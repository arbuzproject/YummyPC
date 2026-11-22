package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"regexp"
	"sort"
	"strconv"
	"strings"
)

// aniLibriaIndexItem — запись индекса AniLibria (поля, по которым идёт матчинг).
type aniLibriaIndexItem struct {
	ID          int    `json:"id"`
	MalID       int    `json:"mal_id,omitempty"`
	ShikimoriID int    `json:"shikimori_id,omitempty"`
	Year        int    `json:"year,omitempty"`
	Main        string `json:"main,omitempty"`
	English     string `json:"english,omitempty"`
	Alternative string `json:"alternative,omitempty"`
	Alias       string `json:"alias,omitempty"`
}

// aniLibriaCatalogName — названия релиза в ответе API каталога.
type aniLibriaCatalogName struct {
	Main        string `json:"main"`
	English     string `json:"english"`
	Alternative string `json:"alternative"`
}

// aniLibriaCatalogEntry — один релиз в ответе /api/v1/anime/catalog/releases.
type aniLibriaCatalogEntry struct {
	ID    int                  `json:"id"`
	Year  int                  `json:"year"`
	Name  aniLibriaCatalogName `json:"name"`
	Alias string               `json:"alias"`
	Mal   struct {
		ID int `json:"id"`
	} `json:"mal"`
	Shikimori struct {
		ID int `json:"id"`
	} `json:"shikimori"`
}

func (e aniLibriaCatalogEntry) toIndexItem() aniLibriaIndexItem {
	return aniLibriaIndexItem{
		ID:          e.ID,
		MalID:       e.Mal.ID,
		ShikimoriID: e.Shikimori.ID,
		Year:        e.Year,
		Main:        e.Name.Main,
		English:     e.Name.English,
		Alternative: e.Name.Alternative,
		Alias:       e.Alias,
	}
}

// aniLibriaCatalogPage — страница каталога AniLibria.
type aniLibriaCatalogPage struct {
	Data []aniLibriaCatalogEntry `json:"data"`
	Meta struct {
		Pagination struct {
			TotalPages int `json:"total_pages"`
			Total      int `json:"total"`
		} `json:"pagination"`
	} `json:"meta"`
}

// matchCandidate — кандидат для отладочного вывода info-ручки.
type matchCandidate struct {
	ReleaseID int     `json:"release_id"`
	Title     string  `json:"title"`
	Alias     string  `json:"alias,omitempty"`
	Year      int     `json:"year,omitempty"`
	MalID     int     `json:"mal_id,omitempty"`
	Score     float64 `json:"score"`
}

const (
	// titleMinScore — порог для матчинга по названию+году, когда известны оба года.
	titleMinScore = 0.55
	// titleMinScoreNoYear — более строгий порог, когда год yani или релиза неизвестен.
	titleMinScoreNoYear = 0.62
	// seasonVariantScore — счёт для совпадения после отбрасывания «N сезон»/«часть N».
	seasonVariantScore = 0.90

	// Поиск через API AniLibria: последняя попытка для случаев, когда названия
	// у yani и AniLibria локализованы по-разному.
	searchAcceptScore   = 0.62 // уверенное совпадение — принимаем сразу
	searchRelaxedScore  = 0.60 // «доминирующий» первый результат поиска
	searchNarrowScore   = 0.60 // поиск вернул 1–2 результата
	searchNarrowResults = 2
	searchDominance     = 0.15 // отрыв лучшего результата от следующего
)

// preparedTitle — заранее нормализованное название (индекс или yani).
type preparedTitle struct {
	norm     string   // нормализованное название
	stripped string   // то же без «N сезон»/«часть N»
	markers  []string // номера сезонов/частей, найденные в названии
}

// preparedItem — запись индекса с предвычисленными названиями.
type preparedItem struct {
	item   aniLibriaIndexItem
	titles []preparedTitle
}

// scoredItem — релиз с итоговым счётом.
type scoredItem struct {
	item  aniLibriaIndexItem
	score float64
}

