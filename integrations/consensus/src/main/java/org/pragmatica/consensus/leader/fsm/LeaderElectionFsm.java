/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */

package org.pragmatica.consensus.leader.fsm;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.leader.LeaderManager.LeaderProposalHandler;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmObserver;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/// Factory for the leader-election FSM. Builds the Fsm with a constructor-driven initial-state
/// factory: the factory receives the partially-constructed Fsm, creates the shared
/// [`LeaderElectionContext`] (which in turn builds every per-FSM state singleton), captures the
/// context in an `AtomicReference` holder for the caller, and returns the `Dormant` initial state.
///
/// No executor, no queue, no dispatcher thread — `Fsm.dispatch` runs on the caller thread.
public final class LeaderElectionFsm {
    /// Pair of the built Fsm and its context. Returned together so the factory caller (the
    /// `LeaderManager` builder) can wire both into the final `LeaderManager` record.
    public record FsmWithContext(Fsm<LeaderElectionState, ClusterFsmEvent> fsm,
                                 LeaderElectionContext context) {}

    private LeaderElectionFsm() {}

    public static FsmWithContext leaderElectionFsm(NodeId self,
                                                   Option<LeaderProposalHandler> proposalHandler,
                                                   List<NodeId> expectedCluster,
                                                   MessageRouter router,
                                                   TimeSpan proposalRetryDelay,
                                                   TimeSpan baseElectionDelay,
                                                   TimeSpan perRankDelay) {
        return leaderElectionFsm(self, proposalHandler, expectedCluster, router,
                                 proposalRetryDelay, baseElectionDelay, perRankDelay,
                                 FsmObserver.noop());
    }

    public static FsmWithContext leaderElectionFsm(NodeId self,
                                                   Option<LeaderProposalHandler> proposalHandler,
                                                   List<NodeId> expectedCluster,
                                                   MessageRouter router,
                                                   TimeSpan proposalRetryDelay,
                                                   TimeSpan baseElectionDelay,
                                                   TimeSpan perRankDelay,
                                                   FsmObserver<LeaderElectionState, ClusterFsmEvent> observer) {
        var ctxHolder = new AtomicReference<LeaderElectionContext>();
        var timeout = LeaderElectionContext.proposalTimeoutFor(proposalRetryDelay);
        Function<Fsm<LeaderElectionState, ClusterFsmEvent>, LeaderElectionState> initialStateFactory =
            f -> buildContextAndInitialState(ctxHolder, f, self, proposalHandler, expectedCluster,
                                             router, proposalRetryDelay, baseElectionDelay,
                                             perRankDelay, timeout);
        var fsm = Fsm.fsm("leader-election", self.id(), initialStateFactory, observer);
        return new FsmWithContext(fsm, ctxHolder.get());
    }

    private static LeaderElectionState buildContextAndInitialState(AtomicReference<LeaderElectionContext> ctxHolder,
                                                                   Fsm<LeaderElectionState, ClusterFsmEvent> fsm,
                                                                   NodeId self,
                                                                   Option<LeaderProposalHandler> proposalHandler,
                                                                   List<NodeId> expectedCluster,
                                                                   MessageRouter router,
                                                                   TimeSpan proposalRetryDelay,
                                                                   TimeSpan baseElectionDelay,
                                                                   TimeSpan perRankDelay,
                                                                   TimeSpan proposalTimeout) {
        var ctx = new LeaderElectionContext(fsm, self, proposalHandler, expectedCluster, router,
                                            proposalRetryDelay, baseElectionDelay, perRankDelay,
                                            proposalTimeout,
                                            LeaderElectionContext.DEFAULT_STUCK_ELECTION_THRESHOLD);
        ctxHolder.set(ctx);
        return ctx.dormant();
    }
}
