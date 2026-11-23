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
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.leader.LeaderManager.FsmBackedLeaderManager;
import org.pragmatica.consensus.leader.LeaderManager.LeaderProposalHandler;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.consensus.net.NetworkMessage.LeaderPreVoteRequest;
import org.pragmatica.consensus.net.NetworkMessage.LeaderPreVoteResponse;
import org.pragmatica.consensus.net.NetworkServiceMessage.Send;
import org.pragmatica.consensus.rabia.VoterConfiguration;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.TransportObservation.ObservationSource;
import org.pragmatica.consensus.topology.TransportObservation.PeerDisconnected;
import org.pragmatica.consensus.topology.TransportObservation.PeerJoined;
import org.pragmatica.consensus.topology.TransportObservation.PeerObservedFaulty;
import org.pragmatica.consensus.topology.TransportObservation.PeerReconnected;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmObserver;
import org.pragmatica.statemachine.FsmTags;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

/// #3605: every `LeaderManager` entry point that reads election state before it acts runs its check AND its act as
/// one task of the node's serial executor. [LeaderSerialDispatchCensusTest] proves each event reaches the FSM through
/// the executor; it cannot see the `serially` wrap, because the inner `submit` queues on its own. What the wrap adds is
/// that the READ (eligibility, the stored view, the voter set, the cluster-state sequence, the pre-vote electorate)
/// happens in the order, not on the caller's thread.
///
/// Instrument: another thread holds the drainer; a task that changes what the entry point reads is queued behind it;
/// the entry point is called from this (foreign) thread; the drainer is released and a no-op through the executor is
/// the barrier. Asserted: (a) nothing happened before the release (no event, no send, no write to the state the entry
/// point owns); (b) after it, the outcome is the one computed from the post-task state: no outcome that only the
/// pre-task state produces (`stale`), and, where the post-task state produces one, that outcome (`fresh`). An entry
/// point that read on the caller's thread reads the pre-task state: RED on (a) or (b).
class LeaderEntryPointAtomicityTest {
    private static final NodeId SELF = new NodeId("core-0");
    private static final NodeId PEER = new NodeId("core-1");
    private static final List<NodeId> CORES = List.of(SELF, PEER);
    private static final List<NodeId> VIEW = List.of(SELF, PEER);
    private static final List<NodeId> SELF_ONLY = List.of(SELF);
    private static final TimeSpan LONG = TimeSpan.timeSpan(60).seconds();
    private static final Function<LeaderElectionContext, Object> NO_WRITE = _ -> "no write owned by this entry point";

    private final List<Object> observed = new CopyOnWriteArrayList<>();
    private final List<Object> sent = new CopyOnWriteArrayList<>();

    enum Fixture {
        DORMANT,
        ELECTING,
        LOCAL_DORMANT
    }

    record Case(Fixture fixture,
                Consumer<LeaderElectionContext> prepare,
                Consumer<LeaderElectionContext> change,
                Consumer<FsmBackedLeaderManager> call,
                Function<LeaderElectionContext, Object> owned,
                Predicate<Object> stale,
                Option<Predicate<Object>> fresh) {}

