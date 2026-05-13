// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterEventLogKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterEventValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Promise;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;


/// RC1 Step 1 — publisher unit tests.
///
/// Covered:
/// - assigns `(epoch, nodeId, seq)` from suppliers; `seq` increments monotonically per publish
/// - originator nodeId stamped both on the value AND in the key (preventing cross-node
///   `(epoch, seq)` collisions — see `twoPublishers_sameEpochAndSeq_doNotCollideOnKey`)
/// - rate-cap drops excess events (high publish rate) without throwing
/// - `resetSeqForNewEpoch` resets the counter so a new epoch starts fresh
///
/// **Misleading-test note.** The earlier `publish_assignsMonotonicSequenceUnderConstantEpoch`
/// asserted only the per-node seq counter behaviour with ONE publisher, which left a
/// dangerous implicit assumption that `(epoch, seq)` was globally unique. It was not — the
/// `CapturingApplier` records every Put verbatim, exactly as Rabia commits would. With one
/// publisher you never observe the collision; the bug only manifests under two concurrent
/// publishers. The new regression test below makes the multi-writer invariant explicit.
class ClusterEventLogPublisherTest {

    private static final NodeId SELF = new NodeId("publisher-test-self");

    private static final class CapturingApplier implements java.util.function.Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> {
        final List<KVCommand<AetherKey>> captured = new ArrayList<>();

        @Override public Promise<List<Object>> apply(List<KVCommand<AetherKey>> commands) {
            captured.addAll(commands);
            return Promise.success(List.of());
        }
    }

    private static HlcClock newClock() {
        return HlcClock.hlcClock(SELF.id()).unwrap();
    }

    @Test
    void publish_assignsMonotonicSequenceUnderConstantEpoch() {
        var applier = new CapturingApplier();
        var publisher = ClusterEventLogPublisher.clusterEventLogPublisher(SELF, newClock(), () -> 7L, applier);

        for (var i = 0; i < 5; i++) {
            publisher.publish(ClusterEventValue.EventType.NODE_JOINED,
                              ClusterEventValue.Severity.INFO,
                              "evt-" + i,
                              Map.of());
        }

        assertThat(applier.captured).hasSize(5);
        for (var i = 0; i < 5; i++) {
            var put = (KVCommand.Put<?, ?>) applier.captured.get(i);
            var key = (ClusterEventLogKey) put.key();
            assertThat(key.epoch()).isEqualTo(7L);
            assertThat(key.nodeId()).isEqualTo(SELF);
            assertThat(key.seq()).isEqualTo((long) i);
        }
    }

    /// **Regression for cross-node `(epoch, seq)` collision.** Before adding `NodeId` to the
    /// key, two publishers on different nodes writing concurrent events at the same
    /// `(epoch, seq=0)` would produce two `KVCommand.Put`s with the SAME key — Rabia commits
    /// both verbatim and the KV-Store applies last-write-wins, silently dropping one event.
    ///
    /// With `nodeId` in the key, each publisher owns a disjoint sub-keyspace; the two Puts
    /// land at distinct keys and both events survive. This test asserts both writes are
    /// preserved and the resulting keys are distinct.
    @Test
    void twoPublishers_sameEpochAndSeq_doNotCollideOnKey() {
        var sharedApplier = new CapturingApplier();
        var nodeA = new NodeId("node-a");
        var nodeB = new NodeId("node-b");
        // Both publishers see the same epoch (1) and both start their local seq at 0.
        var publisherA = ClusterEventLogPublisher.clusterEventLogPublisher(nodeA, newClock(), () -> 1L, sharedApplier);
        var publisherB = ClusterEventLogPublisher.clusterEventLogPublisher(nodeB, newClock(), () -> 1L, sharedApplier);

        publisherA.publish(ClusterEventValue.EventType.NODE_JOINED,
                           ClusterEventValue.Severity.INFO,
                           "from-A",
                           Map.of());
        publisherB.publish(ClusterEventValue.EventType.NODE_JOINED,
                           ClusterEventValue.Severity.INFO,
                           "from-B",
                           Map.of());

        assertThat(sharedApplier.captured).hasSize(2);
        var keyA = (ClusterEventLogKey) ((KVCommand.Put<?, ?>) sharedApplier.captured.get(0)).key();
        var keyB = (ClusterEventLogKey) ((KVCommand.Put<?, ?>) sharedApplier.captured.get(1)).key();
        // Same (epoch, seq) — would have collided pre-fix.
        assertThat(keyA.epoch()).isEqualTo(1L);
        assertThat(keyB.epoch()).isEqualTo(1L);
        assertThat(keyA.seq()).isEqualTo(0L);
        assertThat(keyB.seq()).isEqualTo(0L);
        // Different nodeId sub-keyspaces — distinct KV keys, both writes survive.
        assertThat(keyA.nodeId()).isEqualTo(nodeA);
        assertThat(keyB.nodeId()).isEqualTo(nodeB);
        assertThat(keyA).isNotEqualTo(keyB);
        assertThat(keyA.asString()).isNotEqualTo(keyB.asString());
    }

