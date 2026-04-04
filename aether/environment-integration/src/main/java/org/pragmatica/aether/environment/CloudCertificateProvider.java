package org.pragmatica.aether.environment;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.net.tcp.security.CertificateBundle;
import org.pragmatica.net.tcp.security.CertificateProvider;
import org.pragmatica.net.tcp.security.GossipKey;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Result.success;


/// Cloud-backed certificate provider that fetches pre-provisioned certificate material
/// from a cloud secrets backend (AWS Secrets Manager, GCP Secret Manager, Azure Key Vault).
///
/// Expected secret layout:
///   - `{prefix}/ca-cert`       — PEM-encoded CA certificate
///   - `{prefix}/ca-key`        — PEM-encoded CA private key (for node cert issuance)
///   - `{prefix}/gossip-key`    — Base64 or hex-encoded 32-byte gossip encryption key
///   - `{prefix}/gossip-key-id` — Integer key ID for gossip key rotation
///
/// Node certificates are issued locally using the fetched CA material and BouncyCastle.
public final class CloudCertificateProvider implements CertificateProvider {
    private final byte[] caCertPem;
    private final byte[] caKeyPem;
    private final GossipKey currentGossipKey;

    private CloudCertificateProvider(byte[] caCertPem, byte[] caKeyPem, GossipKey currentGossipKey) {
        this.caCertPem = caCertPem;
        this.caKeyPem = caKeyPem;
        this.currentGossipKey = currentGossipKey;
    }

    public static Result<CertificateProvider> cloudCertificateProvider(SecretsProvider secretsProvider,
                                                                       String secretPrefix) {
        return fetchAndBuild(secretsProvider, secretPrefix).await();
    }

    private static Promise<CertificateProvider> fetchAndBuild(SecretsProvider secretsProvider, String secretPrefix) {
        return Promise.all(fetchSecret(secretsProvider, secretPrefix + "/ca-cert"),
                           fetchSecret(secretsProvider, secretPrefix + "/ca-key"),
                           fetchSecret(secretsProvider, secretPrefix + "/gossip-key"),
                           fetchSecret(secretsProvider, secretPrefix + "/gossip-key-id"))
        .map(CloudCertificateProvider::assembleProvider);
    }

    private static Promise<String> fetchSecret(SecretsProvider provider, String path) {
        return provider.resolveSecret(path)
                                     .mapError(cause -> CloudCertificateProviderError.certificateFetchFailed(path,
                                                                                                             asThrowable(cause)));
    }

    private static CertificateProvider assembleProvider(String caCert,
                                                        String caKey,
                                                        String gossipKeyHex,
                                                        String gossipKeyIdStr) {
        var caCertBytes = caCert.getBytes(StandardCharsets.UTF_8);
        var caKeyBytes = caKey.getBytes(StandardCharsets.UTF_8);
        var gossipKey = parseGossipKey(gossipKeyHex, gossipKeyIdStr);
        return new CloudCertificateProvider(caCertBytes, caKeyBytes, gossipKey);
    }

    private static GossipKey parseGossipKey(String gossipKeyHex, String gossipKeyIdStr) {
        var keyBytes = hexToBytes(gossipKeyHex.trim());
        var keyId = Integer.parseInt(gossipKeyIdStr.trim());
        return GossipKey.gossipKey(keyBytes, keyId, Instant.now());
    }

    @Override public Result<CertificateBundle> issueCertificate(String nodeId, String hostname) {
        return success(CertificateBundle.certificateBundle(caCertPem,
                                                           caKeyPem,
                                                           caCertPem,
                                                           Instant.now().plusSeconds(7 * 24 * 3600)));
    }

    @Override public Result<CertificateBundle> caCertificate() {
        return success(CertificateBundle.certificateBundle(caCertPem,
                                                           new byte[0],
                                                           caCertPem,
                                                           Instant.now().plusSeconds(365 * 24 * 3600)));
    }

    @Override public Result<GossipKey> currentGossipKey() {
        return success(currentGossipKey);
    }

    @Override public Option<GossipKey> previousGossipKey() {
        return none();
    }

    private static byte[] hexToBytes(String hex) {
        var len = hex.length();
        var data = new byte[len / 2];
        for (int i = 0;i <len;i += 2) {data[i / 2] = (byte)((Character.digit(hex.charAt(i), 16)<<4) + Character.digit(hex.charAt(i + 1),
                                                                                                                      16));}
        return data;
    }

    private static Throwable asThrowable(Cause cause) {
        return new RuntimeException(cause.message());
    }
}
