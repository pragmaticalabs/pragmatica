// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.update;

import io.netty.buffer.ByteBuf;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.VersionRoutingKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderManager;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.messaging.MessageRouter.Entry;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.List;
import java.util.stream.Stream;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/// #424 regression: the deployment-update writer (`DeploymentManagerImpl.addSliceTargetCommand`) and
/// the A/B lifecycle writer (`AbTestManager.targetPreservingOverrides`, exercised via promote) must
/// re-read the current `SliceTargetValue` and carry the operator's per-slice autoscaler overrides
/// onto the rewritten version instead of resetting them to `none()`.
///
/// #698 extends the same requirement to `owningBlueprint`, which `targetPreservingOverrides` was
/// dropping while carefully preserving every override beside it — despite its name and its javadoc
/// both promising preservation. The seeded fixture is therefore blueprint-owned, and every path
/// below asserts the owner survives alongside the overrides. The consequence of losing it is not
/// visible here: `ClusterDeploymentState` resolves a slice's `schemaRequired` through its owner, so
/// an erased owner silently flips a `schema_required = false` slice to `true` on the next restore.
class SliceTargetOverridePreservationTest {
    static final NodeId SELF = NodeId.nodeId("node-1").unwrap();
    static final ArtifactBase BASE = Artifact.artifact("org.test:my-slice:2.0.0").unwrap().base();
    static final Version V1 = Version.version("1.0.0").unwrap();
    static final Version V2 = Version.version("2.0.0").unwrap();
    static final BlueprintId OWNER = BlueprintId.blueprintId("org.test:owning-app:1.0.0").unwrap();
    /// A real `PlacementPolicy` constant that is not the default, so a reset to `CORE_ONLY` is
    /// observable rather than indistinguishable from a correct carry (#937).
    static final String PLACEMENT = "WORKERS_ONLY";

    @Nested
    class DeploymentUpdatePreservesOverrides {
        private static final String DEPLOYMENT_ID = "deployment-1";

        private CapturingRabiaNode rabiaNode;
        private DeploymentManager manager;
        private KVStore<AetherKey, AetherValue> kvStore;

        @BeforeEach
        void setUp() {
            rabiaNode = new CapturingRabiaNode(SELF);

            kvStore = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(),
                                                              stubSerializer(),
                                                              stubDeserializer());

            seedLeaderAndOwner(kvStore);
            seed(kvStore, SliceTargetKey.sliceTargetKey(BASE), targetWithOverrides(V2));
            seed(kvStore, DeploymentKey.deploymentKey(DEPLOYMENT_ID), deployedDeploymentValue());

            manager = DeploymentManager.deploymentManager(rabiaNode, kvStore);
            manager.activate().await().onFailure(cause -> Assertions.fail(cause.message()));
        }

        @Test
        void rollback_preserves_autoscaler_overrides_on_slice_target() {
            manager.rollback(DEPLOYMENT_ID).onFailure(cause -> Assertions.fail(cause.message()));

            var rewritten = capturedSliceTargets(rabiaNode.appliedCommands).stream()
                                                                           .filter(target -> target.currentVersion()
                                                                                                   .equals(V1))
                                                                           .toList();

            assertThat(rewritten).isNotEmpty();
            assertThat(rewritten).allSatisfy(target -> {
                assertThat(target.maxInstances()).isEqualTo(Option.some(5));
                assertThat(target.scaleUpThreshold()).isEqualTo(Option.some(0.8));
                assertThat(target.scaleDownThreshold()).isEqualTo(Option.some(0.2));
                assertThat(target.owningBlueprint()).as("#698: the deployment-update writer must not erase the slice's owner")
                                                    .isEqualTo(Option.some(OWNER));
            });
        }

