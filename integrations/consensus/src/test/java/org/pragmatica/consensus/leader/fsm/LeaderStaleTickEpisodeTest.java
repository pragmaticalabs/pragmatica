/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.consensus.leader.fsm;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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


/// #3809: a timer tick acts only on the episode that armed it. A tick whose timer fired before its episode exited sits in
/// the node's serial queue behind the exit and the re-entry; `cancel(false)` does not remove it. Without the episode
/// check it is handled by the NEW episode of the same state, which then acts on a clock it never armed (a cut grace
/// window, an early poll, a stale proposal timeout, a lease verdict counted on the old tenure).
///
/// Production order, no state built by hand: every event goes through the real confined dispatch. Each stale case holds
/// the drainer, queues the exit-and-re-entry as one serial task, lets the old episode's timer fire into the queue behind
/// it (the instrument: at least two tasks pending), and releases. The assertion reads the state that results. Each case
/// has its control: the same tick, the episode not exited, still acts.
class LeaderStaleTickEpisodeTest {
    private static final NodeId SELF = new NodeId("node-1");
    private static final NodeId PEER = new NodeId("node-2");
    private static final NodeId PEER_B = new NodeId("node-3");
    private static final List<NodeId> CORES = List.of(SELF, PEER, PEER_B);
    private static final TimeSpan LONG = TimeSpan.timeSpan(60).seconds();
    private static final Duration WITHIN = Duration.ofSeconds(10);

    /// The delays under test: the grace, the first election tick, the retry delay, the proposal timeout, the peer
    /// observation interval, the lease check interval and its warm-up. Everything not under test is `LONG`.
    private record Timings(TimeSpan grace,
                           TimeSpan firstTick,
                           TimeSpan retry,
                           TimeSpan proposalTimeout,
                           TimeSpan observation,
                           TimeSpan leaseInterval,
                           TimeSpan leaseWarmup) {
        static Timings only(TimeSpan grace,
                            TimeSpan firstTick,
                            TimeSpan retry,
                            TimeSpan proposalTimeout,
                            TimeSpan observation,
                            TimeSpan leaseInterval) {
            return new Timings(grace, firstTick, retry, proposalTimeout, observation, leaseInterval, leaseInterval);
        }
    }

    private final AtomicBoolean consensusReady = new AtomicBoolean(true);
    private final AtomicBoolean kvSyncPending = new AtomicBoolean(false);
    private final AtomicBoolean pingFresh = new AtomicBoolean(true);
    private final AtomicInteger proposals = new AtomicInteger();
    private final AtomicReference<Option<LeaderCommitted>> kv = new AtomicReference<>(Option.none());
    private final AtomicReference<Promise<Unit>> proposalResult = new AtomicReference<>(Promise.promise());
    private final AtomicReference<LeaderElectionState> reentered = new AtomicReference<>();
    private final AtomicReference<Object> captured = new AtomicReference<>();
    private FsmTestHarness<LeaderElectionState, ClusterFsmEvent> harness;
    private LeaderElectionContext ctx;

    @AfterEach
    void stopTimers() {
        if (harness != null) {
            OnDrainer.dispatch(ctx, new ClusterFsmEvent.Shutdown());
        }
    }

    // ---- grace (rows 1 and 2) ----
    @Test
    void graceTick_ofTheCurrentEpisode_fallsThroughToElecting() {
        start(timings(ms(300), LONG, LONG, LONG, LONG, LONG));
        enterAwaitingKvSync();
        awaitState(LeaderElectionState.Electing.class);
    }

    @Test
    void graceTick_ofAnExitedEpisode_leavesTheReenteredEpisodeItsFullGrace() throws InterruptedException {
        start(timings(ms(500), LONG, LONG, LONG, LONG, LONG));
        enterAwaitingKvSync();
        var episodeOne = harness.state();

        staleTickBehind(this::reenterAwaitingKvSync);
        assertThat(episodeOne).isInstanceOf(LeaderElectionState.AwaitingKvSync.class);
        assertThat(harness.state()).as("the re-entered AwaitingKvSync still waits out its own grace: the old episode's tick was dropped")
                  .isSameAs(reentered.get());
    }

