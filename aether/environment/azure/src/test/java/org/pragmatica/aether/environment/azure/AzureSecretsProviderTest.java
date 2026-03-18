package org.pragmatica.aether.environment.azure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.cloud.azure.AzureError;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;

class AzureSecretsProviderTest {

    private TestAzureClient testClient;
    private AzureSecretsProvider provider;

    @BeforeEach
    void setUp() {
        testClient = new TestAzureClient();
        provider = AzureSecretsProvider.azureSecretsProvider(testClient).unwrap();
    }

    @Nested
    class ResolveSecretTests {

        @Test
        void resolveSecret_success_returnsValue() {
            testClient.getSecretResponse = Promise.success("my-secret-value");

            provider.resolveSecret("myVault/mySecret")
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(value -> assertThat(value).isEqualTo("my-secret-value"));

            assertThat(testClient.lastGetSecretVaultName).isEqualTo("myVault");
            assertThat(testClient.lastGetSecretName).isEqualTo("mySecret");
        }

        @Test
        void resolveSecret_failure_mapsToEnvironmentError() {
            testClient.getSecretResponse = new AzureError.ApiError(404, "SecretNotFound", "Not found").promise();

            provider.resolveSecret("myVault/mySecret")
                    .await()
                    .onSuccess(value -> assertThat(value).isNull())
                    .onFailure(AzureSecretsProviderTest::assertSecretResolutionFailed);
        }

        @Test
        void resolveSecret_invalidPath_noSlash_fails() {
            provider.resolveSecret("noSlashHere")
                    .await()
                    .onSuccess(value -> assertThat(value).isNull())
                    .onFailure(AzureSecretsProviderTest::assertSecretResolutionFailed);
        }

        @Test
        void resolveSecret_invalidPath_trailingSlash_fails() {
            provider.resolveSecret("vault/")
                    .await()
                    .onSuccess(value -> assertThat(value).isNull())
                    .onFailure(AzureSecretsProviderTest::assertSecretResolutionFailed);
        }

        @Test
        void resolveSecret_invalidPath_leadingSlash_fails() {
            provider.resolveSecret("/secretName")
                    .await()
                    .onSuccess(value -> assertThat(value).isNull())
                    .onFailure(AzureSecretsProviderTest::assertSecretResolutionFailed);
        }
    }

    @Nested
    class SplitPathTests {

        @Test
        void splitPath_validPath_splits() {
            var result = AzureSecretsProvider.splitPath("myVault/mySecret");
            result.onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(vas -> {
                      assertThat(vas.vaultName()).isEqualTo("myVault");
                      assertThat(vas.secretName()).isEqualTo("mySecret");
                  });
        }

        @Test
        void splitPath_nestedPath_splitsOnFirstSlash() {
            var result = AzureSecretsProvider.splitPath("vault/nested/secret");
            result.onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(vas -> {
                      assertThat(vas.vaultName()).isEqualTo("vault");
                      assertThat(vas.secretName()).isEqualTo("nested/secret");
                  });
        }
    }

    // --- Assertion helpers ---

    private static void assertSecretResolutionFailed(Cause cause) {
        assertThat(cause).isInstanceOf(EnvironmentError.SecretResolutionFailed.class);
    }
}
