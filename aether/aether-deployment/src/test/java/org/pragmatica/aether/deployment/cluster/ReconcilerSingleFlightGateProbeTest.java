// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.LateResolution;
import org.pragmatica.aether.deployment.cluster.NodeReplacementPlanner.DrainState;
import org.pragmatica.aether.deployment.cluster.NodeReplacementPlanner.Effect;
import org.pragmatica.aether.deployment.cluster.NodeReplacementPlanner.Observation;
import org.pragmatica.aether.deployment.cluster.NodeReplacementPlanner.Timings;
import org.pragmatica.aether.deployment.cluster.NodeReplacementReconciler.EffectResult;
import org.pragmatica.aether.deployment.cluster.UpgradeRunPlanner.Member;
import org.pragmatica.aether.deployment.cluster.UpgradeRunReconciler.BeginResult;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.UpgradeRunState;
import org.pragmatica.aether.slice.kvstore.AetherValue.UpgradeRunValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.UpgradeStop;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.rabia.ClusterConfig;
import org.pragmatica.consensus.rabia.VoterConfiguration;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;

/// The four reconcilers that hold a single-flight `running` flag release it as a DEPENDENT step of the promise they
/// hand to the caller. A caller that awaits a tick and calls `reconcile()` again must therefore find the flag released.
/// Released by an `onResultRun` handler instead (which runs on another thread, concurrently with the caller's next step),
/// the second call is silently turned away: it returns at once and does not reach the environment.
///
/// Every fake here resolves LATER, on another thread ([LateResolution]): with a fake that returns an already-resolved
/// promise the handler runs inline on the caller and the defect cannot show. Each probe makes [TRIALS] await-then-reconcile
/// pairs and counts how many reached the environment; the handler form lost most of them.
class ReconcilerSingleFlightGateProbeTest {
    private static final int TRIALS = 40;

    private static final NodeId A = new NodeId("a");
    private static final NodeId B = new NodeId("b");
    private static final NodeId C = new NodeId("c");
    private static final NodeId D = new NodeId("d");
    private static final VoterConfiguration CURRENT = new VoterConfiguration(0, new ClusterConfig(List.of(A, B, C)));

    @Test
    void coreVoterReconciler_reconcileRightAfterAwait_reachesTheReconfiguration() {
        var calls = new AtomicInteger();
        var reconciler = CoreVoterReconciler.coreVoterReconciler(A,
                                                                 () -> true,
                                                                 () -> Option.some(CURRENT),
                                                                 () -> Option.some(CURRENT),
                                                                 () -> 3,
                                                                 () -> Set.of(A, B, D),
                                                                 Map::of,
                                                                 () -> Set.of(C),
                                                                 roster -> {
                                                                     calls.incrementAndGet();

                                                                     return LateResolution.later(Unit.unit());
                                                                 });

        for (var trial = 0; trial < TRIALS; trial++) {
            reconciler.reconcile().await();
            reconciler.reconcile().await();
        }

        assertThat(calls.get()).as("every reconcile after an awaited one must run, not be turned away by the gate")
                               .isEqualTo(2 * TRIALS);
    }

    private static final NodeId OLD = new NodeId("core-OLD");
    private static final NodeId NEW = new NodeId("core-NEW");
    private static final Timings TIMINGS = new Timings(10_000, 10_000, 10_000, 10_000, 0, 10_000, 10_000);

    @Test
    void nodeReplacementReconciler_reconcileRightAfterAwait_reachesTheEffect() {
        var executions = new AtomicInteger();
        var record = new NodeReplacementValue(NEW, "core", NodeReplacementPhase.PROVISIONING, 1_000_000L, "src", "", NodeReplacementValue.MODE_CTM, 0, "", 0L);
        var reconciler = NodeReplacementReconciler.nodeReplacementReconciler(new NodeReplacementReconciler.Environment() {
            @Override public boolean isLeader() {return true;}
            @Override public Map<NodeId, NodeReplacementValue> records() {return Map.of(OLD, record);}
            @Override public Promise<Boolean> commit(NodeId original, NodeReplacementValue expected, NodeReplacementValue next) {return LateResolution.later(true);}
            @Override public Observation observe(NodeId original, NodeReplacementValue value) {return notYetJoined();}
            @Override public Result<String> resolveSource(NodeId original, NodeReplacementValue value) {return Result.success("src");}

            @Override
            public Promise<EffectResult> execute(Effect effect, NodeId original, NodeReplacementValue value) {
                executions.incrementAndGet();

                return LateResolution.later(new EffectResult.Deferred("waiting"));
            }
        }, TIMINGS);

        for (var trial = 0; trial < TRIALS; trial++) {
            reconciler.reconcile().await();
            reconciler.reconcile().await();
        }

        assertThat(executions.get()).as("every reconcile after an awaited one must run, not be turned away by the gate")
                                    .isEqualTo(2 * TRIALS);
    }