// newPreparedItem предвычисляет нормализованные варианты названия релиза.
func newPreparedItem(item aniLibriaIndexItem) preparedItem {
	variants := []string{item.Main, item.English, item.Alternative, item.Alias}
	seen := make(map[string]bool, len(variants))
	titles := make([]preparedTitle, 0, len(variants))
	for _, v := range variants {
		n := normalizeTitle(v)
		if n == "" || seen[n] {
			continue
		}
		seen[n] = true
		stripped, markers := stripSeasonPart(n)
		titles = append(titles, preparedTitle{norm: n, stripped: stripped, markers: markers})
	}
	return preparedItem{item: item, titles: titles}
}

// prepareNeedles нормализует названия тайтла yani (title + other_titles).
func prepareNeedles(variants []string) []preparedTitle {
	seen := make(map[string]bool, len(variants))
	out := make([]preparedTitle, 0, len(variants))
	for _, v := range variants {
		n := normalizeTitle(v)
		if n == "" || seen[n] {
			continue
		}
		seen[n] = true
		stripped, markers := stripSeasonPart(n)
		out = append(out, preparedTitle{norm: n, stripped: stripped, markers: markers})
	}
	return out
}

// titleMatchScore — лучший счёт релиза по всем вариантам названия.
func titleMatchScore(p preparedItem, needles []preparedTitle, yaniYear int) float64 {
	best := 0.0
	for _, needle := range needles {
		nw := strings.Fields(needle.norm)
		if len(nw) == 0 {
			continue
		}
		for _, cand := range p.titles {
			cw := strings.Fields(cand.norm)
			if len(cw) == 0 {
				continue
			}
			if s := titleScore(nw, cw); s > best {
				best = s
			}
			// Вариант «сезон/часть»: у AniLibria название часто без «2 сезон»/«часть 2».
			// Применяем только при согласованном годе: иначе это, скорее всего, другой сезон.
			if needle.stripped != "" && needle.stripped != needle.norm &&
				cand.stripped != "" && needle.stripped == cand.stripped &&
				markersCompatible(needle.markers, cand.markers) &&
				rescueYearCompatible(p.item.Year, yaniYear) && seasonVariantScore > best {
				best = seasonVariantScore
			}
		}
	}
	return best
}

// rescueYearCompatible — можно ли применять мягкий вариант «сезон/часть».
// Требуем известный год с обеих сторон и расхождение не больше года:
// иначе легко привязать «2 сезон» к базовому релизу первого сезона.
func rescueYearCompatible(releaseYear, yaniYear int) bool {
	if releaseYear <= 0 || yaniYear <= 0 {
		return false
	}
	return absInt(releaseYear-yaniYear) <= 1
}

// markersCompatible проверяет, что номера сезонов/частей не противоречат друг другу.
// Если хотя бы у одной стороны номера неизвестны — считаем совместимыми.
func markersCompatible(a, b []string) bool {
	if len(a) == 0 || len(b) == 0 {
		return true
	}
	set := make(map[string]bool, len(a))
	for _, v := range a {
		set[v] = true
	}
	for _, v := range b {
		if !set[v] {
			return false
		}
	}
	return true
}

// applyYearAdjust добавляет бонус за совпадение года релиза и года yani.
// Большая разница лет — почти наверняка другой сезон/ремейк, поэтому штраф строгий.
func applyYearAdjust(score float64, releaseYear, yaniYear int) float64 {
	if releaseYear <= 0 || yaniYear <= 0 {
		return score
	}
	switch diff := absInt(releaseYear - yaniYear); {
	case diff == 0:
		return score + 0.12
	case diff == 1:
		return score + 0.04
	case diff == 2:
		return score - 0.12
	case diff <= 5:
		return score - 0.30
	default:
		return score - 0.45
	}
}

// titleThreshold — порог принятия для матчинга по названию.
func titleThreshold(releaseYear, yaniYear int) float64 {
	if releaseYear <= 0 || yaniYear <= 0 {
		return titleMinScoreNoYear
	}
	return titleMinScore
}

