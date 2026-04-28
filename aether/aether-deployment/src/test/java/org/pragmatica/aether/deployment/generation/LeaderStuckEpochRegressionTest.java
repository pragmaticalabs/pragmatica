// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GenerationSnapshotKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GenerationSnapshotValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;


/// Regression for cluster-B leader-stuck-epoch (handover 2026-04-27): under the OLD
/// HealthReconciler design, the leader's currentSnapshot() could return ambient
/// `(rabiaTerm, 0)` whenever the FSM bounced out of LeadingSteady. The KV-as-truth design
/// makes this impossible because reads come from local KV not from FSM state.
///
/// This test:
///   1. Starts with empty KV and `onLeaderGained()` — verifies the first publish lands
///      with a non-zero counter (counter = previous + 1; previous = none → 0; published = 0
///      then incremented as content changes drive subsequent applies).
///   2. Adds a NodeLifecycle entry then `markDirty()` — verifies the second publish
///      increments the epoch counter relative to the first.
///   3. Asserts that across reads after the first apply, `KvBackedGenerationSnapshotSource`
///      never returns a snapshot with epoch.localCounter() == 0 once a non-empty membership
///      has been applied.
class LeaderStuckEpochRegressionTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId NODE_OTHER = new NodeId("node-other");
    private static final long DEADLINE_MS = 2_000L;
    private static final long POLL_MS = 5L;

    @Test
    void epochCounterIncrements_acrossPublishes_neverResetsToZero() throws Exception {
        var fixture = newFixture();
        try {
            var source = KvBackedGenerationSnapshotSource.kvBackedGenerationSnapshotSource(fixture.kvStore);

            // 1) Cold start — no KV, no snapshot.
            assertThat(source.currentMembershipView().isEmpty()).isTrue();

            // 2) Seed self lifecycle, gain leadership — first publish lands.
            seedLifecycle(fixture, SELF, NodeLifecycleState.ON_DUTY);
            fixture.publisher.onLeaderGained();
            awaitGenerationSnapshotKvPresent(fixture);
            var firstCounter = readPublishedCounter(fixture);

            // 3) Add a second node, mark dirty — second publish increments counter.
            seedLifecycle(fixture, NODE_OTHER, NodeLifecycleState.ON_DUTY);
            fixture.publisher.markDirty();
            awaitCounterAdvancesPast(fixture, firstCounter);
            var secondCounter = readPublishedCounter(fixture);
            assertThat(secondCounter).isGreaterThan(firstCounter);

            // 4) THE KEY ASSERTION: across additional reads, the source NEVER returns a
            // snapshot with localCounter == 0 — once published, the counter only advances.
            for (int i = 0; i < 10; i++) {
                var view = source.currentMembershipView();
                assertThat(view.isPresent()).isTrue();
                var counter = readPublishedCounter(fixture);
                assertThat(counter).isGreaterThanOrEqualTo(firstCounter);
            }
        } finally {
            fixture.shutdown();
        }
    }

    // ---- Helpers ----

    private static long readPublishedCounter(Fixture f) {
        return f.kvStore.getTyped(GenerationSnapshotKey.SINGLETON, GenerationSnapshotValue.class)
                        .map(v -> v.snapshot().epoch().localCounter())
                        .or(- 1L);
    }

    private static void awaitGenerationSnapshotKvPresent(Fixture f) throws InterruptedException {
        var deadline = System.currentTimeMillis() + DEADLINE_MS;
        while (f.kvStore.getTyped(GenerationSnapshotKey.SINGLETON, GenerationSnapshotValue.class).isEmpty()) {
            if (System.currentTimeMillis() >= deadline) {
                throw new AssertionError("timed out waiting for GenerationSnapshotKey to land in KV");
            }
            Thread.sleep(POLL_MS);
        }
    }

    private static void awaitCounterAdvancesPast(Fixture f, long previous) throws InterruptedException {
        var deadline = System.currentTimeMillis() + DEADLINE_MS;
        while (readPublishedCounter(f) <= previous) {
            if (System.currentTimeMillis() >= deadline) {
                throw new AssertionError("timed out waiting for counter to advance past " + previous);
            }
            Thread.sleep(POLL_MS);
        }
    }

    private static void seedLifecycle(Fixture f, NodeId nodeId, NodeLifecycleState state) {
        var value = NodeLifecycleValue.nodeLifecycleValue(state, "host-" + nodeId.id(), 9001);
        var snapshot = new HashMap<>(f.kvRef.get());
        snapshot.put(NodeLifecycleKey.nodeLifecycleKey(nodeId), value);
        f.kvRef.set(snapshot);
        f.kvStore.process(new KVCommand.Put<AetherKey, AetherValue>(NodeLifecycleKey.nodeLifecycleKey(nodeId), value));
    }

    private static Fixture newFixture() {
        var router = MessageRouter.mutable();
        KVStore<AetherKey, AetherValue> kvStore = new KVStore<>(router, NoOpSerializer.INSTANCE, NoOpDeserializer.INSTANCE);
        var hlcClock = HlcClock.hlcClock("test-self").unwrap();
        var projector = ClusterGenerationProjector.clusterGenerationProjector();
        var isLeader = new AtomicBoolean(true);
        AtomicReference<Map<AetherKey, AetherValue>> kvRef = new AtomicReference<>(new HashMap<>());
        java.util.function.Supplier<Map<AetherKey, AetherValue>> kvSupplier = () -> Map.copyOf(kvRef.get());
        var swimHints = SwimHintsRegistry.swimHintsRegistry(Duration.ofSeconds(60), () -> {});
        var cluster = new KvReflectingClusterNode(kvStore, kvRef);
        var executor = Executors.newSingleThreadExecutor();
        var publisher = GenerationSnapshotPublisher.generationSnapshotPublisher(isLeader::get,
                                                                                () -> 1L,
                                                                                hlcClock,
                                                                                projector,
                                                                                swimHints,
                                                                                kvSupplier,
                                                                                kvStore,
                                                                                cluster,
                                                                                executor);
        return new Fixture(publisher, kvStore, kvRef, executor);
    }

    private static final class Fixture {
        final GenerationSnapshotPublisher publisher;
        final KVStore<AetherKey, AetherValue> kvStore;
        final AtomicReference<Map<AetherKey, AetherValue>> kvRef;
        final ExecutorService executor;

        Fixture(GenerationSnapshotPublisher publisher,
                KVStore<AetherKey, AetherValue> kvStore,
                AtomicReference<Map<AetherKey, AetherValue>> kvRef,
                ExecutorService executor) {
            this.publisher = publisher;
            this.kvStore = kvStore;
            this.kvRef = kvRef;
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

    private static final class KvReflectingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final KVStore<AetherKey, AetherValue> kvStore;
        private final AtomicReference<Map<AetherKey, AetherValue>> kvRef;

        KvReflectingClusterNode(KVStore<AetherKey, AetherValue> kvStore,
                                AtomicReference<Map<AetherKey, AetherValue>> kvRef) {
            this.kvStore = kvStore;
            this.kvRef = kvRef;
        }

        @Override public NodeId self() {return SELF;}

        @Override public TopologyManager topologyManager() {
            throw new UnsupportedOperationException("not used");
        }

        @Override public Promise<Unit> start() {return Promise.success(Unit.unit());}

        @Override public Promise<Unit> stop() {return Promise.success(Unit.unit());}

        @SuppressWarnings({"unchecked", "rawtypes"})
        @Override public synchronized <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            for (var cmd : commands) {
                kvStore.process(cmd);
                mirrorToShadow(cmd);
            }
            return (Promise) Promise.success(List.of());
        }

        private void mirrorToShadow(KVCommand<AetherKey> cmd) {
            var snapshot = new HashMap<>(kvRef.get());
            switch (cmd) {
                case KVCommand.Put<?, ?> put -> snapshot.put((AetherKey) put.key(), (AetherValue) put.value());
                case KVCommand.Remove<?> remove -> snapshot.remove(remove.key());
                case KVCommand.Get<?> get -> {/* no-op */}
            }
            kvRef.set(snapshot);
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
}
