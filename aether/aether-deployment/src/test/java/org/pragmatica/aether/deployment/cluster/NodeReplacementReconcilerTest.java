// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.NodeReplacementPlanner.DrainState;
import org.pragmatica.aether.deployment.cluster.NodeReplacementPlanner.Effect;
import org.pragmatica.aether.deployment.cluster.NodeReplacementPlanner.Observation;
import org.pragmatica.aether.deployment.cluster.NodeReplacementPlanner.Timings;
import org.pragmatica.aether.deployment.cluster.NodeReplacementReconciler.EffectResult;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;

/// The replacement reconciler against a MODEL of the cluster, not the real one: the model answers the observations and
/// applies the effects the way the real cluster would, so each test states a scenario and reads the phases it walked.
/// Wiring to the real cluster is pinned elsewhere (Ember).
class NodeReplacementReconcilerTest {
    private static final NodeId OLD = new NodeId("core-OLD");
    private static final NodeId NEW = new NodeId("core-NEW");
    private static final Timings TIMINGS = new Timings(10_000, 10_000, 10_000, 10_000, 0, 10_000, 10_000);

    /// A tiny cluster: what is true of the two nodes, and what the effects have done so far.
    private static final class Model implements NodeReplacementReconciler.Environment {
        final Map<NodeId, NodeReplacementValue> records = new LinkedHashMap<>();
        final AtomicLong clock = new AtomicLong(1_000);
        final List<String> effects = new ArrayList<>();
        final List<NodeReplacementPhase> phases = new ArrayList<>();
        final List<String> announcements = new ArrayList<>();
        boolean leader = true;
        boolean oldAlive = true;
        boolean newKnown;
        boolean newAlive;
        boolean newCaughtUp;
        boolean oldVoter = true;
        boolean newVoter;
        boolean settled = true;
        boolean newReady = true;
        String newVersion = "2.0.0";
        DrainState drain = DrainState.NOT_REQUESTED;
        String drainBlocked = "";
        String drainRefusal = "";
        boolean decommissioned;
        boolean handoffSettled = true;
        boolean oldInstanceGone;
        String reapFailure = "";
        EffectResult retireResult = new EffectResult.Done();
        EffectResult terminateResult = new EffectResult.Done();
        EffectResult provisionResult = new EffectResult.Done();
        Promise<EffectResult> provisionPending;
        boolean commitNeverAnswers;
        Promise<EffectResult> drainPending;
        boolean provisionedSoNewJoins = true;

        @Override public boolean isLeader() {return leader;}
        @Override public Map<NodeId, NodeReplacementValue> records() {return Map.copyOf(records);}

        @Override
        public Promise<Boolean> commit(NodeId original, NodeReplacementValue expected, NodeReplacementValue next) {
            if (commitNeverAnswers) {
                return Promise.promise();
            }

            if (!expected.equals(records.get(original))) {
                return Promise.success(false);
            }

            records.put(original, next);
            phases.add(next.phase());
            announcements.add(next.phase() + (next.reason().isEmpty() ? "" : "(" + next.reason() + ")"));

            return Promise.success(true);
        }

        @Override
        public Observation observe(NodeId original, NodeReplacementValue record) {
            return new Observation(clock.get(),
                                   oldAlive,
                                   newKnown,
                                   newAlive,
                                   newCaughtUp,
                                   oldVoter,
                                   newVoter,
                                   settled,
                                   newReady,
                                   newVersion,
                                   drain,
                                   drainBlocked,
                                   decommissioned,
                                   handoffSettled,
                                   drainRefusal,
                                   oldInstanceGone,
                                   reapFailure);
        }

        @Override
        public Promise<EffectResult> execute(Effect effect, NodeId original, NodeReplacementValue record) {
            effects.add(effect.name());

            switch (effect) {
                case PROVISION -> {
                    if (provisionPending != null) {
                        return provisionPending;
                    }

                    if (provisionResult instanceof EffectResult.Done && provisionedSoNewJoins) {
                        newKnown = true;
                        newAlive = true;
                    }

                    return Promise.success(provisionResult);
                }
                case DRAIN_OLD -> {
                    if (drainPending != null) {
                        return drainPending;
                    }

                    if (drainBlocked.isEmpty()) {
                        drain = DrainState.COMPLETE;
                    }
                }
                case RETIRE_OLD -> {
                    oldAlive = false;
                    decommissioned = true;
                    oldInstanceGone = retireResult instanceof EffectResult.Done;

                    return Promise.success(retireResult);
                }
                case TERMINATE_REPLACEMENT -> {
                    newAlive = false;
                    newKnown = false;

                    return Promise.success(terminateResult);
                }
                case NONE -> {}
            }

            return Promise.success(new EffectResult.Done());
        }

