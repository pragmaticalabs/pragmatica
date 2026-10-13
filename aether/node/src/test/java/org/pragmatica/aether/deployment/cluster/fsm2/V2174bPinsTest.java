// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput.Settlement;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.PendingGoal;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ArtifactJudgmentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactJudgmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.UnresponsiveEntry;
import org.pragmatica.consensus.NodeId;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

/// The pins of the v-2174b round (PR #2174): the nine delta mutants that survived (D3), the `join-unmatched` cadence (J9 ruling) and the
/// ownership of the scan meter. Each test names the mutant it turns red.
class V2174bPinsTest {
    private static final long SCAN_THRESHOLD_PLUS = PMap.SCAN_THRESHOLD + 6L;

    // -- F19 (a): a committed key answers only the in-flight goal it describes ----------------------------------------------------
    /// F19a1. Red when: `Indexer.indexDesired` clears the in-flight goal of another generation (`clearPending` for `clearMatchingPending`):
    /// the goal issued later stops counting as placed, although only the older key was committed.
    @Test
    void desiredPut_leavesTheInFlightGoalOfAnotherGeneration() {
        var h = LcSupport.leader(DeploymentAtomicity.BEST_EFFORT, 0.5d, CORE_1);
        var pair = SlicePair.slicePair(CORE_1, ORDERS);

        h.activate();
        Indexer.putPending(h.model(), pair, new PendingGoal(SliceGoal.LOADED, gen(5)));
        h.deliver(new ClusterInput.KvPut(new SliceDesiredKey(CORE_1, ORDERS), desired(SliceGoal.LOADED, gen(4))));
        assertThat(h.model().pendingGoals.containsKey(pair)).as("generation 4 committed: generation 5 stays in flight").isTrue();
        h.deliver(new ClusterInput.KvPut(new SliceDesiredKey(CORE_1, ORDERS), desired(SliceGoal.LOADED, gen(5))));
        assertThat(h.model().pendingGoals.containsKey(pair)).as("control: generation 5 committed answers it").isFalse();
    }

    /// F19a2. Red when: `Indexer.removeDesired` clears an in-flight placement (`clearPending` for `clearRemovedPending`): a removal answers
    /// an in-flight UNLOADED goal only.
    @Test
    void desiredRemove_leavesAnInFlightPlacement() {
        var h = LcSupport.leader(DeploymentAtomicity.BEST_EFFORT, 0.5d, CORE_1);
        var pair = SlicePair.slicePair(CORE_1, ORDERS);

        h.activate();
        Indexer.putPending(h.model(), pair, new PendingGoal(SliceGoal.LOADED, gen(5)));
        h.deliver(new ClusterInput.KvRemove(new SliceDesiredKey(CORE_1, ORDERS)));
        assertThat(h.model().pendingGoals.containsKey(pair)).as("a removal does not answer a placement").isTrue();
        Indexer.putPending(h.model(), pair, new PendingGoal(SliceGoal.UNLOADED, gen(6)));
        h.deliver(new ClusterInput.KvRemove(new SliceDesiredKey(CORE_1, ORDERS)));
        assertThat(h.model().pendingGoals.containsKey(pair)).as("control: it answers an unload").isFalse();
    }

    // -- F10 (h, l2): a directive in flight --------------------------------------------------------------------------------------
    private static MbWorld oneWorker(NodeId worker) {
        return MbWorld.world(CORE_1, CORE_2, CORE_3)
                      .seams(seams -> seams.withCommunityPolicies(Map.of())
                                           .withMemberSource(Map.of(worker, "east")))
                      .activate();
    }

    /// F10h. Red when: a re-evaluation of a join whose directive write is unsettled (any reconcile runs one) stops tracking the node
    /// (`Joins.directive` returns the model for `track`): when that write then fails, nothing derives the directive again.
    @Test
    void directiveInFlight_isStillTracked_afterAReevaluation_soAFailedWriteIsDerivedAgain() {
        var worker = node("worker-1");
        var w = oneWorker(worker);

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.deliver(new ClusterInput.WorkerJoin(worker, w.admit(worker, 1L, 21L)));
        var first = w.h.submits(SubmitKind.DIRECTIVE).getFirst();

        w.h.deliver(new ClusterInput.Reconcile());
        assertThat(w.h.model().pendingJoins.containsKey(worker)).as("tracked while the write is unsettled").isTrue();
        w.h.held.clear();
        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        w.h.settleHeld(first, new Settlement.Failed("injected"));
        w.h.advance(120_000L);
        assertThat(w.present(new ActivationDirectiveKey(worker))).as("the failed write is derived again").isTrue();
    }

