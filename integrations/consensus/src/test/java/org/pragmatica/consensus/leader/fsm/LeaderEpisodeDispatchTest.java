/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.consensus.leader.fsm;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.leader.LeaderManager.LeaderProposalHandler;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderCommitted;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.OwnerLifetime;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;
import org.pragmatica.statemachine.TransitionRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// #3846 lane C1, INV-E: the handle's own dispatch (`EpisodeHandle#dispatch`), its liveness read (`isCurrent`), the
/// handle a handler mints from its request (`handlerEpisode`) and the public read of `current()`. Deterministic rows
/// (latches, no sleeps); the FSM is driven the way production drives it, through the drainer ([OnDrainer]).
///
/// The request of `handlerEpisode` is a test double that answers `lifetime()` with the entry the Fsm holds: a real
/// [TransitionRequest] exists only inside a dispatch frame, and no handler mints from it before lane St. The frame
/// refusal itself is lane S's (`TransitionRequestFrameTest`).
class LeaderEpisodeDispatchTest {
    private static final NodeId SELF = new NodeId("node-1");
    private static final NodeId PEER = new NodeId("node-2");
    private static final NodeId PEER_B = new NodeId("node-3");
    private static final List<NodeId> CORES = List.of(SELF, PEER, PEER_B);
    private static final TimeSpan LONG = TimeSpan.timeSpan(60).seconds();
    private static final long FOREIGN_WITHIN_SECONDS = 10;

    private FsmTestHarness<LeaderElectionState, ClusterFsmEvent> harness;
    private LeaderElectionContext ctx;
    private final AtomicReference<RuntimeException> foreignRefusal = new AtomicReference<>();

    @AfterEach
    void stopTimers() {
        if (harness != null) {
            OnDrainer.dispatch(ctx, new ClusterFsmEvent.Shutdown());
        }
    }

    // ---- EpisodeHandle.dispatch ----
    @Test
    void dispatch_ofTheLiveEntry_onTheDrainer_reachesTheFsm() {
        start();
        var live = handleOfTheCurrentEntry();
        var before = harness.state();

        OnDrainer.run(ctx, () -> live.dispatch(new ClusterFsmEvent.QuorumEstablished()));

        assertThat(harness.state()).as("a live episode's dispatch did not reach the FSM").isNotSameAs(before);
        assertThat(ctx.refusedDispatchesForTest()).as("a live dispatch was counted as refused").isZero();
    }

    @Test
    void dispatch_ofAnExitedEntry_onTheDrainer_isRefusedAndCounted() {
        var stale = exitQuorumWaiting();
        var exited = harness.state();

        OnDrainer.run(ctx, () -> stale.dispatch(new ClusterFsmEvent.QuorumEstablished()));

        assertThat(harness.state()).as("an event of a left episode reached the state that replaced it").isSameAs(exited);
        assertThat(ctx.refusedDispatchesForTest()).as("the refusal is counted").isEqualTo(1);
        assertThat(ctx.confinementBreachesForTest()).as("an on-drainer refusal is not a confinement breach").isZero();
    }

    @Test
    void dispatch_offTheDrainer_onALiveHandle_throwsThroughTheConfinementBreach() {
        start();
        var live = handleOfTheCurrentEntry();
        var before = harness.state();

        onForeignThread(() -> live.dispatch(new ClusterFsmEvent.QuorumEstablished()));

        assertThat(foreignRefusal.get()).as("a foreign dispatch was not refused by a throw")
                                        .isInstanceOf(IllegalStateException.class)
                                        .hasMessageContaining("#3605 confinement rule");
        assertThat(ctx.confinementBreachesForTest()).as("the breach is counted").isEqualTo(1);
        assertThat(harness.state()).as("a foreign thread moved the FSM").isSameAs(before);
        assertThat(ctx.refusedDispatchesForTest()).as("a breach is not a liveness refusal").isZero();
    }

    @Test
    void dispatch_offTheDrainer_onADeadHandle_stillThrows_neverASilentRefusal() {
        var stale = exitQuorumWaiting();

        onForeignThread(() -> stale.dispatch(new ClusterFsmEvent.QuorumEstablished()));

        assertThat(foreignRefusal.get()).as("a foreign dispatch on a dead handle was absorbed as a refusal")
                                        .isInstanceOf(IllegalStateException.class);
        assertThat(ctx.confinementBreachesForTest()).as("the breach is counted").isEqualTo(1);
        assertThat(ctx.refusedDispatchesForTest()).as("confinement is checked before liveness").isZero();
    }

    // ---- EpisodeHandle.isCurrent ----
    @Test
    void isCurrent_isTrueWhileTheEntryIsCurrent_andFalseAfterItEnds() {
        start();
        OnDrainer.dispatch(harness, new ClusterFsmEvent.QuorumEstablished());
        var handle = handleOfTheCurrentEntry();

        assertThat(handle.isCurrent()).as("the current entry's handle is not current").isTrue();

        OnDrainer.dispatch(harness, new ClusterFsmEvent.QuorumDisappeared());

        assertThat(handle.isCurrent()).as("a handle of a left entry is still current").isFalse();
    }

    @Test
    void isCurrent_isFalseForAHandleOverAnEndedLifetime_andForAnUnenteredState() {
        start();
        var ended = OwnerLifetime.ownerLifetime();

        ended.end();

        assertThat(ctx.entryEpisode(ended).isCurrent()).as("a handle over an ended lifetime is current").isFalse();
        assertThat(ctx.unentered().isCurrent()).as("an unentered state's handle is current").isFalse();
    }

