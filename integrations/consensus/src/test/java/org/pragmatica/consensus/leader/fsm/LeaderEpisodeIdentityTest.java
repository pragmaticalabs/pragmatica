/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.consensus.leader.fsm;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.leader.LeaderManager.LeaderProposalHandler;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.ElectionTick;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.KvSyncGraceTimeout;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.pragmatica.consensus.fsm.Observations.seq;
import static org.assertj.core.api.Assertions.assertThat;


/// #3846 (class of #3809): every asynchronous completion that reaches the election FSM carries the identity of the
/// episode that started it, and is dropped unless that episode is still the FSM's current entry.
///
/// Failure inputs of the ticket, one group each:
/// 1. a late proposal failure of an exited episode (the `ProposalSettled` promise half of #1797);
/// 3. a timed state re-entered with a SAVED record: the record reference is the same, the entry is not;
/// 4. the control: a completion of the current episode still acts.
/// Input 2 (a timer armed through an unfenced path) is `LeaderEpisodeEntryPathTest`.
///
/// Production order: every event goes through the real confined dispatch, the exit and re-entry is one serial task,
/// the proposal promises are the ones the handler returned, completed by the test as the network would. The saved-record
/// re-entry is the one step production has no handler for; the harness requests it the way a handler would
/// ([FsmTestHarness#reenter]).
class LeaderEpisodeIdentityTest {
    private static final NodeId SELF = new NodeId("node-1");
    private static final NodeId PEER = new NodeId("node-2");
    private static final NodeId PEER_B = new NodeId("node-3");
    private static final List<NodeId> CORES = List.of(SELF, PEER, PEER_B);
    private static final TimeSpan LONG = TimeSpan.timeSpan(60).seconds();
    private static final Duration WITHIN = Duration.ofSeconds(10);

    private final AtomicInteger proposals = new AtomicInteger();
    private final List<Promise<Unit>> issued = new CopyOnWriteArrayList<>();
    private final AtomicReference<TimeSpan> grace = new AtomicReference<>(LONG);
    private final AtomicReference<TimeSpan> firstTick = new AtomicReference<>(LONG);
    private FsmTestHarness<LeaderElectionState, ClusterFsmEvent> harness;
    private LeaderElectionContext ctx;

    @AfterEach
    void stopTimers() {
        if (harness != null) {
            OnDrainer.dispatch(ctx, new ClusterFsmEvent.Shutdown());
        }
    }

    // ---- input 1: a late proposal failure of an exited episode ----
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lateProposalFailure_ofAnExitedEpisode_leavesTheReenteredEpisodesProposalOutstanding(boolean reElecting) {
        start();
        enterElection(reElecting);
        proposeInCurrentEpisode();
        var episodeOne = issued.get(0);

        OnDrainer.run(ctx, () -> reenterElection(reElecting));
        proposeInCurrentEpisode();
        var tickOfEpisodeTwo = tickFutureOfCurrent();
        var stuckBefore = ctx.stuckElectionCount();

        assertThat(proposals).hasValue(2);
        assertThat(ctx.proposalInFlight()).as("instrument: the re-entered episode's proposal is outstanding").isTrue();
        failDelivered(episodeOne, "late failure of the exited episode");
        assertThat(ctx.proposalInFlight()).as("the exited episode's failure cleared the re-entered episode's in-flight flag")
                  .isTrue();
        assertThat(ctx.stuckElectionCount()).as("the exited episode's failure was counted against the re-entered episode")
                  .isEqualTo(stuckBefore);
        assertThat(tickFutureOfCurrent()).as("the exited episode's failure rescheduled the re-entered episode's tick")
                  .isSameAs(tickOfEpisodeTwo);
    }

