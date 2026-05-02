package com.reverseproxy.core.cache;

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for cached responses.
 */
public final class CacheManager {
    private static final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private static boolean enabled = true;

    public static void setEnabled(boolean isEnabled) {
        enabled = isEnabled;
    }

    public static CacheEntry get(String url) {
        if (!enabled) return null;
        return cache.get(url);
    }

    public static void put(String url, CacheEntry entry) {
        if (!enabled || entry.getEtag() == null) return;
        
        // Simple size limit for learning: clear cache if too big
        if (cache.size() > 100) {
            com.reverseproxy.core.logging.Logger.logSystem("Cache", "Cache full. Clearing all entries.");
            cache.clear();
        }
        
        cache.put(url, entry);
        com.reverseproxy.core.logging.Logger.logSystem("Cache", "Cached new resource: " + url + " [ETag: " + entry.getEtag() + "]");
    }
}
