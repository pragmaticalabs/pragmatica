/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */

package org.pragmatica.consensus.leader;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.fsm.ClusterFsmRouter;
import org.pragmatica.consensus.leader.fsm.LeaderElectionContext;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents;
import org.pragmatica.consensus.leader.fsm.LeaderElectionFsm;
import org.pragmatica.consensus.leader.fsm.LeaderElectionState;
import org.pragmatica.consensus.topology.QuorumStateNotification;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeAdded;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeDown;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeRemoved;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.Fsm;

import java.util.List;

/// Leader manager responsible for choosing the cluster leader. Backed by the
/// [`LeaderElectionFsm`](fsm/LeaderElectionFsm.java) — a GoF-style state machine with CAS-guarded
/// transitions, caller-thread dispatch (no executor), and explicit per-state event handlers.
///
/// Modes:
/// 1. **Local election** (backward compatible): no `LeaderProposalHandler`; leader is computed
///    locally as the first node in sorted topology and fed back as a synthesized
///    `LeaderCommitted`.
/// 2. **Consensus-based election**: proposals go through consensus; the committed leader flows
///    back via [`onLeaderCommitted`](#onLeaderCommitted(NodeId)).
public interface LeaderManager {
    Option<NodeId> leader();

    boolean isLeader();

    /// Called when leader election is committed through consensus.
    void onLeaderCommitted(NodeId leader);

    /// Signal that consensus has completed sync and the node is ready to elect.
    void triggerElection();

    /// Stop the manager — further events are ignored.
    void stop();

    @MessageReceiver
    void nodeAdded(NodeAdded nodeAdded);

    @MessageReceiver
    void nodeRemoved(NodeRemoved nodeRemoved);

    @MessageReceiver
    void nodeDown(NodeDown nodeDown);

    @MessageReceiver
    void watchQuorumState(QuorumStateNotification quorumState);

    /// Handler for submitting leader proposals through consensus.
    @FunctionalInterface
    interface LeaderProposalHandler {
        Promise<Unit> propose(NodeId candidate, long viewSequence);
    }

    // --- Factories ---

    static LeaderManager leaderManager(NodeId self, MessageRouter router) {
        return build(self, router, Option.none(), List.of(),
                     LeaderElectionContext.DEFAULT_PROPOSAL_RETRY_DELAY,
                     LeaderElectionContext.DEFAULT_BASE_ELECTION_DELAY,
                     LeaderElectionContext.DEFAULT_PER_RANK_DELAY);
    }

    static LeaderManager leaderManager(NodeId self, MessageRouter router, LeaderProposalHandler proposalHandler) {
        return build(self, router, Option.some(proposalHandler), List.of(),
                     LeaderElectionContext.DEFAULT_PROPOSAL_RETRY_DELAY,
                     LeaderElectionContext.DEFAULT_BASE_ELECTION_DELAY,
                     LeaderElectionContext.DEFAULT_PER_RANK_DELAY);
    }

    static LeaderManager leaderManager(NodeId self,
                                       MessageRouter router,
                                       LeaderProposalHandler proposalHandler,
                                       List<NodeId> expectedCluster) {
        return build(self, router, Option.some(proposalHandler), expectedCluster,
                     LeaderElectionContext.DEFAULT_PROPOSAL_RETRY_DELAY,
                     LeaderElectionContext.DEFAULT_BASE_ELECTION_DELAY,
                     LeaderElectionContext.DEFAULT_PER_RANK_DELAY);
    }

    static LeaderManager leaderManager(NodeId self,
                                       MessageRouter router,
                                       LeaderProposalHandler proposalHandler,
                                       List<NodeId> expectedCluster,
                                       TimeSpan proposalRetryDelay,
                                       TimeSpan baseElectionDelay,
                                       TimeSpan perRankDelay) {
        return build(self, router, Option.some(proposalHandler), expectedCluster,
                     proposalRetryDelay, baseElectionDelay, perRankDelay);
    }

