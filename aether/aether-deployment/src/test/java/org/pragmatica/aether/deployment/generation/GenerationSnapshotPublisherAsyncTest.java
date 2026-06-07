// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GenerationSnapshotKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GenerationSnapshotValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;


/// Integration tests for [`GenerationSnapshotPublisher`] verifying the async publish path,
/// the FSM-driven coalescing of in-flight Marks, and proper handling of leader-loss
/// transitions during apply.
///
/// **Threading model**: a real `Executors.newSingleThreadExecutor()` is used so the apply
/// path runs off the dispatch thread (matching production semantics). The test uses the
/// `ManualPromiseClusterNode` to capture each in-flight apply and resolve its promise on
/// demand, which lets us verify intermediate FSM states (Publishing / PublishingDirty)
/// before the apply completes.
class GenerationSnapshotPublisherAsyncTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final long DEADLINE_MS = 2_000L;
    private static final long POLL_MS = 5L;

    @Test
    void disabled_ignoresMark_noApplyIssued() throws Exception {
        var fixture = newFixture();
        try {
            // No onLeaderGained() — publisher stays in Disabled.
            assertThat(fixture.publisher.currentState()).isInstanceOf(PublisherState.Disabled.class);
            fixture.publisher.markDirty();
            // Give the executor a chance to run any erroneously scheduled work.
            fixture.executor.submit(() -> {}).get(500, TimeUnit.MILLISECONDS);
            assertThat(fixture.cluster.applyCount()).isEqualTo(0);
            assertThat(fixture.publisher.currentState()).isInstanceOf(PublisherState.Disabled.class);
        } finally {
            fixture.shutdown();
        }
    }

    @Test
    void leaderGained_synthesizesMark_applyEmitsGenerationSnapshotPut() throws Exception {
        var fixture = newFixture();
        try {
            fixture.publisher.onLeaderGained();
            // Wait for the publisher to issue the synthetic apply.
            awaitApplyCount(fixture, 1);

            var batch = fixture.cluster.takeBatch();
            assertThat(batch).hasSize(1);
            assertThat(batch.get(0)).isInstanceOf(KVCommand.Put.class);
            var put = (KVCommand.Put<AetherKey, AetherValue>) batch.get(0);
            assertThat(put.key()).isEqualTo(GenerationSnapshotKey.SINGLETON);
            assertThat(put.value()).isInstanceOf(GenerationSnapshotValue.class);
            // FSM is Publishing while apply is in flight.
            assertThat(fixture.publisher.currentState()).isInstanceOf(PublisherState.Publishing.class);

            // Resolve the in-flight apply — FSM returns to Idle.
            fixture.cluster.completeNext();
            awaitState(fixture, PublisherState.Idle.class);
        } finally {
            fixture.shutdown();
        }
    }

    @Test
    void markDuringInFlightApply_coalescesIntoSingleReIteration() throws Exception {
        var fixture = newFixture();
        try {
            fixture.publisher.onLeaderGained();
            awaitApplyCount(fixture, 1);
            // First apply in flight — FSM is Publishing.
            assertThat(fixture.publisher.currentState()).isInstanceOf(PublisherState.Publishing.class);

            // Burst three Marks while first apply is unresolved — must collapse to one re-iteration.
            fixture.publisher.markDirty();
            fixture.publisher.markDirty();
            fixture.publisher.markDirty();
            awaitState(fixture, PublisherState.PublishingDirty.class);

            // Resolve first apply — coalesced PublishingDirty triggers exactly one second apply.
            fixture.cluster.completeNext();
            awaitApplyCount(fixture, 2);

            // Resolve the second apply — FSM returns to Idle. No further iterations.
            fixture.cluster.completeNext();
            awaitState(fixture, PublisherState.Idle.class);
            // Sanity drain.
            fixture.executor.submit(() -> {}).get(500, TimeUnit.MILLISECONDS);
            assertThat(fixture.cluster.applyCount()).isEqualTo(2);
        } finally {
            fixture.shutdown();
        }
    }

    @Test
    void leaderLostDuringInFlightApply_transitionsToDisabled_noFurtherIterations() throws Exception {
        var fixture = newFixture();
        try {
            fixture.publisher.onLeaderGained();
            awaitApplyCount(fixture, 1);
            assertThat(fixture.publisher.currentState()).isInstanceOf(PublisherState.Publishing.class);

            // Demote mid-apply.
            fixture.isLeader.set(false);
            fixture.publisher.onLeaderLost();
            awaitState(fixture, PublisherState.Disabled.class);

            // Resolve the in-flight apply — FSM stays Disabled (LeaderLost is sticky).
            fixture.cluster.completeNext();
            // Subsequent Marks must be ignored.
            fixture.publisher.markDirty();
            fixture.executor.submit(() -> {}).get(500, TimeUnit.MILLISECONDS);
            assertThat(fixture.cluster.applyCount()).isEqualTo(1);
            assertThat(fixture.publisher.currentState()).isInstanceOf(PublisherState.Disabled.class);
        } finally {
            fixture.shutdown();
        }
    }

    // ---- Awaiting helpers (no Awaitility on classpath; latch-based polling) ----

    /// Membership-v2 finale: the published snapshot's coreMembers are derived purely from
    /// `memberSupplier` presence (no synthetic per-node lifecycle). Address comes from the
    /// resolver; an unresolved peer defaults to empty host / zero port.
    @Test
    void publishedSnapshot_reflectsMemberSupplierPresence() throws Exception {
        var peerA = new NodeId("peer-a");
        var peerB = new NodeId("peer-b");
        var resolver = addressResolverOf(Map.of(SELF, address("10.0.0.1", 7001),
                                                peerA, address("10.0.0.2", 7002)));
        var fixture = newFixture(() -> Set.of(SELF, peerA, peerB),
                                 resolver);
        try {
            fixture.publisher.onLeaderGained();
            awaitApplyCount(fixture, 1);

            var snapshot = takeSnapshot(fixture);
            var coreMembers = snapshot.coreMembers();

            assertThat(coreMembers.keySet()).containsExactlyInAnyOrder(SELF, peerA, peerB);
            assertThat(coreMembers.get(SELF).host()).isEqualTo("10.0.0.1");
            assertThat(coreMembers.get(SELF).port()).isEqualTo(7001);
            // Address resolver returned none() for peer-b — defaults to empty host / zero port.
            assertThat(coreMembers.get(peerB).host()).isEmpty();
            assertThat(coreMembers.get(peerB).port()).isZero();

            fixture.cluster.completeNext();
            awaitState(fixture, PublisherState.Idle.class);
        } finally {
            fixture.shutdown();
        }
    }

    private static ClusterGenerationSnapshot takeSnapshot(Fixture fixture) {
        var batch = fixture.cluster.takeBatch();
        var put = (KVCommand.Put<AetherKey, AetherValue>) batch.get(0);
        return ((GenerationSnapshotValue) put.value()).snapshot();
    }

    private static NodeInfo address(String host, int port) {
        return NodeInfo.nodeInfo(new NodeId(host), new NodeAddress(host, port));
    }

    private static Function<NodeId, Option<NodeInfo>> addressResolverOf(Map<NodeId, NodeInfo> table) {
        return nodeId -> Option.option(table.get(nodeId));
    }

    /// RC1 Step 2: `onMembershipDecision` is the snapshot-then-tail subscription entry
    /// point. After leader-gained moves the FSM to Idle, a tail decision must trigger an
    /// apply identical to the one a direct `markDirty()` call would issue.
    @Test
    void onMembershipDecision_postLeaderGained_marksDirtyAndTriggersApply() throws Exception {
        var fixture = newFixture();
        try {
            fixture.publisher.onLeaderGained();
            awaitApplyCount(fixture, 1);
            fixture.cluster.completeNext();
            awaitState(fixture, PublisherState.Idle.class);
            assertThat(fixture.cluster.applyCount()).isEqualTo(1);

            // RC1 Step 2 tail-decision: triggers the same Mark path.
            fixture.publisher.onMembershipDecision(MembershipDecision.nodeJoining(
                    new NodeId("peer-a"),
                    List.of(SELF, new NodeId("peer-a"))));

            awaitApplyCount(fixture, 2);
            fixture.cluster.completeNext();
            awaitState(fixture, PublisherState.Idle.class);
        } finally {
            fixture.shutdown();
        }
    }

    private static void awaitApplyCount(Fixture f, int target) throws InterruptedException {
        var deadline = System.currentTimeMillis() + DEADLINE_MS;
        while (f.cluster.applyCount() < target) {
            if (System.currentTimeMillis() >= deadline) {
                throw new AssertionError("timed out waiting for applyCount >= " + target + ", was " + f.cluster.applyCount());
            }
            Thread.sleep(POLL_MS);
        }
    }

    private static void awaitState(Fixture f, Class<? extends PublisherState> expected) throws InterruptedException {
        var deadline = System.currentTimeMillis() + DEADLINE_MS;
        while (!expected.isInstance(f.publisher.currentState())) {
            if (System.currentTimeMillis() >= deadline) {
                throw new AssertionError("timed out waiting for state " + expected.getSimpleName() + ", was " + f.publisher.currentState());
            }
            Thread.sleep(POLL_MS);
        }
    }

    // ---- Fixture wiring ----

    private static Fixture newFixture() {
        return newFixture(() -> Set.of(SELF), nodeId -> Option.none());
    }

    private static Fixture newFixture(Supplier<Set<NodeId>> memberSupplier,
                                      Function<NodeId, Option<NodeInfo>> addressResolver) {
        var router = MessageRouter.mutable();
        KVStore<AetherKey, AetherValue> kvStore = new KVStore<>(router, NoOpSerializer.INSTANCE, NoOpDeserializer.INSTANCE);
        var hlcClock = HlcClock.hlcClock(new NodeId("test-self"));
        var projector = ClusterGenerationProjector.clusterGenerationProjector();
        var isLeader = new AtomicBoolean(true);
        AtomicReference<Map<AetherKey, AetherValue>> kvRef = new AtomicReference<>(new HashMap<>());
        // Provide a copy each call so the publisher's snapshot supplier matches production semantics.
        java.util.function.Supplier<Map<AetherKey, AetherValue>> kvSupplier = () -> Map.copyOf(kvRef.get());
        java.util.function.Supplier<Map<NodeId, org.pragmatica.aether.slice.generation.HealthHint>> healthHints = Map::of;
        var cluster = new ManualPromiseClusterNode();
        var executor = Executors.newSingleThreadExecutor();
        var publisher = GenerationSnapshotPublisher.generationSnapshotPublisher(isLeader::get,
                                                                                () -> 1L,
                                                                                hlcClock,
                                                                                projector,
                                                                                healthHints,
                                                                                kvSupplier,
                                                                                kvStore,
                                                                                cluster,
                                                                                executor,
                                                                                memberSupplier,
                                                                                addressResolver);
        return new Fixture(publisher, isLeader, cluster, executor);
    }

    private static final class Fixture {
        final GenerationSnapshotPublisher publisher;
        final AtomicBoolean isLeader;
        final ManualPromiseClusterNode cluster;
        final ExecutorService executor;

        Fixture(GenerationSnapshotPublisher publisher,
                AtomicBoolean isLeader,
                ManualPromiseClusterNode cluster,
                ExecutorService executor) {
            this.publisher = publisher;
            this.isLeader = isLeader;
            this.cluster = cluster;
            this.executor = executor;
        }

        void shutdown() {
            executor.shutdownNow();
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /// Cluster-node stub that exposes each in-flight apply as a manually-resolvable Promise.
    /// Tests call `completeNext()` to resolve the oldest unresolved apply, which lets them
    /// observe the FSM state while applies are mid-flight.
    private static final class ManualPromiseClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final List<List<KVCommand<AetherKey>>> batches = new ArrayList<>();
        private final List<Promise<List<Object>>> pending = new ArrayList<>();

        @Override public NodeId self() {return SELF;}

        @Override public TopologyManager topologyManager() {
            throw new UnsupportedOperationException("not used");
        }

        @Override public Promise<Unit> start() {return Promise.success(Unit.unit());}

        @Override public Promise<Unit> stop() {return Promise.success(Unit.unit());}

        @SuppressWarnings({"unchecked", "rawtypes"})
        @Override public synchronized <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            batches.add(List.copyOf(commands));
            Promise<List<Object>> promise = Promise.promise();
            pending.add(promise);
            return (Promise) promise;
        }

        synchronized int applyCount() {return batches.size();}

        synchronized List<KVCommand<AetherKey>> takeBatch() {
            return batches.get(batches.size() - 1);
        }

        synchronized void completeNext() {
            for (var p : pending) {
                if (!p.isResolved()) {
                    p.succeed(List.of());
                    return;
                }
            }
            throw new IllegalStateException("no pending apply to complete");
        }
    }

    private enum NoOpSerializer implements Serializer {
        INSTANCE;

        @Override public <T> void write(ByteBuf byteBuf, T object) {}
    }

    private enum NoOpDeserializer implements Deserializer {
        INSTANCE;

        @Override public <T> T read(ByteBuf byteBuf) {return null;}
    }

    // Reference to silence unused-import warnings if static helpers above don't need them.
    @SuppressWarnings("unused")
    private static ClusterGenerationSnapshot dummyEpochRef() {
        return ClusterGenerationSnapshot.empty(0L);
    }

    @SuppressWarnings("unused")
    private static Epoch dummyEpoch() {
        return Epoch.epoch(0L, 0L);
    }
}
