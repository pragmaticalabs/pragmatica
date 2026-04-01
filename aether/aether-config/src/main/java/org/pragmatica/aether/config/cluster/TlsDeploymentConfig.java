package org.pragmatica.aether.config.cluster;

import org.pragmatica.lang.Option;

/// TLS configuration for cluster deployment.
///
/// @param autoGenerate whether to auto-generate TLS certificates
/// @param clusterSecret secret reference for CA generation
/// @param certTtl certificate time-to-live duration string
public record TlsDeploymentConfig( boolean autoGenerate,
                                   Option<String> clusterSecret,
                                   String certTtl) {
    /// Factory method.
    public static TlsDeploymentConfig tlsDeploymentConfig(boolean autoGenerate,
                                                          Option<String> clusterSecret,
                                                          String certTtl) {
        return new TlsDeploymentConfig(autoGenerate, clusterSecret, certTtl);
    }

    /// Default TLS config.
    public static TlsDeploymentConfig defaultTlsConfig() {
        return new TlsDeploymentConfig(true, Option.none(), "720h");
    }
}
