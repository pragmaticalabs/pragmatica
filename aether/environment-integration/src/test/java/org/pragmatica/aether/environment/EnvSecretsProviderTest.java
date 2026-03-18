package org.pragmatica.aether.environment;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EnvSecretsProviderTest {

    @Nested
    class PathConversionTests {

        @Test
        void toEnvVarName_simpleSlashPath_convertsCorrectly() {
            assertThat(EnvSecretsProvider.toEnvVarName("database/password"))
                .isEqualTo("AETHER_SECRET_DATABASE_PASSWORD");
        }

        @Test
        void toEnvVarName_singleSegment_prependsPrefix() {
            assertThat(EnvSecretsProvider.toEnvVarName("token"))
                .isEqualTo("AETHER_SECRET_TOKEN");
        }

        @Test
        void toEnvVarName_nestedPath_convertsAllSlashes() {
            assertThat(EnvSecretsProvider.toEnvVarName("app/db/master/password"))
                .isEqualTo("AETHER_SECRET_APP_DB_MASTER_PASSWORD");
        }

        @Test
        void toEnvVarName_lowercaseInput_convertsToUppercase() {
            assertThat(EnvSecretsProvider.toEnvVarName("my/secret"))
                .isEqualTo("AETHER_SECRET_MY_SECRET");
        }
    }

    @Nested
    class ResolutionTests {

        @Test
        void resolveSecret_missingEnvVar_returnsFailure() {
            var provider = EnvSecretsProvider.envSecretsProvider();

            provider.resolveSecret("nonexistent/secret")
                    .await()
                    .onSuccess(_ -> assertThat(true).as("Expected failure").isFalse())
                    .onFailure(cause -> assertThat(cause.message()).contains("Secret resolution failed"));
        }
    }
}
