// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.update;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.SliceTargetFence.SliceTargetFenceError;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderManager;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.messaging.MessageRouter.Entry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.BASE;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.OWNER;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.PLACEMENT;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.SELF;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.V1;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.V2;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.seed;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.seedLeaderAndOwner;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.stubDeserializer;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.stubSerializer;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2662: the deployment and A/B writers' read-modify-writes of a slice target are correct whatever order consensus commits them in.
///
/// Each writer reads the target and writes a value derived from it. The write is held here while a racing change commits into the
/// real `KVStore`, then the held write commits after it — the reverse of the order the two were decided in. A plain put lands either
/// way: after a blueprint delete it re-creates the deleted blueprint's target (an orphan), and after a republish it reverts what the
/// republish wrote (the stale value wins). The fenced write is refused in both orders, and its rebuild reads what committed.
class SliceTargetWriterOrderingTest {
    private static final SliceTargetKey TARGET_KEY = SliceTargetKey.sliceTargetKey(BASE);
    private static final AppBlueprintKey BLUEPRINT_KEY = AppBlueprintKey.appBlueprintKey(OWNER);
    private static final String REPUBLISHED_PLACEMENT = "CORE_ONLY";

    private KVStore<AetherKey, AetherValue> store;
    private HoldingRabiaNode node;

    @BeforeEach
    void leaderAndOwnedTarget() {
        store = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        seedLeaderAndOwner(store);
        seed(store, TARGET_KEY, SliceTargetOverridePreservationTest.targetWithOverrides(V1));
        node = new HoldingRabiaNode(store);
    }

    @Nested
    class DeploymentStart {
        private DeploymentManager manager;

        @BeforeEach
        void activeManager() {
            manager = DeploymentManager.deploymentManager(node, store);
            manager.activate().await().onFailure(cause -> Assertions.fail(cause.message()));
        }

        /// Control of the instrument: with nothing racing, the start moves the target to the new version.
        @Test
        void start_nothingRaces_movesTheTarget() {
            var started = async(this::start);

            node.commitAllAsTheyArrive();

            var deployment = started.join().unwrap();

            assertThat(committedTarget().map(SliceTargetValue::currentVersion)).isEqualTo(Option.some(V2));
            assertThat(store.get(DeploymentKey.deploymentKey(deployment.deploymentId())))
                .as("the deployment record commits in the same transaction as the target")
                .isNotEqualTo(Option.none());
        }

        @Test
        void start_deleteCommitsFirst_doesNotRecreateTheTarget() {
            var started = async(this::start);

            node.awaitHeld();
            deleteBlueprintAndTarget();
            node.commitAllAsTheyArrive();

            assertThat(committedTarget()).as("no target is re-created for a deleted blueprint").isEqualTo(Option.none());
            assertThat(failure(started.join())).isInstanceOf(SliceTargetFenceError.BlueprintAbsent.class);
        }

        @Test
        void start_republishCommitsFirst_carriesWhatTheRepublishWrote() {
            var started = async(this::start);

            node.awaitHeld();
            republish();
            node.commitAllAsTheyArrive();

            assertThat(started.join().isSuccess()).isTrue();
            assertThat(committedTarget().map(SliceTargetValue::effectivePlacement)).as("the stale read does not win")
                                                                                    .isEqualTo(Option.some(REPUBLISHED_PLACEMENT));
            assertThat(committedTarget().map(SliceTargetValue::currentVersion)).isEqualTo(Option.some(V2));
        }

        private Result<Deployment> start() {
            return manager.start(OWNER.asString(),
                                 V2,
                                 DeploymentStrategy.BLUE_GREEN,
                                 new StrategyConfig.BlueGreenConfig(timeSpan(30).seconds()),
                                 HealthThresholds.DEFAULT,
                                 CleanupPolicy.GRACE_PERIOD,
                                 3);
        }
    }

    @Nested
    class DeploymentRollback {
        private DeploymentManager manager;
        private String deploymentId;

        @BeforeEach
        void startedDeployment() {
            manager = DeploymentManager.deploymentManager(node, store);
            manager.activate().await().onFailure(cause -> Assertions.fail(cause.message()));

            var started = async(() -> manager.start(OWNER.asString(),
                                                    V2,
                                                    DeploymentStrategy.BLUE_GREEN,
                                                    new StrategyConfig.BlueGreenConfig(timeSpan(30).seconds()),
                                                    HealthThresholds.DEFAULT,
                                                    CleanupPolicy.GRACE_PERIOD,
                                                    3));

            node.commitAllAsTheyArrive();
            deploymentId = started.join().unwrap().deploymentId();
        }

