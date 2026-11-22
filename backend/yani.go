package main

import (
	"bytes"
	"io"
	"log"
	"net/http"
	"strings"
	"time"
)

// yaniProxy проксирует запросы клиента к api.yani.tv, подставляя токен приложения.
// Токен живёт только на сервере: клиент его больше не знает.
type yaniProxy struct {
	cfg    config
	cache  *responseCache
	client *http.Client
}

func newYaniProxy(cfg config, cache *responseCache) *yaniProxy {
	return &yaniProxy{
		cfg:   cfg,
		cache: cache,
		client: &http.Client{
			Timeout: 25 * time.Second,
			Transport: &http.Transport{
				MaxIdleConns:        32,
				MaxIdleConnsPerHost: 16,
				IdleConnTimeout:     90 * time.Second,
			},
		},
	}
}

func (p *yaniProxy) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	target := p.cfg.YaniBase + r.URL.Path
	if r.URL.RawQuery != "" {
		target += "?" + r.URL.RawQuery
	}

	auth := r.Header.Get("Authorization")
	cacheable := r.Method == http.MethodGet && auth == ""
	cacheKey := "yani:" + r.URL.Path + "?" + r.URL.RawQuery + "|" + r.Header.Get("Lang")

	if cacheable {
		if entry, ok := p.cache.get(cacheKey); ok {
			copyHeaders(w, entry)
			w.WriteHeader(entry.status)
			_, _ = w.Write(entry.body)
			return
		}
	}

	body, err := io.ReadAll(io.LimitReader(r.Body, 2<<20))
	if err != nil {
		http.Error(w, "cannot read body", http.StatusBadRequest)
		return
	}

	req, err := http.NewRequestWithContext(r.Context(), r.Method, target, bytes.NewReader(body))
	if err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	req.Header.Set("X-Application", p.cfg.YaniAppToken)
	lang := r.Header.Get("Lang")
	if lang == "" {
		lang = "ru"
	}
	req.Header.Set("Lang", lang)
	req.Header.Set("Accept", r.Header.Get("Accept"))
	if req.Header.Get("Accept") == "" {
		req.Header.Set("Accept", "application/json")
	}
	if auth != "" {
		req.Header.Set("Authorization", auth)
	}
	if ct := r.Header.Get("Content-Type"); ct != "" {
		req.Header.Set("Content-Type", ct)
	}
	req.Header.Set("User-Agent", "YummyPC-backend/"+version)

	resp, err := p.client.Do(req)
	if err != nil {
		log.Printf("[yani] %s %s: %v", r.Method, r.URL.Path, err)
		writeJSON(w, http.StatusBadGateway, map[string]string{"error": "yani недоступен"})
		return
	}
	defer resp.Body.Close()

	respBody, err := io.ReadAll(io.LimitReader(resp.Body, 32<<20))
	if err != nil {
		http.Error(w, "read error", http.StatusBadGateway)
		return
	}

	entry := cacheEntry{
		body:      respBody,
		status:    resp.StatusCode,
		contentTy: resp.Header.Get("Content-Type"),
	}
	if cacheable && resp.StatusCode == http.StatusOK {
		p.cache.put(cacheKey, entry, ttlForPath(r.URL.Path))
	}
	copyHeaders(w, entry)
	w.WriteHeader(entry.status)
	_, _ = w.Write(entry.body)
}

func copyHeaders(w http.ResponseWriter, entry cacheEntry) {
	if entry.contentTy != "" {
		w.Header().Set("Content-Type", entry.contentTy)
	}
	if strings.Contains(entry.contentTy, "json") {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
	}
	// Кэш разделяемый: клиентские кэши не должны держать устаревшие данные дольше нашего TTL.
	w.Header().Set("Cache-Control", "public, max-age=60")
}

func ttlForPath(path string) time.Duration {
	switch {
	case strings.HasPrefix(path, "/feed"):
		return 90 * time.Second
	case strings.HasPrefix(path, "/video/"):
		return 120 * time.Second
	case strings.HasPrefix(path, "/anime/schedule"), strings.HasPrefix(path, "/anime/catalog"):
		return 30 * time.Minute
	case strings.HasPrefix(path, "/anime/genres"):
		return 12 * time.Hour
	case strings.HasSuffix(path, "/videos"):
		return 15 * time.Minute
	case strings.HasPrefix(path, "/anime/"):
		return 10 * time.Minute
	default:
		return 3 * time.Minute
	}
}