    /// F10l2. Red when: `Directory.directiveInFlight` looks at the outbox only (not at `submitted`): a re-evaluation while the first write is
    /// unsettled writes a second DIRECTIVE expecting the same absent key, which costs a refusal round.
    @Test
    void directiveInFlight_seesTheSubmittedWrite_soAReevaluationWritesNoSecond() {
        var worker = node("worker-1");
        var w = oneWorker(worker);

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.deliver(new ClusterInput.WorkerJoin(worker, w.admit(worker, 1L, 21L)));
        assertThat(w.h.submits(SubmitKind.DIRECTIVE)).as("precondition: one write, submitted").hasSize(1);
        w.h.deliver(new ClusterInput.Reconcile());
        w.h.deliver(new ClusterInput.Reconcile());
        assertThat(w.h.submits(SubmitKind.DIRECTIVE)).as("still one: the first is unsettled").hasSize(1);
    }

    // -- F5b, F10k, R4f: a refused transaction that carries shared mutations / events ----------------------------------------------
    private static boolean excluded(FsmHarness h, NodeId node) {
        return h.store.get(new ArtifactJudgmentKey(ORDERS))
                      .map(value -> ((ArtifactJudgmentValue) value).unresponsive()
                                                                   .containsKey(node))
                      .or(false);
    }

    private static final Generation G2 = new Generation(1L, 2L);
    private static final Generation G3 = new Generation(1L, 3L);

    private static Map<NodeId, UnresponsiveEntry> excludedCore2() {
        return Map.of(CORE_2, new UnresponsiveEntry(SlFixtures.G1, SliceGoal.LOADED, FsmHarness.START_WALL));
    }

    /// One FAILURE transaction, held: CORE_3's failed pair (its UNLOADED goal) and the judgment clear of CORE_2, which answered UNLOADED
    /// (an event-only operation: no reconcile step derives it again).
    private static FsmHarness heldFailureWithSharedClear() {
        var h = LcSupport.leader(DeploymentAtomicity.ALL_OR_NOTHING, 0.5d, CORE_1, CORE_2, CORE_3);

        SlFixtures.seedTarget(h, ORDERS, 1, 1);
        SlFixtures.seedJudgment(h, ORDERS, new ArtifactJudgmentValue(false, Map.of(), excludedCore2()));
        SlFixtures.seedGoal(h, CORE_2, ORDERS, SliceGoal.UNLOADED, G2);
        SlFixtures.seedRow(h, CORE_2, ORDERS, SliceObservedState.UNLOADING, G2, 12L);
        SlFixtures.seedGoal(h, CORE_3, ORDERS, SliceGoal.LOADED, G3);
        SlFixtures.seedRow(h, CORE_3, ORDERS, SliceObservedState.LOADING, G3, 13L);
        h.activate();
        h.policy = FsmHarness.SettlePolicy.HOLD;
        h.clearLog();
        new NodeSim(h, CORE_2, process(12L)).writes(ORDERS, SliceObservedState.UNLOADED, G2);
        new NodeSim(h, CORE_3, process(13L)).fails(ORDERS, false);
        h.drain();

        return h;
    }

    /// Refuses the latest FAILURE submission, with nothing moved, until a refusal is not answered by a new submission.
    private static FsmHarness refusedToTheBound() {
        var h = heldFailureWithSharedClear();
        var current = h.submits(SubmitKind.FAILURE).getLast();

        for (var i = 0; i < 12; i++) {
            var before = h.submits(SubmitKind.FAILURE).size();

            h.held.clear();
            h.settleHeld(current, new Settlement.Refused(h.committedLeader(), Map.of()));
            if (h.submits(SubmitKind.FAILURE).size() == before) {
                break;
            }

            current = h.submits(SubmitKind.FAILURE).getLast();
        }

        return h;
    }

    /// F5b. Red when: `Outbox.resubmitSharedOnly` resubmits at the bound (`>` for `>=`): a refusal at the last allowed attempt, which
    /// releases every pair, is answered by one more submission instead of parking the clear for the next reconcile.
    @Test
    void sharedOnlyResubmission_stopsAtTheGoalBound() {
        var h = refusedToTheBound();

        assertThat(h.submits(SubmitKind.FAILURE)).as("the first submission and GOAL_RESUBMISSIONS resubmissions")
                  .hasSize(1 + Outbox.GOAL_RESUBMISSIONS);
    }

