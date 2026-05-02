package com.reverseproxy.core.handler;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple Token Bucket rate limiter per IP.
 */
public final class TokenBucketRateLimiter {
    private static int MAX_TOKENS = 10;
    private static int REFILL_RATE_MS = 500; 

    private static final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public static void init(int max, int refillMs) {
        MAX_TOKENS = max;
        REFILL_RATE_MS = refillMs;
    }

    public static boolean tryConsume(String ip) {
        Bucket bucket = buckets.computeIfAbsent(ip, k -> new Bucket());
        return bucket.consume(ip);
    }

    private static class Bucket {
        private final AtomicInteger tokens = new AtomicInteger(MAX_TOKENS);
        private long lastRefill = System.currentTimeMillis();

        public synchronized boolean consume(String ip) {
            refill();
            if (tokens.get() > 0) {
                tokens.decrementAndGet();
                com.reverseproxy.core.logging.Logger.logSystem("RateLimiter", "IP " + ip + " consumed token. Tokens left: " + tokens.get());
                return true;
            }
            com.reverseproxy.core.logging.Logger.logSystem("RateLimiter", "IP " + ip + " rejected. Bucket empty.");
            return false;
        }

        private void refill() {
            long now = System.currentTimeMillis();
            long timePassed = now - lastRefill;
            if (timePassed > REFILL_RATE_MS) {
                int newTokens = (int) (timePassed / REFILL_RATE_MS);
                if (newTokens > 0) {
                    int oldTokens = tokens.get();
                    tokens.set(Math.min(MAX_TOKENS, tokens.get() + newTokens));
                    com.reverseproxy.core.logging.Logger.logSystem("RateLimiter", "Refilled " + (tokens.get() - oldTokens) + " tokens. Total: " + tokens.get());
                    lastRefill = now;
                }
            }
        }
    }
}
