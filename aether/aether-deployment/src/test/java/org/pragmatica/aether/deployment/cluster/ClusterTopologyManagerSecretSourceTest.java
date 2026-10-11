// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.nio.file.Files;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #828 — the secret the leader bakes into a replacement comes through the shared reader, so a leader given ONLY
/// `AETHER_CLUSTER_SECRET_FILE` still mints replacements that carry the secret. Red if the site reverts to a plain
/// `AETHER_CLUSTER_SECRET` read: an auto-healed node would then be minted without it.
class ClusterTopologyManagerSecretSourceTest {
    @Test
    void aLeaderWithOnlyTheSecretFile_stillBakesTheSecretIntoReplacements() throws Exception {
        var file = Files.createTempFile("ctm-secret-", ".txt");

        try {
            Files.writeString(file, "sentinel-secret-7f3a9c\n");

            assertThat(ClusterTopologyManagerRecord.clusterSecretFrom(Map.of("AETHER_CLUSTER_SECRET_FILE", file.toString())::get).unwrap())
                .isEqualTo("sentinel-secret-7f3a9c");
            assertThat(ClusterTopologyManagerRecord.clusterSecretFrom(Map.of("AETHER_CLUSTER_SECRET", "plain-sentinel")::get).unwrap()).isEqualTo("plain-sentinel");
            assertThat(ClusterTopologyManagerRecord.clusterSecretFrom(Map.<String, String> of()::get).isEmpty()).isTrue();
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