    @Test
    void publish_stampsOriginatorNodeIdAndMessage() {
        var applier = new CapturingApplier();
        var publisher = ClusterEventLogPublisher.clusterEventLogPublisher(SELF, newClock(), () -> 1L, applier);

        publisher.publish(ClusterEventValue.EventType.ACCESS_DENIED,
                          ClusterEventValue.Severity.WARNING,
                          "hello",
                          Map.of("principal", "alice"));

        var put = (KVCommand.Put<?, ?>) applier.captured.getFirst();
        var value = (ClusterEventValue) put.value();
        assertThat(value.nodeId()).isEqualTo(SELF.id());
        assertThat(value.message()).isEqualTo("hello");
        assertThat(value.metadata()).containsEntry("principal", "alice");
        assertThat(value.version()).isEqualTo(ClusterEventValue.CURRENT_VERSION);
    }

    @Test
    void publish_rateCappedDropsExcessEvents_withoutThrowing() {
        var applier = new CapturingApplier();
        // Tight bucket: 1 token/sec capacity 2. Burst of 10 publishes will land 2, drop 8.
        var tokensPerSec = 1;
        var burst = 2;
        var fixedNano = new AtomicLong(0L);
        var publisher = ClusterEventLogPublisher.clusterEventLogPublisher(SELF,
                                                                            newClock(),
                                                                            () -> 1L,
                                                                            applier,
                                                                            tokensPerSec,
                                                                            burst,
                                                                            fixedNano::get);

        for (var i = 0; i < 10; i++) {
            publisher.publish(ClusterEventValue.EventType.NODE_JOINED,
                              ClusterEventValue.Severity.INFO,
                              "evt-" + i,
                              Map.of());
        }

        // With burst=2, exactly 2 events survive the rate-cap (no time progress between
        // publishes → no refill).
        assertThat(applier.captured).hasSize(burst);
        assertThat(publisher.droppedCount()).isEqualTo(10L - burst);
    }

    @Test
    void resetSeqForNewEpoch_restartsSeqCounter() {
        var applier = new CapturingApplier();
        var epoch = new AtomicLong(1L);
        var publisher = ClusterEventLogPublisher.clusterEventLogPublisher(SELF, newClock(), epoch::get, applier);

        publisher.publish(ClusterEventValue.EventType.NODE_JOINED, ClusterEventValue.Severity.INFO, "a", Map.of());
        publisher.publish(ClusterEventValue.EventType.NODE_JOINED, ClusterEventValue.Severity.INFO, "b", Map.of());

        epoch.set(2L);
        publisher.resetSeqForNewEpoch();

        publisher.publish(ClusterEventValue.EventType.NODE_JOINED, ClusterEventValue.Severity.INFO, "c", Map.of());

        var keys = applier.captured.stream().map(c -> (ClusterEventLogKey) ((KVCommand.Put<?, ?>) c).key()).toList();
        assertThat(keys.get(0).epoch()).isEqualTo(1L);
        assertThat(keys.get(0).nodeId()).isEqualTo(SELF);
        assertThat(keys.get(0).seq()).isEqualTo(0L);
        assertThat(keys.get(1).epoch()).isEqualTo(1L);
        assertThat(keys.get(1).nodeId()).isEqualTo(SELF);
        assertThat(keys.get(1).seq()).isEqualTo(1L);
        assertThat(keys.get(2).epoch()).isEqualTo(2L);
        assertThat(keys.get(2).nodeId()).isEqualTo(SELF);
        assertThat(keys.get(2).seq()).isEqualTo(0L);
    }
}
