package org.pragmatica.aether.environment.aws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.cloud.aws.AwsError;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;

class AwsSecretsProviderTest {

    private TestAwsClient testClient;
    private AwsSecretsProvider provider;

    @BeforeEach
    void setUp() {
        testClient = new TestAwsClient();
        provider = AwsSecretsProvider.awsSecretsProvider(testClient).unwrap();
    }

    @Nested
    class ResolveSecretTests {

        @Test
        void resolveSecret_success_returnsValue() {
            testClient.getSecretValueResponse = Promise.success("my-secret-value");

            provider.resolveSecret("database/password")
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(value -> assertThat(value).isEqualTo("my-secret-value"));

            assertThat(testClient.lastSecretId).isEqualTo("database/password");
        }

        @Test
        void resolveSecret_failure_mapsToEnvironmentError() {
            testClient.getSecretValueResponse = new AwsError.ApiError(404, "ResourceNotFoundException", "Secret not found").promise();

            provider.resolveSecret("missing/secret")
                    .await()
                    .onSuccess(value -> assertThat(value).isNull())
                    .onFailure(AwsSecretsProviderTest::assertSecretResolutionFailed);
        }
    }

    // --- Assertion helpers ---

    private static void assertSecretResolutionFailed(Cause cause) {
        assertThat(cause).isInstanceOf(EnvironmentError.SecretResolutionFailed.class);
    }
}
