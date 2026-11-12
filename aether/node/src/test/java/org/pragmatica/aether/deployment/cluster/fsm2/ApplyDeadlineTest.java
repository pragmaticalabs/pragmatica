// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentApplyKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentOutcomeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.FsmWarningKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.FsmWarningValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.SlFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// The apply deadline (R-4.1.7, #970, NT-157..NT-160): an apply that receives no slice report reaches a terminal within
/// `apply_deadline` of unpaused time. Every test drives the real path: a publish batch, the reconcile ticks, the applier. No node ever
/// reports, and the divergence timeouts are set far beyond the test horizon so that no other judgment ends the apply first.
class ApplyDeadlineTest {
    private static final String APP = "org.example:app:1.0.0";
    private static final BlueprintId ID = blueprintId(APP);
    private static final long TEN_HOURS_MS = 36_000_000L;

    private static FsmHarness harness(DeploymentAtomicity atomicity, UnaryOperator<Timing> timing) {
        var bounds = DivergenceBounds.divergenceBounds(TEN_HOURS_MS, TEN_HOURS_MS, TEN_HOURS_MS, TEN_HOURS_MS);
        var settings = Settings.settings(FsmHarness.SELF, atomicity, Set.of(), bounds())
                               .withDivergence(bounds)
                               .withTiming(timing.apply(Timing.DEFAULT));
        var h = new FsmHarness(settings);
        var cores = Set.of(CORE_1, CORE_2, CORE_3);

        h.commitLeader(FsmHarness.SELF, 1L);
        h.seams = Seams.UNWIRED.withCountedCores(cores).withReady(cores);
        h.activate();

        return h;
    }

    private static FsmHarness publishedApply(DeploymentAtomicity atomicity, UnaryOperator<Timing> timing) {
        var h = harness(atomicity, timing);

        publish(h, blueprint(APP, false, ATTEMPT, slice(ORDERS, 1, 1, Set.of())), Option.none());

        return h;
    }

    private static FsmHarness publishedApply() {
        return publishedApply(DeploymentAtomicity.ALL_OR_NOTHING, UnaryOperator.identity());
    }

    private static Option<DeploymentOutcomeValue> outcome(FsmHarness h) {
        return h.store.get(new DeploymentOutcomeKey(ID))
                 .map(value -> (DeploymentOutcomeValue) value);
    }

    private static Option<DeploymentOutcomeStatus> status(FsmHarness h) {
        return outcome(h).map(DeploymentOutcomeValue::status);
    }

    private static SchemaVersionValue schema(SchemaStatus status) {
        return schemaOwnedBy(status, ID);
    }

    private static SchemaVersionValue schemaOwnedBy(SchemaStatus status, BlueprintId owner) {
        return new SchemaVersionValue("ds", 1, "m1", status, "g:a:1", owner, 2, FsmHarness.START_WALL);
    }

    private static String lastWarning(FsmHarness h) {
        return h.warns("apply-deadline-exceeded").getLast().message();
    }

    /// The apply's slice is ACTIVE and the SUCCEEDED outcome is submitted but unsettled (held).
    private static FsmHarness succeededAndHeld(UnaryOperator<Timing> timing) {
        var h = publishedApply(DeploymentAtomicity.ALL_OR_NOTHING, timing);
        var host = hosts(h, ORDERS).getFirst();

        node(h, host, 1L).follows(ORDERS);
        h.drain();
        h.policy = FsmHarness.SettlePolicy.HOLD;
        node(h, host, 1L).follows(ORDERS);
        h.drain();
        assertThat(h.submits(ClusterModel.SubmitKind.SUCCEEDED)).as("the success is in flight").isNotEmpty();

        return h;
    }