    @Test
    void rearmedGraceTick_ofTheCurrentEpisode_fallsThroughOnceTheSyncEnds() {
        start(timings(ms(300), LONG, LONG, LONG, LONG, LONG));
        kvSyncPending.set(true);
        enterAwaitingKvSync();
        var waiting = (LeaderElectionState.AwaitingKvSync) harness.state();
        var armedFirst = waiting.graceTimeoutFuture().get();

        Awaitility.await().atMost(WITHIN).until(() -> waiting.graceTimeoutFuture()
                                                             .get() != armedFirst);
        kvSyncPending.set(false);
        awaitState(LeaderElectionState.Electing.class);
    }

    @Test
    void rearmedGraceTick_ofAnExitedEpisode_leavesTheReenteredEpisodeItsFullGrace() throws InterruptedException {
        start(timings(ms(500), LONG, LONG, LONG, LONG, LONG));
        kvSyncPending.set(true);
        enterAwaitingKvSync();
        var waiting = (LeaderElectionState.AwaitingKvSync) harness.state();
        var armedFirst = waiting.graceTimeoutFuture().get();

        Awaitility.await().atMost(WITHIN).until(() -> waiting.graceTimeoutFuture()
                                                             .get() != armedFirst);
        staleTickBehind(this::reenterAwaitingKvSyncWithTheSyncEnded);
        assertThat(harness.state()).as("the second grace of the exited episode did not reach the re-entered one")
                  .isSameAs(reentered.get());
    }

    // ---- QuorumWaiting poll (row 3) ----
    @Test
    void pollTick_ofTheCurrentEpisode_advancesOnceConsensusIsReady() {
        start(quiet());
        consensusReady.set(false);
        OnDrainer.dispatch(harness, new ClusterFsmEvent.QuorumEstablished());
        assertThat(harness.state()).isInstanceOf(LeaderElectionState.QuorumWaiting.class);
        consensusReady.set(true);
        awaitState(LeaderElectionState.AwaitingKvSync.class);
    }

    @Test
    void pollTick_ofAnExitedEpisode_doesNotAdvanceTheReenteredEpisode() throws InterruptedException {
        start(quiet());
        consensusReady.set(false);
        OnDrainer.dispatch(harness, new ClusterFsmEvent.QuorumEstablished());
        assertThat(harness.state()).isInstanceOf(LeaderElectionState.QuorumWaiting.class);
        staleTickBehind(this::reenterQuorumWaitingThenReady);
        assertThat(harness.state()).as("the re-entered QuorumWaiting advances on its own poll, not on the exited episode's")
                  .isSameAs(reentered.get());
    }

