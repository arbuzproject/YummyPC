package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"sort"
	"strings"
	"testing"
)

// TestDebugScore — диагностический тест (не для CI): печатает разбор скоринга
// для конкретного anime_id. Запуск:
//
//	CATALOG_JSON=... YANI_ID=24623 go test -run TestDebugScore -v .
func TestDebugScore(t *testing.T) {
	catalogPath := os.Getenv("CATALOG_JSON")
	yaniID := os.Getenv("YANI_ID")
	if catalogPath == "" || yaniID == "" {
		t.Skip("diagnostic test: set CATALOG_JSON and YANI_ID")
	}
	raw, err := os.ReadFile(catalogPath)
	if err != nil {
		t.Fatal(err)
	}
	var entries []aniLibriaCatalogEntry
	if err := json.Unmarshal(raw, &entries); err != nil {
		t.Fatal(err)
	}
	prepared := make([]preparedItem, len(entries))
	for i := range entries {
		prepared[i] = newPreparedItem(entries[i].toIndexItem())
	}
	resp, err := http.Get("http://127.0.0.1:18080/yani/anime/" + yaniID)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var envelope struct {
		Response yaniDetails `json:"response"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&envelope); err != nil {
		t.Fatal(err)
	}
	d := &envelope.Response
	t.Logf("yani %d %q year=%d alias=%q", d.ID, d.Title, d.Year, d.RemoteIDs.AniLibriaAlias)
	t.Logf("titles: %s", strings.Join(yaniTitleVariants(d), " | "))
	needles := prepareNeedles(yaniTitleVariants(d))
	for _, n := range needles {
		t.Logf("needle norm=%q stripped=%q markers=%v", n.norm, n.stripped, n.markers)
	}
	type row struct {
		item  aniLibriaIndexItem
		base  float64
		adj   float64
		info  string
		yr    int
		score float64
	}
	rows := make([]row, 0, len(prepared))
	for i := range prepared {
		base := titleMatchScore(prepared[i], needles, d.Year)
		if base <= 0 {
			continue
		}
		adj := applyYearAdjust(base, prepared[i].item.Year, d.Year)
		// найти лучшую пару для наглядности
		bestPair, bestScore := "", 0.0
		for _, n := range needles {
			nw := strings.Fields(n.norm)
			for _, c := range prepared[i].titles {
				cw := strings.Fields(c.norm)
				if s := titleScore(nw, cw); s > bestScore {
					bestScore, bestPair = s, fmt.Sprintf("%q vs %q", n.norm, c.norm)
				}
			}
		}
		rows = append(rows, row{item: prepared[i].item, base: base, adj: adj, info: bestPair, score: bestScore})
	}
	sort.SliceStable(rows, func(i, j int) bool { return rows[i].adj > rows[j].adj })
	for i, r := range rows {
		if i >= 8 {
			break
		}
		t.Logf("%.3f (base %.3f) release=%d %q year=%d mal=%d pair: %s", r.adj, r.base, r.item.ID, r.item.Main, r.item.Year, r.item.MalID, r.info)
	}
}