    // -- NT-157 ----------------------------------------------------------------------------------------------------
    /// NT-157. Red when: an apply that receives no slice report is not rolled back at the deadline (the base never terminates it), or is
    /// rolled back before it, or the terminal lacks the cause, the slices that are not ACTIVE, the apply record's removal or the warning.
    @Test
    void allOrNothing_noSliceReport_rollsBackAtTheDeadline() {
        var h = publishedApply();

        h.advanceBySeconds(14 * 60);
        assertThat(status(h)).as("inside the deadline").isEqualTo(Option.some(DeploymentOutcomeStatus.IN_PROGRESS));
        assertThat(h.submits(ClusterModel.SubmitKind.ROLLBACK)).as("no rollback before the deadline").isEmpty();

        h.advanceBySeconds(2 * 60);
        assertThat(status(h)).as("past the deadline").isEqualTo(Option.some(DeploymentOutcomeStatus.ROLLED_BACK));
        assertThat(outcome(h).map(DeploymentOutcomeValue::cause)).isEqualTo(Option.some("apply-deadline-exceeded"));
        assertThat(outcome(h).map(DeploymentOutcomeValue::failingSlices)).as("the slices that are not ACTIVE")
                             .isEqualTo(Option.some(List.of(ORDERS.asString())));
        assertThat(h.store.get(new DeploymentApplyKey(ID)).isPresent()).as("the apply record goes with the rollback").isFalse();
        assertThat(h.store.get(new AppBlueprintKey(ID)).isPresent()).as("no previous: the blueprint is removed").isFalse();
        assertThat(h.warns("apply-deadline-exceeded")).as("one warning").hasSize(1);
    }

    /// NT-157. Red when: the warning is not committed in the open set, or the republish that replaces the terminal does not close it.
    @Test
    void deadlineWarning_isCommittedOpen_andClosedByARepublish() {
        var h = publishedApply();
        var row = FsmWarningKey.fsmWarningKey("apply-deadline-exceeded", APP);

        h.advanceBySeconds(16 * 60);
        assertThat(h.store.get(row).isPresent()).as("committed open").isTrue();
        assertThat(h.warns("apply-deadline-cleared")).isEmpty();

        h.seedRemove(new DeploymentOutcomeKey(ID));
        publish(h, blueprint(APP, false, "attempt-2", slice(ORDERS, 1, 1, Set.of())), Option.none());
        h.advanceBySeconds(60);
        assertThat(h.warns("apply-deadline-cleared")).as("the republish ends the episode").hasSize(1);
        assertThat(h.store.get(row).isPresent()).as("removed from the open set").isFalse();
    }

    // -- NT-159 ----------------------------------------------------------------------------------------------------
    /// NT-159. Red when: under BEST_EFFORT the deadline rolls back, or does not write FAILED with the slices not ACTIVE, or leaves the apply
    /// record.
    @Test
    void bestEffort_noSliceReport_failsWithoutRollback() {
        var h = publishedApply(DeploymentAtomicity.BEST_EFFORT, UnaryOperator.identity());

        h.advanceBySeconds(16 * 60);
        assertThat(status(h)).isEqualTo(Option.some(DeploymentOutcomeStatus.FAILED));
        assertThat(outcome(h).map(DeploymentOutcomeValue::cause)).isEqualTo(Option.some("apply-deadline-exceeded"));
        assertThat(outcome(h).map(DeploymentOutcomeValue::failingSlices)).isEqualTo(Option.some(List.of(ORDERS.asString())));
        assertThat(h.submits(ClusterModel.SubmitKind.ROLLBACK)).as("no rollback is possible").isEmpty();
        assertThat(h.store.get(new AppBlueprintKey(ID)).isPresent()).as("the blueprint stays").isTrue();
        assertThat(h.store.get(new DeploymentApplyKey(ID)).isPresent()).as("the apply record is removed").isFalse();
        assertThat(h.warns("apply-deadline-exceeded")).hasSize(1);
    }

    // -- NT-158 ----------------------------------------------------------------------------------------------------
    /// NT-158 first arm. Red when: an apply record this leader cannot read is terminated (absence of a readable record condemns the apply).
    /// Control: `allOrNothing_noSliceReport_rollsBackAtTheDeadline`, the readable record, terminates.
    @Test
    void undecodableApplyRecord_isNotTerminated() {
        var h = harness(DeploymentAtomicity.ALL_OR_NOTHING, UnaryOperator.identity());

        h.seed(new DeploymentOutcomeKey(ID), DeploymentOutcomeValue.inProgress(h.wallMs, 1L, ATTEMPT));
        h.seed(new DeploymentApplyKey(ID), DeploymentOutcomeValue.inProgress(h.wallMs, 1L, ATTEMPT));
        h.seed(new AppBlueprintKey(ID), blueprint(APP, false, ATTEMPT, slice(ORDERS, 1, 1, Set.of())));
        h.drain();
        h.advanceBySeconds(30 * 60);
        assertThat(status(h)).isEqualTo(Option.some(DeploymentOutcomeStatus.IN_PROGRESS));
        assertThat(h.submits(ClusterModel.SubmitKind.ROLLBACK)).isEmpty();
        assertThat(h.submits(ClusterModel.SubmitKind.OUTCOME)).isEmpty();
    }

