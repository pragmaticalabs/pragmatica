// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.environment;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

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

        /// #904 round 2 (N-2): the operator has to SET a variable, so the failure names the variable,
        /// not only the secret path it was derived from.
        @Test
        void resolveSecret_missingEnvVar_namesTheEnvVarToSet() {
            var provider = EnvSecretsProvider.envSecretsProvider();

            provider.resolveSecret("nonexistent/secret")
                    .await()
                    .onSuccess(_ -> fail("AETHER_SECRET_NONEXISTENT_SECRET is not set, resolution must fail"))
                    .onFailure(cause -> assertThat(cause.message()).contains("AETHER_SECRET_NONEXISTENT_SECRET"));
        }
    }
}