        /// Control of the instrument: with nothing racing, the rollback restores the old version.
        @Test
        void rollback_nothingRaces_restoresTheOldVersion() {
            var rolled = async(() -> manager.rollback(deploymentId));

            node.commitAllAsTheyArrive();

            assertThat(rolled.join().isSuccess()).isTrue();
            assertThat(committedTarget().map(SliceTargetValue::currentVersion)).isEqualTo(Option.some(V1));
        }

        @Test
        void rollback_deleteCommitsFirst_doesNotRecreateTheTarget_andEnds() {
            var rolled = async(() -> manager.rollback(deploymentId));

            node.awaitHeld();
            deleteBlueprintAndTarget();
            node.commitAllAsTheyArrive();

            assertThat(committedTarget()).as("no target is re-created for a deleted blueprint").isEqualTo(Option.none());
            assertThat(rolled.join().unwrap().state()).isEqualTo(DeploymentState.ROLLED_BACK);
        }

        @Test
        void rollback_republishCommitsFirst_carriesWhatTheRepublishWrote() {
            var rolled = async(() -> manager.rollback(deploymentId));

            node.awaitHeld();
            republish();
            node.commitAllAsTheyArrive();

            assertThat(rolled.join().isSuccess()).isTrue();
            assertThat(committedTarget().map(SliceTargetValue::effectivePlacement)).as("the stale read does not win")
                                                                                    .isEqualTo(Option.some(REPUBLISHED_PLACEMENT));
        }
    }

    /// #2662 (CTO caution): a deployment of a blueprint whose later slice has no committed target yet (a register-only publish) creates
    /// that target, as the unfenced writer did. The create expects the target absent and witnesses the deployment's blueprint, so it is
    /// refused when the blueprint was deleted under it. The first slice's target is UNOWNED here, so only the create's own witness can
    /// refuse the write.
    @Nested
    class DeploymentAbsentTarget {
        private static final ArtifactBase SECOND = Artifact.artifact("org.test:second-slice:1.0.0").unwrap().base();
        private static final SliceTargetKey SECOND_KEY = SliceTargetKey.sliceTargetKey(SECOND);

        private DeploymentManager manager;

        @BeforeEach
        void blueprintWithAnUntargetedSlice() {
            var first = ResolvedSlice.resolvedSlice(BASE.withVersion(V1), 3, false).unwrap();
            var second = ResolvedSlice.resolvedSlice(SECOND.withVersion(V1), 3, false).unwrap();

            commit(new KVCommand.Put<AetherKey, AetherValue>(BLUEPRINT_KEY,
                                                             AppBlueprintValue.appBlueprintValue(ExpandedBlueprint.expandedBlueprint(OWNER,
                                                                                                                                     List.of(first,
                                                                                                                                             second)))));
            commit(new KVCommand.Put<AetherKey, AetherValue>(TARGET_KEY, SliceTargetValue.sliceTargetValue(V1, 3)));
            manager = DeploymentManager.deploymentManager(node, store);
            manager.activate().await().onFailure(cause -> Assertions.fail(cause.message()));
        }

        /// Control of the instrument, and the behaviour kept: with nothing racing, the start creates the absent target at the new version.
        @Test
        void start_targetAbsent_nothingRaces_createsItAsBefore() {
            var started = async(this::start);

            node.commitAllAsTheyArrive();

            assertThat(started.join().isSuccess()).isTrue();
            assertThat(target(SECOND_KEY).map(SliceTargetValue::currentVersion)).isEqualTo(Option.some(V2));
            assertThat(target(SECOND_KEY).map(SliceTargetValue::targetInstances)).isEqualTo(Option.some(3));
        }

        @Test
        void start_targetAbsent_blueprintDeleteCommitsFirst_createsNoTarget() {
            var started = async(this::start);

            node.awaitHeld();
            commit(new KVCommand.Remove<>(BLUEPRINT_KEY));
            node.commitAllAsTheyArrive();

            assertThat(target(SECOND_KEY)).as("no target is created for a deleted blueprint").isEqualTo(Option.none());
            assertThat(failure(started.join())).isInstanceOf(SliceTargetFenceError.BlueprintAbsent.class);
        }

