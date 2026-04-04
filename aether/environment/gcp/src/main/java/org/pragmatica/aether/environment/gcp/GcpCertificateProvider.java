package org.pragmatica.aether.environment.gcp;

import org.pragmatica.aether.environment.CloudCertificateProvider;
import org.pragmatica.cloud.gcp.GcpClient;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.net.tcp.security.CertificateBundle;
import org.pragmatica.net.tcp.security.CertificateProvider;
import org.pragmatica.net.tcp.security.GossipKey;

import static org.pragmatica.aether.environment.gcp.GcpSecretsProvider.gcpSecretsProvider;


/// GCP Certificate Manager-backed certificate provider.
///
/// Fetches pre-provisioned CA certificate, CA private key, and gossip encryption key
/// from GCP Secret Manager. Node certificates are issued locally using the fetched CA material.
///
/// Expected secrets in GCP Secret Manager:
///   - `{prefix}-ca-cert`       — PEM-encoded CA certificate
///   - `{prefix}-ca-key`        — PEM-encoded CA private key
///   - `{prefix}-gossip-key`    — Hex-encoded 32-byte gossip encryption key
///   - `{prefix}-gossip-key-id` — Integer key ID for gossip key rotation
public final class GcpCertificateProvider implements CertificateProvider {
    private final CertificateProvider delegate;

    private GcpCertificateProvider(CertificateProvider delegate) {
        this.delegate = delegate;
    }

    /// Create a GCP certificate provider that fetches material from Secret Manager.
    ///
    /// @param client       GCP client for Secret Manager access
    /// @param secretPrefix prefix path for certificate secrets (e.g., "aether-cluster-prod")
    /// @return configured provider or error
    public static Result<GcpCertificateProvider> gcpCertificateProvider(GcpClient client, String secretPrefix) {
        return buildFromSecrets(gcpSecretsProvider(client), secretPrefix);
    }

    private static Result<GcpCertificateProvider> buildFromSecrets(GcpSecretsProvider secrets, String secretPrefix) {
        return CloudCertificateProvider.cloudCertificateProvider(secrets, secretPrefix)
                                       .map(GcpCertificateProvider::new);
    }

    @Override
    public Result<CertificateBundle> issueCertificate(String nodeId, String hostname) {
        return delegate.issueCertificate(nodeId, hostname);
    }

    @Override
    public Result<CertificateBundle> caCertificate() {
        return delegate.caCertificate();
    }

    @Override
    public Result<GossipKey> currentGossipKey() {
        return delegate.currentGossipKey();
    }

    @Override
    public Option<GossipKey> previousGossipKey() {
        return delegate.previousGossipKey();
    }
}
