package main

import (
	"sync"
	"time"
)

type cacheEntry struct {
	body      []byte
	status    int
	contentTy string
	expires   time.Time
}

type responseCache struct {
	mu      sync.RWMutex
	entries map[string]cacheEntry
	maxSize int
	ttl     time.Duration
	hits    int64
	misses  int64
}

func newCache(maxSize int, ttl time.Duration) *responseCache {
	return &responseCache{entries: make(map[string]cacheEntry), maxSize: maxSize, ttl: ttl}
}

func (c *responseCache) get(key string) (cacheEntry, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	entry, ok := c.entries[key]
	if !ok || time.Now().After(entry.expires) {
		if ok {
			delete(c.entries, key)
		}
		c.misses++
		return cacheEntry{}, false
	}
	c.hits++
	return entry, true
}

func (c *responseCache) put(key string, entry cacheEntry, ttl time.Duration) {
	if ttl <= 0 {
		ttl = c.ttl
	}
	entry.expires = time.Now().Add(ttl)
	c.mu.Lock()
	defer c.mu.Unlock()
	if len(c.entries) >= c.maxSize {
		now := time.Now()
		for k, v := range c.entries {
			if now.After(v.expires) {
				delete(c.entries, k)
			}
		}
		if len(c.entries) >= c.maxSize {
			for k := range c.entries {
				delete(c.entries, k)
				break
			}
		}
	}
	c.entries[key] = entry
}

func (c *responseCache) stats() map[string]any {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return map[string]any{"entries": len(c.entries), "hits": c.hits, "misses": c.misses}
}