        @Test
        void rollback_targetAbsent_blueprintCommitted_recreatesItAsBefore() {
            var deploymentId = startedDeployment();

            commit(new KVCommand.Remove<>(SECOND_KEY));

            var rolled = async(() -> manager.rollback(deploymentId));

            node.commitAllAsTheyArrive();

            assertThat(rolled.join().unwrap().state()).isEqualTo(DeploymentState.ROLLED_BACK);
            assertThat(target(SECOND_KEY).map(SliceTargetValue::currentVersion)).isEqualTo(Option.some(V1));
        }

        @Test
        void rollback_targetAbsent_blueprintDeleteCommitsFirst_createsNoTarget_andEnds() {
            var deploymentId = startedDeployment();

            commit(new KVCommand.Remove<>(SECOND_KEY));

            var rolled = async(() -> manager.rollback(deploymentId));

            node.awaitHeld();
            commit(new KVCommand.Remove<>(BLUEPRINT_KEY));
            node.commitAllAsTheyArrive();

            assertThat(target(SECOND_KEY)).as("no target is created for a deleted blueprint").isEqualTo(Option.none());
            assertThat(rolled.join().unwrap().state()).isEqualTo(DeploymentState.ROLLED_BACK);
        }

        private String startedDeployment() {
            var started = async(this::start);

            node.commitAllAsTheyArrive();

            return started.join().unwrap().deploymentId();
        }

        private Result<Deployment> start() {
            return manager.start(OWNER.asString(),
                                 V2,
                                 DeploymentStrategy.BLUE_GREEN,
                                 new StrategyConfig.BlueGreenConfig(timeSpan(30).seconds()),
                                 HealthThresholds.DEFAULT,
                                 CleanupPolicy.GRACE_PERIOD,
                                 3);
        }
    }

    @Nested
    class AbTest {
        private AbTestManager manager;

        @BeforeEach
        void activeManager() {
            manager = AbTestManager.abTestManager(node, store, InvocationMetricsCollector.invocationMetricsCollector());
            manager.activate().await().onFailure(cause -> Assertions.fail(cause.message()));
        }

        /// Control of the instrument: with nothing racing, the variant write moves the target to the variant.
        @Test
        void createTest_nothingRaces_writesTheVariant() {
            var created = createTest();

            node.commitAllAsTheyArrive();

            assertThat(created.join().isSuccess()).isTrue();
            assertThat(committedTarget().map(SliceTargetValue::currentVersion)).isEqualTo(Option.some(V2));
        }

        @Test
        void createTest_deleteCommitsFirst_doesNotRecreateTheTarget() {
            var created = createTest();

            node.commitUntilTargetWriteHeld();
            deleteBlueprintAndTarget();
            node.commitAllAsTheyArrive();

            assertThat(committedTarget()).as("no target is re-created for a deleted blueprint").isEqualTo(Option.none());
            assertThat(failure(created.join())).isInstanceOf(SliceTargetFenceError.TargetAbsent.class);
        }

        @Test
        void createTest_republishCommitsFirst_carriesWhatTheRepublishWrote() {
            var created = createTest();

            node.commitUntilTargetWriteHeld();
            republish();
            node.commitAllAsTheyArrive();

            assertThat(created.join().isSuccess()).isTrue();
            assertThat(committedTarget().map(SliceTargetValue::effectivePlacement)).as("the stale read does not win")
                                                                                    .isEqualTo(Option.some(REPUBLISHED_PLACEMENT));
        }

        @Test
        void concludeTest_deleteCommitsFirst_doesNotRecreateTheTarget() {
            var testId = activeTest();
            var concluded = async(() -> manager.concludeTest(testId, "canary").await());

            node.commitUntilTargetWriteHeld();
            deleteBlueprintAndTarget();
            node.commitAllAsTheyArrive();

            assertThat(committedTarget()).as("no target is re-created for a deleted blueprint").isEqualTo(Option.none());
            assertThat(failure(concluded.join())).isInstanceOf(SliceTargetFenceError.TargetAbsent.class);
        }

