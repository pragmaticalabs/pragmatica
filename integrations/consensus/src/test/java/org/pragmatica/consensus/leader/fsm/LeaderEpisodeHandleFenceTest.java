/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.consensus.leader.fsm;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.leader.LeaderManager.FsmBackedLeaderManager;
import org.pragmatica.consensus.leader.LeaderManager.LeaderProposalHandler;
import org.pragmatica.consensus.leader.fsm.LeaderElectionContext.EpisodeIdentity;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderCommitted;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;
import org.pragmatica.testlogging.LogCapture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.leader.LeaderInboundForTest.inbound;
import static org.pragmatica.lang.utils.OwnerLifetime.ownerLifetime;

/// #3846 round 2: the findings of v3855c (C1-C4) and the stale handle, as deterministic behaviour rows (latches, no
/// sleeps). INVARIANT under test: FSM code reaches the serial executor, `Fsm.dispatch` or `followUp` from outside the
/// current dispatch frame only through a handle of the episode it was given at entry, and that call takes effect only if
/// the episode is still current when it runs; a refusal is a no-op a test observes.
///
/// Each finding is ONE scenario method that returns what happened, and one real row reads it. Lane A wrote the rows with
/// the API of 2867082201 (`serially(inbound, task)`, `serializedFor(owner|identity, task)`, `ctx.followUp`); the SCENARIO
/// (who calls, from which thread, in which episode) is unchanged, only the call spelling moved to the handle API: a
/// receiver ([LeaderElectionContext.EpisodeHandle], [LeaderElectionContext.Inbound]) instead of an argument. Where a
/// finding's old spelling no longer exists (`serially(null, ...)`, `serializedFor(ctx.current(), ...)`), the compile rows
/// of `LeaderEpisodeEntryPathTest` pin its absence and the row here pins the nearest spelling that still compiles.
class LeaderEpisodeHandleFenceTest {
    private static final String SERIAL_LOGGER = "org.pragmatica.consensus.leader.fsm.SerialExecutor";
    private static final NodeId SELF = new NodeId("node-1");
    private static final NodeId PEER = new NodeId("node-2");
    private static final NodeId PEER_B = new NodeId("node-3");
    private static final List<NodeId> CORES = List.of(SELF, PEER, PEER_B);
    private static final TimeSpan LONG = TimeSpan.timeSpan(60).seconds();
    private static final long FOREIGN_WITHIN_SECONDS = 10;

    private FsmTestHarness<LeaderElectionState, ClusterFsmEvent> harness;
    private LeaderElectionContext ctx;
    private final AtomicReference<RuntimeException> foreignRefusal = new AtomicReference<>();
    private LogCapture serialLog;
    private LeaderElectionContext.EpisodeHandle exitedHandle;

    @AfterEach
    void stopTimersAndRestoreLog() {
        if (harness != null) {
            OnDrainer.dispatch(ctx, new ClusterFsmEvent.Shutdown());
        }
        if (serialLog != null) {
            serialLog.close();
        }
    }

    // ---- C1: a foreign thread changes the FSM state through followUp ----
    @Test
    void c1_foreignFollowUp_isRefused() {
        assertThat(foreignFollowUpMovedTheFsm()).as("a foreign thread changed the FSM state through followUp (C1)")
                                                .isFalse();
        assertThat(ctx.refusedFollowUpsForTest()).as("the refusal is a counted, named check, not an event the state ignored")
                                                 .isPositive();
    }

    /// Instrument for C1: the event the foreign thread sends does move the exited FSM when it arrives the production way,
    /// so a "no change" in the real row comes from the refusal and not from an event the state ignores.
    @Test
    void c1_control_theEventMovesTheExitedFsm_onTheDrainer() {
        var exited = exitQuorum();

        OnDrainer.dispatch(harness, new ClusterFsmEvent.QuorumEstablished());
        assertThat(harness.state()).as("instrument: QuorumEstablished moves the exited state").isNotSameAs(exited);
    }

    private boolean foreignFollowUpMovedTheFsm() {
        var exited = exitQuorum();
        var exitedEpisode = exitedHandle;

        onForeignThread(() -> exitedEpisode.followUp(new ClusterFsmEvent.QuorumEstablished()));
        OnDrainer.drained(ctx);

        return harness.state() != exited;
    }

    // ---- C2: null stands in for the capability ----
    /// The inbound receiver is handed out once, to the code that builds the context; a claim made after that is refused,
    /// so FSM code that reaches `claimInbound()` cannot run a task through it.
    @Test
    void c2_aLaterClaimOfTheInboundReceiver_isRefused() {
        assertThat(laterClaimTaskRan()).as("a receiver claimed after the builder's ran a task (C2)").isFalse();
        assertThat(ctx.refusedInboundForTest()).as("the refusal is counted: a named check")
                                               .isPositive();
    }

