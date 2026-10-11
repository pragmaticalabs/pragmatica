// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.nio.file.Files;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #828 — an operator who adopted `AETHER_CLUSTER_SECRET_FILE` can still join nodes: the CLI's own secret lookup goes through the
/// shared reader. Red if it reverts to a plain `AETHER_CLUSTER_SECRET` read.
class WaveNodeProvisioningSecretTest {
    @Test
    void ambientSecret_withOnlyTheFileVariable_resolvesIt() throws Exception {
        var file = Files.createTempFile("wave-secret-", ".txt");

        try {
            Files.writeString(file, "sentinel-secret-7f3a9c\n");

            assertThat(WaveNodeProvisioning.ambientSecret(Map.of("AETHER_CLUSTER_SECRET_FILE", file.toString())::get).unwrap()).isEqualTo("sentinel-secret-7f3a9c");
            assertThat(WaveNodeProvisioning.ambientSecret(Map.of("AETHER_CLUSTER_SECRET", "plain-sentinel")::get).unwrap()).isEqualTo("plain-sentinel");
            assertThat(WaveNodeProvisioning.ambientSecret(Map.<String, String> of()::get).isEmpty()).isTrue();
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
