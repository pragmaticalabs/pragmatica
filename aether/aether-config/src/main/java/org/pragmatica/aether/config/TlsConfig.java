package org.pragmatica.aether.config;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.nio.file.Path;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;

/// TLS configuration for secure cluster communication.
///
/// @param autoGenerate  Generate self-signed certificates if true
/// @param certPath      Path to certificate file (if not auto-generating)
/// @param keyPath       Path to private key file (if not auto-generating)
/// @param caPath        Path to CA certificate file (if not auto-generating)
/// @param clusterSecret Shared secret for deterministic key derivation (empty = use default)
public record TlsConfig( boolean autoGenerate,
                         String certPath,
                         String keyPath,
                         String caPath,
                         String clusterSecret) {
    /// Factory method following JBCT naming convention.
    public static Result<TlsConfig> tlsConfig(boolean autoGenerate,
                                              String certPath,
                                              String keyPath,
                                              String caPath,
                                              String clusterSecret) {
        return success(new TlsConfig(autoGenerate, certPath, keyPath, caPath, clusterSecret));
    }

    /// Factory method without cluster secret.
    public static Result<TlsConfig> tlsConfig(boolean autoGenerate, String certPath, String keyPath, String caPath) {
        return tlsConfig(autoGenerate, certPath, keyPath, caPath, "");
    }

    /// Default: auto-generate self-signed certificates.
    public static TlsConfig tlsConfig() {
        return tlsConfig(true, "", "", "").unwrap();
    }

    /// Use provided certificates.
    public static TlsConfig tlsConfig(String certPath, String keyPath, String caPath) {
        return tlsConfig(false, certPath, keyPath, caPath).unwrap();
    }

    /// Auto-generate with explicit cluster secret.
    public static TlsConfig tlsConfig(String clusterSecret) {
        return tlsConfig(true, "", "", "", clusterSecret).unwrap();
    }

    /// Check if a cluster secret is configured.
    public boolean hasClusterSecret() {
        return ! clusterSecret.isBlank();
    }

    /// Check if using user-provided certificates.
    public boolean hasProvidedCertificates() {
        return ! autoGenerate && !certPath.isBlank() && !keyPath.isBlank();
    }

    public Option<Path> certFile() {
        return certPath.isBlank()
               ? none()
               : option(Path.of(certPath));
    }

    public Option<Path> keyFile() {
        return keyPath.isBlank()
               ? none()
               : option(Path.of(keyPath));
    }

    public Option<Path> caFile() {
        return caPath.isBlank()
               ? none()
               : option(Path.of(caPath));
    }
}
