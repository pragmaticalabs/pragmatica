package org.pragmatica.aether.environment;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;

class CompositeSecretsProviderTest {

    @Nested
    class ChainOrderTests {

        @Test
        void resolveSecret_firstProviderSucceeds_returnsFirstResult() {
            SecretsProvider first = _ -> Promise.success("first-value");
            SecretsProvider second = _ -> Promise.success("second-value");
            var composite = CompositeSecretsProvider.compositeSecretsProvider(first, second);

            composite.resolveSecret("any/path")
                     .await()
                     .onFailure(cause -> assertThat(cause).as("Expected success").isNull())
                     .onSuccess(value -> assertThat(value).isEqualTo("first-value"));
        }

        @Test
        void resolveSecret_firstFails_fallsBackToSecond() {
            SecretsProvider failing = path -> EnvironmentError.secretResolutionFailed(path, new IllegalStateException("not found"))
                                                             .promise();
            SecretsProvider succeeding = _ -> Promise.success("fallback-value");
            var composite = CompositeSecretsProvider.compositeSecretsProvider(failing, succeeding);

            composite.resolveSecret("some/secret")
                     .await()
                     .onFailure(cause -> assertThat(cause).as("Expected success").isNull())
                     .onSuccess(value -> assertThat(value).isEqualTo("fallback-value"));
        }

        @Test
        void resolveSecret_allFail_returnsLastFailure() {
            SecretsProvider first = path -> EnvironmentError.secretResolutionFailed(path, new IllegalStateException("first failed"))
                                                           .promise();
            SecretsProvider second = path -> EnvironmentError.secretResolutionFailed(path, new IllegalStateException("second failed"))
                                                            .promise();
            var composite = CompositeSecretsProvider.compositeSecretsProvider(first, second);

            composite.resolveSecret("missing/secret")
                     .await()
                     .onSuccess(_ -> assertThat(true).as("Expected failure").isFalse())
                     .onFailure(cause -> assertThat(cause.message()).contains("Secret resolution failed"));
        }
    }

    @Nested
    class EdgeCaseTests {

        @Test
        void resolveSecret_noProviders_returnsFailure() {
            var composite = CompositeSecretsProvider.compositeSecretsProvider();

            composite.resolveSecret("any/path")
                     .await()
                     .onSuccess(_ -> assertThat(true).as("Expected failure").isFalse())
                     .onFailure(cause -> assertThat(cause.message()).contains("Secret resolution failed"));
        }
    }
}
