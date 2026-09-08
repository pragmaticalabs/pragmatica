// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.Activate;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.AppBlueprintPutReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.NodeArtifactPutReceived;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.slice.SliceLoadingFailure.Unrecognised;
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
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.CoreError;
import org.pragmatica.lang.io.TimeSpan;
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
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import io.netty.buffer.ByteBuf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #922 — retry exhaustion settles permanently IF AND ONLY IF a blueprint APPLY is still
/// outstanding for the artifact. Replaces `RetryExhaustionEverActiveTest`.
///
/// The question the FSM asks changed, and so did what has to be pinned. Three earlier rounds asked
/// "has this artifact EVER been healthy?" and were defeated three times by the same shape: a store
/// that could not answer. Round 1 read present-tense liveness, which [Active#handleSliceFailure]
/// erases before either branch runs. Round 2 added an in-memory `everActiveArtifacts`, empty on a
/// new leader. Round 3 added a durable SUCCEEDED record reached through `Blueprint::owner` — the
/// pointer `ControlLoopContext.applyScaling` and `AbTestManager.targetPreservingOverrides` overwrite
/// with `Option.none()` (#698). Each failure was SILENT, and silence meant CONDEMN.
///
/// [Active#deploymentApplyOutstanding] asks about the OPERATION instead: `AppBlueprintKey(id)`
/// present with no `DeploymentOutcomeKey(id)` record. `BlueprintService` writes that pair in one
/// consensus batch (#759). That batching is pinned independently, in `aether/node`, by
/// `BlueprintServiceTest.RedeployAfterPriorFailureTests#publish_writesBlueprintAndClearsStaleOutcome_inOneConsensusBatch`
/// and its `delete_...` sibling — those are a PRECONDITION of everything here, and if they go red
/// these results mean nothing.
///
/// **Fixture fidelity, and it is load-bearing twice.** Production reaches the KV-Store for both
/// halves of the marker, so a fixture that only dispatches FSM events would leave the store empty
/// and make `deploymentApplyOutstanding` answer FALSE everywhere — every "does not settle" test
/// would pass vacuously while pinning nothing. So [#applyBlueprint] writes `AppBlueprintKey` into
/// the store AND dispatches the notification, exactly as consensus does, and
/// [RecordingClusterNode#apply] applies what the leader submits into the same store rather than
/// only recording it. Without the second, `recordSucceededOutcome`'s write would never land and the
/// SUCCEEDED arm could not be reached by the production path at all.
///
/// Assertions read `Active.permanentlyFailed()` directly. `Active` is a record, so the accessor IS
/// the terminal — not an inference from command counts, which cannot distinguish "not condemned"
/// from "condemned but the rollback matched nothing".
class RetryExhaustionApplyOutstandingTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final Artifact SLICE = Artifact.artifact("com.example:slice-a:1.0.0").unwrap();
    private static final Artifact SLICE_B = Artifact.artifact("com.example:slice-b:1.0.0").unwrap();
    private static final Artifact SLICE_V2 = Artifact.artifact("com.example:slice-a:2.0.0").unwrap();

    /// `ClusterDeploymentState.Active.MAX_RETRIES` is private; 5 retries means the SIXTH reported
    /// failure is the one that spends the budget.
    private static final int TERMINAL_ON_REPORT = 6;

    /// Sentinel for [#outcomeStatusName]: no `DeploymentOutcomeValue` at all. Deliberately not a
    /// `DeploymentOutcomeStatus` name, so it can never collide with a real status.
    private static final String NO_OUTCOME = "NO-OUTCOME-RECORD";

    private static final Supplier<Set<NodeId>> RESOLVED_MEMBERSHIP = () -> Set.of(SELF, NODE_A);

    private KVStore<AetherKey, AetherValue> leaderStore;
    private FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> leaderHarness;

    @BeforeEach
    void setUp() {
        leaderStore = freshStore();
        leaderHarness = leaderHarness(new RecordingClusterNode(SELF, leaderStore), leaderStore, RESOLVED_MEMBERSHIP);
    }

    /// INSTRUMENT CHECK, and it is a precondition for every other test in this class.
    ///
    /// `deploymentApplyOutstanding` is `AppBlueprintKey` present AND `DeploymentOutcomeKey` absent.
    /// In a fixture where nothing ever lands in the `KVStore`, the second conjunct is
    /// UNCONDITIONALLY TRUE — the predicate degenerates to "blueprint present", and every
    /// `doesNotContain` assertion below would pass without exercising the mechanism at all. Green,
    /// fast, and vacuous.
    ///
    /// **What the fixture could not do before, and can now.** The inherited `RecordingClusterNode`
    /// only RECORDED what the leader submitted; `cluster.apply` never reached the store, so no
    /// `DeploymentOutcomeValue` written by the production path was ever readable back and the store
    /// could only ever be in one of the two states this predicate distinguishes. It now applies each
    /// submitted batch into the same `KVStore` the FSM reads, as consensus does for every replica
    /// including the leader's own.
    ///
    /// This test drives that end to end: absent before, SUCCEEDED after, read back through
    /// `DeploymentOutcomeKey` — the same key and store `applyNotYetTerminal` consults. If the
    /// fixture ever regresses to record-only, this fails first and names the reason.
    @Test
    void instrumentCheck_theFixtureHoldsBothOutcomeStates_soTheAssertionsAreNotVacuous() {
        var expanded = blueprint();

        applyBlueprint(leaderHarness, leaderStore, expanded);

        assertThat(outcomeStatusName(leaderStore, expanded.id()))
                .as("before every slice is ACTIVE the apply carries IN_PROGRESS — the POSITIVE state in "
                    + "which the predicate answers OUTSTANDING. #963 replaced the absence that used to "
                    + "stand here, which was indistinguishable from a record that was never written")
                .isEqualTo(DeploymentOutcomeStatus.IN_PROGRESS.name());

        // The production path: every slice ACTIVE retires the blueprint via trackBlueprintSliceActive,
        // whose terminal is recordSucceededOutcome -> submitBatch -> ctx.cluster().apply(...).
        leaderHarness.dispatch(new NodeArtifactPutReceived(replayOn(NODE_A, SLICE, activeInstance())));
        leaderHarness.dispatch(new NodeArtifactPutReceived(replayOn(SELF, SLICE, activeInstance())));

        assertThat(outcomeStatusName(leaderStore, expanded.id()))
                .as("and after, the record the FSM wrote through cluster.apply must be READABLE BACK "
                    + "from the store — a record-only fixture leaves this at NO_OUTCOME and silently "
                    + "makes every 'does not settle' test in this class pass for the wrong reason")
                .isEqualTo(DeploymentOutcomeStatus.SUCCEEDED.name());
    }

    /// CONTROL A (positive polarity). The apply is outstanding and nothing ever came up, so
    /// exhaustion MUST settle. Without this the suite cannot observe settling at all, and every
    /// `doesNotContain` below would be satisfied by a fix that simply never settles. This is #922's
    /// own population.
    @Test
    void control_applyOutstanding_neverActive_doesSettle() {
        var expanded = blueprint();

        applyBlueprint(leaderHarness, leaderStore, expanded);
        exhaustRetryBudgetOn(leaderHarness, SELF, SLICE);

        assertThat(activeState(leaderHarness).permanentlyFailed())
                .as("instrument check: an apply that produced nothing MUST reach a terminal")
                .contains(SLICE);
    }

    /// CONTROL B (negative polarity), and it is CONTROL A with ONE variable changed: the blueprint
    /// reaches its terminal first, so a `DeploymentOutcomeValue` exists.
    ///
    /// The pair is what discriminates. Same artifact, same failure reports, same node, same
    /// membership — the outcome record is the only difference, so a green pair cannot be explained
    /// by the harness condemning everything or nothing.
    @Test
    void control_applyAlreadySucceeded_doesNotSettle() {
        var expanded = blueprint();

        applyBlueprint(leaderHarness, leaderStore, expanded);
        // Drive the real production path to the SUCCEEDED record: every slice ACTIVE retires the
        // blueprint via `trackBlueprintSliceActive`, which calls `recordSucceededOutcome`.
        leaderHarness.dispatch(new NodeArtifactPutReceived(replayOn(NODE_A, SLICE, activeInstance())));
        leaderHarness.dispatch(new NodeArtifactPutReceived(replayOn(SELF, SLICE, activeInstance())));

        assertThat(outcomeStatusName(leaderStore, expanded.id()))
                .as("precondition: the production path must have written the terminal record, or this "
                    + "test is asserting against an apply that is still outstanding")
                .isEqualTo(DeploymentOutcomeStatus.SUCCEEDED.name());

        exhaustRetryBudgetOn(leaderHarness, SELF, SLICE);

        assertThat(activeState(leaderHarness).permanentlyFailed())
                .as("the apply reached its terminal, so this is reconciliation of a running workload — "
                    + "a node-local transient must never condemn it cluster-wide")
                .doesNotContain(SLICE);
    }

    /// #924 round 4 BLOCKING (B1), pinned. The autoscaler erases `SliceTargetValue.owningBlueprint`
    /// on every scaling write, and round 3's durable leg reached its evidence only through that
    /// pointer — so an autoscaled slice was condemned after a failover with its own SUCCEEDED record
    /// sitting unread in the KV-Store.
    ///
    /// The seeded state here is byte-identical to a healthy applied deployment EXCEPT that the
    /// owner is `Option.none()`. Under the old discriminator that single field decided condemnation;
    /// [Active#owningBlueprintOf] reads `ExpandedBlueprint.loadOrder()` instead, which no other
    /// subsystem writes, so the field is not consulted at all.
    @Test
    void autoscaledSliceWithErasedOwner_afterFailover_isNotCondemned() {
        var expanded = blueprint();
        var newLeaderStore = freshStore();
        var newLeaderHarness = leaderHarness(new RecordingClusterNode(SELF, newLeaderStore),
                                             newLeaderStore,
                                             RESOLVED_MEMBERSHIP);

        // Exactly what a new leader finds after an autoscale event: the blueprint, its SUCCEEDED
        // record, and a slice target whose owner the control loop overwrote with none().
        seed(newLeaderStore,
             new KVCommand.Put<>(AppBlueprintKey.appBlueprintKey(expanded.id()),
                                 AppBlueprintValue.appBlueprintValue(expanded)),
             new KVCommand.Put<>(DeploymentOutcomeKey.deploymentOutcomeKey(expanded.id()),
                                 DeploymentOutcomeValue.succeeded(1L)),
             new KVCommand.Put<>(SliceTargetKey.sliceTargetKey(SLICE.base()),
                                 SliceTargetValue.sliceTargetValue(SLICE.version(), 3, Option.none())));

        exhaustRetryBudgetOn(newLeaderHarness, SELF, SLICE);

        assertThat(activeState(newLeaderHarness).permanentlyFailed())
                .as("#698 erases the owner pointer, and that must not be able to condemn a workload "
                    + "whose blueprint demonstrably applied — the SUCCEEDED record is right there")
                .doesNotContain(SLICE);
    }

    /// Round-2 BLOCKING's second shape: `instances = 1`, so no sibling exists that could vote the
    /// artifact alive, and a failover leaves no in-memory evidence either. Both of the old
    /// artifact-scoped legs are structurally unable to answer here; the apply marker is unaffected
    /// because it is not about the artifact's instances at all.
    @Test
    void singleInstanceSlice_afterFailover_withAppliedBlueprint_isNotCondemned() {
        var expanded = singleInstanceBlueprint();
        var newLeaderStore = freshStore();
        var newLeaderHarness = leaderHarness(new RecordingClusterNode(SELF, newLeaderStore),
                                             newLeaderStore,
                                             RESOLVED_MEMBERSHIP);

        seed(newLeaderStore,
             new KVCommand.Put<>(AppBlueprintKey.appBlueprintKey(expanded.id()),
                                 AppBlueprintValue.appBlueprintValue(expanded)),
             new KVCommand.Put<>(DeploymentOutcomeKey.deploymentOutcomeKey(expanded.id()),
                                 DeploymentOutcomeValue.succeeded(1L)));

        exhaustRetryBudgetOn(newLeaderHarness, SELF, SLICE);

        assertThat(activeState(newLeaderHarness).permanentlyFailed())
                .as("a single-instance workload has no sibling to vote for it, and that must not be "
                    + "what decides whether it is condemned")
                .doesNotContain(SLICE);
    }

    /// The failover pin in the OTHER direction, and the reason the marker beats `inFlightBlueprints`.
    ///
    /// A new leader rebuilds every in-memory collection empty, so `inFlightBlueprints` is empty here
    /// and a discriminator resting on it would decline to settle — silently reopening #922 for every
    /// deployment whose leader changed mid-apply. The durable pair still says the apply never
    /// finished, so the terminal is still reached.
    @Test
    void newLeader_withApplyStillOutstanding_stillSettles() {
        var expanded = blueprint();
        var newLeaderStore = freshStore();
        var newLeaderHarness = leaderHarness(new RecordingClusterNode(SELF, newLeaderStore),
                                             newLeaderStore,
                                             RESOLVED_MEMBERSHIP);

        seed(newLeaderStore,
             new KVCommand.Put<>(AppBlueprintKey.appBlueprintKey(expanded.id()),
                                 AppBlueprintValue.appBlueprintValue(expanded)),
             new KVCommand.Put<>(DeploymentOutcomeKey.deploymentOutcomeKey(expanded.id()),
                                 DeploymentOutcomeValue.inProgress(1L)));

        assertThat(activeState(newLeaderHarness).inFlightBlueprints())
                .as("precondition: a new leader has NO in-memory record of the apply, which is the "
                    + "whole reason the durable marker is read instead")
                .doesNotContainKey(expanded.id());

        exhaustRetryBudgetOn(newLeaderHarness, SELF, SLICE);

        assertThat(activeState(newLeaderHarness).permanentlyFailed())
                .as("the durable marker survives failover and still says the apply never finished")
                .contains(SLICE);
    }

    /// A `registerOnly` blueprint is stored but deliberately never deployed, so it has no outcome
    /// record and never will. Read without the `registerOnly` check it looks permanently mid-apply,
    /// which would condemn any slice it declares on the first exhaustion.
    @Test
    void registerOnlyBlueprint_isNeverOutstanding() {
        var expanded = blueprint();
        var newLeaderStore = freshStore();
        var newLeaderHarness = leaderHarness(new RecordingClusterNode(SELF, newLeaderStore),
                                             newLeaderStore,
                                             RESOLVED_MEMBERSHIP);

        seed(newLeaderStore,
             new KVCommand.Put<>(AppBlueprintKey.appBlueprintKey(expanded.id()),
                                 AppBlueprintValue.appBlueprintValue(expanded, true)));

        exhaustRetryBudgetOn(newLeaderHarness, SELF, SLICE);

        assertThat(activeState(newLeaderHarness).permanentlyFailed())
                .as("a register-only blueprint is not an apply in progress; it must never be able to "
                    + "condemn a slice it merely declares")
                .doesNotContain(SLICE);
    }

    /// Attribution matches the FULL artifact including version. A running v1 whose blueprint
    /// succeeded must not vouch for a v2 that never came up — that is #922's own case (a coordinate
    /// that does not resolve) reopened through the guard meant to bound it.
    @Test
    void aNewVersionUnderItsOwnBlueprint_settles_whileTheOlderVersionSucceeded() {
        var v1 = blueprint();
        var v2 = versionTwoBlueprint();
        var newLeaderStore = freshStore();
        var newLeaderHarness = leaderHarness(new RecordingClusterNode(SELF, newLeaderStore),
                                             newLeaderStore,
                                             RESOLVED_MEMBERSHIP);

        seed(newLeaderStore,
             new KVCommand.Put<>(AppBlueprintKey.appBlueprintKey(v1.id()), AppBlueprintValue.appBlueprintValue(v1)),
             new KVCommand.Put<>(DeploymentOutcomeKey.deploymentOutcomeKey(v1.id()),
                                 DeploymentOutcomeValue.succeeded(1L)),
             new KVCommand.Put<>(AppBlueprintKey.appBlueprintKey(v2.id()), AppBlueprintValue.appBlueprintValue(v2)),
             new KVCommand.Put<>(DeploymentOutcomeKey.deploymentOutcomeKey(v2.id()),
                                 DeploymentOutcomeValue.inProgress(1L)));

        exhaustRetryBudgetOn(newLeaderHarness, SELF, SLICE_V2);

        assertThat(activeState(newLeaderHarness).permanentlyFailed())
                .as("v2's own apply is outstanding, so v2 settles")
                .contains(SLICE_V2);
        assertThat(activeState(newLeaderHarness).permanentlyFailed())
                .as("and v1, whose apply succeeded, is untouched by v2's verdict")
                .doesNotContain(SLICE);
    }

    /// No blueprint declares the artifact — a standalone deploy, or a blueprint already torn down.
    /// Attribution fails, and the failure must land on the REVERSIBLE side: decline to settle and
    /// re-drive, never condemn. This is the class-2 direction the round-4 report found inverted in
    /// the previous design, asserted rather than argued.
    @Test
    void anArtifactNoBlueprintDeclares_isNotCondemned() {
        var newLeaderStore = freshStore();
        var newLeaderHarness = leaderHarness(new RecordingClusterNode(SELF, newLeaderStore),
                                             newLeaderStore,
                                             RESOLVED_MEMBERSHIP);

        exhaustRetryBudgetOn(newLeaderHarness, SELF, SLICE);

        assertThat(activeState(newLeaderHarness).permanentlyFailed())
                .as("unattributable evidence must fail toward re-driving; condemning is the one-way door")
                .doesNotContain(SLICE);
    }

    /// The torn-read claim, asserted rather than assumed. `KVStore.process` applies a batch's
    /// commands one at a time under no cross-command lock, so a reader on another thread can observe
    /// the state between `Put(AppBlueprintKey)` and `Remove(DeploymentOutcomeKey)`. This is that
    /// intermediate state: the new apply's blueprint is visible while the PREVIOUS attempt's
    /// terminal record has not yet been removed.
    ///
    /// It must answer "not outstanding" and decline to settle. That is the reversible arm — the
    /// exhaustion re-drives and the next one re-decides against a settled store.
    @Test
    void aTornBatch_blueprintVisibleBeforeTheStaleOutcomeIsRemoved_doesNotCondemn() {
        var expanded = blueprint();
        var newLeaderStore = freshStore();
        var newLeaderHarness = leaderHarness(new RecordingClusterNode(SELF, newLeaderStore),
                                             newLeaderStore,
                                             RESOLVED_MEMBERSHIP);

        seed(newLeaderStore,
             new KVCommand.Put<>(AppBlueprintKey.appBlueprintKey(expanded.id()),
                                 AppBlueprintValue.appBlueprintValue(expanded)),
             new KVCommand.Put<>(DeploymentOutcomeKey.deploymentOutcomeKey(expanded.id()),
                                 DeploymentOutcomeValue.failed(List.of(SLICE.asString()), "previous attempt", 1L)));

        exhaustRetryBudgetOn(newLeaderHarness, SELF, SLICE);

        assertThat(activeState(newLeaderHarness).permanentlyFailed())
                .as("a half-applied batch must never be read as an outstanding apply")
                .doesNotContain(SLICE);
    }

    /// A blueprint of two slices where one comes up and the other never does. It never retires, so
    /// no outcome record is ever written and the apply stays outstanding — correctly, because under
    /// `ALL_OR_NOTHING` that blueprint never applied.
    ///
    /// **This deliberately REVERSES the round-3b parked assertion**
    /// (`oss/internal/park-924-round3b-2026-09-08.patch`, `aSliceThatCameUpUnderAPartiallyDeployedBlueprint_isNotCondemned`),
    /// which treated the slice that came up as a workload owed convergence. It is not one: the
    /// operator was promised all-or-nothing and got neither, so the honest terminal is a rollback
    /// with a record, not indefinite re-driving of half a deployment. Round 4 classified this as
    /// class-2 harm because under the old design it produced a SILENT condemnation with no record;
    /// the objection was to the silence, and the record is what removes it.
    @Test
    void partiallyAppliedBlueprint_condemnsTheSliceThatCameUp_andRecordsIt() {
        var expanded = twoSliceBlueprint();

        applyBlueprint(leaderHarness, leaderStore, expanded);
        leaderHarness.dispatch(new NodeArtifactPutReceived(replayOn(NODE_A, SLICE, activeInstance())));
        leaderHarness.dispatch(new NodeArtifactPutReceived(replayOn(SELF, SLICE, activeInstance())));

        assertThat(outcomeStatusName(leaderStore, expanded.id()))
                .as("precondition: SLICE_B never came up, so the apply never completed and its record "
                    + "still reads IN_PROGRESS — genuinely outstanding, positively so")
                .isEqualTo(DeploymentOutcomeStatus.IN_PROGRESS.name());

        exhaustRetryBudgetOn(leaderHarness, SELF, SLICE);

        assertThat(activeState(leaderHarness).permanentlyFailed())
                .as("an ALL_OR_NOTHING blueprint that never applied reaches a terminal rather than "
                    + "re-driving half of itself forever")
                .contains(SLICE);
        assertThat(outcomeStatusName(leaderStore, expanded.id()))
                .as("#922 acceptance: the operator must be able to see from the blueprint status that "
                    + "the deployment did not apply — silence is the defect, not the condemnation")
                .isNotEqualTo(NO_OUTCOME);
    }

    /// Multiple declaration, which `hasConflictingOwnership` permits: it rejects a blueprint whose
    /// artifact is owned by one with a DIFFERENT base, but two blueprints sharing a base and
    /// differing only in version are the upgrade path, and a slice unchanged across the upgrade is
    /// declared by both.
    ///
    /// The older blueprint has SUCCEEDED and the newer one is mid-apply. The slice is a workload
    /// that IS up, so it must not be condemned — the succeeded blueprint has to be able to veto.
    /// The first version of this code picked one declaring blueprint arbitrarily
    /// (`owners.getFirst()` over a `HashMap` scan), so this case condemned or did not depending on
    /// iteration order. Requiring EVERY declaring blueprint to be non-terminal removes both the
    /// nondeterminism and the one-way door.
    @Test
    void aSliceDeclaredByBothASucceededAndAnInFlightBlueprint_isNotCondemned() {
        var succeeded = blueprint();
        var upgrading = sameBaseUpgradeBlueprintAlsoDeclaring(SLICE);
        var newLeaderStore = freshStore();
        var newLeaderHarness = leaderHarness(new RecordingClusterNode(SELF, newLeaderStore),
                                             newLeaderStore,
                                             RESOLVED_MEMBERSHIP);

        seed(newLeaderStore,
             new KVCommand.Put<>(AppBlueprintKey.appBlueprintKey(succeeded.id()),
                                 AppBlueprintValue.appBlueprintValue(succeeded)),
             new KVCommand.Put<>(DeploymentOutcomeKey.deploymentOutcomeKey(succeeded.id()),
                                 DeploymentOutcomeValue.succeeded(1L)),
             new KVCommand.Put<>(AppBlueprintKey.appBlueprintKey(upgrading.id()),
                                 AppBlueprintValue.appBlueprintValue(upgrading)));

        exhaustRetryBudgetOn(newLeaderHarness, SELF, SLICE);

        assertThat(activeState(newLeaderHarness).permanentlyFailed())
                .as("a blueprint that already applied must be able to veto the settle for a slice it "
                    + "declares, whatever a concurrently-applying blueprint says about the same slice")
                .doesNotContain(SLICE);
    }

    /// #963 (#924 round-5 BLOCKING), variant A — the acceptance test, now GREEN. Documents the open defect; it is not a
    /// passing pin and must not be read as one.
    ///
    /// **The apply genuinely COMPLETES and no record is ever written.** Nothing is seeded: the
    /// blueprint is applied under one leader, the leader changes, and the slices reach ACTIVE under
    /// the new one. [ClusterDeploymentContext#newActive] builds `inFlightBlueprints` EMPTY and only
    /// the live `handleAppBlueprintChange` path ever populates it, so `trackBlueprintSliceActive`
    /// iterates an empty map and `recordSucceededOutcome` is never reached — not late, never.
    ///
    /// The store is then byte-identical to a deployment that never started, which
    /// [Active#deploymentApplyOutstanding] reads as OUTSTANDING and condemns. A fully-ACTIVE
    /// workload is marked permanently failed cluster-wide and its blueprint rolled back.
    ///
    /// In THIS variant one instance is still ACTIVE at decision time (`SELF` fails, `NODE_A` does
    /// not), so a present-tense health veto would rescue it. Variant B is the same defect where such
    /// a veto cannot vote.
    @Test
    void anApplyCompletedUnderANewLeader_writesNoRecord_andMustNotCondemnAHealthyWorkload() {
        var expanded = blueprint();

        applyBlueprint(leaderHarness, leaderStore, expanded);

        // FAILOVER onto the same durable store. Every in-memory collection starts empty.
        var newLeaderHarness = leaderHarness(new RecordingClusterNode(SELF, leaderStore),
                                             leaderStore,
                                             RESOLVED_MEMBERSHIP);

        assertThat(activeState(newLeaderHarness).inFlightBlueprints())
                .as("precondition: the new leader has no in-memory record of the apply, which is why "
                    + "the completion write below never happens")
                .doesNotContainKey(expanded.id());

        newLeaderHarness.dispatch(new NodeArtifactPutReceived(replayOn(NODE_A, SLICE, activeInstance())));
        newLeaderHarness.dispatch(new NodeArtifactPutReceived(replayOn(SELF, SLICE, activeInstance())));

        assertThat(outcomeStatusName(leaderStore, expanded.id()))
                .as("#963 THE REPAIR: before the fix this read NO-OUTCOME-RECORD and none would ever be "
                    + "written — `trackBlueprintSliceActive` iterates an `inFlightBlueprints` this leader "
                    + "built empty, so `recordSucceededOutcome` was unreachable. "
                    + "`recordApplyCompletionFromDurableState` now writes it off the blueprint's own "
                    + "loadOrder and durable slice states, so the completed apply is recorded on ANY leader")
                .isEqualTo(DeploymentOutcomeStatus.SUCCEEDED.name());

        exhaustRetryBudgetOn(newLeaderHarness, SELF, SLICE);

        assertThat(activeState(newLeaderHarness).permanentlyFailed())
                .as("a workload whose apply completed must never be condemned because the completion "
                    + "record was never written — absence of the record is not evidence of failure")
                .doesNotContain(SLICE);
    }

    /// #963 (#924 round-5 BLOCKING), variant B — the acceptance test, now GREEN, and the one that discriminates.
    ///
    /// Identical to variant A except that the transient reaches EVERY instance before the budget is
    /// spent, which is what a shared downstream dependency does by construction.
    /// [Active#handleSliceFailure] removes each failing key from `sliceStates` before either branch
    /// runs, so at decision time NO instance is ACTIVE anywhere.
    ///
    /// This is why a present-tense health veto narrows the defect without closing it: it is round-2's
    /// own BLOCKING shape, and it cannot vote here. Any fix whose safety rests on an instance being
    /// ACTIVE at decision time leaves this case condemning.
    @Test
    void anApplyCompletedUnderANewLeader_withASharedTransientOnEveryInstance_mustNotCondemn() {
        var expanded = blueprint();

        applyBlueprint(leaderHarness, leaderStore, expanded);

        var newLeaderHarness = leaderHarness(new RecordingClusterNode(SELF, leaderStore),
                                             leaderStore,
                                             RESOLVED_MEMBERSHIP);

        newLeaderHarness.dispatch(new NodeArtifactPutReceived(replayOn(NODE_A, SLICE, activeInstance())));
        newLeaderHarness.dispatch(new NodeArtifactPutReceived(replayOn(SELF, SLICE, activeInstance())));

        assertThat(outcomeStatusName(leaderStore, expanded.id()))
                .as("precondition: the repair recorded the completed apply, so the shared transient below "
                    + "is judged against a workload known to have come up")
                .isEqualTo(DeploymentOutcomeStatus.SUCCEEDED.name());

        // The shared transient takes the other instance down too, so nothing is ACTIVE to vouch.
        newLeaderHarness.dispatch(new NodeArtifactPutReceived(replayOn(NODE_A, SLICE, intermittentFailure())));
        exhaustRetryBudgetOn(newLeaderHarness, SELF, SLICE);

        assertThat(activeState(newLeaderHarness).permanentlyFailed())
                .as("a previously-healthy workload must not be condemned merely because a shared "
                    + "transient reached every instance while its apply had no completion record")
                .doesNotContain(SLICE);
    }

    /// #963 — THE ABSENCE PIN, and the one the two repair tests above no longer make.
    ///
    /// Once the repair works, those two pass because the record is PRESENT and says SUCCEEDED. That
    /// leaves the original defect class untested: a blueprint whose apply reached no terminal write
    /// at all. [Active#handleSucceededOutcomeWriteFailure] states such a write "will NOT be retried",
    /// so this state is durable and reachable independently of any leader transition.
    ///
    /// Seeded as the store would actually look: the blueprint present, no outcome record of any kind.
    /// Under the old absence-gating that read as OUTSTANDING and condemned. Under #963 an absent
    /// record is not evidence of anything and the settle declines.
    ///
    /// This is the assertion that would go red if anyone re-introduced `isEmpty()` as the
    /// outstandingness test.
    @Test
    void aBlueprintWhoseOutcomeRecordWasLostEntirely_isNotCondemned() {
        var expanded = blueprint();
        var newLeaderStore = freshStore();
        var newLeaderHarness = leaderHarness(new RecordingClusterNode(SELF, newLeaderStore),
                                             newLeaderStore,
                                             RESOLVED_MEMBERSHIP);

        seed(newLeaderStore,
             new KVCommand.Put<>(AppBlueprintKey.appBlueprintKey(expanded.id()),
                                 AppBlueprintValue.appBlueprintValue(expanded)));

        assertThat(outcomeStatusName(newLeaderStore, expanded.id()))
                .as("precondition: no outcome record of any kind — the state a permanently-lost "
                    + "SUCCEEDED write leaves behind")
                .isEqualTo(NO_OUTCOME);

        exhaustRetryBudgetOn(newLeaderHarness, SELF, SLICE);

        assertThat(activeState(newLeaderHarness).permanentlyFailed())
                .as("an absent record is indistinguishable from one that was never written, so it must "
                    + "never authorise an irreversible verdict — five rounds of #924 each condemned on "
                    + "exactly this absence")
                .doesNotContain(SLICE);
    }

    private static ClusterDeploymentState.Active activeState(FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> harness) {
        return (ClusterDeploymentState.Active) harness.state();
    }

    /// Returns the outcome status NAME, or the sentinel [#NO_OUTCOME] when no record exists.
    ///
    /// A String rather than an `Option`, because "no record" and "SUCCEEDED" are the two states the
    /// apply marker distinguishes and they must be comparable as VALUES — an assertion that only
    /// checks presence cannot tell a stale terminal from a fresh one.
    private static String outcomeStatusName(KVStore<AetherKey, AetherValue> store, BlueprintId blueprintId) {
        return store.get(DeploymentOutcomeKey.deploymentOutcomeKey(blueprintId))
                    .filter(value -> value instanceof DeploymentOutcomeValue)
                    .map(value -> ((DeploymentOutcomeValue) value).status())
                    .map(DeploymentOutcomeStatus::name)
                    .or(NO_OUTCOME);
    }

    /// Mirrors what a blueprint publish does in production: `BlueprintService` writes
    /// `AppBlueprintKey` through consensus, and the FSM sees the resulting notification. Dispatching
    /// only the notification would leave the store empty and make every apply look un-attributable.
    private static void applyBlueprint(FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> harness,
                                       KVStore<AetherKey, AetherValue> store,
                                       ExpandedBlueprint expanded) {
        seed(store,
             new KVCommand.Put<>(AppBlueprintKey.appBlueprintKey(expanded.id()),
                                 AppBlueprintValue.appBlueprintValue(expanded)),
             // #963: production writes IN_PROGRESS in the SAME batch as the blueprint Put
             // (`BlueprintService.buildAllCommands` / `storeBlueprintWithKey`). A fixture that seeded
             // only the blueprint would model a state production never produces, and — since the
             // settle is now gated on the PRESENCE of that record — would make every "does settle"
             // test silently unreachable.
             new KVCommand.Put<>(DeploymentOutcomeKey.deploymentOutcomeKey(expanded.id()),
                                 DeploymentOutcomeValue.inProgress(1L)));
        harness.dispatch(new AppBlueprintPutReceived(appBlueprintPut(expanded)));
    }

    private static void exhaustRetryBudgetOn(FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> harness,
                                             NodeId node,
                                             Artifact artifact) {
        for (var report = 1; report <= TERMINAL_ON_REPORT; report++) {
            harness.dispatch(new NodeArtifactPutReceived(replayOn(node, artifact, intermittentFailure())));
        }
    }

    private static KVStore<AetherKey, AetherValue> freshStore() {
        return new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
    }

    @SafeVarargs
    private static void seed(KVStore<AetherKey, AetherValue> store, KVCommand<AetherKey>... commands) {
        var batch = List.of(commands);

        store.process(store.createBatch(batch));
    }

    private static ExpandedBlueprint blueprint() {
        var id = BlueprintId.blueprintId("com.example:app:1.0.0").unwrap();
        var slice = ResolvedSlice.resolvedSlice(SLICE, 3, false).unwrap();

        return ExpandedBlueprint.expandedBlueprint(id, List.of(slice));
    }

    private static ExpandedBlueprint singleInstanceBlueprint() {
        var id = BlueprintId.blueprintId("com.example:app:1.0.0").unwrap();
        var slice = ResolvedSlice.resolvedSlice(SLICE, 1, false).unwrap();

        return ExpandedBlueprint.expandedBlueprint(id, List.of(slice));
    }

    private static ExpandedBlueprint twoSliceBlueprint() {
        var id = BlueprintId.blueprintId("com.example:app:1.0.0").unwrap();
        var sliceA = ResolvedSlice.resolvedSlice(SLICE, 2, false).unwrap();
        var sliceB = ResolvedSlice.resolvedSlice(SLICE_B, 2, false).unwrap();

        return ExpandedBlueprint.expandedBlueprint(id, List.of(sliceA, sliceB));
    }

    /// A same-BASE, later-version blueprint that also declares `artifact` — what an upgrade looks
    /// like for a slice that did not change. `hasConflictingOwnership` permits this precisely
    /// because the bases match.
    private static ExpandedBlueprint sameBaseUpgradeBlueprintAlsoDeclaring(Artifact artifact) {
        var id = BlueprintId.blueprintId("com.example:app:1.1.0").unwrap();
        var slice = ResolvedSlice.resolvedSlice(artifact, 3, false).unwrap();

        return ExpandedBlueprint.expandedBlueprint(id, List.of(slice));
    }

    private static ExpandedBlueprint versionTwoBlueprint() {
        var id = BlueprintId.blueprintId("com.example:app:2.0.0").unwrap();
        var slice = ResolvedSlice.resolvedSlice(SLICE_V2, 3, false).unwrap();

        return ExpandedBlueprint.expandedBlueprint(id, List.of(slice));
    }

    private static NodeArtifactValue activeInstance() {
        return NodeArtifactValue.activeNodeArtifactValue(0, List.of());
    }

    private static NodeArtifactValue intermittentFailure() {
        return NodeArtifactValue.failedNodeArtifactValue(new CoreError.Timeout("downstream dependency restarting"),
                                                         Unrecognised.RETRY);
    }

    private static ValuePut<NodeArtifactKey, NodeArtifactValue> replayOn(NodeId node, Artifact artifact, NodeArtifactValue value) {
        var key = NodeArtifactKey.nodeArtifactKey(node, artifact);

        return new ValuePut<>(new KVCommand.Put<>(key, value), Option.none());
    }

    private static ValuePut<AppBlueprintKey, AppBlueprintValue> appBlueprintPut(ExpandedBlueprint expanded) {
        var key = AppBlueprintKey.appBlueprintKey(expanded.id());

        return new ValuePut<>(new KVCommand.Put<>(key, AppBlueprintValue.appBlueprintValue(expanded)), Option.none());
    }

    private static FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> leaderHarness(ClusterNode<KVCommand<AetherKey>> cluster,
                                                                                        KVStore<AetherKey, AetherValue> kvStore,
                                                                                        Supplier<Set<NodeId>> coreMembers) {
        var router = MessageRouter.mutable();
        LongSupplier clock = () -> 10_000_000L;
        Function<Fsm<ClusterDeploymentState, ClusterFsmEvent>, ClusterDeploymentState> factory =
                fsm -> new ClusterDeploymentContext(fsm,
                                                    SELF,
                                                    cluster,
                                                    kvStore,
                                                    router,
                                                    stubTopologyManager(SELF),
                                                    stubSchemaOrchestrator(),
                                                    coreMembers,
                                                    () -> Set.of(SELF, NODE_A),
                                                    Set::of,
                                                    Set.of(SELF, NODE_A),
                                                    DeploymentAtomicity.ALL_OR_NOTHING,
                                                    3,
                                                    timeSpan(300).seconds(),
                                                    clock).dormant();
        var harness = FsmTestHarness.<ClusterDeploymentState, ClusterFsmEvent>harness("apply-outstanding-922-" + SELF.id(), factory);

        harness.dispatch(new Activate());

        return harness;
    }

    /// Records what the leader submits AND applies it, because a real `ClusterNode.apply` reaches
    /// consensus and lands in every replica's local `KVStore` — including the leader's own. A
    /// recorder that only records leaves the store permanently empty, which is exactly the state
    /// [Active#deploymentApplyOutstanding] reads through: a fixture that cannot exercise the durable
    /// marker cannot validate it either. Adopted from the parked round-3b work, where the same gap
    /// was found against the previous design's durable leg.
    private static final class RecordingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final NodeId self;
        private final KVStore<AetherKey, AetherValue> kvStore;
        private final List<KVCommand<AetherKey>> commands = Collections.synchronizedList(new ArrayList<>());

        private RecordingClusterNode(NodeId self, KVStore<AetherKey, AetherValue> kvStore) {
            this.self = self;
            this.kvStore = kvStore;
        }

        @Override public NodeId self() {return self;}

        @Override public TopologyManager topologyManager() {return stubTopologyManager(self);}

        @Override public Promise<Unit> start() {return Promise.unitPromise();}

        @Override public Promise<Unit> stop() {return Promise.unitPromise();}

        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> batch) {
            commands.addAll(batch);
            kvStore.process(kvStore.createBatch(batch));

            return Promise.success(Collections.emptyList());
        }
    }

    private static SchemaOrchestratorService stubSchemaOrchestrator() {
        return new SchemaOrchestratorService() {
            @Override public Promise<Unit> migrateIfNeeded(String datasourceName) {return Promise.success(Unit.unit());}

            @Override public Promise<Unit> undoTo(String datasourceName, int targetVersion) {return Promise.success(Unit.unit());}

            @Override public Promise<Unit> baseline(String datasourceName, int version) {return Promise.success(Unit.unit());}
        };
    }

    private static TopologyManager stubTopologyManager(NodeId self) {
        return new TopologyManager() {
            @Override public NodeInfo self() {return NodeInfo.nodeInfo(self, new NodeAddress("localhost", 9000));}

            @Override public Option<NodeInfo> get(NodeId id) {return Option.some(NodeInfo.nodeInfo(id, new NodeAddress("localhost", 9000)));}

            @Override public int clusterSize() {return 2;}

            @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {return Option.empty();}

            @Override public Promise<Unit> start() {return Promise.unitPromise();}

            @Override public Promise<Unit> stop() {return Promise.unitPromise();}

            @Override public TimeSpan pingInterval() {return timeSpan(5).seconds();}

            @Override public TimeSpan helloTimeout() {return timeSpan(5).seconds();}

            @Override public Option<NodeState> getState(NodeId id) {return Option.empty();}

            @Override public List<NodeId> topology() {return List.of(self);}
        };
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override public <T> T read(ByteBuf byteBuf) {return null;}
        };
    }
}
