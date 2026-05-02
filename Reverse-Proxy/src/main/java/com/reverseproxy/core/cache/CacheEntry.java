package com.reverseproxy.core.cache;

/**
 * Represents a cached HTTP response.
 */
public final class CacheEntry {
    private final byte[] body;
    private final String etag;
    private final String contentType;
    private final long createdAt;

    public CacheEntry(byte[] body, String etag, String contentType) {
        this.body = body;
        this.etag = etag;
        this.contentType = contentType;
        this.createdAt = System.currentTimeMillis();
    }

    public byte[] getBody() {
        return body;
    }

    public String getEtag() {
        return etag;
    }

    public String getContentType() {
        return contentType;
    }

    public long getCreatedAt() {
        return createdAt;
    }
}
