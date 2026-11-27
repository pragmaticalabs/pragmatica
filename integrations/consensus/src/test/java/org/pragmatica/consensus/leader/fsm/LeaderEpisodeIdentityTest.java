/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.consensus.leader.fsm;

import static org.pragmatica.consensus.leader.LeaderInboundForTest.inbound;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.leader.LeaderManager.LeaderProposalHandler;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.ElectionTick;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.KvSyncGraceTimeout;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderCommitted;
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
/// re-entry is the one step production cannot reach, so the harness requests it the way a handler would
/// ([FsmTestHarness#reenter]). WHY production order cannot reach it: every record that carries a timer is built per
/// transition (`LeaderElectionContext#quorumWaiting`, `awaitingKvSync`, `electing`, `reElecting`, `Led.fresh`,
/// `new Passive`), and the only transitions to an existing record go to the timer-free singletons `dormant`,
/// `quorumLost` and `stopped`. That fact is pinned by `productionReentry_ofATimedState_buildsANewRecord`: when a
/// production transition re-enters a saved record, that test goes red and the saved-record tests become production
/// tests (replace `harness.reenter` by that transition).
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
    private final AtomicReference<TimeSpan> observation = new AtomicReference<>(LONG);
    private final AtomicInteger kvReads = new AtomicInteger();
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
        assertThat((Object) tickFutureOfCurrent()).as("the exited episode's failure rescheduled the re-entered episode's tick")
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
        assertThat((Object) tickFutureOfCurrent()).as("the exited entry's failure rescheduled the new entry's tick")
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
        assertThat((Object) tickFutureOfCurrent()).isNotSameAs(tickBefore);
    }

    // ---- input 3, tick half: a timed state re-entered with a saved record ----
    @Test
    void graceTick_ofAnExitedEntry_leavesTheSavedRecordItsFullGrace() throws InterruptedException {
        grace.set(TimeSpan.timeSpan(500).millis());
        start();
        enterAwaitingKvSync();
        var saved = harness.state();

        staleTickBehind(graceTimerOf(saved), () -> harness.reenter(saved));
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void firstTick_ofAnExitedEntry_submitsNoProposalForTheSavedRecord(boolean reElecting) throws InterruptedException {
        firstTick.set(TimeSpan.timeSpan(500).millis());
        start();
        enterElection(reElecting);
        var saved = harness.state();

        staleTickBehind(tickFutureOfCurrent(), () -> harness.reenter(saved));
        assertThat(proposals).as("the old entry's first tick proposed for the saved record's new entry").hasValue(0);
        assertThat(harness.state()).isSameAs(saved);
    }

    // ---- input 3, bound then re-entered (round 1 n2): a record whose first entry already bound ----
    /// The peer observation of the first entry runs (it binds the record to that entry), then the record is left and
    /// entered again with an observation of the first entry still queued behind the exit. The queued observation must be
    /// refused in the new entry: it reads the KV (the counted effect) only if the slot rebound to the new entry.
    @Test
    void observation_ofAnExitedFirstEntry_ofABoundRecord_actsNotInTheNewEntry() throws InterruptedException {
        observation.set(TimeSpan.timeSpan(50).millis());
        start();
        enterElection(false);
        var saved = harness.state();
        var readsAtEntry = kvReads.get();

        Awaitility.await("instrument: an observation ran in the first entry, which bound the record to it")
                  .atMost(WITHIN)
                  .until(() -> kvReads.get() > readsAtEntry);
        var readsAfterReentry = new AtomicInteger();
        var parked = parkExit(() -> reenterAndCount(saved, readsAfterReentry));

        Awaitility.await("instrument: an observation of the first entry is queued behind the exit task")
                  .atMost(WITHIN)
                  .until(() -> ctx.pendingSerialTasksForTest() >= 1);
        parked.release();
        OnDrainer.drained(ctx);
        assertThat(harness.state()).as("instrument: the saved record was entered again").isSameAs(saved);
        assertThat(kvReads.get()).as("an observation of the exited first entry acted in the saved record's new entry")
                  .isEqualTo(readsAfterReentry.get());
    }

    private void reenterAndCount(LeaderElectionState saved, AtomicInteger readsAfterReentry) {
        harness.reenter(saved);
        readsAfterReentry.set(kvReads.get());
    }

    // ---- g08: the Fsm holds no entry yet ----
    /// The Fsm's factory runs before the Fsm holds any entry (`Fsm#entered` is null then). An arm in it gets an identity
    /// that is never live, and stays never live after the Fsm holds its first entry, though the owner IS that entry.
    @Test
    void arm_insideTheFsmFactory_whereNoEntryExists_isNeverLive() {
        var armed = new AtomicReference<Runnable>();
        var ran = new AtomicInteger();

        harness = FsmTestHarness.<LeaderElectionState, ClusterFsmEvent> harness("episode-identity",
                                                                                 fsm -> armInFactory(fsm, armed, ran));
        ctx = harness.state().ctx();

        armed.get().run();
        OnDrainer.drained(ctx);
        assertThat(ran).as("an arm made before the Fsm held an entry ran").hasValue(0);
    }

    private LeaderElectionState armInFactory(Fsm<LeaderElectionState, ClusterFsmEvent> fsm,
                                             AtomicReference<Runnable> armed,
                                             AtomicInteger ran) {
        var dormant = contextOver(fsm);

        armed.set(dormant.ctx().serializedFor(dormant, ran::incrementAndGet));

        return dormant;
    }

    // ---- (f): production order never re-enters a record that carries a timer ----
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void productionReentry_ofATimedState_buildsANewRecord(boolean reElecting) {
        start();
        enterElection(reElecting);
        var saved = harness.state();

        OnDrainer.run(ctx, () -> reenterElection(reElecting));
        assertThat(harness.state()).as("a production transition entered the saved record again: see the class doc")
                  .isNotSameAs(saved);
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
                                              this::readKv,
                                              grace.get(),
                                              observation.get(),
                                              LONG,
                                              _ -> true,
                                              LONG);

        built.installKvSyncPending(() -> false);

        return built.dormant();
    }

    private Option<LeaderCommitted> readKv() {
        kvReads.incrementAndGet();

        return Option.none();
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
        ctx.submit(inbound(), new ClusterFsmEvent.QuorumDisappeared());
        ctx.submit(inbound(), new ClusterFsmEvent.QuorumEstablished());
        ctx.submit(inbound(), new ClusterFsmEvent.NodeAdded(SELF, CORES, seq()));
        ctx.submit(inbound(), new KvSyncGraceTimeout());
        assertThat(harness.state()).isInstanceOf(reElecting
                                                 ? LeaderElectionState.ReElecting.class
                                                 : LeaderElectionState.Electing.class);
    }

    private void proposeInCurrentEpisode() {
        var before = proposals.get();

        OnDrainer.dispatch(harness, new ElectionTick());
        assertThat(proposals.get()).as("instrument: the tick proposed").isEqualTo(before + 1);
    }

    private ScheduledFuture<?> tickFutureOfCurrent() {
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

    /// Holds the drainer, queues `exitAndReenter` as one serial task, waits until `timer` (the ONE timer under test, the
    /// first tick or the grace, never a count of queued tasks: the entry arms a peer observation as well) has fired into
    /// the queue behind it, then releases and waits for the drainer to run everything queued. Two instruments:
    /// the timer had not fired when the exit task was queued, so the exit task is ahead of it; and the timer is done
    /// only after the scheduler handed it to the serial executor, so it is queued behind the exit task.
    private void staleTickBehind(ScheduledFuture<?> timer, Runnable exitAndReenter) throws InterruptedException {
        var held = LeaderEntryPointAtomicityTest.holdDrainer(ctx);

        ctx.serially(inbound(), exitAndReenter);
        assertThat(timer.isDone()).as("instrument: the timer fired before the exit task was queued: raise its delay")
                  .isFalse();
        Awaitility.await("instrument: the timer under test fired behind the exit task")
                  .atMost(WITHIN)
                  .until(timer::isDone);
        held.release();
        OnDrainer.drained(ctx);
    }

    private static ScheduledFuture<?> graceTimerOf(LeaderElectionState waiting) {
        return switch (waiting) {
            case LeaderElectionState.AwaitingKvSync kvSync -> kvSync.graceTimeoutFuture().get();
            default -> throw new AssertionError("not waiting for the KV sync: " + waiting);
        };
    }

    /// Queues `exitAndReenter` as ONE serial task on its own thread that parks at its start until released: every timer
    /// task that fires while it is parked queues behind it (a task queued before it ran before it started).
    private Parked parkExit(Runnable exitAndReenter) throws InterruptedException {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var thread = Thread.ofPlatform().start(() -> ctx.serially(inbound(), () -> parkThen(started, release, exitAndReenter)));

        assertThat(started.await(WITHIN.toSeconds(), TimeUnit.SECONDS)).as("instrument: the exit task started").isTrue();

        return new Parked(thread, release);
    }

    private static void parkThen(CountDownLatch started, CountDownLatch release, Runnable exitAndReenter) {
        started.countDown();
        awaitRelease(release);
        exitAndReenter.run();
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            assertThat(release.await(WITHIN.toSeconds(), TimeUnit.SECONDS)).as("instrument: the parked task was released").isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record Parked(Thread thread, CountDownLatch latch) {
        void release() throws InterruptedException {
            latch.countDown();
            thread.join(10_000);
        }
    }
}
