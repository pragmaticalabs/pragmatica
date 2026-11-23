/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.consensus.leader.fsm;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.leader.LeaderManager.FsmBackedLeaderManager;
import org.pragmatica.consensus.leader.LeaderManager.LeaderProposalHandler;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.TransportObservation.ObservationSource;
import org.pragmatica.consensus.topology.TransportObservation.PeerDisconnected;
import org.pragmatica.consensus.topology.TransportObservation.PeerJoined;
import org.pragmatica.consensus.topology.TransportObservation.PeerObservedFaulty;
import org.pragmatica.consensus.topology.TransportObservation.PeerReconnected;
import org.pragmatica.consensus.topology.TransportObservation.SelfShutdown;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmObserver;
import org.pragmatica.statemachine.FsmTags;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;


/// #3587 census: every `LeaderManager` entry point that delivers a leader-election event enters the node's one serial
/// executor. Instrument: another thread holds the drainer (a task blocked on a latch); an entry point called meanwhile
/// must return without its event reaching the FSM, and the event is handled once the drainer is released. An entry point
/// that bypassed the executor would be handled on the caller's thread while the drainer is held: RED.
///
/// Not covered here: `installVoterConfiguration` and the pre-vote request/response (their wiring needs a roster and a
/// pre-vote electorate); they take the same `serially` path in `LeaderManager`.
class LeaderSerialDispatchCensusTest {
    private static final NodeId SELF = new NodeId("core-0");
    private static final NodeId PEER = new NodeId("core-1");
    private static final List<NodeId> CORES = List.of(SELF, PEER);
    private static final List<NodeId> VIEW = List.of(SELF, PEER);
    private static final TimeSpan LONG = TimeSpan.timeSpan(60).seconds();

    private final List<Object> observed = new CopyOnWriteArrayList<>();

    static Stream<Arguments> entryPoints() {
        return Stream.of(entry("installPassiveCoreDirectory", manager -> manager.installPassiveCoreDirectory(CORES)),
                         entry("onLeaderCommitted", manager -> manager.onLeaderCommitted(PEER)),
                         entry("onLeaderCommitted(sequence)", manager -> manager.onLeaderCommitted(PEER, 5)),
                         entry("triggerElection", FsmBackedLeaderManager::triggerElection),
                         entry("stop", FsmBackedLeaderManager::stop),
                         entry("peerJoined",
                               manager -> manager.peerJoined(new PeerJoined(PEER, VIEW, ObservationSource.QUIC, 1))),
                         entry("peerDisconnected",
                               manager -> manager.peerDisconnected(new PeerDisconnected(PEER,
                                                                                        List.of(SELF),
                                                                                        ObservationSource.QUIC,
                                                                                        1))),
                         entry("peerReconnected",
                               manager -> manager.peerReconnected(new PeerReconnected(PEER,
                                                                                      VIEW,
                                                                                      ObservationSource.QUIC,
                                                                                      1))),
                         entry("peerObservedFaulty",
                               manager -> manager.peerObservedFaulty(new PeerObservedFaulty(PEER,
                                                                                            List.of(SELF),
                                                                                            ObservationSource.SWIM))),
                         entry("selfShutdown",
                               manager -> manager.selfShutdown(new SelfShutdown(SELF, VIEW, ObservationSource.QUIC))),
                         entry("watchClusterState",
                               manager -> manager.watchClusterState(ClusterStateNotification.passive())));
    }

