/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */

package org.pragmatica.consensus.leader.fsm;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.pragmatica.testlogging.LogCapture;
import org.pragmatica.utility.react.ReactExecutor;
import org.pragmatica.utility.react.ReactFailureSink;
import org.pragmatica.utility.react.ReactGate;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/// Exit (ab), leader re-activation kind (KVStore dispatcher design §5, §3.5.1 row 107; D1-react): a LeaderKey commit
/// absorbed before an engine-only stop, whose route runs after it, never re-activates a leader-pinned subscriber. The
/// election FSM step stays in absorb (`leader()` moves at once); only the `LeaderChange` route is a reaction. Replaces the
/// C9 tripwire LeaderChangeEngineStopTripwireTest, which pinned the inline route this gate removes.
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class LeaderChangeEngineStopGateTest {
    private static final String COMPONENT = "LeaderManager";

    /// Exit (ab) log line (design R5: logged once per component per stop): the drop warns once; closed in tearDown.
    private final LogCapture dropLog = LogCapture.capture(ReactExecutor.class, Level.WARN, false);
    private static final NodeId SELF = new NodeId("node-1");
    private static final NodeId PEER = new NodeId("node-2");
    private static final NodeId THIRD = new NodeId("node-3");
    private static final TimeSpan LONG = TimeSpan.timeSpan(60).seconds();

    /// The react executor under test, its engine flag (the gate's engine half) and the hold seam (§5 (ab): "hold the
    /// component's react executor, a latch on its next task"). The hold is an UNDO task, so the gate never drops it.
    private final AtomicBoolean engineStopped = new AtomicBoolean(false);
    private final ReactExecutor executor = ReactExecutor.reactExecutor(COMPONENT,
                                                                       ReactGate.reactGate(() -> !engineStopped.get(),
                                                                                           () -> false),
                                                                       ReactFailureSink.loggingOnly());

    private CountDownLatch hold() throws InterruptedException {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        executor.undo("test-hold", () -> awaitQuietly(started, release));
        assertThat(started.await(10, TimeUnit.SECONDS)).as("hold task started").isTrue();

        return release;
    }

    private void drain() throws InterruptedException {
        var done = new CountDownLatch(1);

        executor.undo("test-drain", done::countDown);
        assertThat(done.await(10, TimeUnit.SECONDS)).as("executor drained").isTrue();
    }

    private static void awaitQuietly(CountDownLatch started, CountDownLatch release) {
        started.countDown();
        try {
            release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private final List<LeaderChange> recorded = new CopyOnWriteArrayList<>();
    private FsmBackedLeaderManager manager;

    @BeforeEach
    void setUp() throws InterruptedException {
        var router = MessageRouter.mutable();
        router.addRoute(LeaderChange.class, recorded::add);
        var harness = FsmTestHarness.<LeaderElectionState, ClusterFsmEvent>harness("leader-change-engine-stop-gate",
                                                                                    fsm -> context(fsm, router).dormant());
        var ctx = ((LeaderElectionState) harness.state()).ctx();
        manager = new FsmBackedLeaderManager(ctx.fsm(), ctx, false);
        manager.installReactExecutor(executor);
        harness.dispatch(new ClusterFsmEvent.QuorumEstablished());
        drain();
        recorded.clear();
    }

    @AfterEach
    void tearDown() {
        manager.stop();
        executor.close();
        dropLog.close();
    }

    @Test
    void selfLeaderCommit_routeQueuedBeforeEngineStop_isDroppedAndCounted() throws InterruptedException {
        var release = hold();

        manager.onLeaderCommitted(SELF, 5);
        assertThat(manager.leader()).as("the FSM step stays in absorb").isEqualTo(Option.some(SELF));
        assertThat(executor.queueDepth()).as("the route is queued, so the probe reaches the gate").isEqualTo(1);
        engineStopped.set(true);
        release.countDown();
        drain();

        assertThat(recorded).as("no leader re-activation after the engine stop").isEmpty();
        assertThat(executor.droppedEngineStopped()).as("dropped count rose by exactly 1").isEqualTo(1);
        assertThat(dropLog.messages()).as("one warn for the first drop, naming the component")
                                      .hasSize(1)
                                      .allMatch(message -> message.contains("Engine stopped: react executor " + COMPONENT
                                                                            + " drops side-effect reactions"));
    }

    @Test
    void selfLeaderCommit_engineAlive_routesOnce_dropsNothing() throws InterruptedException {
        var release = hold();

        manager.onLeaderCommitted(SELF, 5);
        assertThat(executor.queueDepth()).isEqualTo(1);
        release.countDown();
        drain();

        assertThat(recorded).as("control arm: exactly one LeaderChange, carrying its own leader")
                            .containsExactly(new LeaderChange(Option.some(SELF), true));
        assertThat(executor.droppedEngineStopped()).isZero();
    }

    @Test
    void otherLeaderCommit_isAnUndo_routedEvenAfterEngineStop() throws InterruptedException {
        var release = hold();

        manager.onLeaderCommitted(PEER, 5);
        engineStopped.set(true);
        release.countDown();
        drain();

        assertThat(recorded).as("a change away from this node deactivates; it is never gated")
                            .containsExactly(new LeaderChange(Option.some(PEER), false));
        assertThat(executor.droppedEngineStopped()).isZero();
    }

    private static LeaderElectionContext context(Fsm<LeaderElectionState, ClusterFsmEvent> fsm, MessageRouter router) {
        LeaderProposalHandler neverSettling = (_, _) -> Promise.promise();

        return new LeaderElectionContext(fsm,
                                         SELF,
                                         Option.some(neverSettling),
                                         List.of(SELF, PEER, THIRD),
                                         router,
                                         LONG,
                                         LONG,
                                         LONG,
                                         LONG,
                                         LeaderElectionContext.DEFAULT_STUCK_ELECTION_THRESHOLD,
                                         LeaderElectionContext.DEFAULT_JITTER_SOURCE,
                                         LeaderElectionContext.DEFAULT_RABIA_TERM_SUPPLIER,
                                         () -> true,
                                         Option::none,
                                         LONG,
                                         LONG);
    }
}
