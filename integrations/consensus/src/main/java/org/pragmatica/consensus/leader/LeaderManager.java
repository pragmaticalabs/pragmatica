/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package org.pragmatica.consensus.leader;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.fsm.LeaderElectionContext;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvent;
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

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Leader manager responsible for choosing the cluster leader. Backed by an explicit state
/// machine ([`LeaderElectionFsm`](fsm/LeaderElectionFsm.java)) — every legal state, transition,
/// and guard is enumerated there. The public interface exposed here is unchanged from the
/// previous implementation and is wired into the message router the same way.
///
/// Modes of operation remain:
/// 1. **Local election** (backward compatible): no `LeaderProposalHandler`, leader computed
///    locally as the first node in sorted topology.
/// 2. **Consensus-based election**: proposals go through consensus; the committed leader flows
///    back in via [`onLeaderCommitted`](#onLeaderCommitted(NodeId)).
public interface LeaderManager {
    Logger log = LoggerFactory.getLogger(LeaderManager.class);

    TimeSpan DEFAULT_PROPOSAL_RETRY_DELAY = timeSpan(500).millis();
    TimeSpan DEFAULT_BASE_ELECTION_DELAY = timeSpan(2).seconds();
    TimeSpan DEFAULT_PER_RANK_DELAY = timeSpan(1).seconds();
    /// Minimum proposal timeout (5s). Actual timeout is `max(3*retryDelay, 5s)`.
    TimeSpan DEFAULT_MIN_PROPOSAL_TIMEOUT = timeSpan(5).seconds();
    /// Failed proposal attempts before the FSM relaxes its candidate pool to raw topology.
    int DEFAULT_STUCK_ELECTION_THRESHOLD = 10;

    Option<NodeId> leader();

    boolean isLeader();

    /// Called when leader election is committed through consensus. Validates against topology
    /// and updates state only if the leader is known to the local topology; otherwise logs a
    /// warning and drops the update.
    void onLeaderCommitted(NodeId leader);

    /// Signal that consensus has completed sync and the node is ready to elect. Triggers the
    /// staggered initial election or an immediate re-election depending on prior history.
    void triggerElection();