        @Test
        void concludeTest_republishCommitsFirst_carriesWhatTheRepublishWrote() {
            var testId = activeTest();
            var concluded = async(() -> manager.concludeTest(testId, "canary").await());

            node.commitUntilTargetWriteHeld();
            republish();
            node.commitAllAsTheyArrive();

            assertThat(concluded.join().isSuccess()).isTrue();
            assertThat(committedTarget().map(SliceTargetValue::effectivePlacement)).as("the stale read does not win")
                                                                                    .isEqualTo(Option.some(REPUBLISHED_PLACEMENT));
        }

        @Test
        void rollbackTest_deleteCommitsFirst_doesNotRecreateTheTarget_andEnds() {
            var testId = activeTest();
            var rolled = async(() -> manager.rollbackTest(testId).await());

            node.commitUntilTargetWriteHeld();
            deleteBlueprintAndTarget();
            node.commitAllAsTheyArrive();

            assertThat(committedTarget()).as("no target is re-created for a deleted blueprint").isEqualTo(Option.none());
            assertThat(rolled.join().unwrap().state()).isEqualTo(AbTestState.ROLLED_BACK);
        }

        @Test
        void rollbackTest_targetAlreadyGone_restoresNothing_andEnds() {
            var testId = activeTest();

            deleteBlueprintAndTarget();

            var rolled = async(() -> manager.rollbackTest(testId).await());

            node.commitAllAsTheyArrive();

            assertThat(committedTarget()).as("no target is created for a deleted blueprint").isEqualTo(Option.none());
            assertThat(rolled.join().unwrap().state()).isEqualTo(AbTestState.ROLLED_BACK);
        }

        /// The owner's blueprint is deleted while the target is still committed (its removal has not committed yet): the restore would
        /// keep the orphan alive, so it writes nothing, and the test still ends.
        @Test
        void rollbackTest_blueprintDeleteCommitsFirst_targetStillCommitted_restoresNothing_andEnds() {
            var testId = activeTest();
            var before = committedTarget();
            var rolled = async(() -> manager.rollbackTest(testId).await());

            node.commitUntilTargetWriteHeld();
            commit(new KVCommand.Remove<>(BLUEPRINT_KEY));
            node.commitAllAsTheyArrive();

            assertThat(committedTarget()).as("the orphan is not written").isEqualTo(before);
            assertThat(rolled.join().unwrap().state()).isEqualTo(AbTestState.ROLLED_BACK);
        }

        /// The unfenced writer issued one put per variant in the map's iteration order, so the last variant's version stayed committed;
        /// the single fenced write carries that same version.
        @Test
        void createTest_twoVariants_writesTheVersionTheLastPutWrote() {
            var variants = Map.of("canary", V2, "other", Version.version("3.0.0").unwrap());
            var created = async(() -> manager.createTest(BASE,
                                                         variants,
                                                         SplitRule.HeaderHashSplit.headerHashSplit("X-Request-Id", 3))
                                             .await());

            node.commitAllAsTheyArrive();

            var test = created.join().unwrap();

            assertThat(committedTarget().map(SliceTargetValue::currentVersion)).isEqualTo(Option.some(List.copyOf(test.variantVersions()
                                                                                                                       .values())
                                                                                                        .getLast()));
        }

        private CompletableFuture<Result<AbTestDeployment>> createTest() {
            return async(() -> manager.createTest(BASE,
                                                  Map.of("canary", V2),
                                                  SplitRule.HeaderHashSplit.headerHashSplit("X-Request-Id", 2))
                                      .await());
        }

        private String activeTest() {
            var created = createTest();

            node.commitAllAsTheyArrive();

            return created.join().unwrap().testId();
        }
    }

    // ---- races ----

    private void deleteBlueprintAndTarget() {
        commit(new KVCommand.Remove<>(BLUEPRINT_KEY));
        commit(new KVCommand.Remove<>(TARGET_KEY));
    }

    /// A republish of the owning blueprint: a new blueprint value, and its target with a placement the writer did not read.
    private void republish() {
        var slice = ResolvedSlice.resolvedSlice(BASE.withVersion(V1), 2, false).unwrap();

        commit(new KVCommand.Put<AetherKey, AetherValue>(BLUEPRINT_KEY,
                                                         AppBlueprintValue.appBlueprintValue(ExpandedBlueprint.expandedBlueprint(OWNER,
                                                                                                                                 List.of(slice)))));
        commit(new KVCommand.Put<AetherKey, AetherValue>(TARGET_KEY,
                                                         committedTarget().unwrap()
                                                                          .withPlacement(REPUBLISHED_PLACEMENT)));
    }

