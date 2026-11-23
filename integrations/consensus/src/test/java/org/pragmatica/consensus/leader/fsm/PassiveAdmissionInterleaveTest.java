/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */

package org.pragmatica.consensus.leader.fsm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.leader.LeaderManager.FsmBackedLeaderManager;
import org.pragmatica.consensus.leader.LeaderManager.LeaderProposalHandler;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderCommitted;
import org.pragmatica.consensus.topology.TransportObservation.ObservationSource;
import org.pragmatica.consensus.topology.TransportObservation.PeerDisconnected;
import org.pragmatica.consensus.topology.TransportObservation.PeerJoined;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;

import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderCommitted.leaderCommitted;

/// #3587 (TLA+ leader-admission model, row `interleave`): `Passive`'s push admission (`observeCommittedLeader`: the
/// leader is in the stored view, then it is adopted) runs on the KV notification thread, and a view write that drops
/// the leader (`storePassiveView` -> `holdLeaderToView`) runs on the transport thread. When the view write lands
/// between the admission's read of the stored view and the adoption, `holdLeaderToView` finds no held leader yet, and
/// the adoption then stores a leader the stored view no longer holds: `Passive` holds and announces a leader absent
/// from its view until the next sequenced view write.
///
/// The stress drives exactly those two calls into one worker's `LeaderManager` from two threads, released together
/// by a barrier, with a random spin on the view side to sweep the offset between them, once per fresh worker. The
/// outcome checked after both calls returned is I2 for `Passive`: a held leader is in the stored view. Before the fix
/// (the two calls dispatched on their own threads) the racy outcome was observed within 0.3 s of a 20,000-race run; with
/// every event of the node in one serial executor (#3587) the two calls are handled one after the other.
class PassiveAdmissionInterleaveTest {
    private static final NodeId SELF = new NodeId("worker-1");
    private static final NodeId LEADER = new NodeId("core-1");
    private static final NodeId OTHER = new NodeId("core-2");
    private static final List<NodeId> CORES = List.of(LEADER, OTHER);
    private static final long COMMIT_SEQUENCE = 5;
    /// 30 repetitions of 1,000 races each (30,000 fresh workers): the repeat count #3587 asks for, run on every build.
    private static final int REPETITIONS = 30;
    private static final int ITERATIONS = 1_000;
    private static final int MAX_VIEW_SPIN = 64;
    private static final TimeSpan LONG = TimeSpan.timeSpan(60).seconds();

    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    @AfterEach
    void shutdownPool() {
        pool.shutdownNow();
    }

    /// The real assertion: no interleaving of the push admission and the view write leaves a held leader out of view.
    @RepeatedTest(REPETITIONS)
    void passiveNeverHoldsALeaderAbsentFromItsStoredView() throws Exception {
        assertThat(violations(ITERATIONS)).as("iterations ending with a held leader absent from the stored view")
                                          .isZero();
    }

    private int violations(int iterations) throws Exception {
        var count = 0;

        for (int i = 0; i < iterations; i++) {
            if (raceOnce()) {
                count++;
            }
        }

        return count;
    }

    /// One fresh `Passive` worker whose stored view holds the leader; the commit and the view drop race. Returns
    /// `true` iff the worker ends holding a leader its stored view lacks.
    private boolean raceOnce() throws InterruptedException, ExecutionException, TimeoutException {
        var kvLeader = new AtomicReference<Option<LeaderCommitted>>(Option.none());
        var worker = passiveWorkerWithLeaderInView(kvLeader);
        var barrier = new CyclicBarrier(2);

        kvLeader.set(Option.some(leaderCommitted(LEADER, COMMIT_SEQUENCE)));
        var push = pool.submit(() -> pushCommit(worker, barrier));
        var drop = pool.submit(() -> dropLeaderFromView(worker, barrier));

        push.get(10, TimeUnit.SECONDS);
        drop.get(10, TimeUnit.SECONDS);

        return worker.manager()
                     .leader()
                     .filter(leader -> !worker.ctx()
                                              .currentTopology()
                                              .contains(leader))
                     .isPresent();
    }

    private static Void pushCommit(Worker worker, CyclicBarrier barrier) throws InterruptedException,
                                                                               BrokenBarrierException {
        barrier.await();
        worker.manager()
              .onLeaderCommitted(LEADER, COMMIT_SEQUENCE);

        return null;
    }

    private static Void dropLeaderFromView(Worker worker, CyclicBarrier barrier) throws InterruptedException,
                                                                                       BrokenBarrierException {
        barrier.await();
        spin(ThreadLocalRandom.current()
                              .nextInt(MAX_VIEW_SPIN));
        worker.manager()
              .peerDisconnected(new PeerDisconnected(LEADER, List.of(SELF, OTHER), ObservationSource.QUIC, 2));

        return null;
    }

    private static void spin(int rounds) {
        for (int i = 0; i < rounds; i++) {
            Thread.onSpinWait();
        }
    }

    private record Worker(FsmBackedLeaderManager manager, LeaderElectionContext ctx) {}

    private Worker passiveWorkerWithLeaderInView(AtomicReference<Option<LeaderCommitted>> kvLeader) {
        var router = MessageRouter.mutable();

        router.addRoute(LeaderChange.class, PassiveAdmissionInterleaveTest::ignoreChange);
        var harness = FsmTestHarness.<LeaderElectionState, ClusterFsmEvent>harness("passive-admission-interleave",
                                                                                   fsm -> context(fsm,
                                                                                                  router,
                                                                                                  kvLeader).dormant());
        var ctx = ((LeaderElectionState) harness.state()).ctx();
        var manager = new FsmBackedLeaderManager(ctx.fsm(), ctx, false);

        manager.installPassiveCoreDirectory(CORES);
        manager.peerJoined(new PeerJoined(LEADER, List.of(SELF, LEADER, OTHER), ObservationSource.QUIC, 1));
        assertThat(harness.state()).as("instrument: the worker is Passive").isInstanceOf(LeaderElectionState.Passive.class);
        assertThat(ctx.currentTopology()).as("instrument: the stored view holds the leader").contains(LEADER);
        assertThat(manager.leader()).as("instrument: no leader before the race").isEqualTo(Option.none());

        return new Worker(manager, ctx);
    }

    private static void ignoreChange(LeaderChange change) {}

    private static LeaderElectionContext context(Fsm<LeaderElectionState, ClusterFsmEvent> fsm,
                                                 MessageRouter router,
                                                 AtomicReference<Option<LeaderCommitted>> kvLeader) {
        LeaderProposalHandler neverSettling = (_, _) -> Promise.promise();

        return new LeaderElectionContext(fsm, SELF, Option.some(neverSettling), CORES, router, LONG, LONG, LONG, LONG,
                                         LeaderElectionContext.DEFAULT_STUCK_ELECTION_THRESHOLD,
                                         LeaderElectionContext.DEFAULT_JITTER_SOURCE,
                                         LeaderElectionContext.DEFAULT_RABIA_TERM_SUPPLIER,
                                         () -> true, kvLeader::get, LONG, LONG);
    }
}
