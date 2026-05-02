package com.reverseproxy.core.logging;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Handles Access and Error logging to files specified in the config.
 */
public final class Logger {
    private static String accessPath;
    private static String errorPath;
    private static String systemPath;
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void init(String accessLogPath, String errorLogPath, String systemLogPath) throws IOException {
        accessPath = accessLogPath;
        errorPath = errorLogPath;
        systemPath = systemLogPath;
        
        Files.createDirectories(Paths.get(accessPath).getParent());
        Files.createDirectories(Paths.get(errorPath).getParent());
        Files.createDirectories(Paths.get(systemPath).getParent());
    }

    public static synchronized void logSystem(String component, String message) {
        String entry = String.format("[%s] [%s] %s\n",
                LocalDateTime.now().format(formatter), component.toUpperCase(), message);
        writeToFile(systemPath, entry);
    }

    public static synchronized void logAccess(String clientIp, String method, String path, int status, long durationMs) {
        String entry = String.format("[%s] %s - %s %s - %d (%dms)\n",
                LocalDateTime.now().format(formatter), clientIp, method, path, status, durationMs);
        writeToFile(accessPath, entry);
    }

    public static synchronized void logError(String message, Throwable t) {
        String time = LocalDateTime.now().format(formatter);
        String entry = String.format("[%s] ERROR: %s\n", time, message);
        writeToFile(errorPath, entry);
        if (t != null) {
            try (FileWriter fw = new FileWriter(errorPath, true);
                 PrintWriter pw = new PrintWriter(fw)) {
                t.printStackTrace(pw);
            } catch (IOException ignored) {}
        }
    }

    private static void writeToFile(String path, String content) {
        try (FileWriter fw = new FileWriter(path, true)) {
            fw.write(content);
        } catch (IOException e) {
            System.err.println("Failed to write to log file " + path + ": " + e.getMessage());
        }
    }
}
