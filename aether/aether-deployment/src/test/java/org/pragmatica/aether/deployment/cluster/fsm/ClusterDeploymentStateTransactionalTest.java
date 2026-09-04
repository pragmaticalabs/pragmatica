// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.Blueprint;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.Activate;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.AppBlueprintPutReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.AppBlueprintRemoveReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.NodeArtifactPutReceived;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentOutcomeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

import io.netty.buffer.ByteBuf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Transactional + isolation semantics for ALL_OR_NOTHING cluster deployments:
/// (1) blueprint ownership is keyed by name (artifact base), not version — a version upgrade of
///     the same blueprint must not self-conflict, but a different blueprint name sharing a slice
///     base must still conflict;
/// (2) `capturePreviousBlueprint` recovers the prior ACTIVE version from KV so a failed deploy can
///     be rolled back to the exact pre-deployment state;
/// (3) `restorePreviousBlueprint` re-Puts the prior value under the prior version's key, not the
///     failed deploy's key;
/// (4) `unloadBlueprintSlices` rollback (no previous blueprint) atomically bundles the
///     `AppBlueprintKey` removal with a durable FAILED `DeploymentOutcomeKey` Put in the SAME
///     consensus batch, so `GET /api/blueprints/status/{id}` has a terminal record to read even
///     though the blueprint's own key is gone (#759).
class ClusterDeploymentStateTransactionalTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final Version V1 = Version.version("1.0.0").unwrap();
    private static final Version V2 = Version.version("2.0.0").unwrap();
    // #760/#724 review round 2 item l: mirrors SchemaActivationGateTest's own ACTIVE_LOGGER_NAME —
    // targets the FSM's inner Active class directly rather than the outer ClusterDeploymentState,
    // since that is the logger `log.warn` in handleSucceededOutcomeWriteFailure actually resolves to.
    private static final String ACTIVE_LOGGER_NAME = "org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentState$Active";

    private InMemoryKvStore kvStore;
    private RecordingClusterNode cluster;
    private FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> harness;

    @BeforeEach
    void setUp() {
        var router = MessageRouter.mutable();
        kvStore = new InMemoryKvStore(router);
        cluster = new RecordingClusterNode(SELF);
        LongSupplier clock = () -> 10_000_000L;

        Function<Fsm<ClusterDeploymentState, ClusterFsmEvent>, ClusterDeploymentState> factory =
                fsm -> new ClusterDeploymentContext(fsm,
                                                    SELF,
                                                    cluster,
                                                    kvStore,
                                                    router,
                                                    stubTopologyManager(SELF),
                                                    stubSchemaOrchestrator(),
                                                    () -> Set.of(SELF, NODE_A),
                                                    () -> Set.of(SELF, NODE_A),
                                                    Set::of,
                                                    Set.of(SELF, NODE_A),
                                                    DeploymentAtomicity.ALL_OR_NOTHING,
                                                    3,
                                                    timeSpan(300).seconds(),
                                                    clock).dormant();
        harness = FsmTestHarness.harness("transactional-test-" + SELF.id(), factory);
        harness.dispatch(new Activate());
    }

    private ClusterDeploymentState.Active activeState() {
        return (ClusterDeploymentState.Active) harness.state();
    }

    private static Artifact artifact(String name, Version version) {
        return Artifact.artifact("com.example:" + name + ":" + version.bareVersion()).unwrap();
    }

    private static BlueprintId blueprintId(String name, Version version) {
        return BlueprintId.blueprintId("com.example:" + name + ":" + version.bareVersion()).unwrap();
    }

    private static ExpandedBlueprint blueprint(String name, Version version, String sliceName) {
        var id = blueprintId(name, version);
        var slice = ResolvedSlice.resolvedSlice(artifact(sliceName, version), 3, false).unwrap();
        return ExpandedBlueprint.expandedBlueprint(id, List.of(slice));
    }

    /// #809 review: replays one command from an ACTUALLY EMITTED batch through the real FSM entry
    /// point (`harness.dispatch`) — the same one a live KV-watch notification uses — instead of
    /// calling `handleAppBlueprintChange`/`handleAppBlueprintRemoval` directly. `RecordingClusterNode`
    /// only records `apply()` calls, it never applies them, so this is what makes a test sensitive
    /// to two commands in a batch trading places; every other assertion in this file stays green
    /// either way.
    private void replayThroughRealHandlers(KVCommand<AetherKey> command) {
        if (command instanceof KVCommand.Put<AetherKey, ?> put && put.key() instanceof AppBlueprintKey appKey) {
            harness.dispatch(new AppBlueprintPutReceived(new ValuePut<>(new KVCommand.Put<>(appKey, (AppBlueprintValue) put.value()),
                                                                        Option.none())));
            return;
        }

        if (command instanceof KVCommand.Remove<AetherKey> remove && remove.key() instanceof AppBlueprintKey appKey) {
            harness.dispatch(new AppBlueprintRemoveReceived(new ValueRemove<>(new KVCommand.Remove<>(appKey, remove.witness()),
                                                                              Option.none())));
        }
    }

    @Nested
    class NameKeyedOwnership {
        @Test
        void hasConflictingOwnership_sameNameDifferentVersion_noConflict() {
            var existing = blueprint("app", V1, "slice-a");
            // #699: schemaRequired is orthogonal to ownership-conflict detection; true preserves
            // this test's pre-existing behavior.
            var ownedSlice = Blueprint.blueprint(artifact("slice-a", V1), 3, 1, Option.some(existing.id()), true);
            activeState().blueprints().put(ownedSlice.artifact(), ownedSlice);

            var upgrade = blueprint("app", V2, "slice-a");

            assertThat(activeState().hasConflictingOwnershipForTest(upgrade)).isFalse();
        }

        @Test
        void hasConflictingOwnership_differentNameSharedSliceBase_stillConflicts() {
            var owner = blueprint("owner-app", V1, "slice-a");
            // #699: schemaRequired is orthogonal to ownership-conflict detection; true preserves
            // this test's pre-existing behavior.
            var ownedSlice = Blueprint.blueprint(artifact("slice-a", V1), 3, 1, Option.some(owner.id()), true);
            activeState().blueprints().put(ownedSlice.artifact(), ownedSlice);

            var intruder = blueprint("other-app", V1, "slice-a");

            assertThat(activeState().hasConflictingOwnershipForTest(intruder)).isTrue();
        }
    }

    @Nested
    class CapturePreviousBlueprint {
        @Test
        void capturePreviousBlueprint_priorActiveVersionInKv_returnsPriorExpanded() {
            // The prior AppBlueprintValue is keyed by the BLUEPRINT id (com.example:app:1.0.0),
            // while the SliceTargetValue is keyed by the SLICE base (com.example:slice-a) — these
            // are distinct bases. The capture must read the version from the slice target but
            // derive the prior blueprint id from the blueprint base, not the slice base.
            var prior = blueprint("app", V1, "slice-a");
            var sliceBase = artifact("slice-a", V1).base();
            assertThat(sliceBase).isNotEqualTo(prior.id().base());
            kvStore.put(SliceTargetKey.sliceTargetKey(sliceBase), SliceTargetValue.sliceTargetValue(V1, 3));
            kvStore.put(AppBlueprintKey.appBlueprintKey(prior.id()), AppBlueprintValue.appBlueprintValue(prior));

            var upgrade = blueprint("app", V2, "slice-a");

            var captured = activeState().capturePreviousBlueprintForTest(upgrade);

            assertThat(captured.isPresent()).isTrue();
            captured.onPresent(p -> assertThat(p.id()).isEqualTo(prior.id()));
        }

        @Test
        void capturePreviousBlueprint_noPriorTarget_returnsEmpty() {
            var firstDeploy = blueprint("app", V1, "slice-a");

            assertThat(activeState().capturePreviousBlueprintForTest(firstDeploy).isEmpty()).isTrue();
        }

        @Test
        void capturePreviousBlueprint_sameVersionRedeploy_returnsEmpty() {
            var sliceBase = artifact("slice-a", V1).base();
            kvStore.put(SliceTargetKey.sliceTargetKey(sliceBase), SliceTargetValue.sliceTargetValue(V1, 3));

            var sameVersion = blueprint("app", V1, "slice-a");

            assertThat(activeState().capturePreviousBlueprintForTest(sameVersion).isEmpty()).isTrue();
        }
    }

    @Nested
    class RestorePreviousBlueprint {
        @Test
        void restorePreviousBlueprint_putsUnderPriorVersionKey() {
            var prior = blueprint("app", V1, "slice-a");
            var failing = blueprint("app", V2, "slice-a");
            var inflight = ClusterDeploymentState.Active.InFlightBlueprint.inFlightBlueprint(failing.id(),
                                                                                             failing,
                                                                                             Option.some(prior));

            activeState().restorePreviousBlueprintForTest(inflight, prior, "boom");

            var expectedKey = AppBlueprintKey.appBlueprintKey(prior.id());
            assertThat(cluster.putKeys()).contains(expectedKey);
        }

        @Test
        void restorePreviousBlueprint_doesNotPutUnderFailedVersionKey() {
            var prior = blueprint("app", V1, "slice-a");
            var failing = blueprint("app", V2, "slice-a");
            var failedKey = AppBlueprintKey.appBlueprintKey(blueprintId("app", V2));
            var inflight = ClusterDeploymentState.Active.InFlightBlueprint.inFlightBlueprint(failing.id(),
                                                                                             failing,
                                                                                             Option.some(prior));

            activeState().restorePreviousBlueprintForTest(inflight, prior, "boom");

            assertThat(cluster.putKeys()).doesNotContain(failedKey);
        }

        // #760/#724 review round 2 item g: `restorePreviousBlueprint` must write ROLLED_BACK in the
        // SAME consensus batch as the previous blueprint's own AppBlueprintKey Put, mirroring
        // unloadBlueprintSlices_..._recordsFailedOutcomeAtomicallyWithRemoval below — a caller must
        // never observe the restore having landed without also observing the terminal outcome
        // record for the FAILING blueprint, or vice versa.
        @Test
        void restorePreviousBlueprint_recordsRolledBackOutcomeAtomicallyWithRestore() {
            var prior = blueprint("app", V1, "slice-a");
            var failing = blueprint("app", V2, "slice-a");
            var inflight = ClusterDeploymentState.Active.InFlightBlueprint.inFlightBlueprint(failing.id(),
                                                                                             failing,
                                                                                             Option.some(prior));

            activeState().restorePreviousBlueprintForTest(inflight, prior, "boom: disk full");

            var bpKey = AppBlueprintKey.appBlueprintKey(prior.id());
            var outcomeKey = DeploymentOutcomeKey.deploymentOutcomeKey(failing.id());

            var outcomePut = cluster.commands
                    .stream()
                    .filter(c -> c instanceof KVCommand.Put<AetherKey, ?> && c.key().equals(outcomeKey))
                    .findFirst();
            assertThat(outcomePut).isPresent();

            var outcome = (DeploymentOutcomeValue) ((KVCommand.Put<?, ?>) outcomePut.get()).value();
            assertThat(outcome.status()).isEqualTo(DeploymentOutcomeStatus.ROLLED_BACK);
            assertThat(outcome.cause()).isEqualTo("boom: disk full");

            var atomicBatch = cluster.batches
                    .stream()
                    .filter(batch -> batch.stream().anyMatch(c -> c.key().equals(bpKey))
                                     && batch.stream().anyMatch(c -> c.key().equals(outcomeKey)))
                    .findFirst();
            assertThat(atomicBatch).isPresent();
        }

        // #809: the failed blueprint's OWN AppBlueprintKey (inflight.id()) must be removed in the
        // SAME consensus batch as the previous-blueprint Put and the ROLLED_BACK outcome, so a
        // reader of that key never observes the stale pre-failure value after a restore-rollback
        // lands. Mutation-probed: red with the Remove dropped from restorePreviousBlueprint's batch
        // (the exact #809 defect), green restored.
        @Test
        void restorePreviousBlueprint_removesFailedBlueprintKeyAtomicallyWithRestoreAndOutcome() {
            var prior = blueprint("app", V1, "slice-a");
            var failing = blueprint("app", V2, "slice-a");
            var inflight = ClusterDeploymentState.Active.InFlightBlueprint.inFlightBlueprint(failing.id(),
                                                                                             failing,
                                                                                             Option.some(prior));

            activeState().restorePreviousBlueprintForTest(inflight, prior, "boom: disk full");

            var priorKey = AppBlueprintKey.appBlueprintKey(prior.id());
            var failedKey = AppBlueprintKey.appBlueprintKey(failing.id());
            var outcomeKey = DeploymentOutcomeKey.deploymentOutcomeKey(failing.id());

            // get(inflight.id()) becomes empty: the failed blueprint's own key is removed.
            var removedFailedKey = cluster.commands
                    .stream()
                    .anyMatch(c -> c instanceof KVCommand.Remove<AetherKey> && c.key().equals(failedKey));
            assertThat(removedFailedKey).isTrue();

            // get(previous.id()) is the restored value.
            var restoredPriorPut = cluster.commands
                    .stream()
                    .filter(c -> c instanceof KVCommand.Put<AetherKey, ?> && c.key().equals(priorKey))
                    .findFirst();
            assertThat(restoredPriorPut).isPresent();

            var restoredValue = (AppBlueprintValue) ((KVCommand.Put<?, ?>) restoredPriorPut.get()).value();
            assertThat(restoredValue.blueprint()).isEqualTo(prior);

            // outcome(inflight.id()) is ROLLED_BACK — and all three land in ONE atomic batch, so a
            // reader never observes any one of the three without the other two.
            var atomicBatch = cluster.batches
                    .stream()
                    .filter(batch -> batch.stream().anyMatch(c -> c.key().equals(priorKey)
                                                                   && c instanceof KVCommand.Put<AetherKey, ?>)
                                     && batch.stream().anyMatch(c -> c.key().equals(failedKey)
                                                                      && c instanceof KVCommand.Remove<AetherKey>)
                                     && batch.stream().anyMatch(c -> c.key().equals(outcomeKey)
                                                                      && c instanceof KVCommand.Put<AetherKey, ?>))
                    .findFirst();
            assertThat(atomicBatch).isPresent();
        }

        // #809 review round: the ordering (Put(previous) before Remove(inflight)) is load-bearing,
        // not cosmetic — but RecordingClusterNode only RECORDS commands/batches, it never APPLIES
        // them, so every assertion above stays green even if the two commands traded places. This
        // test closes that gap: it replays the actual emitted batch through the real production
        // handlers (harness.dispatch, the same entry point a live KV-watch notification uses) IN
        // THE BATCH'S OWN RECORDED ORDER, then asserts the mechanism's actual payoff — a slice
        // SHARED between the failed and restored blueprints must survive (no SliceTargetKey
        // Remove), while a slice that existed ONLY under the failed blueprint (net-new, absent
        // from `prior`) must be deallocated as rollback cleanup (SliceTargetKey Remove emitted).
        // Mutation-probed: swapping Put(previous) and Remove(inflight) in restorePreviousBlueprint's
        // batch list turns this red — handleAppBlueprintRemoval then fires while the shared slice is
        // still owned by the failed id and wrongly deallocates it — "[a slice shared with the
        // restored blueprint must NOT be deallocated] Expecting value to be false but was true".
        @Test
        void restorePreviousBlueprint_replayedInEmittedOrder_deallocatesOnlyNetNewSlice() {
            var sharedArtifact = artifact("shared", V1);
            var netNewArtifact = artifact("netnew", V2);

            var prior = ExpandedBlueprint.expandedBlueprint(blueprintId("app", V1),
                                                             List.of(ResolvedSlice.resolvedSlice(sharedArtifact, 3, false)
                                                                                  .unwrap()));
            var failing = ExpandedBlueprint.expandedBlueprint(blueprintId("app", V2),
                                                               List.of(ResolvedSlice.resolvedSlice(sharedArtifact, 3, false)
                                                                                    .unwrap(),
                                                                       ResolvedSlice.resolvedSlice(netNewArtifact, 3, false)
                                                                                    .unwrap()));
            var inflight = ClusterDeploymentState.Active.InFlightBlueprint.inFlightBlueprint(failing.id(),
                                                                                             failing,
                                                                                             Option.some(prior));

            // Seed the FSM's own ownership map as it would be right after the failed deploy
            // activated both slices: shared AND net-new, both currently owned by the failed id.
            activeState().blueprints().put(sharedArtifact,
                                           Blueprint.blueprint(sharedArtifact, 3, 1, Option.some(failing.id()), false));
            activeState().blueprints().put(netNewArtifact,
                                           Blueprint.blueprint(netNewArtifact, 3, 1, Option.some(failing.id()), false));

            activeState().restorePreviousBlueprintForTest(inflight, prior, "boom: disk full");

            var priorKey = AppBlueprintKey.appBlueprintKey(prior.id());
            var failedKey = AppBlueprintKey.appBlueprintKey(failing.id());
            var outcomeKey = DeploymentOutcomeKey.deploymentOutcomeKey(failing.id());

            var emittedBatch = cluster.batches
                    .stream()
                    .filter(batch -> batch.stream().anyMatch(c -> c.key().equals(priorKey))
                                     && batch.stream().anyMatch(c -> c.key().equals(failedKey))
                                     && batch.stream().anyMatch(c -> c.key().equals(outcomeKey)))
                    .findFirst();
            assertThat(emittedBatch).isPresent();

            // Replay each command in the batch's OWN recorded order through the real production
            // entry point — proves the ordering instead of assuming it.
            for (var command : emittedBatch.get()) {
                replayThroughRealHandlers(command);
            }

            var sharedSliceTargetKey = SliceTargetKey.sliceTargetKey(sharedArtifact.base());
            var netNewSliceTargetKey = SliceTargetKey.sliceTargetKey(netNewArtifact.base());

            var sharedSliceRemoved = cluster.commands
                    .stream()
                    .anyMatch(c -> c instanceof KVCommand.Remove<AetherKey> && c.key().equals(sharedSliceTargetKey));
            var netNewSliceRemoved = cluster.commands
                    .stream()
                    .anyMatch(c -> c instanceof KVCommand.Remove<AetherKey> && c.key().equals(netNewSliceTargetKey));

            assertThat(sharedSliceRemoved).as("a slice shared with the restored blueprint must NOT be deallocated")
                                          .isFalse();
            assertThat(netNewSliceRemoved).as("a slice that existed only under the failed blueprint must be deallocated")
                                          .isTrue();
        }
    }

    @Nested
    class DeploymentOutcomeRecord {
        // #759: a FAILED NodeArtifact Put on an ALL_OR_NOTHING blueprint with no previous version
        // drives handleDeterministicFailure -> rollbackBlueprintForArtifact -> unloadBlueprintSlices,
        // which must atomically bundle the AppBlueprintKey removal (the original 404 cause) with a
        // durable FAILED DeploymentOutcomeKey Put in the SAME consensus batch, so the status route
        // has a terminal record to read even after the blueprint's own key is gone.
        @Test
        void unloadBlueprintSlices_deterministicFailureNoPreviousBlueprint_recordsFailedOutcomeAtomicallyWithRemoval() {
            var failing = blueprint("app", V1, "slice-a");
            var failedArtifact = artifact("slice-a", V1);
            var inflight = ClusterDeploymentState.Active.InFlightBlueprint.inFlightBlueprint(failing.id(), failing, Option.empty());
            activeState().inFlightBlueprints().put(failing.id(), inflight);

            var failValue = new NodeArtifactValue(SliceState.FAILED, Option.some("boom: disk full"), true, 0, List.of(), 0L);
            var putCommand = new KVCommand.Put<>(NodeArtifactKey.nodeArtifactKey(NODE_A, failedArtifact), failValue);
            var event = new NodeArtifactPutReceived(new ValuePut<>(putCommand, Option.none()));

            harness.dispatch(event);

            var appBlueprintKey = AppBlueprintKey.appBlueprintKey(failing.id());
            var outcomeKey = DeploymentOutcomeKey.deploymentOutcomeKey(failing.id());

            var removedAppBlueprint = cluster.commands
                    .stream()
                    .anyMatch(c -> c instanceof KVCommand.Remove<AetherKey> && c.key().equals(appBlueprintKey));
            assertThat(removedAppBlueprint).isTrue();

            var outcomePut = cluster.commands
                    .stream()
                    .filter(c -> c instanceof KVCommand.Put<AetherKey, ?> && c.key().equals(outcomeKey))
                    .findFirst();
            assertThat(outcomePut).isPresent();

            var outcome = (DeploymentOutcomeValue) ((KVCommand.Put<?, ?>) outcomePut.get()).value();
            assertThat(outcome.status()).isEqualTo(DeploymentOutcomeStatus.FAILED);
            assertThat(outcome.cause()).isEqualTo("boom: disk full");
            assertThat(outcome.failingSlices()).containsExactly(failedArtifact.asString());

            var atomicBatch = cluster.batches
                    .stream()
                    .filter(batch -> batch.stream().anyMatch(c -> c.key().equals(appBlueprintKey))
                                     && batch.stream().anyMatch(c -> c.key().equals(outcomeKey)))
                    .findFirst();
            assertThat(atomicBatch).isPresent();
        }
    }

    @Nested
    class SucceededOutcomeWriteFailure {
        // #760/#724 review round 2 item l: recordSucceededOutcome deliberately bypasses submitBatch
        // (see its Javadoc) because handleBatchFailure's reconcile() reschedule cannot recover this
        // write — by the time it runs, trackBlueprintSliceActive has already removed the blueprint
        // from inFlightBlueprints, so reconcile() has nothing left to revisit. Before this fix, a
        // failed apply() here was indistinguishable from any other transient batch failure. This
        // drives that failure with RecordingClusterNode.forcedApplyFailure and pins the targeted WARN.
        private CapturingAppender appender;
        private LoggerConfig loggerConfig;
        private Level originalLevel;

        @BeforeEach
        void captureActiveLogger() {
            appender = CapturingAppender.create("SucceededOutcomeWriteFailureCapture");
            appender.start();
            var ctx = (LoggerContext) LogManager.getContext(false);
            var configuration = ctx.getConfiguration();
            loggerConfig = getOrCreateLoggerConfig(configuration);
            originalLevel = loggerConfig.getLevel();
            loggerConfig.addAppender(appender, Level.WARN, null);
            loggerConfig.setLevel(Level.WARN);
            ctx.updateLoggers();
        }

        @AfterEach
        void releaseActiveLogger() {
            var ctx = (LoggerContext) LogManager.getContext(false);
            loggerConfig.removeAppender(appender.getName());
            loggerConfig.setLevel(originalLevel);
            ctx.updateLoggers();
            appender.stop();
        }

        @Test
        void trackBlueprintSliceActive_applyFails_warnsNamingBlueprintAndNoRetry() {
            var deploying = blueprint("app", V1, "slice-a");
            var activeArtifact = artifact("slice-a", V1);
            var inflight = ClusterDeploymentState.Active.InFlightBlueprint.inFlightBlueprint(deploying.id(), deploying, Option.empty());
            activeState().inFlightBlueprints().put(deploying.id(), inflight);

            cluster.forcedApplyFailure = Causes.cause("injected: consensus write failure");

            var activeValue = NodeArtifactValue.nodeArtifactValue(SliceState.ACTIVE);
            var putCommand = new KVCommand.Put<>(NodeArtifactKey.nodeArtifactKey(NODE_A, activeArtifact), activeValue);
            var event = new NodeArtifactPutReceived(new ValuePut<>(putCommand, Option.none()));

            harness.dispatch(event);

            assertThat(appender.capturedWarns())
                    .as("a failed recordSucceededOutcome write must be reported at WARN, naming the blueprint and that it will not be retried")
                    .anyMatch(msg -> msg.contains(deploying.id().asString())
                                     && msg.contains("NOT persisted")
                                     && msg.contains("NOT be retried"));
        }

        private LoggerConfig getOrCreateLoggerConfig(Configuration configuration) {
            var existing = configuration.getLoggerConfig(ACTIVE_LOGGER_NAME);
            if (ACTIVE_LOGGER_NAME.equals(existing.getName())) {return existing;}
            var fresh = new LoggerConfig(ACTIVE_LOGGER_NAME, Level.WARN, false);
            configuration.addLogger(ACTIVE_LOGGER_NAME, fresh);
            return fresh;
        }
    }

    @Nested
    class BestEffortFailureOutcome {
        // #760/#724 review round 3 GAP fix (151b11d94): BEST_EFFORT deployments now populate
        // inFlightBlueprints too (trackInFlightBlueprint tracks both atomicities), so a BEST_EFFORT
        // artifact reaches one of two terminals depending on how its slice ends — ACTIVE retires it
        // via trackBlueprintSliceActive's recordSucceededOutcome (see BestEffortSuccessOutcome
        // below), FAILED retires it here via recordBestEffortFailureOutcome. Uses its own harness
        // (not the shared ALL_OR_NOTHING @BeforeEach fixture) so atomicity can be set to
        // BEST_EFFORT.
        @Test
        void handleDeterministicFailure_bestEffortAtomicity_recordsFailedOutcomeForOwningBlueprint() {
            var router = MessageRouter.mutable();
            var localKvStore = new InMemoryKvStore(router);
            var localCluster = new RecordingClusterNode(SELF);
            LongSupplier clock = () -> 10_000_000L;
            Function<Fsm<ClusterDeploymentState, ClusterFsmEvent>, ClusterDeploymentState> factory =
                    fsm -> new ClusterDeploymentContext(fsm,
                                                        SELF,
                                                        localCluster,
                                                        localKvStore,
                                                        router,
                                                        stubTopologyManager(SELF),
                                                        stubSchemaOrchestrator(),
                                                        () -> Set.of(SELF, NODE_A),
                                                        () -> Set.of(SELF, NODE_A),
                                                        Set::of,
                                                        Set.of(SELF, NODE_A),
                                                        DeploymentAtomicity.BEST_EFFORT,
                                                        3,
                                                        timeSpan(300).seconds(),
                                                        clock).dormant();
            var localHarness = FsmTestHarness.harness("best-effort-test-" + SELF.id(), factory);
            localHarness.dispatch(new Activate());
            var active = (ClusterDeploymentState.Active) localHarness.state();

            var owner = blueprintId("app", V1);
            var failedArtifact = artifact("slice-a", V1);
            var ownedSlice = Blueprint.blueprint(failedArtifact, 3, 1, Option.some(owner), false);
            active.blueprints().put(failedArtifact, ownedSlice);

            var failValue = new NodeArtifactValue(SliceState.FAILED, Option.some("boom: disk full"), true, 0, List.of(), 0L);
            var putCommand = new KVCommand.Put<>(NodeArtifactKey.nodeArtifactKey(NODE_A, failedArtifact), failValue);
            var event = new NodeArtifactPutReceived(new ValuePut<>(putCommand, Option.none()));

            localHarness.dispatch(event);

            var outcomeKey = DeploymentOutcomeKey.deploymentOutcomeKey(owner);
            var outcomePut = localCluster.commands
                    .stream()
                    .filter(c -> c instanceof KVCommand.Put<AetherKey, ?> && c.key().equals(outcomeKey))
                    .findFirst();
            assertThat(outcomePut).isPresent();

            var outcome = (DeploymentOutcomeValue) ((KVCommand.Put<?, ?>) outcomePut.get()).value();
            assertThat(outcome.status()).isEqualTo(DeploymentOutcomeStatus.FAILED);
            assertThat(outcome.cause()).isEqualTo("boom: disk full");
            assertThat(outcome.failingSlices()).containsExactly(failedArtifact.asString());
        }

        @Test
        void handleDeterministicFailure_bestEffortAtomicity_noOwningBlueprint_writesNoOutcome() {
            var router = MessageRouter.mutable();
            var localKvStore = new InMemoryKvStore(router);
            var localCluster = new RecordingClusterNode(SELF);
            LongSupplier clock = () -> 10_000_000L;
            Function<Fsm<ClusterDeploymentState, ClusterFsmEvent>, ClusterDeploymentState> factory =
                    fsm -> new ClusterDeploymentContext(fsm,
                                                        SELF,
                                                        localCluster,
                                                        localKvStore,
                                                        router,
                                                        stubTopologyManager(SELF),
                                                        stubSchemaOrchestrator(),
                                                        () -> Set.of(SELF, NODE_A),
                                                        () -> Set.of(SELF, NODE_A),
                                                        Set::of,
                                                        Set.of(SELF, NODE_A),
                                                        DeploymentAtomicity.BEST_EFFORT,
                                                        3,
                                                        timeSpan(300).seconds(),
                                                        clock).dormant();
            var localHarness = FsmTestHarness.harness("best-effort-standalone-test-" + SELF.id(), factory);
            localHarness.dispatch(new Activate());
            var active = (ClusterDeploymentState.Active) localHarness.state();

            var standaloneArtifact = artifact("slice-a", V1);
            var standaloneSlice = Blueprint.blueprint(standaloneArtifact, 3, 1, Option.empty(), false);
            active.blueprints().put(standaloneArtifact, standaloneSlice);

            var failValue = new NodeArtifactValue(SliceState.FAILED, Option.some("boom"), true, 0, List.of(), 0L);
            var putCommand = new KVCommand.Put<>(NodeArtifactKey.nodeArtifactKey(NODE_A, standaloneArtifact), failValue);
            var event = new NodeArtifactPutReceived(new ValuePut<>(putCommand, Option.none()));

            localHarness.dispatch(event);

            var anyOutcomePut = localCluster.commands
                    .stream()
                    .anyMatch(c -> c instanceof KVCommand.Put<AetherKey, ?> p && p.value() instanceof DeploymentOutcomeValue);
            assertThat(anyOutcomePut).isFalse();
        }
    }

    @Nested
    class BestEffortSuccessOutcome {
        // #760/#724 review round 3 GAP: trackInFlightBlueprint gated inFlightBlueprints population
        // on ALL_OR_NOTHING, so a BEST_EFFORT deployment whose slices ALL reach ACTIVE never got an
        // entry in that map — trackBlueprintSliceActive's success detection (removing the blueprint
        // once pendingSlices empties, then recordSucceededOutcome) is otherwise atomicity-agnostic
        // and never got a chance to run. Before this fix, a fully successful BEST_EFFORT deployment
        // wrote NO outcome record at all — BlueprintService's accessor Javadoc case 2 ("an outcome
        // will land once it does") was false for it. Drives the REAL deploy-registration path via
        // AppBlueprintPutReceived (not manual inFlightBlueprints seeding, which the sibling tests
        // above use for OTHER, already-correct code paths) because the bug is specifically in the
        // gate that decides whether to populate the map in the first place.
        @Test
        void handleSliceActive_bestEffortAtomicity_allSlicesActive_recordsSucceededOutcome() {
            var router = MessageRouter.mutable();
            var localKvStore = new InMemoryKvStore(router);
            var localCluster = new RecordingClusterNode(SELF);
            LongSupplier clock = () -> 10_000_000L;
            Function<Fsm<ClusterDeploymentState, ClusterFsmEvent>, ClusterDeploymentState> factory =
                    fsm -> new ClusterDeploymentContext(fsm,
                                                        SELF,
                                                        localCluster,
                                                        localKvStore,
                                                        router,
                                                        stubTopologyManager(SELF),
                                                        stubSchemaOrchestrator(),
                                                        () -> Set.of(SELF, NODE_A),
                                                        () -> Set.of(SELF, NODE_A),
                                                        Set::of,
                                                        Set.of(SELF, NODE_A),
                                                        DeploymentAtomicity.BEST_EFFORT,
                                                        3,
                                                        timeSpan(300).seconds(),
                                                        clock).dormant();
            var localHarness = FsmTestHarness.harness("best-effort-success-test-" + SELF.id(), factory);
            localHarness.dispatch(new Activate());

            var expanded = blueprint("app", V1, "slice-a");
            var deployValue = AppBlueprintValue.appBlueprintValue(expanded);
            var deployCommand = new KVCommand.Put<>(AppBlueprintKey.appBlueprintKey(expanded.id()), deployValue);
            localHarness.dispatch(new AppBlueprintPutReceived(new ValuePut<>(deployCommand, Option.none())));

            var activeArtifact = artifact("slice-a", V1);
            var activeValue = NodeArtifactValue.nodeArtifactValue(SliceState.ACTIVE);
            var putCommand = new KVCommand.Put<>(NodeArtifactKey.nodeArtifactKey(NODE_A, activeArtifact), activeValue);
            localHarness.dispatch(new NodeArtifactPutReceived(new ValuePut<>(putCommand, Option.none())));

            var outcomeKey = DeploymentOutcomeKey.deploymentOutcomeKey(expanded.id());
            var outcomePut = localCluster.commands
                    .stream()
                    .filter(c -> c instanceof KVCommand.Put<AetherKey, ?> && c.key().equals(outcomeKey))
                    .findFirst();
            assertThat(outcomePut)
                    .as("a BEST_EFFORT deployment whose only slice reaches ACTIVE must write a SUCCEEDED outcome")
                    .isPresent();

            var outcome = (DeploymentOutcomeValue) ((KVCommand.Put<?, ?>) outcomePut.get()).value();
            assertThat(outcome.status()).isEqualTo(DeploymentOutcomeStatus.SUCCEEDED);
        }
    }

    /// #759 review (M2) — the rollback sequence that #759's `BlueprintResponse` doc comment
    /// (`ManagementApiResponses.java`) and `management-api.md` now describe: a deterministic,
    /// fatal slice failure under `ALL_OR_NOTHING` removes the blueprint's KV entry outright
    /// (`unloadBlueprintSlices`, `ClusterDeploymentState.java:2139-2158`), rather than recording a
    /// FAILED status anyone can read back. No test previously drove this sequence end to end.
    ///
    /// This only covers the half of the sequence owned by THIS module — the FAILED `NodeArtifact`
    /// Put through the `AppBlueprintKey` Remove command. The other half is a different module's HTTP
    /// layer: **#759 Phase 2 has since landed** (`SliceRoutes.routeBlueprintStatusByOutcome`,
    /// `aether/node`) and reads the durable `DeploymentOutcomeValue` this rollback sequence also
    /// writes, ahead of the (now-removed) live `AppBlueprintKey` — so a real status read after THIS
    /// exact sequence answers `200` `FAILED`/`ROLLED_BACK` with `cause`/`failingSlices`, not `404`
    /// (`BlueprintStatusAggregationTest#statusRoute_outcomeFailed_returns200Failed`,
    /// `#statusRoute_outcomeRolledBack_returns200RolledBack`). `404
    /// BLUEPRINT_NOT_FOUND` (`BlueprintStatusAggregationTest#statusRoute_blueprintAbsentFromKvAndNoOutcome_returns404BlueprintNotFound`)
    /// is now the DIFFERENT, narrower case of no outcome ever having been recorded for the id at all —
    /// not the general shape of this test's rollback sequence, as an earlier version of this comment
    /// claimed before Phase 2 landed.
    @Nested
    class RollbackSequence {
        @Test
        void deterministicFatalFailure_freshDeploy_removesAppBlueprintKeyFromKv() {
            var expanded = blueprint("app", V1, "slice-a");
            var sliceArtifact = artifact("slice-a", V1);

            harness.dispatch(new AppBlueprintPutReceived(appBlueprintPut(expanded)));
            assertThat(cluster.putKeys()).as("initial publish tracks the blueprint in-flight, not yet rolled back")
                                         .doesNotContain(AppBlueprintKey.appBlueprintKey(expanded.id()));

            harness.dispatch(new NodeArtifactPutReceived(fatalFailurePut(SELF, sliceArtifact, "boom")));

            var blueprintKey = AppBlueprintKey.appBlueprintKey(expanded.id());
            assertThat(cluster.removeKeys()).as("#759 C2 — deterministic failure under ALL_OR_NOTHING "
                                                 + "removes the blueprint's KV entry outright, it does not "
                                                 + "record a durable FAILED status")
                                            .contains(blueprintKey);
            assertThat(cluster.removeKeys()).contains(SliceTargetKey.sliceTargetKey(sliceArtifact.base()));
        }

        @Test
        void deterministicFatalFailure_nonFatalOrTransient_doesNotRollback() {
            var expanded = blueprint("app", V1, "slice-a");
            var sliceArtifact = artifact("slice-a", V1);

            harness.dispatch(new AppBlueprintPutReceived(appBlueprintPut(expanded)));
            harness.dispatch(new NodeArtifactPutReceived(transientFailurePut(SELF, sliceArtifact, "retry me")));

            assertThat(cluster.removeKeys()).as("a transient (non-fatal) failure retries in place; "
                                               + "it must not trigger the ALL_OR_NOTHING rollback")
                                            .doesNotContain(AppBlueprintKey.appBlueprintKey(expanded.id()));
        }
    }

    private static ValuePut<AppBlueprintKey, AppBlueprintValue> appBlueprintPut(ExpandedBlueprint expanded) {
        var key = AppBlueprintKey.appBlueprintKey(expanded.id());
        var value = AppBlueprintValue.appBlueprintValue(expanded);
        return new ValuePut<>(new KVCommand.Put<>(key, value), Option.none());
    }

    private static ValuePut<NodeArtifactKey, NodeArtifactValue> fatalFailurePut(NodeId nodeId,
                                                                                Artifact artifact,
                                                                                String reason) {
        var key = NodeArtifactKey.nodeArtifactKey(nodeId, artifact);
        var value = new NodeArtifactValue(SliceState.FAILED, Option.option(reason), true, 0, List.of(), 0L);
        return new ValuePut<>(new KVCommand.Put<>(key, value), Option.none());
    }

    private static ValuePut<NodeArtifactKey, NodeArtifactValue> transientFailurePut(NodeId nodeId,
                                                                                    Artifact artifact,
                                                                                    String reason) {
        var key = NodeArtifactKey.nodeArtifactKey(nodeId, artifact);
        var value = new NodeArtifactValue(SliceState.FAILED, Option.option(reason), false, 0, List.of(), 0L);
        return new ValuePut<>(new KVCommand.Put<>(key, value), Option.none());
    }

    // --- test fixtures (mirrors ClusterDeploymentStateActiveTest) ---

    private static SchemaOrchestratorService stubSchemaOrchestrator() {
        return new SchemaOrchestratorService() {
            @Override public Promise<Unit> migrateIfNeeded(String datasourceName) {
                return Promise.success(Unit.unit());
            }

            @Override public Promise<Unit> undoTo(String datasourceName, int targetVersion) {
                return Promise.success(Unit.unit());
            }

            @Override public Promise<Unit> baseline(String datasourceName, int version) {
                return Promise.success(Unit.unit());
            }
        };
    }

    private static TopologyManager stubTopologyManager(NodeId self) {
        return new TopologyManager() {
            @Override public NodeInfo self() {
                return NodeInfo.nodeInfo(self, new NodeAddress("localhost", 9000));
            }

            @Override public Option<NodeInfo> get(NodeId id) {
                return Option.some(NodeInfo.nodeInfo(id, new NodeAddress("localhost", 9000)));
            }

            @Override public int clusterSize() {
                return 2;
            }

            @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {
                return Option.empty();
            }

            @Override public Promise<Unit> start() {
                return Promise.unitPromise();
            }

            @Override public Promise<Unit> stop() {
                return Promise.unitPromise();
            }

            @Override public TimeSpan pingInterval() {
                return timeSpan(5).seconds();
            }

            @Override public TimeSpan helloTimeout() {
                return timeSpan(5).seconds();
            }

            @Override public Option<NodeState> getState(NodeId id) {
                return Option.empty();
            }

            @Override public List<NodeId> topology() {
                return List.of(self);
            }
        };
    }

    private static final class RecordingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        final NodeId self;
        final List<KVCommand<AetherKey>> commands = Collections.synchronizedList(new ArrayList<>());
        // Tracks each apply() call's batch verbatim (unlike `commands`, which flattens them) so
        // tests can pin that two commands landed in the SAME consensus batch, not merely both
        // somewhere in the recorded history — see DeploymentOutcomeRecord (#759).
        final List<List<KVCommand<AetherKey>>> batches = Collections.synchronizedList(new ArrayList<>());
        // #760/#724 review round 2 item l: lets a single test force `apply()` to fail without a real
        // consensus fault, so `recordSucceededOutcome`'s WARN path (SucceededOutcomeWriteFailure below)
        // can be driven red-before-green. Null (the default) preserves every other test's always-succeeds
        // behavior untouched.
        volatile Cause forcedApplyFailure;

        RecordingClusterNode(NodeId self) {this.self = self;}

        @Override public NodeId self() {return self;}

        @Override public TopologyManager topologyManager() {return stubTopologyManager(self);}

        @Override public Promise<Unit> start() {return Promise.unitPromise();}

        @Override public Promise<Unit> stop() {return Promise.unitPromise();}

        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> batch) {
            commands.addAll(batch);
            batches.add(List.copyOf(batch));

            var failure = forcedApplyFailure;
            if (failure != null) {
                return failure.promise();
            }

            return Promise.success(Collections.emptyList());
        }

        List<AetherKey> putKeys() {
            synchronized (commands) {
                return commands.stream()
                               .filter(c -> c instanceof KVCommand.Put<AetherKey, ?>)
                               .map(KVCommand::key)
                               .toList();
            }
        }

        /// #759 review (M2) — the rollback-sequence test needs to see `KVCommand.Remove`, not
        /// just `Put`: `unloadBlueprintSlices` rolls back by REMOVING the `AppBlueprintKey`, it
        /// never Puts a FAILED status anywhere.
        List<AetherKey> removeKeys() {
            synchronized (commands) {
                return commands.stream()
                               .filter(c -> c instanceof KVCommand.Remove<AetherKey>)
                               .map(KVCommand::key)
                               .toList();
            }
        }
    }

    private static final class InMemoryKvStore extends KVStore<AetherKey, AetherValue> {
        InMemoryKvStore(MessageRouter router) {
            super(router, stubSerializer(), stubDeserializer());
        }

        void put(AetherKey key, AetherValue value) {
            process(createBatch(List.of(new KVCommand.Put<>(key, value))));
        }
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }

    /// In-memory log4j2 appender capturing WARN-and-above messages for assertions. Mirrors
    /// `SchemaActivationGateTest.CapturingAppender` — kept self-contained here rather than shared,
    /// matching that precedent's own per-file scope.
    private static final class CapturingAppender extends AbstractAppender {
        private final List<String> messages = new CopyOnWriteArrayList<>();

        private CapturingAppender(String name, Layout<?> layout) {
            super(name, (Filter) null, layout, true, Property.EMPTY_ARRAY);
        }

        static CapturingAppender create(String name) {
            var layout = PatternLayout.createDefaultLayout();
            return new CapturingAppender(name, layout);
        }

        @Override public void append(LogEvent event) {
            if (event.getLevel().isMoreSpecificThan(Level.WARN)) {
                messages.add(event.getMessage().getFormattedMessage());
            }
        }

        List<String> capturedWarns() {
            return messages.stream().collect(Collectors.toUnmodifiableList());
        }
    }
}