    // ---- election first tick (row 4), Electing and ReElecting ----
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void firstTick_ofTheCurrentEpisode_submitsTheProposal(boolean reElecting) {
        start(timings(LONG, ms(300), LONG, LONG, LONG, LONG));
        enterElection(reElecting);
        Awaitility.await().atMost(WITHIN).until(() -> proposals.get() == 1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void firstTick_ofAnExitedEpisode_submitsNoProposalForTheReenteredOne(boolean reElecting) throws InterruptedException {
        start(timings(LONG, ms(500), LONG, LONG, LONG, LONG));
        enterElection(reElecting);
        staleTickBehind(() -> reenterElection(reElecting));
        assertThat(proposals).as("the exited episode's first tick proposed on behalf of the re-entered episode")
                  .hasValue(0);
        assertThat(harness.state()).isSameAs(reentered.get());
    }

    // ---- election retry tick (row 5) ----
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retryTick_ofTheCurrentEpisode_proposesAgain(boolean reElecting) {
        start(timings(LONG, ms(200), ms(200), LONG, LONG, LONG));
        proposalResult.set(Causes.cause("proposal failed").promise());
        enterElection(reElecting);
        Awaitility.await().atMost(WITHIN).until(() -> proposals.get() >= 2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retryTick_ofAnExitedEpisode_submitsNoProposalForTheReenteredOne(boolean reElecting) throws InterruptedException {
        start(timings(LONG, ms(300), ms(500), LONG, LONG, LONG));
        proposalResult.set(Causes.cause("proposal failed").promise());
        enterElection(reElecting);
        Awaitility.await().atMost(WITHIN).until(() -> proposals.get() == 1);
        staleTickBehind(() -> reenterElection(reElecting));
        assertThat(proposals).as("the exited episode's retry tick proposed on behalf of the re-entered episode")
                  .hasValue(1);
        assertThat(harness.state()).isSameAs(reentered.get());
    }

    // ---- peer-observation tick (row 6), Electing and ReElecting ----
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void observationTick_ofTheCurrentEpisode_adoptsTheCommittedLeader(boolean reElecting) {
        start(timings(LONG, LONG, LONG, LONG, ms(200), LONG));
        enterElection(reElecting);
        OnDrainer.run(ctx, () -> ctx.observeViewSequence(5L));
        kv.set(Option.some(LeaderCommitted.leaderCommitted(PEER, 5L)));
        awaitState(LeaderElectionState.Led.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void observationTick_ofAnExitedEpisode_adoptsNothingIntoTheReenteredOne(boolean reElecting) throws InterruptedException {
        start(timings(LONG, LONG, LONG, LONG, ms(400), LONG));
        enterElection(reElecting);
        staleTickBehind(() -> reenterElectionThenKvLeader(reElecting));
        assertThat(harness.state()).as("the re-entered election adopts the KV leader on its own observation tick, not on the exited one's")
                  .isSameAs(reentered.get());
    }

    // ---- lease check (row 7) ----
    @Test
    void leaseTick_ofTheCurrentEpisode_reElectsWhenTheLeaderStaysSilent() {
        start(timings(LONG, LONG, LONG, LONG, LONG, ms(100)));
        pingFresh.set(false);
        enterLed(1L);
        awaitState(LeaderElectionState.ReElecting.class);
    }

    @Test
    void leaseTick_ofAnExitedEpisode_isNotCountedAgainstTheReenteredTenure() throws InterruptedException {
        start(timings(LONG, LONG, LONG, LONG, LONG, ms(300)));
        pingFresh.set(false);
        enterLed(1L);
        var tenureOne = (LeaderElectionState.Led) harness.state();

        staleTickBehind(this::reenterLedAfterOneSilenceShortOfTheThreshold, tenureOne);
        assertThat(harness.state()).as("the old tenure's lease check, one short of the threshold, must not re-elect away from the new tenure")
                  .isSameAs(reentered.get());
    }

    // ---- proposal timeout (row 8) ----
    @Test
    void proposalTimeout_ofTheCurrentEpisode_retriesTheProposal() {
        start(timings(LONG, ms(200), ms(200), ms(200), LONG, LONG));
        enterElection(false);
        Awaitility.await().atMost(WITHIN).until(() -> proposals.get() >= 2);
    }

    @Test
    void proposalTimeout_ofAnExitedEpisode_schedulesNoRetryForTheReenteredOne() throws InterruptedException {
        start(timings(LONG, ms(200), LONG, ms(400), LONG, LONG));
        enterElection(false);
        Awaitility.await().atMost(WITHIN).until(() -> proposals.get() == 1);
        staleTickBehind(this::reenterElectionProposing);
        var episode = (LeaderElectionState.Electing) harness.state();

        assertThat(episode).isSameAs(reentered.get());
        assertThat(ctx.proposalInFlight()).as("the exited episode's timeout failed the re-entered episode's proposal")
                  .isTrue();
        assertThat((Object) episode.tickFuture().get()).as("the exited episode's timeout rescheduled the re-entered episode's tick")
                  .isSameAs(captured.get());
    }

    // ---- the fixture ----
    private Timings quiet() {
        return timings(LONG, LONG, LONG, LONG, LONG, LONG);
    }

    private static Timings timings(TimeSpan grace,
                                   TimeSpan firstTick,
                                   TimeSpan retry,
                                   TimeSpan proposalTimeout,
                                   TimeSpan observation,
                                   TimeSpan leaseInterval) {
        return Timings.only(grace, firstTick, retry, proposalTimeout, observation, leaseInterval);
    }

    private static TimeSpan ms(long millis) {
        return TimeSpan.timeSpan(millis).millis();
    }

    private void start(Timings timings) {
        harness = FsmTestHarness.<LeaderElectionState, ClusterFsmEvent> harness("stale-tick-episode",
                                                                                fsm -> contextOver(fsm, timings));
        ctx = harness.state().ctx();
    }

    private LeaderElectionState contextOver(Fsm<LeaderElectionState, ClusterFsmEvent> fsm, Timings timings) {
        LeaderProposalHandler handler = (_, _) -> propose();
        var built = new LeaderElectionContext(fsm,
                                              SELF,
                                              Option.some(handler),
                                              CORES,
                                              MessageRouter.mutable(),
                                              timings.retry(),
                                              timings.firstTick(),
                                              ms(0),
                                              timings.proposalTimeout(),
                                              LeaderElectionContext.DEFAULT_STUCK_ELECTION_THRESHOLD,
                                              () -> 0.0,
                                              LeaderElectionContext.DEFAULT_RABIA_TERM_SUPPLIER,
                                              consensusReady::get,
                                              kv::get,
                                              timings.grace(),
                                              timings.observation(),
                                              timings.leaseInterval(),
                                              _ -> pingFresh.get(),
                                              timings.leaseWarmup());

        built.installKvSyncPending(kvSyncPending::get);

        return built.dormant();
    }

    private Promise<Unit> propose() {
        proposals.incrementAndGet();

        return proposalResult.get();
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

    private void enterLed(long viewSequence) {
        enterAwaitingKvSync();
        LeaderCommits.commitLeaderOnDrainer(ctx, PEER, viewSequence);
        assertThat(harness.state()).isInstanceOf(LeaderElectionState.Led.class);
    }

    private void awaitState(Class<? extends LeaderElectionState> expected) {
        Awaitility.await().atMost(WITHIN).until(() -> expected.isInstance(harness.state()));
    }

    /// Runs on the drainer: the state is left and entered again, as one serial task.
    private void reenterAwaitingKvSync() {
        ctx.submit(new ClusterFsmEvent.QuorumDisappeared());
        ctx.submit(new ClusterFsmEvent.QuorumEstablished());
        reentered.set(ctx.fsm().current());
    }

    private void reenterAwaitingKvSyncWithTheSyncEnded() {
        reenterAwaitingKvSync();
        kvSyncPending.set(false);
    }

    private void reenterQuorumWaitingThenReady() {
        ctx.submit(new ClusterFsmEvent.QuorumDisappeared());
        ctx.submit(new ClusterFsmEvent.QuorumEstablished());
        reentered.set(ctx.fsm().current());
        consensusReady.set(true);
    }

    private void reenterElection(boolean reElecting) {
        reenterAwaitingKvSync();
        ctx.submit(new KvSyncGraceTimeout());
        reentered.set(ctx.fsm().current());
        assertThat(reentered.get()).isInstanceOf(reElecting
                                                 ? LeaderElectionState.ReElecting.class
                                                 : LeaderElectionState.Electing.class);
    }

    private void reenterElectionThenKvLeader(boolean reElecting) {
        reenterElection(reElecting);
        ctx.observeViewSequence(5L);
        kv.set(Option.some(LeaderCommitted.leaderCommitted(PEER, 5L)));
    }

    private void reenterElectionProposing() {
        reenterElection(false);
        ctx.submit(new ElectionTick());
        captured.set(((LeaderElectionState.Electing) reentered.get()).tickFuture().get());
    }

    private void reenterLedAfterOneSilenceShortOfTheThreshold() {
        ctx.submit(new ClusterFsmEvent.QuorumDisappeared());
        ctx.submit(new ClusterFsmEvent.QuorumEstablished());
        LeaderCommits.commitLeader(ctx, ctx::submit, PEER, 2L);
        reentered.set(ctx.fsm().current());
        assertThat(reentered.get()).isInstanceOf(LeaderElectionState.Led.class);
    }

    private void staleTickBehind(Runnable exitAndReenter) throws InterruptedException {
        staleTickBehind(exitAndReenter, null);
    }

    /// Holds the drainer, queues `exitAndReenter` as one serial task, waits until the old episode's timer has fired into
    /// the queue behind it (the instrument: the exit task and at least one tick are pending), then releases and waits
    /// for the drainer to run everything queued.
    private void staleTickBehind(Runnable exitAndReenter, LeaderElectionState.Led tenureOne) throws InterruptedException {
        var held = LeaderEntryPointAtomicityTest.holdDrainer(ctx);

        if (tenureOne != null) {
            tenureOne.consecutiveSilentChecks().set(LeaderElectionContext.LEADER_SILENCE_THRESHOLD_INTERVALS - 1);
        }

        ctx.serially(exitAndReenter);
        Awaitility.await("instrument: the old episode's timer fired behind the exit task")
                  .atMost(WITHIN)
                  .until(() -> ctx.pendingSerialTasksForTest() >= 2);
        held.release();
        OnDrainer.drained(ctx);
    }
}
