package com.reverseproxy.core.tls;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.KeyManagementException;

/**
 * Factory for creating SSLContext for TLS termination.
 */
public final class TlsContextFactory {

    private TlsContextFactory() {
    }

    public static SSLContext createSslContext(String keystorePath, String password) throws IOException {
        try {
            // 1. Load the KeyStore from disk
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (FileInputStream fis = new FileInputStream(keystorePath)) {
                keyStore.load(fis, password.toCharArray());
            }

            // 2. Initialize KeyManagerFactory with the KeyStore
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            try {
                kmf.init(keyStore, password.toCharArray());
            } catch (java.security.UnrecoverableKeyException e) {
                throw new IOException("Failed to initialize KeyManagerFactory: " + e.getMessage(), e);
            }

            // 3. Initialize SSLContext with the KeyManagers
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), null, null);

            return sslContext;
        } catch (KeyStoreException | NoSuchAlgorithmException | CertificateException | KeyManagementException e) {
            throw new IOException("Failed to initialize SSLContext: " + e.getMessage(), e);
        }
    }
}
