// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.config.toml.TomlDocument;

import static org.assertj.core.api.Assertions.assertThat;

/// #828 — the credentials a node reads from the environment have a file form: `AETHER_API_KEYS_FILE` for the accepted keys, and
/// `NAME_FILE` for any `${env:NAME}` reference (the node config's `api_key = "${env:AETHER_API_KEY}"` among them). The environment
/// is supplied, so the test states what the process sees instead of inheriting it.
class ConfigLoaderCredentialFilesTest {
    private static final String KEYS = "sentinel-keys-5d2e81:ops:ADMIN:ADMIN";

    @Test
    void apiKeys_fromTheFile_areTheAcceptedKeys(@TempDir Path dir) throws Exception {
        var file = Files.writeString(dir.resolve("keys"), KEYS + "\n");

        var keys = ConfigLoader.resolveApiKeys(TomlDocument.EMPTY, Map.of("AETHER_API_KEYS_FILE", file.toString())::get);

        assertThat(keys).containsKey("sentinel-keys-5d2e81");
    }

    @Test
    void apiKeys_fromThePlainVariable_stillWork() {
        assertThat(ConfigLoader.resolveApiKeys(TomlDocument.EMPTY, Map.of("AETHER_API_KEYS", KEYS)::get)).containsKey("sentinel-keys-5d2e81");
    }

    @Test
    void apiKeys_unreadableOrConflictingFile_yieldNoEnvKeys_theStartupGateIsWhatRefuses(@TempDir Path dir) throws Exception {
        var file = Files.writeString(dir.resolve("keys"), KEYS);

        assertThat(ConfigLoader.resolveApiKeys(TomlDocument.EMPTY, Map.of("AETHER_API_KEYS_FILE", dir.resolve("absent").toString())::get)).isEmpty();
        assertThat(ConfigLoader.resolveApiKeys(TomlDocument.EMPTY,
                                               Map.of("AETHER_API_KEYS_FILE", file.toString(), "AETHER_API_KEYS", "other:x:y:VIEWER")::get)).isEmpty();
    }

    @Test
    void envReference_resolvesFromTheValue_orFromTheNameFile_andStaysALiteralWhenNeither(@TempDir Path dir) throws Exception {
        var file = Files.writeString(dir.resolve("key"), "sentinel-key-9b4c70\n");

        assertThat(ConfigLoader.resolveEnvVar("${env:AETHER_API_KEY}", Map.of("AETHER_API_KEY", "sentinel-key-9b4c70")::get)).isEqualTo("sentinel-key-9b4c70");
        assertThat(ConfigLoader.resolveEnvVar("${env:AETHER_API_KEY}", Map.of("AETHER_API_KEY_FILE", file.toString())::get)).isEqualTo("sentinel-key-9b4c70");
        assertThat(ConfigLoader.resolveEnvVar("${env:AETHER_API_KEY}", Map.<String, String> of()::get)).isEqualTo("${env:AETHER_API_KEY}");
        assertThat(ConfigLoader.resolveEnvVar("${env:AETHER_API_KEY}", Map.of("AETHER_API_KEY_FILE", dir.resolve("absent").toString())::get))
            .as("an unreadable file fails closed: the placeholder authenticates nothing").isEqualTo("${env:AETHER_API_KEY}");
    }
}
