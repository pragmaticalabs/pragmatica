/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.consensus.leader.fsm;

import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.statemachine.FsmTestHarness;

/// Test entry to the leader FSM through the node's serial executor (#3605 round 4). Leader code dispatches only on the
/// serial drainer ([LeaderElectionContext#dispatchConfined] refuses any other caller), so a test that drives the FSM
/// from its own thread enters the way production does: [LeaderElectionContext#submit], which runs the event on the
/// drainer, inline when the drainer is idle. `harness.dispatch(...)` from a test thread is off the drainer: a handler
/// that reaches `dispatchSelf` would be refused.
final class OnDrainer {
    private OnDrainer() {}

    static void dispatch(FsmTestHarness<LeaderElectionState, ClusterFsmEvent> harness, ClusterFsmEvent event) {
        harness.state()
               .ctx()
               .submit(event);
    }
}