    private boolean laterClaimTaskRan() {
        var ran = new AtomicInteger();

        start();
        inbound(ctx);
        onForeignThread(() -> ctx.claimInbound()
                                 .serially(ran::incrementAndGet));
        OnDrainer.drained(ctx);

        return ran.get() > 0;
    }

    // ---- C3: identity laundered through ctx.current() ----
    /// Episode N's callback holds N's handle only (the state exposes no way to read another episode's); used in N+1 it
    /// is refused, from a foreign thread as from the drainer.
    @Test
    void c3_launderedIdentity_isRefused() {
        assertThat(launderedTaskRanInTheNextEpisode())
            .as("a callback of episode N armed a task through N's handle, and it ran in episode N+1 (C3)")
            .isFalse();
    }

    private boolean launderedTaskRanInTheNextEpisode() {
        var storedRan = new AtomicInteger();
        var launderedRan = new AtomicInteger();

        start();
        OnDrainer.dispatch(harness, new ClusterFsmEvent.QuorumEstablished());
        var episodeOne = harness.state();
        var handleOne = handleOfTheCurrentEntry();
        var stored = handleOne.serialized(storedRan::incrementAndGet);
        Runnable callbackOfOne = () -> handleOne.serialized(launderedRan::incrementAndGet)
                                                .run();

        OnDrainer.dispatch(harness, new ClusterFsmEvent.QuorumDisappeared());
        OnDrainer.dispatch(harness, new ClusterFsmEvent.QuorumEstablished());
        assertThat(harness.state()).as("instrument: episode N+1 is a different entry").isNotSameAs(episodeOne);
        stored.run();
        onForeignThread(callbackOfOne);
        OnDrainer.drained(ctx);
        assertThat(storedRan).as("instrument: the Runnable armed in N is dropped in N+1").hasValue(0);

        return launderedRan.get() > 0;
    }

    // ---- C4: null identity fenced only by an accidental NullPointerException ----
    @Test
    void c4_nullIdentity_isRefusedByANamedCheck() {
        assertThat(nullIdentityEndedInAnAbsorbedNpe())
            .as("a null identity was refused by an accidental NullPointerException that the serial executor absorbed (C4)")
            .isFalse();
        assertThat(ctx.refusedNullIdentitiesForTest()).as("the refusal is the named check, and it is counted")
                                                      .isPositive();
    }

    private boolean nullIdentityEndedInAnAbsorbedNpe() {
        var ran = new AtomicInteger();

        start();
        serialLog = LogCapture.capture(SERIAL_LOGGER, Level.ERROR, true);
        ctx.episodeOf((EpisodeIdentity) null)
           .serialized(ran::incrementAndGet)
           .run();
        OnDrainer.drained(ctx);
        assertThat(ran).as("a null identity ran the task").hasValue(0);

        return serialLog.events()
                        .stream()
                        .map(LogEvent::getThrown)
                        .anyMatch(NullPointerException.class::isInstance);
    }

    // ---- stale handle: a handle of N, used after the same record is re-entered as N+1, is refused ----
    @Test
    void staleHandle_ofEntryN_usedInEntryNPlusOne_isRefused() {
        var staleRan = new AtomicInteger();
        var freshRan = new AtomicInteger();

        start();
        OnDrainer.dispatch(harness, new ClusterFsmEvent.QuorumEstablished());
        var saved = harness.state();
        var staleHandle = handleOfTheCurrentEntry().serialized(staleRan::incrementAndGet);

        OnDrainer.run(ctx, () -> harness.reenter(saved));
        assertThat(harness.state()).as("instrument: the same record, a new entry").isSameAs(saved);
        var freshHandle = handleOfTheCurrentEntry().serialized(freshRan::incrementAndGet);

        staleHandle.run();
        freshHandle.run();
        OnDrainer.drained(ctx);
        assertThat(freshRan).as("instrument: a handle taken in the new entry acts").hasValue(1);
        assertThat(staleRan).as("a handle of entry N acted in entry N+1").hasValue(0);
    }

    // ---- the run-time enforcer: a handle's liveness is its entry's IDENTITY (round-2 invariant, enforcer 2) ----
    // Liveness is two conjuncts, each pinned alone: the entry's lifetime has not ended, AND it is the very lifetime the Fsm
    // holds as the current entry. The Fsm ends a lifetime only in the CAS winner's `advanced`, so through production alone
    // the two never disagree on the drainer; these rows build the disagreement the way the receiver is specified (a
    // lifetime handed to `entryEpisode`), so a check that kept only one conjunct, or compared the state, goes red.
    @Test
    void handle_ofAnEndedLifetime_thatIsStillTheFsmsEntry_isRefused() {
        var ran = new AtomicInteger();

        start();
        OnDrainer.dispatch(harness, new ClusterFsmEvent.QuorumEstablished());
        var lifetime = harness.fsm()
                              .entered()
                              .lifetime();
        var handle = ctx.entryEpisode(lifetime);

        handle.serialized(ran::incrementAndGet).run();
        OnDrainer.drained(ctx);
        assertThat(ran).as("instrument: the live current entry acts").hasValue(1);
        lifetime.end();
        handle.serialized(ran::incrementAndGet).run();
        OnDrainer.drained(ctx);
        assertThat(ran).as("a handle over an ended lifetime acted although the Fsm still holds it").hasValue(1);
    }

