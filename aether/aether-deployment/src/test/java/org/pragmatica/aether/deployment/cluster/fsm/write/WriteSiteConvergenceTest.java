// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm.write;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.AppBlueprintPutReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.MembershipDecisionReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.NodeArtifactPutReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentState;
import org.pragmatica.aether.slice.SliceLoadingFailure.Unrecognised;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentOutcomeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.CoreError;
import org.pragmatica.statemachine.FsmTestHarness;
import org.pragmatica.testlogging.LogCapture;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.cluster.fsm.write.WriteFixture.OTHER;
import static org.pragmatica.aether.deployment.cluster.fsm.write.WriteFixture.SELF;
import static org.pragmatica.aether.deployment.cluster.fsm.write.WriteFixture.active;
import static org.pragmatica.aether.deployment.cluster.fsm.write.WriteFixture.activeLeader;
import static org.pragmatica.aether.deployment.cluster.fsm.write.WriteFixture.commitLeader;
import static org.pragmatica.aether.deployment.cluster.fsm.write.WriteFixture.freshStore;
import static org.pragmatica.aether.deployment.cluster.fsm.write.WriteFixture.seed;

/// #2584 design §3.1 T1: every (`WriteSite`, `Mechanism`) pair a site declares has a scenario driving the real leader
/// FSM. P0 declares only `NOT_LEADER`, whose scenario is the one for the site's [NotLeaderAction]: each arm commits a
/// leader record naming ANOTHER node into the store of a live `Active`, then drives one write site of that action.
class WriteSiteConvergenceTest {
    private static final Artifact SLICE = Artifact.artifact("com.example:slice-a:1.0.0").unwrap();
    private static final NodeId JOINER = new NodeId("node-joiner");
    private static final int TERMINAL_ON_REPORT = 6;
    private static final Set<String> LEGACY_SITES = Set.of("SCHEMA_STALLED_RESET",
                                                           "BLUEPRINT_DELETE_TARGET_REMOVE",
                                                           "CORE_DIRECTIVE",
                                                           "WORKER_ASSIGNMENT",
                                                           "BEST_EFFORT_FAILURE_OUTCOME",
                                                           "ACTIVATE",
                                                           "ACTIVATE_REISSUE",
                                                           "UNLOAD_SLICE_FAILED",
                                                           "UNLOAD_STUCK_RESET",
                                                           "NODE_ARTIFACT_REMOVE_STUCK",
                                                           "DEPARTURE_DIRECTIVE_REMOVE",
                                                           "SUCCEEDED_OUTCOME",
                                                           "ROLLBACK_UNFENCED",
                                                           "ROLLBACK_FENCED");

    private final KVStore<AetherKey, AetherValue> store = freshStore();
    private final WriteFixture.RecordingClusterNode cluster = new WriteFixture.RecordingClusterNode(store);
    private final LogCapture log = LogCapture.capture(ClusterDeploymentState.Active.class, Level.INFO, false);

    @AfterEach
    void releaseLog() {
        log.close();
    }

    /// D13b: an UNLOAD issued after another node's leader record committed is not applied, and the site's own failure
    /// line reports it.
    @Test
    void notLeader_FAIL_NOT_LEADER() throws ReflectiveOperationException {
        var harness = deposedLeader(Set.of(SELF, OTHER));
        var key = SliceNodeKey.sliceNodeKey(SLICE, SELF);

        issueUnload(active(harness), WriteSite.UNLOAD_SLICE_FAILED, key);

        assertThat(cluster.batches()).as("no apply from a deposed leader").isEmpty();
        assertThat(log.messages(Level.ERROR)).as("the site's existing failure line fired")
                                             .anyMatch(message -> message.startsWith("Failed to issue UNLOAD command for " + key));
    }

    /// D05: a joining core node's directive is refused with the hierarchy writer's INACTIVE, as it always was.
    @Test
    void notLeader_INACTIVE_REFUSAL() {
        var harness = deposedLeader(Set.of(SELF, OTHER));

        harness.dispatch(new MembershipDecisionReceived(MembershipDecision.nodeJoined(JOINER, List.of(SELF, OTHER, JOINER))));

        assertThat(cluster.batches()).as("no directive write").noneMatch(batch -> writes(batch, ActivationDirectiveKey.activationDirectiveKey(JOINER)));
        assertThat(log.messages(Level.WARN)).anyMatch(message -> message.startsWith("Activation refused for " + JOINER));
    }

    /// D20 (#2592 moved into TermWriter): the restore rollback is not submitted, `onAbandoned` releases the restore
    /// marker, and the failure path with its reconcile (`abandonRollback` -> `handleBatchFailure`) does not run.
    @Test
    void notLeader_ABANDON_ROLLBACK() {
        commitLeader(store, SELF, 1);
        var harness = activeLeader(cluster, store, Set.of(SELF, OTHER));
        var first = blueprint(3);
        var second = blueprint(2);

        applyBlueprint(harness, first, "attempt-1", 1L);
        applyBlueprint(harness, second, "attempt-2", 2L);
        commitLeader(store, OTHER, 2);
        cluster.forget();
        for (var report = 1; report <= TERMINAL_ON_REPORT; report++) {
            harness.dispatch(new NodeArtifactPutReceived(failureOn(SELF)));
        }

        assertThat(active(harness).permanentlyFailed()).as("precondition: the failure reached the rollback").contains(SLICE);
        assertThat(cluster.batches()).as("no rollback transaction submitted")
                                     .noneMatch(batch -> batch.stream().anyMatch(KVCommand.LeaderTransaction.class::isInstance));
        assertThat(active(harness).restoringBlueprints()).as("onAbandoned released the restore marker").doesNotContain(first.id());
        assertThat(log.messages(Level.INFO)).as("the rollback was abandoned as not-leader")
                                            .anyMatch(message -> message.contains("rollback of blueprint " + first.id().asString() + " not issued"));
        assertThat(log.messages(Level.ERROR)).as("not routed to abandonRollback, whose handleBatchFailure schedules a reconcile")
                                             .noneMatch(message -> message.contains("could not be written"));
    }