    /// NT-158 second arm. Red when: an unreadable bound (zero or negative) terminates the apply.
    @Test
    void unreadableBound_isNotTerminated() {
        var h = publishedApply(DeploymentAtomicity.ALL_OR_NOTHING, timing -> timing.withApplyDeadline(-1L));

        h.advanceBySeconds(30 * 60);
        assertThat(status(h)).isEqualTo(Option.some(DeploymentOutcomeStatus.IN_PROGRESS));
        assertThat(h.submits(ClusterModel.SubmitKind.ROLLBACK)).isEmpty();
    }

    // -- NT-160 ----------------------------------------------------------------------------------------------------
    /// NT-160. Red when: an open schema gate (MIGRATING) does not pause the clock, or its release does not resume it.
    @Test
    void openSchemaGate_pausesTheClock_andItsReleaseResumesIt() {
        var h = publishedApply();

        h.seed(new SchemaVersionKey("ds"), schema(SchemaStatus.MIGRATING));
        h.advanceBySeconds(20 * 60);
        assertThat(status(h)).as("paused past the deadline").isEqualTo(Option.some(DeploymentOutcomeStatus.IN_PROGRESS));

        h.seed(new SchemaVersionKey("ds"), schema(SchemaStatus.COMPLETED));
        h.advanceBySeconds(14 * 60);
        assertThat(status(h)).as("the clock restarts from what was unpaused").isEqualTo(Option.some(DeploymentOutcomeStatus.IN_PROGRESS));
        h.advanceBySeconds(3 * 60);
        assertThat(status(h)).isEqualTo(Option.some(DeploymentOutcomeStatus.ROLLED_BACK));
    }

    /// NT-160 second arm. Red when: the pause is not capped by `apply_schema_pause_max` (a datasource held MIGRATING for ever then keeps the
    /// apply open for ever).
    @Test
    void schemaPause_isCapped_andTheClockResumesPastIt() {
        var h = publishedApply(DeploymentAtomicity.ALL_OR_NOTHING, timing -> timing.withApplySchemaPauseMax(30 * 60_000L));

        h.seed(new SchemaVersionKey("ds"), schema(SchemaStatus.MIGRATING));
        h.advanceBySeconds(40 * 60);
        assertThat(status(h)).as("30 min paused, 10 unpaused").isEqualTo(Option.some(DeploymentOutcomeStatus.IN_PROGRESS));
        h.advanceBySeconds(10 * 60);
        assertThat(status(h)).as("the pause is spent: 20 unpaused").isEqualTo(Option.some(DeploymentOutcomeStatus.ROLLED_BACK));
    }

    /// NT-160 third arm. Red when: a FAILED datasource pauses the clock.
    @Test
    void failedDatasource_doesNotPauseTheClock() {
        var h = publishedApply();

        h.seed(new SchemaVersionKey("ds"), schema(SchemaStatus.FAILED));
        h.advanceBySeconds(16 * 60);
        assertThat(status(h)).isEqualTo(Option.some(DeploymentOutcomeStatus.ROLLED_BACK));
    }

    // -- leader tenure ----------------------------------------------------------------------------------------------
    /// L-21 and the resume rule. Red when: the time a leader spent suspended counts toward the deadline, so that the first pass after the
    /// resume condemns an apply it could not observe.
    @Test
    void suspension_doesNotCountTowardTheDeadline() {
        var h = publishedApply();

        h.advanceBySeconds(10 * 60);
        h.commitLeader(CORE_1, 2L);
        h.advanceBySeconds(10 * 60);
        h.commitLeader(FsmHarness.SELF, 3L);
        h.advanceBySeconds(8 * 60);
        assertThat(status(h)).as("8 min since the resume").isEqualTo(Option.some(DeploymentOutcomeStatus.IN_PROGRESS));
        h.advanceBySeconds(8 * 60);
        assertThat(status(h)).as("16 min since the resume").isEqualTo(Option.some(DeploymentOutcomeStatus.ROLLED_BACK));
    }

