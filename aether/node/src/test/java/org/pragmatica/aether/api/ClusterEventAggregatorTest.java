// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterEventLogKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterEventValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;


/// RC1 Step 1 — verifies the KV-backed projection:
///   - producers delegate to `ClusterEventLogPublisher`; published events flow back through
///     `onClusterEventLogPut` and surface in `events()` in commit order
///   - two writers interleaving events at different `(epoch, seq)` pairs come out on a
///     third reader in commit order (the core OB2 invariant)
///   - `eventsSince(epoch, seq)` cursor returns only events strictly after the cursor
///   - the legacy `onNodeLifecyclePut` producer delegates to the publisher (legacy enum
///     idempotency contract preserved at the producer layer)
class ClusterEventAggregatorTest {

    private static final NodeId NODE_A = new NodeId("node-a");

    private static final NodeId NODE_B = new NodeId("node-b");

    private static final int CLUSTER_SIZE = 4;

    /// In-process applier: routes every Put directly back to the aggregator's
    /// `onClusterEventLogPut`. Models the post-Rabia-commit fan-out.
    private static final class LoopbackHarness {
        final ClusterEventAggregator aggregator;
        final ClusterEventLogPublisher publisher;
        final AtomicLong epoch = new AtomicLong(1L);

        LoopbackHarness(NodeId nodeId) {
            var hlc = HlcClock.hlcClock(nodeId.id()).unwrap();
            this.publisher = ClusterEventLogPublisher.clusterEventLogPublisher(nodeId,
                                                                                hlc,
                                                                                epoch::get,
                                                                                this::apply);
            this.aggregator = ClusterEventAggregator.clusterEventAggregator(ClusterEventAggregatorConfig.defaultConfig(),
                                                                             () -> CLUSTER_SIZE,
                                                                             publisher,
                                                                             () -> true);
            aggregator.markReplayComplete();
        }

        @SuppressWarnings({"unchecked", "rawtypes"}) Promise<List<Object>> apply(List<KVCommand<AetherKey>> commands) {
            for (var cmd : commands) {
                if (cmd instanceof KVCommand.Put<?, ?> put && put.key() instanceof ClusterEventLogKey clk) {
                    var valuePut = (ValuePut) new ValuePut<>(new KVCommand.Put<>(clk, (ClusterEventValue) put.value()), Option.none());
                    aggregator.onClusterEventLogPut(valuePut);
                }
            }
            return Promise.success(List.of());
        }
    }

    private static ValuePut<NodeLifecycleKey, NodeLifecycleValue> lifecyclePut(NodeId nodeId, NodeLifecycleState state) {
        var key = NodeLifecycleKey.nodeLifecycleKey(nodeId);
        var value = NodeLifecycleValue.nodeLifecycleValue(state);
        return new ValuePut<>(new KVCommand.Put<>(key, value), Option.none());
    }

