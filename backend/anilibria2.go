package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

// indexSchemaVersion — версия схемы индекса. При несовпадении индекс пересобирается:
// schema 1 — без alternative/shikimori_id, schema 2 — полный набор полей матчинга.
const indexSchemaVersion = 2

type aniLibriaEpisode struct {
	Ordinal  float64 `json:"ordinal"`
	HLS480   string  `json:"hls_480"`
	HLS720   string  `json:"hls_720"`
	HLS1080  string  `json:"hls_1080"`
	Duration int     `json:"duration"`
}

type aniLibriaRelease struct {
	ID   int `json:"id"`
	Name struct {
		Main string `json:"main"`
	} `json:"name"`
	Episodes []aniLibriaEpisode `json:"episodes"`
}

// aniLibria — внешний балансер AniLibria (публичный API aniliberty.top).
// Ссылки HLS не привязаны к IP клиента, поэтому резолв на сервере допустим.
type aniLibria struct {
	cfg    config
	cache  *responseCache
	client *http.Client

	mu            sync.RWMutex
	items         []aniLibriaIndexItem
	prepared      []preparedItem
	indexSchema   int
	builtAt       time.Time
	crawlFailedAt time.Time
	buildErr      string
	building      bool
	detailTTL     time.Duration
}

func newAniLibria(cfg config, cache *responseCache) *aniLibria {
	return &aniLibria{
		cfg:   cfg,
		cache: cache,
		client: &http.Client{
			Timeout: 40 * time.Second,
			Transport: &http.Transport{
				MaxIdleConns:        16,
				MaxIdleConnsPerHost: 8,
				IdleConnTimeout:     60 * time.Second,
			},
		},
		detailTTL: 30 * time.Minute,
	}
}

func (a *aniLibria) indexPath() string {
	return filepath.Join(a.cfg.DataDir, "anilibria-index.json")
}

func (a *aniLibria) stats() map[string]any {
	a.mu.RLock()
	defer a.mu.RUnlock()
	out := map[string]any{"releases": len(a.items), "schema": a.indexSchema}
	if !a.builtAt.IsZero() {
		out["built_at"] = a.builtAt.Format(time.RFC3339)
		out["age_min"] = int(time.Since(a.builtAt).Minutes())
	}
	if a.buildErr != "" {
		out["error"] = a.buildErr
	}
	if a.building {
		out["building"] = true
	}
	return out
}

func (a *aniLibria) ensureIndex(ctx context.Context, force bool) error {
	a.mu.Lock()
	if a.building {
		a.mu.Unlock()
		return nil
	}
	if !force && len(a.items) > 0 {
		freshEnough := a.indexSchema >= indexSchemaVersion && time.Since(a.builtAt) < 24*time.Hour
		// Не долбим каталог повторным краулом, если недавно уже не получилось:
		// работаем на индексе с диска, пока не истечёт пауза.
		failedRecently := !a.crawlFailedAt.IsZero() && time.Since(a.crawlFailedAt) < 10*time.Minute
		if freshEnough || failedRecently {
			a.mu.Unlock()
			return nil
		}
	}
	a.building = true
	a.mu.Unlock()

	defer func() {
		a.mu.Lock()
		a.building = false
		a.mu.Unlock()
	}()

	items, schema, builtAt, loadErr := a.loadIndexFromDisk()
	needCrawl := force || loadErr != nil || len(items) == 0 || schema < indexSchemaVersion
	crawlOK := false
	if needCrawl {
		crawled, err := a.crawlIndex(ctx)
		if err != nil {
			a.mu.Lock()
			a.buildErr = err.Error()
			a.crawlFailedAt = time.Now()
			a.mu.Unlock()
			if len(items) == 0 {
				return err
			}
			log.Printf("[index] обновление не удалось (%v), использую индекс с диска: %d релизов", err, len(items))
			builtAt = time.Now()
		} else {
			items = crawled
			schema = indexSchemaVersion
			builtAt = time.Now()
			crawlOK = true
			a.saveIndexToDisk(items)
		}
	}

	prepared := make([]preparedItem, len(items))
	for i := range items {
		prepared[i] = newPreparedItem(items[i])
	}

	a.mu.Lock()
	a.items = items
	a.prepared = prepared
	a.builtAt = builtAt
	a.indexSchema = schema
	a.buildErr = ""
	if crawlOK {
		a.crawlFailedAt = time.Time{}
	}
	a.mu.Unlock()
	log.Printf("[index] AniLibria: %d релизов (схема %d)", len(items), schema)
	return nil
}

