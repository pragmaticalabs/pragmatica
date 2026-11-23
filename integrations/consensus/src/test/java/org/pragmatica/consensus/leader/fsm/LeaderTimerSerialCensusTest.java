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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.leader.LeaderManager.LeaderProposalHandler;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderCommitted;
import org.pragmatica.consensus.net.NetworkMessage.LeaderPreVoteRequest;
import org.pragmatica.consensus.net.NetworkMessage.LeaderPreVoteResponse;
import org.pragmatica.consensus.net.NetworkServiceMessage.Send;
import org.pragmatica.consensus.topology.TransportView;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmObserver;
import org.pragmatica.statemachine.FsmTags;

import org.awaitility.Awaitility;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;


/// #3605 timer census: every timer callback of the leader election (pre-vote round and retry, the QuorumWaiting poll,
/// the Electing and ReElecting peer observation, the Led lease, the view re-assertion) runs its WHOLE body as one task
/// of the node's serial executor. [LeaderSerialDispatchCensusTest] cannot see this: a callback's own event goes through
/// `submit`, which queues on its own; what the `serialized` wrap adds is that the callback's READS (the KV leader, the
/// ping freshness, the jitter, the transport view, the pre-vote episode) and its sends happen in the order.
///
/// Instrument: another thread holds the drainer ([LeaderEntryPointAtomicityTest#holdDrainer]); the timer under test is
/// armed AFTER that, with a short delay (every other timer of the context is 60 s), so it fires on a scheduler thread
/// while the drainer is held. Every input the callback reads is a test supplier that records the read, every send and
/// every FSM event is recorded, all in one trace. The test awaits the condition the firing produces: the callback's task
/// queued in the executor (an unserialized callback produces a read in the trace instead). Asserted: (a) nothing in the
/// trace before the release; (b) after it, the callback's read (and its event, where it has one) comes before a marker
/// task queued after the callback's task: handled in order. An unserialized callback reads on the scheduler thread:
/// RED on (a).
///
/// The queue is read through the test seam [LeaderElectionContext#pendingSerialTasksForTest]: the only way to observe that
/// a serialized callback fired, since it does nothing else until the drainer runs it. The 200 ms timer delay is the
/// trigger, never a wait: the test awaits the condition the firing produces (a task queued, or, for an unserialized
/// callback, a read in the trace). No assertion is made on the state of the queue right after arming (the timer may
/// already have fired under a stall); a callback that ran before the release fails (a), one that never ran in the order
/// fails (b).
class LeaderTimerSerialCensusTest {
    private static final NodeId SELF = new NodeId("core-0");
    private static final NodeId PEER = new NodeId("core-1");
    private static final List<NodeId> CORES = List.of(SELF, PEER);
    private static final TimeSpan LONG = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan SHORT = TimeSpan.timeSpan(200).millis();
    private static final String READ_KV = "read: KV leader";
    private static final String READ_PING = "read: leader ping freshness";
    private static final String READ_JITTER = "read: jitter";
    private static final String READ_VIEW = "read: transport view";
    private static final String MARKER = "marker: the task queued after the timer's";

    private final List<Object> trace = new CopyOnWriteArrayList<>();
    private final AtomicReference<Option<LeaderCommitted>> kvLeader = new AtomicReference<>(Option.none());

    /// The delays the case shortens; every other delay of the context is [#LONG].
    record Timings(TimeSpan proposalRetryDelay,
                   TimeSpan peerObservationInterval,
                   TimeSpan leaseCheckInterval,
                   TimeSpan leaseWarmup) {
        static Timings allLong() {
            return new Timings(LONG, LONG, LONG, LONG);
        }
    }

    /// `arm` runs on the test thread while the drainer is held and returns the cleanup that cancels what it armed.
    record Case(Timings timings,
                Function<LeaderElectionContext, LeaderElectionState> initial,
                Consumer<LeaderElectionContext> prepare,
                BiFunction<LeaderTimerSerialCensusTest, LeaderElectionContext, Runnable> arm,
                Predicate<Object> read,
                Option<Predicate<Object>> event) {}