    /// Stop the manager. Flushes pending events and shuts the dispatcher. After stop(), the
    /// manager is inert — incoming events are silently dropped.
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
                     DEFAULT_PROPOSAL_RETRY_DELAY, DEFAULT_BASE_ELECTION_DELAY, DEFAULT_PER_RANK_DELAY);
    }

    static LeaderManager leaderManager(NodeId self, MessageRouter router, LeaderProposalHandler proposalHandler) {
        return build(self, router, Option.some(proposalHandler), List.of(),
                     DEFAULT_PROPOSAL_RETRY_DELAY, DEFAULT_BASE_ELECTION_DELAY, DEFAULT_PER_RANK_DELAY);
    }

    static LeaderManager leaderManager(NodeId self,
                                       MessageRouter router,
                                       LeaderProposalHandler proposalHandler,
                                       List<NodeId> expectedCluster) {
        return build(self, router, Option.some(proposalHandler), expectedCluster,
                     DEFAULT_PROPOSAL_RETRY_DELAY, DEFAULT_BASE_ELECTION_DELAY, DEFAULT_PER_RANK_DELAY);
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
        var proposalTimeout = pickProposalTimeout(proposalRetryDelay);
        var ctx = new LeaderElectionContext(self,
                                            proposalHandler,
                                            expectedCluster,
                                            router,
                                            proposalRetryDelay,
                                            baseElectionDelay,
                                            perRankDelay,
                                            proposalTimeout,
                                            DEFAULT_STUCK_ELECTION_THRESHOLD);
        var fsm = LeaderElectionFsm.leaderElectionFsm(ctx);
        return new fsmBackedLeaderManager(fsm, ctx, proposalHandler.isEmpty(), new AtomicLong(0));
    }

    private static TimeSpan pickProposalTimeout(TimeSpan retryDelay) {
        var tripleRetry = retryDelay.millis() * 3L;
        var floor = DEFAULT_MIN_PROPOSAL_TIMEOUT.millis();
        return timeSpan(Math.max(tripleRetry, floor)).millis();
    }

    /// FSM-backed implementation. `localMode` means no proposal handler — we compute the leader
    /// locally as the first node in sorted topology and synthesize a LeaderCommitted event.
    record fsmBackedLeaderManager(LeaderElectionFsm fsm,
                                  LeaderElectionContext context,
                                  boolean localMode,
                                  AtomicLong quorumSequence) implements LeaderManager {
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
            fsm.dispatch(new LeaderElectionEvent.LeaderCommitted(leader));
        }

        @Override
        public void triggerElection() {
            fsm.dispatch(new LeaderElectionEvent.ConsensusReady());
        }

        @Override
        public void stop() {
            fsm.stop();
        }

        @Override
        public void nodeAdded(NodeAdded nodeAdded) {
            fsm.dispatch(new LeaderElectionEvent.NodeAdded(nodeAdded.nodeId(), nodeAdded.topology()));
            if (localMode) {
                electLocallyIfPossible();
            }
        }

        @Override
        public void nodeRemoved(NodeRemoved nodeRemoved) {
            // Local mode needs the new leader to appear BEFORE the NodeGone event so the FSM
            // stays in LED (swap) rather than transitioning LED → RE_ELECTING → LED. That path
            // would emit an intermediate "no leader" notification, which legacy local-mode
            // consumers do not expect.
            if (localMode) {
                dispatchLocalModeAdoption(nodeRemoved.topology());
            }
            fsm.dispatch(new LeaderElectionEvent.NodeGone(nodeRemoved.nodeId(), nodeRemoved.topology()));
        }

        @Override
        public void nodeDown(NodeDown nodeDown) {
            // NodeDown carries empty topology by contract — map to QuorumDisappeared semantics
            // so the FSM drops to QUORUM_LOST / STOPPED appropriately.
            if (nodeDown.topology().isEmpty()) {
                fsm.dispatch(new LeaderElectionEvent.QuorumDisappeared());
                return;
            }
            if (localMode) {
                dispatchLocalModeAdoption(nodeDown.topology());
            }
            fsm.dispatch(new LeaderElectionEvent.NodeGone(nodeDown.nodeId(), nodeDown.topology()));
        }

        private void dispatchLocalModeAdoption(List<NodeId> topology) {
            var sorted = topology.stream().sorted().toList();
            if (sorted.isEmpty()) {
                return;
            }
            fsm.dispatch(new LeaderElectionEvent.LeaderCommitted(sorted.getFirst()));
        }

        @Override
        public void watchQuorumState(QuorumStateNotification quorumState) {
            if (!quorumState.advanceSequence(quorumSequence)) {
                log.debug("Ignoring stale QuorumStateNotification: {}", quorumState);
                return;
            }
            switch (quorumState.state()) {
                case ESTABLISHED -> {
                    fsm.dispatch(new LeaderElectionEvent.QuorumEstablished());
                    if (localMode) {
                        // Local mode does not wait for an external triggerElection — move directly
                        // into the election state and synthesize a LeaderCommitted based on topology.
                        fsm.dispatch(new LeaderElectionEvent.ConsensusReady());
                        electLocallyIfPossible();
                    }
                }
                case DISAPPEARED -> fsm.dispatch(new LeaderElectionEvent.QuorumDisappeared());
            }
        }

        /// Local-mode election: pick the first node in sorted topology and feed it back as a
        /// LeaderCommitted so the FSM transitions to LED.
        private void electLocallyIfPossible() {
            var state = fsm.currentState();
            if (state == LeaderElectionState.STOPPED || state == LeaderElectionState.DORMANT
                    || state == LeaderElectionState.QUORUM_LOST) {
                return;
            }
            var topology = context.currentTopology();
            if (topology.isEmpty()) {
                return;
            }
            fsm.dispatch(new LeaderElectionEvent.LeaderCommitted(topology.getFirst()));
        }
    }
}
