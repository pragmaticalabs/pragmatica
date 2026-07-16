/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.consensus.leader.fsm;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.leader.LeaderManager.LeaderProposalHandler;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmObserver;


/// Factory for the leader-election FSM. Builds the Fsm with a constructor-driven initial-state
/// factory: the factory receives the partially-constructed Fsm, creates the shared
/// [`LeaderElectionContext`] (which in turn builds every per-FSM state singleton), captures the
/// context in an `AtomicReference` holder for the caller, and returns the `Dormant` initial state.
///
/// No executor, no queue, no dispatcher thread — `Fsm.dispatch` runs on the caller thread.
public final class LeaderElectionFsm {
    /// Pair of the built Fsm and its context. Returned together so the factory caller (the
    /// `LeaderManager` builder) can wire both into the final `LeaderManager` record.
    public record FsmWithContext(Fsm<LeaderElectionState, ClusterFsmEvent> fsm, LeaderElectionContext context) {}

    private LeaderElectionFsm() {}

    public static FsmWithContext leaderElectionFsm(NodeId self,
                                                   Option<LeaderProposalHandler> proposalHandler,
                                                   List<NodeId> expectedCluster,
                                                   MessageRouter router,
                                                   TimeSpan proposalRetryDelay,
                                                   TimeSpan baseElectionDelay,
                                                   TimeSpan perRankDelay) {
        return leaderElectionFsm(self,
                                 proposalHandler,
                                 expectedCluster,
                                 router,
                                 proposalRetryDelay,
                                 baseElectionDelay,
                                 perRankDelay,
                                 FsmObserver.noop(),
                                 LeaderElectionContext.DEFAULT_RABIA_TERM_SUPPLIER,
                                 LeaderElectionContext.DEFAULT_CONSENSUS_READY_SUPPLIER);
    }

    public static FsmWithContext leaderElectionFsm(NodeId self,
                                                   Option<LeaderProposalHandler> proposalHandler,
                                                   List<NodeId> expectedCluster,
                                                   MessageRouter router,
                                                   TimeSpan proposalRetryDelay,
                                                   TimeSpan baseElectionDelay,
                                                   TimeSpan perRankDelay,
                                                   FsmObserver<LeaderElectionState, ClusterFsmEvent> observer) {
        return leaderElectionFsm(self,
                                 proposalHandler,
                                 expectedCluster,
                                 router,
                                 proposalRetryDelay,
                                 baseElectionDelay,
                                 perRankDelay,
                                 observer,
                                 LeaderElectionContext.DEFAULT_RABIA_TERM_SUPPLIER,
                                 LeaderElectionContext.DEFAULT_CONSENSUS_READY_SUPPLIER);
    }

    public static FsmWithContext leaderElectionFsm(NodeId self,
                                                   Option<LeaderProposalHandler> proposalHandler,
                                                   List<NodeId> expectedCluster,
                                                   MessageRouter router,
                                                   TimeSpan proposalRetryDelay,
                                                   TimeSpan baseElectionDelay,
                                                   TimeSpan perRankDelay,
                                                   FsmObserver<LeaderElectionState, ClusterFsmEvent> observer,
                                                   Supplier<Long> rabiaTermSupplier) {
        return leaderElectionFsm(self,
                                 proposalHandler,
                                 expectedCluster,
                                 router,
                                 proposalRetryDelay,
                                 baseElectionDelay,
                                 perRankDelay,
                                 observer,
                                 rabiaTermSupplier,
                                 LeaderElectionContext.DEFAULT_CONSENSUS_READY_SUPPLIER);
    }

    /// Factory accepting rabia-term + consensus-readiness suppliers; defaults the KV-leader
    /// supplier to `Option.none()`. Existing callers (and tests) that don't need the pull-from-KV
    /// path stay on this overload.
    public static FsmWithContext leaderElectionFsm(NodeId self,
                                                   Option<LeaderProposalHandler> proposalHandler,
                                                   List<NodeId> expectedCluster,
                                                   MessageRouter router,
                                                   TimeSpan proposalRetryDelay,
                                                   TimeSpan baseElectionDelay,
                                                   TimeSpan perRankDelay,
                                                   FsmObserver<LeaderElectionState, ClusterFsmEvent> observer,
                                                   Supplier<Long> rabiaTermSupplier,
                                                   Supplier<Boolean> consensusReadySupplier) {
        return leaderElectionFsm(self,
                                 proposalHandler,
                                 expectedCluster,
                                 router,
                                 proposalRetryDelay,
                                 baseElectionDelay,
                                 perRankDelay,
                                 observer,
                                 rabiaTermSupplier,
                                 consensusReadySupplier,
                                 LeaderElectionContext.DEFAULT_CURRENT_LEADER_FROM_KV_SUPPLIER);
    }

