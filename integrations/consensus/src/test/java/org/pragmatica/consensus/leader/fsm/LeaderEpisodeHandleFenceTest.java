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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
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

/// #3846 round 2, lane A: the findings of v3855c (C1-C4) and the stale handle, as deterministic behaviour rows (latches,
/// no sleeps). INVARIANT under test: FSM code reaches the serial executor, `Fsm.dispatch` or `followUp` from outside the
/// current dispatch frame only through a handle of the episode it was given at entry, and that call takes effect only if
/// the episode is still current when it runs; a refusal is a no-op a test observes.
///
/// Each finding is ONE scenario method that returns what happened, and two tests read it:
/// - `c<N>_*_defectStillReproduces_atThisHead` is the ENABLED tripwire: it asserts the CURRENT behaviour, and its failure
///   message says what to enable. It goes red when the fence lands, which is the signal to enable the real row beside it
///   and delete the tripwire.
/// - `c<N>_*_isRefused` is the real assertion, `@Disabled` until the fence exists (lane B). Lifted, it is red at
///   2867082201.
/// The stale-handle row and the controls hold at this head and stay enabled.
///
/// The rows use the API of 2867082201 (`serially(inbound, task)`, `serializedFor(owner|identity, task)`,
/// `followUp`). Lane B ports each row to the handle API: the SCENARIO (who calls, from which thread, in which episode)
/// is the contract, the call spelling is not.
class LeaderEpisodeHandleFenceTest {
    private static final String DISABLED = "#3846 round 2: enable when the episode-handle fence exists (lane B)";
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
    void c1_foreignFollowUp_defectStillReproduces_atThisHead() {
        assertThat(foreignFollowUpMovedTheFsm())
            .as("C1 no longer reproduces: a foreign-thread followUp did not move the FSM. The fence landed: enable "
                + "c1_foreignFollowUp_isRefused and delete this tripwire")
            .isTrue();
    }

    @Disabled(DISABLED)
    @Test
    void c1_foreignFollowUp_isRefused() {
        assertThat(foreignFollowUpMovedTheFsm()).as("a foreign thread changed the FSM state through followUp (C1)")
                                                .isFalse();
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

        onForeignThread(() -> ctx.followUp(new ClusterFsmEvent.QuorumEstablished()));

        return harness.state() != exited;
    }

    // ---- C2: null stands in for the capability ----
    @Test
    void c2_seriallyWithNull_defectStillReproduces_atThisHead() {
        assertThat(nullCapabilityTaskRan())
            .as("C2 no longer reproduces: serially(null, task) did not run the task. The fence landed: enable "
                + "c2_seriallyWithNull_isRefused and delete this tripwire")
            .isTrue();
    }

    @Disabled(DISABLED)
    @Test
    void c2_seriallyWithNull_isRefused() {
        assertThat(nullCapabilityTaskRan()).as("serially(null, task) ran the task (C2)").isFalse();
    }

    private boolean nullCapabilityTaskRan() {
        var ran = new AtomicInteger();

        start();
        onForeignThread(() -> ctx.serially(null, ran::incrementAndGet));
        OnDrainer.drained(ctx);

        return ran.get() > 0;
    }

    // ---- C3: identity laundered through ctx.current() ----
    @Test
    void c3_launderedIdentity_defectStillReproduces_atThisHead() {
        assertThat(launderedTaskRanInTheNextEpisode())
            .as("C3 no longer reproduces: a task armed through ctx.current() from episode N's callback did not run in N+1. "
                + "The fence landed: enable c3_launderedIdentity_isRefused and delete this tripwire")
            .isTrue();
    }

    @Disabled(DISABLED)
    @Test
    void c3_launderedIdentity_isRefused() {
        assertThat(launderedTaskRanInTheNextEpisode())
            .as("serializedFor(ctx.current(), task), called by a callback of episode N, ran in episode N+1 (C3)")
            .isFalse();
    }

