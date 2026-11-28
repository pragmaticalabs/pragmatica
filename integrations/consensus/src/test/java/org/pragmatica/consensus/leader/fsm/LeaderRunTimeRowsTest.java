/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.consensus.leader.fsm;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.leader.LeaderManager.LeaderProposalHandler;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.ConsensusReady;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderCommitted;
import org.pragmatica.consensus.net.NetworkMessage.LeaderPreVoteRequest;
import org.pragmatica.consensus.net.NetworkMessage.LeaderPreVoteResponse;
import org.pragmatica.consensus.net.NetworkServiceMessage.Send;
import org.pragmatica.consensus.topology.TransportView;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.utility.react.ReactExecutor;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.pragmatica.consensus.fsm.Observations.seq;
import static org.pragmatica.consensus.leader.LeaderInboundForTest.inbound;
import static org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderCommitted.leaderCommitted;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    private static final TimeSpan SHORT = TimeSpan.timeSpan(50).millis();
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
        var h = harness(ready::get, () -> armedKvRead(armed, ready, kvNamesLeader), LONG);

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

    // ---------------------------------------------------------------------------------------------------------------
    // Group 2: K24 (forged pre-vote over the node's ctx) and K24b (the installed pre-vote).
    // ---------------------------------------------------------------------------------------------------------------
    /// K24 (INV-E anchor): a pre-vote the node did NOT install (`LeaderPreVote.leaderPreVote(ctx, ...)`, package-private) mints
    /// Suspicions that are never live, so its `LeaderDoubtConfirmed` is refused and counted and `Led` stays. The leader is
    /// suspected through the public `removeFromView`, which dispatches nothing, so the only thing that could end the tenure
    /// here is the forged verdict (the instrument lines pin that no pre-vote is installed and that `Led` still stands
    /// before the forged round).
    @Test
    void k24_forgedPreVoteOverTheNodesCtx_underInvE_isRefusedAndLedStays() {
        var h = ledUnderPeerA(MessageRouter.mutable());

        try {
            var ctx = h.state().ctx();
            var forged = LeaderPreVote.leaderPreVote(ctx, LONG);

            var refusedBefore = ctx.refusedDispatchesForTest();

            assertThat(ctx.preVote().isEmpty()).as("instrument: the node installed no pre-vote, the verdict is the forged one's")
                      .isTrue();
            assertThat(ctx.removeFromView(PEER_A)).as("instrument: the leader left the stored view").isTrue();
            assertThat(h.state()).as("instrument: a removal through removeFromView alone leaves Led standing")
                      .isInstanceOf(LeaderElectionState.Led.class);
            roundWithDoubtingMajorityOnDrainer(ctx, forged);
            assertThat(h.state()).as("the forged verdict was refused: Led stays")
                      .isInstanceOf(LeaderElectionState.Led.class);
            assertThat(ctx.refusedDispatchesForTest()).as("the refusal is counted: one forged verdict, one refused dispatch")
                                                      .isEqualTo(refusedBefore + 1);
        } finally {
            shutdown(h);
        }
    }

    /// K24b (pin, no lane flips it): the same round through the pre-vote the node INSTALLED. Its verdict is the supported
    /// path and acts (`Led` leaves), whoever supplied the responses: pinned, not claimed (memo 7.7, 7.10).
    @Test
    void k24b_forgedResponsesThroughTheInstalledPreVote_atHead_ledLeaves() {
        var h = ledUnderPeerA(MessageRouter.mutable());

        try {
            var ctx = h.state().ctx();

            ctx.enablePreVote(LONG);
            assertThat(ctx.removeFromView(PEER_A)).as("instrument: the leader left the stored view").isTrue();
            roundWithDoubtingMajorityOnDrainer(ctx,
                                               ctx.preVote().unwrap());
            assertThat(h.state()).as("K24b pin changed: the installed pre-vote's verdict no longer takes Led to ReElecting")
                      .isInstanceOf(LeaderElectionState.ReElecting.class);
        } finally {
            shutdown(h);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Group 3: K29 and K30, the verdict path and the suspicion episode (green at head).
    // ---------------------------------------------------------------------------------------------------------------
    /// K29: a verdict the handler DISCARDS (the leader is still in the view, so `Led` does not suspect it) leaves the
    /// installed pre-vote free to open a new round for the same leader: `rounds` advances, observed as the round numbers
    /// of the requests the node sends (round 1 asks the two other voters, the next suspicion's round is 2).
    @Test
    void k29_discardedVerdict_thenSuspectOfTheSameLeader_opensANewRound() {
        var sends = new CopyOnWriteArrayList<Send>();
        var h = ledUnderPeerA(routerCapturing(sends));

        try {
            var ctx = h.state().ctx();

            ctx.enablePreVote(LONG);
            var preVote = ctx.preVote().unwrap();

            roundWithDoubtingMajorityOnDrainer(ctx, preVote);
            assertThat(h.state()).as("instrument: the verdict was discarded (the leader is in the view), Led stays")
                      .isInstanceOf(LeaderElectionState.Led.class);
            assertThat(ctx.confinementBreachesForTest()).as("instrument: the verdict dispatch was admitted, not refused")
                      .isZero();
            assertThat(requestRounds(sends)).as("instrument: round 1 asked the two other voters")
                      .containsExactlyInAnyOrder(1L, 1L);
            OnDrainer.run(ctx, () -> preVote.suspect(PEER_A));
            assertThat(requestRounds(sends)).as("K29: the suspicion after a discarded verdict opens round 2")
                      .contains(2L);
        } finally {
            shutdown(h);
        }
    }

    /// K30 (R4): the verdict's dispatch THROWS (the installed pre-vote's quorum `onResponse` runs on a thread that is not the
    /// drainer, so `dispatchConfined` refuses with an `IllegalStateException`, which propagates to the caller of
    /// `onResponse`); `Led` stays, and the next `suspect(sameLeader)` still opens a new round.
    @Test
    void k30_verdictDispatchThrows_thenSuspectOfTheSameLeader_opensANewRound() {
        var sends = new CopyOnWriteArrayList<Send>();
        var h = ledUnderPeerA(routerCapturing(sends));

        try {
            var ctx = h.state().ctx();

            ctx.enablePreVote(LONG);
            var preVote = ctx.preVote().unwrap();

            OnDrainer.run(ctx, () -> preVote.suspect(PEER_A));
            assertThatThrownBy(() -> preVote.onResponse(doubt(PEER_B, 1L))).as("the verdict dispatch off the drainer throws to the caller")
                              .isInstanceOf(IllegalStateException.class);
            assertThat(ctx.confinementBreachesForTest()).as("instrument: the throw is the confinement refusal")
                      .isEqualTo(1);
            assertThat(h.state()).as("instrument: the refused verdict left Led standing")
                      .isInstanceOf(LeaderElectionState.Led.class);
            OnDrainer.run(ctx, () -> preVote.suspect(PEER_A));
            assertThat(requestRounds(sends)).as("K30: the suspicion after a throwing verdict opens round 2")
                      .contains(2L);
        } finally {
            shutdown(h);
        }
    }

    /// One serial task: the installed or forged pre-vote suspects `PEER_A` (round 1, the node counts itself as doubting)
    /// and `PEER_B` answers that it doubts too, a majority of three, so the verdict is dispatched on the drainer.
    private static void roundWithDoubtingMajorityOnDrainer(LeaderElectionContext ctx, LeaderPreVote preVote) {
        OnDrainer.run(ctx, () -> suspectAndAnswer(preVote));
    }

    private static void suspectAndAnswer(LeaderPreVote preVote) {
        preVote.suspect(PEER_A);
        preVote.onResponse(doubt(PEER_B, 1L));
    }

    private static LeaderPreVoteResponse doubt(NodeId sender, long round) {
        return new LeaderPreVoteResponse(sender, PEER_A, round, false);
    }

    private static List<Long> requestRounds(List<Send> sends) {
        return sends.stream()
                    .map(Send::payload)
                    .filter(LeaderPreVoteRequest.class::isInstance)
                    .map(LeaderPreVoteRequest.class::cast)
                    .map(LeaderPreVoteRequest::round)
                    .toList();
    }

    private static MessageRouter routerCapturing(List<Send> sends) {
        var router = MessageRouter.mutable();

        router.addRoute(Send.class, (Consumer<Send>) sends::add);

        return router;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Group 4: the pins O09, K23b, K25, K26 and K27 (install-time wiring; asserted so a change is visible, never claimed).
    // ---------------------------------------------------------------------------------------------------------------
    /// O09 (P1, pin): the body of the transport-view supplier runs on the drainer, in the reassert task the removal arms,
    /// and the view it returns is admitted as the node's stored view: the removed `PEER_B` is back. (`currentTopology()`
    /// keeps only the eligible nodes of the configured cluster, so a node outside it would be stored yet invisible: the
    /// chosen view is `ALL`.) The reassert's own dispatch is confined, so a task that ran off the drainer would leave the
    /// breach count at 1 and the view unchanged.
    @Test
    void o09_transportViewSupplierBody_runsOnTheDrainerAndItsViewIsAdmitted() {
        var h = ledUnderPeerA(MessageRouter.mutable());

        try {
            var ctx = h.state().ctx();
            var ran = new AtomicBoolean(false);
            var chosen = ALL;

            ctx.installTransportView(() -> chosenView(ran, chosen),
                                     TimeSpan.timeSpan(1).millis());
            assertThat(ctx.removeFromView(PEER_B)).as("instrument: the removal is applied and arms the reassert")
                      .isTrue();
            Awaitility.await("the reassert task ran the supplier and dispatched its view")
                      .atMost(POLL_WITHIN_SECONDS, TimeUnit.SECONDS)
                      .until(() -> ran.get() && ctx.currentTopology()
                                                   .contains(PEER_B));
            assertThat(ctx.currentTopology()).as("O09: the caller-chosen view is the stored view")
                      .containsExactlyInAnyOrderElementsOf(chosen);
            assertThat(ctx.confinementBreachesForTest()).as("O09: the supplier's view was dispatched on the drainer, not refused")
                      .isZero();
        } finally {
            shutdown(h);
        }
    }

    private static Option<TransportView> chosenView(AtomicBoolean ran, List<NodeId> topology) {
        ran.set(true);

        return some(new TransportView(topology, seq()));
    }

    /// K23b (pin): a closure installed through `installKvSyncPending` that publishes a leader (`setCurrentLeader`) ACTS: the
    /// published leader is the context's leader. The closure is read by the production path, the `AwaitingKvSync` grace
    /// timeout (it answers "still pending", so the node keeps waiting inside the maximum wait).
    @Test
    void k23b_kvSyncPendingClosurePublishingALeader_atHead_acts() {
        var called = new AtomicBoolean(false);
        var h = harness(() -> true, Option::none, SHORT);

        try {
            var ctx = h.state().ctx();

            ctx.installKvSyncPending(() -> publishLeaderAndStayPending(ctx, called));
            OnDrainer.dispatch(h, new ClusterFsmEvent.NodeAdded(PEER_A, ALL, seq()));
            OnDrainer.dispatch(h, new ClusterFsmEvent.QuorumEstablished());
            Awaitility.await("instrument: the grace timeout read the closure")
                      .atMost(POLL_WITHIN_SECONDS, TimeUnit.SECONDS)
                      .until(called::get);
            assertThat(ctx.currentLeader()).as("K23b pin changed: the closure's setCurrentLeader no longer publishes")
                      .isEqualTo(some(PEER_B));
        } finally {
            shutdown(h);
        }
    }

    private static boolean publishLeaderAndStayPending(LeaderElectionContext ctx, AtomicBoolean called) {
        ctx.setCurrentLeader(some(PEER_B));
        called.set(true);

        return true;
    }

    /// K25 (pin): the `installReplaySource` supplier is read while the leader change is absorbed, on the drainer task that
    /// dispatched the commit (the thread that runs that task), and its answer is the change's `replayed` bit.
    @Test
    void k25_replaySourceSupplier_atHead_runsInTheDrainerTaskThatAbsorbedTheCommit() {
        var carried = new CopyOnWriteArrayList<Boolean>();
        var router = MessageRouter.mutable();

        router.addRoute(LeaderChange.class,
                        (Consumer<LeaderChange>) change -> carried.add(change.replayed()));
        var h = awaitingKvSync(router);

        try {
            var ctx = h.state().ctx();
            var supplierThreads = new CopyOnWriteArrayList<Thread>();
            var drainerThread = new AtomicReference<Thread>();

            ctx.installReplaySource(() -> recordThread(supplierThreads));
            OnDrainer.run(ctx, () -> commitFromTask(ctx, drainerThread));
            assertThat(h.state()).as("instrument: the commit moved the node to Led")
                      .isInstanceOf(LeaderElectionState.Led.class);
            assertThat(supplierThreads).as("K25: the supplier was read, in the thread of the task that absorbed the commit")
                      .isNotEmpty()
                      .containsOnly(drainerThread.get());
            assertThat(carried).as("K25: the supplier's answer is the replay bit of the change").containsOnly(true);
        } finally {
            shutdown(h);
        }
    }

    private static boolean recordThread(List<Thread> threads) {
        threads.add(Thread.currentThread());

        return true;
    }

    private static void commitFromTask(LeaderElectionContext ctx, AtomicReference<Thread> drainerThread) {
        drainerThread.set(Thread.currentThread());
        inbound(ctx).submit(leaderCommitted(PEER_A, LeaderCommitted.NO_SEQUENCE));
    }

    /// K26 (pin): with an executor installed through `installReactExecutor`, the leader-change route is handed to it and
    /// does NOT run inside the absorbing task: nothing is routed until the executor runs the callback (here, on the test
    /// thread, after the commit returned).
    @Test
    void k26_reactExecutor_atHead_receivesTheRouteAndTheCallbackRunsOutsideTheOrder() {
        var routed = new CopyOnWriteArrayList<LeaderChange>();
        var router = MessageRouter.mutable();

        router.addRoute(LeaderChange.class, (Consumer<LeaderChange>) routed::add);
        var h = awaitingKvSync(router);

        try {
            var ctx = h.state().ctx();
            var executor = new CapturingReactExecutor();

            ctx.installReactExecutor(executor);
            LeaderCommits.commitLeaderOnDrainer(h, PEER_A, 1L);
            assertThat(h.state()).as("instrument: the commit moved the node to Led")
                      .isInstanceOf(LeaderElectionState.Led.class);
            assertThat(executor.reactions).as("K26: the executor received the route of the leader change")
                      .containsExactly("LeaderChange(other)");
            assertThat(routed).as("K26: the callback has not run inside the order").isEmpty();
            executor.calls.forEach(Runnable::run);
            assertThat(routed).as("K26: the callback routes the change when the executor runs it")
                      .extracting(LeaderChange::leaderId)
                      .containsExactly(some(PEER_A));
        } finally {
            shutdown(h);
        }
    }

    /// K27 (pin): `enablePreVote()` after a pre-vote is installed is a no-op: the installed instance stays.
    @Test
    void k27_enablePreVoteAfterInstall_atHead_isANoOp() {
        var h = harness(() -> true, Option::none, LONG);

        try {
            var ctx = h.state().ctx();

            assertThat(ctx.preVote().isEmpty()).as("instrument: no pre-vote before the first enable").isTrue();
            ctx.enablePreVote(LONG);
            var installed = ctx.preVote().unwrap();

            ctx.enablePreVote();
            assertThat(ctx.preVote().unwrap()).as("K27 pin changed: a second enable replaced the installed pre-vote")
                      .isSameAs(installed);
        } finally {
            shutdown(h);
        }
    }

    /// A `ReactExecutor` that keeps the callbacks it is handed instead of running them.
    private static final class CapturingReactExecutor implements ReactExecutor {
        final List<String> reactions = new CopyOnWriteArrayList<>();
        final List<Runnable> calls = new CopyOnWriteArrayList<>();

        @Override
        public Unit react(String reaction, Runnable call) {
            reactions.add(reaction);
            calls.add(call);

            return Unit.unit();
        }

        @Override
        public Unit undo(String reaction, Runnable call) {
            return react(reaction, call);
        }

        @Override
        public String component() {
            return "capturing";
        }

        @Override
        public long queueDepth() {
            return calls.size();
        }

        @Override
        public long highWater() {
            return calls.size();
        }

        @Override
        public long droppedEngineStopped() {
            return 0;
        }

        @Override
        public Unit close() {
            return Unit.unit();
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Shared harness.
    // ---------------------------------------------------------------------------------------------------------------
    /// `Led(PEER_A)` reached the way production reaches it: view, quorum, then the leader commit on the drainer.
    private static FsmTestHarness<LeaderElectionState, ClusterFsmEvent> ledUnderPeerA(MessageRouter router) {
        var h = awaitingKvSync(router);

        LeaderCommits.commitLeaderOnDrainer(h, PEER_A, 1L);
        assertThat(h.state()).as("instrument: the commit moved the node to Led(PEER_A)")
                  .isInstanceOf(LeaderElectionState.Led.class);

        return h;
    }

    /// Consensus ready and no KV leader: the view, then `QuorumEstablished`, leave the node in `AwaitingKvSync` (long grace).
    private static FsmTestHarness<LeaderElectionState, ClusterFsmEvent> awaitingKvSync(MessageRouter router) {
        var h = harness(() -> true, Option::none, LONG, router);

        OnDrainer.dispatch(h, new ClusterFsmEvent.NodeAdded(PEER_A, ALL, seq()));
        OnDrainer.dispatch(h, new ClusterFsmEvent.QuorumEstablished());
        assertThat(h.state()).as("instrument: ready consensus and no KV leader wait in AwaitingKvSync")
                  .isInstanceOf(LeaderElectionState.AwaitingKvSync.class);

        return h;
    }

    private static FsmTestHarness<LeaderElectionState, ClusterFsmEvent> harness(BooleanSupplier consensusReady,
                                                                                Supplier<Option<LeaderCommitted>> kv,
                                                                                TimeSpan grace) {
        return harness(consensusReady, kv, grace, MessageRouter.mutable());
    }

    private static FsmTestHarness<LeaderElectionState, ClusterFsmEvent> harness(BooleanSupplier consensusReady,
                                                                                Supplier<Option<LeaderCommitted>> kv,
                                                                                TimeSpan grace,
                                                                                MessageRouter router) {
        return FsmTestHarness.<LeaderElectionState, ClusterFsmEvent> harness("run-time-rows",
                                                                             fsm -> context(fsm,
                                                                                            consensusReady,
                                                                                            kv,
                                                                                            grace,
                                                                                            router).dormant());
    }

    /// The lease and its checks are long (a lease check never ends a tenure inside a test); the grace is the caller's.
    private static LeaderElectionContext context(Fsm<LeaderElectionState, ClusterFsmEvent> fsm,
                                                 BooleanSupplier consensusReady,
                                                 Supplier<Option<LeaderCommitted>> kv,
                                                 TimeSpan grace,
                                                 MessageRouter router) {
        return new LeaderElectionContext(fsm,
                                         SELF,
                                         some(CLUSTER_HANDLER),
                                         ALL,
                                         router,
                                         LONG,
                                         LONG,
                                         LONG,
                                         LONG,
                                         LeaderElectionContext.DEFAULT_STUCK_ELECTION_THRESHOLD,
                                         LeaderElectionContext.DEFAULT_JITTER_SOURCE,
                                         LeaderElectionContext.DEFAULT_RABIA_TERM_SUPPLIER,
                                         consensusReady::getAsBoolean,
                                         kv,
                                         grace,
                                         LONG,
                                         LONG,
                                         leader -> true);
    }
}