    /// Success wins. Red when: the deadline terminates an apply whose slices are all ACTIVE by the time it fires.
    @Test
    void completedApply_isNotTerminatedByTheDeadline() {
        var h = publishedApply();

        node(h, hosts(h, ORDERS).getFirst(), 1L).follows(ORDERS);
        h.drain();
        node(h, hosts(h, ORDERS).getFirst(), 1L).follows(ORDERS);
        h.drain();
        h.advanceBySeconds(20 * 60);
        assertThat(status(h)).isNotEqualTo(Option.some(DeploymentOutcomeStatus.ROLLED_BACK));
        assertThat(h.warns("apply-deadline-exceeded")).isEmpty();
    }

    // -- boundaries and guards (mutation pins) ----------------------------------------------------------------------
    /// The deadline is inclusive. Red when: an apply whose unpaused time equals the deadline is not terminated at that pass (it waits a tick).
    @Test
    void deadline_isInclusive() {
        var h = publishedApply(DeploymentAtomicity.ALL_OR_NOTHING, timing -> timing.withApplyDeadline(60_000L));

        h.advanceBySeconds(5 * 60);
        assertThat(status(h)).isEqualTo(Option.some(DeploymentOutcomeStatus.ROLLED_BACK));
        assertThat(lastWarning(h)).as("terminated at exactly the deadline").contains("after 60000 ms of unpaused time");
    }

    /// Red when: a datasource owned by another blueprint base pauses this blueprint's clock.
    @Test
    void foreignDatasource_doesNotPauseTheClock() {
        var h = publishedApply();

        h.seed(new SchemaVersionKey("ds"), schemaOwnedBy(SchemaStatus.MIGRATING, blueprintId("org.other:app:1.0.0")));
        h.advanceBySeconds(16 * 60);
        assertThat(status(h)).isEqualTo(Option.some(DeploymentOutcomeStatus.ROLLED_BACK));
    }

    /// Red when: the shipped default of the schema pause cap is not two hours, or of the deadline not fifteen minutes.
    @Test
    void defaults_areFifteenMinutes_andTwoHours() {
        assertThat(Timing.DEFAULT.applyDeadline()).isEqualTo(15 * 60_000L);
        assertThat(Timing.DEFAULT.applySchemaPauseMax()).isEqualTo(2 * 60 * 60_000L);
    }

    /// Success wins while it is in flight. Red when: an apply whose slices are ACTIVE and whose SUCCEEDED outcome is unsettled is terminated
    /// by the deadline.
    @Test
    void successInFlight_isNotTerminatedByTheDeadline() {
        var h = succeededAndHeld(UnaryOperator.identity());

        h.advanceBySeconds(20 * 60);
        assertThat(h.warns("apply-deadline-exceeded")).isEmpty();
        assertThat(h.submits(ClusterModel.SubmitKind.ROLLBACK)).isEmpty();
    }

    /// Success wins while it is in flight, even when a slice stops being ACTIVE afterwards. Red when: the in-flight SUCCEEDED does not by itself
    /// stop the deadline.
    @Test
    void successInFlight_staysProtected_whenASliceLeavesActive() {
        var h = succeededAndHeld(timing -> timing.withApplyDeadline(30_000L));

        node(h, hosts(h, ORDERS).getFirst(), 1L).writes(ORDERS, SliceObservedState.LOADED);
        h.drain();
        h.advanceBySeconds(40);
        assertThat(h.warns("apply-deadline-exceeded")).isEmpty();
        assertThat(h.submits(ClusterModel.SubmitKind.ROLLBACK)).isEmpty();
    }

    /// Once per episode. Red when: a deadline terminal whose rollback is still unsettled raises the warning again at every pass.
    @Test
    void deadlineWarning_isRaisedOnce_whileTheTerminalIsUnsettled() {
        var h = publishedApply();

        h.policy = FsmHarness.SettlePolicy.HOLD;
        h.advanceBySeconds(16 * 60);
        h.advanceBySeconds(5 * 60);
        assertThat(h.warns("apply-deadline-exceeded")).hasSize(1);
    }

