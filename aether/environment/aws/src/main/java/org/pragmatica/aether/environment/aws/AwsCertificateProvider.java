package org.pragmatica.aether.environment.aws;

import org.pragmatica.aether.environment.CloudCertificateProvider;
import org.pragmatica.cloud.aws.AwsClient;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.net.tcp.security.CertificateBundle;
import org.pragmatica.net.tcp.security.CertificateProvider;
import org.pragmatica.net.tcp.security.GossipKey;

import static org.pragmatica.aether.environment.aws.AwsSecretsProvider.awsSecretsProvider;


/// AWS Certificate Manager-backed certificate provider.
///
/// Fetches pre-provisioned CA certificate, CA private key, and gossip encryption key
/// from AWS Secrets Manager. Node certificates are issued locally using the fetched CA material.
///
/// Expected secrets in AWS Secrets Manager:
///   - `{prefix}/ca-cert`       — PEM-encoded CA certificate
///   - `{prefix}/ca-key`        — PEM-encoded CA private key
///   - `{prefix}/gossip-key`    — Hex-encoded 32-byte gossip encryption key
///   - `{prefix}/gossip-key-id` — Integer key ID for gossip key rotation
public final class AwsCertificateProvider implements CertificateProvider {
    private final CertificateProvider delegate;

    private AwsCertificateProvider(CertificateProvider delegate) {
        this.delegate = delegate;
    }

    public static Result<AwsCertificateProvider> awsCertificateProvider(AwsClient client, String secretPrefix) {
        return awsSecretsProvider(client).flatMap(secrets -> buildFromSecrets(secrets, secretPrefix));
    }

    private static Result<AwsCertificateProvider> buildFromSecrets(AwsSecretsProvider secrets, String secretPrefix) {
        return CloudCertificateProvider.cloudCertificateProvider(secrets, secretPrefix)
                                                                .map(AwsCertificateProvider::new);
    }

    @Override public Result<CertificateBundle> issueCertificate(String nodeId, String hostname) {
        return delegate.issueCertificate(nodeId, hostname);
    }

    @Override public Result<CertificateBundle> caCertificate() {
        return delegate.caCertificate();
    }

    @Override public Result<GossipKey> currentGossipKey() {
        return delegate.currentGossipKey();
    }

    @Override public Option<GossipKey> previousGossipKey() {
        return delegate.previousGossipKey();
    }
}
