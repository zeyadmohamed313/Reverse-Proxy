package com.reverseproxy.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable model for a parsed HTTP request.
 */
public final class HttpRequest {
    private final String method;
    private final String path;
    private final String httpVersion;
    private final Map<String, String> headers;
    private final byte[] body;

    public HttpRequest(
            String method,
            String path,
            String httpVersion,
            Map<String, String> headers,
            byte[] body
    ) {
        this.method = method;
        this.path = path;
        this.httpVersion = httpVersion;
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        this.body = body.clone();
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public String getHttpVersion() {
        return httpVersion;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public byte[] getBody() {
        return body.clone();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Method: ").append(method).append('\n');
        sb.append("Path: ").append(path).append('\n');
        sb.append("HTTP Version: ").append(httpVersion).append('\n');
        sb.append("Headers:").append('\n');

        for (Map.Entry<String, String> header : headers.entrySet()) {
            sb.append("  ")
                    .append(header.getKey())
                    .append(": ")
                    .append(header.getValue())
                    .append('\n');
        }

        sb.append("Body Length: ").append(body.length).append(" bytes");
        return sb.toString();
    }
}
