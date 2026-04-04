package org.pragmatica.aether.environment.aws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;

class AwsCertificateProviderTest {

    private TestAwsClient testClient;

    @BeforeEach
    void setUp() {
        testClient = new TestAwsClient();
    }

    @Nested
    class SuccessfulCreation {

        @Test
        void awsCertificateProvider_success_fetchesCertificateMaterial() {
            setupValidSecrets();

            var result = AwsCertificateProvider.awsCertificateProvider(testClient, "aether/cluster");

            result.onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(provider -> {
                      assertThat(provider).isNotNull();
                      provider.caCertificate()
                              .onFailure(c -> assertThat(c).isNull())
                              .onSuccess(bundle -> assertThat(bundle.certificatePem()).isNotEmpty());
                  });
        }

        @Test
        void issueCertificate_success_returnsBundleWithCaPem() {
            setupValidSecrets();

            var provider = AwsCertificateProvider.awsCertificateProvider(testClient, "aether/cluster").unwrap();

            provider.issueCertificate("node-1", "10.0.0.1")
                    .onFailure(c -> assertThat(c).isNull())
                    .onSuccess(bundle -> {
                        assertThat(bundle.certificatePem()).isNotEmpty();
                        assertThat(bundle.caCertificatePem()).isNotEmpty();
                        assertThat(bundle.notAfter()).isNotNull();
                    });
        }

        @Test
        void currentGossipKey_success_returnsParsedKey() {
            setupValidSecrets();

            var provider = AwsCertificateProvider.awsCertificateProvider(testClient, "aether/cluster").unwrap();

            provider.currentGossipKey()
                    .onFailure(c -> assertThat(c).isNull())
                    .onSuccess(key -> {
                        assertThat(key.key()).hasSize(32);
                        assertThat(key.keyId()).isEqualTo(42);
                    });
        }

        @Test
        void previousGossipKey_success_returnsNone() {
            setupValidSecrets();

            var provider = AwsCertificateProvider.awsCertificateProvider(testClient, "aether/cluster").unwrap();

            assertThat(provider.previousGossipKey().isEmpty()).isTrue();
        }
    }

    @Nested
    class FailedCreation {

        @Test
        void awsCertificateProvider_missingSecret_failsWithError() {
            testClient.getSecretValueResponse = new org.pragmatica.aether.environment.CloudCertificateProviderError
                .CertificateFetchFailed("aether/cluster/ca-cert", new RuntimeException("not found")).promise();

            var result = AwsCertificateProvider.awsCertificateProvider(testClient, "aether/cluster");

            result.onSuccess(p -> assertThat(p).isNull())
                  .onFailure(cause -> assertThat(cause.message()).contains("Certificate fetch failed"));
        }
    }

    // --- Setup helpers ---

    private void setupValidSecrets() {
        var caCertPem = "-----BEGIN CERTIFICATE-----\nMIIB...test...\n-----END CERTIFICATE-----";
        var caKeyPem = "-----BEGIN EC PRIVATE KEY-----\nMHQ...test...\n-----END EC PRIVATE KEY-----";
        var gossipKeyHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        var gossipKeyId = "42";

        var responses = new java.util.concurrent.LinkedBlockingQueue<Promise<String>>();
        responses.add(Promise.success(caCertPem));
        responses.add(Promise.success(caKeyPem));
        responses.add(Promise.success(gossipKeyHex));
        responses.add(Promise.success(gossipKeyId));

        testClient.secretResponses = responses;
    }
}
