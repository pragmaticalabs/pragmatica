// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.ReplicationDefaultsConfig;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.ConsistentHashRing;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.dht.DHTNode;
import org.pragmatica.hlc.HlcClock;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #1777 track 1: the node's DHT takes its factors from the cluster's committed `[replication]` section and the
/// cache namespace from `[cache]`, never from the node-local placeholder it boots with.
class AetherNodeDhtReplicationTest {
    private static final NodeId SELF = new NodeId("node-0");

    @Test
    void applyDhtReplication_committedFactors_replaceThePlaceholder() {
        var node = awaiting();
        var cache = new AtomicReference<>(DHTConfig.CACHE_DEFAULT);

        AetherNode.applyDhtReplication(new ReplicationDefaultsConfig(5, 3, 1, 2, 1, ReplicationDefaultsConfig.DEFAULT_TOMBSTONE_RETENTION),
                                       node,
                                       DHTConfig.DEFAULT,
                                       DHTConfig.CACHE_DEFAULT,
                                       cache);

        assertThat(node.replicationResolved()).isTrue();
        assertThat(node.config().replicationFactor()).isEqualTo(5);
        assertThat(node.config().writeQuorum()).isEqualTo(3);
        assertThat(node.config().readQuorum()).isEqualTo(3);
        assertThat(cache.get().replicationFactor()).isEqualTo(2);
        assertThat(cache.get().writeQuorum()).isEqualTo(1);
        assertThat(cache.get().readQuorum()).isEqualTo(2);
    }

    /// #1777 track 3: the tombstone retention is cluster-wide like the factors — every replica must agree which
    /// tombstones have expired.
    @Test
    void applyDhtReplication_committedTombstoneRetention_reachesTheNode() {
        var node = awaiting();
        var retention = org.pragmatica.lang.io.TimeSpan.timeSpan(2).hours();

        AetherNode.applyDhtReplication(new ReplicationDefaultsConfig(3, 2, 1, 1, 1, retention),
                                       node,
                                       DHTConfig.DEFAULT,
                                       DHTConfig.CACHE_DEFAULT,
                                       new AtomicReference<>());

        assertThat(node.tombstoneRetention()).isEqualTo(retention);
    }

    @Test
    void applyDhtReplication_builtInDefaults_areRf3Cf2AndASingleCopyCache() {
        var node = awaiting();
        var cache = new AtomicReference<>(DHTConfig.DEFAULT);

        AetherNode.applyDhtReplication(ReplicationDefaultsConfig.BUILT_IN, node, DHTConfig.DEFAULT, DHTConfig.CACHE_DEFAULT, cache);

        assertThat(node.config().replicationFactor()).isEqualTo(3);
        assertThat(node.config().writeQuorum()).isEqualTo(2);
        assertThat(node.config().readQuorum()).isEqualTo(2);
        assertThat(cache.get().replicationFactor()).isEqualTo(1);
    }

    /// Two nodes booted with different node-local placeholders place identically once both read the same
    /// committed factors — the property a node-local `target_rf` could not give.
    @Test
    void applyDhtReplication_differentPlaceholders_resolveToTheSamePlacement() {
        var first = awaiting(DHTConfig.DEFAULT);
        var second = awaiting(DHTConfig.SINGLE_NODE);
        var committed = new ReplicationDefaultsConfig(5, 3, 1, 1, 1, ReplicationDefaultsConfig.DEFAULT_TOMBSTONE_RETENTION);

        AetherNode.applyDhtReplication(committed, first, DHTConfig.DEFAULT, DHTConfig.CACHE_DEFAULT, new AtomicReference<>());
        AetherNode.applyDhtReplication(committed, second, DHTConfig.SINGLE_NODE, DHTConfig.CACHE_DEFAULT, new AtomicReference<>());

        assertThat(first.config().replicationFactor()).isEqualTo(second.config().replicationFactor());
        assertThat(first.config().writeQuorum()).isEqualTo(second.config().writeQuorum());
        assertThat(first.config().readQuorum()).isEqualTo(second.config().readQuorum());
    }

    @Test
    void applyDhtReplication_declaredFullReplication_isKept() {
        var node = DHTNode.dhtNode(SELF, memoryStorageEngine(), ring(), DHTConfig.FULL);

        AetherNode.applyDhtReplication(ReplicationDefaultsConfig.BUILT_IN, node, DHTConfig.FULL, DHTConfig.CACHE_DEFAULT, new AtomicReference<>());

        assertThat(node.config().isFullReplication()).isTrue();
    }

    private static DHTNode awaiting() {
        return awaiting(DHTConfig.DEFAULT);
    }

    private static DHTNode awaiting(DHTConfig placeholder) {
        return DHTNode.dhtNodeAwaitingReplication(SELF, memoryStorageEngine(), ring(), placeholder, HlcClock.hlcClock(SELF));
    }

    private static ConsistentHashRing<NodeId> ring() {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();

        for (int i = 0; i < 5; i++) {
            ring.addNode(new NodeId("node-" + i));
        }

        return ring;
    }
}
