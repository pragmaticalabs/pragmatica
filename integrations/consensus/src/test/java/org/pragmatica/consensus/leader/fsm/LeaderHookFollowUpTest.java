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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.leader.LeaderManager.FsmBackedLeaderManager;
import org.pragmatica.consensus.leader.LeaderManager.LeaderProposalHandler;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.TransportObservation.ObservationSource;
import org.pragmatica.consensus.topology.TransportObservation.PeerJoined;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmObserver;
import org.pragmatica.statemachine.FsmTags;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// #3587 ruling (know 4829e525dc): a follow-up event decided in a hook is delivered when the dispatch whose transition
/// ran the hook returns, after the observer callback, before the rest of the task and before any other event of the
/// node. Each test names the order it pins and the arm that turns it RED.
class LeaderHookFollowUpTest {
    private static final NodeId SELF = new NodeId("core-0");
    private static final NodeId PEER = new NodeId("core-1");
    private static final List<NodeId> CORES = List.of(SELF, PEER);
    private static final List<NodeId> VIEW = List.of(SELF, PEER);
    private static final TimeSpan LONG = TimeSpan.timeSpan(60).seconds();

    private final List<LeaderElectionState> transitions = new CopyOnWriteArrayList<>();
    private final AtomicBoolean consensusReady = new AtomicBoolean();

    private final AtomicReference<Option<LeaderElectionEvents.LeaderCommitted>> kvLeader = new AtomicReference<>(Option.none());

    /// F1 (v3605 report): a local `Led` entry with its leader out of the view loses it as the entry's follow-up, before
    /// a view already queued behind the commit. That view re-admits the leader and the local election re-adopts it.
    /// RED with the loss queued at the tail (the `enqueue` of 6684f6257d): the stale loss runs after the re-admission
    /// and drops a leader that is back in the view (`ReElecting`).
    @Test
    void localLedEntry_queuedReadmission_beforeStaleOutOfView_keepsTheLeader() throws InterruptedException {
        var manager = localQuorumWaiting();
        var held = holdDrainer(manager.context());

        manager.onLeaderCommitted(SELF);
        manager.peerJoined(new PeerJoined(PEER, VIEW, ObservationSource.QUIC, 1));
        held.release();
        assertThat(manager.context().currentTopology()).as("instrument: SELF in the stored view").contains(SELF);
        assertThat(manager.context().fsm().current()).as("a leader re-admitted after its loss is kept")
                  .isInstanceOf(LeaderElectionState.Led.class);
        assertThat(manager.leader()).isEqualTo(Option.some(SELF));
    }

    /// Control for F1: the same two calls without a held drainer (no interleave) end in `Led(SELF)` in every arm.
    @Test
    void localLedEntry_sequential_control_endsLed() {
        var manager = localQuorumWaiting();

        manager.onLeaderCommitted(SELF);
        manager.peerJoined(new PeerJoined(PEER, VIEW, ObservationSource.QUIC, 1));
        assertThat(manager.context().fsm().current()).isInstanceOf(LeaderElectionState.Led.class);
        assertThat(manager.leader()).isEqualTo(Option.some(SELF));
    }

    /// F4, `QuorumWaiting` path: consensus ready on entry, so the entry follows up with `ConsensusReady`. Observers see
    /// `QuorumWaiting` before `AwaitingKvSync`. RED with the follow-up dispatched nested inside the hook (6684f6257d):
    /// the nested transition is observed first.
    @Test
    void quorumWaitingEntry_followUp_observedAfterTheOuterTransition() {
        consensusReady.set(true);
        var manager = clusterManager();

        manager.watchClusterState(ClusterStateNotification.active());
        assertThat(transitions).hasSize(2);
        assertThat(transitions.get(0)).isInstanceOf(LeaderElectionState.QuorumWaiting.class);
        assertThat(transitions.get(1)).isInstanceOf(LeaderElectionState.AwaitingKvSync.class);
    }

    /// F4, `AwaitingKvSync` path: re-entered from `QuorumLost` while the KV record names a leader, so the entry follows
    /// up with its `LeaderCommitted`. Observers see `AwaitingKvSync` before `Led`. RED with the nested dispatch
    /// (6684f6257d). No timer can interleave: consensus is ready on entry (no poll), the grace window is 60 s.
    @Test
    void awaitingKvSyncEntry_followUp_observedAfterTheOuterTransition() {
        consensusReady.set(true);
        var manager = clusterManager();

        manager.watchClusterState(ClusterStateNotification.active());
        manager.watchClusterState(ClusterStateNotification.passive());
        assertThat(manager.context().fsm().current()).as("instrument: QuorumLost")
                  .isInstanceOf(LeaderElectionState.QuorumLost.class);
        kvLeader.set(Option.some(new LeaderElectionEvents.LeaderCommitted(PEER)));
        manager.watchClusterState(ClusterStateNotification.active());
        assertThat(transitions).hasSize(5);
        assertThat(transitions.get(3)).isInstanceOf(LeaderElectionState.AwaitingKvSync.class);
        assertThat(transitions.get(4)).isInstanceOf(LeaderElectionState.Led.class);
        assertThat(manager.leader()).isEqualTo(Option.some(PEER));
    }

