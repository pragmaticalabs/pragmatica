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
        final List<KVCommand<AetherKey>> appliedCommands = new ArrayList<>();

        LoopbackHarness(NodeId nodeId) {
            this(nodeId, true);
        }

        LoopbackHarness(NodeId nodeId, boolean isLeader) {
            var hlc = HlcClock.hlcClock(nodeId.id()).unwrap();
            this.publisher = ClusterEventLogPublisher.clusterEventLogPublisher(nodeId,
                                                                                hlc,
                                                                                epoch::get,
                                                                                this::apply);
            this.aggregator = ClusterEventAggregator.clusterEventAggregator(ClusterEventAggregatorConfig.defaultConfig(),
                                                                             () -> CLUSTER_SIZE,
                                                                             publisher,
                                                                             () -> isLeader);
            aggregator.markReplayComplete();
        }

        @SuppressWarnings({"unchecked", "rawtypes"}) Promise<List<Object>> apply(List<KVCommand<AetherKey>> commands) {
            for (var cmd : commands) {
                appliedCommands.add(cmd);
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

        h.aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.STOPPED));

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
        h.aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.STOPPED));

        var events = h.aggregator.events();
        assertThat(events).hasSize(2);
        assertThat(events.get(0).type()).isEqualTo(ClusterEventValue.EventType.NODE_LIFECYCLE_CHANGED);
        assertThat(events.get(0).details()).containsEntry("transition", "NONE->DRAINING");
        assertThat(events.get(1).type()).isEqualTo(ClusterEventValue.EventType.NODE_LEFT);
    }

    @Test
    void onNodeLifecyclePut_idempotentOnSameState_noDoubleEmit() {
        var h = new LoopbackHarness(NODE_A);

        h.aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.STOPPED));
        h.aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.STOPPED));
        h.aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.STOPPED));

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
    /// receives every committed event via the materialised-view subscriber. Total order on
    /// the reader is established by **commit-arrival order** (Rabia does not rewrite keys
    /// in production — the publisher's `(epoch, nodeId, seq)` triple lands on KV verbatim).
    ///
    /// **Pre-fix misleading harness.** An earlier version of this test had the shared
    /// applier rewrite `seq` to a global monotonic value, masking the production bug where
    /// two nodes writing the same `(epoch, seq)` collide on KV. With `nodeId` now in the key
    /// each writer's keys are inherently disjoint and no rewrite is needed; the harness
    /// preserves the original publisher-assigned key verbatim and asserts arrival order.
    @Test
    void twoWriters_interleavedEvents_observedInCommitOrderOnReader() {
        var readerEpoch = new AtomicLong(1L);
        var commitOrder = new ArrayList<ClusterEventLogKey>();

        var readerAggregator = ClusterEventAggregator.clusterEventAggregator(ClusterEventAggregatorConfig.defaultConfig(),
                                                                              () -> CLUSTER_SIZE,
                                                                              org.pragmatica.aether.api.ClusterEventLogPublisher.clusterEventLogPublisher(
                                                                                  new NodeId("reader"),
                                                                                  HlcClock.hlcClock("reader").unwrap(),
                                                                                  readerEpoch::get,
                                                                                  cmds -> Promise.success(List.of())),
                                                                              () -> true);
        readerAggregator.markReplayComplete();

        // Production-faithful shared applier: passes publisher-assigned keys through
        // verbatim (no seq rewrite). Cross-node total order on the reader is established by
        // arrival order — exactly the contract Rabia delivers.
        java.util.function.Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> sharedApplier = commands -> {
            for (var cmd : commands) {
                if (cmd instanceof KVCommand.Put<?, ?> put && put.key() instanceof ClusterEventLogKey clk) {
                    var value = (ClusterEventValue) put.value();
                    commitOrder.add(clk);
                    @SuppressWarnings({"unchecked", "rawtypes"}) var valuePut = (ValuePut) new ValuePut<>(new KVCommand.Put<>(clk, value), Option.none());
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

        // Invariant 1 — every commit-order key is unique (no cross-node collision). Pre-fix
        // (no nodeId in key) writer A and writer B would emit identical `(epoch, seq=0)`
        // pairs and collide on KV; this set-size assertion would fail.
        assertThat(commitOrder).hasSize(6);
        assertThat(java.util.Set.copyOf(commitOrder)).hasSize(6);

        // Invariant 2 — within each node's sub-keyspace, seqs are strictly monotone in
        // commit order. Cross-node ordering is provided by commit-arrival order, not by
        // the seq value.
        var lastSeqPerNode = new java.util.HashMap<NodeId, Long>();
        for (var key : commitOrder) {
            var prior = lastSeqPerNode.put(key.nodeId(), key.seq());
            if (prior != null) {
                assertThat(key.seq())
                    .as("per-node seq must be strictly monotone for %s", key.nodeId())
                    .isGreaterThan(prior);
            }
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

    /// Regression for bug H1 (Step 1 chaos diagnostic, 2026-05-13): every node receiving
    /// the lifecycle KV-put notification used to call `publisher.publish(NODE_FAILED, ...)`,
    /// which submits a leader-bound KVCommand. During kill-leader windows the applier fails
    /// across all followers and the NODE_FAILED event is lost. Only the leader should
    /// publish derived events.
    @Test
    void onNodeLifecyclePut_nonLeader_doesNotPublish() {
        var h = new LoopbackHarness(NODE_A, false);

        h.aggregator.onNodeLifecyclePut(lifecyclePut(NODE_B, NodeLifecycleState.STOPPED));

        assertThat(h.appliedCommands).isEmpty();
        assertThat(h.aggregator.events()).isEmpty();
    }

    @Test
    void onNodeLifecyclePut_leader_publishesNodeFailed() {
        var h = new LoopbackHarness(NODE_A, true);

        h.aggregator.onNodeLifecyclePut(lifecyclePut(NODE_B, NodeLifecycleState.STOPPED));

        assertThat(h.aggregator.events()).hasSize(1);
        assertThat(h.aggregator.events().getFirst().type()).isEqualTo(ClusterEventValue.EventType.NODE_FAILED);
        assertThat(h.aggregator.events().getFirst().details()).containsEntry("nodeId", NODE_B.id());
    }

    @Test
    void replayActive_suppressesDownstreamFanOut_butStillPopulatesView() {
        // Aggregator constructed with replayActive=true; we feed it directly via
        // onClusterEventLogPut as if KVStore.restoreSnapshot were doing the replay.
        var aggregator = ClusterEventAggregator.clusterEventAggregator(ClusterEventAggregatorConfig.defaultConfig(),
                                                                        () -> CLUSTER_SIZE);
        var key = ClusterEventLogKey.clusterEventLogKey(1L, new NodeId("replayer"), 0L);
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