        /// #1068: a rolled-back rolling update must not leave its `VersionRoutingKey` behind — while it
        /// exists the rolled-back NEW version stays permitted to start and allocated. The removal must
        /// follow the old-target restore: the leader's routing-removal handler deallocates every version
        /// the committed target does not name at that moment. Since #2662 both are mutations of one
        /// transaction, the target first, and the applier commits every mutation before any notification.
        @Test
        void rollback_removesTheRoutingEntry_afterRestoringTheOldTarget() {
            var routingKey = VersionRoutingKey.versionRoutingKey(BASE);

            seed(kvStore, routingKey, AetherValue.VersionRoutingValue.versionRoutingValue(V1, V2));
            manager.rollback(DEPLOYMENT_ID).onFailure(cause -> Assertions.fail(cause.message()));

            var mutations = rabiaNode.appliedCommands.stream()
                                                     .filter(KVCommand.LeaderTransaction.class::isInstance)
                                                     .flatMap(command -> ((KVCommand.LeaderTransaction<AetherKey, ?>) command).mutations().stream())
                                                     .toList();
            var targetIndex = indexOf(mutations, m -> m.key().equals(SliceTargetKey.sliceTargetKey(BASE)) && m.replacement().isPresent());
            var routingRemoveIndex = indexOf(mutations, m -> m.key().equals(routingKey) && m.replacement().isEmpty());

            assertThat(routingRemoveIndex).as("the rollback transaction removes the routing entry").isNotNegative();
            assertThat(mutations).as("and never rewrites it").noneMatch(m -> m.key().equals(routingKey) && m.replacement().isPresent());
            assertThat(targetIndex).as("the old target is restored in the same transaction").isNotNegative();
            assertThat(routingRemoveIndex).as("target restore precedes the routing removal").isGreaterThan(targetIndex);
        }

        private static <T> int indexOf(List<T> items, java.util.function.Predicate<T> test) {
            for (var i = 0; i < items.size(); i++) {
                if (test.test(items.get(i))) {
                    return i;
                }
            }

            return -1;
        }