    static Stream<Arguments> timers() {
        return Stream.of(timer("pv01 pre-vote round timeout",
                               new Case(Timings.allLong(),
                                        LeaderElectionContext::dormant,
                                        ctx -> ctx.enablePreVote(SHORT),
                                        LeaderTimerSerialCensusTest::openPreVoteRound,
                                        READ_JITTER::equals,
                                        Option.none())),
                         timer("pv03 pre-vote retry",
                               new Case(new Timings(SHORT, LONG, LONG, LONG),
                                        ctx -> LeaderElectionState.Led.fresh(ctx, PEER),
                                        ctx -> ctx.enablePreVote(LONG),
                                        LeaderTimerSerialCensusTest::backOffPreVoteRound,
                                        LeaderTimerSerialCensusTest::asksPeer,
                                        Option.none())),
                         timer("st02 QuorumWaiting poll",
                               new Case(Timings.allLong(),
                                        LeaderElectionContext::dormant,
                                        LeaderTimerSerialCensusTest::nothing,
                                        LeaderTimerSerialCensusTest::armQuorumPoll,
                                        READ_KV::equals,
                                        Option.some(LeaderTimerSerialCensusTest::commitsPeer))),
                         timer("st03 Electing peer observation",
                               new Case(new Timings(LONG, SHORT, LONG, LONG),
                                        LeaderElectionContext::dormant,
                                        LeaderTimerSerialCensusTest::nothing,
                                        LeaderTimerSerialCensusTest::armElectingObservation,
                                        READ_KV::equals,
                                        Option.some(LeaderTimerSerialCensusTest::commitsPeer))),
                         timer("st04 ReElecting peer observation",
                               new Case(new Timings(LONG, SHORT, LONG, LONG),
                                        LeaderElectionContext::dormant,
                                        LeaderTimerSerialCensusTest::nothing,
                                        LeaderTimerSerialCensusTest::armReElectingObservation,
                                        READ_KV::equals,
                                        Option.some(LeaderTimerSerialCensusTest::commitsPeer))),
                         timer("st05 Led leader-acting lease",
                               new Case(new Timings(LONG, LONG, SHORT, SHORT),
                                        LeaderElectionContext::dormant,
                                        LeaderTimerSerialCensusTest::nothing,
                                        LeaderTimerSerialCensusTest::armLease,
                                        READ_PING::equals,
                                        Option.none())),
                         timer("cx01 view re-assertion",
                               new Case(Timings.allLong(),
                                        LeaderElectionContext::dormant,
                                        LeaderTimerSerialCensusTest::nothing,
                                        LeaderTimerSerialCensusTest::removePeerFromView,
                                        READ_VIEW::equals,
                                        Option.some(LeaderTimerSerialCensusTest::addsTheView))));
    }