func (a *aniLibria) refreshLoop(ctx context.Context, every time.Duration) {
	ticker := time.NewTicker(every)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if err := a.ensureIndex(ctx, true); err != nil {
				log.Printf("[index] refresh: %v", err)
			}
		}
	}
}

func (a *aniLibria) loadIndexFromDisk() ([]aniLibriaIndexItem, int, time.Time, error) {
	data, err := os.ReadFile(a.indexPath())
	if err != nil {
		return nil, 0, time.Time{}, err
	}
	var payload struct {
		Schema  int                  `json:"schema"`
		BuiltAt time.Time            `json:"built_at"`
		Items   []aniLibriaIndexItem `json:"items"`
	}
	if err := json.Unmarshal(data, &payload); err != nil {
		return nil, 0, time.Time{}, err
	}
	if len(payload.Items) == 0 {
		return nil, 0, time.Time{}, fmt.Errorf("пустой индекс")
	}
	return payload.Items, payload.Schema, payload.BuiltAt, nil
}

func (a *aniLibria) saveIndexToDisk(items []aniLibriaIndexItem) {
	payload := map[string]any{"schema": indexSchemaVersion, "built_at": time.Now(), "items": items}
	data, err := json.Marshal(payload)
	if err != nil {
		return
	}
	tmp := a.indexPath() + ".tmp"
	if err := os.WriteFile(tmp, data, 0o644); err != nil {
		log.Printf("[index] запись: %v", err)
		return
	}
	_ = os.Rename(tmp, a.indexPath())
}

func (a *aniLibria) crawlIndex(ctx context.Context) ([]aniLibriaIndexItem, error) {
	const perPage = 50
	items := make([]aniLibriaIndexItem, 0, 4096)
	page := 1
	totalPages := 1
	for page <= totalPages {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		url := fmt.Sprintf("%s/api/v1/anime/catalog/releases?limit=%d&page=%d", a.cfg.AniLibriaBase, perPage, page)
		var catalog aniLibriaCatalogPage
		if err := fetchJSON(a.client, url, &catalog); err != nil {
			return nil, fmt.Errorf("страница %d: %w", page, err)
		}
		if page == 1 && catalog.Meta.Pagination.TotalPages > 0 {
			totalPages = catalog.Meta.Pagination.TotalPages
		}
		for _, item := range catalog.Data {
			items = append(items, item.toIndexItem())
		}
		page++
		time.Sleep(120 * time.Millisecond)
	}
	if len(items) == 0 {
		return nil, fmt.Errorf("каталог пуст")
	}
	return items, nil
}

// yaniDetails — нужные для матчинга поля ответа api.yani.tv/anime/{id}.
type yaniDetails struct {
	ID          int      `json:"anime_id"`
	Title       string   `json:"title"`
	Year        int      `json:"year"`
	OtherTitles []string `json:"other_titles"`
	RemoteIDs   struct {
		MALID          int    `json:"myanimelist_id"`
		ShikimoriID    int    `json:"shikimori_id"`
		AniLibriaAlias string `json:"anilibria_alias"`
	} `json:"remote_ids"`
	Episodes struct {
		Count int `json:"count"`
	} `json:"episodes"`
}