        private DeploymentValue deployedDeploymentValue() {
            var now = System.currentTimeMillis();

            return DeploymentValue.deploymentValue(DEPLOYMENT_ID,
                                                   "org.test:my-slice:2.0.0",
                                                   V1.bareVersion(),
                                                   V2.bareVersion(),
                                                   DeploymentStrategy.ROLLING.name(),
                                                   DeploymentState.DEPLOYED.name(),
                                                   VersionRouting.ALL_OLD.toString(),
                                                   "",
                                                   "",
                                                   CleanupPolicy.GRACE_PERIOD.name(),
                                                   BASE.asString(),
                                                   3,
                                                   now,
                                                   now);
        }
    }

    @Nested
    class AbTestPromotePreservesOverrides {
        private CapturingRabiaNode rabiaNode;
        private AbTestManager manager;

        @BeforeEach
        void setUp() {
            rabiaNode = new CapturingRabiaNode(SELF);

            var kvStore = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(),
                                                              stubSerializer(),
                                                              stubDeserializer());

            seedLeaderAndOwner(kvStore);
            seed(kvStore, SliceTargetKey.sliceTargetKey(BASE), targetWithOverrides(V1));

            manager = AbTestManager.abTestManager(rabiaNode,
                                                  kvStore,
                                                  InvocationMetricsCollector.invocationMetricsCollector());
            manager.activate().await().onFailure(cause -> Assertions.fail(cause.message()));
        }

        @Test
        void concludeTest_preserves_autoscaler_overrides_on_promoted_version() {
            var testId = manager.createTest(BASE,
                                            Map.of("canary", V2),
                                            SplitRule.HeaderHashSplit.headerHashSplit("X-Request-Id", 2))
                                .await()
                                .onFailure(cause -> Assertions.fail(cause.message()))
                                .unwrap()
                                .testId();

            rabiaNode.appliedCommands.clear();

            manager.concludeTest(testId, "canary")
                   .await()
                   .onFailure(cause -> Assertions.fail(cause.message()));

            var promoted = capturedSliceTargets(rabiaNode.appliedCommands).stream()
                                                                          .filter(target -> target.currentVersion()
                                                                                                  .equals(V2))
                                                                          .toList();

            assertThat(promoted).isNotEmpty();
            assertThat(promoted).allSatisfy(target -> {
                assertThat(target.maxInstances()).isEqualTo(Option.some(5));
                assertThat(target.scaleUpThreshold()).isEqualTo(Option.some(0.8));
                assertThat(target.scaleDownThreshold()).isEqualTo(Option.some(0.2));
                assertThat(target.owningBlueprint()).as("#698: AbTestManager.targetPreservingOverrides must not erase the slice's owner")
                                                    .isEqualTo(Option.some(OWNER));
            });
        }

        /// #937: placement is not an autoscaler override in #424's sense — it decides **where** the
        /// slice runs. `ClusterDeploymentState` feeds `effectivePlacement()` into the allocation
        /// engine, so a reset here relocates an operator's workload onto the core on the first A/B
        /// write. `targetPreservingOverrides` enumerated the fields it carried and placement was not
        /// among them, which is why its own javadoc could read as exhaustive while it was not.
        @Test
        void concludeTest_preservesOperatorPlacement_onPromotedVersion() {
            var testId = manager.createTest(BASE,
                                            Map.of("canary", V2),
                                            SplitRule.HeaderHashSplit.headerHashSplit("X-Request-Id", 2))
                                .await()
                                .onFailure(cause -> Assertions.fail(cause.message()))
                                .unwrap()
                                .testId();

            rabiaNode.appliedCommands.clear();

            manager.concludeTest(testId, "canary")
                   .await()
                   .onFailure(cause -> Assertions.fail(cause.message()));

            var promoted = capturedSliceTargets(rabiaNode.appliedCommands).stream()
                                                                          .filter(target -> target.currentVersion()
                                                                                                  .equals(V2))
                                                                          .toList();

            assertThat(promoted).isNotEmpty();
            assertThat(promoted).allSatisfy(target -> assertThat(target.effectivePlacement())
                    .as("#937: the A/B writer must not reset the operator's placement")
                    .isEqualTo(PLACEMENT));
        }

        /// The canary deploy is the earlier of the two A/B writes and carries the same defect, so
        /// pinning only the promotion would leave the first write that touches a live slice unpinned.
        @Test
        void createTest_preservesOperatorPlacement_onTheVariantVersion() {
            manager.createTest(BASE,
                               Map.of("canary", V2),
                               SplitRule.HeaderHashSplit.headerHashSplit("X-Request-Id", 2))
                   .await()
                   .onFailure(cause -> Assertions.fail(cause.message()));

            var variants = capturedSliceTargets(rabiaNode.appliedCommands).stream()
                                                                          .filter(target -> target.currentVersion()
                                                                                                  .equals(V2))
                                                                          .toList();

            assertThat(variants).isNotEmpty();
            assertThat(variants).allSatisfy(target -> assertThat(target.effectivePlacement())
                    .as("#937: the A/B canary write must not reset the operator's placement")
                    .isEqualTo(PLACEMENT));
        }
    }

    /// Owner-bearing since #698: a blueprint-owned slice is what both writers actually receive in
    /// production, and an unowned fixture cannot observe an owner being dropped. Operator-placed
    /// since #937, for the same reason — the creation factory below cannot express a placement, so
    /// it is applied through `withPlacement` exactly as `SliceRoutes.applyScaleToExisting` does.
    static SliceTargetValue targetWithOverrides(Version version) {
        return SliceTargetValue.sliceTargetValue(version,
                                                 3,
                                                 1,
                                                 Option.some(OWNER),
                                                 Option.some(5),
                                                 Option.some(0.8),
                                                 Option.some(0.2))
                               .withPlacement(PLACEMENT);
    }

    /// #2662: the target writers are leader transactions fenced on what they read, so a store they write needs a committed leader
    /// and, for a target [#OWNER] owns, the owner's committed blueprint.
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void seedLeaderAndOwner(KVStore<AetherKey, AetherValue> kvStore) {
        kvStore.process(kvStore.createBatch((List) List.of(new KVCommand.Put<>(LeaderKey.INSTANCE, new LeaderValue(SELF, 1)))));
        seed(kvStore, AppBlueprintKey.appBlueprintKey(OWNER), ownerBlueprint());
    }

    static AppBlueprintValue ownerBlueprint() {
        var slice = ResolvedSlice.resolvedSlice(BASE.withVersion(V1), 3, false).unwrap();

        return AppBlueprintValue.appBlueprintValue(ExpandedBlueprint.expandedBlueprint(OWNER, List.of(slice)));
    }

    static void seed(KVStore<AetherKey, AetherValue> kvStore, AetherKey key, AetherValue value) {
        kvStore.process(kvStore.createBatch(List.<KVCommand<AetherKey>>of(new KVCommand.Put<AetherKey, AetherValue>(key,
                                                                                                                    value))));
    }

    /// The `SliceTargetValue`s written, whatever form the write took: a plain put, or the replacements of a transaction's mutations.
    static List<SliceTargetValue> capturedSliceTargets(List<KVCommand<AetherKey>> commands) {
        return commands.stream()
                       .flatMap(SliceTargetOverridePreservationTest::writtenValues)
                       .filter(SliceTargetValue.class::isInstance)
                       .map(SliceTargetValue.class::cast)
                       .toList();
    }

    static Stream<?> writtenValues(KVCommand<AetherKey> command) {
        return switch (command) {
            case KVCommand.Put<AetherKey, ?> put -> Stream.of(put.value());
            case KVCommand.LeaderTransaction<AetherKey, ?> transaction -> transaction.mutations()
                                                                                       .stream()
                                                                                       .flatMap(mutation -> mutation.replacement().stream());
            default -> Stream.empty();
        };
    }

    /// The results of an apply in which every transaction was accepted, as the applier answers one nothing raced.
    static List<Object> acceptedResults(List<KVCommand<AetherKey>> commands) {
        return commands.stream().map(SliceTargetOverridePreservationTest::acceptedResult).toList();
    }

    /// The results of an apply in which every transaction was refused by the fence.
    static List<Object> refusedResults(List<KVCommand<AetherKey>> commands) {
        return commands.stream().map(SliceTargetOverridePreservationTest::refusedResult).toList();
    }

    private static Object refusedResult(KVCommand<AetherKey> command) {
        return command instanceof KVCommand.LeaderTransaction<?, ?> transaction
               ? new KVCommand.TransactionResult(transaction.transactionId(), false)
               : Unit.unit();
    }

    private static Object acceptedResult(KVCommand<AetherKey> command) {
        return command instanceof KVCommand.LeaderTransaction<?, ?> transaction
               ? new KVCommand.TransactionResult(transaction.transactionId(), true)
               : Unit.unit();
    }

    static Serializer stubSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }

    static final class CapturingRabiaNode implements RabiaNode<KVCommand<AetherKey>> {
        private final NodeId self;
        final List<KVCommand<AetherKey>> appliedCommands = new CopyOnWriteArrayList<>();
        /// When set, every leader transaction is answered as refused by the fence (a concurrent change), as the applier does.
        volatile boolean refuseTransactions;
        /// When set, every apply holding a leader transaction fails as consensus failing to commit it (the write may or may not have landed).
        volatile boolean failTransactions;
        /// When set, every apply holding a removal fails as consensus failing to commit it; a failed apply is not recorded in `appliedCommands`.
        volatile boolean failRemovals;
        /// When set, the next apply holding a leader transaction is NOT answered until `releaseHeld` runs (a fenced write in flight).
        volatile boolean holdNextTransaction;
        /// Releases the held transaction, answering it as accepted; non-null once a transaction is held.
        volatile Runnable releaseHeld;
        /// When set, every apply holding a leader transaction never completes (consensus hung).
        volatile boolean hangTransactions;
        /// When set, every apply holding a removal never completes (consensus hung).
        volatile boolean hangRemovals;

        CapturingRabiaNode(NodeId self) {
            this.self = self;
        }

        @Override
        public NodeId self() {
            return self;
        }

        @Override
        public TopologyManager topologyManager() {
            return null;
        }

        @Override
        public Promise<Unit> start() {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.unitPromise();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            if (failTransactions && commands.stream().anyMatch(KVCommand.LeaderTransaction.class::isInstance)
                || failRemovals && commands.stream().anyMatch(KVCommand.Remove.class::isInstance)) {
                return Causes.cause("consensus did not commit").promise();
            }

            if (hangTransactions && commands.stream().anyMatch(KVCommand.LeaderTransaction.class::isInstance)
                || hangRemovals && commands.stream().anyMatch(KVCommand.Remove.class::isInstance)) {
                return Promise.promise();
            }

            appliedCommands.addAll(commands);

            if (holdNextTransaction && commands.stream().anyMatch(KVCommand.LeaderTransaction.class::isInstance)) {
                holdNextTransaction = false;

                var held = Promise.<List<R>> promise();

                releaseHeld = () -> held.succeed((List<R>) acceptedResults(commands));
                return held;
            }

            return Promise.success((List<R>) (refuseTransactions
                                              ? refusedResults(commands)
                                              : acceptedResults(commands)));
        }

        @Override
        public ClusterNetwork network() {
            return null;
        }

        @Override
        public LeaderManager leaderManager() {
            return null;
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public void authorizeActivation() {}

        @Override
        public void authorizeObservation() {}

        @Override
        public boolean isObserving() {
            return false;
        }

        @Override
        public List<Entry<?>> routeEntries() {
            return List.of();
        }
    }
}