    /// Ruling (2): the follow-up runs before the REST of the task that ran the hook. Local `onClusterActive` submits
    /// `QuorumEstablished`, `ConsensusReady` and the local election's `LeaderCommitted(core-0)` in one task; the
    /// `AwaitingKvSync` entry follows up with the leader the node already knew (core-1). The known leader is adopted
    /// first and the local election, decided after it, wins: `Led(core-0)`. RED with a task-end drain (the follow-up
    /// run after the task): the stale known leader is applied last and swaps the tenure to `Led(core-1)`.
    @Test
    void localClusterActive_knownLeaderFollowUp_runsBeforeTheLocalElection() {
        var manager = localDormantWithKnownLeader();

        manager.watchClusterState(ClusterStateNotification.active());
        assertThat(manager.context().fsm().current()).isInstanceOf(LeaderElectionState.Led.class);
        assertThat(manager.leader()).as("the local election, decided after the known leader, wins")
                  .isEqualTo(Option.some(SELF));
    }

    private FsmBackedLeaderManager localQuorumWaiting() {
        var manager = manager(true);
        var ctx = manager.context();

        ctx.serially(() -> ctx.fsm()
                              .dispatch(new ClusterFsmEvent.QuorumEstablished()));
        assertThat(ctx.fsm().current()).as("instrument: QuorumWaiting")
                  .isInstanceOf(LeaderElectionState.QuorumWaiting.class);
        assertThat(ctx.currentTopology()).as("instrument: SELF not in the stored view").doesNotContain(SELF);

        return manager;
    }

    private FsmBackedLeaderManager localDormantWithKnownLeader() {
        var manager = manager(true);
        var ctx = manager.context();

        ctx.serially(() -> knowLeaderInView(ctx));
        assertThat(ctx.currentTopology()).as("instrument: both cores in the stored view").containsExactly(SELF, PEER);
        assertThat(ctx.localLeaderCandidate()).as("instrument: the local election picks core-0")
                  .isEqualTo(Option.some(SELF));
        assertThat(ctx.currentLeader()).as("instrument: core-1 is the leader already known")
                  .isEqualTo(Option.some(PEER));

        return manager;
    }

    private static void knowLeaderInView(LeaderElectionContext ctx) {
        ctx.applyTopology(new ClusterFsmEvent.NodeAdded(PEER, VIEW, 1));
        ctx.setCurrentLeader(Option.some(PEER));
    }

    private FsmBackedLeaderManager clusterManager() {
        return manager(false);
    }

    private FsmBackedLeaderManager manager(boolean local) {
        var router = MessageRouter.mutable();

        router.addRoute(LeaderChange.class, LeaderHookFollowUpTest::ignoreChange);
        var fsm = Fsm.<LeaderElectionState, ClusterFsmEvent> fsm("leader-follow-up",
                                                                 f -> context(f, router, local).dormant(),
                                                                 recorder());

        return new FsmBackedLeaderManager(fsm,
                                          fsm.current().ctx(),
                                          local);
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

    private FsmObserver<LeaderElectionState, ClusterFsmEvent> recorder() {
        return new FsmObserver<>() {
            @Override
            public void onTransition(FsmTags tags, LeaderElectionState from, LeaderElectionState to) {
                transitions.add(to);
            }

            @Override
            public void onCasLost(FsmTags tags, LeaderElectionState expected, LeaderElectionState actual) {}

            @Override
            public void onEventIgnored(FsmTags tags, LeaderElectionState state, ClusterFsmEvent event) {}

            @Override
            public void onHandled(FsmTags tags, LeaderElectionState state, ClusterFsmEvent event) {}
        };
    }

    private static void ignoreChange(LeaderChange change) {}

    /// Local mode is the absence of a proposal handler ([LeaderElectionContext#isLocalMode]); a cluster-mode node gets
    /// one that never settles.
    private LeaderElectionContext context(Fsm<LeaderElectionState, ClusterFsmEvent> fsm,
                                          MessageRouter router,
                                          boolean local) {
        LeaderProposalHandler neverSettling = (_, _) -> Promise.promise();
        var handler = local
                      ? Option.<LeaderProposalHandler> none()
                      : Option.some(neverSettling);

        return new LeaderElectionContext(fsm,
                                         SELF,
                                         noHandler,
                                         CORES,
                                         router,
                                         LONG,
                                         LONG,
                                         LONG,
                                         LONG,
                                         LeaderElectionContext.DEFAULT_STUCK_ELECTION_THRESHOLD,
                                         LeaderElectionContext.DEFAULT_JITTER_SOURCE,
                                         LeaderElectionContext.DEFAULT_RABIA_TERM_SUPPLIER,
                                         consensusReady::get,
                                         kvLeader::get,
                                         LONG,
                                         LONG);
    }
}