    private static Observation notYetJoined() {
        return new Observation(1_000, true, false, false, false, true, false, true, true, "2.0.0", DrainState.NOT_REQUESTED, "", false, true, "", false, "", "");
    }

    private static final NodeId CORE_1 = new NodeId("core-1");
    private static final NodeId CORE_2 = new NodeId("core-2");

    @Test
    void upgradeRunReconciler_reconcileRightAfterAwait_reachesTheBegin() {
        var begins = new AtomicInteger();
        var run = new UpgradeRunValue("1.1.0", List.of(CORE_1, CORE_2), 0, "", UpgradeRunState.RUNNING, UpgradeStop.NONE, "", 0L, 0L, 0L);
        var reconciler = UpgradeRunReconciler.upgradeRunReconciler(new UpgradeRunReconciler.Environment() {
            @Override public boolean isLeader() {return true;}
            @Override public Option<UpgradeRunValue> run() {return Option.option(run);}
            @Override public Promise<Boolean> commit(UpgradeRunValue expected, UpgradeRunValue next) {return LateResolution.later(true);}
            @Override public long now() {return 5L;}

            @Override
            public UpgradeRunPlanner.Observation observe() {
                return new UpgradeRunPlanner.Observation(Map.of(CORE_1, new Member("core", "1.0.0"), CORE_2, new Member("core", "1.0.0")),
                                                         Map.<NodeId, NodeReplacementValue> of());
            }

            @Override
            public Promise<BeginResult> begin(NodeId node, String targetVersion) {
                begins.incrementAndGet();

                return LateResolution.later(new BeginResult.Deferred("another replacement holds the slot"));
            }
        });

        for (var trial = 0; trial < TRIALS; trial++) {
            reconciler.reconcile().await();
            reconciler.reconcile().await();
        }

        assertThat(begins.get()).as("every reconcile after an awaited one must run, not be turned away by the gate")
                                .isEqualTo(2 * TRIALS);
    }

    private static final NodeId CORE = new NodeId("core");
    private static final NodeId OLD_WORKER = new NodeId("old");
    private static final String CONFIG = """
        config_version = "1.0.0"
        [cluster]
        name = "test"
        version = "1.0.0"
        [source.pool]
        type = "forge"
        zones = ["old", "new"]
        [source.pool.core]
        count = 3
        [source.pool.worker]
        count = 1
        [community.stable]
        target_size = 1
        [community.stable.placement.destination]
        source = "pool"
        zone = "new"
        """;

