package com.reverseproxy.core.model;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Configuration and state for a single backend server.
 */
public final class BackendConfig {
    private final String host;
    private final int port;
    private final AtomicBoolean isClosed = new AtomicBoolean(false);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    public BackendConfig(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public boolean isClosed() {
        return isClosed.get();
    }

    public void setClosed(boolean closed) {
        isClosed.set(closed);
    }

    public int incrementFailures() {
        return consecutiveFailures.incrementAndGet();
    }

    public void resetFailures() {
        consecutiveFailures.set(0);
        isClosed.set(false);
    }

    @Override
    public String toString() {
        return host + ":" + port + " [" + (isClosed.get() ? "OPEN/FAILED" : "CLOSED/HEALTHY") + "]";
    }
}