// rankPrepared считает счёт для всех релизов и возвращает top-limit по убыванию.
// limit <= 0 — вернуть все.
func rankPrepared(items []preparedItem, needles []preparedTitle, yaniYear, limit int) []scoredItem {
	if len(items) == 0 || len(needles) == 0 {
		return nil
	}
	ranked := make([]scoredItem, 0, len(items))
	for i := range items {
		base := titleMatchScore(items[i], needles, yaniYear)
		if base <= 0 {
			continue
		}
		score := applyYearAdjust(base, items[i].item.Year, yaniYear)
		if score <= 0 {
			continue
		}
		ranked = append(ranked, scoredItem{item: items[i].item, score: score})
	}
	sort.SliceStable(ranked, func(i, j int) bool {
		if ranked[i].score == ranked[j].score {
			return ranked[i].item.ID < ranked[j].item.ID
		}
		return ranked[i].score > ranked[j].score
	})
	if limit > 0 && len(ranked) > limit {
		ranked = ranked[:limit]
	}
	return ranked
}

// candidatesFrom конвертирует ранжированные релизы в отладочный формат.
func candidatesFrom(ranked []scoredItem) []matchCandidate {
	if len(ranked) == 0 {
		return nil
	}
	out := make([]matchCandidate, 0, len(ranked))
	for _, r := range ranked {
		out = append(out, matchCandidate{
			ReleaseID: r.item.ID,
			Title:     r.item.Main,
			Alias:     r.item.Alias,
			Year:      r.item.Year,
			MalID:     r.item.MalID,
			Score:     roundScore(r.score),
		})
	}
	return out
}

func roundScore(v float64) float64 {
	if v > 1 {
		v = 1
	}
	if v < 0 {
		v = 0
	}
	return float64(int(v*1000+0.5)) / 1000
}

// aliasKey приводит alias к сравнимому виду (нижний регистр, только буквы/цифры через дефис).
func aliasKey(s string) string {
	s = strings.ToLower(strings.TrimSpace(s))
	var b strings.Builder
	prevDash := false
	for _, r := range s {
		switch {
		case r >= 'a' && r <= 'z', r >= '0' && r <= '9', r >= 'а' && r <= 'я', r == 'ё':
			b.WriteRune(r)
			prevDash = false
		default:
			if !prevDash && b.Len() > 0 {
				b.WriteByte('-')
				prevDash = true
			}
		}
	}
	return strings.Trim(b.String(), "-")
}

// findByAlias ищет релиз по alias из remote_ids yani.
func findByAlias(items []preparedItem, alias string) (aniLibriaIndexItem, bool) {
	key := aliasKey(alias)
	if key == "" {
		return aniLibriaIndexItem{}, false
	}
	for i := range items {
		if aliasKey(items[i].item.Alias) == key {
			return items[i].item, true
		}
	}
	return aniLibriaIndexItem{}, false
}

// findByMalID ищет релиз по MAL id.
func findByMalID(items []preparedItem, malID int) (aniLibriaIndexItem, bool) {
	if malID <= 0 {
		return aniLibriaIndexItem{}, false
	}
	for i := range items {
		if items[i].item.MalID == malID {
			return items[i].item, true
		}
	}
	return aniLibriaIndexItem{}, false
}

// findByShikimoriID ищет релиз по Shikimori id (у части релизов AniLibria нет MAL id).
func findByShikimoriID(items []preparedItem, shikimoriID int) (aniLibriaIndexItem, bool) {
	if shikimoriID <= 0 {
		return aniLibriaIndexItem{}, false
	}
	for i := range items {
		if items[i].item.ShikimoriID == shikimoriID {
			return items[i].item, true
		}
	}
	return aniLibriaIndexItem{}, false
}