    // ---- input 3, promise half: the saved record re-entered ----
    @Test
    void lateProposalFailure_ofAnExitedEntry_leavesTheSavedRecordsNewEntryOutstanding() {
        start();
        enterElection(false);
        proposeInCurrentEpisode();
        var entryOne = issued.get(0);
        var saved = harness.state();

        OnDrainer.run(ctx, () -> harness.reenter(saved));
        assertThat(harness.state()).as("instrument: the same record, a new entry").isSameAs(saved);
        proposeInCurrentEpisode();
        var tickOfEntryTwo = tickFutureOfCurrent();
        var stuckBefore = ctx.stuckElectionCount();

        failDelivered(entryOne, "late failure of the exited entry");
        assertThat(ctx.proposalInFlight()).as("the exited entry's failure cleared the new entry's in-flight flag")
                  .isTrue();
        assertThat(ctx.stuckElectionCount()).isEqualTo(stuckBefore);
        assertThat(tickFutureOfCurrent()).as("the exited entry's failure rescheduled the new entry's tick")
                  .isSameAs(tickOfEntryTwo);
    }

    // ---- input 2, owner half: an owner that is not the FSM's current entry arms nothing that can run ----
    @Test
    void arm_byAStateThatIsNotTheCurrentEntry_runsNothing() {
        start();
        var dormant = harness.state();

        enterAwaitingKvSync();
        var ran = new AtomicInteger();

        ctx.serializedFor(dormant, ran::incrementAndGet).run();
        ctx.serializedFor(harness.state(), ran::incrementAndGet).run();
        OnDrainer.drained(ctx);
        assertThat(ran).as("only the arm by the current entry ran").hasValue(1);
    }

    // ---- input 4: the control ----
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void proposalFailure_ofTheCurrentEpisode_clearsTheFlagAndSchedulesTheRetry(boolean reElecting) {
        start();
        enterElection(reElecting);
        proposeInCurrentEpisode();
        var tickBefore = tickFutureOfCurrent();

        failDelivered(issued.get(0), "failure of the current episode");
        assertThat(ctx.proposalInFlight()).isFalse();
        assertThat(ctx.stuckElectionCount()).isEqualTo(1);
        assertThat(tickFutureOfCurrent()).isNotSameAs(tickBefore);
    }

    // ---- input 3, tick half: a timed state re-entered with a saved record ----
    @Test
    void graceTick_ofAnExitedEntry_leavesTheSavedRecordItsFullGrace() throws InterruptedException {
        grace.set(TimeSpan.timeSpan(500).millis());
        start();
        enterAwaitingKvSync();
        var saved = harness.state();

        staleTickBehind(() -> harness.reenter(saved));
        assertThat(harness.state()).as("the old entry's grace tick fell through the saved record's new entry")
                  .isSameAs(saved);
    }

    @Test
    void graceTick_ofTheNewEntryOfASavedRecord_fallsThroughToElecting() {
        grace.set(TimeSpan.timeSpan(300).millis());
        start();
        enterAwaitingKvSync();
        OnDrainer.run(ctx,
                      () -> harness.reenter(harness.state()));
        awaitState(LeaderElectionState.Electing.class);
    }

    @Test
    void firstTick_ofAnExitedEntry_submitsNoProposalForTheSavedRecord() throws InterruptedException {
        firstTick.set(TimeSpan.timeSpan(500).millis());
        start();
        enterElection(false);
        var saved = harness.state();

        staleTickBehind(() -> harness.reenter(saved));
        assertThat(proposals).as("the old entry's first tick proposed for the saved record's new entry").hasValue(0);
        assertThat(harness.state()).isSameAs(saved);
    }

    // ---- the fixture ----
    private void start() {
        harness = FsmTestHarness.<LeaderElectionState, ClusterFsmEvent> harness("episode-identity", this::contextOver);
        ctx = harness.state().ctx();
    }

    private LeaderElectionState contextOver(Fsm<LeaderElectionState, ClusterFsmEvent> fsm) {
        LeaderProposalHandler handler = (_, _) -> propose();
        var built = new LeaderElectionContext(fsm,
                                              SELF,
                                              Option.some(handler),
                                              CORES,
                                              MessageRouter.mutable(),
                                              LONG,
                                              firstTick.get(),
                                              TimeSpan.timeSpan(0).millis(),
                                              LONG,
                                              LeaderElectionContext.DEFAULT_STUCK_ELECTION_THRESHOLD,
                                              () -> 0.0,
                                              LeaderElectionContext.DEFAULT_RABIA_TERM_SUPPLIER,
                                              () -> true,
                                              Option::none,
                                              grace.get(),
                                              LONG,
                                              LONG,
                                              _ -> true,
                                              LONG);

        built.installKvSyncPending(() -> false);

        return built.dormant();
    }