    // ---- handlerEpisode ----
    @Test
    void handlerEpisode_ofARequestOfTheCurrentEntry_isCurrent_andDiesWithThatEntry() {
        start();
        OnDrainer.dispatch(harness, new ClusterFsmEvent.QuorumEstablished());
        var episode = ctx.handlerEpisode(requestOver(harness.fsm().entered().lifetime()));

        assertThat(episode.isCurrent()).as("the handler's episode is not current inside its own entry").isTrue();

        OnDrainer.dispatch(harness, new ClusterFsmEvent.QuorumDisappeared());
        var after = harness.state();
        OnDrainer.run(ctx, () -> episode.dispatch(new ClusterFsmEvent.QuorumEstablished()));

        assertThat(episode.isCurrent()).as("the handler's episode outlived its own transition").isFalse();
        assertThat(harness.state()).as("a handler dispatched after its own transition left the entry").isSameAs(after);
        assertThat(ctx.refusedDispatchesForTest()).as("the late dispatch is counted").isEqualTo(1);
    }

    @Test
    void handlerEpisode_ofARequestOverAnotherEntry_isNotTheCurrentEntry() {
        start();
        var request = requestOver(OwnerLifetime.ownerLifetime());

        assertThat(ctx.handlerEpisode(request).isCurrent()).as("a request over a lifetime the Fsm does not hold is current")
                                                           .isFalse();
    }

    @Test
    void handlerEpisode_ofANullRequest_isNeverLive_andCounted() {
        start();

        var episode = ctx.handlerEpisode(null);
        var before = harness.state();

        OnDrainer.run(ctx, () -> episode.dispatch(new ClusterFsmEvent.QuorumEstablished()));

        assertThat(ctx.refusedNullIdentitiesForTest()).as("the missing request is the named, counted check").isEqualTo(1);
        assertThat(episode.isCurrent()).as("a missing request yields a live handle").isFalse();
        assertThat(harness.state()).as("a missing request dispatched").isSameAs(before);
        assertThat(ctx.refusedDispatchesForTest()).as("the dispatch is refused and counted").isEqualTo(1);
    }

    // ---- current() ----
    @Test
    void current_isPublic_andReadsTheFsmState() throws NoSuchMethodException {
        start();

        var method = LeaderElectionContext.class.getDeclaredMethod("current");

        assertThat(Modifier.isPublic(method.getModifiers())).as("current() must be callable outside leader.fsm").isTrue();
        assertThat(ctx.current()).as("current() does not read the FSM").isSameAs(harness.state());
    }

    // ---- fixture ----
    @SuppressWarnings("unchecked")
    private static TransitionRequest<LeaderElectionState, ClusterFsmEvent> requestOver(OwnerLifetime lifetime) {
        var request = (TransitionRequest<LeaderElectionState, ClusterFsmEvent>) mock(TransitionRequest.class);

        when(request.lifetime()).thenReturn(lifetime);

        return request;
    }

    /// The handle of the quorum-waiting entry, taken before the FSM leaves it: the stale handle of a left episode.
    private LeaderElectionContext.EpisodeHandle exitQuorumWaiting() {
        start();
        OnDrainer.dispatch(harness, new ClusterFsmEvent.QuorumEstablished());
        var stale = handleOfTheCurrentEntry();

        OnDrainer.dispatch(harness, new ClusterFsmEvent.QuorumDisappeared());

        return stale;
    }

    private LeaderElectionContext.EpisodeHandle handleOfTheCurrentEntry() {
        return ctx.entryEpisode(harness.fsm()
                                       .entered()
                                       .lifetime());
    }

    private void start() {
        harness = FsmTestHarness.<LeaderElectionState, ClusterFsmEvent> harness("episode-dispatch", this::contextOver);
        ctx = harness.state()
                     .ctx();
    }

    private LeaderElectionState contextOver(Fsm<LeaderElectionState, ClusterFsmEvent> fsm) {
        Option<LeaderProposalHandler> noHandler = Option.none();
        var built = new LeaderElectionContext(fsm,
                                              SELF,
                                              noHandler,
                                              CORES,
                                              MessageRouter.mutable(),
                                              LONG,
                                              LONG,
                                              TimeSpan.timeSpan(0)
                                                      .millis(),
                                              LONG,
                                              LeaderElectionContext.DEFAULT_STUCK_ELECTION_THRESHOLD,
                                              () -> 0.0,
                                              LeaderElectionContext.DEFAULT_RABIA_TERM_SUPPLIER,
                                              () -> false,
                                              Option::<LeaderCommitted> none,
                                              LONG,
                                              LONG,
                                              LONG,
                                              _ -> true,
                                              LONG);

        built.installKvSyncPending(() -> false);

        return built.dormant();
    }

    /// Runs `body` on a thread that holds no dispatch frame and no drainer, and returns after it finished (a latch,
    /// bounded: never a sleep). The throw of `body` is the observation here: it is kept in `foreignRefusal`.
    private void onForeignThread(Runnable body) {
        var finished = new CountDownLatch(1);
        var thread = new Thread(() -> runThenFinish(body, finished), "foreign-callback");

        thread.start();
        awaitFinished(finished);
    }

    private void runThenFinish(Runnable body, CountDownLatch finished) {
        try {
            body.run();
        } catch (RuntimeException thrown) {
            foreignRefusal.set(thrown);
        } finally {
            finished.countDown();
        }
    }

    private static void awaitFinished(CountDownLatch finished) {
        try {
            assertThat(finished.await(FOREIGN_WITHIN_SECONDS, TimeUnit.SECONDS)).as("the foreign thread did not finish")
                                                                                .isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread()
                  .interrupt();
            throw new AssertionError("interrupted while waiting for the foreign thread", e);
        }
    }
}
