// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.pragmatica.aether.environment.PeerInfo;

import static org.assertj.core.api.Assertions.assertThat;

/// RFC-0017 stage 4 — the pure half of cloud core self-assembly: mapping provider-discovered
/// instances to consensus seed peers.
///
/// The port on every produced `NodeInfo` is the LOCAL cluster port, never the instance's
/// `aether-port` label — that label is applied by `registerSelf` only AFTER a node joins, so it
/// cannot exist during pre-formation discovery, while every core shares one cluster port by
/// config composition. The `aether-node-id` label is create-stamped
/// (`HetznerComputeProvider.labelsFor`), which is what makes discovery able to produce full
/// `NodeInfo` before any node has ever joined.
class MainDiscoveredPeersTest {
    private static final int CLUSTER_PORT = 8090;

    private static PeerInfo instance(String host, String nodeId, String role) {
        return new PeerInfo(host, 9100, Map.of("aether-node-id", nodeId, "aether-role", role));
    }

    @Test
    void discoveredCorePeers_mapsLabelledCores_toSeedPeers_onTheLocalClusterPort() {
        var discovered = List.of(instance("10.0.0.2", "eu-core-0", "core"),
                                 instance("10.0.0.3", "eu-core-1", "core"));

        var peers = Main.discoveredCorePeers(discovered, CLUSTER_PORT);

        assertThat(peers).hasSize(2);
        assertThat(peers.getFirst().id().id()).isEqualTo("eu-core-0");
        assertThat(peers.getFirst().address().port())
                .as("port must be the local cluster port, not the instance's label port (9100)")
                .isEqualTo(CLUSTER_PORT);
        assertThat(peers.getFirst().address().host()).isEqualTo("10.0.0.2");
    }

    /// Workers and spot instances share the cluster label — they must never become consensus seeds.
    @Test
    void discoveredCorePeers_excludesNonCoreRoles() {
        var discovered = List.of(instance("10.0.0.2", "eu-core-0", "core"),
                                 instance("10.0.0.9", "eu-worker-0", "worker"),
                                 instance("10.0.0.10", "eu-spot-0", "spot"));

        assertThat(Main.discoveredCorePeers(discovered, CLUSTER_PORT))
                .extracting(node -> node.id().id())
                .containsExactly("eu-core-0");
    }

    /// An instance without the create-stamped node-id label cannot be addressed as a consensus
    /// peer — a pre-#579 VM or a foreign server matching the cluster label must be skipped, not
    /// guessed at.
    @Test
    void discoveredCorePeers_skipsInstancesWithoutNodeIdLabel() {
        var unlabelled = new PeerInfo("10.0.0.4", 9100, Map.of("aether-role", "core"));
        var discovered = List.of(instance("10.0.0.2", "eu-core-0", "core"), unlabelled);

        assertThat(Main.discoveredCorePeers(discovered, CLUSTER_PORT)).hasSize(1);
    }

    /// Every core derives the same seed list from the same provider state: duplicates collapse
    /// (first wins) and the result is sorted by node id.
    @Test
    void discoveredCorePeers_deduplicatesById_andSortsDeterministically() {
        var discovered = List.of(instance("10.0.0.5", "eu-core-2", "core"),
                                 instance("10.0.0.2", "eu-core-0", "core"),
                                 instance("10.0.0.6", "eu-core-2", "core"),
                                 instance("10.0.0.3", "eu-core-1", "core"));

        var peers = Main.discoveredCorePeers(discovered, CLUSTER_PORT);

        assertThat(peers).extracting(node -> node.id().id())
                         .containsExactly("eu-core-0", "eu-core-1", "eu-core-2");
        assertThat(peers.get(2).address().host())
                .as("first occurrence of a duplicated id wins")
                .isEqualTo("10.0.0.5");
    }

    @Test
    void discoveredCorePeers_emptyDiscovery_yieldsEmptyList() {
        assertThat(Main.discoveredCorePeers(List.of(), CLUSTER_PORT)).isEmpty();
    }

    /// The timeout gate: a MAJORITY of the expected set may proceed (Rabia forms on quorum; one
    /// dead VM must not deadlock every healthy node), below it the node refuses loudly.
    @Test
    void sufficientAtTimeout_requiresMajorityOfExpected() {
        assertThat(Main.sufficientAtTimeout(2, 3)).isTrue();
        assertThat(Main.sufficientAtTimeout(1, 3)).isFalse();
        assertThat(Main.sufficientAtTimeout(3, 5)).isTrue();
        assertThat(Main.sufficientAtTimeout(2, 5)).isFalse();
        assertThat(Main.sufficientAtTimeout(1, 1)).isTrue();
        assertThat(Main.sufficientAtTimeout(0, 1)).isFalse();
    }
}