    @Test
    void handle_ofAUnendedLifetime_thatIsNotTheFsmsEntry_isRefused() {
        var ran = new AtomicInteger();

        start();
        OnDrainer.dispatch(harness, new ClusterFsmEvent.QuorumEstablished());
        var foreign = ownerLifetime();

        ctx.entryEpisode(foreign)
           .serialized(ran::incrementAndGet)
           .run();
        OnDrainer.drained(ctx);
        assertThat(ran).as("a handle over a lifetime the Fsm does not hold acted (identity not compared)").hasValue(0);
    }

    @Test
    void entryEpisode_ofAMissingLifetime_isRefusedByANamedCheck() {
        var ran = new AtomicInteger();

        start();
        ctx.entryEpisode(null)
           .serialized(ran::incrementAndGet)
           .run();
        OnDrainer.drained(ctx);
        assertThat(ran).as("a missing lifetime ran the task").hasValue(0);
        assertThat(ctx.refusedNullIdentitiesForTest()).as("the refusal is the named, counted check").isEqualTo(1);
    }

    // ---- followUp: both conjuncts of its guard ----
    @Test
    void followUp_ofTheLiveCurrentEntry_fromAForeignThread_isRefused() {
        start();
        OnDrainer.dispatch(harness, new ClusterFsmEvent.QuorumEstablished());
        var before = harness.state();
        var live = handleOfTheCurrentEntry();

        onForeignThread(() -> live.followUp(new ClusterFsmEvent.QuorumDisappeared()));
        OnDrainer.drained(ctx);
        assertThat(harness.state()).as("a foreign thread moved the FSM through a LIVE handle").isSameAs(before);
        assertThat(ctx.refusedFollowUpsForTest()).as("refused by the confinement check").isPositive();
    }

    @Test
    void followUp_ofAnExitedEntry_onTheDrainer_isRefused() {
        var exited = exitQuorum();
        var staleHandle = exitedHandle;

        OnDrainer.run(ctx, () -> staleHandle.followUp(new ClusterFsmEvent.QuorumEstablished()));
        assertThat(harness.state()).as("a stale handle moved the FSM on the drainer").isSameAs(exited);
        assertThat(ctx.refusedFollowUpsForTest()).as("refused by the liveness check").isPositive();
    }

    // ---- the inbound boundary's stop reaches the FSM in order ----
    @Test
    void managerStop_shutsTheFsmDownThroughTheInbound() {
        start();
        var manager = new FsmBackedLeaderManager(harness.fsm(), ctx, inbound(ctx), false);

        manager.stop();
        assertThat(harness.state()).as("stop() did not deliver Shutdown through the inbound").isSameAs(ctx.stopped());
        harness = null;
    }

    // ---- fixture ----
    private LeaderElectionState exitQuorum() {
        start();
        OnDrainer.dispatch(harness, new ClusterFsmEvent.QuorumEstablished());
        exitedHandle = handleOfTheCurrentEntry();
        OnDrainer.dispatch(harness, new ClusterFsmEvent.QuorumDisappeared());

        return harness.state();
    }

    /// The handle the current state received at its entry ([LeaderElectionState#onEntered]), rebuilt over the lifetime the
    /// Fsm holds for it: the same identity the state's own handle carries.
    private LeaderElectionContext.EpisodeHandle handleOfTheCurrentEntry() {
        return ctx.entryEpisode(harness.fsm()
                                       .entered()
                                       .lifetime());
    }

    private void start() {
        harness = FsmTestHarness.<LeaderElectionState, ClusterFsmEvent> harness("episode-handle-fence", this::contextOver);
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

    /// Runs `body` on a thread that holds no dispatch frame and no drainer, and returns after it finished (a latch, bounded:
    /// never a sleep). A throw of `body` is the refusal's shape and is not the observation: the tests read the FSM.
    private void onForeignThread(Runnable body) {
        var finished = new CountDownLatch(1);
        var thread = new Thread(() -> runThenFinish(body, finished, foreignRefusal), "foreign-callback");

        thread.start();
        awaitFinished(finished);
    }

    private static void runThenFinish(Runnable body, CountDownLatch finished, AtomicReference<RuntimeException> refusal) {
        try {
            body.run();
        } catch (RuntimeException thrown) {
            // the refusal of a fenced path may throw; the row observes the FSM, not the throw
            refusal.set(thrown);
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
