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

/// #828 — the `[tls]` section's secret falls back to the ambient one through the shared reader, so a node given ONLY
/// `AETHER_CLUSTER_SECRET_FILE` keeps its secret here. Red if this site reverts to a plain `AETHER_CLUSTER_SECRET` read.
class ConfigLoaderTlsSecretSourceTest {
    @Test
    void onlyTheSecretFileVariable_stillYieldsTheSecret(@TempDir Path dir) throws Exception {
        var file = Files.writeString(dir.resolve("secret"), "sentinel-secret-7f3a9c\n");

        var tls = ConfigLoader.tlsFromDocument(TomlDocument.EMPTY, Map.of("AETHER_CLUSTER_SECRET_FILE", file.toString())::get);

        assertThat(tls.clusterSecret()).isEqualTo("sentinel-secret-7f3a9c");
    }

    @Test
    void thePlainVariable_stillWorks_andNothingYieldsNoSecret() {
        assertThat(ConfigLoader.tlsFromDocument(TomlDocument.EMPTY, Map.of("AETHER_CLUSTER_SECRET", "sentinel-secret-7f3a9c")::get).clusterSecret())
            .isEqualTo("sentinel-secret-7f3a9c");
        assertThat(ConfigLoader.tlsFromDocument(TomlDocument.EMPTY, Map.<String, String> of()::get).clusterSecret()).isEmpty();
    }
}
