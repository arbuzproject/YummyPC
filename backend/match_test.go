package main

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func testPrepared(items ...aniLibriaIndexItem) []preparedItem {
	out := make([]preparedItem, len(items))
	for i := range items {
		out[i] = newPreparedItem(items[i])
	}
	return out
}

func testDetails(title, alias string, mal, shikimori, year int) *yaniDetails {
	d := &yaniDetails{Title: title, Year: year}
	d.RemoteIDs.AniLibriaAlias = alias
	d.RemoteIDs.MALID = mal
	d.RemoteIDs.ShikimoriID = shikimori
	return d
}

func TestFindByAlias(t *testing.T) {
	items := testPrepared(
		aniLibriaIndexItem{ID: 1, Alias: "shuiro-no-kamen", MalID: 61999},
		aniLibriaIndexItem{ID: 2, Alias: "naruto-shippuuden-naruto-uragannye-khroniki", MalID: 1735},
	)
	if item, ok := findByAlias(items, "Shuiro-No-Kamen"); !ok || item.ID != 1 {
		t.Fatalf("alias match = %+v, %v", item, ok)
	}
	if _, ok := findByAlias(items, "unknown-alias"); ok {
		t.Fatal("unexpected alias match")
	}
	if _, ok := findByAlias(items, ""); ok {
		t.Fatal("empty alias must not match")
	}
}

func TestFindByMALAndShikimori(t *testing.T) {
	items := testPrepared(
		aniLibriaIndexItem{ID: 1, MalID: 61967},
		aniLibriaIndexItem{ID: 2, ShikimoriID: 12345},
	)
	if item, ok := findByMalID(items, 61967); !ok || item.ID != 1 {
		t.Fatalf("mal match = %+v, %v", item, ok)
	}
	if item, ok := findByShikimoriID(items, 12345); !ok || item.ID != 2 {
		t.Fatalf("shikimori match = %+v, %v", item, ok)
	}
}

func TestSeasonPartVariant(t *testing.T) {
	items := testPrepared(
		aniLibriaIndexItem{ID: 1, Main: "О моём перерождении в слизь 4", Year: 2027},
	)
	d := testDetails("О моём перерождении в слизь 4 | Часть 2", "", 0, 0, 2027)
	needles := prepareNeedles(yaniTitleVariants(d))
	ranked := rankPrepared(items, needles, d.Year, 3)
	if len(ranked) == 0 {
		t.Fatal("no candidates")
	}
	if ranked[0].item.ID != 1 || ranked[0].score < titleMinScore {
		t.Fatalf("season/part variant: got %+v score %.3f", ranked[0].item, ranked[0].score)
	}
}

func TestSeasonMismatchRejected(t *testing.T) {
	items := testPrepared(
		aniLibriaIndexItem{ID: 1, Main: "Звёздные врата: Там бьются наши воины", Year: 2026},
	)
	d := testDetails("Звёздные врата 2: Там бьются наши воины", "", 0, 0, 2026)
	needles := prepareNeedles(yaniTitleVariants(d))
	ranked := rankPrepared(items, needles, d.Year, 3)
	if len(ranked) > 0 && ranked[0].score >= titleThreshold(ranked[0].item.Year, d.Year) {
		t.Fatalf("wrong-season match accepted: %+v %.3f", ranked[0].item, ranked[0].score)
	}
}

func TestAbsentTitleNotMatched(t *testing.T) {
	items := testPrepared(
		aniLibriaIndexItem{ID: 5152, Main: "Чёрный Клевер (Спэшл)", English: "Black Clover Special", Year: 2016, MalID: 39864},
	)
	d := testDetails("Чёрный клевер 2", "", 61967, 61967, 2026)
	d.OtherTitles = []string{"Black Clover 2nd Season", "Black Clover Season 2"}
	needles := prepareNeedles(yaniTitleVariants(d))
	ranked := rankPrepared(items, needles, d.Year, 3)
	if len(ranked) > 0 && ranked[0].score >= titleThreshold(ranked[0].item.Year, d.Year) {
		t.Fatalf("absent title matched: %+v %.3f", ranked[0].item, ranked[0].score)
	}
}

func TestMatchErrorText(t *testing.T) {
	d := testDetails("Чёрный клевер 2", "", 61967, 61967, 2026)
	want := "релиз AniLibria не найден для «Чёрный клевер 2» (MAL 61967)"
	if got := noReleaseError(d).Error(); got != want {
		t.Fatalf("error = %q, want %q", got, want)
	}
}

func TestMatchBySearchNarrowQuery(t *testing.T) {
	// Поиск AniLibria отдаёт единственный релиз с сильно отличающимся русским
	// названием, но совпадающим английским/романдзи — такой результат принимается.
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/anime/catalog/releases" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"data":[{"id":9011,"year":2021,"name":{"main":"Меня выгнали из отряда героя, поэтому я решил спокойно жить в глуши","english":"Shin no Nakama"},"alias":"shin-no-nakama-ja-nai-to-yuusha-no-party-wo-oidasareta-node-henkyou-de-slow-life-suru-koto-ni-shimashita","mal":{"id":0},"shikimori":{"id":0}}]}`))
	}))
	defer srv.Close()
	a := &aniLibria{
		cfg:    config{AniLibriaBase: srv.URL},
		cache:  newCache(16, time.Minute),
		client: &http.Client{Timeout: 5 * time.Second},
	}
	d := testDetails("Меня выгнали из гильдии героев, потому что я был плохим компаньоном", "", 0, 0, 2021)
	d.OtherTitles = []string{"Shin no Nakama ja Nai to Yuusha no Party wo Oidasareta node, Henkyou de Slow Life suru Koto ni Shima"}
	needles := prepareNeedles(yaniTitleVariants(d))
	item, ok := a.matchBySearch(yaniTitleVariants(d), needles, d.Year)
	if !ok || item.ID != 9011 {
		t.Fatalf("search fallback = %+v, %v", item, ok)
	}
}

func TestMatchBySearchRejectsUnrelated(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"data":[{"id":5152,"year":2016,"name":{"main":"Чёрный Клевер (Спэшл)","english":"Black Clover Special"},"alias":"black-clover-special","mal":{"id":39864}}]}`))
	}))
	defer srv.Close()
	a := &aniLibria{
		cfg:    config{AniLibriaBase: srv.URL},
		cache:  newCache(16, time.Minute),
		client: &http.Client{Timeout: 5 * time.Second},
	}
	d := testDetails("Чёрный клевер 2", "", 61967, 0, 2026)
	d.OtherTitles = []string{"Black Clover 2nd Season"}
	needles := prepareNeedles(yaniTitleVariants(d))
	if item, ok := a.matchBySearch(yaniTitleVariants(d), needles, d.Year); ok {
		t.Fatalf("unexpected search match: %+v", item)
	}
}
