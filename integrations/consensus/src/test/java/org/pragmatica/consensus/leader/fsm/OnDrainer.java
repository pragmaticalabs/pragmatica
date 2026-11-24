/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.consensus.leader.fsm;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.statemachine.FsmTestHarness;

import static org.assertj.core.api.Assertions.assertThat;

/// Test entry to the leader FSM through the node's serial executor (#3605 round 4). Leader code dispatches only on the
/// serial drainer ([LeaderElectionContext#dispatchConfined] refuses any other caller), so a test that drives the FSM
/// from its own thread enters the way production does: a task on the drainer that submits the event. The call returns
/// only after that task ran: when the drainer is idle it runs inline; when a timer task holds the drainer the task is
/// queued behind it, and the call waits (bounded, never a sleep) for the completion the task sets, so the next
/// assertion of the test never runs ahead of the event (#3605 v3605j row 50). `harness.dispatch(...)` from a test
/// thread is off the drainer: a handler that reaches `dispatchSelf` would be refused.
final class OnDrainer {
    private static final long HANDLED_WITHIN_SECONDS = 10;

    private OnDrainer() {}

    static void dispatch(FsmTestHarness<LeaderElectionState, ClusterFsmEvent> harness, ClusterFsmEvent event) {
        dispatch(harness.state()
                        .ctx(),
                 event);
    }

    static void dispatch(LeaderElectionContext ctx, ClusterFsmEvent event) {
        var handled = new CountDownLatch(1);

        ctx.serially(() -> submitThenComplete(ctx, event, handled));
        awaitHandled(handled);
    }

    /// Returns only after every task submitted to the node's serial executor before this call ran (the executor is FIFO
    /// across submitters): the await for a production entry (`FsmBackedLeaderManager#onLeaderCommitted`) that only
    /// queued its task because a timer task held the drainer. The next assertion then reads the state that task produced.
    static void drained(LeaderElectionContext ctx) {
        var handled = new CountDownLatch(1);

        ctx.serially(handled::countDown);
        awaitHandled(handled);
    }

    private static void submitThenComplete(LeaderElectionContext ctx, ClusterFsmEvent event, CountDownLatch handled) {
        try {
            ctx.submit(event);
        } finally {
            handled.countDown();
        }
    }

    private static void awaitHandled(CountDownLatch handled) {
        try {
            assertThat(handled.await(HANDLED_WITHIN_SECONDS, TimeUnit.SECONDS)).as("instrument: the dispatched event was handled by the drainer")
                                                                               .isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread()
                  .interrupt();
            throw new AssertionError("interrupted while waiting for the drainer to handle the event", e);
        }
    }
}