    private static LeaderManager build(NodeId self,
                                       MessageRouter router,
                                       Option<LeaderProposalHandler> proposalHandler,
                                       List<NodeId> expectedCluster,
                                       TimeSpan proposalRetryDelay,
                                       TimeSpan baseElectionDelay,
                                       TimeSpan perRankDelay) {
        var built = LeaderElectionFsm.leaderElectionFsm(self, proposalHandler, expectedCluster,
                                                        router, proposalRetryDelay,
                                                        baseElectionDelay, perRankDelay);
        return new FsmBackedLeaderManager(built.fsm(), built.context(), proposalHandler.isEmpty());
    }

    /// FSM-backed implementation. `localMode=true` means no consensus; we synthesize
    /// `LeaderCommitted` events locally to drive the FSM.
    record FsmBackedLeaderManager(Fsm<LeaderElectionState, ClusterFsmEvent> fsm,
                                  LeaderElectionContext context,
                                  boolean localMode) implements LeaderManager {
        @Override
        public Option<NodeId> leader() {
            return context.currentLeader();
        }

        @Override
        public boolean isLeader() {
            return context.isLeader();
        }

        @Override
        public void onLeaderCommitted(NodeId leader) {
            fsm.dispatch(new LeaderElectionEvents.LeaderCommitted(leader));
        }

        @Override
        public void triggerElection() {
            fsm.dispatch(new LeaderElectionEvents.ConsensusReady());
        }

        @Override
        public void stop() {
            fsm.dispatch(new ClusterFsmEvent.Shutdown());
        }

        @Override
        public void nodeAdded(NodeAdded nodeAdded) {
            fsm.dispatch(new ClusterFsmEvent.NodeAdded(nodeAdded.nodeId(), nodeAdded.topology()));
            if (localMode) {
                electLocallyIfPossible();
            }
        }

        @Override
        public void nodeRemoved(NodeRemoved nodeRemoved) {
            // Local mode needs the new leader to appear BEFORE the NodeGone event so the FSM
            // stays in Led (different leader) rather than going Led → ReElecting → Led, which
            // would emit an intermediate "no leader" notification unexpected for legacy consumers.
            if (localMode) {
                dispatchLocalModeAdoption(nodeRemoved.topology());
            }
            fsm.dispatch(new ClusterFsmEvent.NodeGone(nodeRemoved.nodeId(), nodeRemoved.topology()));
        }

        @Override
        public void nodeDown(NodeDown nodeDown) {
            // Always dispatch; states decide. Empty topology → QuorumDisappeared; non-empty →
            // (optional) local-mode adoption + NodeGone. No external FSM-state reads here.
            if (nodeDown.topology().isEmpty()) {
                fsm.dispatch(new ClusterFsmEvent.QuorumDisappeared());
                return;
            }
            if (localMode) {
                dispatchLocalModeAdoption(nodeDown.topology());
            }
            fsm.dispatch(new ClusterFsmEvent.NodeGone(nodeDown.nodeId(), nodeDown.topology()));
        }

        private void dispatchLocalModeAdoption(List<NodeId> topology) {
            var sorted = topology.stream().sorted().toList();
            if (sorted.isEmpty()) {
                return;
            }
            fsm.dispatch(new LeaderElectionEvents.LeaderCommitted(sorted.getFirst()));
        }

        @Override
        public void watchQuorumState(QuorumStateNotification quorumState) {
            if (!quorumState.advanceSequence(context.quorumSequence())) {
                return;
            }
            switch (quorumState.state()) {
                case ESTABLISHED -> onQuorumEstablished();
                case DISAPPEARED -> fsm.dispatch(new ClusterFsmEvent.QuorumDisappeared());
            }
        }

        private void onQuorumEstablished() {
            fsm.dispatch(new ClusterFsmEvent.QuorumEstablished());
            if (localMode) {
                fsm.dispatch(new LeaderElectionEvents.ConsensusReady());
                electLocallyIfPossible();
            }
        }

        private void electLocallyIfPossible() {
            // No external FSM-state read here — per state's handler, a LeaderCommitted that does
            // not match the current topology / active state is ignored or logged. We still need
            // to read topology to *construct* the LeaderCommitted event (it requires a concrete
            // NodeId), so an empty topology short-circuits before event construction — this is
            // a data-validity check, not a TOCTOU guard on FSM state.
            var topology = context.currentTopology();
            if (topology.isEmpty()) {
                return;
            }
            fsm.dispatch(new LeaderElectionEvents.LeaderCommitted(topology.getFirst()));
        }
    }
}