    /// Red when: the event-only operations of a resubmitted remainder are not parked at the bound (`Outbox.awaitEvents` named the artifact by
    /// the draft's key only, and a remainder's key is its first pair's desired key): the clear of CORE_2's exclusion is lost.
    @Test
    void clearOfAResubmittedRemainder_survivesTheBound() {
        var h = refusedToTheBound();

        h.held.clear();
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.advance(120_000L);
        assertThat(excluded(h, CORE_2)).as("the parked clear is sent again by the next reconcile").isFalse();
    }

    /// F10k. Red when: a refusal in which a shared mutation moved (`Outbox.rederiveSplit`) does not park the transaction's event-only
    /// operations (`awaitEvents`): the clear of CORE_2's exclusion is lost, and the node stays excluded until the 10T expiry.
    @Test
    void refusalInWhichTheJudgmentMoved_keepsTheEventOnlyClear() {
        var h = heldFailureWithSharedClear();
        var failure = h.submits(SubmitKind.FAILURE).getLast();

        new NodeSim(h, CORE_3, process(13L)).writes(ORDERS, SliceObservedState.UNLOADING, G3);
        h.seed(new ArtifactJudgmentKey(ORDERS), new ArtifactJudgmentValue(false, Map.of(CORE_1, 1), excludedCore2()));
        h.drain();
        var verdict = h.applyHeld(failure.correlationId());

        assertThat(verdict).as("precondition: the pair and the judgment moved").isInstanceOf(Settlement.Refused.class);
        h.held.clear();
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.settleHeld(failure, verdict);
        h.advance(120_000L);
        assertThat(excluded(h, CORE_2)).as("CORE_2 answered UNLOADED: its exclusion is cleared, not left to the 10T expiry").isFalse();
    }

    private static long adjustmentsOf(FsmHarness h, org.pragmatica.aether.artifact.Artifact artifact) {
        return h.effects(ClusterEffect.Route.class)
                .stream()
                .filter(route -> route.event() instanceof RoutedEvent.ReconciliationAdjustment adjustment && adjustment.artifact().equals(artifact))
                .count();
    }

    /// R4f. Red when: `Outbox.stillStarted` keeps only `DeploymentStarted` events: the `ReconciliationAdjustment` of an artifact whose first goal
    /// stays in a transaction that is refused because another pair moved is dropped when the rest is resubmitted.
    @Test
    void adjustmentRidingARefusedAndSplitTransaction_isAnnouncedWhenTheRestLands() {
        var h = LcSupport.leader(DeploymentAtomicity.ALL_OR_NOTHING, 0.5d, CORE_1);

        SlFixtures.seedTarget(h, ORDERS, 1, 1);
        SlFixtures.seedTarget(h, BILLING, 1, 1);
        h.policy = FsmHarness.SettlePolicy.HOLD;
        h.activate();
        var goals = h.submits(SubmitKind.GOALS).getFirst();

        assertThat(goals.transaction().mutations()).as("precondition: both goals of the node travel together").hasSize(2);
        h.seed(new SliceDesiredKey(CORE_1, BILLING), desired(SliceGoal.LOADED, G3));
        h.held.clear();
        h.settleHeld(goals, h.refused(goals));
        assertThat(h.submits(SubmitKind.GOALS)).as("precondition: the unmoved goal is resubmitted").hasSizeGreaterThan(1);
        assertThat(adjustmentsOf(h, ORDERS)).as("nothing is announced while the rest is in flight").isZero();
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.submits(SubmitKind.GOALS).stream().skip(1).forEach(submit -> h.settleHeld(submit, h.applyTransaction(submit)));
        assertThat(adjustmentsOf(h, ORDERS)).as("the adjustment survives the split and is announced once").isEqualTo(1L);
    }

    // -- F6b, X1 and the ownership of the meter -----------------------------------------------------------------------------------
    private static long scanMetrics(java.util.List<ClusterEffect> effects) {
        return effects.stream()
                      .filter(ClusterEffect.Metric.class::isInstance)
                      .map(ClusterEffect.Metric.class::cast)
                      .filter(metric -> metric.name().equals("aether_deployment_fsm_scans_total"))
                      .count();
    }

    /// F6b. Red when: `reportScans` reports a scan count of zero: a metric effect on every step, although an ordinary input reports none
    /// (NT-78). Pinned by absence, not by value.
    @Test
    void ordinaryInput_reportsNoScanMetric_notEvenAZeroOne() {
        var h = LcSupport.leader(DeploymentAtomicity.BEST_EFFORT, 0.5d, CORE_1);

        h.activate();
        h.clearLog();
        h.deliver(new ClusterInput.KvPut(new SliceTargetKey(ORDERS.base()),
                                         org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue.sliceTargetValue(ORDERS.version(),
                                                                                                                             1,
                                                                                                                             1)));
        assertThat(h.steps).as("precondition: the input was handled").isNotEmpty();
        assertThat(h.steps.stream().mapToLong(V2174bPinsTest::scanMetrics).sum()).as("no scan metric effect at all").isZero();
    }

