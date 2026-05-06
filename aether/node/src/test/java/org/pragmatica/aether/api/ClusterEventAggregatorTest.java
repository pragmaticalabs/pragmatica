// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.StreamAccess.StreamEvent;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.stream.FrameworkStreamConsumer;
import org.pragmatica.aether.slice.stream.FrameworkStreamConsumers;
import org.pragmatica.aether.slice.stream.FrameworkStreamPublisher;
import org.pragmatica.aether.slice.stream.FrameworkStreamPublishers;
import org.pragmatica.aether.slice.stream.StreamAddress;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/// Verifies the post-Wave-5B-ii [`ClusterEventAggregator`] now backed by
/// `FrameworkStreamPublisher`/`FrameworkStreamConsumer` for `system:cluster-events:1.0.0`:
///   - each `on*` handler publishes the expected `ClusterEvent` variant into the system stream
///   - `events()` returns the published events via the consumer
///   - `eventsSince(Instant)` filters by timestamp
///   - the deferred-binding scenario: when the publisher supplier returns `null` during the
///     bootstrap window, `on*` handlers must log-only and NOT throw
///   - the `onNodeLifecyclePut` KV bridge: `DRAINING -> DECOMMISSIONED` emits `NodeLeft`,
///     `ON_DUTY -> DECOMMISSIONED` emits `NodeFailed`, `unknown -> DECOMMISSIONED` emits
///     `NodeFailed`, idempotency on snapshot replay
class ClusterEventAggregatorTest {

    private static final NodeId NODE_A = new NodeId("node-a");
    private static final NodeId SELF = new NodeId("self-node");
    private static final int CLUSTER_SIZE = 4;

    private static final StreamAddress CLUSTER_EVENTS_ADDRESS =
            StreamAddress.streamAddress("system:cluster-events:1.0.0").unwrap();

    private List<ClusterEvent> backing;
    private AtomicLong nextOffset;
    private FrameworkStreamPublisher<ClusterEvent> publisher;
    private FrameworkStreamConsumer<ClusterEvent> consumer;

    @BeforeEach
    void setUp() {
        backing = new ArrayList<>();
        nextOffset = new AtomicLong();
        publisher = FrameworkStreamPublishers.<ClusterEvent>testPublisher(CLUSTER_EVENTS_ADDRESS, backing::add).unwrap();
        consumer = FrameworkStreamConsumers.<ClusterEvent>testConsumer(CLUSTER_EVENTS_ADDRESS, this::snapshotAsStreamEvents).unwrap();
    }

    private List<StreamEvent<ClusterEvent>> snapshotAsStreamEvents() {
        var snapshot = List.copyOf(backing);
        return snapshot.stream()
                       .map(payload -> new StreamEvent<>(nextOffset.getAndIncrement(),
                                                         payload.timestamp().toEpochMilli(),
                                                         0,
                                                         payload))
                       .toList();
    }

    private ClusterEventAggregator newAggregator() {
        return ClusterEventAggregator.clusterEventAggregator(() -> publisher,
                                                             () -> consumer,
                                                             SELF,
                                                             EventIdAllocator.eventIdAllocator(SELF),
                                                             () -> CLUSTER_SIZE);
    }

    private ClusterEventAggregator newAggregator(Supplier<FrameworkStreamPublisher<ClusterEvent>> publisherSupplier,
                                                 Supplier<FrameworkStreamConsumer<ClusterEvent>> consumerSupplier) {
        return ClusterEventAggregator.clusterEventAggregator(publisherSupplier,
                                                             consumerSupplier,
                                                             SELF,
                                                             EventIdAllocator.eventIdAllocator(SELF),
                                                             () -> CLUSTER_SIZE);
    }

    private static ValuePut<NodeLifecycleKey, NodeLifecycleValue> lifecyclePut(NodeId nodeId, NodeLifecycleState state) {
        var key = NodeLifecycleKey.nodeLifecycleKey(nodeId);
        var value = NodeLifecycleValue.nodeLifecycleValue(state);
        return new ValuePut<>(new KVCommand.Put<>(key, value), Option.none());
    }

    private List<ClusterEvent> readEvents(ClusterEventAggregator aggregator) {
        return aggregator.events().await().unwrap();
    }

    private List<ClusterEvent> readEventsSince(ClusterEventAggregator aggregator, Instant since) {
        return aggregator.eventsSince(since).await().unwrap();
    }