    private boolean launderedTaskRanInTheNextEpisode() {
        var storedRan = new AtomicInteger();
        var launderedRan = new AtomicInteger();

        start();
        OnDrainer.dispatch(harness, new ClusterFsmEvent.QuorumEstablished());
        var episodeOne = harness.state();
        var stored = ctx.serializedFor(episodeOne, storedRan::incrementAndGet);
        Runnable callbackOfOne = () -> ctx.serializedFor(ctx.current(), launderedRan::incrementAndGet)
                                          .run();

        OnDrainer.dispatch(harness, new ClusterFsmEvent.QuorumDisappeared());
        OnDrainer.dispatch(harness, new ClusterFsmEvent.QuorumEstablished());
        assertThat(harness.state()).as("instrument: episode N+1 is a different entry").isNotSameAs(episodeOne);
        stored.run();
        onForeignThread(callbackOfOne);
        OnDrainer.drained(ctx);
        assertThat(storedRan).as("instrument: the Runnable armed in N is dropped in N+1 (the fence that exists)")
                             .hasValue(0);

        return launderedRan.get() > 0;
    }

    // ---- C4: null identity fenced only by an accidental NullPointerException ----
    @Test
    void c4_nullIdentity_defectStillReproduces_atThisHead() {
        assertThat(nullIdentityEndedInAnAbsorbedNpe())
            .as("C4 no longer reproduces: serializedFor((EpisodeIdentity) null, task) no longer ends in an absorbed NPE. "
                + "The fence landed: enable c4_nullIdentity_isRefusedByANamedCheck and delete this tripwire")
            .isTrue();
    }

    @Disabled(DISABLED)
    @Test
    void c4_nullIdentity_isRefusedByANamedCheck() {
        assertThat(nullIdentityEndedInAnAbsorbedNpe())
            .as("a null identity was refused by an accidental NullPointerException that the serial executor absorbed (C4)")
            .isFalse();
    }

    private boolean nullIdentityEndedInAnAbsorbedNpe() {
        var ran = new AtomicInteger();

        start();
        serialLog = LogCapture.capture(SERIAL_LOGGER, Level.ERROR, true);
        ctx.serializedFor((EpisodeIdentity) null, ran::incrementAndGet)
           .run();
        OnDrainer.drained(ctx);
        assertThat(ran).as("a null identity ran the task").hasValue(0);

        return serialLog.events()
                        .stream()
                        .map(LogEvent::getThrown)
                        .anyMatch(NullPointerException.class::isInstance);
    }

    // ---- stale handle: a handle of N, used after the same record is re-entered as N+1, is refused (holds at this head) ----
    @Test
    void staleHandle_ofEntryN_usedInEntryNPlusOne_isRefused() {
        var staleRan = new AtomicInteger();
        var freshRan = new AtomicInteger();

        start();
        OnDrainer.dispatch(harness, new ClusterFsmEvent.QuorumEstablished());
        var saved = harness.state();
        var staleHandle = ctx.serializedFor(saved, staleRan::incrementAndGet);

        OnDrainer.run(ctx, () -> harness.reenter(saved));
        assertThat(harness.state()).as("instrument: the same record, a new entry").isSameAs(saved);
        var freshHandle = ctx.serializedFor(saved, freshRan::incrementAndGet);

        staleHandle.run();
        freshHandle.run();
        OnDrainer.drained(ctx);
        assertThat(freshRan).as("instrument: a handle taken in the new entry acts").hasValue(1);
        assertThat(staleRan).as("a handle of entry N acted in entry N+1").hasValue(0);
    }

    // ---- fixture ----
    private LeaderElectionState exitQuorum() {
        start();
        OnDrainer.dispatch(harness, new ClusterFsmEvent.QuorumEstablished());
        OnDrainer.dispatch(harness, new ClusterFsmEvent.QuorumDisappeared());

        return harness.state();
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
