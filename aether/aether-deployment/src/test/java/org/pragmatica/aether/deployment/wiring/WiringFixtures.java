// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import io.netty.buffer.ByteBuf;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterEffect;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

/// Shared builders for the wiring tests: identities, a real `KVStore` applier, a scriptable `ClusterNode`, and transactions.
final class WiringFixtures {
    static final NodeId SELF = NodeId.nodeId("core-1").unwrap();
    static final NodeId OTHER = NodeId.nodeId("core-2").unwrap();
    static final Artifact ORDERS = Artifact.artifact("org.example:orders:1.0.0").unwrap();
    static final Artifact BILLING = Artifact.artifact("org.example:billing:2.0.0").unwrap();
    static final Artifact AUDIT = Artifact.artifact("org.example:audit:3.0.0").unwrap();
    static final long SELF_TOKEN = 0x5E1FL;
    static final long BOOT_INCARNATION = 1_760_000_000_000L;
    static final ProcessIncarnation PROCESS = new ProcessIncarnation(BOOT_INCARNATION, SELF_TOKEN);
    static final Generation G1 = new Generation(5L, 1L);
    static final LeaderValue LEADER = new LeaderValue(SELF, 7L);

    private WiringFixtures() {}

    /// A real applier whose notifications go nowhere unless the test routes them.
    static KVStore<AetherKey, AetherValue> store(MessageRouter.MutableRouter router) {
        return new KVStore<>(router, new Serializer() {
            @Override
            public <T> void write(ByteBuf buffer, T value) {}
        }, new Deserializer() {
            @Override
            public <T> T read(ByteBuf buffer) {
                return null;
            }
        });
    }

    static KVStore<AetherKey, AetherValue> store() {
        return store(MessageRouter.mutable());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void commitLeader(KVStore<AetherKey, AetherValue> store, LeaderValue leader) {
        var raw = (KVStore) store;

        raw.process(raw.createBatch(List.of(new KVCommand.Put(LeaderKey.INSTANCE, leader))));
    }

    static SliceDesiredValue desired(SliceGoal goal) {
        return new SliceDesiredValue(goal, G1, BOOT_INCARNATION);
    }

    static SliceDesiredKey desiredKey(Artifact artifact) {
        return SliceDesiredKey.sliceDesiredKey(SELF, artifact);
    }

    /// A one-mutation `LeaderTransaction` that writes `goal` for `artifact`, expecting what `store` holds now.
    static KVCommand.LeaderTransaction<AetherKey, AetherValue> put(KVStore<AetherKey, AetherValue> store,
                                                                    String id,
                                                                    LeaderValue leader,
                                                                    Artifact artifact,
                                                                    SliceGoal goal) {
        var key = desiredKey(artifact);

        return new KVCommand.LeaderTransaction<>(key,
                                                 id,
                                                 leader,
                                                 List.of(),
                                                 List.of(new KVCommand.Mutation<>(key, store.get(key), Option.some(desired(goal)))));
    }

    /// A transaction guarded on `guard`'s current value and writing `target`.
    static KVCommand.LeaderTransaction<AetherKey, AetherValue> guarded(KVStore<AetherKey, AetherValue> store,
                                                                        String id,
                                                                        LeaderValue leader,
                                                                        Artifact guard,
                                                                        Artifact target) {
        var guardKey = desiredKey(guard);
        var targetKey = desiredKey(target);

        return new KVCommand.LeaderTransaction<>(targetKey,
                                                 id,
                                                 leader,
                                                 List.of(new KVCommand.ReadWitness<>(guardKey, store.get(guardKey).map(v -> (Object) v))),
                                                 List.of(new KVCommand.Mutation<>(targetKey,
                                                                                  store.get(targetKey),
                                                                                  Option.some(desired(SliceGoal.ACTIVE)))));
    }

    static ClusterEffect.Submit submit(KVCommand.LeaderTransaction<AetherKey, AetherValue> transaction, long incarnation) {
        return new ClusterEffect.Submit(transaction.transactionId(), incarnation, ClusterModel.SubmitKind.GOALS, transaction);
    }

    /// A `ClusterNode` whose `apply` is scripted and recorded; `self()` is [#SELF].
    static final class StubCluster implements ClusterNode<KVCommand<AetherKey>> {
        final List<List<KVCommand<AetherKey>>> applied = new ArrayList<>();
        Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> script = _ -> Promise.success(List.of());

        @Override
        public NodeId self() {
            return SELF;
        }

        @Override
        public TopologyManager topologyManager() {
            throw new UnsupportedOperationException("not used by the wiring tests");
        }

        @Override
        public Promise<Unit> start() {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.success(Unit.unit());
        }

        @SuppressWarnings("unchecked")
        @Override
        public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            applied.add(List.copyOf(commands));

            return (Promise<List<R>>)(Promise<?>) script.apply(commands);
        }
    }

    /// The real applier driven the way consensus drives it: every batch is committed at the next slot, so notifications carry a revision.
    static final class Applier {
        private final KVStore<AetherKey, AetherValue> store;
        private final AtomicLong slot;

        Applier(KVStore<AetherKey, AetherValue> store) {
            this.store = store;
            this.slot = new AtomicLong(store.committedRevision());
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        synchronized List<Object> commit(List<? extends KVCommand<AetherKey>> commands) {
            var raw = (KVStore) store;

            return (List<Object>) raw.processCommitted(raw.createBatch(List.copyOf(commands)), slot.incrementAndGet());
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        synchronized void commitLeader(LeaderValue leader) {
            var raw = (KVStore) store;

            raw.processCommitted(raw.createBatch(List.of(new KVCommand.Put(LeaderKey.INSTANCE, leader))), slot.incrementAndGet());
        }

        void put(AetherKey key, AetherValue value) {
            commit(List.of(new KVCommand.Put<AetherKey, AetherValue>(key, value)));
        }

        long slot() {
            return slot.get();
        }
    }

    /// A `ClusterNode` over a real applier: `apply` commits the batch and returns the applier's results.
    static StubCluster over(KVStore<AetherKey, AetherValue> store) {
        return over(new Applier(store));
    }

    static StubCluster over(Applier applier) {
        var cluster = new StubCluster();

        cluster.script = commands -> Promise.success(applier.commit(commands));

        return cluster;
    }
}
