/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */

package org.pragmatica.consensus.leader.fsm;

import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmObserver;

/// Factory for the leader-election FSM. Builds the shared [`LeaderElectionContext`], constructs
/// the per-FSM state instances (Dormant, QuorumWaiting, Electing, ReElecting, QuorumLost,
/// Stopped), wires the Fsm, and binds the Fsm reference into the state holder so state code can
/// self-dispatch (for buffered ConsensusReady replay, timer ticks, and proposal callbacks).
///
/// No executor, no queue, no dispatcher thread — `Fsm.dispatch` runs on the caller thread.
public final class LeaderElectionFsm {
    private LeaderElectionFsm() {}

    public static Fsm<LeaderElectionState, ClusterFsmEvent> leaderElectionFsm(LeaderElectionContext context) {
        return leaderElectionFsm(context, FsmObserver.noop());
    }

    public static Fsm<LeaderElectionState, ClusterFsmEvent> leaderElectionFsm(
            LeaderElectionContext context,
            FsmObserver<LeaderElectionState, ClusterFsmEvent> observer) {
        context.initStates();
        var fsmName = "leader-election-" + context.self().id();
        var fsm = Fsm.fsm(fsmName, context.dormant(), observer);
        context.bindFsm(fsm);
        return fsm;
    }
}