    // ---- helpers ----

    private Option<SliceTargetValue> committedTarget() {
        return target(TARGET_KEY);
    }

    private Option<SliceTargetValue> target(SliceTargetKey key) {
        return store.get(key).filter(SliceTargetValue.class::isInstance).map(SliceTargetValue.class::cast);
    }

    private void commit(KVCommand<AetherKey> command) {
        store.process(store.createBatch(List.of(command)));
    }

    private static <T> CompletableFuture<T> async(Supplier<T> action) {
        return CompletableFuture.supplyAsync(action);
    }

    private static Cause failure(Result<?> result) {
        return result.fold(cause -> cause, _ -> Assertions.fail("expected a refusal, the write succeeded"));
    }

    private static void sleep() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static {
        // The fixture's placement differs from the republished one, so a stale value is observable.
        assert !PLACEMENT.equals(REPUBLISHED_PLACEMENT);
    }

    /// Holds every submitted batch until the test commits it into the real store; the promise then resolves with the applier's results.
    private static final class HoldingRabiaNode implements RabiaNode<KVCommand<AetherKey>> {
        private record Held(List<KVCommand<AetherKey>> batch, Promise<List<Object>> promise) {}

        private final KVStore<AetherKey, AetherValue> store;
        private final List<Held> held = new ArrayList<>();

        HoldingRabiaNode(KVStore<AetherKey, AetherValue> store) {
            this.store = store;
        }

        @Override
        @SuppressWarnings("unchecked")
        public synchronized <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> batch) {
            var promise = Promise.<List<Object>> promise();

            held.add(new Held(List.copyOf(batch), promise));

            return (Promise<List<R>>) (Promise<?>) promise;
        }

        private synchronized int heldCount() {
            return held.size();
        }

        private synchronized boolean oldestWritesTarget() {
            return !held.isEmpty() && held.getFirst()
                                          .batch()
                                          .stream()
                                          .anyMatch(Held2662::writesTarget);
        }

        void awaitHeld() {
            var deadline = System.currentTimeMillis() + 5_000;

            while (heldCount() == 0 && System.currentTimeMillis() < deadline) {
                sleep();
            }

            assertThat(heldCount()).as("a write is held").isPositive();
        }

        /// Commits the held writes that do not touch the target (the A/B record puts), until a target write is held.
        void commitUntilTargetWriteHeld() {
            var deadline = System.currentTimeMillis() + 5_000;

            while (!oldestWritesTarget() && System.currentTimeMillis() < deadline) {
                if (heldCount() > 0) {
                    commitOldest();
                } else {
                    sleep();
                }
            }

            assertThat(oldestWritesTarget()).as("a target write is held").isTrue();
        }

        synchronized void commitOldest() {
            var entry = held.removeFirst();

            entry.promise().succeed(store.<Object> process(store.createBatch(entry.batch())));
        }

        /// Commits the held writes, then each one the commits lead to (rebuilds, next steps), until none arrives for a while.
        void commitAllAsTheyArrive() {
            var quietUntil = System.currentTimeMillis() + 300;

            while (System.currentTimeMillis() < quietUntil) {
                if (heldCount() > 0) {
                    commitOldest();
                    quietUntil = System.currentTimeMillis() + 300;
                } else {
                    sleep();
                }
            }
        }

        @Override public NodeId self() {return SELF;}

        @Override public TopologyManager topologyManager() {return null;}

        @Override public Promise<Unit> start() {return Promise.unitPromise();}

        @Override public Promise<Unit> stop() {return Promise.unitPromise();}

        @Override public ClusterNetwork network() {return null;}

        @Override public LeaderManager leaderManager() {return null;}

        @Override public boolean isActive() {return true;}

        @Override public void authorizeActivation() {}

        @Override public void authorizeObservation() {}

        @Override public boolean isObserving() {return false;}

        @Override public List<Entry<?>> routeEntries() {return List.of();}
    }

    private static final class Held2662 {
        private Held2662() {}

        static boolean writesTarget(KVCommand<AetherKey> command) {
            return command instanceof KVCommand.LeaderTransaction<?, ?> transaction
                   && transaction.mutations().stream().anyMatch(mutation -> TARGET_KEY.equals(mutation.key()))
                   || command instanceof KVCommand.Put<?, ?> put && TARGET_KEY.equals(put.key());
        }
    }
}
