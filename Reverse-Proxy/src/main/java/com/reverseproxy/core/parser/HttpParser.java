package com.reverseproxy.core.parser;

import com.reverseproxy.core.model.HttpRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manual HTTP/1.x request parser that reads from a raw InputStream.
 */
public final class HttpParser {
    private static final int DEFAULT_MAX_HEADER_BYTES = 16 * 1024;

    private HttpParser() {
    }

    public static HttpRequest parse(InputStream inputStream) throws IOException {
        return parse(inputStream, DEFAULT_MAX_HEADER_BYTES);
    }

    public static HttpRequest parse(InputStream inputStream, int maxHeaderBytes) throws IOException {
        if (maxHeaderBytes <= 0) {
            throw new IllegalArgumentException("maxHeaderBytes must be greater than 0");
        }

        HeaderSizeTracker headerSizeTracker = new HeaderSizeTracker(maxHeaderBytes);

        String requestLine = readLine(inputStream, headerSizeTracker);
        if (requestLine == null) {
            return null;
        }

        if (requestLine.isEmpty()) {
            throw new MalformedRequestException("Empty request line");
        }

        String[] requestParts = requestLine.split(" ");
        if (requestParts.length != 3) {
            throw new MalformedRequestException("Malformed request line: " + requestLine);
        }

        String method = requestParts[0]; // e.g., GET
        String path = requestParts[1];   // e.g., /api/users
        String httpVersion = requestParts[2]; // e.g., HTTP/1.1

        Map<String, String> headers = new LinkedHashMap<>();
        while (true) {
            String headerLine = readLine(inputStream, headerSizeTracker);
            if (headerLine == null) {
                throw new MalformedRequestException("Unexpected end of stream while reading headers");
            }

            if (headerLine.isEmpty()) {
                break;
            }

            int separatorIndex = headerLine.indexOf(':');
            if (separatorIndex <= 0) {
                throw new MalformedRequestException("Malformed header line: " + headerLine);
            }

            String headerName = headerLine.substring(0, separatorIndex).trim();
            String headerValue = headerLine.substring(separatorIndex + 1).trim();
            headers.put(headerName, headerValue);
        }

        int contentLength = extractContentLength(headers);
        byte[] body = readBody(inputStream, contentLength);

        return new HttpRequest(method, path, httpVersion, headers, body);
    }

    private static String readLine(InputStream inputStream, HeaderSizeTracker headerSizeTracker)
            throws IOException {
        ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();
        int previous = -1;

        while (true) {
            int current = inputStream.read();
            if (current == -1) {
                if (lineBuffer.size() == 0 && previous == -1) {
                    return null;
                }
                throw new MalformedRequestException("Unexpected end of stream while reading line");
            }

            headerSizeTracker.increment();

            if (previous == '\r' && current == '\n') {
                byte[] rawLine = lineBuffer.toByteArray();
                int length = rawLine.length;
                if (length > 0 && rawLine[length - 1] == '\r') {
                    length = length - 1;
                }
                return new String(rawLine, 0, length, StandardCharsets.US_ASCII);
            }

            lineBuffer.write(current);
            previous = current;
        }
    }

    private static int extractContentLength(Map<String, String> headers)
            throws MalformedRequestException {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if ("Content-Length".equalsIgnoreCase(entry.getKey())) {
                String value = entry.getValue();
                try {
                    int contentLength = Integer.parseInt(value);
                    if (contentLength < 0) {
                        throw new MalformedRequestException("Negative Content-Length: " + contentLength);
                    }
                    return contentLength;
                } catch (NumberFormatException ex) {
                    throw new MalformedRequestException("Invalid Content-Length value: " + value, ex);
                }
            }
        }
        return 0;
    }

    private static byte[] readBody(InputStream inputStream, int contentLength) throws IOException {
        if (contentLength == 0) {
            return new byte[0];
        }

        byte[] body = new byte[contentLength];
        int offset = 0;

        while (offset < contentLength) {
            int bytesRead = inputStream.read(body, offset, contentLength - offset);
            if (bytesRead == -1) {
                throw new MalformedRequestException(
                        "Unexpected end of stream while reading body. Expected "
                                + contentLength
                                + " bytes, read "
                                + offset
                                + " bytes."
                );
            }
            offset += bytesRead;
        }

        return body;
    }

    private static final class HeaderSizeTracker {
        private final int maxBytes;
        private int bytesRead;

        private HeaderSizeTracker(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        private void increment() throws IOException {
            bytesRead += 1;
            if (bytesRead > maxBytes) {
                throw new HeaderTooLargeException(
                        "Header section exceeds max size of " + maxBytes + " bytes"
                );
            }
        }
    }

    public static class MalformedRequestException extends IOException {
        public MalformedRequestException(String message) {
            super(message);
        }

        public MalformedRequestException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class HeaderTooLargeException extends IOException {
        public HeaderTooLargeException(String message) {
            super(message);
        }
    }
}
