// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.UpgradeRunPlanner.Action;
import org.pragmatica.aether.deployment.cluster.UpgradeRunPlanner.Member;
import org.pragmatica.aether.deployment.cluster.UpgradeRunPlanner.Observation;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.UpgradeRunState;
import org.pragmatica.aether.slice.kvstore.AetherValue.UpgradeRunValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.UpgradeStop;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;

/// #1543 part F — the planner of the rolling-upgrade run. Each test states one rule of the run: serial order, skip-by-version, the
/// terminal states of the replacement in flight, and that an operator's pause or abort never abandons a replacement mid-phase.
class UpgradeRunPlannerTest {
    private static final String OLD = "1.0.0";
    private static final String NEW = "1.1.0";
    private static final NodeId C1 = new NodeId("core-1");
    private static final NodeId C2 = new NodeId("core-2");
    private static final NodeId C3 = new NodeId("core-3");
    private static final NodeId W1 = new NodeId("worker-1");
    private static final long NOW = 1_000L;

    private static UpgradeRunValue run(String inFlight, int index, UpgradeRunState state, UpgradeStop stop) {
        return new UpgradeRunValue(NEW, List.of(C1, C2, C3, W1), index, inFlight, state, stop, "", 0L, 0L, 0L);
    }

    private static UpgradeRunValue running() {
        return run("", 0, UpgradeRunState.RUNNING, UpgradeStop.NONE);
    }

    private static Observation cluster() {
        return observation(Map.of(C1, new Member("core", OLD),
                                  C2, new Member("core", OLD),
                                  C3, new Member("core", OLD),
                                  W1, new Member("worker", OLD)),
                           Map.of());
    }

    private static Observation observation(Map<NodeId, Member> members, Map<NodeId, NodeReplacementValue> records) {
        return new Observation(members, records);
    }

    private static NodeReplacementValue record(NodeReplacementPhase phase, String reason) {
        return new NodeReplacementValue(new NodeId("fresh-" + phase), "core", phase, 0L, "", NEW, NodeReplacementValue.MODE_CTM, 0, reason, 0L);
    }

    private static Observation with(Observation base, NodeId original, NodeReplacementValue record) {
        var records = new HashMap<>(base.records());

        records.put(original, record);

        return observation(base.members(), records);
    }

    private static Observation without(Observation base, NodeId... gone) {
        var members = new HashMap<>(base.members());

        for (var node : gone) {
            members.remove(node);
        }

        return observation(members, base.records());
    }

    @Test
    void firstTick_beginsTheFirstNodeOnTheList_andRecordsItInFlight() {
        var plan = UpgradeRunPlanner.plan(running(), cluster(), NOW);

        assertThat(plan.action()).isEqualTo(new Action.Begin(C1));
        assertThat(plan.next().unwrap().inFlight()).isEqualTo("core-1");
        assertThat(plan.next().unwrap().state()).isEqualTo(UpgradeRunState.RUNNING);
    }

    @Test
    void nodesAlreadyOnTheTargetVersion_andNodesThatAreGone_areSkipped() {
        var members = new HashMap<>(cluster().members());

        members.put(C1, new Member("core", NEW));
        members.remove(C2);

        var plan = UpgradeRunPlanner.plan(running(), observation(members, Map.of()), NOW);

        assertThat(plan.action()).as("core-1 is already on the target, core-2 is gone: the next one is core-3").isEqualTo(new Action.Begin(C3));
        assertThat(plan.next().unwrap().index()).isEqualTo(2);
    }

    @Test
    void aReplacementInFlight_isWaitedFor_inEveryLivePhase() {
        for (var phase : List.of(NodeReplacementPhase.PROVISIONING, NodeReplacementPhase.JOINING, NodeReplacementPhase.SWAPPING,
                                 NodeReplacementPhase.CANARY, NodeReplacementPhase.DRAINING_OLD, NodeReplacementPhase.RETIRING_OLD,
                                 NodeReplacementPhase.REVERTING)) {
            var plan = UpgradeRunPlanner.plan(run("core-1", 0, UpgradeRunState.RUNNING, UpgradeStop.NONE),
                                              with(cluster(), C1, record(phase, "")),
                                              NOW);

            assertThat(plan.action()).as("%s", phase).isEqualTo(new Action.Hold());
            assertThat(plan.next().isPresent()).as("%s: nothing to commit while the replacement runs", phase).isFalse();
        }
    }

    @Test
    void aDoneReplacement_advancesTheRun_andBeginsTheNextNodeInTheSameStep() {
        var plan = UpgradeRunPlanner.plan(run("core-1", 0, UpgradeRunState.RUNNING, UpgradeStop.NONE),
                                          with(without(cluster(), C1), C1, record(NodeReplacementPhase.DONE, "")),
                                          NOW);

        assertThat(plan.action()).isEqualTo(new Action.Begin(C2));
        assertThat(plan.next().unwrap().index()).isEqualTo(1);
        assertThat(plan.next().unwrap().inFlight()).isEqualTo("core-2");
    }