        void begin(NodeReplacementPhase phase) {
            records.put(OLD, new NodeReplacementValue(NEW, "core", phase, clock.get() + 10_000));
        }

        /// What the cluster does by itself while the reconciler waits: the replacement catches up, the swap applies.
        void clusterProgresses() {
            if (newAlive) {
                newCaughtUp = true;
            }

            var phase = records.get(OLD).phase();

            if (phase == NodeReplacementPhase.SWAPPING && newCaughtUp) {
                newVoter = true;
                oldVoter = false;
            }

            if (phase == NodeReplacementPhase.REVERTING) {
                newVoter = false;
                oldVoter = true;
            }
        }
    }

    private static NodeReplacementReconciler driver(Model model) {
        return NodeReplacementReconciler.nodeReplacementReconciler(model, TIMINGS);
    }

    private static void runToTerminal(Model model, NodeReplacementReconciler driver) {
        for (int tick = 0; tick < 60 && !NodeReplacementReconciler.isTerminal(model.records.get(OLD).phase()); tick++) {
            driver.reconcile().await();
            model.clusterProgresses();
            model.clock.addAndGet(100);
        }
    }

    private static List<NodeReplacementPhase> distinct(List<NodeReplacementPhase> phases) {
        return phases.stream().distinct().toList();
    }

    @Test
    void healthyCoreReplacement_walksEveryPhaseInOrder_toDone() {
        var model = new Model();

        model.begin(NodeReplacementPhase.PROVISIONING);
        runToTerminal(model, driver(model));

        assertThat(distinct(model.phases)).containsExactly(NodeReplacementPhase.JOINING,
                                                           NodeReplacementPhase.SWAPPING,
                                                           NodeReplacementPhase.CANARY,
                                                           NodeReplacementPhase.DRAINING_OLD,
                                                           NodeReplacementPhase.RETIRING_OLD,
                                                           NodeReplacementPhase.DONE);
        assertThat(model.effects).contains("PROVISION", "DRAIN_OLD", "RETIRE_OLD").doesNotContain("TERMINATE_REPLACEMENT");
    }

    /// The caught-up gate: while the replacement is not a caught-up, admitted core candidate, SWAPPING is never committed,
    /// so the swap (authorized only in SWAPPING) can never be asked for. Deterministic: the model never lets it catch up.
    @Test
    void swapIsNeverAuthorized_whileTheReplacementIsNotCaughtUp() {
        var model = new Model();

        model.begin(NodeReplacementPhase.JOINING);
        model.newKnown = true;
        model.newAlive = true;
        model.newCaughtUp = false;
        for (int tick = 0; tick < 20; tick++) {
            driver(model).reconcile().await();
            model.clock.addAndGet(100);
        }

        assertThat(model.records.get(OLD).phase()).isIn(NodeReplacementPhase.JOINING);
        assertThat(model.phases).doesNotContain(NodeReplacementPhase.SWAPPING);
    }

    /// Control for the gate: the same state with the replacement caught up does swap.
    @Test
    void swapIsAuthorized_theMomentTheReplacementIsCaughtUp() {
        var model = new Model();

        model.begin(NodeReplacementPhase.JOINING);
        model.newKnown = true;
        model.newAlive = true;
        model.newCaughtUp = true;
        driver(model).reconcile().await();

        assertThat(model.records.get(OLD).phase()).isEqualTo(NodeReplacementPhase.SWAPPING);
    }

    @Test
    void replacementThatNeverJoins_isRolledBackAtTheDeadline_andTerminated_oldUntouched() {
        var model = new Model();

        model.begin(NodeReplacementPhase.PROVISIONING);
        model.provisionedSoNewJoins = false;
        for (int tick = 0; tick < 400 && !NodeReplacementReconciler.isTerminal(model.records.get(OLD).phase()); tick++) {
            driver(model).reconcile().await();
            model.clock.addAndGet(100);
        }

        assertThat(model.records.get(OLD).phase()).isEqualTo(NodeReplacementPhase.ROLLED_BACK);
        assertThat(model.effects).contains("TERMINATE_REPLACEMENT").doesNotContain("DRAIN_OLD", "RETIRE_OLD");
        assertThat(model.oldAlive && model.oldVoter).as("the old node is untouched").isTrue();
        assertThat(model.announcements).anyMatch(text -> text.startsWith("JOINING(join-overdue)"));
        assertThat(model.announcements.getLast()).startsWith("ROLLED_BACK");
    }

