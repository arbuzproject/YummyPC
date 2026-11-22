// YummyPC backend: прокси к yani API (токены не уезжают в клиент) и внешние балансеры.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync/atomic"
	"syscall"
	"time"
)

var (
	version   = "0.3.1"
	startedAt = time.Now()
	requests  atomic.Int64
)

type config struct {
	Addr          string
	YaniAppToken  string
	YaniBase      string
	AniLibriaBase string
	DataDir       string
	AdminToken    string
	CacheTTL      time.Duration
}

func loadConfig() config {
	cfg := config{
		Addr:          envOr("ADDR", ":8080"),
		YaniAppToken:  os.Getenv("YANI_APP_TOKEN"),
		YaniBase:      envOr("YANI_BASE", "https://api.yani.tv"),
		AniLibriaBase: envOr("ANILIBRIA_BASE", "https://aniliberty.top"),
		DataDir:       envOr("DATA_DIR", "/var/lib/yummypc"),
		AdminToken:    os.Getenv("ADMIN_TOKEN"),
		CacheTTL:      time.Duration(intEnv("CACHE_TTL_SEC", 300)) * time.Second,
	}
	if cfg.YaniAppToken == "" {
		log.Fatal("YANI_APP_TOKEN не задан: публичный токен приложения yani обязателен")
	}
	if err := os.MkdirAll(cfg.DataDir, 0o755); err != nil {
		log.Fatalf("не удалось создать каталог данных: %v", err)
	}
	return cfg
}

func main() {
	showVersion := flag.Bool("version", false, "показать версию и выйти")
	flag.Parse()
	if *showVersion {
		println("yummypc-backend " + version)
		return
	}

	cfg := loadConfig()
	cache := newCache(512, cfg.CacheTTL)
	yani := newYaniProxy(cfg, cache)
	anilibria := newAniLibria(cfg, cache)
	go func() {
		if err := anilibria.ensureIndex(context.Background(), false); err != nil {
			log.Printf("[index] не удалось собрать индекс AniLibria: %v", err)
		}
		anilibria.refreshLoop(context.Background(), 24*time.Hour)
	}()

	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{
			"status":     "ok",
			"version":    version,
			"uptime_sec": int(time.Since(startedAt).Seconds()),
			"requests":   requests.Load(),
			"index":      anilibria.stats(),
			"cache":      cache.stats(),
		})
	})
	mux.Handle("GET /yani/", http.StripPrefix("/yani", yani))
	mux.Handle("POST /yani/", http.StripPrefix("/yani", yani))
	mux.Handle("PUT /yani/", http.StripPrefix("/yani", yani))
	mux.Handle("DELETE /yani/", http.StripPrefix("/yani", yani))
	mux.HandleFunc("GET /v1/sources/{animeId}/{episode}", anilibria.handleSources)
	mux.HandleFunc("GET /v1/sources/{animeId}", anilibria.handleReleaseInfo)
	mux.HandleFunc("POST /v1/admin/index/refresh", func(w http.ResponseWriter, r *http.Request) {
		if cfg.AdminToken == "" || r.Header.Get("Authorization") != "Bearer "+cfg.AdminToken {
			writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"})
			return
		}
		go func() {
			if err := anilibria.ensureIndex(context.Background(), true); err != nil {
				log.Printf("[index] refresh: %v", err)
			}
		}()
		writeJSON(w, http.StatusAccepted, map[string]string{"status": "refresh started"})
	})

	handler := countingMiddleware(mux)
	srv := &http.Server{
		Addr:              cfg.Addr,
		Handler:           handler,
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       60 * time.Second,
		WriteTimeout:      120 * time.Second,
		IdleTimeout:       120 * time.Second,
		MaxHeaderBytes:    1 << 20,
	}

	go func() {
		log.Printf("yummypc-backend %s слушает %s (yani=%s, anilibria=%s)", version, cfg.Addr, cfg.YaniBase, cfg.AniLibriaBase)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatalf("сервер упал: %v", err)
		}
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)
	<-stop
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_ = srv.Shutdown(ctx)
	log.Println("остановлено")
}

func countingMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Headers", "Authorization, Content-Type")
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	enc := json.NewEncoder(w)
	enc.SetEscapeHTML(false)
	_ = enc.Encode(payload)
}

func envOr(key, fallback string) string {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		return v
	}
	return fallback
}

func intEnv(key string, fallback int) int {
	if v := os.Getenv(key); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return fallback
}
