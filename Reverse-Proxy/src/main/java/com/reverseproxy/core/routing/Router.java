package com.reverseproxy.core.routing;

import com.reverseproxy.core.model.BackendConfig;
import com.reverseproxy.core.model.HttpRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles Load Balancing and Sticky Sessions.
 */
public final class Router {
    private final List<BackendConfig> backends = new ArrayList<>();
    private final AtomicInteger roundRobinCounter = new AtomicInteger(0);

    public Router(List<BackendConfig> backendList) {
        this.backends.addAll(backendList);
    }

    public BackendConfig route(HttpRequest request) {
        // Sticky Session Check
        String stickyId = getCookieValue(request, "PROXY_ID");
        if (stickyId != null) {
            try {
                int index = Integer.parseInt(stickyId.replace("backend_", ""));
                if (index >= 0 && index < backends.size()) {
                    BackendConfig stickyBackend = backends.get(index);
                    if (!stickyBackend.isClosed()) {
                        com.reverseproxy.core.logging.Logger.logSystem("Router", "Sticky session found for " + stickyId + ". Routing to " + stickyBackend);
                        return stickyBackend;
                    }
                }
            } catch (Exception ignored) {}
        }

        // Round Robin Load Balancing
        int attempts = 0;
        while (attempts < backends.size()) {
            int index = Math.abs(roundRobinCounter.getAndIncrement() % backends.size());
            BackendConfig candidate = backends.get(index);
            if (!candidate.isClosed()) {
                com.reverseproxy.core.logging.Logger.logSystem("Router", "No sticky session. Round Robin picked " + candidate);
                return candidate;
            }
            attempts++;
        }
        
        com.reverseproxy.core.logging.Logger.logSystem("Router", "CRITICAL: All backends are currently DOWN/OPEN.");

        return null;
    }

    public int getBackendIndex(BackendConfig config) {
        return backends.indexOf(config);
    }

    public List<BackendConfig> getAllBackends() {
        return backends;
    }

    private String getCookieValue(HttpRequest request, String name) {
        String cookieHeader = request.getHeaders().get("Cookie");
        if (cookieHeader == null) return null;

        String[] cookies = cookieHeader.split(";");
        for (String cookie : cookies) {
            String[] parts = cookie.trim().split("=");
            if (parts.length == 2 && parts[0].equals(name)) {
                return parts[1];
            }
        }
        return null;
    }
}