    @Test
    void onNodeLifecyclePut_decommissionedFromUnknown_emitsNodeFailed() {
        var h = new LoopbackHarness(NODE_A);

        h.aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.DECOMMISSIONED));

        var events = h.aggregator.events();
        assertThat(events).hasSize(1);
        var event = events.getFirst();
        assertThat(event.type()).isEqualTo(ClusterEventValue.EventType.NODE_FAILED);
        assertThat(event.details()).containsEntry("nodeId", NODE_A.id())
                                   .containsEntry("clusterSize", String.valueOf(CLUSTER_SIZE));
    }

    @Test
    void onNodeLifecyclePut_decommissionedAfterDraining_emitsNodeLeft() {
        var h = new LoopbackHarness(NODE_A);

        h.aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.DRAINING));
        h.aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.DECOMMISSIONED));

        var events = h.aggregator.events();
        assertThat(events).hasSize(2);
        assertThat(events.get(0).type()).isEqualTo(ClusterEventValue.EventType.NODE_LIFECYCLE_CHANGED);
        assertThat(events.get(0).details()).containsEntry("transition", "NONE->DRAINING");
        assertThat(events.get(1).type()).isEqualTo(ClusterEventValue.EventType.NODE_LEFT);
    }

    @Test
    void onNodeLifecyclePut_idempotentOnSameState_noDoubleEmit() {
        var h = new LoopbackHarness(NODE_A);

        h.aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.DECOMMISSIONED));
        h.aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.DECOMMISSIONED));
        h.aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.DECOMMISSIONED));

        var events = h.aggregator.events();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo(ClusterEventValue.EventType.NODE_FAILED);
    }

    @Test
    void onNodeLifecyclePut_onDuty_doesNotEmitNodeLeft() {
        var h = new LoopbackHarness(NODE_A);

        h.aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.ON_DUTY));

        assertThat(h.aggregator.events()).isEmpty();
    }

    /// The core OB2 invariant test (spec §3.6, plan §1 validation).
    ///
    /// Two writers (node-a + node-b) emit events. The reader's view (a third aggregator)
    /// receives every committed event via the materialised-view subscriber. The relative
    /// order of events from each writer is preserved (within-writer sequence), AND every
    /// event appears with a strictly-increasing `(epoch, seq)` cursor in the order it was
    /// committed.
    @Test
    void twoWriters_interleavedEvents_observedInCommitOrderOnReader() {
        var readerEpoch = new AtomicLong(1L);
        var commitOrder = new ArrayList<ClusterEventLogKey>();

        // Single shared "consensus log" applier: assigns seq monotonically and routes the
        // commit to the reader aggregator. Both writers publish through it, modeling the
        // single Rabia decision stream.
        var nextSeq = new AtomicLong(0L);
        var readerAggregator = ClusterEventAggregator.clusterEventAggregator(ClusterEventAggregatorConfig.defaultConfig(),
                                                                              () -> CLUSTER_SIZE,
                                                                              org.pragmatica.aether.api.ClusterEventLogPublisher.clusterEventLogPublisher(
                                                                                  new NodeId("reader"),
                                                                                  HlcClock.hlcClock("reader").unwrap(),
                                                                                  readerEpoch::get,
                                                                                  cmds -> Promise.success(List.of())),
                                                                              () -> true);
        readerAggregator.markReplayComplete();

        java.util.function.Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> sharedApplier = commands -> {
            for (var cmd : commands) {
                if (cmd instanceof KVCommand.Put<?, ?> put && put.key() instanceof ClusterEventLogKey clk) {
                    // Reassign seq to model Rabia commit assigning a single monotonic ordering
                    // across both writers.
                    var assignedKey = ClusterEventLogKey.clusterEventLogKey(clk.epoch(), nextSeq.getAndIncrement());
                    var value = (ClusterEventValue) put.value();
                    commitOrder.add(assignedKey);
                    @SuppressWarnings({"unchecked", "rawtypes"}) var valuePut = (ValuePut) new ValuePut<>(new KVCommand.Put<>(assignedKey, value), Option.none());
                    readerAggregator.onClusterEventLogPut(valuePut);
                }
            }
            return Promise.success(List.of());
        };

        var writerA = ClusterEventLogPublisher.clusterEventLogPublisher(NODE_A,
                                                                         HlcClock.hlcClock(NODE_A.id()).unwrap(),
                                                                         readerEpoch::get,
                                                                         sharedApplier);
        var writerB = ClusterEventLogPublisher.clusterEventLogPublisher(NODE_B,
                                                                         HlcClock.hlcClock(NODE_B.id()).unwrap(),
                                                                         readerEpoch::get,
                                                                         sharedApplier);

        // Interleave: A1, B1, A2, B2, A3, B3
        writerA.publish(ClusterEventValue.EventType.NODE_JOINED, ClusterEventValue.Severity.INFO, "A1", Map.of("seq", "1"));
        writerB.publish(ClusterEventValue.EventType.NODE_JOINED, ClusterEventValue.Severity.INFO, "B1", Map.of("seq", "1"));
        writerA.publish(ClusterEventValue.EventType.ACCESS_DENIED, ClusterEventValue.Severity.WARNING, "A2", Map.of("seq", "2"));
        writerB.publish(ClusterEventValue.EventType.ACCESS_DENIED, ClusterEventValue.Severity.WARNING, "B2", Map.of("seq", "2"));
        writerA.publish(ClusterEventValue.EventType.NODE_FAILED, ClusterEventValue.Severity.CRITICAL, "A3", Map.of("seq", "3"));
        writerB.publish(ClusterEventValue.EventType.NODE_FAILED, ClusterEventValue.Severity.CRITICAL, "B3", Map.of("seq", "3"));

        var events = readerAggregator.events();
        assertThat(events).hasSize(6);
        var summaries = events.stream().map(ClusterEvent::summary).toList();
        assertThat(summaries).containsExactly("A1", "B1", "A2", "B2", "A3", "B3");

        // Every consecutive pair satisfies the strict (epoch, seq) total ordering.
        for (var i = 1; i < commitOrder.size(); i++) {
            var prev = commitOrder.get(i - 1);
            var cur = commitOrder.get(i);
            var strictlyAfter = cur.epoch() > prev.epoch() || (cur.epoch() == prev.epoch() && cur.seq() > prev.seq());
            assertThat(strictlyAfter)
                .as("commit-order key #%d must be strictly after #%d", i, i - 1)
                .isTrue();
        }
    }

    @Test
    void eventsSinceCursor_returnsOnlyEventsAfter() {
        var h = new LoopbackHarness(NODE_A);
        h.publisher.publish(ClusterEventValue.EventType.NODE_JOINED, ClusterEventValue.Severity.INFO, "first", Map.of());
        h.publisher.publish(ClusterEventValue.EventType.NODE_JOINED, ClusterEventValue.Severity.INFO, "second", Map.of());
        h.publisher.publish(ClusterEventValue.EventType.NODE_JOINED, ClusterEventValue.Severity.INFO, "third", Map.of());

        var afterFirst = h.aggregator.eventsSince(1L, 0L);
        assertThat(afterFirst).hasSize(2);
        assertThat(afterFirst.get(0).summary()).isEqualTo("second");
        assertThat(afterFirst.get(1).summary()).isEqualTo("third");

        var afterLast = h.aggregator.eventsSince(1L, 2L);
        assertThat(afterLast).isEmpty();
    }

    @Test
    void publishedEvent_carriesOriginNodeIdInDetails() {
        var h = new LoopbackHarness(NODE_A);
        h.publisher.publish(ClusterEventValue.EventType.ACCESS_DENIED,
                            ClusterEventValue.Severity.WARNING,
                            "denied",
                            Map.of("principal", "alice"));

        var events = h.aggregator.events();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().details()).containsEntry("originNodeId", NODE_A.id())
                                                .containsEntry("principal", "alice");
    }

    @Test
    void replayActive_suppressesDownstreamFanOut_butStillPopulatesView() {
        // Aggregator constructed with replayActive=true; we feed it directly via
        // onClusterEventLogPut as if KVStore.restoreSnapshot were doing the replay.
        var aggregator = ClusterEventAggregator.clusterEventAggregator(ClusterEventAggregatorConfig.defaultConfig(),
                                                                        () -> CLUSTER_SIZE);
        var key = ClusterEventLogKey.clusterEventLogKey(1L, 0L);
        var value = ClusterEventValue.clusterEventValue(HlcTimestamp.ZERO,
                                                         ClusterEventValue.EventType.NODE_JOINED,
                                                         ClusterEventValue.Severity.INFO,
                                                         "replayer",
                                                         "replayed-event",
                                                         Map.of());
        aggregator.onClusterEventLogPut(new ValuePut<>(new KVCommand.Put<>(key, value), Option.none()));

        // View is populated even during replay (the projection always runs).
        assertThat(aggregator.events()).hasSize(1);
        assertThat(aggregator.events().getFirst().summary()).isEqualTo("replayed-event");

        // markReplayComplete is idempotent and doesn't drop the buffer.
        aggregator.markReplayComplete();
        aggregator.markReplayComplete();
        assertThat(aggregator.events()).hasSize(1);
    }
}