    static Stream<Arguments> entryPoints() {
        return Stream.of(entry("lm01 installVoterConfiguration",
                               new Case(Fixture.DORMANT,
                                        ctx -> ctx.setCurrentLeader(Option.some(PEER)),
                                        ctx -> ctx.setCurrentLeader(Option.none()),
                                        manager -> manager.installVoterConfiguration(selfOnlyVoters()),
                                        LeaderElectionContext::electorate,
                                        event -> event instanceof LeaderElectionEvents.PeerRemovedFromView removed
                                                 && removed.node().equals(PEER),
                                        Option.some(LeaderElectionEvents.VoterReadmitted.class::isInstance))),
                         entry("lm02 leaderPreVoteRequest",
                               new Case(Fixture.ELECTING,
                                        ctx -> ctx.enablePreVote(LONG),
                                        LeaderEntryPointAtomicityTest::removePeerFromVoters,
                                        manager -> manager.leaderPreVoteRequest(new LeaderPreVoteRequest(PEER, SELF, 1)),
                                        NO_WRITE,
                                        LeaderEntryPointAtomicityTest::sendsToPeer,
                                        Option.none())),
                         entry("lm03 leaderPreVoteResponse",
                               new Case(Fixture.DORMANT,
                                        LeaderEntryPointAtomicityTest::suspectPeer,
                                        LeaderEntryPointAtomicityTest::removePeerFromVoters,
                                        manager -> manager.leaderPreVoteResponse(new LeaderPreVoteResponse(PEER,
                                                                                                           PEER,
                                                                                                           1,
                                                                                                           false)),
                                        NO_WRITE,
                                        event -> event instanceof LeaderElectionEvents.LeaderDoubtConfirmed doubt
                                                 && doubt.leader().equals(PEER),
                                        Option.none())),
                         entry("lm04 onLeaderCommitted",
                               new Case(Fixture.DORMANT,
                                        LeaderEntryPointAtomicityTest::nothing,
                                        LeaderEntryPointAtomicityTest::removePeerFromVoters,
                                        manager -> manager.onLeaderCommitted(PEER),
                                        NO_WRITE,
                                        LeaderEntryPointAtomicityTest::commitsPeer,
                                        Option.none())),
                         entry("lm05 onLeaderCommitted(sequence)",
                               new Case(Fixture.DORMANT,
                                        LeaderEntryPointAtomicityTest::nothing,
                                        LeaderEntryPointAtomicityTest::removePeerFromVoters,
                                        manager -> manager.onLeaderCommitted(PEER, 5),
                                        LeaderElectionContext::committedViewSequence,
                                        LeaderEntryPointAtomicityTest::commitsPeer,
                                        Option.none())),
                         entry("lm06 peerJoined",
                               new Case(Fixture.DORMANT,
                                        LeaderEntryPointAtomicityTest::nothing,
                                        LeaderEntryPointAtomicityTest::removePeerFromVoters,
                                        manager -> manager.peerJoined(new PeerJoined(PEER,
                                                                                     VIEW,
                                                                                     ObservationSource.QUIC,
                                                                                     1)),
                                        NO_WRITE,
                                        LeaderEntryPointAtomicityTest::viewHoldsPeer,
                                        Option.some(LeaderEntryPointAtomicityTest::viewIsSelfOnly))),
                         entry("lm07 peerDisconnected",
                               new Case(Fixture.DORMANT,
                                        LeaderEntryPointAtomicityTest::nothing,
                                        LeaderEntryPointAtomicityTest::removePeerFromVoters,
                                        manager -> manager.peerDisconnected(new PeerDisconnected(PEER,
                                                                                                 VIEW,
                                                                                                 ObservationSource.QUIC,
                                                                                                 1)),
                                        NO_WRITE,
                                        LeaderEntryPointAtomicityTest::viewHoldsPeer,
                                        Option.some(LeaderEntryPointAtomicityTest::viewIsSelfOnly))),
                         entry("lm08 peerObservedFaulty (local mode)",
                               new Case(Fixture.LOCAL_DORMANT,
                                        ctx -> ctx.applyTopology(new ClusterFsmEvent.NodeAdded(PEER, VIEW, 1)),
                                        ctx -> ctx.applyTopology(new ClusterFsmEvent.NodeGone(PEER, SELF_ONLY, 2)),
                                        manager -> manager.peerObservedFaulty(new PeerObservedFaulty(PEER,
                                                                                                     SELF_ONLY,
                                                                                                     ObservationSource.SWIM)),
                                        LeaderElectionContext::currentTopology,
                                        event -> event instanceof LeaderElectionEvents.PeerRemovedFromView removed
                                                 && removed.node().equals(PEER),
                                        Option.none())),
                         entry("lm09 peerReconnected",
                               new Case(Fixture.DORMANT,
                                        LeaderEntryPointAtomicityTest::nothing,
                                        LeaderEntryPointAtomicityTest::removePeerFromVoters,
                                        manager -> manager.peerReconnected(new PeerReconnected(PEER,
                                                                                               VIEW,
                                                                                               ObservationSource.QUIC,
                                                                                               1)),
                                        NO_WRITE,
                                        LeaderEntryPointAtomicityTest::viewHoldsPeer,
                                        Option.some(LeaderEntryPointAtomicityTest::viewIsSelfOnly))),
                         entry("lm10 watchClusterState",
                               new Case(Fixture.DORMANT,
                                        LeaderEntryPointAtomicityTest::nothing,
                                        LeaderEntryPointAtomicityTest::observeNewerClusterState,
                                        manager -> manager.watchClusterState(ClusterStateNotification.passive()),
                                        ctx -> ctx.quorumSequence().get(),
                                        ClusterFsmEvent.QuorumDisappeared.class::isInstance,
                                        Option.none())));
    }