    @Test
    void communityPlacementReconciler_reconcileRightAfterAwait_readsTheSnapshotAgain() {
        var snapshots = new AtomicInteger();
        var store = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), new Serializer() {
            @Override public <T> void write(ByteBuf buffer, T value) {}
        }, new Deserializer() {
            @Override public <T> T read(ByteBuf buffer) {return null;}
        }) {
            @Override
            public synchronized Map<AetherKey, AetherValue> snapshot() {
                snapshots.incrementAndGet();

                return super.snapshot();
            }
        };

        seedPlacementScenario(store);

        var ready = ConcurrentHashMap.<NodeId> newKeySet();

        ready.add(OLD_WORKER);
        var reconciler = CommunityPlacementReconciler.communityPlacementReconciler(CORE,
                                                                                   store,
                                                                                   commands -> LateResolution.later(store.process(store.createBatch(commands))),
                                                                                   () -> true,
                                                                                   ready::contains,
                                                                                   () -> Set.of(CORE, OLD_WORKER),
                                                                                   () -> 10,
                                                                                   new CommunityPlacementReconciler.Actuator() {
                                                                                       @Override public Result<String> sourceBinding(org.pragmatica.aether.environment.SourceName source) {return Result.success("binding");}
                                                                                       @Override public Promise<Unit> create(AetherValue.CommunityPlacementOperationValue operation) {return LateResolution.later(Unit.unit());}
                                                                                       @Override public Promise<Boolean> retirementSafe(AetherValue.CommunityPlacementOperationValue operation) {return LateResolution.later(true);}
                                                                                       @Override public Promise<Unit> drain(AetherValue.CommunityPlacementOperationValue operation) {return LateResolution.later(Unit.unit());}
                                                                                       @Override public Promise<Unit> terminate(AetherValue.CommunityPlacementOperationValue operation) {return LateResolution.later(Unit.unit());}
                                                                                       @Override public Promise<Boolean> previousInstanceExists(AetherValue.CommunityPlacementOperationValue operation) {return LateResolution.later(true);}
                                                                                   },
                                                                                   escalation -> {},
                                                                                   (node, cause) -> {},
                                                                                   org.pragmatica.lang.io.TimeSpan.timeSpan(60_000).millis(),
                                                                                   System::currentTimeMillis);
        var operationKey = new AetherKey.CommunityPlacementOperationKey("stable");
        var skipped = new AtomicInteger();

        // Walk the operation to DRAIN_REQUESTED, where every further reconcile calls the (late) drain: a tick with asynchronous work
        // each time, which is what lets the gate's release race the caller's next reconcile.
        for (var step = 0; step < 30 && !draining(store, operationKey); step++) {
            store.getTyped(operationKey, AetherValue.CommunityPlacementOperationValue.class)
                 .onPresent(operation -> makeReady(store, ready, operation.targetNode()));
            reconciler.reconcile().await();
            LockSupport.parkNanos(20_000_000L);
        }

        assertThat(draining(store, operationKey)).as("the warm-up reached DRAIN_REQUESTED").isTrue();

        for (var trial = 0; trial < TRIALS; trial++) {
            reconciler.reconcile().await();

            var before = snapshots.get();

            reconciler.reconcile().await();
            if (snapshots.get() == before) {
                skipped.incrementAndGet();
            }
        }

        assertThat(skipped.get()).as("reconcile calls turned away by the gate right after an awaited one (of %d)", TRIALS)
                                 .isZero();
    }

    /// The replacement the operation created has joined: placed, activated as a worker of the community and ready.
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void makeReady(KVStore<AetherKey, AetherValue> store, Set<NodeId> ready, NodeId target) {
        List<KVCommand> seeds = List.of(new KVCommand.Put<>(new AetherKey.ActivationDirectiveKey(target),
                                                            new AetherValue.ActivationDirectiveValue(AetherValue.ActivationDirectiveValue.WORKER, "stable", "")),
                                        new KVCommand.Put<>(new AetherKey.NodePlacementKey(target),
                                                            new AetherValue.NodePlacementValue("pool", Option.some("new"), "new-instance")));

        seeds.forEach(command -> store.process(store.createBatch(List.of(command))));
        ready.add(target);
    }

    private static boolean draining(KVStore<AetherKey, AetherValue> store, AetherKey.CommunityPlacementOperationKey key) {
        return store.getTyped(key, AetherValue.CommunityPlacementOperationValue.class)
                    .map(operation -> operation.phase() == AetherValue.PlacementOperationPhase.DRAIN_REQUESTED)
                    .or(false);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void seedPlacementScenario(KVStore<AetherKey, AetherValue> store) {
        List<KVCommand> seeds = new ArrayList<>();

        seeds.add(new KVCommand.Put<>(LeaderKey.INSTANCE, new LeaderValue(CORE, 1)));
        seeds.add(new KVCommand.Put<>(AetherKey.ClusterConfigKey.CURRENT,
                                      new AetherValue.ClusterConfigValue(Option.some(CONFIG), "test", "1.0.0", List.of(), 3, 3, "forge", 1, 0)));
        seeds.add(new KVCommand.Put<>(new AetherKey.ActivationDirectiveKey(OLD_WORKER),
                                      new AetherValue.ActivationDirectiveValue(AetherValue.ActivationDirectiveValue.WORKER, "stable", "")));
        seeds.add(new KVCommand.Put<>(new AetherKey.NodePlacementKey(OLD_WORKER),
                                      new AetherValue.NodePlacementValue("pool", Option.some("old"), "old-instance")));
        seeds.forEach(command -> store.process(store.createBatch(List.of(command))));
    }
}
