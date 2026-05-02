package com.reverseproxy.core.config;

import com.reverseproxy.core.model.BackendConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads configuration from config.json using manual parsing (Dependency-free).
 */
public final class ProxyConfig {
    public int port;
    public int workerPoolSize;
    public int socketTimeoutMs;
    public int maxHeaderBytes;
    public int maxKeepAliveRequests;
    
    public String keystorePath;
    public String keystorePassword;
    
    public String accessLogPath;
    public String errorLogPath;
    public String systemLogPath;
    
    public int rateMaxTokens;
    public int rateRefillMs;
    
    public int cbThreshold;
    public int cbResetMs;
    
    public List<BackendConfig> backends = new ArrayList<>();

    public static ProxyConfig load(String filePath) throws IOException {
        String content = new String(Files.readAllBytes(Paths.get(filePath)));
        ProxyConfig config = new ProxyConfig();

        config.port = Integer.parseInt(extractValue(content, "\"port\":\\s*(\\d+)"));
        config.workerPoolSize = Integer.parseInt(extractValue(content, "\"workerPoolSize\":\\s*(\\d+)"));
        config.socketTimeoutMs = Integer.parseInt(extractValue(content, "\"socketTimeoutMs\":\\s*(\\d+)"));
        config.maxHeaderBytes = Integer.parseInt(extractValue(content, "\"maxHeaderBytes\":\\s*(\\d+)"));
        config.maxKeepAliveRequests = Integer.parseInt(extractValue(content, "\"maxKeepAliveRequests\":\\s*(\\d+)"));

        config.keystorePath = extractValue(content, "\"path\":\\s*\"([^\"]+)\"");
        config.keystorePassword = extractValue(content, "\"password\":\\s*\"([^\"]+)\"");

        config.accessLogPath = extractValue(content, "\"accessLogPath\":\\s*\"([^\"]+)\"");
        config.errorLogPath = extractValue(content, "\"errorLogPath\":\\s*\"([^\"]+)\"");
        config.systemLogPath = extractValue(content, "\"systemLogPath\":\\s*\"([^\"]+)\"");

        config.rateMaxTokens = Integer.parseInt(extractValue(content, "\"maxTokens\":\\s*(\\d+)"));
        config.rateRefillMs = Integer.parseInt(extractValue(content, "\"refillRateMs\":\\s*(\\d+)"));

        config.cbThreshold = Integer.parseInt(extractValue(content, "\"failureThreshold\":\\s*(\\d+)"));
        config.cbResetMs = Integer.parseInt(extractValue(content, "\"resetTimeoutMs\":\\s*(\\d+)"));

        // Parse backends list
        Pattern p = Pattern.compile("\\{\\s*\"host\":\\s*\"([^\"]+)\",\\s*\"port\":\\s*(\\d+)\\s*\\}");
        Matcher m = p.matcher(content);
        while (m.find()) {
            config.backends.add(new BackendConfig(m.group(1), Integer.parseInt(m.group(2))));
        }

        return config;
    }

    private static String extractValue(String content, String regex) {
        Matcher m = Pattern.compile(regex).matcher(content);
        if (m.find()) {
            return m.group(1);
        }
        return "";
    }
}