    @Test
    void onNodeLifecyclePut_decommissionedFromUnknown_emitsNodeFailed() {
        var aggregator = newAggregator();

        aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.DECOMMISSIONED));

        var events = readEvents(aggregator);
        assertThat(events).hasSize(1);
        var event = events.getFirst();
        assertThat(event).isInstanceOf(ClusterEvent.NodeFailed.class);
        assertThat(event.details()).containsEntry("nodeId", NODE_A.id())
                                   .containsEntry("clusterSize", String.valueOf(CLUSTER_SIZE));
    }

    @Test
    void onNodeLifecyclePut_decommissionedAfterOnDuty_emitsNodeFailed() {
        var aggregator = newAggregator();

        aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.ON_DUTY));
        aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.DECOMMISSIONED));

        var events = readEvents(aggregator);
        assertThat(events).hasSize(1);
        var event = events.getFirst();
        assertThat(event).isInstanceOf(ClusterEvent.NodeFailed.class);
        assertThat(event.details()).containsEntry("nodeId", NODE_A.id())
                                   .containsEntry("clusterSize", String.valueOf(CLUSTER_SIZE));
    }

    @Test
    void onNodeLifecyclePut_decommissionedAfterDraining_emitsNodeLeft() {
        var aggregator = newAggregator();

        aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.DRAINING));
        aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.DECOMMISSIONED));

        var events = readEvents(aggregator);
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isInstanceOf(ClusterEvent.NodeLifecycleChanged.class);
        assertThat(events.get(0).details()).containsEntry("transition", "NONE->DRAINING");
        assertThat(events.get(1)).isInstanceOf(ClusterEvent.NodeLeft.class);
        assertThat(events.get(1).details()).containsEntry("nodeId", NODE_A.id())
                                           .containsEntry("clusterSize", String.valueOf(CLUSTER_SIZE));
    }

    @Test
    void onNodeLifecyclePut_draining_emitsNodeLifecycleChanged() {
        var aggregator = newAggregator();

        aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.DRAINING));

        var events = readEvents(aggregator);
        assertThat(events).hasSize(1);
        var event = events.getFirst();
        assertThat(event).isInstanceOf(ClusterEvent.NodeLifecycleChanged.class);
        assertThat(event.details()).containsEntry("nodeId", NODE_A.id())
                                   .containsEntry("transition", "NONE->DRAINING");
    }

    @Test
    void onNodeLifecyclePut_idempotentOnSameState_noDoubleEmit() {
        var aggregator = newAggregator();

        aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.DECOMMISSIONED));
        aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.DECOMMISSIONED));
        aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.DECOMMISSIONED));

        var events = readEvents(aggregator);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).isInstanceOf(ClusterEvent.NodeFailed.class);
    }

    @Test
    void onNodeLifecyclePut_onDuty_doesNotEmit() {
        var aggregator = newAggregator();

        aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.ON_DUTY));

        assertThat(readEvents(aggregator)).isEmpty();
    }

    @Test
    void emittedEvents_carryEventIdAndSourceNode() {
        var aggregator = newAggregator();

        aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.DECOMMISSIONED));

        var event = readEvents(aggregator).getFirst();
        assertThat(event.id()).isNotNull();
        assertThat(event.id().nodeId()).isEqualTo(SELF);
        assertThat(event.id().sequence()).isPositive();
        assertThat(event.sourceNode()).isEqualTo(SELF);
    }

    @Test
    void emittedEvents_haveMonotonicallyIncreasingSequences() {
        var aggregator = newAggregator();

        aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.DRAINING));
        aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.DECOMMISSIONED));

        var events = readEvents(aggregator);
        assertThat(events).hasSize(2);
        assertThat(events.get(0).id().sequence()).isLessThan(events.get(1).id().sequence());
    }

    @Test
    void events_returnsEmpty_whenConsumerSupplierReturnsNull() {
        var aggregator = newAggregator(() -> publisher, () -> null);

        aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.DECOMMISSIONED));

        assertThat(readEvents(aggregator)).isEmpty();
    }

    @Test
    void emit_isLogOnly_whenPublisherSupplierReturnsNull_duringBootstrap() {
        var aggregator = newAggregator(() -> null, () -> consumer);

        aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.DECOMMISSIONED));

        assertThat(backing).isEmpty();
        assertThat(readEvents(aggregator)).isEmpty();
    }

    /// Reviewer test gap #18 — deferred-binding lifecycle.
    ///
    /// During AetherNode bootstrap the aggregator is constructed before the local stream stack
    /// exists, so its publisher/consumer suppliers can return `null` for an arbitrary window. Once
    /// the stack binds, subsequent reads must see events that were published after the binding.
    /// This test pins that lifecycle: while suppliers return `null` the first read is empty, then
    /// after late-binding the suppliers a follow-up read returns the events published in the
    /// post-binding window.
    @Test
    void events_returnsResults_afterDeferredBindingResolves() {
        var publisherRef = new AtomicReference<FrameworkStreamPublisher<ClusterEvent>>();
        var consumerRef = new AtomicReference<FrameworkStreamConsumer<ClusterEvent>>();
        var aggregator = newAggregator(publisherRef::get, consumerRef::get);

        // Pre-binding: emit is dropped, fetch returns empty.
        aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.DECOMMISSIONED));
        assertThat(readEvents(aggregator)).isEmpty();

        // Bind both sides — same backing list and offset counter as the stub setUp uses.
        publisherRef.set(publisher);
        consumerRef.set(consumer);

        // Post-binding: the new event lands in the stream and the next fetch surfaces it.
        aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.DRAINING));
        var events = readEvents(aggregator);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).isInstanceOf(ClusterEvent.NodeLifecycleChanged.class);
    }

    @Test
    void eventsSince_filtersByTimestamp() throws InterruptedException {
        var aggregator = newAggregator();

        aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.DRAINING));
        Thread.sleep(5);
        var cutoff = Instant.now();
        Thread.sleep(5);
        aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.DECOMMISSIONED));

        var events = readEventsSince(aggregator, cutoff);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).isInstanceOf(ClusterEvent.NodeLeft.class);
    }

    @Test
    void eventsSince_returnsAll_whenSinceIsBeforeAllEvents() {
        var aggregator = newAggregator();
        var beforeAll = Instant.now().minusSeconds(60);

        aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.DRAINING));
        aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.DECOMMISSIONED));

        assertThat(readEventsSince(aggregator, beforeAll)).hasSize(2);
    }

    @Test
    void eventsSince_returnsEmpty_whenSinceIsAfterAllEvents() {
        var aggregator = newAggregator();

        aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.DRAINING));
        aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.DECOMMISSIONED));
        var afterAll = Instant.now().plusSeconds(60);

        assertThat(readEventsSince(aggregator, afterAll)).isEmpty();
    }
}