func (a *aniLibria) yaniDetails(animeID int) (*yaniDetails, error) {
	key := "details:" + strconv.Itoa(animeID)
	if entry, ok := a.cache.get(key); ok {
		var details yaniDetails
		if err := json.Unmarshal(entry.body, &details); err == nil {
			return &details, nil
		}
	}
	url := fmt.Sprintf("%s/anime/%d", a.cfg.YaniBase, animeID)
	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("X-Application", a.cfg.YaniAppToken)
	req.Header.Set("Lang", "ru")
	req.Header.Set("Accept", "application/json")
	resp, err := a.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("yani /anime/%d: HTTP %d", animeID, resp.StatusCode)
	}
	var envelope struct {
		Response yaniDetails `json:"response"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&envelope); err != nil {
		return nil, err
	}
	raw, _ := json.Marshal(envelope.Response)
	a.cache.put(key, cacheEntry{body: raw, status: 200, contentTy: "application/json"}, 10*time.Minute)
	return &envelope.Response, nil
}

// matchOutcome — результат сопоставления тайтла yani с релизом AniLibria.
type matchOutcome struct {
	Details    *yaniDetails
	Item       aniLibriaIndexItem
	Found      bool
	MatchedBy  string // alias | mal_id | shikimori_id | search | title
	Candidates []matchCandidate
}

// matchCacheValue — сериализованный результат матчинга для кэша.
type matchCacheValue struct {
	Found      bool               `json:"found"`
	Item       aniLibriaIndexItem `json:"item,omitempty"`
	MatchedBy  string             `json:"matched_by,omitempty"`
	Candidates []matchCandidate   `json:"candidates,omitempty"`
}

func noReleaseError(details *yaniDetails) error {
	return fmt.Errorf("релиз AniLibria не найден для «%s» (MAL %d)", details.Title, details.RemoteIDs.MALID)
}

// match сопоставляет тайтл yani с релизом AniLibria в порядке:
// alias из yani → MAL id → Shikimori id → поиск по API → название+год.
func (a *aniLibria) match(animeID int) (*matchOutcome, error) {
	details, err := a.yaniDetails(animeID)
	if err != nil {
		return nil, err
	}
	out := &matchOutcome{Details: details}

	cacheKey := "match:" + strconv.Itoa(animeID)
	if entry, ok := a.cache.get(cacheKey); ok {
		var mc matchCacheValue
		if json.Unmarshal(entry.body, &mc) == nil {
			out.Found = mc.Found
			out.Item = mc.Item
			out.MatchedBy = mc.MatchedBy
			out.Candidates = mc.Candidates
			if !mc.Found {
				return out, noReleaseError(details)
			}
			return out, nil
		}
	}

	a.mu.RLock()
	items := a.prepared
	a.mu.RUnlock()
	if len(items) == 0 {
		return out, fmt.Errorf("индекс AniLibria ещё не собран")
	}

	rawTitles := yaniTitleVariants(details)
	needles := prepareNeedles(rawTitles)
	ranked := rankPrepared(items, needles, details.Year, 3)
	out.Candidates = candidatesFrom(ranked)

	// 1. alias из remote_ids: самый точный указатель на релиз AniLibria.
	alias := strings.TrimSpace(details.RemoteIDs.AniLibriaAlias)
	if alias != "" {
		if item, ok := findByAlias(items, alias); ok {
			out.Item, out.Found, out.MatchedBy = item, true, "alias"
		} else if item, ok := a.releaseItemByAlias(alias); ok {
			out.Item, out.Found, out.MatchedBy = item, true, "alias"
		}
	}

	// 2. MAL id.
	if !out.Found {
		if item, ok := findByMalID(items, details.RemoteIDs.MALID); ok {
			out.Item, out.Found, out.MatchedBy = item, true, "mal_id"
		}
	}

	// 3. Shikimori id — у части релизов AniLibria не заполнен MAL id.
	if !out.Found {
		if item, ok := findByShikimoriID(items, details.RemoteIDs.ShikimoriID); ok {
			out.Item, out.Found, out.MatchedBy = item, true, "shikimori_id"
		}
	}

	// 4. Поиск по API AniLibria: спасает случаи, когда локализованные названия
	// у yani и AniLibria различаются и id не совпадают.
	if !out.Found {
		if item, ok := a.matchBySearch(rawTitles, needles, details.Year); ok {
			// Если обычный матчинг по названию уверенно находит другой релиз —
			// доверяем ему, поиск оставляем только как уточнение.
			if len(ranked) == 0 || ranked[0].item.ID == item.ID || ranked[0].score < titleThreshold(ranked[0].item.Year, details.Year) {
				out.Item, out.Found, out.MatchedBy = item, true, "search"
			}
		}
	}

	// 5. Нормализованное название + год (с вариантом «сезон/часть»).
	if !out.Found && len(ranked) > 0 {
		top := ranked[0]
		if top.score >= titleThreshold(top.item.Year, details.Year) {
			out.Item, out.Found, out.MatchedBy = top.item, true, "title"
		}
	}

	payload := matchCacheValue{Found: out.Found, Item: out.Item, MatchedBy: out.MatchedBy, Candidates: out.Candidates}
	if data, err := json.Marshal(payload); err == nil {
		a.cache.put(cacheKey, cacheEntry{body: data, status: 200, contentTy: "application/json"}, 10*time.Minute)
	}
	if !out.Found {
		return out, noReleaseError(details)
	}
	return out, nil
}

// yaniTitleVariants — варианты названия из yani: основное и альтернативные.
func yaniTitleVariants(d *yaniDetails) []string {
	out := make([]string, 0, 1+len(d.OtherTitles))
	if t := strings.TrimSpace(d.Title); t != "" {
		out = append(out, t)
	}
	for _, t := range d.OtherTitles {
		if t = strings.TrimSpace(t); t != "" {
			out = append(out, t)
		}
	}
	if len(out) > 8 {
		out = out[:8]
	}
	return out
}

// searchReleases ищет релизы через публичный API AniLibria (f[search]).
func (a *aniLibria) searchReleases(query string) ([]aniLibriaIndexItem, error) {
	key := "al-search:" + strings.ToLower(strings.TrimSpace(query))
	if entry, ok := a.cache.get(key); ok {
		var items []aniLibriaIndexItem
		if err := json.Unmarshal(entry.body, &items); err == nil {
			return items, nil
		}
	}
	params := url.Values{}
	params.Set("f[search]", query)
	params.Set("limit", "10")
	u := a.cfg.AniLibriaBase + "/api/v1/anime/catalog/releases?" + params.Encode()
	var page aniLibriaCatalogPage
	if err := fetchJSON(a.client, u, &page); err != nil {
		return nil, fmt.Errorf("поиск AniLibria: %w", err)
	}
	items := make([]aniLibriaIndexItem, 0, len(page.Data))
	for _, e := range page.Data {
		items = append(items, e.toIndexItem())
	}
	if data, err := json.Marshal(items); err == nil {
		a.cache.put(key, cacheEntry{body: data, status: 200, contentTy: "application/json"}, 30*time.Minute)
	}
	return items, nil
}

// releaseItemByAlias резолвит релиз напрямую по alias (страховка от устаревшего индекса).
func (a *aniLibria) releaseItemByAlias(alias string) (aniLibriaIndexItem, bool) {
	alias = strings.TrimSpace(alias)
	if alias == "" {
		return aniLibriaIndexItem{}, false
	}
	key := "al-release-alias:" + strings.ToLower(alias)
	if entry, ok := a.cache.get(key); ok {
		var item aniLibriaIndexItem
		if err := json.Unmarshal(entry.body, &item); err == nil {
			return item, item.ID > 0
		}
	}
	params := url.Values{}
	params.Set("include", "id,name.main,name.english,name.alternative,year,alias,mal.id,shikimori.id")
	u := fmt.Sprintf("%s/api/v1/anime/releases/%s?%s", a.cfg.AniLibriaBase, url.PathEscape(alias), params.Encode())
	var entry aniLibriaCatalogEntry
	item := aniLibriaIndexItem{}
	ttl := 30 * time.Minute
	if err := fetchJSON(a.client, u, &entry); err != nil {
		ttl = 5 * time.Minute // отрицательный кэш, чтобы не долбить API на каждый запрос
	} else {
		item = entry.toIndexItem()
	}
	if data, err := json.Marshal(item); err == nil {
		a.cache.put(key, cacheEntry{body: data, status: 200, contentTy: "application/json"}, ttl)
	}
	return item, item.ID > 0
}

// matchBySearch пробует найти релиз через поиск AniLibria и принимает результат,
// только если он достаточно уверенный (иначе — обычный матчинг по названию).
func (a *aniLibria) matchBySearch(rawTitles []string, needles []preparedTitle, yaniYear int) (aniLibriaIndexItem, bool) {
	queries := chooseSearchQueries(rawTitles)
	if len(queries) == 0 || len(needles) == 0 {
		return aniLibriaIndexItem{}, false
	}
	pool := make([]aniLibriaIndexItem, 0, 16)
	seen := make(map[int]bool)
	for _, q := range queries {
		items, err := a.searchReleases(q)
		if err != nil {
			log.Printf("[search] %q: %v", q, err)
			continue
		}
		for _, it := range items {
			if it.ID <= 0 || seen[it.ID] {
				continue
			}
			seen[it.ID] = true
			pool = append(pool, it)
		}
	}
	if len(pool) == 0 {
		return aniLibriaIndexItem{}, false
	}
	prepared := make([]preparedItem, len(pool))
	for i := range pool {
		prepared[i] = newPreparedItem(pool[i])
	}
	ranked := rankPrepared(prepared, needles, yaniYear, 0)
	if len(ranked) == 0 {
		return aniLibriaIndexItem{}, false
	}
	top := ranked[0]
	yearCompatible := yaniYear <= 0 || top.item.Year <= 0 || absInt(top.item.Year-yaniYear) <= 1
	if top.score >= searchAcceptScore || (len(pool) <= searchNarrowResults && yearCompatible && top.score >= searchNarrowScore) {
		return top.item, true
	}
	if yearCompatible && top.score >= searchRelaxedScore {
		second := 0.0
		if len(ranked) > 1 {
			second = ranked[1].score
		}
		if top.score-second >= searchDominance {
			return top.item, true
		}
	}
	return aniLibriaIndexItem{}, false
}

// chooseSearchQueries выбирает до четырёх запросов для поиска AniLibria:
// основное название и до трёх альтернативных (сначала латиница/английские).
func chooseSearchQueries(variants []string) []string {
	if len(variants) == 0 {
		return nil
	}
	out := []string{strings.TrimSpace(variants[0])}
	seen := map[string]bool{strings.ToLower(out[0]): true}
	add := func(v string) {
		v = strings.TrimSpace(v)
		key := strings.ToLower(v)
		if v == "" || seen[key] {
			return
		}
		seen[key] = true
		out = append(out, v)
	}
	hasLatin := func(s string) bool {
		for _, r := range s {
			if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') {
				return true
			}
		}
		return false
	}
	for _, v := range variants[1:] {
		if len(out) >= 4 {
			break
		}
		if !hasLatin(v) || len([]rune(v)) < 4 {
			continue
		}
		add(v)
	}
	for _, v := range variants[1:] {
		if len(out) >= 4 {
			break
		}
		add(v)
	}
	return out
}

func (a *aniLibria) release(releaseID int) (*aniLibriaRelease, error) {
	key := "anilibria:release:" + strconv.Itoa(releaseID)
	if entry, ok := a.cache.get(key); ok {
		var release aniLibriaRelease
		if err := json.Unmarshal(entry.body, &release); err == nil {
			return &release, nil
		}
	}
	include := "id,name.main,episodes.ordinal,episodes.duration,episodes.hls_480,episodes.hls_720,episodes.hls_1080"
	url := fmt.Sprintf("%s/api/v1/anime/releases/%d?include=%s", a.cfg.AniLibriaBase, releaseID, include)
	var release aniLibriaRelease
	if err := fetchJSON(a.client, url, &release); err != nil {
		return nil, err
	}
	raw, _ := json.Marshal(release)
	a.cache.put(key, cacheEntry{body: raw, status: 200, contentTy: "application/json"}, a.detailTTL)
	return &release, nil
}

type sourceQuality struct {
	Quality string `json:"quality"`
	URL     string `json:"url"`
}

type sourceResponse struct {
	Source    string            `json:"source"`
	Dubbing   string            `json:"dubbing"`
	AnimeID   int               `json:"anime_id"`
	ReleaseID int               `json:"release_id"`
	Title     string            `json:"title"`
	Episode   string            `json:"episode"`
	Ordinal   string            `json:"ordinal,omitempty"`
	URL       string            `json:"url"`
	Qualities []sourceQuality   `json:"qualities"`
	Headers   map[string]string `json:"headers,omitempty"`
}

func (a *aniLibria) handleSources(w http.ResponseWriter, r *http.Request) {
	animeID, err := strconv.Atoi(r.PathValue("animeId"))
	if err != nil || animeID <= 0 {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "bad anime id"})
		return
	}
	episodeInt, err := strconv.Atoi(r.PathValue("episode"))
	if err != nil || episodeInt <= 0 {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "bad episode"})
		return
	}

	if err := a.ensureIndex(r.Context(), false); err != nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": err.Error()})
		return
	}
	outcome, err := a.match(animeID)
	if err != nil {
		status := http.StatusNotFound
		if outcome == nil {
			status = http.StatusBadGateway
		}
		writeJSON(w, status, map[string]string{"error": err.Error()})
		return
	}
	item := outcome.Item
	release, err := a.release(item.ID)
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]string{"error": "AniLibria: " + err.Error()})
		return
	}

	var found *aniLibriaEpisode
	for i := range release.Episodes {
		ep := &release.Episodes[i]
		if int(ep.Ordinal) == episodeInt || strings.HasPrefix(epOrdinalString(ep.Ordinal), strconv.Itoa(episodeInt)) {
			found = ep
			break
		}
	}
	// Некоторые релизы AniLibria нумеруют серии сквозным номером (Наруто: 370, 371, ...),
	// а yani считает серии с единицы. Тогда берём серию по позиции в релизе.
	if found == nil && episodeInt >= 1 && episodeInt <= len(release.Episodes) {
		found = &release.Episodes[episodeInt-1]
	}
	// Некоторые релизы AniLibria нумеруют серии сквозным номером (Наруто: 370, 371, ...),
	// а yani считает серии с единицы. Тогда берём серию по позиции в релизе.
	if found == nil && episodeInt >= 1 && episodeInt <= len(release.Episodes) {
		found = &release.Episodes[episodeInt-1]
	}
	if found == nil {
		writeJSON(w, http.StatusNotFound, map[string]string{
			"error": fmt.Sprintf("в релизе %d нет серии %d", release.ID, episodeInt),
		})
		return
	}

	qualities := make([]sourceQuality, 0, 3)
	best := ""
	for _, q := range []struct{ name, url string }{
		{"1080p", found.HLS1080},
		{"720p", found.HLS720},
		{"480p", found.HLS480},
	} {
		if q.url == "" {
			continue
		}
		url := preferRuCDN(q.url)
		qualities = append(qualities, sourceQuality{Quality: q.name, URL: url})
		if best == "" {
			best = url
		}
	}
	if best == "" {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "AniLibria не отдала ссылки на поток"})
		return
	}
	sortQualities(qualities)

	writeJSON(w, http.StatusOK, sourceResponse{
		Source:    "anilibria",
		Dubbing:   "AniLibria",
		AnimeID:   animeID,
		ReleaseID: release.ID,
		Title:     release.Name.Main,
		Episode:   strconv.Itoa(episodeInt),
		Ordinal:   epOrdinalString(found.Ordinal),
		URL:       best,
		Qualities: qualities,
	})
}

func (a *aniLibria) handleReleaseInfo(w http.ResponseWriter, r *http.Request) {
	animeID, err := strconv.Atoi(r.PathValue("animeId"))
	if err != nil || animeID <= 0 {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "bad anime id"})
		return
	}
	if err := a.ensureIndex(r.Context(), false); err != nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": err.Error()})
		return
	}
	outcome, err := a.match(animeID)
	if err != nil {
		status := http.StatusNotFound
		if outcome == nil {
			status = http.StatusBadGateway
		}
		payload := map[string]any{"error": err.Error()}
		if outcome != nil {
			payload["yani_alias"] = strings.TrimSpace(outcome.Details.RemoteIDs.AniLibriaAlias)
			payload["matched_by"] = ""
			payload["candidates"] = candidatesOrEmpty(outcome.Candidates)
		}
		writeJSON(w, status, payload)
		return
	}
	item := outcome.Item
	release, err := a.release(item.ID)
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]string{"error": err.Error()})
		return
	}
	type ep struct {
		Ordinal  string `json:"ordinal"`
		Duration int    `json:"duration,omitempty"`
	}
	episodes := make([]ep, 0, len(release.Episodes))
	for _, e := range release.Episodes {
		episodes = append(episodes, ep{Ordinal: epOrdinalString(e.Ordinal), Duration: e.Duration})
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"source":     "anilibria",
		"anime_id":   animeID,
		"release_id": release.ID,
		"title":      release.Name.Main,
		"yani_title": outcome.Details.Title,
		"yani_alias": strings.TrimSpace(outcome.Details.RemoteIDs.AniLibriaAlias),
		"matched_by": outcome.MatchedBy,
		"candidates": candidatesOrEmpty(outcome.Candidates),
		"episodes":   episodes,
	})
}

func candidatesOrEmpty(list []matchCandidate) []matchCandidate {
	if list == nil {
		return []matchCandidate{}
	}
	return list
}

func preferRuCDN(url string) string {
	const generic = "cache.libria.fun"
	const ru = "cache-rfn.libria.fun"
	if strings.HasPrefix(url, "https://"+generic+"/") {
		return strings.Replace(url, generic, ru, 1)
	}
	return url
}

func sortQualities(list []sourceQuality) {
	order := map[string]int{"480p": 0, "720p": 1, "1080p": 2}
	for i := 0; i < len(list); i++ {
		for j := i + 1; j < len(list); j++ {
			if order[list[j].Quality] < order[list[i].Quality] {
				list[i], list[j] = list[j], list[i]
			}
		}
	}
}
