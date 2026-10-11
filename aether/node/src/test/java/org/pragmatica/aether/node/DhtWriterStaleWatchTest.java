// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.OperationalEvent;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.ConsistentHashRing;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.dht.DHTNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #1777 (owner rule, v1882 round 4): a node whose DHT writes the replication-change fence keeps refusing as stale
/// announces it once past the bound, and announces the resolution once it adopts the change — one emitter, the subject.
class DhtWriterStaleWatchTest {
    private static final NodeId SELF = new NodeId("writer-1");
    private static final long BOUND = DhtReplicationSettlement.OVERDUE_AFTER_MS;

    @Test
    void refusedPastTheBound_announcesOnce_thenResolvesOnAdoption() {
        var node = node();
        var events = new ArrayList<OperationalEvent>();
        var now = new long[]{1_000L};
        var watch = DhtWriterStaleWatch.dhtWriterStaleWatch(SELF, node, events::add, () -> now[0]);

        node.noteStaleRefusal(DHTNode.NO_CHANGE, 1_000L);
        watch.tick();
        assertThat(events).as("within the bound: a change in progress, not a condition").isEmpty();

        now[0] = 1_000L + BOUND + 1;
        watch.tick();
        watch.tick();
        node.noteStaleRefusal(DHTNode.NO_CHANGE, now[0]);
        watch.tick();

        assertThat(events).as("entered once, deduped across ticks and further refusals").hasSize(1);
        assertThat(events.getFirst()).isInstanceOfSatisfying(OperationalEvent.DhtWriterStale.class,
                                                              event -> assertThat(event.nodeId()).isEqualTo(SELF.id()));

        node.adoptReplicationChange(7L, DHTConfig.DEFAULT);
        watch.tick();
        watch.tick();

        assertThat(events).hasSize(2);
        assertThat(events.get(1)).isInstanceOf(OperationalEvent.DhtWriterStaleResolved.class);
    }

    @Test
    void refusalThatEndsWithinTheBound_announcesNothing() {
        var node = node();
        List<OperationalEvent> events = new ArrayList<>();
        var watch = DhtWriterStaleWatch.dhtWriterStaleWatch(SELF, node, events::add, () -> 2_000L);

        node.noteStaleRefusal(DHTNode.NO_CHANGE, 1_000L);
        watch.tick();
        node.adoptReplicationChange(7L, DHTConfig.DEFAULT);
        watch.tick();

        assertThat(events).isEmpty();
    }

    private static DHTNode node() {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();

        ring.addNode(SELF);

        return DHTNode.dhtNode(SELF, memoryStorageEngine(), ring, DHTConfig.DEFAULT);
    }
}
