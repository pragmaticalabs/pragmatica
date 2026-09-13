// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #584 (2) — a successful bootstrap must make the new cluster the ACTIVE context. `ClusterRegistry.add`
/// deliberately keeps whatever context was current (`ClusterRegistryTest.saveAndLoad_preservesState…`
/// pins that), so a fresh bootstrap left `[current] context` on whatever was active before — in the
/// live case a cluster dead since July — and the first context-routed command after bootstrap dialled
/// the wrong cluster with a bare `ConnectException`.
///
/// Pins the pure registration step rather than `registerClusterLocally`, for the same reason
/// `BootstrapPhasePostEndpointTest` does: the latter writes the operator's real `~/.aether/clusters.toml`.
class BootstrapPhasePostContextTest {
    @TempDir
    Path tempDir;

    @Test
    void registerAndActivate_switchesContextToTheBootstrappedCluster_whenAnotherWasActive() {
        ClusterRegistry.load(tempDir.resolve("clusters.toml"))
                       .map(registry -> registry.add("old-dead",
                                                     "http://10.0.0.1:8080",
                                                     none()))
                       .flatMap(registry -> BootstrapPhasePost.registerAndActivate(registry,
                                                                                   "fresh",
                                                                                   "https://138.199.236.244:8080",
                                                                                   some("AETHER_FRESH_API_KEY")))
                       .onFailure(cause -> fail(cause.message()))
                       .onSuccess(registry -> {
                                      assertThat(registry.currentContext()).as("the cluster just bootstrapped is the one the operator's next command must reach")
                                                .isEqualTo(some("fresh"));
                                      assertThat(registry.entries()).extracting(ClusterRegistry.ClusterEntry::name)
                                                .containsExactly("old-dead", "fresh");
                                      assertThat(registry.current()).as("the active entry carries the bootstrapped endpoint and key env")
                                                .isEqualTo(some(new ClusterRegistry.ClusterEntry("fresh",
                                                                                                 "https://138.199.236.244:8080",
                                                                                                 some("AETHER_FRESH_API_KEY"))));
                                  });
    }

    @Test
    void registerAndActivate_setsContext_whenRegistryWasEmpty() {
        ClusterRegistry.load(tempDir.resolve("clusters.toml"))
                       .flatMap(registry -> BootstrapPhasePost.registerAndActivate(registry,
                                                                                   "fresh",
                                                                                   "http://localhost:8080",
                                                                                   none()))
                       .onFailure(cause -> fail(cause.message()))
                       .onSuccess(registry -> assertThat(registry.currentContext()).isEqualTo(some("fresh")));
    }
}
