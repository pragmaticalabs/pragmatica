// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
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
}