// normalizeTitle приводит название к списку значимых слов: нижний регистр,
// ё→е, пунктуация и стоп-слова убираются, лишние пробелы схлопываются.
func normalizeTitle(s string) string {
	s = strings.ToLower(s)
	replacer := strings.NewReplacer(
		"ё", "е", "«", " ", "»", " ", "\"", " ", "'", " ",
		"(", " ", ")", " ", "[", " ", "]", " ", "{", " ", "}", " ",
		"–", " ", "—", " ", "-", " ", ":", " ", ";", " ", ",", " ", ".", " ",
		"!", " ", "?", " ", "/", " ", "\\", " ", "|", " ", "_", " ", "+", " ",
		"…", " ", "&", " ", "%", " ", "*", " ", "=", " ", "#", " ", "@", " ",
		"~", " ", "^", " ", "№", " ",
	)
	s = replacer.Replace(s)
	fields := strings.Fields(s)
	stop := map[string]bool{
		"the": true, "a": true, "an": true,
		"of": true, "to": true, "in": true, "on": true, "at": true, "for": true,
		"and": true, "or": true, "with": true,
		"и": true, "в": true, "на": true, "с": true, "о": true, "об": true,
		"из": true, "по": true, "для": true, "же": true, "ли": true,
	}
	out := fields[:0]
	for _, f := range fields {
		if stop[f] {
			continue
		}
		out = append(out, f)
	}
	return strings.Join(out, " ")
}

// structuralWords — служебные слова сезонов/частей: сами по себе не несут смысла
// и не должны давать совпадение (иначе любые «... 2 сезон» матчатся друг с другом).
var structuralWords = map[string]bool{
	"сезон": true, "сезона": true, "сезоне": true, "сезоны": true, "сезонов": true,
	"season": true, "seasons": true, "part": true, "часть": true, "части": true,
	"курс": true, "course": true,
}

// contentWords убирает служебные слова сезонов/частей и висячие суффиксы
// порядковых числительных («2-й» после нормализации распадается на «2» и «й»).
func contentWords(words []string) []string {
	out := make([]string, 0, len(words))
	for _, w := range words {
		if structuralWords[w] {
			continue
		}
		if numberSuffixWords[w] && len(out) > 0 && isSeasonNumber(out[len(out)-1]) {
			continue
		}
		out = append(out, w)
	}
	return out
}

// seasonNumbers возвращает множество номеров сезонов/частей в названии.
func seasonNumbers(words []string) map[int]bool {
	out := make(map[int]bool, 2)
	for _, w := range words {
		if n, ok := parseSeasonNumber(w); ok {
			out[n] = true
		}
	}
	return out
}

func sameIntSet(a, b map[int]bool) bool {
	if len(a) != len(b) {
		return false
	}
	for n := range a {
		if !b[n] {
			return false
		}
	}
	return true
}

// strictPrefixConflict — заголовки совпадают по началу, но один длиннее.
// Это почти всегда другой сезон/фильм/спешл с тем же базовым названием.
func strictPrefixConflict(a, b []string) bool {
	shorter, longer := a, b
	if len(a) > len(b) {
		shorter, longer = b, a
	}
	if len(shorter) == 0 || len(longer) <= len(shorter) {
		return false
	}
	for i := range shorter {
		if shorter[i] != longer[i] {
			return false
		}
	}
	return true
}

// titleScore — Jaccard по словам + небольшой бонус за вложенность строк.
// Встроены защиты от ложных совпадений: номера сезонов, префиксы-продолжения,
// короткие названия, совпадение только по числам.
func titleScore(a, b []string) float64 {
	if len(a) == 0 || len(b) == 0 {
		return 0
	}
	if strings.Join(a, " ") == strings.Join(b, " ") {
		return 1
	}
	ca, cb := contentWords(a), contentWords(b)
	if len(ca) == 0 || len(cb) == 0 {
		return 0
	}
	// Одиночное слово без точного совпадения — слишком слабый сигнал.
	if len(ca) < 2 || len(cb) < 2 {
		return 0
	}
	if strictPrefixConflict(ca, cb) {
		return 0
	}
	// Номера сезонов/частей должны совпадать, иначе это другой релиз.
	numsA, numsB := seasonNumbers(ca), seasonNumbers(cb)
	if (len(numsA) > 0) != (len(numsB) > 0) {
		return 0
	}
	if len(numsA) > 0 && !sameIntSet(numsA, numsB) {
		return 0
	}

	setA := make(map[string]bool, len(ca))
	for _, t := range ca {
		setA[t] = true
	}
	setB := make(map[string]bool, len(cb))
	common, commonContent := 0, 0
	for _, t := range cb {
		if setA[t] && !setB[t] {
			common++
			if _, ok := parseSeasonNumber(t); !ok {
				commonContent++
			}
		}
		setB[t] = true
	}
	if common == 0 {
		return 0
	}
	// Совпадение только по числам («3», «2») не считается.
	if commonContent == 0 {
		return 0
	}
	// ca == cb как множества слов — точное совпадение.
	if common == len(setA) && common == len(setB) {
		return 1
	}
	union := len(setA) + len(setB) - common
	jaccard := float64(common) / float64(union)
	joinedA := strings.Join(ca, " ")
	joinedB := strings.Join(cb, " ")
	if strings.Contains(joinedA, joinedB) || strings.Contains(joinedB, joinedA) {
		jaccard += 0.15
	}
	return jaccard
}