    @Test
    void aRolledBackReplacement_pausesTheRun_namingTheNode_andNeverSkipsIt() {
        var plan = UpgradeRunPlanner.plan(run("core-1", 0, UpgradeRunState.RUNNING, UpgradeStop.NONE),
                                          with(cluster(), C1, record(NodeReplacementPhase.ROLLED_BACK, "never joined")),
                                          NOW);
        var next = plan.next().unwrap();

        assertThat(next.state()).isEqualTo(UpgradeRunState.PAUSED);
        assertThat(next.index()).as("the node is not skipped").isZero();
        assertThat(next.reason()).contains("core-1").contains("rolled back").contains("never joined");
    }

    @Test
    void aKeptBothReplacement_pausesTheRun_andSaysHowToSettleIt() {
        var next = UpgradeRunPlanner.plan(run("core-1", 0, UpgradeRunState.RUNNING, UpgradeStop.NONE),
                                          with(cluster(), C1, record(NodeReplacementPhase.FAILED_KEPT_BOTH, "drain never completed")),
                                          NOW).next().unwrap();

        assertThat(next.state()).isEqualTo(UpgradeRunState.PAUSED);
        assertThat(next.reason()).contains("both nodes kept").contains("settle");
    }

    @Test
    void anUnknownReplacementPhase_pausesTheRun() {
        var next = UpgradeRunPlanner.plan(run("core-1", 0, UpgradeRunState.RUNNING, UpgradeStop.NONE),
                                          with(cluster(), C1, record(NodeReplacementPhase.UNKNOWN, "")),
                                          NOW).next().unwrap();

        assertThat(next.state()).isEqualTo(UpgradeRunState.PAUSED);
    }

    /// The pin of the abort rule: with a replacement in flight an abort request changes NOTHING until that replacement is terminal,
    /// then the run ends ABORTED. It never abandons a record mid-phase.
    @Test
    void abortRequested_whileAReplacementIsInFlight_waitsForItToEnd() {
        var abort = run("core-1", 0, UpgradeRunState.RUNNING, UpgradeStop.ABORT);

        for (var phase : List.of(NodeReplacementPhase.PROVISIONING, NodeReplacementPhase.SWAPPING, NodeReplacementPhase.DRAINING_OLD)) {
            var plan = UpgradeRunPlanner.plan(abort, with(cluster(), C1, record(phase, "")), NOW);

            assertThat(plan.next().isPresent()).as("%s: no commit while the replacement is live", phase).isFalse();
            assertThat(plan.action()).isEqualTo(new Action.Hold());
        }
    }

    @Test
    void abortRequested_endsTheRunAborted_whenTheReplacementInFlightIsDone_andStartsNothingElse() {
        var plan = UpgradeRunPlanner.plan(run("core-1", 0, UpgradeRunState.RUNNING, UpgradeStop.ABORT),
                                          with(without(cluster(), C1), C1, record(NodeReplacementPhase.DONE, "")),
                                          NOW);

        assertThat(plan.action()).as("no further replacement is begun").isEqualTo(new Action.Hold());
        assertThat(plan.next().unwrap().state()).isEqualTo(UpgradeRunState.ABORTED);
        assertThat(plan.next().unwrap().index()).as("the finished replacement is counted").isEqualTo(1);
    }

    @Test
    void abortRequested_endsTheRunAborted_whenTheReplacementInFlightRolledBack() {
        var next = UpgradeRunPlanner.plan(run("core-1", 0, UpgradeRunState.RUNNING, UpgradeStop.ABORT),
                                          with(cluster(), C1, record(NodeReplacementPhase.ROLLED_BACK, "canary failed")),
                                          NOW).next().unwrap();

        assertThat(next.state()).as("an abort wins over the pause a rollback would cause").isEqualTo(UpgradeRunState.ABORTED);
    }

    @Test
    void pauseRequested_whileAReplacementIsInFlight_waitsForIt_thenPauses() {
        var pause = run("core-1", 0, UpgradeRunState.RUNNING, UpgradeStop.PAUSE);

        assertThat(UpgradeRunPlanner.plan(pause, with(cluster(), C1, record(NodeReplacementPhase.CANARY, "")), NOW).next().isPresent()).isFalse();

        var next = UpgradeRunPlanner.plan(pause, with(without(cluster(), C1), C1, record(NodeReplacementPhase.DONE, "")), NOW).next().unwrap();

        assertThat(next.state()).isEqualTo(UpgradeRunState.PAUSED);
        assertThat(next.index()).isEqualTo(1);
    }