    @Test
    void provisionRefused_rollsBackAtOnce_noBreakBeforeMakeFallback() {
        var model = new Model();

        model.begin(NodeReplacementPhase.PROVISIONING);
        model.provisionResult = new EffectResult.Failed("capacity refused");
        driver(model).reconcile().await();

        assertThat(model.records.get(OLD).phase()).isEqualTo(NodeReplacementPhase.ROLLED_BACK);
        assertThat(model.effects).containsExactly("PROVISION");
    }

    @Test
    void provisionDeferred_holdsAndRetries() {
        var model = new Model();

        model.begin(NodeReplacementPhase.PROVISIONING);
        model.provisionResult = new EffectResult.Deferred("circuit open");
        driver(model).reconcile().await();
        driver(model).reconcile().await();

        assertThat(model.records.get(OLD).phase()).isEqualTo(NodeReplacementPhase.PROVISIONING);
        assertThat(model.effects).containsExactly("PROVISION", "PROVISION");
    }

    @Test
    void swapThatNeverSettles_whileOldIsStillVoter_rollsBack() {
        var model = new Model();

        model.begin(NodeReplacementPhase.SWAPPING);
        model.newKnown = true;
        model.newAlive = true;
        model.clock.addAndGet(20_000);
        driver(model).reconcile().await();

        assertThat(model.records.get(OLD).phase()).isEqualTo(NodeReplacementPhase.ROLLED_BACK);
    }

    @Test
    void canaryWithWrongVersion_revertsTheSwap_thenRollsBack() {
        var model = new Model();

        model.records.put(OLD,
                          new NodeReplacementValue(NEW, "core", NodeReplacementPhase.CANARY, model.clock.get() + 500, "", "3.0.0", "CTM", 0, "", 0L));
        model.newKnown = true;
        model.newAlive = true;
        model.newVoter = true;
        model.oldVoter = false;
        model.newVersion = "2.0.0";
        for (int tick = 0; tick < 60 && !NodeReplacementReconciler.isTerminal(model.records.get(OLD).phase()); tick++) {
            driver(model).reconcile().await();
            model.clusterProgresses();
            model.clock.addAndGet(100);
        }

        assertThat(distinct(model.phases)).containsExactly(NodeReplacementPhase.REVERTING, NodeReplacementPhase.ROLLED_BACK);
        assertThat(model.oldVoter).as("the original holds its seat again").isTrue();
        assertThat(model.effects).contains("TERMINATE_REPLACEMENT");
    }

    @Test
    void drainBlockedByTheFloor_marksItOnce_thenFailsKeptBoth_neverForced() {
        var model = new Model();

        model.begin(NodeReplacementPhase.DRAINING_OLD);
        model.newKnown = true;
        model.newAlive = true;
        model.newVoter = true;
        model.oldVoter = false;
        model.drainBlocked = "org.example:a below minAvailable 2";
        for (int tick = 0; tick < 400 && !NodeReplacementReconciler.isTerminal(model.records.get(OLD).phase()); tick++) {
            driver(model).reconcile().await();
            model.clock.addAndGet(100);
        }

        assertThat(model.records.get(OLD).phase()).isEqualTo(NodeReplacementPhase.FAILED_KEPT_BOTH);
        assertThat(model.announcements.stream().filter(text -> text.startsWith("DRAINING_OLD(drain-blocked")).count())
            .as("one blocked transition, not one per tick").isEqualTo(1);
        assertThat(model.oldAlive).as("the old node stays up").isTrue();
    }

    @Test
    void drainBlockThatClears_isAnnouncedUnblocked_andReplacementCompletes() {
        var model = new Model();

        model.begin(NodeReplacementPhase.DRAINING_OLD);
        model.newKnown = true;
        model.newAlive = true;
        model.newVoter = true;
        model.oldVoter = false;
        model.drainBlocked = "slice below floor";
        driver(model).reconcile().await();
        driver(model).reconcile().await();
        model.drainBlocked = "";
        runToTerminal(model, driver(model));

        assertThat(model.records.get(OLD).phase()).isEqualTo(NodeReplacementPhase.DONE);
        assertThat(model.announcements).anyMatch(text -> text.equals("DRAINING_OLD"));
    }

    /// Addition 1: a dead old node is never drained. At EVERY phase the old node may be dead; whatever the phase, the
    /// replacement reaches DONE without a DRAIN_OLD effect.
    @Test
    void deadOldNode_atEveryPhase_isNeverDrained_andTheReplacementCompletes() {
        for (var start : EnumSet.of(NodeReplacementPhase.PROVISIONING,
                                    NodeReplacementPhase.JOINING,
                                    NodeReplacementPhase.SWAPPING,
                                    NodeReplacementPhase.CANARY,
                                    NodeReplacementPhase.DRAINING_OLD,
                                    NodeReplacementPhase.RETIRING_OLD)) {
            var model = new Model();

            model.begin(start);
            model.oldAlive = false;
            model.newKnown = start != NodeReplacementPhase.PROVISIONING;
            model.newAlive = model.newKnown;
            model.newVoter = start == NodeReplacementPhase.CANARY || start == NodeReplacementPhase.DRAINING_OLD || start == NodeReplacementPhase.RETIRING_OLD;
            model.oldVoter = !model.newVoter;
            runToTerminal(model, driver(model));

            assertThat(model.records.get(OLD).phase()).as("start phase %s", start).isEqualTo(NodeReplacementPhase.DONE);
            assertThat(model.effects).as("start phase %s", start).doesNotContain("DRAIN_OLD");
        }
    }

