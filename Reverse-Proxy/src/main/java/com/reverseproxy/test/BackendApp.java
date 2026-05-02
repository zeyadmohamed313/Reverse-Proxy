package com.reverseproxy.test;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

/**
 * A simple backend application to test load balancing.
 */
public class BackendApp {
    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(args[0]);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new MyHandler(port));
        server.setExecutor(null);
        server.start();
        System.out.println("Backend started on port " + port);
    }

    static class MyHandler implements HttpHandler {
        private final int port;

        public MyHandler(int port) {
            this.port = port;
        }

        @Override
        public void handle(HttpExchange t) throws IOException {
            String etag = "\"v1-" + port + "\"";
            String ifNoneMatch = t.getRequestHeaders().getFirst("If-None-Match");

            if (etag.equals(ifNoneMatch)) {
                t.sendResponseHeaders(304, -1); // Not Modified
                t.close();
                return;
            }

            String response = "Hello! Served by Backend on port: " + port + "\n";
            t.getResponseHeaders().set("ETag", etag);
            t.getResponseHeaders().set("Content-Type", "text/plain");
            t.sendResponseHeaders(200, response.length());
            OutputStream os = t.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }
    }
}
