package org.pragmatica.aether.environment.gcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;

class GcpCertificateProviderTest {

    private TestGcpClient testClient;

    @BeforeEach
    void setUp() {
        testClient = new TestGcpClient();
    }

    @Nested
    class SuccessfulCreation {

        @Test
        void gcpCertificateProvider_success_fetchesCertificateMaterial() {
            setupValidSecrets();

            var result = GcpCertificateProvider.gcpCertificateProvider(testClient, "aether-cluster");

            result.onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(provider -> {
                      assertThat(provider).isNotNull();
                      provider.caCertificate()
                              .onFailure(c -> assertThat(c).isNull())
                              .onSuccess(bundle -> assertThat(bundle.certificatePem()).isNotEmpty());
                  });
        }

        @Test
        void currentGossipKey_success_returnsParsedKey() {
            setupValidSecrets();

            var provider = GcpCertificateProvider.gcpCertificateProvider(testClient, "aether-cluster").unwrap();

            provider.currentGossipKey()
                    .onFailure(c -> assertThat(c).isNull())
                    .onSuccess(key -> {
                        assertThat(key.key()).hasSize(32);
                        assertThat(key.keyId()).isEqualTo(7);
                    });
        }
    }

    @Nested
    class FailedCreation {

        @Test
        void gcpCertificateProvider_missingSecret_failsWithError() {
            testClient.accessSecretResponse = new org.pragmatica.aether.environment.CloudCertificateProviderError
                .CertificateFetchFailed("aether-cluster/ca-cert", new RuntimeException("not found")).promise();

            var result = GcpCertificateProvider.gcpCertificateProvider(testClient, "aether-cluster");

            result.onSuccess(p -> assertThat(p).isNull())
                  .onFailure(cause -> assertThat(cause.message()).contains("Certificate fetch failed"));
        }
    }

    // --- Setup helpers ---

    private void setupValidSecrets() {
        var caCertPem = "-----BEGIN CERTIFICATE-----\nMIIB...test...\n-----END CERTIFICATE-----";
        var caKeyPem = "-----BEGIN EC PRIVATE KEY-----\nMHQ...test...\n-----END EC PRIVATE KEY-----";
        var gossipKeyHex = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
        var gossipKeyId = "7";

        var responses = new java.util.concurrent.LinkedBlockingQueue<Promise<String>>();
        responses.add(Promise.success(caCertPem));
        responses.add(Promise.success(caKeyPem));
        responses.add(Promise.success(gossipKeyHex));
        responses.add(Promise.success(gossipKeyId));

        testClient.secretResponses = responses;
    }
}
