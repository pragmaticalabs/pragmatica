// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.workflow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.slice.kvstore.AetherKey.WorkflowClaimKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.WorkflowRunKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowClaimValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowRunValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowState;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.lang.Option;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.workflow.WorkflowTestCluster.CORE_1;
import static org.assertj.core.api.Assertions.assertThat;


/// rc6 f2b §5: the run index the KV route feeds, and the durable announce derived from the applied transition. The index cases run
/// the real store's put/remove route ([WorkflowTestCluster]); the announce cases feed applied records to one index per node, as every
/// node of a cluster applies every record, and only the cluster-events owner raises.
class WorkflowRunIndexTest {
    private WorkflowTestCluster cluster;
    private WarningProbe probe;

    @BeforeEach
    void setUp() {
        cluster = new WorkflowTestCluster();
        probe = new WarningProbe();
    }

    private static WorkflowRunValue run(String id, long startedAt) {
        return WorkflowRunValue.admitted(id, "k", Map.of(), List.of("k:" + id), startedAt, startedAt + 1_000L);
    }

    private static ValuePut<WorkflowRunKey, WorkflowRunValue> put(WorkflowRunValue run) {
        return new ValuePut<>(new KVCommand.Put<>(new WorkflowRunKey(run.id()), run), Option.none());
    }

    private static List<OperatorWarningCode> codes(List<OperatorWarning> warnings) {
        return warnings.stream()
                       .map(OperatorWarning::code)
                       .toList();
    }

    //--- the view fed by the KV route

    @Test
    void absorbPut_viaTheStoreRoute_indexesTheCommittedRun_andIgnoresTheClaims() {
        var ledger = cluster.ledger(CORE_1);
        var created = run("wf-a", 10L);

        assertThat(ledger.create(created).await().unwrap()).isTrue();
        assertThat(cluster.index().runs()).containsExactly(created);
    }

    @Test
    void absorbRemove_viaTheStoreRoute_dropsTheRun() {
        var ledger = cluster.ledger(CORE_1);

        ledger.create(run("wf-a", 10L)).await().unwrap();
        ledger.create(run("wf-b", 20L)).await().unwrap();
        cluster.removeRaw("wf-a");
        assertThat(cluster.index().runs()).extracting(WorkflowRunValue::id).containsExactly("wf-b");
    }

    @Test
    void restore_replacesTheView_withTheSnapshotsRunsOnly_andAnnouncesNothing() {
        var index = WorkflowRunIndex.workflowRunIndex();
        var stale = run("wf-stale", 5L);
        var restored = run("wf-a", 10L).in(WorkflowState.ESCALATED, "incident");

        index.onTransition(WorkflowRunIndex.announcer(index, () -> true, probe.sink()));
        index.put(stale);
        index.restoreFrom(() -> Map.<Object, Object> of(new WorkflowRunKey("wf-a"),
                             restored,
                             new WorkflowClaimKey("k:wf-a"),
                             new WorkflowClaimValue("wf-a"),
                             LeaderKey.INSTANCE,
                             "not an AetherValue"));
        assertThat(index.runs()).containsExactly(restored);
        assertThat(probe.settled()).as("a restore is not a transition").isEmpty();
    }

    /// F4 (v2803): the snapshot is taken inside the index's lock, so a live put absorbed while the restore is in progress waits and
    /// lands AFTER the restore instead of being overwritten by the older snapshot.
    @Test
    void restoreFrom_aPutAbsorbedDuringTheRestore_isNotOverwrittenByTheSnapshot() throws Exception {
        var index = WorkflowRunIndex.workflowRunIndex();
        var older = run("wf-a", 10L);
        var newer = older.in(WorkflowState.ESCALATED, "newer");
        var putter = new Thread(() -> index.put(newer));

        index.restoreFrom(() -> startAndAwaitBlockedOrDone(putter, Map.of(new WorkflowRunKey("wf-a"), older)));
        putter.join(5_000L);
        assertThat(index.runs()).containsExactly(newer);
    }

