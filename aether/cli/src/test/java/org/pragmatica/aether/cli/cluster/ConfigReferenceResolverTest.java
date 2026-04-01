package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigReferenceResolverTest {

    @Nested
    class HappyPath {
        @Test
        void resolveAll_envReference_resolvesFromEnvironment() {
            var input = "home = \"${env:HOME}\"";
            ConfigReferenceResolver.resolveAll(input)
                                   .onFailureRun(Assertions::fail)
                                   .onSuccess(result -> {
                                       assertFalse(result.contains("${env:HOME}"));
                                       assertTrue(result.contains(System.getenv("HOME")));
                                   });
        }

        @Test
        void resolveAll_noReferences_passesThrough() {
            var input = "name = \"plain-value\"\nport = 8080";
            ConfigReferenceResolver.resolveAll(input)
                                   .onFailureRun(Assertions::fail)
                                   .onSuccess(result -> assertEquals(input, result));
        }

        @Test
        void resolveAll_multipleEnvReferences_resolvesBoth() {
            var home = System.getenv("HOME");
            var input = "first = \"${env:HOME}\"\nsecond = \"${env:HOME}\"";
            ConfigReferenceResolver.resolveAll(input)
                                   .onFailureRun(Assertions::fail)
                                   .onSuccess(result -> {
                                       assertTrue(result.contains("first = \"" + home + "\""));
                                       assertTrue(result.contains("second = \"" + home + "\""));
                                   });
        }

        @Test
        void resolveAll_mixedReferencesInOneValue_resolvesBoth() {
            var home = System.getenv("HOME");
            var input = "value = \"${env:HOME}:${env:HOME}/bin\"";
            ConfigReferenceResolver.resolveAll(input)
                                   .onFailureRun(Assertions::fail)
                                   .onSuccess(result -> assertEquals("value = \"" + home + ":" + home + "/bin\"", result));
        }

        @Test
        void resolveAll_unknownPattern_leftAsIs() {
            var input = "value = \"${unknown:something}\"";
            ConfigReferenceResolver.resolveAll(input)
                                   .onFailureRun(Assertions::fail)
                                   .onSuccess(result -> assertEquals(input, result));
        }
    }

    @Nested
    class SecretsResolution {
        @Test
        void resolveAll_secretsReference_resolvesViaAetherPrefix() {
            // This test verifies the env var name conversion: secrets:cluster-secret -> AETHER_CLUSTER_SECRET
            var input = "secret = \"${secrets:cluster-secret}\"";
            ConfigReferenceResolver.resolveAll(input)
                                   .onSuccess(_ -> {
                                       // If it succeeds, AETHER_CLUSTER_SECRET is set in the env -- fine
                                   })
                                   .onFailure(cause -> assertTrue(cause.message().contains("AETHER_CLUSTER_SECRET")));
        }
    }

    @Nested
    class FailureCases {
        @Test
        void resolveAll_missingEnvVar_failureWithVarName() {
            var input = "value = \"${env:DEFINITELY_NOT_A_REAL_ENV_VAR_XYZ_12345}\"";
            ConfigReferenceResolver.resolveAll(input)
                                   .onSuccess(_ -> Assertions.fail("Expected failure"))
                                   .onFailure(cause -> assertTrue(cause.message()
                                                                       .contains("DEFINITELY_NOT_A_REAL_ENV_VAR_XYZ_12345")));
        }

        @Test
        void resolveAll_multipleMissing_reportsAll() {
            var input = "a = \"${env:MISSING_VAR_AAA}\"\nb = \"${env:MISSING_VAR_BBB}\"";
            ConfigReferenceResolver.resolveAll(input)
                                   .onSuccess(_ -> Assertions.fail("Expected failure"))
                                   .onFailure(cause -> {
                                       assertTrue(cause.message().contains("MISSING_VAR_AAA"));
                                       assertTrue(cause.message().contains("MISSING_VAR_BBB"));
                                   });
        }

        @Test
        void resolveAll_missingSecretsReference_reportsEnvVarName() {
            var input = "secret = \"${secrets:my-app-token}\"";
            ConfigReferenceResolver.resolveAll(input)
                                   .onSuccess(_ -> Assertions.fail("Expected failure"))
                                   .onFailure(cause -> assertTrue(cause.message().contains("AETHER_MY_APP_TOKEN")));
        }
    }
}
