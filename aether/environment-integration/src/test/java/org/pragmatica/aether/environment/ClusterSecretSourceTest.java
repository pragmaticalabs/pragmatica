// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.environment;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterSecretSourceTest {
    private static final String SENTINEL = "sentinel-secret-7f3a9c";

    private static Result<String> fileHolding(String content, Path path) {
        return path.toString().equals("/run/secret") ? Result.success(content) : Causes.cause("no such file").result();
    }

    private static String valueOf(Map<String, String> env, String fileContent) {
        return ClusterSecretSource.resolve(env::get, path -> fileHolding(fileContent, path))
                                  .fold(cause -> "FAILED: " + cause.message(), option -> option.or("NONE"));
    }

    @Test
    void neitherVariable_resolvesToNothing() {
        assertThat(valueOf(Map.of(), "")).isEqualTo("NONE");
    }

    @Test
    void plainVariableOnly_isTheSecret() {
        assertThat(valueOf(Map.of("AETHER_CLUSTER_SECRET", SENTINEL), "")).isEqualTo(SENTINEL);
    }

    @Test
    void fileVariableOnly_readsTheFile_andStripsTheTrailingNewline() {
        assertThat(valueOf(Map.of("AETHER_CLUSTER_SECRET_FILE", "/run/secret"), SENTINEL + "\n")).isEqualTo(SENTINEL);
    }

    @Test
    void both_equal_isTheSharedValue() {
        var env = Map.of("AETHER_CLUSTER_SECRET_FILE", "/run/secret", "AETHER_CLUSTER_SECRET", SENTINEL);

        assertThat(valueOf(env, SENTINEL)).isEqualTo(SENTINEL);
    }

    @Test
    void both_differing_isRefused_andTheMessageNamesNoValue() {
        var env = Map.of("AETHER_CLUSTER_SECRET_FILE", "/run/secret", "AETHER_CLUSTER_SECRET", "plain-" + SENTINEL);
        var outcome = valueOf(env, SENTINEL);

        assertThat(outcome).startsWith("FAILED:").contains("AETHER_CLUSTER_SECRET_FILE").doesNotContain(SENTINEL);
    }

    @Test
    void unreadableFile_isRefused_namingThePath_notFallingBackToThePlainVariable() {
        var env = new HashMap<String, String>();

        env.put("AETHER_CLUSTER_SECRET_FILE", "/missing");
        env.put("AETHER_CLUSTER_SECRET", SENTINEL);

        assertThat(valueOf(env, "")).startsWith("FAILED:").contains("/missing").doesNotContain(SENTINEL);
    }

    @Test
    void emptyFile_isRefused() {
        assertThat(valueOf(Map.of("AETHER_CLUSTER_SECRET_FILE", "/run/secret"), "\n")).startsWith("FAILED:").contains("is empty");
    }

    @Test
    void blankVariables_countAsUnset() {
        assertThat(valueOf(Map.of("AETHER_CLUSTER_SECRET", "  ", "AETHER_CLUSTER_SECRET_FILE", ""), "")).isEqualTo("NONE");
    }

    @Test
    void theRealFileReader_readsAnActualFile() throws Exception {
        var file = java.nio.file.Files.createTempFile("secret-source-", ".txt");

        try {
            java.nio.file.Files.writeString(file, SENTINEL + "\n");

            var resolved = ClusterSecretSource.resolve(Map.of("AETHER_CLUSTER_SECRET_FILE", file.toString())::get)
                                              .fold(cause -> "FAILED: " + cause.message(), option -> option.or("NONE"));

            assertThat(resolved).isEqualTo(SENTINEL);
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    private static String valueOfVar(Map<String, String> env, String var, String fileContent) {
        return ClusterSecretSource.resolve(env::get, var, path -> fileHolding(fileContent, path))
                                  .fold(cause -> "FAILED: " + cause.message(), option -> option.or("NONE"));
    }

    @Test
    void theApiKeyVariables_followTheSameRules() {
        var conflict = valueOfVar(Map.of("AETHER_API_KEYS_FILE", "/run/secret", "AETHER_API_KEYS", "plain-" + SENTINEL), "AETHER_API_KEYS", SENTINEL);

        assertThat(conflict).startsWith("FAILED:").contains("AETHER_API_KEYS_FILE").doesNotContain(SENTINEL);
        assertThat(valueOfVar(Map.of("AETHER_API_KEY_FILE", "/run/secret"), "AETHER_API_KEY", SENTINEL + "\n")).isEqualTo(SENTINEL);
    }

    @Test
    void validate_refusesAnyBrokenCredentialFile_andAcceptsAHealthyOrAbsentEnvironment() {
        assertThat(ClusterSecretSource.validate(Map.<String, String> of()::get).isSuccess()).isTrue();
        assertThat(ClusterSecretSource.validate(Map.of("AETHER_API_KEYS_FILE", "/missing")::get).isFailure()).as("a broken API keys file").isTrue();
        assertThat(ClusterSecretSource.validate(Map.of("AETHER_API_KEY_FILE", "/missing")::get).isFailure()).as("a broken API key file").isTrue();
        assertThat(ClusterSecretSource.validate(Map.of("AETHER_CLUSTER_SECRET_FILE", "/missing")::get).isFailure()).as("a broken cluster secret file").isTrue();
    }

    @Test
    void paths_followOneConvention() {
        assertThat(ClusterSecretSource.hostFile("AETHER_API_KEYS")).isEqualTo("/opt/aether/config/api-keys");
        assertThat(ClusterSecretSource.containerMount("AETHER_API_KEY")).isEqualTo("/run/secrets/aether-api-key");
        assertThat(ClusterSecretSource.jvmFile("AETHER_CLUSTER_SECRET")).isEqualTo("/etc/aether/cluster-secret");
    }

    @Test
    void onlyLineTerminatorsAreStripped_blanksArePartOfTheSecret() {
        assertThat(valueOf(Map.of("AETHER_CLUSTER_SECRET_FILE", "/run/secret"), "abc  \r\n")).as("trailing blanks stay").isEqualTo("abc  ");
        assertThat(valueOf(Map.of("AETHER_CLUSTER_SECRET_FILE", "/run/secret", "AETHER_CLUSTER_SECRET", "abc  "), "abc  \n"))
            .as("the same secret by file and by plain variable is one identity, not a conflict").isEqualTo("abc  ");
        assertThat(valueOf(Map.of("AETHER_CLUSTER_SECRET_FILE", "/run/secret"), "   \n")).as("a blank-only file is empty").startsWith("FAILED:");
    }
}
