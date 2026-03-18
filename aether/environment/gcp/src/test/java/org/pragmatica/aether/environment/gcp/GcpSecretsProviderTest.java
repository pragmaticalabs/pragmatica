package org.pragmatica.aether.environment.gcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.cloud.gcp.GcpError;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;

class GcpSecretsProviderTest {

    private TestGcpClient testClient;
    private GcpSecretsProvider provider;

    @BeforeEach
    void setUp() {
        testClient = new TestGcpClient();
        provider = GcpSecretsProvider.gcpSecretsProvider(testClient);
    }

    @Nested
    class ResolveSecretTests {

        @Test
        void resolveSecret_success_returnsSecretValue() {
            testClient.accessSecretResponse = Promise.success("my-secret-value");

            provider.resolveSecret("database/password")
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(value -> assertThat(value).isEqualTo("my-secret-value"));

            assertThat(testClient.lastSecretName).isEqualTo("database/password");
        }

        @Test
        void resolveSecret_failure_mapsToEnvironmentError() {
            testClient.accessSecretResponse = new GcpError.ApiError(404, "NOT_FOUND", "Secret not found").promise();

            provider.resolveSecret("missing/secret")
                    .await()
                    .onSuccess(value -> assertThat(value).isNull())
                    .onFailure(GcpSecretsProviderTest::assertSecretResolutionFailed);
        }
    }

    // --- Assertion helpers ---

    private static void assertSecretResolutionFailed(Cause cause) {
        assertThat(cause).isInstanceOf(EnvironmentError.SecretResolutionFailed.class);
    }
}