    @Test
    void stopRequested_whenNothingIsInFlight_takesEffectAtOnce() {
        assertThat(UpgradeRunPlanner.plan(run("", 0, UpgradeRunState.RUNNING, UpgradeStop.ABORT), cluster(), NOW).next().unwrap().state())
            .isEqualTo(UpgradeRunState.ABORTED);
        assertThat(UpgradeRunPlanner.plan(run("", 0, UpgradeRunState.RUNNING, UpgradeStop.PAUSE), cluster(), NOW).next().unwrap().state())
            .isEqualTo(UpgradeRunState.PAUSED);
        assertThat(UpgradeRunPlanner.plan(run("", 0, UpgradeRunState.RUNNING, UpgradeStop.UNKNOWN), cluster(), NOW).next().unwrap().state())
            .as("a request this node does not know stops automation").isEqualTo(UpgradeRunState.PAUSED);
    }

    @Test
    void aRunThatIsNotRunning_isLeftAlone() {
        for (var state : List.of(UpgradeRunState.PAUSED, UpgradeRunState.COMPLETED, UpgradeRunState.ABORTED, UpgradeRunState.UNKNOWN)) {
            var plan = UpgradeRunPlanner.plan(run("", 0, state, UpgradeStop.NONE), cluster(), NOW);

            assertThat(plan.next().isPresent()).as("%s", state).isFalse();
            assertThat(plan.action()).isEqualTo(new Action.Hold());
        }
    }

    @Test
    void anotherNodesLiveReplacement_isWaitedFor_notStartedOver() {
        var plan = UpgradeRunPlanner.plan(running(), with(cluster(), W1, record(NodeReplacementPhase.JOINING, "")), NOW);

        assertThat(plan.action()).isEqualTo(new Action.Hold());
        assertThat(plan.next().isPresent()).isFalse();
    }

    @Test
    void anotherNodesKeptBothReplacement_pausesTheRun_insteadOfWaitingForeverForAnOperator() {
        var next = UpgradeRunPlanner.plan(running(), with(cluster(), W1, record(NodeReplacementPhase.FAILED_KEPT_BOTH, "x")), NOW).next().unwrap();

        assertThat(next.state()).isEqualTo(UpgradeRunState.PAUSED);
        assertThat(next.reason()).contains("worker-1").contains("settle");
    }

    @Test
    void aLiveReplacementOfTheNextNode_isAdopted_notBegunAgain() {
        var plan = UpgradeRunPlanner.plan(running(), with(cluster(), C1, record(NodeReplacementPhase.PROVISIONING, "")), NOW);

        assertThat(plan.action()).as("begin() would be refused: the record exists, it is adopted").isEqualTo(new Action.Hold());
        assertThat(plan.next().unwrap().inFlight()).isEqualTo("core-1");
    }

    @Test
    void aRecordThatVanished_whileTheNodeIsStillThere_isTriedAgain_andWhileTheNodeIsGone_isDone() {
        var retried = UpgradeRunPlanner.plan(run("core-1", 0, UpgradeRunState.RUNNING, UpgradeStop.NONE), cluster(), NOW);

        assertThat(retried.action()).isEqualTo(new Action.Begin(C1));

        var done = UpgradeRunPlanner.plan(run("core-1", 0, UpgradeRunState.RUNNING, UpgradeStop.NONE), without(cluster(), C1), NOW);

        assertThat(done.action()).isEqualTo(new Action.Begin(C2));
        assertThat(done.next().unwrap().index()).isEqualTo(1);
    }

    @Test
    void whenEveryNodeIsOnTheTarget_theRunCompletes() {
        var members = new HashMap<NodeId, Member>();

        cluster().members().forEach((id, member) -> members.put(id, new Member(member.role(), NEW)));

        var next = UpgradeRunPlanner.plan(running(), observation(members, Map.of()), NOW).next().unwrap();

        assertThat(next.state()).isEqualTo(UpgradeRunState.COMPLETED);
    }

    /// COMPLETED is a statement about the cluster, not about the list: a node that joined during the run on another version is appended.
    @Test
    void aStragglerThatJoinedDuringTheRun_isAppended_beforeTheRunCompletes() {
        var members = new HashMap<NodeId, Member>();
        var latecomer = new NodeId("core-late");

        cluster().members().forEach((id, member) -> members.put(id, new Member(member.role(), NEW)));
        members.put(latecomer, new Member("core", OLD));
        members.put(new NodeId("spot-1"), new Member("spot", OLD));

        var plan = UpgradeRunPlanner.plan(run("", 4, UpgradeRunState.RUNNING, UpgradeStop.NONE), observation(members, Map.of()), NOW);

        assertThat(plan.next().unwrap().state()).isEqualTo(UpgradeRunState.RUNNING);
        assertThat(plan.next().unwrap().order()).containsExactly(C1, C2, C3, W1, latecomer);
    }

    @Test
    void aMemberWithNoVersionLabel_isNotOnTheTarget() {
        var members = new HashMap<>(cluster().members());

        members.put(C1, new Member("core", ""));

        var plan = UpgradeRunPlanner.plan(running(), observation(members, Map.of()), NOW);

        assertThat(plan.action()).isEqualTo(new Action.Begin(C1));
    }
}
