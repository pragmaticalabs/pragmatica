// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.pragmatica.aether.config.cluster.PortMapping;

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

    /// The WIRING, which the two `registerAndActivate` tests above cannot see (a call site that
    /// went back to `registry.add` alone would leave them green): the real registration step, driven
    /// against a scratch registry whose current context is another cluster, must save the
    /// bootstrapped cluster as current and announce it.
    @Test
    void registerClusterLocally_savesTheBootstrappedClusterAsCurrent_andAnnouncesIt() throws IOException {
        var registryPath = tempDir.resolve("clusters.toml");
        var out = new ByteArrayOutputStream();
        var originalOut = System.out;

        Files.writeString(registryPath,
                          "[current]\ncontext = \"old-dead\"\n\n[clusters.old-dead]\nendpoint = \"http://10.0.0.1:8080\"\n");
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            BootstrapPhasePost.registerClusterLocally(BootstrapPhasePostEndpointTest.context(true,
                                                                                             PortMapping.defaultPortMapping(),
                                                                                             "138.199.236.244"),
                                                      ClusterRegistry.load(registryPath));
        } finally {
            System.setOut(originalOut);
        }

        var saved = Files.readString(registryPath);

        assertThat(saved).as("the file the next command reads names the bootstrapped cluster as current")
                  .contains("context = \"endpoint-probe\"")
                  .contains("[clusters.endpoint-probe]")
                  .contains("endpoint = \"https://138.199.236.244:8080\"")
                  .contains("[clusters.old-dead]");
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Active cluster context: endpoint-probe");
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