    private static Arguments entry(String name, Consumer<FsmBackedLeaderManager> call) {
        return Arguments.of(name, call);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entryPoints")
    void entryPoint_whileAnotherThreadDrains_reachesTheFsmOnlyThroughTheDrainer(String name,
                                                                                Consumer<FsmBackedLeaderManager> call) throws InterruptedException {
        var manager = dormantManager();
        var held = holdDrainer(manager.context());

        call.accept(manager);
        assertThat(observed).as("%s reached the FSM while another thread held the serial executor: it bypassed it", name)
                  .isEmpty();
        held.release();
        assertThat(observed).as("%s: its event is handled once the drainer is released", name).isNotEmpty();
    }

    /// Shutdown is drained up to the stop and rejects the rest: an event submitted before `stop` is handled, one
    /// submitted after it (queued behind it, or later) never reaches the FSM.
    @Test
    void stop_eventsBeforeItHandledEventsAfterItRejected() throws InterruptedException {
        var manager = dormantManager();
        var held = holdDrainer(manager.context());

        manager.peerJoined(new PeerJoined(PEER, VIEW, ObservationSource.QUIC, 1));
        manager.stop();
        manager.peerJoined(new PeerJoined(PEER, VIEW, ObservationSource.QUIC, 2));
        held.release();
        manager.peerJoined(new PeerJoined(PEER, VIEW, ObservationSource.QUIC, 3));
        assertThat(manager.context().fsm().current()).isInstanceOf(LeaderElectionState.Stopped.class);
        assertThat(observedSequences()).as("only the view submitted before the stop reached the FSM")
                  .containsExactly(1L);
    }

    /// FER at the executor: a task that throws is logged and dropped, and the event already queued BEHIND it still
    /// reaches the FSM in the same drain (the queue is not stranded), as does one submitted afterwards.
    @Test
    void failingTask_doesNotStallTheQueue() throws InterruptedException {
        var manager = dormantManager();
        var held = holdDrainer(manager.context());

        manager.context().serially(LeaderSerialDispatchCensusTest::failingTask);
        manager.peerJoined(new PeerJoined(PEER, VIEW, ObservationSource.QUIC, 1));
        held.release();
        assertThat(observedSequences()).as("the event queued behind a failing task ran in the same drain")
                  .containsExactly(1L);
        manager.peerJoined(new PeerJoined(PEER, VIEW, ObservationSource.QUIC, 2));
        assertThat(observedSequences()).as("the event queued behind a failing task, then a later one, are handled")
                  .containsExactly(1L, 2L);
    }

    /// F3: the executor closes even when the `Shutdown` dispatch throws (here its observer callback): an event after
    /// the stop is still rejected. RED without the `finally` in `shutdownInOrder`: the executor stays open and the
    /// later view is handled in `Stopped`.
    @Test
    void stop_whenShutdownDispatchThrows_stillClosesTheExecutor() {
        var router = MessageRouter.mutable();

        router.addRoute(LeaderChange.class, LeaderSerialDispatchCensusTest::ignoreChange);
        var fsm = Fsm.<LeaderElectionState, ClusterFsmEvent> fsm("leader-serial-census-throwing-stop",
                                                                 f -> context(f, router).dormant(),
                                                                 throwingOnStop());
        var manager = new FsmBackedLeaderManager(fsm,
                                                 fsm.current().ctx(),
                                                 false);

        manager.stop();
        assertThat(fsm.current()).as("instrument: Shutdown was dispatched")
                  .isInstanceOf(LeaderElectionState.Stopped.class);
        manager.peerJoined(new PeerJoined(PEER, VIEW, ObservationSource.QUIC, 1));
        assertThat(observedSequences()).as("no event reaches the FSM after a stop whose Shutdown dispatch threw")
                  .isEmpty();
    }

    private FsmObserver<LeaderElectionState, ClusterFsmEvent> throwingOnStop() {
        var recording = recorder();

        return new FsmObserver<>() {
            @Override
            public void onTransition(FsmTags tags, LeaderElectionState from, LeaderElectionState to) {
                failOnStopped(to);
            }

            @Override
            public void onCasLost(FsmTags tags, LeaderElectionState expected, LeaderElectionState actual) {}

            @Override
            public void onEventIgnored(FsmTags tags, LeaderElectionState state, ClusterFsmEvent event) {
                recording.onEventIgnored(tags, state, event);
            }

            @Override
            public void onHandled(FsmTags tags, LeaderElectionState state, ClusterFsmEvent event) {
                recording.onHandled(tags, state, event);
            }
        };
    }

    private static void failOnStopped(LeaderElectionState to) {
        if (to instanceof LeaderElectionState.Stopped) {
            throw new IllegalStateException("#3587 probe: a Shutdown listener that throws");
        }
    }

    private static void failingTask() {
        throw new IllegalStateException("#3587 probe: a failing task");
    }

    private List<Long> observedSequences() {
        return observed.stream()
                       .filter(ClusterFsmEvent.NodeAdded.class::isInstance)
                       .map(ClusterFsmEvent.NodeAdded.class::cast)
                       .map(ClusterFsmEvent.NodeAdded::sequence)
                       .toList();
    }

    /// A drainer held by another thread until [HeldDrainer#release].
    private record HeldDrainer(Thread holder, CountDownLatch latch) {
        void release() throws InterruptedException {
            latch.countDown();
            holder.join(TimeUnit.SECONDS.toMillis(10));
            assertThat(holder.isAlive()).as("instrument: the holder finished draining").isFalse();
        }
    }

    private static HeldDrainer holdDrainer(LeaderElectionContext ctx) throws InterruptedException {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holder = Thread.ofPlatform().start(() -> ctx.serially(() -> block(entered, release)));

        assertThat(entered.await(10, TimeUnit.SECONDS)).as("instrument: the holder owns the drainer").isTrue();

        return new HeldDrainer(holder, release);
    }

    private static void block(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        try {
            release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private FsmBackedLeaderManager dormantManager() {
        var router = MessageRouter.mutable();

        router.addRoute(LeaderChange.class, LeaderSerialDispatchCensusTest::ignoreChange);
        var fsm = Fsm.<LeaderElectionState, ClusterFsmEvent> fsm("leader-serial-census",
                                                                 f -> context(f, router).dormant(),
                                                                 recorder());
        var ctx = fsm.current().ctx();

        return new FsmBackedLeaderManager(fsm, ctx, false);
    }

    private FsmObserver<LeaderElectionState, ClusterFsmEvent> recorder() {
        return new FsmObserver<>() {
            @Override
            public void onTransition(FsmTags tags, LeaderElectionState from, LeaderElectionState to) {
                observed.add(to);
            }

            @Override
            public void onCasLost(FsmTags tags, LeaderElectionState expected, LeaderElectionState actual) {}

            @Override
            public void onEventIgnored(FsmTags tags, LeaderElectionState state, ClusterFsmEvent event) {
                observed.add(event);
            }

            @Override
            public void onHandled(FsmTags tags, LeaderElectionState state, ClusterFsmEvent event) {
                observed.add(event);
            }
        };
    }

    private static void ignoreChange(LeaderChange change) {}

    private static LeaderElectionContext context(Fsm<LeaderElectionState, ClusterFsmEvent> fsm, MessageRouter router) {
        LeaderProposalHandler neverSettling = (_, _) -> Promise.promise();
        var kvLeader = new AtomicReference<Option<LeaderElectionEvents.LeaderCommitted>>(Option.none());

        return new LeaderElectionContext(fsm,
                                         SELF,
                                         Option.some(neverSettling),
                                         CORES,
                                         router,
                                         LONG,
                                         LONG,
                                         LONG,
                                         LONG,
                                         LeaderElectionContext.DEFAULT_STUCK_ELECTION_THRESHOLD,
                                         LeaderElectionContext.DEFAULT_JITTER_SOURCE,
                                         LeaderElectionContext.DEFAULT_RABIA_TERM_SUPPLIER,
                                         () -> true,
                                         kvLeader::get,
                                         LONG,
                                         LONG);
    }
}
