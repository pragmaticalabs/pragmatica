/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.consensus.leader.fsm;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.leader.LeaderManager.LeaderProposalHandler;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.ConsensusReady;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderCommitted;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.pragmatica.consensus.fsm.Observations.seq;
import static org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderCommitted.leaderCommitted;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;


/// #3846 lane A3a: the run-time rows of memo r3 section 4 that hold at the head, in production event order, through the
/// harness helpers ([OnDrainer], [LeaderCommits], [FsmTestHarness]). Each row states what happens TODAY; a row that lane
/// St, C or F changes is a pair of methods (the ENABLED tripwire asserts today's behaviour and names its lane in the
/// failure message; the `@Disabled` twin asserts the rule). The row table, its mutants and its flipping lanes are in the
/// memo; a pin (no lane flips it) is asserted so that a change is visible, never claimed.
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class LeaderRunTimeRowsTest {
    private static final NodeId SELF = new NodeId("node-1");
    private static final NodeId PEER_A = new NodeId("node-2");
    private static final NodeId PEER_B = new NodeId("node-3");
    private static final List<NodeId> ALL = List.of(SELF, PEER_A, PEER_B);
    private static final TimeSpan LONG = TimeSpan.timeSpan(60).seconds();
    private static final long POLL_WITHIN_SECONDS = 15;

    /// Cluster mode (a proposal handler is present): a committed KV leader absent from the view is adopted only there.
    private static final LeaderProposalHandler CLUSTER_HANDLER = (candidate, viewSequence) -> Promise.success(Unit.unit());

    // ---------------------------------------------------------------------------------------------------------------
    // Group 1: R-4 (P3), the QuorumWaiting poll task.
    // ---------------------------------------------------------------------------------------------------------------
    /// R-4 instrument and control: the poll tick with NO KV leader and consensus ready dispatches `ConsensusReady` into
    /// `QuorumWaiting`, which moves to `AwaitingKvSync`. Observed through the harness observer, the same instrument the
    /// tripwire reads, so a missing `(Led, ConsensusReady)` pair in the tripwire cannot come from a dead observer: this
    /// control sees the observer's `Transition` for the same dispatch. ENABLED at every head.
    @Test
    void r4Control_pollWithNoKvLeader_dispatchesConsensusReadyIntoQuorumWaiting() {
        var h = quorumWaitingAwaitingPoll(false);

        try {
            awaitState(h, LeaderElectionState.AwaitingKvSync.class);
            OnDrainer.drained(h.state().ctx());
            assertThat(h.transitions()).as("instrument: ConsensusReady moved QuorumWaiting to AwaitingKvSync")
                      .anyMatch(t -> t.from() instanceof LeaderElectionState.QuorumWaiting && t.to() instanceof LeaderElectionState.AwaitingKvSync);
            assertThat(ignoredConsensusReadyInLed(h)).as("instrument: with no KV leader no Led ever saw ConsensusReady")
                      .isZero();
        } finally {
            shutdown(h);
        }
    }

    /// ENABLED tripwire (flipped in lane St): the poll task adopts the KV leader (`Led`) and then dispatches
    /// `ConsensusReady` into that `Led`, which ignores it at the `default` arm; the observer sees the ignored pair.
    @Test
    void r4_pollThatAdoptsAKvLeader_atHead_dispatchesConsensusReadyIntoLed() {
        var h = quorumWaitingAwaitingPoll(true);

        try {
            awaitState(h, LeaderElectionState.Led.class);
            OnDrainer.drained(h.state().ctx());
            assertThat(ignoredConsensusReadyInLed(h)).as("R-4 no longer acts: lane St landed the dispatchIfLive refusal of "
                                                        + "the poll's second dispatch. Enable "
                                                        + "r4_pollThatAdoptsAKvLeader_underInvE_isRefusedAndLedSeesNoConsensusReady "
                                                        + "and delete this method")
                      .isEqualTo(1L);
        } finally {
            shutdown(h);
        }
    }

    /// The rule, `@Disabled` until lane St: the second dispatch of the poll is refused (the `refusedDispatches` count is a
    /// lane C counter that does not exist at this head, so lane St adds it to this assertion), and `Led` sees no
    /// `ConsensusReady`.
    @Disabled("lane St: enable when the poll's second dispatch is refused through dispatchIfLive (R-4)")
    @Test
    void r4_pollThatAdoptsAKvLeader_underInvE_isRefusedAndLedSeesNoConsensusReady() {
        var h = quorumWaitingAwaitingPoll(true);

        try {
            awaitState(h, LeaderElectionState.Led.class);
            OnDrainer.drained(h.state().ctx());
            assertThat(ignoredConsensusReadyInLed(h)).as("the second dispatch was refused: Led saw no ConsensusReady")
                      .isZero();
        } finally {
            shutdown(h);
        }
    }

    /// Builds a harness in `QuorumWaiting` (consensus not ready, no KV leader), then arms ONE poll tick that sees both
    /// signals: the first KV read after arming flips consensus readiness and, when `kvNamesLeader`, names `PEER_A`.
    /// `pollConsensusAndKv` reads KV first and readiness second, so both are visible to the same tick and the order of
    /// the two reads cannot split them.
    private static FsmTestHarness<LeaderElectionState, ClusterFsmEvent> quorumWaitingAwaitingPoll(boolean kvNamesLeader) {
        var ready = new AtomicBoolean(false);
        var armed = new AtomicBoolean(false);
        var h = harness(ready::get, () -> armedKvRead(armed, ready, kvNamesLeader));

        OnDrainer.dispatch(h, new ClusterFsmEvent.NodeAdded(PEER_A, ALL, seq()));
        OnDrainer.dispatch(h, new ClusterFsmEvent.QuorumEstablished());
        assertThat(h.state()).as("instrument: consensus not ready and KV empty keep the node in QuorumWaiting, polling")
                  .isInstanceOf(LeaderElectionState.QuorumWaiting.class);
        armed.set(true);

        return h;
    }

    private static Option<LeaderCommitted> armedKvRead(AtomicBoolean armed,
                                                       AtomicBoolean ready,
                                                       boolean kvNamesLeader) {
        if (!armed.get()) {
            return none();
        }

        ready.set(true);

        return kvNamesLeader
               ? some(leaderCommitted(PEER_A, 1L))
               : none();
    }

    private static long ignoredConsensusReadyInLed(FsmTestHarness<LeaderElectionState, ClusterFsmEvent> h) {
        return h.ignored()
                .stream()
                .filter(ignored -> ignored.state() instanceof LeaderElectionState.Led && ignored.event() instanceof ConsensusReady)
                .count();
    }

    private static void awaitState(FsmTestHarness<LeaderElectionState, ClusterFsmEvent> h,
                                   Class<? extends LeaderElectionState> expected) {
        Awaitility.await("instrument: the 1 s poll tick moved the FSM to " + expected.getSimpleName())
                  .atMost(POLL_WITHIN_SECONDS, TimeUnit.SECONDS)
                  .until(() -> expected.isInstance(h.state()));
    }

    private static void shutdown(FsmTestHarness<LeaderElectionState, ClusterFsmEvent> h) {
        OnDrainer.dispatch(h, new ClusterFsmEvent.Shutdown());
    }

    private static FsmTestHarness<LeaderElectionState, ClusterFsmEvent> harness(BooleanSupplier consensusReady,
                                                                                Supplier<Option<LeaderCommitted>> kv) {
        return FsmTestHarness.<LeaderElectionState, ClusterFsmEvent> harness("run-time-rows",
                                                                             fsm -> context(fsm, consensusReady, kv).dormant());
    }

    private static LeaderElectionContext context(Fsm<LeaderElectionState, ClusterFsmEvent> fsm,
                                                 BooleanSupplier consensusReady,
                                                 Supplier<Option<LeaderCommitted>> kv) {
        return new LeaderElectionContext(fsm,
                                         SELF,
                                         some(CLUSTER_HANDLER),
                                         ALL,
                                         MessageRouter.mutable(),
                                         LONG,
                                         LONG,
                                         LONG,
                                         LONG,
                                         LeaderElectionContext.DEFAULT_STUCK_ELECTION_THRESHOLD,
                                         LeaderElectionContext.DEFAULT_JITTER_SOURCE,
                                         LeaderElectionContext.DEFAULT_RABIA_TERM_SUPPLIER,
                                         consensusReady::getAsBoolean,
                                         kv,
                                         LONG,
                                         LONG);
    }
}
