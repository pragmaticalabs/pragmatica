/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */

package org.pragmatica.consensus.leader.fsm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.leader.LeaderManager.FsmBackedLeaderManager;
import org.pragmatica.consensus.leader.LeaderManager.LeaderProposalHandler;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;
import org.pragmatica.utility.react.ReactExecutor;
import org.pragmatica.utility.react.ReactFailureSink;
import org.pragmatica.utility.react.ReactGate;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/// Row 107 R3 (KVStore dispatcher design §3.5: a reaction captures the replay bit at absorb; v-2653 F3). The node store's
/// replay bit is a ThreadLocal of the delivering thread (`NotificationDispatcher.replaying`, read by
/// `KVStore.isReplaying`). A LeaderKey put delivered in a replay burst reaches `onLeaderCommitted` on that thread; the
/// `LeaderChange` route runs on the react executor, where the bit reads false, so the change carries it as `replayed`.
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class LeaderChangeReplayBitTest {
    private static final NodeId SELF = new NodeId("node-1");
    private static final TimeSpan LONG = TimeSpan.timeSpan(60).seconds();
    private static final ThreadLocal<Boolean> REPLAYING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final ReactExecutor executor = ReactExecutor.reactExecutor("LeaderManager",
                                                                       ReactGate.alwaysOpen(),
                                                                       ReactFailureSink.loggingOnly());
    private final List<Boolean> carriedBits = new CopyOnWriteArrayList<>();
    private final List<Boolean> receiverThreadBits = new CopyOnWriteArrayList<>();
    private FsmBackedLeaderManager manager;

    @AfterEach
    void tearDown() {
        manager.stop();
        executor.close();
    }

    /// Red before the fix: the receiver could only read the store's bit on its own thread, where it is false, and
    /// announced a replayed leader change as live.
    @Test
    void leaderCommitDeliveredInAReplayBurst_changeCarriesTheReplayBit() throws InterruptedException {
        startQuorate();

        REPLAYING.set(true);              // the dispatcher thread delivering a replay batch
        try {
            manager.onLeaderCommitted(SELF, 5);
        } finally {
            REPLAYING.set(false);
        }
        drain();

        assertThat(receiverThreadBits).as("instrument: the receiver's own thread never sees the store's replay bit")
                                      .containsExactly(false);
        assertThat(carriedBits).as("the change carries the replay bit of the burst that raised it").containsExactly(true);
    }

    /// Control: a live commit carries no replay bit.
    @Test
    void leaderCommitDeliveredLive_changeIsNotReplayed() throws InterruptedException {
        startQuorate();

        manager.onLeaderCommitted(SELF, 5);
        drain();

        assertThat(carriedBits).as("a live change is not marked replayed").containsExactly(false);
    }

    private void startQuorate() throws InterruptedException {
        var router = MessageRouter.mutable();
        router.addRoute(LeaderChange.class, this::record);
        var harness = FsmTestHarness.<LeaderElectionState, ClusterFsmEvent>harness("leader-change-replay-bit",
                                                                                    fsm -> context(fsm, router).dormant());
        var ctx = ((LeaderElectionState) harness.state()).ctx();
        manager = new FsmBackedLeaderManager(ctx.fsm(), ctx, false);
        manager.installReactExecutor(executor);
        manager.installReplaySource(REPLAYING::get);
        harness.dispatch(new ClusterFsmEvent.QuorumEstablished());
        drain();
        carriedBits.clear();
        receiverThreadBits.clear();
    }

    private void record(LeaderChange change) {
        receiverThreadBits.add(REPLAYING.get());
        carriedBits.add(change.replayed());
    }

    private void drain() throws InterruptedException {
        var done = new CountDownLatch(1);

        executor.undo("drain", done::countDown);
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
    }

    private static LeaderElectionContext context(Fsm<LeaderElectionState, ClusterFsmEvent> fsm, MessageRouter router) {
        LeaderProposalHandler neverSettling = (_, _) -> Promise.promise();

        return new LeaderElectionContext(fsm, SELF, Option.some(neverSettling), List.of(SELF, new NodeId("node-2"),
                                                                                          new NodeId("node-3")),
                                         router, LONG, LONG, LONG, LONG,
                                         LeaderElectionContext.DEFAULT_STUCK_ELECTION_THRESHOLD,
                                         LeaderElectionContext.DEFAULT_JITTER_SOURCE,
                                         LeaderElectionContext.DEFAULT_RABIA_TERM_SUPPLIER,
                                         () -> true, Option::none, LONG, LONG);
    }
}