    private static FsmHarness withManySchemas() {
        var h = LcSupport.leader();

        for (var i = 0; i < SCAN_THRESHOLD_PLUS; i++) {
            var name = "ds-" + i;

            h.seedQuietly(new SchemaVersionKey(name),
                          new SchemaVersionValue(name,
                                                 1,
                                                 "m1",
                                                 SchemaStatus.COMPLETED,
                                                 "g:a:1",
                                                 blueprintId("org.example:app:1.0.0"),
                                                 0,
                                                 FsmHarness.START_WALL));
        }

        return h;
    }

    /// X1. Red when: the Rebuild's own scans are not reported (`freshlyBuilt` reports zero): the rebuild of a store with more schema
    /// records than the scan threshold walks them (the recovery pass), and that step must say so.
    @Test
    void rebuild_reportsItsOwnScans_inTheStepThatBuiltTheModel() {
        var h = withManySchemas();

        h.activate();
        var rebuildStep = h.steps.stream()
                               .filter(effects -> effects.stream()
                                                         .anyMatch(effect -> effect instanceof ClusterEffect.Metric metric && "Rebuild".equals(metric.tags()
                                                                                                                                                       .get("input"))))
                               .findFirst();

        assertThat(rebuildStep).as("precondition: the Rebuild step is in the log").isPresent();
        assertThat(scanMetrics(rebuildStep.get())).as("the Rebuild step reports the scans it made").isPositive();
    }

    private static FsmHarness withManyPairs() {
        var h = LcSupport.leader();

        for (var i = 0; i < SCAN_THRESHOLD_PLUS * 4; i++) {
            var key = node("w-" + (i % 20));
            var artifact = artifact("org.example:a" + i + ":1.0.0");

            h.seedQuietly(new SliceDesiredKey(key, artifact), desired(SliceGoal.ACTIVE, gen(1)));
            h.seedQuietly(new SliceObservedKey(key, artifact), row(SliceObservedState.ACTIVE, 1L, gen(1), process(7L)));
        }

        return h;
    }

    /// Red when: `ClusterModel.copy` shares the scan meter (or the maps stay bound to the input model's): a step then counts on the meter
    /// the model it was given reads, so `step` mutates its input (the contract of `ClusterModel`).
    @Test
    void step_doesNotTouchTheScanMeterOfItsInputModel() {
        var h = withManyPairs();

        h.activate();
        var input = h.state;
        var inputMeter = h.model().scans.count();
        var stepped = h.fsm.step(input, new ClusterInput.Reconcile());

        assertThat(scanMetrics(stepped.effects())).as("control: a reconcile pass over many pairs scans").isPositive();
        assertThat(((ClusterState.Active) input).model().scans.count()).as("the input model's meter is where it was").isEqualTo(inputMeter);
    }

    /// Red when: an index bucket larger than the scan threshold keeps counting on the meter of the model it was built in
    /// (`Idx.members` without `rebound`): reading it in a step mutates the input model's meter and the step reports no scan.
    @Test
    void bucketRead_countsOnTheStepsMeter_notOnTheInputModels() {
        var h = LcSupport.leader();
        var busy = node("busy");

        for (var i = 0; i < SCAN_THRESHOLD_PLUS; i++) {
            var artifact = artifact("org.example:a" + i + ":1.0.0");

            h.seedQuietly(new SliceObservedKey(busy, artifact),
                          row(SliceObservedState.ACTIVE, 1L, gen(1), process(7L)));
        }

        h.activate();
        var input = h.state;
        var inputMeter = h.model().scans.count();
        var stepped = h.fsm.step(input, new ClusterInput.NodeJoined(busy, admission(1L, 7L)));

        assertThat(scanMetrics(stepped.effects())).as("the bucket walk is reported by the step that made it").isPositive();
        assertThat(((ClusterState.Active) input).model().scans.count()).as("the input model's meter is where it was").isEqualTo(inputMeter);
    }

    // -- J9: the join-unmatched WARN cadence (CTO ruling, session 35) ----------------------------------------------------------------
    private static MbWorld unmatchedWorld() {
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3);