    private static Arguments timer(String name, Case testCase) {
        return Arguments.of(name, testCase);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("timers")
    void timerCallback_whileAnotherThreadDrains_runsWhollyInTheSerialOrder(String name, Case testCase) throws InterruptedException {
        var ctx = context(testCase);

        testCase.prepare().accept(ctx);
        var held = LeaderEntryPointAtomicityTest.holdDrainer(ctx);
        var cleanup = testCase.arm().apply(this, ctx);

        try {
            trace.clear();
            awaitFiring(ctx);
            assertThat(trace).as("%s ran on the scheduler thread while another thread held the serial executor", name)
                      .isEmpty();
            assertThat(ctx.pendingSerialTasksForTest()).as("instrument: %s fired while the drainer was held", name)
                      .isPositive();
            ctx.serially(() -> trace.add(MARKER));
            held.release();
            var beforeMarker = beforeMarker();

            assertThat(beforeMarker).as("%s: its read ran in the order, before the task queued after it", name)
                      .anyMatch(testCase.read());
            testCase.event()
                    .onPresent(event -> assertThat(beforeMarker).as("%s: its event was handled in the order", name)
                                                  .anyMatch(event));
        } finally {
            cleanup.run();
        }
    }

    /// st02: `QuorumWaiting`'s entry arms the poll when the KV holds no leader and consensus is not ready; the KV then
    /// names one, so the poll has an event to deliver.
    private Runnable armQuorumPoll(LeaderElectionContext ctx) {
        var waiting = LeaderElectionState.QuorumWaiting.fresh(ctx);

        waiting.onEntry();
        kvLeader.set(Option.some(new LeaderCommitted(PEER)));

        return waiting::onExit;
    }

    private Runnable armElectingObservation(LeaderElectionContext ctx) {
        kvLeader.set(Option.some(new LeaderCommitted(PEER)));

        return LeaderElectionState.Electing.fresh(ctx)::onCasLost;
    }

    private Runnable armReElectingObservation(LeaderElectionContext ctx) {
        kvLeader.set(Option.some(new LeaderCommitted(PEER)));

        return LeaderElectionState.ReElecting.fresh(ctx)::onCasLost;
    }

    /// pv01: a suspicion opens round 1; its timeout refrains, which reads the jitter for the retry delay.
    private Runnable openPreVoteRound(LeaderElectionContext ctx) {
        var preVote = ctx.preVote().unwrap();

        preVote.suspect(PEER);

        return () -> preVote.cancel(PEER);
    }

    /// pv03: round 1 is decided by PEER vouching (refrain), which arms the retry; the retry, while the `Led(PEER)`
    /// leader is still suspected (absent from the empty stored view), opens round 2 and asks PEER.
    private Runnable backOffPreVoteRound(LeaderElectionContext ctx) {
        var preVote = ctx.preVote().unwrap();

        preVote.suspect(PEER);
        var round = trace.stream()
                         .filter(LeaderTimerSerialCensusTest::asksPeer)
                         .map(LeaderTimerSerialCensusTest::round)
                         .findFirst()
                         .orElseThrow();

        preVote.onResponse(new LeaderPreVoteResponse(PEER, PEER, round, true));

        return () -> preVote.cancel(PEER);
    }

    private Runnable armLease(LeaderElectionContext ctx) {
        var led = LeaderElectionState.Led.fresh(ctx, PEER);

        led.onEntry();

        return led::onCasLost;
    }

    /// cx01: a view-free removal arms one re-assertion `SHORT` later; it re-reads the transport view.
    private Runnable removePeerFromView(LeaderElectionContext ctx) {
        ctx.installTransportView(this::transportView, SHORT);
        ctx.applyTopology(new ClusterFsmEvent.NodeAdded(PEER, CORES, 1));
        assertThat(ctx.removeFromView(PEER)).as("instrument: the removal took effect").isTrue();

        return LeaderTimerSerialCensusTest::nothing;
    }

    private void awaitFiring(LeaderElectionContext ctx) {
        Awaitility.await("the timer fired: its task is queued, or an unserialized callback already read")
                  .atMost(Duration.ofSeconds(10))
                  .pollInterval(Duration.ofMillis(5))
                  .until(() -> ctx.pendingSerialTasksForTest() > 0 || !trace.isEmpty());
    }

    private List<Object> beforeMarker() {
        var marker = trace.indexOf(MARKER);

        assertThat(marker).as("instrument: the marker task ran").isNotNegative();

        return List.copyOf(trace.subList(0, marker));
    }

    private static boolean asksPeer(Object message) {
        return message instanceof Send send
               && send.target()
                      .equals(PEER)
               && send.payload() instanceof LeaderPreVoteRequest;
    }

    private static long round(Object message) {
        return ((LeaderPreVoteRequest)((Send) message).payload()).round();
    }

    private static boolean commitsPeer(Object event) {
        return event instanceof LeaderCommitted committed && committed.leader()
                                                                      .equals(PEER);
    }

    private static boolean addsTheView(Object event) {
        return event instanceof ClusterFsmEvent.NodeAdded added && added.topology()
                                                                        .equals(CORES);
    }

    private static void nothing(LeaderElectionContext ctx) {}

    private static void nothing() {}

    private Option<TransportView> transportView() {
        trace.add(READ_VIEW);

        return Option.some(new TransportView(CORES, 2));
    }

    private Option<LeaderCommitted> kv() {
        trace.add(READ_KV);

        return kvLeader.get();
    }

    private double jitter() {
        trace.add(READ_JITTER);

        return 0.0;
    }

    private boolean pingFresh(NodeId leader) {
        trace.add(READ_PING);

        return false;
    }

    private LeaderElectionContext context(Case testCase) {
        var router = MessageRouter.mutable();

        router.addRoute(LeaderChange.class, LeaderTimerSerialCensusTest::ignoreChange);
        router.addRoute(Send.class, trace::add);
        var fsm = Fsm.<LeaderElectionState, ClusterFsmEvent> fsm("leader-timer-serial-census",
                                                                 f -> testCase.initial()
                                                                              .apply(context(f,
                                                                                             router,
                                                                                             testCase.timings())),
                                                                 recorder());

        return fsm.current()
                  .ctx();
    }

    private FsmObserver<LeaderElectionState, ClusterFsmEvent> recorder() {
        return new FsmObserver<>() {
            @Override
            public void onTransition(FsmTags tags, LeaderElectionState from, LeaderElectionState to) {
                trace.add(to);
            }

            @Override
            public void onCasLost(FsmTags tags, LeaderElectionState expected, LeaderElectionState actual) {}

            @Override
            public void onEventIgnored(FsmTags tags, LeaderElectionState state, ClusterFsmEvent event) {
                trace.add(event);
            }

            @Override
            public void onHandled(FsmTags tags, LeaderElectionState state, ClusterFsmEvent event) {
                trace.add(event);
            }
        };
    }

    private static void ignoreChange(LeaderChange change) {}

    private LeaderElectionContext context(Fsm<LeaderElectionState, ClusterFsmEvent> fsm,
                                          MessageRouter router,
                                          Timings timings) {
        LeaderProposalHandler neverSettling = (_, _) -> Promise.promise();

        return new LeaderElectionContext(fsm,
                                         SELF,
                                         Option.some(neverSettling),
                                         CORES,
                                         router,
                                         timings.proposalRetryDelay(),
                                         LONG,
                                         LONG,
                                         LONG,
                                         LeaderElectionContext.DEFAULT_STUCK_ELECTION_THRESHOLD,
                                         this::jitter,
                                         LeaderElectionContext.DEFAULT_RABIA_TERM_SUPPLIER,
                                         () -> false,
                                         this::kv,
                                         LONG,
                                         timings.peerObservationInterval(),
                                         timings.leaseCheckInterval(),
                                         this::pingFresh,
                                         timings.leaseWarmup());
    }
}
