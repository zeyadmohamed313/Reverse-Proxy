package com.reverseproxy.core.handler;

import com.reverseproxy.core.ProxyEngine;
import com.reverseproxy.core.model.BackendConfig;
import com.reverseproxy.core.model.HttpRequest;
import com.reverseproxy.core.parser.HttpParser;
import com.reverseproxy.core.routing.Router;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.net.Socket;
import java.util.Map;

/**
 * Worker task that processes requests by forwarding them to backends.
 */
public final class ClientHandler implements Runnable {
    private final Socket clientSocket;
    private final int socketReadTimeoutMillis;
    private final int maxHeaderBytes;
    private final int maxKeepAliveRequests;
    private final Router router;
    private final int cbThreshold;

    public ClientHandler(
            Socket clientSocket,
            int socketReadTimeoutMillis,
            int maxHeaderBytes,
            int maxKeepAliveRequests,
            Router router,
            int cbThreshold
    ) {
        this.clientSocket = clientSocket;
        this.socketReadTimeoutMillis = socketReadTimeoutMillis;
        this.maxHeaderBytes = maxHeaderBytes;
        this.maxKeepAliveRequests = maxKeepAliveRequests;
        this.router = router;
        this.cbThreshold = cbThreshold;
    }

    @Override
    public void run() {
        String clientIp = clientSocket.getInetAddress().getHostAddress();

        try (Socket socket = clientSocket;
             InputStream inputStream = socket.getInputStream();
             OutputStream outputStream = socket.getOutputStream()) {
            socket.setSoTimeout(socketReadTimeoutMillis);

            int handledRequests = 0;
            while (handledRequests < maxKeepAliveRequests) {
                long startTime = System.currentTimeMillis();
                
                if (!com.reverseproxy.core.handler.TokenBucketRateLimiter.tryConsume(clientIp)) {
                    writeErrorResponse(outputStream, 429, "Too Many Requests", "Rate limit exceeded");
                    com.reverseproxy.core.logging.Logger.logAccess(clientIp, "REJECTED", "RATE_LIMIT", 429, 0);
                    break;
                }

                HttpRequest request;
                try {
                    request = HttpParser.parse(inputStream, maxHeaderBytes);
                } catch (SocketTimeoutException ex) {
                    break;
                } catch (HttpParser.HeaderTooLargeException ex) {
                    writeErrorResponse(outputStream, 431, "Header Too Large", "Headers too large");
                    com.reverseproxy.core.logging.Logger.logAccess(clientIp, "ERROR", "HEADER_LARGE", 431, 0);
                    break;
                } catch (HttpParser.MalformedRequestException ex) {
                    writeErrorResponse(outputStream, 400, "Bad Request", "Malformed request");
                    com.reverseproxy.core.logging.Logger.logAccess(clientIp, "ERROR", "MALFORMED", 400, 0);
                    break;
                } catch (IOException ex) {
                    break;
                }

                if (request == null) break;
                handledRequests += 1;

                if ("/__stats".equals(request.getPath())) {
                    writeStatsResponse(outputStream);
                    com.reverseproxy.core.logging.Logger.logAccess(clientIp, request.getMethod(), request.getPath(), 200, System.currentTimeMillis() - startTime);
                    break;
                }

                BackendConfig target = router.route(request);
                if (target == null) {
                    writeErrorResponse(outputStream, 503, "Service Unavailable", "No healthy backends available (Circuit Open)");
                    com.reverseproxy.core.logging.Logger.logAccess(clientIp, request.getMethod(), request.getPath(), 503, System.currentTimeMillis() - startTime);
                    break;
                }

                try {
                    boolean isNewSession = !request.getHeaders().containsKey("Cookie") || 
                                           !request.getHeaders().get("Cookie").contains("PROXY_ID");
                    
                    ProxyEngine.forward(request, target, outputStream, clientIp, isNewSession);
                    com.reverseproxy.core.logging.Logger.logAccess(clientIp, request.getMethod(), request.getPath(), 200, System.currentTimeMillis() - startTime);
                } catch (IOException ex) {
                    if (target.incrementFailures() >= cbThreshold) {
                        target.setClosed(true);
                        com.reverseproxy.core.logging.Logger.logError("CIRCUIT TRIPPED for " + target, ex);
                    }
                    writeErrorResponse(outputStream, 502, "Bad Gateway", "Backend connection failed");
                    com.reverseproxy.core.logging.Logger.logAccess(clientIp, request.getMethod(), request.getPath(), 502, System.currentTimeMillis() - startTime);
                    break;
                }

                if (!shouldKeepAlive(request) || handledRequests >= maxKeepAliveRequests) {
                    break;
                }
            }

        } catch (IOException ex) {
            com.reverseproxy.core.logging.Logger.logError("Client handling error", ex);
        }
    }

    private void writeStatsResponse(OutputStream out) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("--- Reverse Proxy Stats ---\n");
        for (BackendConfig b : router.getAllBackends()) {
            sb.append(b.toString()).append("\n");
        }
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static boolean shouldKeepAlive(HttpRequest request) {
        String conn = request.getHeaders().get("Connection");
        if ("HTTP/1.1".equalsIgnoreCase(request.getHttpVersion())) {
            return conn == null || !conn.equalsIgnoreCase("close");
        }
        return "keep-alive".equalsIgnoreCase(conn);
    }

    private static void writeErrorResponse(OutputStream out, int code, String reason, String msg) throws IOException {
        byte[] body = msg.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + code + " " + reason + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }
}
