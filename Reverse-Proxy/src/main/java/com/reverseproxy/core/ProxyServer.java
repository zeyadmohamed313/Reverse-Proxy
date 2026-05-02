package com.reverseproxy.core;

import com.reverseproxy.core.config.ProxyConfig;
import com.reverseproxy.core.handler.ClientHandler;
import com.reverseproxy.core.handler.TokenBucketRateLimiter;
import com.reverseproxy.core.logging.Logger;
import com.reverseproxy.core.routing.Router;
import com.reverseproxy.core.tls.TlsContextFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TCP acceptor for incoming client connections.
 */
public final class ProxyServer {
    private static final String CONFIG_FILE = "config.json";

    private ProxyServer() {
    }

    public static void main(String[] args) {
        try {
            ProxyConfig config = ProxyConfig.load(CONFIG_FILE);
            System.out.println("Config loaded from " + CONFIG_FILE);

            Logger.init(config.accessLogPath, config.errorLogPath, config.systemLogPath);
            System.out.println("Logging initialized. Access: " + config.accessLogPath + ", System: " + config.systemLogPath);

            TokenBucketRateLimiter.init(config.rateMaxTokens, config.rateRefillMs);

            Router router = new Router(config.backends);
            com.reverseproxy.core.health.HealthChecker healthChecker = new com.reverseproxy.core.health.HealthChecker(config.backends);
            healthChecker.start();

            ExecutorService workerPool = Executors.newFixedThreadPool(config.workerPoolSize);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Shutting down worker pool...");
                workerPool.shutdown();
            }));

            SSLContext sslContext = TlsContextFactory.createSslContext(config.keystorePath, config.keystorePassword);
            SSLServerSocketFactory ssf = sslContext.getServerSocketFactory();

            try (SSLServerSocket serverSocket = (SSLServerSocket) ssf.createServerSocket(config.port)) {
                serverSocket.setEnabledProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
                System.out.println("Reverse proxy (TLS) listening on port " + config.port);

                while (true) {
                    Socket clientSocket = serverSocket.accept();
                    workerPool.submit(new ClientHandler(
                            clientSocket,
                            config.socketTimeoutMs,
                            config.maxHeaderBytes,
                            config.maxKeepAliveRequests,
                            router,
                            config.cbThreshold
                    ));
                }
            }
        } catch (Exception ex) {
            System.err.println("CRITICAL: Server failed to start: " + ex.getMessage());
            ex.printStackTrace();
        }
    }
}