    /// Leader change at EVERY phase: driver A is dropped the moment the record first reaches the phase, and a brand new
    /// driver B over the same committed records finishes the replacement. Nothing is carried but the record.
    @Test
    void leaderChange_atEveryPhase_resumesFromTheRecord_toDone() {
        for (var crashAt : List.of(NodeReplacementPhase.JOINING,
                                   NodeReplacementPhase.SWAPPING,
                                   NodeReplacementPhase.CANARY,
                                   NodeReplacementPhase.DRAINING_OLD,
                                   NodeReplacementPhase.RETIRING_OLD)) {
            var model = new Model();
            var first = driver(model);

            model.begin(NodeReplacementPhase.PROVISIONING);
            for (int tick = 0; tick < 60 && model.records.get(OLD).phase() != crashAt; tick++) {
                first.reconcile().await();
                model.clusterProgresses();
                model.clock.addAndGet(100);
            }

            assertThat(model.records.get(OLD).phase()).as("reached %s", crashAt).isEqualTo(crashAt);
            model.leader = false;
            first.reconcile().await();
            assertThat(model.records.get(OLD).phase()).as("a non-leader changes nothing at %s", crashAt).isEqualTo(crashAt);
            model.leader = true;
            runToTerminal(model, driver(model));

            assertThat(model.records.get(OLD).phase()).as("resumed after %s", crashAt).isEqualTo(NodeReplacementPhase.DONE);
        }
    }

    @Test
    void staleWriter_cannotOverwriteANewerRecord() {
        var model = new Model();

        model.begin(NodeReplacementPhase.JOINING);
        var stale = model.records.get(OLD);

        model.records.put(OLD, stale.advanced(NodeReplacementPhase.SWAPPING, model.clock.get() + 1_000, ""));

        assertThat(model.commit(OLD, stale, stale.advanced(NodeReplacementPhase.ROLLED_BACK, 0, "late")).await().unwrap()).isFalse();
        assertThat(model.records.get(OLD).phase()).isEqualTo(NodeReplacementPhase.SWAPPING);
    }

    @Test
    void retirementOverdue_endsDone_withTheReasonRecorded_neverStuck() {
        var model = new Model();

        model.begin(NodeReplacementPhase.RETIRING_OLD);
        model.handoffSettled = false;
        for (int tick = 0; tick < 400 && !NodeReplacementReconciler.isTerminal(model.records.get(OLD).phase()); tick++) {
            driver(model).reconcile().await();
            model.clock.addAndGet(100);
        }

        assertThat(model.records.get(OLD).phase()).isEqualTo(NodeReplacementPhase.DONE);
        assertThat(model.records.get(OLD).reason()).contains("retirement overdue");
    }

    /// #1543: DONE means the old node's provider instance is confirmed gone. A retirement whose termination is never confirmed ends
    /// in FAILED_KEPT_BOTH at the deadline, with the cause named, never DONE.
    @Test
    void retirement_isNeverDone_whileTheInstanceIsNotConfirmedGone() {
        var model = new Model();

        model.begin(NodeReplacementPhase.RETIRING_OLD);
        model.retireResult = new EffectResult.Deferred("termination not confirmed: still listed");
        model.reapFailure = "instance of core-OLD: still listed at the provider after terminate";
        for (int tick = 0; tick < 400 && !NodeReplacementReconciler.isTerminal(model.records.get(OLD).phase()); tick++) {
            driver(model).reconcile().await();
            model.clock.addAndGet(100);
        }

        assertThat(model.records.get(OLD).phase()).isEqualTo(NodeReplacementPhase.FAILED_KEPT_BOTH);
        assertThat(model.records.get(OLD).reason()).contains("not confirmed terminated").contains("still listed at the provider after terminate");
        assertThat(model.phases).doesNotContain(NodeReplacementPhase.DONE);
        assertThat(model.announcements.getLast()).startsWith("FAILED_KEPT_BOTH");
    }

