package org.pragmatica.aether.environment.azure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.CloudCertificateProviderError;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class AzureCertificateProviderTest {

    private TestAzureClient testClient;

    @BeforeEach
    void setUp() {
        testClient = new TestAzureClient();
    }

    @Nested
    class SuccessfulCreation {

        @Test
        void azureCertificateProvider_success_fetchesCertificateMaterial() {
            setupValidSecrets();

            var result = AzureCertificateProvider.azureCertificateProvider(testClient, "my-vault/aether-cluster");

            result.onFailure(cause -> fail("Expected success but got: " + cause.message()))
                  .onSuccess(provider -> {
                      assertThat(provider).isNotNull();
                      provider.caCertificate()
                              .onFailure(c -> fail("Expected success but got: " + c.message()))
                              .onSuccess(bundle -> assertThat(bundle.certificatePem()).isNotEmpty());
                  });
        }

        @Test
        void currentGossipKey_success_returnsParsedKey() {
            setupValidSecrets();

            var provider = AzureCertificateProvider.azureCertificateProvider(testClient, "my-vault/aether-cluster").unwrap();

            provider.currentGossipKey()
                    .onFailure(c -> fail("Expected success but got: " + c.message()))
                    .onSuccess(key -> {
                        assertThat(key.key()).hasSize(32);
                        assertThat(key.keyId()).isEqualTo(99);
                    });
        }
    }

    @Nested
    class FailedCreation {

        @Test
        void azureCertificateProvider_invalidPrefix_failsWithInvalidMaterial() {
            var result = AzureCertificateProvider.azureCertificateProvider(testClient, "no-slash");

            result.onSuccess(p -> assertThat(p).isNull())
                  .onFailure(cause -> assertThat(cause).isInstanceOf(
                      CloudCertificateProviderError.InvalidCertificateMaterial.class));
        }

        @Test
        void azureCertificateProvider_missingSecret_failsWithError() {
            testClient.getSecretResponse = CloudCertificateProviderError.certificateFetchFailed(
                "my-vault/aether-cluster-ca-cert", new RuntimeException("not found")).promise();

            var result = AzureCertificateProvider.azureCertificateProvider(testClient, "my-vault/aether-cluster");

            result.onSuccess(p -> assertThat(p).isNull())
                  .onFailure(cause -> assertThat(cause.message()).contains("Certificate fetch failed"));
        }
    }

    // --- Setup helpers ---

    private void setupValidSecrets() {
        var caCertPem = "-----BEGIN CERTIFICATE-----\nMIIB...test...\n-----END CERTIFICATE-----";
        var caKeyPem = "-----BEGIN EC PRIVATE KEY-----\nMHQ...test...\n-----END EC PRIVATE KEY-----";
        var gossipKeyHex = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";
        var gossipKeyId = "99";

        var responses = new java.util.concurrent.LinkedBlockingQueue<Promise<String>>();
        responses.add(Promise.success(caCertPem));
        responses.add(Promise.success(caKeyPem));
        responses.add(Promise.success(gossipKeyHex));
        responses.add(Promise.success(gossipKeyId));

        testClient.secretResponses = responses;
    }
}
