// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GenerationSnapshotKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GenerationSnapshotValue;
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


/// End-to-end roundtrip: publisher → cluster.apply → KVStore → KvBackedGenerationSnapshotSource.
///
/// The `KvReflectingClusterNode` stub mirrors each apply batch into the local KV-Store via
/// `KVStore.process(...)`, which is the production semantic for a single-node cluster
/// (Rabia replication is the cross-node concern; the local apply is a synchronous KV mutation
/// with no externally-visible difference for this test).
class GenerationSnapshotKvRoundtripTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final long DEADLINE_MS = 2_000L;
    private static final long POLL_MS = 5L;

    @Test
    void publisherApplyLandsInKv_andSourceReadsBackMembershipView() throws Exception {
        var fixture = newFixture();
        try {
            // Seed a single present member so the projection produces a non-empty membership view.
            seedMember(fixture, SELF);

            fixture.publisher.markDirty();          // dropped — Disabled
            fixture.publisher.onLeaderGained();      // → Idle → synthetic Mark → Publishing → apply
            awaitGenerationSnapshotKvPresent(fixture);

            // KvBackedGenerationSnapshotSource reads through to the same KV.
            var source = KvBackedGenerationSnapshotSource.kvBackedGenerationSnapshotSource(fixture.kvStore);
            var view = source.currentMembershipView();
            assertThat(view.isPresent()).isTrue();
            view.onPresent(v -> {
                assertThat(v.coreMemberIds()).contains(SELF);
                assertThat(v.onDutyMemberIds()).contains(SELF);
                assertThat(v.healthyOnDutyCount()).isEqualTo(1);
            });
        } finally {
            fixture.shutdown();
        }
    }

    // ---- Helpers ----

    private static void seedMember(Fixture f, NodeId nodeId) {
        f.memberSeed.add(nodeId);
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

    private static Fixture newFixture() {
        var router = MessageRouter.mutable();
        KVStore<AetherKey, AetherValue> kvStore = new KVStore<>(router, NoOpSerializer.INSTANCE, NoOpDeserializer.INSTANCE);
        var hlcClock = HlcClock.hlcClock(new NodeId("test-self"));
        var projector = ClusterGenerationProjector.clusterGenerationProjector();
        var isLeader = new AtomicBoolean(true);
        AtomicReference<Map<AetherKey, AetherValue>> kvRef = new AtomicReference<>(new HashMap<>());
        java.util.function.Supplier<Map<AetherKey, AetherValue>> kvSupplier = () -> Map.copyOf(kvRef.get());
        var swimHints = SwimHintsRegistry.swimHintsRegistry(Duration.ofSeconds(60), () -> {});
        var cluster = new KvReflectingClusterNode(kvStore, kvRef);
        var executor = Executors.newSingleThreadExecutor();
        // Membership-v2 finale: membership is SWIM/NTT-derived presence; the node-lifecycle KV
        // atom is gone. Tests seed a plain in-memory member set; the member supplier derives its
        // set directly so the roundtrip intent (SELF present → snapshot view) is preserved.
        var memberSeed = java.util.concurrent.ConcurrentHashMap.<NodeId>newKeySet();
        var publisher = GenerationSnapshotPublisher.generationSnapshotPublisher(isLeader::get,
                                                                                () -> 1L,
                                                                                hlcClock,
                                                                                projector,
                                                                                swimHints,
                                                                                kvSupplier,
                                                                                kvStore,
                                                                                cluster,
                                                                                executor,
                                                                                () -> java.util.Set.copyOf(memberSeed),
                                                                                nodeId -> org.pragmatica.lang.Option.none());
        return new Fixture(publisher, kvStore, kvRef, executor, memberSeed);
    }

    private static final class Fixture {
        final GenerationSnapshotPublisher publisher;
        final KVStore<AetherKey, AetherValue> kvStore;
        final AtomicReference<Map<AetherKey, AetherValue>> kvRef;
        final ExecutorService executor;
        final java.util.Set<NodeId> memberSeed;

        Fixture(GenerationSnapshotPublisher publisher,
                KVStore<AetherKey, AetherValue> kvStore,
                AtomicReference<Map<AetherKey, AetherValue>> kvRef,
                ExecutorService executor,
                java.util.Set<NodeId> memberSeed) {
            this.publisher = publisher;
            this.kvStore = kvStore;
            this.kvRef = kvRef;
            this.executor = executor;
            this.memberSeed = memberSeed;
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

    /// Cluster-node stub whose `apply(...)` mirrors each command into the local KV-Store
    /// (mimics single-node Rabia commit semantics), then resolves the returned Promise.
    private static final class KvReflectingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final KVStore<AetherKey, AetherValue> kvStore;
        private final AtomicReference<Map<AetherKey, AetherValue>> kvRef;

        KvReflectingClusterNode(KVStore<AetherKey, AetherValue> kvStore, AtomicReference<Map<AetherKey, AetherValue>> kvRef) {
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
            kvStore.process(kvStore.createBatch(commands));
            for (var cmd : commands) {
                applyToShadow(cmd);
            }
            return (Promise) Promise.success(List.of());
        }

        private void applyToShadow(KVCommand<AetherKey> cmd) {
            // Mirror into the snapshot map so the publisher's `kvSnapshotSupplier` reflects writes.
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