    @Test
    void totality() {
        var missing = new ArrayList<String>();

        for (var site : WriteSite.values()) {
            site.mechanisms()
                .stream()
                .filter(mechanism -> !hasScenario(site, mechanism))
                .forEach(mechanism -> missing.add("no scenario for " + site + "/" + mechanism));
        }
        assertThat(missing).isEmpty();
    }

    /// An ENABLED tripwire: P1-P4 move sites off LEGACY one phase at a time, and each move must update this set.
    @Test
    void legacySites_areExactly_D01_D04_D05_D06_D10_D11_D13b_D13f_D14b_D16_D18_D19_D20() {
        assertThat(sitesOn(Route.LEGACY)).isEqualTo(LEGACY_SITES);
    }

    @Disabled("P5")
    @Test
    void noLegacySitesRemain() {
        assertThat(sitesOn(Route.LEGACY)).isEmpty();
    }

    @Disabled("P5")
    @Test
    void everySiteDeclaresAppliedThenFailedAndNotLeader() {
        assertThat(WriteSite.values()).allMatch(site -> site.mechanisms().containsAll(EnumSet.of(Mechanism.APPLIED_THEN_FAILED, Mechanism.NOT_LEADER)));
    }

    /// P0's only scenarios are the three not-leader arms above, one per [NotLeaderAction]; every other mechanism has none yet.
    private static boolean hasScenario(WriteSite site, Mechanism mechanism) {
        return mechanism == Mechanism.NOT_LEADER && Arrays.stream(WriteSiteConvergenceTest.class.getDeclaredMethods())
                                                          .anyMatch(method -> method.getName().equals("notLeader_" + site.notLeader().name())
                                                                              && method.isAnnotationPresent(Test.class));
    }

    private static Set<String> sitesOn(Route route) {
        return Arrays.stream(WriteSite.values())
                     .filter(site -> site.route() == route)
                     .map(WriteSite::name)
                     .collect(Collectors.toSet());
    }

    /// A live `Active` that has just seen ANOTHER node's leader record commit, before its own `onExit`.
    private FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> deposedLeader(Set<NodeId> seeds) {
        commitLeader(store, SELF, 1);
        var harness = activeLeader(cluster, store, seeds);

        commitLeader(store, OTHER, 2);
        cluster.forget();

        return harness;
    }

    private static void issueUnload(ClusterDeploymentState.Active active, WriteSite site, SliceNodeKey key) throws ReflectiveOperationException {
        var method = ClusterDeploymentState.Active.class.getDeclaredMethod("issueUnloadCommand", WriteSite.class, SliceNodeKey.class);

        method.setAccessible(true);
        try {
            method.invoke(active, site, key);
        } catch (InvocationTargetException e) {
            throw new AssertionError(e.getCause());
        }
    }

    private static boolean writes(List<KVCommand<AetherKey>> batch, AetherKey key) {
        return batch.stream().anyMatch(command -> command.key().equals(key));
    }

    private void applyBlueprint(FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> harness,
                                ExpandedBlueprint expanded,
                                String attemptId,
                                long outcomeVersion) {
        var value = AppBlueprintValue.appBlueprintValue(expanded, false, attemptId);

        seed(store,
             new KVCommand.Put<>(AppBlueprintKey.appBlueprintKey(expanded.id()), value),
             new KVCommand.Put<>(DeploymentOutcomeKey.deploymentOutcomeKey(expanded.id()),
                                 DeploymentOutcomeValue.inProgress(1L, outcomeVersion, attemptId)));
        harness.dispatch(new AppBlueprintPutReceived(new ValuePut<>(new KVCommand.Put<>(AppBlueprintKey.appBlueprintKey(expanded.id()), value),
                                                                    Option.none())));
    }

    private static ValuePut<NodeArtifactKey, NodeArtifactValue> failureOn(NodeId node) {
        var value = NodeArtifactValue.failedNodeArtifactValue(new CoreError.Timeout("downstream dependency restarting"), Unrecognised.RETRY);

        return new ValuePut<>(new KVCommand.Put<>(NodeArtifactKey.nodeArtifactKey(node, SLICE), value), Option.none());
    }

    private static ExpandedBlueprint blueprint(int instances) {
        var id = BlueprintId.blueprintId("com.example:app:1.0.0").unwrap();

        return ExpandedBlueprint.expandedBlueprint(id, List.of(ResolvedSlice.resolvedSlice(SLICE, instances, false).unwrap()));
    }
}
