package com.reverseproxy.core.health;

import com.reverseproxy.core.model.BackendConfig;
import com.reverseproxy.core.logging.Logger;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background service that pings backends to check their health.
 */
public final class HealthChecker {
    private final List<BackendConfig> backends;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public HealthChecker(List<BackendConfig> backends) {
        this.backends = backends;
    }

    public void start() {
        Logger.logSystem("Health", "Starting background health checker (1s interval)");
        scheduler.scheduleAtFixedRate(this::checkAll, 1, 1, TimeUnit.SECONDS);
    }

    private void checkAll() {
        for (BackendConfig backend : backends) {
            boolean wasClosed = backend.isClosed();
            
            try (Socket socket = new Socket()) {
                // Try to connect with a short timeout (500ms)
                socket.connect(new InetSocketAddress(backend.getHost(), backend.getPort()), 500);
                
                if (wasClosed) {
                    backend.resetFailures();
                    Logger.logSystem("Health", "Backend " + backend.getHost() + ":" + backend.getPort() + " is BACK ONLINE.");
                }
            } catch (IOException e) {
                if (!wasClosed) {
                    int failures = backend.incrementFailures();
                    // If we fail the active health check enough times, we trip the circuit even without user traffic
                    if (failures >= 3) {
                        backend.setClosed(true);
                        Logger.logSystem("Health", "Backend " + backend.getHost() + ":" + backend.getPort() + " went DOWN (Health Check Failed).");
                    }
                }
            }
        }
    }

    public void stop() {
        scheduler.shutdown();
    }
}