    /// Full-arity factory accepting all injectable suppliers including the cluster KV-Store
    /// leader accessor. Production wiring (Aether's `RabiaNode`) plumbs:
    ///   - `RabiaEngine::isActive` as `consensusReadySupplier` (push-side: ready-state poll)
    ///   - `() -> kvStore.get(LeaderKey).map(LeaderValue::leader)` as `currentLeaderFromKvSupplier`
    ///     (pull-side: cluster's source-of-truth leader read)
    /// Together they make the FSM pull-and-push on every `Electing`/`ReElecting` entry + tick:
    /// the FSM never gets stuck waiting for a notification that already fired (or never will).
    public static FsmWithContext leaderElectionFsm(NodeId self,
                                                   Option<LeaderProposalHandler> proposalHandler,
                                                   List<NodeId> expectedCluster,
                                                   MessageRouter router,
                                                   TimeSpan proposalRetryDelay,
                                                   TimeSpan baseElectionDelay,
                                                   TimeSpan perRankDelay,
                                                   FsmObserver<LeaderElectionState, ClusterFsmEvent> observer,
                                                   Supplier<Long> rabiaTermSupplier,
                                                   Supplier<Boolean> consensusReadySupplier,
                                                   Supplier<Option<NodeId>> currentLeaderFromKvSupplier) {
        return leaderElectionFsm(self,
                                 proposalHandler,
                                 expectedCluster,
                                 router,
                                 proposalRetryDelay,
                                 baseElectionDelay,
                                 perRankDelay,
                                 observer,
                                 rabiaTermSupplier,
                                 consensusReadySupplier,
                                 currentLeaderFromKvSupplier,
                                 LeaderElectionContext.DEFAULT_LEADER_PING_FRESH_FN);
    }

    /// Full-arity factory adding the leader-acting lease predicate consulted by
    /// [`LeaderElectionState.Led`]. Production wiring backs it with the follower's observed
    /// leader-ping freshness (`ClusterSyncCollector::hasAuthoritativeReadiness`); callers that
    /// don't wire a freshness signal stay on the prior overload (lease-neutral default).
    public static FsmWithContext leaderElectionFsm(NodeId self,
                                                   Option<LeaderProposalHandler> proposalHandler,
                                                   List<NodeId> expectedCluster,
                                                   MessageRouter router,
                                                   TimeSpan proposalRetryDelay,
                                                   TimeSpan baseElectionDelay,
                                                   TimeSpan perRankDelay,
                                                   FsmObserver<LeaderElectionState, ClusterFsmEvent> observer,
                                                   Supplier<Long> rabiaTermSupplier,
                                                   Supplier<Boolean> consensusReadySupplier,
                                                   Supplier<Option<NodeId>> currentLeaderFromKvSupplier,
                                                   Predicate<NodeId> leaderPingFreshFn) {
        var ctxHolder = new AtomicReference<LeaderElectionContext>();
        var timeout = LeaderElectionContext.proposalTimeoutFor(proposalRetryDelay);
        Function<Fsm<LeaderElectionState, ClusterFsmEvent>, LeaderElectionState> initialStateFactory = f -> buildContextAndInitialState(ctxHolder,
                                                                                                                                        f,
                                                                                                                                        self,
                                                                                                                                        proposalHandler,
                                                                                                                                        expectedCluster,
                                                                                                                                        router,
                                                                                                                                        proposalRetryDelay,
                                                                                                                                        baseElectionDelay,
                                                                                                                                        perRankDelay,
                                                                                                                                        timeout,
                                                                                                                                        rabiaTermSupplier,
                                                                                                                                        consensusReadySupplier,
                                                                                                                                        currentLeaderFromKvSupplier,
                                                                                                                                        leaderPingFreshFn);
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
                                                                   TimeSpan proposalTimeout,
                                                                   Supplier<Long> rabiaTermSupplier,
                                                                   Supplier<Boolean> consensusReadySupplier,
                                                                   Supplier<Option<NodeId>> currentLeaderFromKvSupplier,
                                                                   Predicate<NodeId> leaderPingFreshFn) {
        var ctx = new LeaderElectionContext(fsm,
                                            self,
                                            proposalHandler,
                                            expectedCluster,
                                            router,
                                            proposalRetryDelay,
                                            baseElectionDelay,
                                            perRankDelay,
                                            proposalTimeout,
                                            LeaderElectionContext.DEFAULT_STUCK_ELECTION_THRESHOLD,
                                            LeaderElectionContext.DEFAULT_JITTER_SOURCE,
                                            rabiaTermSupplier,
                                            consensusReadySupplier,
                                            currentLeaderFromKvSupplier,
                                            LeaderElectionContext.DEFAULT_KV_SYNC_GRACE_DELAY,
                                            LeaderElectionContext.DEFAULT_PEER_OBSERVATION_INTERVAL,
                                            LeaderElectionContext.DEFAULT_LEADER_LEASE_CHECK_INTERVAL,
                                            leaderPingFreshFn);

        ctxHolder.set(ctx);

        return ctx.dormant();
    }
}