    private static Map<?, ?> startAndAwaitBlockedOrDone(Thread putter, Map<?, ?> snapshot) {
        putter.start();

        var deadline = System.nanoTime() + 2_000_000_000L;

        while (putter.getState() == Thread.State.RUNNABLE && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        return snapshot;
    }

    /// i11: two runs started at the same instant are ordered by id, whatever order they were put in or the map iterates in.
    @Test
    void runs_withEqualStartedAt_areOrderedById() {
        var ids = List.of("wf-e", "wf-b", "wf-d", "wf-a", "wf-c", "wf-f", "wf-h", "wf-g");
        var index = WorkflowRunIndex.workflowRunIndex();

        ids.forEach(id -> index.put(run(id, 10L)));
        assertThat(index.runs()).extracting(WorkflowRunValue::id).isSortedAccordingTo(String::compareTo).hasSize(ids.size());
        assertThat(index.runs()).extracting(WorkflowRunValue::id).containsExactly("wf-a", "wf-b", "wf-c", "wf-d", "wf-e", "wf-f", "wf-g", "wf-h");
    }

    /// i32/i33/i34: put, remove and restore each copy the map and republish it; without `synchronized` two of them interleave and one
    /// update is lost. Spin-barrier start, a deadline on every wait; the writers touch disjoint ids, so every id must survive.
    @Test
    void concurrentPutsRemovesAndRestores_loseNoUpdate() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            var index = WorkflowRunIndex.workflowRunIndex();
            var go = new AtomicBoolean();
            var ready = new AtomicInteger();
            var threads = new ArrayList<Thread>();
            var failure = new AtomicReference<Throwable>();

            for (int t = 0; t < WRITERS; t++) {
                var writer = t;

                threads.add(Thread.ofPlatform().start(() -> write(index, go, ready, writer, failure)));
            }

            awaitReady(ready, WRITERS);
            go.set(true);
            for (var thread : threads) {
                thread.join(10_000L);
                assertThat(thread.isAlive()).as("round " + round + ": a writer did not finish").isFalse();
            }

            assertThat(failure.get()).isNull();
            assertThat(index.runs()).as("round " + round + ": no lost update")
                                    .hasSize(WRITERS * PER_WRITER / 2);
        }
    }

    private static final int ROUNDS = 300;
    private static final int WRITERS = 8;
    private static final int PER_WRITER = 40;

    /// Writer w puts PER_WRITER runs (w-0 ..), then removes the even ones (put and remove interleave across writers); the
    /// survivors are exactly the odd ones. The restore path runs through `restoreFrom` on a snapshot of the live view, which
    /// must keep every concurrent put (the F4 property).
    private static void write(WorkflowRunIndex index, AtomicBoolean go, AtomicInteger ready, int writer, AtomicReference<Throwable> failure) {
        ready.incrementAndGet();
        var deadline = System.nanoTime() + 10_000_000_000L;

        while (!go.get() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        try {
            for (int n = 0; n < PER_WRITER; n++) {
                index.put(run("wf-" + writer + "-" + n, n));
            }

            for (int n = 0; n < PER_WRITER; n += 2) {
                index.remove("wf-" + writer + "-" + n);
            }

            index.restoreFrom(() -> restoreSnapshot(index));
        } catch (Throwable e) {
            failure.compareAndSet(null, e);
        }
    }

    private static Map<?, ?> restoreSnapshot(WorkflowRunIndex index) {
        var snapshot = new HashMap<WorkflowRunKey, WorkflowRunValue>();

        index.runs().forEach(run -> snapshot.put(new WorkflowRunKey(run.id()), run));

        return snapshot;
    }

    private static void awaitReady(AtomicInteger ready, int expected) {
        var deadline = System.nanoTime() + 10_000_000_000L;

        while (ready.get() < expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        assertThat(ready.get()).as("all writers started").isEqualTo(expected);
    }

    //--- the announce: owner-only, never on replay, ESCALATED on entry, RESOLVED on terminal exit, RESUMED on resume

    @Test
    void announcer_escalation_raisesOneIncident_withTheDetail() {
        var index = WorkflowRunIndex.workflowRunIndex();
        var running = run("wf-a", 10L);

        index.onTransition(WorkflowRunIndex.announcer(index, () -> true, probe.sink()));
        index.put(running);
        index.put(running.in(WorkflowState.ESCALATED, "attempt budget exhausted"));
        var raised = probe.settled();

        assertThat(codes(raised)).containsExactly(OperatorWarningCode.WORKFLOW_ESCALATED);
        assertThat(raised.getFirst().subject()).isEqualTo("wf-a");
        assertThat(raised.getFirst().message()).contains("attempt budget exhausted");
    }

    /// The replay bit is captured at absorb and carried with the run: a replayed escalation is committed history.
    @Test
    void announcer_replayedEscalation_announcesNothing_yetIndexesTheRun() {
        var index = WorkflowRunIndex.workflowRunIndex();
        var escalated = run("wf-a", 10L).in(WorkflowState.ESCALATED, "incident");

        index.onTransition(WorkflowRunIndex.announcer(index, () -> true, probe.sink()));
        WorkflowRunIndex.absorbPut(index, () -> true, put(escalated));
        assertThat(index.runs()).containsExactly(escalated);
        assertThat(probe.settled()).isEmpty();
    }

    /// Every node applies every run; three nodes, one owner of the cluster-events partition: exactly one raise per transition.
    @Test
    void announcer_onEveryNode_raisesOnlyOnTheClusterEventsOwner_once() {
        var nodes = List.of(WorkflowRunIndex.workflowRunIndex(), WorkflowRunIndex.workflowRunIndex(), WorkflowRunIndex.workflowRunIndex());

        for (int n = 0; n < nodes.size(); n++) {
            var owner = n == 1;

            nodes.get(n).onTransition(WorkflowRunIndex.announcer(nodes.get(n), () -> owner, probe.sink()));
        }
        var running = run("wf-a", 10L);
        var escalated = running.in(WorkflowState.ESCALATED, "incident");

        for (var node : nodes) {
            WorkflowRunIndex.absorbPut(node, () -> false, put(running));
            WorkflowRunIndex.absorbPut(node, () -> false, put(escalated));
            WorkflowRunIndex.absorbPut(node, () -> false, put(escalated.in(WorkflowState.FAILED, "abandoned")));
        }
        assertThat(codes(probe.settled())).containsExactly(OperatorWarningCode.WORKFLOW_ESCALATED, OperatorWarningCode.WORKFLOW_RESOLVED);
    }

    /// CTO ruling Q4: a resume raises the INFO WORKFLOW_RESUMED and never WORKFLOW_RESOLVED; the incident stays open.
    @Test
    void announcer_resumeOfAnEscalatedRun_raisesResumed_notResolved() {
        var index = WorkflowRunIndex.workflowRunIndex();
        var escalated = run("wf-a", 10L).in(WorkflowState.ESCALATED, "incident");

        index.onTransition(WorkflowRunIndex.announcer(index, () -> true, probe.sink()));
        index.put(escalated);
        index.put(escalated.resumed(99_000L));
        var raised = probe.settled();

        assertThat(codes(raised)).containsExactly(OperatorWarningCode.WORKFLOW_ESCALATED, OperatorWarningCode.WORKFLOW_RESUMED);
        assertThat(raised.getLast().subject()).isEqualTo("wf-a");
        assertThat(raised.getLast().message()).contains("RUNNING");
    }

    /// CTO ruling Q4: ESCALATED to DONE resolves the incident with outcome DONE.
    @Test
    void announcer_escalatedRunReachingDone_raisesResolved_withOutcomeDone() {
        var index = WorkflowRunIndex.workflowRunIndex();
        var escalated = run("wf-a", 10L).in(WorkflowState.ESCALATED, "incident");

        index.onTransition(WorkflowRunIndex.announcer(index, () -> true, probe.sink()));
        index.put(escalated);
        index.put(escalated.in(WorkflowState.DONE, ""));
        var raised = probe.settled();

        assertThat(codes(raised)).containsExactly(OperatorWarningCode.WORKFLOW_ESCALATED, OperatorWarningCode.WORKFLOW_RESOLVED);
        assertThat(raised.getLast().message()).contains("outcome DONE");
        assertThat(raised.getLast().subject()).as("the subject closes the incident").isEqualTo("wf-a");
    }

    /// CTO ruling Q4: at most one RESOLVED per run per episode; a redelivered terminal commit has a terminal before.
    @Test
    void announcer_redeliveredTerminalCommit_raisesResolvedOnce() {
        var index = WorkflowRunIndex.workflowRunIndex();
        var escalated = run("wf-a", 10L).in(WorkflowState.ESCALATED, "incident");
        var failed = escalated.in(WorkflowState.FAILED, "abandoned");

        index.onTransition(WorkflowRunIndex.announcer(index, () -> true, probe.sink()));
        index.put(escalated);
        index.put(failed);
        index.put(failed);
        assertThat(codes(probe.settled())).containsExactly(OperatorWarningCode.WORKFLOW_ESCALATED, OperatorWarningCode.WORKFLOW_RESOLVED);
    }

    /// A terminal commit of a run that never escalated has no incident to resolve.
    @Test
    void announcer_terminalWithoutEscalation_raisesNothing() {
        var index = WorkflowRunIndex.workflowRunIndex();
        var running = run("wf-a", 10L);

        index.onTransition(WorkflowRunIndex.announcer(index, () -> true, probe.sink()));
        index.put(running);
        index.put(running.in(WorkflowState.DONE, ""));
        assertThat(probe.settled()).isEmpty();
    }

    /// A committed abandon (its cause in the run's detail) resolves the incident with outcome ABANDONED, not FAILED (v2803c F3).
    @Test
    void announcer_abandonedTerminalOfAnEscalatedRun_raisesResolved_withOutcomeAbandoned() {
        var index = WorkflowRunIndex.workflowRunIndex();
        var escalated = run("wf-a", 10L).in(WorkflowState.ESCALATED, "incident");

        index.onTransition(WorkflowRunIndex.announcer(index, () -> true, probe.sink()));
        index.put(escalated);
        index.put(escalated.abandoned("operator gave up"));
        var raised = probe.settled();

        assertThat(codes(raised)).containsExactly(OperatorWarningCode.WORKFLOW_ESCALATED, OperatorWarningCode.WORKFLOW_RESOLVED);
        assertThat(raised.getLast().message()).contains("outcome ABANDONED");
        assertThat(raised.getLast().subject()).as("the subject closes the incident").isEqualTo("wf-a");
    }

    /// A FAILED run whose detail is not an abandon cause stays outcome FAILED.
    @Test
    void announcer_failedTerminalOfAnEscalatedRun_raisesResolved_withOutcomeFailed() {
        var index = WorkflowRunIndex.workflowRunIndex();
        var escalated = run("wf-a", 10L).in(WorkflowState.ESCALATED, "incident");

        index.onTransition(WorkflowRunIndex.announcer(index, () -> true, probe.sink()));
        index.put(escalated);
        index.put(escalated.in(WorkflowState.FAILED, "precondition can never hold"));
        var raised = probe.settled();

        assertThat(raised.getLast().message()).contains("outcome FAILED");
        assertThat(raised.getLast().subject()).as("the subject closes the incident").isEqualTo("wf-a");
    }

    /// x07: ESCALATED to WAITING is a resume too: INFO WORKFLOW_RESUMED naming the new state, never RESOLVED.
    @Test
    void announcer_escalatedRunBackToWaiting_raisesResumed_notResolved() {
        var index = WorkflowRunIndex.workflowRunIndex();
        var escalated = run("wf-a", 10L).in(WorkflowState.ESCALATED, "incident");

        index.onTransition(WorkflowRunIndex.announcer(index, () -> true, probe.sink()));
        index.put(escalated);
        index.put(escalated.in(WorkflowState.WAITING, ""));
        var raised = probe.settled();

        assertThat(codes(raised)).containsExactly(OperatorWarningCode.WORKFLOW_ESCALATED, OperatorWarningCode.WORKFLOW_RESUMED);
        assertThat(raised.getLast().subject()).isEqualTo("wf-a");
        assertThat(raised.getLast().message()).contains("WAITING");
    }

    /// Q4 / v2803c F1: re-escalating a run whose incident is still open (a resume leaves it open) does NOT raise a second ESCALATED;
    /// a re-applied ESCALATED value raises nothing either. After the terminal commit closes the incident, a later escalation is new.
    @Test
    void announcer_reEscalationWhileTheIncidentIsOpen_raisesNoSecondEscalated() {
        var index = WorkflowRunIndex.workflowRunIndex();
        var escalated = run("wf-a", 10L).in(WorkflowState.ESCALATED, "first");
        var resumed = escalated.resumed(99_000L);
        var again = resumed.in(WorkflowState.ESCALATED, "second");

        index.onTransition(WorkflowRunIndex.announcer(index, () -> true, probe.sink()));
        index.put(escalated);
        index.put(escalated);
        index.put(resumed);
        index.put(again);
        assertThat(codes(probe.settled())).containsExactly(OperatorWarningCode.WORKFLOW_ESCALATED, OperatorWarningCode.WORKFLOW_RESUMED);
    }

    /// N13 / v2803c F2: a node that never saw the escalation (it came in a snapshot, which announces nothing) takes the events
    /// partition over, is seeded from the COMMITTED escalated runs, and resolves the incident when the terminal commit arrives.
    @Test
    void announcer_ownerHandOff_seededFromCommittedState_resolvesTheOpenIncident() {
        var index = WorkflowRunIndex.workflowRunIndex();
        var escalated = run("wf-a", 10L).in(WorkflowState.ESCALATED, "incident");
        var owner = new AtomicBoolean(false);
        var announcer = WorkflowRunIndex.announcer(index, owner::get, probe.sink());

        index.onTransition(announcer);
        index.restoreFrom(() -> Map.of(new WorkflowRunKey("wf-a"), escalated));
        owner.set(true);
        announcer.reseed();
        index.put(escalated.in(WorkflowState.DONE, ""));
        var raised = probe.settled();

        assertThat(codes(raised)).containsExactly(OperatorWarningCode.WORKFLOW_RESOLVED);
        assertThat(raised.getLast().message()).contains("outcome DONE");
    }

    /// N13: the seeding also dedupes across the hand-off: a run already ESCALATED in committed state is an open incident, so the new
    /// owner does not raise a second ESCALATED when the same escalation is applied to it again.
    @Test
    void announcer_ownerHandOff_seededFromCommittedState_raisesNoSecondEscalated() {
        var index = WorkflowRunIndex.workflowRunIndex();
        var escalated = run("wf-a", 10L).in(WorkflowState.ESCALATED, "incident");
        var announcer = WorkflowRunIndex.announcer(index, () -> true, probe.sink());

        index.onTransition(announcer);
        index.restoreFrom(() -> Map.of(new WorkflowRunKey("wf-a"), escalated));
        announcer.reseed();
        index.put(escalated);
        assertThat(probe.settled()).isEmpty();
    }

    /// N13: the seeding reads the committed state: a run that is no longer ESCALATED in the index is not an open incident any more.
    /// fixD: it escalated (marked) and is terminal, so the reseed closes the incident itself; the later terminal put adds nothing.
    @Test
    void announcer_reseed_closesAnIncidentWhoseRunIsTerminalInCommittedState_once() {
        var index = WorkflowRunIndex.workflowRunIndex();
        var escalated = run("wf-a", 10L).in(WorkflowState.ESCALATED, "incident");
        var announcer = WorkflowRunIndex.announcer(index, () -> true, probe.sink());

        index.onTransition(announcer);
        index.put(escalated);
        index.restoreFrom(() -> Map.of(new WorkflowRunKey("wf-a"), escalated.in(WorkflowState.DONE, "")));
        announcer.reseed();
        index.put(escalated.in(WorkflowState.DONE, ""));
        assertThat(codes(probe.settled())).containsExactly(OperatorWarningCode.WORKFLOW_ESCALATED, OperatorWarningCode.WORKFLOW_RESOLVED);
    }

    /// fixD: ESCALATED raised, the run goes terminal, the old owner dies before RESOLVED (the terminal is applied while this node is
    /// not the owner), then the node owns the partition and reseeds: the reseed closes the incident with the subject and outcome.
    @Test
    void announcer_reseed_afterTerminalAppliedWithoutAnOwner_raisesResolvedWithTheOutcome() {
        assertThat(reseedResolution(run("wf-a", 10L).in(WorkflowState.ESCALATED, "incident").in(WorkflowState.DONE, "")).message())
            .contains("outcome DONE");
        assertThat(reseedResolution(run("wf-a", 10L).in(WorkflowState.ESCALATED, "incident").in(WorkflowState.FAILED, "gone")).message())
            .contains("outcome FAILED");
        var abandoned = reseedResolution(run("wf-a", 10L).in(WorkflowState.ESCALATED, "incident").abandoned("operator gave up"));

        assertThat(abandoned.message()).contains("outcome ABANDONED");
        assertThat(abandoned.subject()).as("the subject closes the incident").isEqualTo("wf-a");
        assertThat(abandoned.code()).isEqualTo(OperatorWarningCode.WORKFLOW_RESOLVED);
    }

    private OperatorWarning reseedResolution(WorkflowRunValue terminal) {
        var index = WorkflowRunIndex.workflowRunIndex();
        var owner = new AtomicBoolean(true);
        var announcer = WorkflowRunIndex.announcer(index, owner::get, probe.sink());
        var escalated = run("wf-a", 10L).in(WorkflowState.ESCALATED, "incident");

        index.onTransition(announcer);
        index.put(escalated);
        owner.set(false);
        index.put(terminal);
        owner.set(true);
        probe.settled();
        announcer.reseed();
        var raised = probe.settled();

        assertThat(raised.stream().map(OperatorWarning::code).toList()).contains(OperatorWarningCode.WORKFLOW_RESOLVED);

        return raised.getLast();
    }

    /// fixD control: a run that never escalated carries no mark, so a reseed raises nothing for it, terminal or not.
    @Test
    void announcer_reseed_runThatNeverEscalated_raisesNothing() {
        var index = WorkflowRunIndex.workflowRunIndex();
        var announcer = WorkflowRunIndex.announcer(index, () -> true, probe.sink());

        index.onTransition(announcer);
        index.put(run("wf-done", 10L).in(WorkflowState.DONE, ""));
        index.put(run("wf-failed", 11L).in(WorkflowState.FAILED, "x"));
        index.put(run("wf-live", 12L));
        announcer.reseed();
        assertThat(probe.settled()).isEmpty();
    }

    /// fixD idempotence: the RESOLVED was already raised live, so a reseed raises none; a second reseed raises none either. (The
    /// aggregator would publish a repeated RESOLVED again, so the dedupe is the announcer's.)
    @Test
    void announcer_reseed_afterResolvedWasAlreadyRaised_raisesNothingMore() {
        var index = WorkflowRunIndex.workflowRunIndex();
        var escalated = run("wf-a", 10L).in(WorkflowState.ESCALATED, "incident");
        var announcer = WorkflowRunIndex.announcer(index, () -> true, probe.sink());

        index.onTransition(announcer);
        index.put(escalated);
        index.put(escalated.in(WorkflowState.DONE, ""));
        announcer.reseed();
        announcer.reseed();
        assertThat(codes(probe.settled())).containsExactly(OperatorWarningCode.WORKFLOW_ESCALATED, OperatorWarningCode.WORKFLOW_RESOLVED);
    }

    /// fixD: two reseeds of a terminal marked run that was never resolved raise one RESOLVED, and a non-owner raises none.
    @Test
    void announcer_reseed_twiceForAnUnresolvedTerminal_raisesOnce_andNotOnANonOwner() {
        var index = WorkflowRunIndex.workflowRunIndex();
        var owner = new AtomicBoolean(false);
        var announcer = WorkflowRunIndex.announcer(index, owner::get, probe.sink());

        index.onTransition(announcer);
        index.restoreFrom(() -> Map.of(new WorkflowRunKey("wf-a"), run("wf-a", 10L).in(WorkflowState.ESCALATED, "i").in(WorkflowState.DONE, "")));
        announcer.reseed();
        assertThat(probe.settled()).isEmpty();
        owner.set(true);
        announcer.reseed();
        announcer.reseed();
        assertThat(codes(probe.settled())).containsExactly(OperatorWarningCode.WORKFLOW_RESOLVED);
    }

    /// fixD: a run resumed after escalating is active again with its incident still open (the mark is kept): it is seeded as open, so
    /// the terminal commit that follows the takeover resolves it.
    @Test
    void announcer_reseed_resumedMarkedRun_isSeededOpen_andResolvedByTheTerminal() {
        var index = WorkflowRunIndex.workflowRunIndex();
        var resumed = run("wf-a", 10L).in(WorkflowState.ESCALATED, "i").resumed(99_000L);
        var announcer = WorkflowRunIndex.announcer(index, () -> true, probe.sink());

        index.onTransition(announcer);
        index.restoreFrom(() -> Map.of(new WorkflowRunKey("wf-a"), resumed));
        announcer.reseed();
        index.put(resumed.in(WorkflowState.DONE, ""));
        assertThat(codes(probe.settled())).containsExactly(OperatorWarningCode.WORKFLOW_RESOLVED);
    }

    /// End to end over the real store route: the ledger's resume commit of an escalated run raises RESUMED.
    @Test
    void announcer_ledgerResumeCommit_viaTheStoreRoute_raisesResumed() {
        var ledger = cluster.ledger(CORE_1);
        var running = run("wf-a", 10L);
        var escalated = running.in(WorkflowState.ESCALATED, "incident");

        cluster.announceTo(() -> true, probe.sink());
        ledger.create(running).await().unwrap();
        assertThat(ledger.commit(running, escalated).await().unwrap()).isTrue();
        assertThat(ledger.commit(escalated, escalated.resumed(99_000L)).await().unwrap()).isTrue();
        assertThat(codes(probe.settled())).containsExactly(OperatorWarningCode.WORKFLOW_ESCALATED, OperatorWarningCode.WORKFLOW_RESUMED);
    }
}