    /// Red when: a warning stands for a different terminal of the same attempt (here FAILED by another cause) instead of being closed.
    @Test
    void deadlineWarning_isClosed_whenTheSameAttemptEndsOtherwise() {
        var h = publishedApply();

        h.advanceBySeconds(16 * 60);
        assertThat(h.warns("apply-deadline-exceeded")).hasSize(1);
        var next = outcome(h).map(DeploymentOutcomeValue::outcomeVersion).or(0L) + 1L;

        h.seed(new DeploymentOutcomeKey(ID),
               new DeploymentOutcomeValue(DeploymentOutcomeStatus.FAILED, List.of(), "slice-failed", FsmHarness.START_WALL, next, ATTEMPT));
        h.advanceBySeconds(60);
        assertThat(h.warns("apply-deadline-cleared")).hasSize(1);
    }

    /// Red when: the clock of an apply that ended survives it, so a republish of the same attempt is condemned at its first pass.
    @Test
    void closedApply_clockIsForgotten_soARepublishStartsAgain() {
        var h = publishedApply();

        h.advanceBySeconds(16 * 60);
        assertThat(status(h)).isEqualTo(Option.some(DeploymentOutcomeStatus.ROLLED_BACK));
        h.advanceBySeconds(60);
        h.seedRemove(new DeploymentOutcomeKey(ID));
        publish(h, blueprint(APP, false, ATTEMPT, slice(ORDERS, 1, 1, Set.of())), Option.none());
        h.advanceBySeconds(2 * 60);
        assertThat(status(h)).as("two minutes into a new apply").isEqualTo(Option.some(DeploymentOutcomeStatus.IN_PROGRESS));
    }

    // -- inheritance (#2834 family) -----------------------------------------------------------------------------------
    private static FsmHarness successorOf(DeploymentOutcomeValue outcome) {
        var bounds = DivergenceBounds.divergenceBounds(TEN_HOURS_MS, TEN_HOURS_MS, TEN_HOURS_MS, TEN_HOURS_MS);
        var h = new FsmHarness(Settings.settings(FsmHarness.SELF, DeploymentAtomicity.ALL_OR_NOTHING, Set.of(), bounds()).withDivergence(bounds));
        var cores = Set.of(CORE_1, CORE_2, CORE_3);

        h.commitLeader(FsmHarness.SELF, 1L);
        h.seams = Seams.UNWIRED.withCountedCores(cores).withReady(cores);
        h.seed(new DeploymentOutcomeKey(ID), outcome);
        h.seed(FsmWarningKey.fsmWarningKey("apply-deadline-exceeded", APP), FsmWarningValue.fsmWarningValue(Map.of()));
        h.activate();

        return h;
    }

    /// An open warning whose deadline terminal still stands is adopted: the successor neither raises it again nor closes it. Red when: `Warnings.adopt`
    /// has no APPLY_DEADLINE arm (the unadopted row is then closed as a stray), or adoption does not rebuild `applyDeadlineWarned`.
    @Test
    void inheritedWarning_whoseTerminalStands_isKeptOpen() {
        var h = successorOf(new DeploymentOutcomeValue(DeploymentOutcomeStatus.ROLLED_BACK,
                                                       List.of(ORDERS.asString()),
                                                       "apply-deadline-exceeded",
                                                       FsmHarness.START_WALL,
                                                       2L,
                                                       ATTEMPT));

        h.advanceBySeconds(5 * 60);
        assertThat(h.warns("apply-deadline-cleared")).as("not closed").isEmpty();
        assertThat(h.warns("apply-deadline-exceeded")).as("not raised again").isEmpty();
        assertThat(h.store.get(FsmWarningKey.fsmWarningKey("apply-deadline-exceeded", APP)).isPresent()).as("still in the open set").isTrue();
    }

    /// An open warning whose terminal was replaced between the leaders (a republish: another attempt, IN_PROGRESS) is closed by the successor. Red when:
    /// the adoption does not check that the outcome is a deadline terminal, so the stale warning stays open for as long as the new attempt runs.
    @Test
    void inheritedWarning_whoseTerminalWasReplaced_isClosed() {
        var h = successorOf(DeploymentOutcomeValue.inProgress(FsmHarness.START_WALL, 3L, "attempt-2"));

        h.advanceBySeconds(60);
        assertThat(h.warns("apply-deadline-cleared")).as("closed by the successor").hasSize(1);
        assertThat(h.store.get(FsmWarningKey.fsmWarningKey("apply-deadline-exceeded", APP)).isPresent()).as("removed from the open set").isFalse();
    }
}