    @Test
    void retirement_retries_andIsDoneOnceTheTerminationIsConfirmed() {
        var model = new Model();

        model.begin(NodeReplacementPhase.RETIRING_OLD);
        model.retireResult = new EffectResult.Deferred("termination not confirmed: refused");
        for (int tick = 0; tick < 3; tick++) {
            driver(model).reconcile().await();
            model.clock.addAndGet(100);
        }

        assertThat(model.records.get(OLD).phase()).as("still retiring: the instance is not confirmed gone").isEqualTo(NodeReplacementPhase.RETIRING_OLD);
        model.retireResult = new EffectResult.Done();
        for (int tick = 0; tick < 10 && !NodeReplacementReconciler.isTerminal(model.records.get(OLD).phase()); tick++) {
            driver(model).reconcile().await();
            model.clock.addAndGet(100);
        }

        assertThat(model.records.get(OLD).phase()).isEqualTo(NodeReplacementPhase.DONE);
        assertThat(model.effects.stream().filter("RETIRE_OLD"::equals).count()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void rollback_isKeptBoth_notRolledBack_whenTheReplacementCannotBeTerminated() {
        var model = new Model();

        model.begin(NodeReplacementPhase.PROVISIONING);
        model.provisionedSoNewJoins = false;
        model.terminateResult = new EffectResult.Failed("still listed");
        model.reapFailure = "instance of core-NEW: still listed at the provider after terminate";
        for (int tick = 0; tick < 400 && !NodeReplacementReconciler.isTerminal(model.records.get(OLD).phase()); tick++) {
            driver(model).reconcile().await();
            model.clock.addAndGet(100);
        }

        assertThat(model.records.get(OLD).phase()).isEqualTo(NodeReplacementPhase.FAILED_KEPT_BOTH);
        assertThat(model.records.get(OLD).reason()).contains("could not be rolled back").contains("still listed at the provider after terminate");
        assertThat(model.phases).doesNotContain(NodeReplacementPhase.ROLLED_BACK);
    }

    @Test
    void timingsOverride_parsesSevenValues_andIgnoresAnythingElse() {
        assertThat(Timings.parse("1,2,3,4,5,6,7")).isEqualTo(new Timings(1, 2, 3, 4, 5, 6, 7));
        assertThat(Timings.parse("1,2,3")).as("too short: defaults").isEqualTo(Timings.parse(""));
        assertThat(Timings.parse("a,b,c,d,e,f,g")).as("unparsable: defaults").isEqualTo(Timings.parse(""));
    }

    private Model workerModel() {
        var model = new Model();

        model.records.put(OLD, new NodeReplacementValue(NEW, "worker", NodeReplacementPhase.PROVISIONING, model.clock.get() + 10_000));
        model.oldVoter = false;

        return model;
    }

    /// E2: a worker holds no consensus seat, so its replacement never enters SWAPPING and never touches a voter.
    @Test
    void workerReplacement_skipsTheSwap_andWalksJoiningCanaryDrainRetireDone() {
        var model = workerModel();

        runToTerminal(model, driver(model));

        assertThat(distinct(model.phases)).containsExactly(NodeReplacementPhase.JOINING,
                                                           NodeReplacementPhase.CANARY,
                                                           NodeReplacementPhase.DRAINING_OLD,
                                                           NodeReplacementPhase.RETIRING_OLD,
                                                           NodeReplacementPhase.DONE);
        assertThat(model.effects).contains("PROVISION", "DRAIN_OLD", "RETIRE_OLD");
    }

    /// A failed worker canary has no seat to swap back: the replacement is given up while the original still serves.
    @Test
    void workerCanaryFailure_rollsBackWithoutReverting_andTerminatesTheReplacement() {
        var model = workerModel();

        model.records.put(OLD, new NodeReplacementValue(NEW, "worker", NodeReplacementPhase.CANARY, model.clock.get() + 300, "", "3.0.0", "CTM", 0, "", 0L));
        model.newKnown = true;
        model.newAlive = true;
        model.newVersion = "2.0.0";
        runToTerminal(model, driver(model));

        assertThat(distinct(model.phases)).containsExactly(NodeReplacementPhase.ROLLED_BACK);
        assertThat(model.effects).contains("TERMINATE_REPLACEMENT");
        assertThat(model.oldAlive).isTrue();
    }

    @Test
    void deadOldWorker_atEveryPhase_isNeverDrained() {
        for (var start : EnumSet.of(NodeReplacementPhase.JOINING, NodeReplacementPhase.CANARY, NodeReplacementPhase.DRAINING_OLD, NodeReplacementPhase.RETIRING_OLD)) {
            var model = workerModel();

            model.records.put(OLD, new NodeReplacementValue(NEW, "worker", start, model.clock.get() + 10_000));
            model.oldAlive = false;
            model.newKnown = true;
            model.newAlive = true;
            model.newCaughtUp = true;
            runToTerminal(model, driver(model));

            assertThat(model.records.get(OLD).phase()).as("start %s", start).isEqualTo(NodeReplacementPhase.DONE);
            assertThat(model.effects).as("start %s", start).doesNotContain("DRAIN_OLD");
        }
    }

    // ---- v-2008 round: B4 / B5 / N3 / N4 ------------------------------------------------------------------------------

    @Test
    void defaultTimings_giveTheCanaryANonZeroWait() {
        assertThat(Timings.parse("").canaryWaitMs()).as("a canary that passes on its first look certifies a corpse").isGreaterThan(0);
    }

    /// B5: the replacement dies silently right after the swap. The canary holds for its wait, sees the death, and REVERTS: the
    /// original is never drained or retired.
    @Test
    void replacementThatDiesDuringTheCanaryWait_revertsTheSwap_andTheOriginalSurvives() {
        var model = new Model();
        var timings = new Timings(10_000, 10_000, 10_000, 10_000, 2_000, 10_000, 10_000);
        var driver = NodeReplacementReconciler.nodeReplacementReconciler(model, timings);

        model.begin(NodeReplacementPhase.CANARY);
        model.newKnown = true;
        model.newAlive = true;
        model.newVoter = true;
        model.oldVoter = false;
        for (int tick = 0; tick < 60 && !NodeReplacementReconciler.isTerminal(model.records.get(OLD).phase()); tick++) {
            driver.reconcile().await();
            model.clusterProgresses();
            model.clock.addAndGet(100);
            if (tick == 5) {
                model.newAlive = false;
            }
        }

        assertThat(distinct(model.phases)).contains(NodeReplacementPhase.REVERTING, NodeReplacementPhase.ROLLED_BACK)
                                          .doesNotContain(NodeReplacementPhase.DRAINING_OLD, NodeReplacementPhase.RETIRING_OLD, NodeReplacementPhase.DONE);
        assertThat(model.effects).doesNotContain("DRAIN_OLD", "RETIRE_OLD");
        assertThat(model.oldAlive && model.oldVoter).as("the original is alive and holds its seat").isTrue();
    }

    @Test
    void replacementDyingBeforeTheDrainStarts_reverts_butOnceTheOldNodeDrainsTheyAreBothKept() {
        var before = new Model();

        before.begin(NodeReplacementPhase.DRAINING_OLD);
        before.newKnown = true;
        before.newAlive = false;
        before.newVoter = true;
        before.oldVoter = false;
        driver(before).reconcile().await();

        assertThat(before.records.get(OLD).phase()).as("drain not requested: swap back").isEqualTo(NodeReplacementPhase.REVERTING);
        assertThat(before.effects).doesNotContain("DRAIN_OLD");

        var during = new Model();

        during.begin(NodeReplacementPhase.DRAINING_OLD);
        during.newKnown = true;
        during.newAlive = false;
        during.newVoter = true;
        during.oldVoter = false;
        during.drain = DrainState.IN_PROGRESS;
        driver(during).reconcile().await();

        assertThat(during.records.get(OLD).phase()).as("the old node is already draining: keep both").isEqualTo(NodeReplacementPhase.FAILED_KEPT_BOTH);
    }

    @Test
    void replacementDyingBeforeTheOldNodeIsRetired_neverRetiresTheLastNodeOfThePair() {
        var model = new Model();

        model.begin(NodeReplacementPhase.RETIRING_OLD);
        model.newKnown = true;
        model.newAlive = false;
        model.newVoter = true;
        model.oldVoter = false;
        model.decommissioned = false;
        driver(model).reconcile().await();

        assertThat(model.records.get(OLD).phase()).isEqualTo(NodeReplacementPhase.FAILED_KEPT_BOTH);
        assertThat(model.effects).doesNotContain("RETIRE_OLD");
    }

    /// N4: a swap that was already requested can still install, so the replacement is terminated only once the roster is settled.
    @Test
    void swapRollback_waitsForTheRosterToSettle_beforeTerminatingTheReplacement() {
        var model = new Model();

        model.begin(NodeReplacementPhase.SWAPPING);
        model.newKnown = true;
        model.newAlive = true;
        model.settled = false;
        model.clock.addAndGet(20_000);
        driver(model).reconcile().await();

        assertThat(model.records.get(OLD).phase()).isEqualTo(NodeReplacementPhase.SWAPPING);
        assertThat(model.effects).doesNotContain("TERMINATE_REPLACEMENT");

        model.settled = true;
        driver(model).reconcile().await();

        assertThat(model.records.get(OLD).phase()).isEqualTo(NodeReplacementPhase.ROLLED_BACK);
    }

    /// B4: an effect that has not answered is not run again by the next tick, however late it is, and an answer that comes in
    /// after the phase deadline still advances the record: a VM that is coming up is never reported as a rollback.
    @Test
    void pendingProvision_isNotReopenedByLaterTicks_andALateAnswerStillAdvances() {
        var model = new Model();
        var driver = driver(model);

        model.begin(NodeReplacementPhase.PROVISIONING);
        model.provisionPending = Promise.promise();
        var first = driver.reconcile();

        driver.reconcile().await();
        model.clock.addAndGet(60_000);
        driver.reconcile().await();

        assertThat(model.effects).as("one dispatch, however many ticks passed").containsExactly("PROVISION");
        assertThat(model.records.get(OLD).phase()).as("no rollback while the provider has not answered").isEqualTo(NodeReplacementPhase.PROVISIONING);

        model.provisionPending.succeed(new EffectResult.Done());
        first.await();

        assertThat(model.records.get(OLD).phase()).isEqualTo(NodeReplacementPhase.JOINING);
        assertThat(model.effects).containsExactly("PROVISION");
    }

    @Test
    void attempt_countsHowOftenTheSamePhaseWasCommittedAgain() {
        var model = new Model();

        model.begin(NodeReplacementPhase.JOINING);
        model.newKnown = true;
        model.newAlive = true;
        model.newCaughtUp = false;
        model.clock.addAndGet(6_000);
        driver(model).reconcile().await();

        assertThat(model.records.get(OLD).reason()).isEqualTo(NodeReplacementPlanner.JOIN_OVERDUE);
        assertThat(model.records.get(OLD).attempt()).as("the same phase, committed again with a marker").isEqualTo(1);
    }

    /// A worker holds no seat: a replacement lost before the old worker is drained is given up (nothing to swap back).
    @Test
    void workerReplacementLostBeforeTheDrain_isRolledBack_notReverted() {
        var model = new Model();

        model.records.put(OLD, new NodeReplacementValue(NEW, "worker", NodeReplacementPhase.DRAINING_OLD, model.clock.get() + 10_000));
        model.newKnown = true;
        model.newAlive = false;
        driver(model).reconcile().await();

        assertThat(model.records.get(OLD).phase()).isEqualTo(NodeReplacementPhase.ROLLED_BACK);
        assertThat(model.effects).contains("TERMINATE_REPLACEMENT").doesNotContain("DRAIN_OLD");
    }

    /// Settling a kept-both worker pair as "roll back" commits REVERTING; for a worker that only gives the replacement up.
    @Test
    void workerRevertingFromASettle_givesTheReplacementUp_withoutWaitingForASeat() {
        var model = new Model();

        model.records.put(OLD, new NodeReplacementValue(NEW, "worker", NodeReplacementPhase.REVERTING, model.clock.get() + 10_000));
        model.newKnown = true;
        model.newAlive = true;
        model.oldVoter = false;
        driver(model).reconcile().await();

        assertThat(model.records.get(OLD).phase()).isEqualTo(NodeReplacementPhase.ROLLED_BACK);
        assertThat(model.effects).contains("TERMINATE_REPLACEMENT");
    }

    /// B4 follow-up: an effect is bounded, and so is the commit. A compare-and-set that never answers must not stop the ticks for
    /// good (the driver does not re-open a tick while one is pending), so the next tick plans again from the committed records.
    @Test
    void commitThatNeverAnswers_doesNotStopLaterTicks() {
        var model = new Model();
        var driver = NodeReplacementReconciler.nodeReplacementReconciler(model, TIMINGS, org.pragmatica.lang.io.TimeSpan.timeSpan(200).millis());

        model.begin(NodeReplacementPhase.PROVISIONING);
        model.commitNeverAnswers = true;
        driver.reconcile().await(org.pragmatica.lang.io.TimeSpan.timeSpan(5).seconds());
        model.commitNeverAnswers = false;
        // The tick that gave up releases the driver just after its promise resolves, so a later tick may need a moment: tick
        // again (as the scheduler does) until the record moves or two seconds pass.
        for (int attempt = 0; attempt < 40 && model.records.get(OLD).phase() == NodeReplacementPhase.PROVISIONING; attempt++) {
            driver.reconcile().await(org.pragmatica.lang.io.TimeSpan.timeSpan(5).seconds());
            sleepBriefly();
        }

        assertThat(model.records.get(OLD).phase()).as("the second tick ran and committed").isEqualTo(NodeReplacementPhase.JOINING);
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /// v-2042 H8b: a worker's failed canary gives the replacement up while the original still serves, but with the original gone the
    /// replacement is the last node of the pair and is kept (never terminated).
    @Test
    void workerCanaryFailure_withTheOriginalGone_keepsTheReplacement_withTheOriginalAlive_givesItUp() {
        var gone = new Model();

        gone.records.put(OLD, new NodeReplacementValue(NEW, "worker", NodeReplacementPhase.CANARY, gone.clock.get() + 10_000));
        gone.newKnown = true;
        gone.newAlive = false;
        gone.oldAlive = false;
        driver(gone).reconcile().await();

        assertThat(gone.records.get(OLD).phase()).isEqualTo(NodeReplacementPhase.FAILED_KEPT_BOTH);
        assertThat(gone.effects).doesNotContain("TERMINATE_REPLACEMENT");

        var serving = new Model();

        serving.records.put(OLD, new NodeReplacementValue(NEW, "worker", NodeReplacementPhase.CANARY, serving.clock.get() + 10_000));
        serving.newKnown = true;
        serving.newAlive = false;
        driver(serving).reconcile().await();

        assertThat(serving.records.get(OLD).phase()).as("control: with the original alive the replacement is given up").isEqualTo(NodeReplacementPhase.ROLLED_BACK);
    }

    /// v-2042 nit: settling a kept-both worker pair as "roll back" must not terminate the last node when the original is gone.
    @Test
    void workerRevertingFromASettle_withTheOriginalGone_keepsTheReplacement() {
        var model = new Model();

        model.records.put(OLD, new NodeReplacementValue(NEW, "worker", NodeReplacementPhase.REVERTING, model.clock.get() + 10_000));
        model.newKnown = true;
        model.newAlive = true;
        model.oldAlive = false;
        driver(model).reconcile().await();

        assertThat(model.records.get(OLD).phase()).isEqualTo(NodeReplacementPhase.FAILED_KEPT_BOTH);
        assertThat(model.effects).doesNotContain("TERMINATE_REPLACEMENT");
    }

    /// v-2008 U2 (B4): an effect is bounded. A provider or admission call that never answers must not stall every later tick (the
    /// driver does not re-open a tick while one is pending), so after the bound the next tick plans again and re-issues the effect.
    @Test
    void effectThatNeverAnswers_isBounded_soALaterTickIssuesItAgain() {
        var model = new Model();
        var driver = NodeReplacementReconciler.nodeReplacementReconciler(model,
                                                                         TIMINGS,
                                                                         org.pragmatica.lang.io.TimeSpan.timeSpan(30).seconds(),
                                                                         effect -> org.pragmatica.lang.io.TimeSpan.timeSpan(200).millis());

        model.begin(NodeReplacementPhase.DRAINING_OLD);
        model.newKnown = true;
        model.newAlive = true;
        model.newVoter = true;
        model.oldVoter = false;
        model.drainPending = Promise.promise();
        driver.reconcile().await(org.pragmatica.lang.io.TimeSpan.timeSpan(5).seconds());
        for (int attempt = 0; attempt < 40 && model.effects.stream().filter("DRAIN_OLD"::equals).count() < 2; attempt++) {
            driver.reconcile().await(org.pragmatica.lang.io.TimeSpan.timeSpan(5).seconds());
            sleepBriefly();
        }

        assertThat(model.effects.stream().filter("DRAIN_OLD"::equals).count()).as("the drain was issued again after the first one outlived its bound").isGreaterThanOrEqualTo(2);
    }

    /// v-2008 S1: a drain the admission refused for a reason other than the floor is remembered, and the kept-both reason says why.
    @Test
    void keptBoth_afterAnUnadmittedDrain_namesTheRefusal() {
        var refused = new Model();

        refused.begin(NodeReplacementPhase.DRAINING_OLD);
        refused.newKnown = true;
        refused.newAlive = true;
        refused.newVoter = true;
        refused.oldVoter = false;
        refused.drain = DrainState.NOT_REQUESTED;
        refused.drainRefusal = "Cannot drain node core-OLD from SYNCING (must be READY)";
        refused.clock.addAndGet(20_000);
        driver(refused).reconcile().await();

        assertThat(refused.records.get(OLD).phase()).isEqualTo(NodeReplacementPhase.FAILED_KEPT_BOTH);
        assertThat(refused.records.get(OLD).reason()).contains("drain refused: Cannot drain node core-OLD from SYNCING (must be READY)");

        var silent = new Model();

        silent.begin(NodeReplacementPhase.DRAINING_OLD);
        silent.newKnown = true;
        silent.newAlive = true;
        silent.newVoter = true;
        silent.oldVoter = false;
        silent.clock.addAndGet(20_000);
        driver(silent).reconcile().await();

        assertThat(silent.records.get(OLD).reason()).as("control: no refusal, no refusal text").doesNotContain("drain refused");
    }
}