    private static Arguments entry(String name, Case testCase) {
        return Arguments.of(name, testCase);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entryPoints")
    void entryPoint_checkThenAct_isOneTaskOfTheSerialOrder(String name, Case testCase) throws InterruptedException {
        var manager = manager(testCase.fixture());
        var ctx = manager.context();

        testCase.prepare().accept(ctx);
        observed.clear();
        sent.clear();
        var ownedBefore = testCase.owned().apply(ctx);
        var held = holdDrainer(ctx);

        ctx.serially(() -> testCase.change().accept(ctx));
        testCase.call().accept(manager);
        assertThat(outcome()).as("%s acted on the caller's thread while another thread held the serial executor", name)
                             .isEmpty();
        assertThat(testCase.owned().apply(ctx)).as("%s wrote election state on the caller's thread before the release",
                                                   name)
                                               .isEqualTo(ownedBefore);
        held.release();
        ctx.serially(LeaderEntryPointAtomicityTest::nothing);
        assertThat(outcome()).as("%s acted on the state read before the queued task ran: its check and its act are not "
                                 + "one task",
                                 name)
                             .noneMatch(testCase.stale());
        testCase.fresh()
                .onPresent(fresh -> assertThat(outcome()).as("%s: the outcome computed from the post-task state", name)
                                                         .anyMatch(fresh));
    }

    private List<Object> outcome() {
        return Stream.concat(observed.stream(), sent.stream())
                     .toList();
    }

    private static VoterConfiguration selfOnlyVoters() {
        return VoterConfiguration.voterConfiguration(1, SELF_ONLY)
                                 .unwrap();
    }

    private static void removePeerFromVoters(LeaderElectionContext ctx) {
        ctx.installVoters(SELF_ONLY);
    }

    /// An episode about PEER, round 1, asking the electorate (PEER included): PEER's doubt makes the majority.
    private static void suspectPeer(LeaderElectionContext ctx) {
        ctx.enablePreVote(LONG);
        ctx.preVote()
           .onPresent(preVote -> preVote.suspect(PEER));
    }

    /// A newer cluster-state notification observed first: the entry point's own notification is then stale.
    private static void observeNewerClusterState(LeaderElectionContext ctx) {
        new ClusterStateNotification(ClusterStateNotification.State.ACTIVE, Long.MAX_VALUE).advanceSequence(ctx.quorumSequence());
    }

    private static void nothing(LeaderElectionContext ctx) {}

    private static void nothing() {}

    private static boolean sendsToPeer(Object message) {
        return message instanceof Send send && send.target()
                                                   .equals(PEER);
    }

    private static boolean commitsPeer(Object event) {
        return event instanceof LeaderElectionEvents.LeaderCommitted committed && committed.leader()
                                                                                           .equals(PEER);
    }

    private static boolean viewHoldsPeer(Object event) {
        return event instanceof ClusterFsmEvent.TopologyChange change && change.topology()
                                                                               .contains(PEER);
    }

    private static boolean viewIsSelfOnly(Object event) {
        return event instanceof ClusterFsmEvent.TopologyChange change && change.topology()
                                                                               .equals(SELF_ONLY);
    }

    /// A drainer held by another thread until [HeldDrainer#release].
    private record HeldDrainer(Thread holder, CountDownLatch latch) {
        void release() throws InterruptedException {
            latch.countDown();
            holder.join(TimeUnit.SECONDS.toMillis(10));
            assertThat(holder.isAlive()).as("instrument: the holder finished draining")
                                        .isFalse();
        }
    }

    private static HeldDrainer holdDrainer(LeaderElectionContext ctx) throws InterruptedException {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holder = Thread.ofPlatform()
                           .start(() -> ctx.serially(() -> block(entered, release)));

        assertThat(entered.await(10, TimeUnit.SECONDS)).as("instrument: the holder owns the drainer")
                                                       .isTrue();

        return new HeldDrainer(holder, release);
    }

    private static void block(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        try {
            release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread()
                  .interrupt();
        }
    }

    private FsmBackedLeaderManager manager(Fixture fixture) {
        var router = MessageRouter.mutable();

        router.addRoute(LeaderChange.class, LeaderEntryPointAtomicityTest::ignoreChange);
        router.addRoute(Send.class, sent::add);
        var fsm = Fsm.<LeaderElectionState, ClusterFsmEvent> fsm("leader-entry-point-atomicity",
                                                                 f -> initial(context(f, router), fixture),
                                                                 recorder());

        return new FsmBackedLeaderManager(fsm, fsm.current().ctx(), fixture == Fixture.LOCAL_DORMANT);
    }

    /// The initial state is set without an `onEnter` (`Fsm#fsm`), so `Electing` arms no timer: it only gives the
    /// pre-vote a stance to answer with.
    private static LeaderElectionState initial(LeaderElectionContext ctx, Fixture fixture) {
        return fixture == Fixture.ELECTING
               ? ctx.electing()
               : ctx.dormant();
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