    private Promise<Unit> propose() {
        var pending = Promise.<Unit> promise();

        proposals.incrementAndGet();
        issued.add(pending);

        return pending;
    }

    private void enterAwaitingKvSync() {
        OnDrainer.dispatch(harness, new ClusterFsmEvent.QuorumEstablished());
        OnDrainer.dispatch(harness, new ClusterFsmEvent.NodeAdded(SELF, CORES, seq()));
        assertThat(harness.state()).isInstanceOf(LeaderElectionState.AwaitingKvSync.class);
    }

    private void enterElection(boolean reElecting) {
        if (reElecting) {
            ctx.markHasEverHadLeader();
        }

        enterAwaitingKvSync();
        OnDrainer.dispatch(harness, new KvSyncGraceTimeout());
        assertThat(harness.state()).isInstanceOf(reElecting
                                                 ? LeaderElectionState.ReElecting.class
                                                 : LeaderElectionState.Electing.class);
    }

    /// Runs on the drainer: the state is left and entered again (a new record), as one serial task.
    private void reenterElection(boolean reElecting) {
        ctx.submit(new ClusterFsmEvent.QuorumDisappeared());
        ctx.submit(new ClusterFsmEvent.QuorumEstablished());
        ctx.submit(new ClusterFsmEvent.NodeAdded(SELF, CORES, seq()));
        ctx.submit(new KvSyncGraceTimeout());
        assertThat(harness.state()).isInstanceOf(reElecting
                                                 ? LeaderElectionState.ReElecting.class
                                                 : LeaderElectionState.Electing.class);
    }

    private void proposeInCurrentEpisode() {
        var before = proposals.get();

        OnDrainer.dispatch(harness, new ElectionTick());
        assertThat(proposals.get()).as("instrument: the tick proposed").isEqualTo(before + 1);
    }

    private Object tickFutureOfCurrent() {
        return switch (harness.state()) {
            case LeaderElectionState.Electing e -> e.tickFuture().get();
            case LeaderElectionState.ReElecting r -> r.tickFuture().get();
            default -> throw new AssertionError("not electing: " + harness.state());
        };
    }

    /// Fails `promise` and returns only after the production callbacks of that failure are queued on the serial executor
    /// and run. A promise runs its callbacks on its own event-handler task, so draining the serial queue right after
    /// `fail` can return before the callback reached it, and the test then reads the state before the event: a control
    /// that reads red, and a late-failure probe that is green without ever having delivered the failure. The
    /// delivery latch is attached after the production callbacks (a promise runs them in attachment order), so it opens
    /// only once they have run.
    private void failDelivered(Promise<Unit> promise, String message) {
        var delivered = new CountDownLatch(1);

        promise.onResultRun(delivered::countDown);
        promise.fail(Causes.cause(message));
        awaitDelivered(delivered);
        OnDrainer.drained(ctx);
    }

    private static void awaitDelivered(CountDownLatch delivered) {
        Awaitility.await("the promise's callbacks ran").atMost(WITHIN).until(() -> delivered.getCount() == 0);
    }

    private void awaitState(Class<? extends LeaderElectionState> expected) {
        Awaitility.await().atMost(WITHIN).until(() -> expected.isInstance(harness.state()));
    }

    /// Holds the drainer, queues `exitAndReenter` as one serial task, waits until the old entry's timer has fired into
    /// the queue behind it (the instrument: the exit task and at least one tick are pending), then releases and waits
    /// for the drainer to run everything queued.
    private void staleTickBehind(Runnable exitAndReenter) throws InterruptedException {
        var held = LeaderEntryPointAtomicityTest.holdDrainer(ctx);

        ctx.serially(exitAndReenter);
        Awaitility.await("instrument: the old entry's timer fired behind the exit task")
                  .atMost(WITHIN)
                  .until(() -> ctx.pendingSerialTasksForTest() >= 2);
        held.release();
        OnDrainer.drained(ctx);
    }
}
