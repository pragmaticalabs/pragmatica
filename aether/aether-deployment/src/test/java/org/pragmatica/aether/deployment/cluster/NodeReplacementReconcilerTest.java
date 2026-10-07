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
import org.pragmatica.lang.Unit;

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
        boolean decommissioned;
        boolean handoffSettled = true;
        EffectResult provisionResult = new EffectResult.Done();
        boolean provisionedSoNewJoins = true;

        @Override public boolean isLeader() {return leader;}
        @Override public Map<NodeId, NodeReplacementValue> records() {return Map.copyOf(records);}

        @Override
        public Promise<Boolean> commit(NodeId original, NodeReplacementValue expected, NodeReplacementValue next) {
            if (!expected.equals(records.get(original))) {
                return Promise.success(false);
            }

            records.put(original, next);
            phases.add(next.phase());

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
                                   handoffSettled);
        }

        @Override
        public Promise<EffectResult> execute(Effect effect, NodeId original, NodeReplacementValue record) {
            effects.add(effect.name());

            switch (effect) {
                case PROVISION -> {
                    if (provisionResult instanceof EffectResult.Done && provisionedSoNewJoins) {
                        newKnown = true;
                        newAlive = true;
                    }

                    return Promise.success(provisionResult);
                }
                case DRAIN_OLD -> {
                    if (drainBlocked.isEmpty()) {
                        drain = DrainState.COMPLETE;
                    }
                }
                case RETIRE_OLD -> {
                    oldAlive = false;
                    decommissioned = true;
                }
                case TERMINATE_REPLACEMENT -> {
                    newAlive = false;
                    newKnown = false;
                }
                case NONE -> {}
            }

            return Promise.success(new EffectResult.Done());
        }

        @Override
        public Unit announce(NodeId original, Option<NodeReplacementValue> before, NodeReplacementValue after) {
            announcements.add(after.phase() + (after.reason().isEmpty() ? "" : "(" + after.reason() + ")"));

            return Unit.unit();
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

    @Test
    void timingsOverride_parsesSevenValues_andIgnoresAnythingElse() {
        assertThat(Timings.parse("1,2,3,4,5,6,7")).isEqualTo(new Timings(1, 2, 3, 4, 5, 6, 7));
        assertThat(Timings.parse("1,2,3")).as("too short: defaults").isEqualTo(Timings.parse(""));
        assertThat(Timings.parse("a,b,c,d,e,f,g")).as("unparsable: defaults").isEqualTo(Timings.parse(""));
    }
}