var ordinalNumberRe = regexp.MustCompile(`^(\d{1,2})(?:st|nd|rd|th|й|ый|ой|ий|ая)$`)
var romanSeasonRe = regexp.MustCompile(`^(?:ii|iii|iv|v|vi|vii|viii|ix|x)$`)

var seasonMarkerWords = map[string]bool{
	"сезон": true, "сезона": true, "сезоне": true, "сезоны": true, "сезонов": true,
	"season": true, "part": true, "часть": true, "части": true,
	"курс": true, "course": true,
}

var numberSuffixWords = map[string]bool{
	"й": true, "ый": true, "ой": true, "ий": true, "ая": true,
	"st": true, "nd": true, "rd": true, "th": true,
}

// parseSeasonNumber разбирает номер сезона/части: «2», «2nd», «3rd», «2й».
// Числа больше 99 (годы) номерами сезонов не считаются.
func parseSeasonNumber(word string) (int, bool) {
	if romanSeasonRe.MatchString(word) {
		return 0, false
	}
	if n, err := strconv.Atoi(word); err == nil {
		if n >= 1 && n <= 99 {
			return n, true
		}
		return 0, false
	}
	if m := ordinalNumberRe.FindStringSubmatch(word); m != nil {
		if n, err := strconv.Atoi(m[1]); err == nil && n >= 1 && n <= 99 {
			return n, true
		}
	}
	return 0, false
}

func isSeasonNumber(word string) bool {
	if romanSeasonRe.MatchString(word) {
		return true
	}
	_, ok := parseSeasonNumber(word)
	return ok
}

// stripSeasonPart убирает из нормализованного названия конструкции
// «2 сезон», «2-й сезон», «season 2», «часть 2», «part 2» и возвращает
// найденные номера сезонов/частей.
func stripSeasonPart(norm string) (string, []string) {
	words := strings.Fields(norm)
	if len(words) == 0 {
		return "", nil
	}
	out := make([]string, 0, len(words))
	var markers []string
	for i := 0; i < len(words); i++ {
		w := words[i]
		if seasonMarkerWords[w] {
			if i+1 < len(words) && isSeasonNumber(words[i+1]) {
				markers = append(markers, words[i+1])
				i++
				continue
			}
			if len(out) > 0 && isSeasonNumber(out[len(out)-1]) {
				markers = append(markers, out[len(out)-1])
				out = out[:len(out)-1]
			}
			continue
		}
		if numberSuffixWords[w] && len(out) > 0 && isSeasonNumber(out[len(out)-1]) {
			continue
		}
		out = append(out, w)
	}
	return strings.Join(out, " "), markers
}

func absInt(v int) int {
	if v < 0 {
		return -v
	}
	return v
}

func fetchJSON(client *http.Client, url string, out any) error {
	resp, err := client.Get(url)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("HTTP %d: %s", resp.StatusCode, url)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
	if err != nil {
		return err
	}
	return json.Unmarshal(body, out)
}

func epOrdinalString(v float64) string {
	if v == float64(int(v)) {
		return strconv.Itoa(int(v))
	}
	return strconv.FormatFloat(v, 'f', -1, 64)
}