        w.admit(CORE_1, 1L, 0x1111L);
        w.admit(CORE_2, 5L, 0x2222L);
        w.admit(CORE_3, 1L, 0x3333L);
        w.directive(CORE_2, ActivationDirectiveValue.core())
         .record(CORE_2, DepartureState.DEPARTED, 5L, 0x2222L, DepartureCause.NODE_REMOVED)
         .activate();

        return w;
    }

    /// Red when: the WARN is emitted by every reconcile pass (a KV drain queues one) instead of once per unmatched episode, or the 30 s
    /// tick does not re-emit it while the node is still unmatched.
    @Test
    void joinUnmatched_warnsOncePerEpisode_andAgainOnTheTick() {
        var w = unmatchedWorld();

        w.h.clearLog();
        w.h.deliver(new ClusterInput.NodeJoined(CORE_2, w.admissionOf(CORE_2, 5L)));
        assertThat(w.h.warns("join-unmatched")).as("the episode starts with one WARN").hasSize(1);
        var passes = w.h.metricTotal("aether_deployment_fsm_reconcile_passes_total", t -> true);

        for (var i = 1; i <= 5; i++) {
            w.h.seed(new SliceTargetKey(ORDERS.base()),
                     org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue.sliceTargetValue(ORDERS.version(), i, 1));
            w.h.drain();
        }

        assertThat(w.h.metricTotal("aether_deployment_fsm_reconcile_passes_total", t -> true) - passes).as("control: five drains evaluated the join again")
                  .isGreaterThanOrEqualTo(5L);
        assertThat(w.h.warns("join-unmatched")).as("five KV drains: still the one WARN").hasSize(1);
        w.h.advance(w.h.settings.timing().tick() + 1);
        assertThat(w.h.warns("join-unmatched")).as("the tick re-emits it once").hasSize(2);
    }

    /// Red when: a new join input for a node does not start a new episode: the condition it is in again is never reported.
    @Test
    void joinUnmatched_newJoinInput_startsANewEpisode() {
        var w = unmatchedWorld();

        w.h.deliver(new ClusterInput.NodeJoined(CORE_2, w.admissionOf(CORE_2, 5L)));
        assertThat(w.h.warns("join-unmatched")).hasSize(1);
        w.h.deliver(new ClusterInput.NodeJoined(CORE_2, w.admissionOf(CORE_2, 5L)));
        assertThat(w.h.warns("join-unmatched")).as("a new join input starts a new episode").hasSize(2);
    }

    // -- D2: ActivationBlocked withheld while suspended ------------------------------------------------------------------------------
    private static SchemaVersionValue schema(SchemaStatus status) {
        return new SchemaVersionValue("ds", 1, "m1", status, "g:a:1", blueprintId("org.example:app:1.0.0"), 2, FsmHarness.START_WALL);
    }

    private static long blockedRoutes(FsmHarness h) {
        return h.effects(ClusterEffect.Route.class)
                .stream()
                .filter(route -> route.event() instanceof RoutedEvent.ActivationBlocked)
                .count();
    }

    private static FsmHarness suspendedWithAFailedRecord() {
        var h = LcSupport.leader(DeploymentAtomicity.ALL_OR_NOTHING, 0.5d, CORE_1);

        h.activate();
        h.deliver(new ClusterInput.WitnessStale());
        h.seed(new SchemaVersionKey("ds"), schema(SchemaStatus.FAILED));
        h.drain();

        return h;
    }

    /// Red when: the resume announces a record that is no longer FAILED (`Schemas.announceWithheld` does not read the record again).
    @Test
    void activationBlocked_withheldThenCompletedWhileSuspended_isNotAnnouncedOnTheResume() {
        var h = suspendedWithAFailedRecord();

        h.seed(new SchemaVersionKey("ds"), schema(SchemaStatus.COMPLETED));
        h.drain();
        h.commitLeader(FsmHarness.SELF, 2L);
        h.drain();
        h.advance(30_000L);
        assertThat(blockedRoutes(h)).as("the hold it would report is gone").isZero();
    }

    /// Red when: the withheld set outlives the resume (`Schemas.announceWithheld` does not empty it): the next resume announces it again.
    @Test
    void activationBlocked_withheld_isAnnouncedOnceAcrossTwoResumes() {
        var h = suspendedWithAFailedRecord();

        h.commitLeader(FsmHarness.SELF, 2L);
        h.drain();
        assertThat(blockedRoutes(h)).as("announced on the first resume").isEqualTo(1L);
        h.deliver(new ClusterInput.WitnessStale());
        h.commitLeader(FsmHarness.SELF, 3L);
        h.drain();
        h.advance(30_000L);
        assertThat(blockedRoutes(h)).as("not again on the second").isEqualTo(1L);
    }
}
