/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */

package org.pragmatica.consensus.leader.fsm;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.leader.LeaderManager.FsmBackedLeaderManager;
import org.pragmatica.consensus.leader.LeaderManager.LeaderProposalHandler;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.FsmTestHarness;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/// C9 tripwire, leader re-activation kind (KVStore dispatcher design §3.5.1 row 107, D1-react entry criterion).
///
/// Pins TODAY's behaviour: a `LeaderKey` commit delivered after an engine-only stop still routes `LeaderChange(x)`
/// inline from the absorb path, so a leader-pinned manager subscribed to it is re-activated on a node whose engine is
/// gone. Nothing gates the route: the engine stop has no input into the election FSM's notification path, and that
/// absence is exactly what this test pins (the stop is therefore not simulated: no seam exists to simulate it through).
///
/// When the D1-react engine-alive gate lands this test goes red. Then delete it and enable the (ab) leader arm.
class LeaderChangeEngineStopTripwireTest {
    private static final NodeId SELF = new NodeId("node-1");
    private static final NodeId PEER = new NodeId("node-2");
    private static final NodeId THIRD = new NodeId("node-3");
    private static final TimeSpan LONG = TimeSpan.timeSpan(60).seconds();
    private static final String ENABLE = "D1-react engine-alive gate landed: delete LeaderChangeEngineStopTripwireTest "
                                         + "and enable the exit (ab) leader re-activation arm";

    @Test
    void tripwire_leaderCommitAfterEngineStop_routesLeaderChangeInline() {
        var recorded = new CopyOnWriteArrayList<LeaderChange>();
        var router = MessageRouter.mutable();
        router.addRoute(LeaderChange.class, recorded::add);
        var harness = harness(router);
        var ctx = ((LeaderElectionState) harness.state()).ctx();
        var manager = new FsmBackedLeaderManager(ctx.fsm(), ctx, false);

        harness.dispatch(new ClusterFsmEvent.QuorumEstablished());
        recorded.clear();
        manager.onLeaderCommitted(SELF, 5);

        assertThat(recorded).as(ENABLE)
                            .containsExactly(new LeaderChange(Option.some(SELF), true));
        manager.stop();
    }

    private static FsmTestHarness<LeaderElectionState, ClusterFsmEvent> harness(MessageRouter router) {
        return FsmTestHarness.<LeaderElectionState, ClusterFsmEvent>harness("leader-change-engine-stop-tripwire",
                                                                             fsm -> context(fsm, router).dormant());
    }

    private static LeaderElectionContext context(org.pragmatica.statemachine.Fsm<LeaderElectionState, ClusterFsmEvent> fsm,
                                                 MessageRouter router) {
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
