package com.reverseproxy.core;

import com.reverseproxy.core.model.BackendConfig;
import com.reverseproxy.core.model.HttpRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The "Bridge" that forwards requests to backends and streams responses back.
 */
public final class ProxyEngine {

    public static void forward(HttpRequest clientRequest, BackendConfig target, OutputStream clientOutput, String clientIp, boolean injectCookie) throws IOException {
        String urlKey = clientRequest.getPath();
        com.reverseproxy.core.cache.CacheEntry cached = com.reverseproxy.core.cache.CacheManager.get(urlKey);

        try (Socket backendSocket = new Socket(target.getHost(), target.getPort());
             OutputStream backendOutput = backendSocket.getOutputStream();
             InputStream backendInput = backendSocket.getInputStream()) {

            com.reverseproxy.core.logging.Logger.logSystem("ProxyEngine", "Opening connection to backend " + target);

            // Forward Request Line
            backendOutput.write(String.format("%s %s %s\r\n", clientRequest.getMethod(), clientRequest.getPath(), clientRequest.getHttpVersion()).getBytes(StandardCharsets.US_ASCII));

            // Forward Headers
            for (Map.Entry<String, String> header : clientRequest.getHeaders().entrySet()) {
                backendOutput.write(String.format("%s: %s\r\n", header.getKey(), header.getValue()).getBytes(StandardCharsets.US_ASCII));
            }
            backendOutput.write(String.format("X-Forwarded-For: %s\r\n", clientIp).getBytes(StandardCharsets.US_ASCII));
            
            if (cached != null) {
                backendOutput.write(String.format("If-None-Match: %s\r\n", cached.getEtag()).getBytes(StandardCharsets.US_ASCII));
                com.reverseproxy.core.logging.Logger.logSystem("Cache", "Sending Conditional GET for " + urlKey + " with ETag " + cached.getEtag());
            }
            backendOutput.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            backendOutput.flush();

            // Process Backend Response
            byte[] buffer = new byte[8192];
            int bytesRead = backendInput.read(buffer);
            if (bytesRead == -1) return;

            String responseStart = new String(buffer, 0, bytesRead, StandardCharsets.US_ASCII);
            
            // Check for 304 Not Modified
            if (cached != null && responseStart.contains("304 Not Modified")) {
                com.reverseproxy.core.logging.Logger.logSystem("Cache", "HIT! Serving from memory.");
                
                String responseHeaders = "HTTP/1.1 200 OK\r\nContent-Type: " + cached.getContentType() + "\r\nContent-Length: " + cached.getBody().length + "\r\nConnection: close\r\n\r\n";
                clientOutput.write(responseHeaders.getBytes(StandardCharsets.US_ASCII));
                clientOutput.write(cached.getBody());
                clientOutput.flush();
                return;
            }

            boolean headersFinished = false;
            java.io.ByteArrayOutputStream bodyCollector = new java.io.ByteArrayOutputStream();
            String etagFound = null;
            String contentTypeFound = "text/plain";

            while (bytesRead != -1) {
                if (!headersFinished) {
                    String chunk = new String(buffer, 0, bytesRead, StandardCharsets.US_ASCII);
                    int headerEndIndex = chunk.indexOf("\r\n\r\n");
                    
                    if (headerEndIndex != -1) {
                        headersFinished = true;
                        
                        etagFound = extractHeader(chunk, "ETag");
                        contentTypeFound = extractHeader(chunk, "Content-Type");

                        int firstLineEnd = chunk.indexOf("\r\n");
                        clientOutput.write(chunk.substring(0, firstLineEnd + 2).getBytes(StandardCharsets.US_ASCII));
                        
                        if (injectCookie) {
                            int backendIndex = target.getPort() - 8081; 
                            String cookieHeader = "Set-Cookie: PROXY_ID=backend_" + backendIndex + "; Path=/; HttpOnly\r\n";
                            clientOutput.write(cookieHeader.getBytes(StandardCharsets.US_ASCII));
                        }
                        
                        clientOutput.write(chunk.substring(firstLineEnd + 2).getBytes(StandardCharsets.US_ASCII));
                        bodyCollector.write(chunk.substring(headerEndIndex + 4).getBytes(StandardCharsets.US_ASCII));
                    } else {
                        clientOutput.write(buffer, 0, bytesRead);
                    }
                } else {
                    clientOutput.write(buffer, 0, bytesRead);
                    bodyCollector.write(buffer, 0, bytesRead);
                }
                clientOutput.flush();
                bytesRead = backendInput.read(buffer);
            }
            
            if (etagFound != null) {
                com.reverseproxy.core.cache.CacheManager.put(urlKey, new com.reverseproxy.core.cache.CacheEntry(bodyCollector.toByteArray(), etagFound, contentTypeFound));
            }

            target.resetFailures(); 
            com.reverseproxy.core.logging.Logger.logSystem("ProxyEngine", "Streaming completed for backend " + target);
        } catch (IOException e) {
            com.reverseproxy.core.logging.Logger.logError("Forwarding failed to " + target, e);
            target.incrementFailures();
            throw e;
        }
    }

    private static String extractHeader(String headers, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)" + name + ":\\s*([^\r\n]+)").matcher(headers);
        if (m.find()) return m.group(1);
        return null;
    }
}
