// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.cluster.metrics;

import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing;
import org.pragmatica.consensus.NodeId;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `ClusterSyncPing` carries the leader's GLOBAL `drainNodes` set alongside the global
/// `evictionHints` set. The compact constructor defensively copies both (null → empty); the
/// legacy factory defaults `drainNodes` to the empty set.
class ClusterSyncPingTest {
    private static final NodeId SENDER = NodeId.nodeId("sender").unwrap();
    private static final NodeId PEER_A = NodeId.nodeId("peer-a").unwrap();
    private static final NodeId PEER_B = NodeId.nodeId("peer-b").unwrap();

    @Test
    void constructor_nullDrainNodes_defaultsToEmptySet() {
        var ping = new ClusterSyncPing(SENDER, Map.of(), 1L, 1L, 0L, Set.of(), null);

        assertThat(ping.drainNodes()).isEmpty();
    }

    @Test
    void constructor_drainNodes_isPreserved() {
        var ping = new ClusterSyncPing(SENDER, Map.of(), 1L, 1L, 0L, Set.of(), Set.of(PEER_A, PEER_B));

        assertThat(ping.drainNodes()).containsExactlyInAnyOrder(PEER_A, PEER_B);
    }

    @Test
    void constructor_drainNodes_isDefensivelyCopied() {
        var mutable = new HashSet<NodeId>();
        mutable.add(PEER_A);
        var ping = new ClusterSyncPing(SENDER, Map.of(), 1L, 1L, 0L, Set.of(), mutable);

        mutable.add(PEER_B);

        assertThat(ping.drainNodes()).containsExactly(PEER_A);
    }

    @Test
    void constructor_drainNodes_isImmutable() {
        var ping = new ClusterSyncPing(SENDER, Map.of(), 1L, 1L, 0L, Set.of(), Set.of(PEER_A));

        assertThatThrownBy(() -> ping.drainNodes().add(PEER_B))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void legacyFactory_defaultsDrainNodesToEmptySet() {
        var ping = ClusterSyncPing.clusterSyncPing(SENDER, Map.of());

        assertThat(ping.drainNodes()).isEmpty();
        assertThat(ping.evictionHints()).isEmpty();
    }
}
