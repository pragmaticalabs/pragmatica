/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.consensus.leader;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.fsm.ClusterFsmRouter;
import org.pragmatica.consensus.leader.fsm.LeaderElectionContext;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents;
import org.pragmatica.consensus.leader.fsm.LeaderElectionFsm;
import org.pragmatica.consensus.leader.fsm.LeaderElectionState;
import org.pragmatica.consensus.net.NetworkMessage.LeaderPreVoteRequest;
import org.pragmatica.consensus.net.NetworkMessage.LeaderPreVoteResponse;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.TransportObservation.PeerDisconnected;
import org.pragmatica.consensus.topology.TransportObservation.PeerJoined;
import org.pragmatica.consensus.topology.TransportObservation.PeerObservedFaulty;
import org.pragmatica.consensus.topology.TransportObservation.PeerReconnected;
import org.pragmatica.consensus.topology.TransportObservation.SelfShutdown;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmObserver;
import org.pragmatica.utility.react.ReactExecutor;


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

    default org.pragmatica.lang.Unit installVoterConfiguration(org.pragmatica.consensus.rabia.VoterConfiguration configuration) {
        return org.pragmatica.lang.Unit.unit();
    }

    /// D1-react (KVStore dispatcher design §3.5.1 row 107): the executor the `LeaderChange` route runs on. A node installs
    /// its gated executor once, at assembly; until then (and in a host that installs none) the route runs inline, as
    /// before D1-react. Default no-op for implementations without an election FSM.
    default org.pragmatica.lang.Unit installReactExecutor(ReactExecutor executor) {
        return org.pragmatica.lang.Unit.unit();
    }

    /// KVStore dispatcher design §3.5 R3 (v-2653 F3): the node store's replay bit (`KVStore::isReplaying`), read on the
    /// thread that raises a `LeaderChange` and carried in it as `replayed`, because the route runs on the react executor,
    /// where the store's bit always reads false. Until installed, no change is marked replayed. Default no-op for
    /// implementations without an election FSM.
    default org.pragmatica.lang.Unit installReplaySource(BooleanSupplier replaying) {
        return org.pragmatica.lang.Unit.unit();
    }

    /// The installed replay source read on the calling thread (a wiring pin seam: true only on a thread that delivers a
    /// replay burst of the node store). Default false for implementations without an election FSM.
    default boolean replaying() {
        return false;
    }

    /// Leader discovery eligibility for a passive client; caller must not treat this as voting authority.
    default org.pragmatica.lang.Unit installPassiveCoreDirectory(List<NodeId> members) {
        return org.pragmatica.lang.Unit.unit();
    }

    /// Wires the signal that this node's KV state still trails the committed frontier (#1803), so a joiner
    /// waits for a committed leader to arrive through KV sync instead of electing on a wall-clock grace.
    /// Default no-op for implementations without an election gate.
    default Unit installKvSyncProgress(Supplier<Boolean> pending) {
        return Unit.unit();
    }

    /// Turns the leader pre-vote on (#1748): a follower that loses its leader asks the electorate before it
    /// elects, so one follower's broken link cannot depose a leader the rest still reaches. Wired by the
    /// consensus node only — it needs a network to ask over. Default no-op for implementations without one.
    default Unit enableLeaderPreVote() {
        return Unit.unit();
    }

    /// A peer asks whether this node still sees `leader` healthy (#1748). Default no-op.
    @Contract
    @MessageReceiver
    default void leaderPreVoteRequest(LeaderPreVoteRequest request) {}

    /// A peer's answer to this node's pre-vote question (#1748). Default no-op.
    @Contract
    @MessageReceiver
    default void leaderPreVoteResponse(LeaderPreVoteResponse response) {}

    boolean isLeader();
    /// Returns the current leader's epoch (the cluster-side rabia term) iff this node is the
    /// elected leader; otherwise [`Option#none`]. Source: the [`Supplier<Long>`] injected at
    /// construction (`leaderManager(...)` overloads). Consumers (e.g. Aether's generation and
    /// ownership epoch suppliers) wrap this raw term into their domain epoch type.
    ///
    /// SSOT note: leader-tenure identity (NodeId) and the rabia term advance together — the term
    /// increments on each leader transition (view change), so a live read here is semantically
    /// equivalent to a snapshot taken at promotion for the active-leader case.
    Option<Long> currentLeaderEpoch();

    /// Called when leader election is committed through consensus.
    @Contract
    void onLeaderCommitted(NodeId leader);

    /// Variant carrying the committed [`viewSequence`] (H4 fence, cluster-topology-overhaul
    /// §Wave 8.2). Seeds the local election baseline (so this node's NEXT proposal is fenced
    /// strictly above the committed sequence) before dispatching the commit. Default delegates
    /// to the sequence-less variant for implementations (test stubs, legacy local mode) that do
    /// not track the baseline.
    @Contract
    default void onLeaderCommitted(NodeId leader, long viewSequence) {
        onLeaderCommitted(leader);
    }

    /// Signal that consensus has completed sync and the node is ready to elect.
    @Contract
    void triggerElection();

    /// Stop the manager — further events are ignored.
    @Contract
    void stop();

    @Contract
    @MessageReceiver
    void peerJoined(PeerJoined peerJoined);

    @Contract
    @MessageReceiver
    void peerDisconnected(PeerDisconnected peerDisconnected);

    @Contract
    @MessageReceiver
    void peerObservedFaulty(PeerObservedFaulty peerObservedFaulty);

    @Contract
    @MessageReceiver
    void peerReconnected(PeerReconnected peerReconnected);

    @Contract
    @MessageReceiver
    void selfShutdown(SelfShutdown selfShutdown);

    @Contract
    @MessageReceiver
    void watchClusterState(ClusterStateNotification clusterState);

    /// Handler for submitting leader proposals through consensus.
    @FunctionalInterface
    interface LeaderProposalHandler {
        Promise<Unit> propose(NodeId candidate, long viewSequence);
    }

    // --- Factories ---
    static LeaderManager leaderManager(NodeId self, MessageRouter router) {
        return build(self,
                     router,
                     Option.none(),
                     List.of(),
                     LeaderElectionContext.DEFAULT_PROPOSAL_RETRY_DELAY,
                     LeaderElectionContext.DEFAULT_BASE_ELECTION_DELAY,
                     LeaderElectionContext.DEFAULT_PER_RANK_DELAY,
                     LeaderElectionContext.DEFAULT_RABIA_TERM_SUPPLIER,
                     LeaderElectionContext.DEFAULT_CONSENSUS_READY_SUPPLIER,
                     LeaderElectionContext.DEFAULT_CURRENT_LEADER_FROM_KV_SUPPLIER);
    }

    static LeaderManager leaderManager(NodeId self, MessageRouter router, LeaderProposalHandler proposalHandler) {
        return build(self,
                     router,
                     Option.some(proposalHandler),
                     List.of(),
                     LeaderElectionContext.DEFAULT_PROPOSAL_RETRY_DELAY,
                     LeaderElectionContext.DEFAULT_BASE_ELECTION_DELAY,
                     LeaderElectionContext.DEFAULT_PER_RANK_DELAY,
                     LeaderElectionContext.DEFAULT_RABIA_TERM_SUPPLIER,
                     LeaderElectionContext.DEFAULT_CONSENSUS_READY_SUPPLIER,
                     LeaderElectionContext.DEFAULT_CURRENT_LEADER_FROM_KV_SUPPLIER);
    }

    static LeaderManager leaderManager(NodeId self,
                                       MessageRouter router,
                                       LeaderProposalHandler proposalHandler,
                                       List<NodeId> expectedCluster) {
        return build(self,
                     router,
                     Option.some(proposalHandler),
                     expectedCluster,
                     LeaderElectionContext.DEFAULT_PROPOSAL_RETRY_DELAY,
                     LeaderElectionContext.DEFAULT_BASE_ELECTION_DELAY,
                     LeaderElectionContext.DEFAULT_PER_RANK_DELAY,
                     LeaderElectionContext.DEFAULT_RABIA_TERM_SUPPLIER,
                     LeaderElectionContext.DEFAULT_CONSENSUS_READY_SUPPLIER,
                     LeaderElectionContext.DEFAULT_CURRENT_LEADER_FROM_KV_SUPPLIER);
    }

    static LeaderManager leaderManager(NodeId self,
                                       MessageRouter router,
                                       LeaderProposalHandler proposalHandler,
                                       List<NodeId> expectedCluster,
                                       TimeSpan proposalRetryDelay,
                                       TimeSpan baseElectionDelay,
                                       TimeSpan perRankDelay) {
        return build(self,
                     router,
                     Option.some(proposalHandler),
                     expectedCluster,
                     proposalRetryDelay,
                     baseElectionDelay,
                     perRankDelay,
                     LeaderElectionContext.DEFAULT_RABIA_TERM_SUPPLIER,
                     LeaderElectionContext.DEFAULT_CONSENSUS_READY_SUPPLIER,
                     LeaderElectionContext.DEFAULT_CURRENT_LEADER_FROM_KV_SUPPLIER);
    }

    /// Factory accepting the `rabiaTermSupplier` consumed by [`#currentLeaderEpoch`]. The
    /// `consensusReadySupplier` defaults to `() -> false` — callers that want auto-advancement
    /// from `QuorumWaiting` must use the [`#leaderManager(NodeId, MessageRouter,
    /// LeaderProposalHandler, List, Supplier, Supplier)`] overload below.
    static LeaderManager leaderManager(NodeId self,
                                       MessageRouter router,
                                       LeaderProposalHandler proposalHandler,
                                       List<NodeId> expectedCluster,
                                       Supplier<Long> rabiaTermSupplier) {
        return build(self,
                     router,
                     Option.some(proposalHandler),
                     expectedCluster,
                     LeaderElectionContext.DEFAULT_PROPOSAL_RETRY_DELAY,
                     LeaderElectionContext.DEFAULT_BASE_ELECTION_DELAY,
                     LeaderElectionContext.DEFAULT_PER_RANK_DELAY,
                     rabiaTermSupplier,
                     LeaderElectionContext.DEFAULT_CONSENSUS_READY_SUPPLIER,
                     LeaderElectionContext.DEFAULT_CURRENT_LEADER_FROM_KV_SUPPLIER);
    }

    /// Factory accepting rabia-term + consensus-readiness suppliers; defaults the KV-leader
    /// supplier to `Option.none()`. Existing callers (and tests) that don't need the
    /// pull-from-KV path stay on this overload.
    static LeaderManager leaderManager(NodeId self,
                                       MessageRouter router,
                                       LeaderProposalHandler proposalHandler,
                                       List<NodeId> expectedCluster,
                                       Supplier<Long> rabiaTermSupplier,
                                       Supplier<Boolean> consensusReadySupplier) {
        return build(self,
                     router,
                     Option.some(proposalHandler),
                     expectedCluster,
                     LeaderElectionContext.DEFAULT_PROPOSAL_RETRY_DELAY,
                     LeaderElectionContext.DEFAULT_BASE_ELECTION_DELAY,
                     LeaderElectionContext.DEFAULT_PER_RANK_DELAY,
                     rabiaTermSupplier,
                     consensusReadySupplier,
                     LeaderElectionContext.DEFAULT_CURRENT_LEADER_FROM_KV_SUPPLIER);
    }

    /// Full-arity factory accepting all injectable suppliers including the cluster KV-Store
    /// leader accessor. Production wiring (Aether's `RabiaNode`) plumbs:
    ///   - `RabiaEngine::isActive` as `consensusReadySupplier` (push-side: ready-state poll)
    ///   - `() -> kvStore.get(LeaderKey).map(LeaderValue::leader)` as `currentLeaderFromKvSupplier`
    ///     (pull-side: cluster's source-of-truth leader read)
    /// The pull-side closes the gap where a rejoining node misses the
    /// `KVStoreNotification.ValuePut<LeaderKey>` notification (e.g. snapshot-restored state didn't
    /// include the LeaderKey, or the listener wired after the Decision was already applied).
    static LeaderManager leaderManager(NodeId self,
                                       MessageRouter router,
                                       LeaderProposalHandler proposalHandler,
                                       List<NodeId> expectedCluster,
                                       Supplier<Long> rabiaTermSupplier,
                                       Supplier<Boolean> consensusReadySupplier,
                                       Supplier<Option<NodeId>> currentLeaderFromKvSupplier) {
        return build(self,
                     router,
                     Option.some(proposalHandler),
                     expectedCluster,
                     LeaderElectionContext.DEFAULT_PROPOSAL_RETRY_DELAY,
                     LeaderElectionContext.DEFAULT_BASE_ELECTION_DELAY,
                     LeaderElectionContext.DEFAULT_PER_RANK_DELAY,
                     rabiaTermSupplier,
                     consensusReadySupplier,
                     currentLeaderFromKvSupplier);
    }

    /// Full-arity factory adding the leader-acting lease predicate. A node in `Led(X)` (X != self)
    /// re-checks `leaderPingFreshFn.test(X)` once per lease interval; consecutive silent checks
    /// trip a re-election (the recovery edge for a SWIM-alive but non-committing leader). Production
    /// wiring (Aether's `RabiaNode` / `AetherNode`) backs the predicate with the follower's
    /// observed leader-ping freshness (`ClusterSyncCollector::hasAuthoritativeReadiness`). Callers
    /// that don't wire a freshness signal stay on the prior overloads (lease-neutral default).
    static LeaderManager leaderManager(NodeId self,
                                       MessageRouter router,
                                       LeaderProposalHandler proposalHandler,
                                       List<NodeId> expectedCluster,
                                       Supplier<Long> rabiaTermSupplier,
                                       Supplier<Boolean> consensusReadySupplier,
                                       Supplier<Option<NodeId>> currentLeaderFromKvSupplier,
                                       Predicate<NodeId> leaderPingFreshFn) {
        return buildWithLease(self,
                              router,
                              Option.some(proposalHandler),
                              expectedCluster,
                              LeaderElectionContext.DEFAULT_PROPOSAL_RETRY_DELAY,
                              LeaderElectionContext.DEFAULT_BASE_ELECTION_DELAY,
                              LeaderElectionContext.DEFAULT_PER_RANK_DELAY,
                              rabiaTermSupplier,
                              consensusReadySupplier,
                              currentLeaderFromKvSupplier,
                              leaderPingFreshFn);
    }

    private static LeaderManager build(NodeId self,
                                       MessageRouter router,
                                       Option<LeaderProposalHandler> proposalHandler,
                                       List<NodeId> expectedCluster,
                                       TimeSpan proposalRetryDelay,
                                       TimeSpan baseElectionDelay,
                                       TimeSpan perRankDelay,
                                       Supplier<Long> rabiaTermSupplier,
                                       Supplier<Boolean> consensusReadySupplier,
                                       Supplier<Option<NodeId>> currentLeaderFromKvSupplier) {
        return buildWithLease(self,
                              router,
                              proposalHandler,
                              expectedCluster,
                              proposalRetryDelay,
                              baseElectionDelay,
                              perRankDelay,
                              rabiaTermSupplier,
                              consensusReadySupplier,
                              currentLeaderFromKvSupplier,
                              LeaderElectionContext.DEFAULT_LEADER_PING_FRESH_FN);
    }

    private static LeaderManager buildWithLease(NodeId self,
                                                MessageRouter router,
                                                Option<LeaderProposalHandler> proposalHandler,
                                                List<NodeId> expectedCluster,
                                                TimeSpan proposalRetryDelay,
                                                TimeSpan baseElectionDelay,
                                                TimeSpan perRankDelay,
                                                Supplier<Long> rabiaTermSupplier,
                                                Supplier<Boolean> consensusReadySupplier,
                                                Supplier<Option<NodeId>> currentLeaderFromKvSupplier,
                                                Predicate<NodeId> leaderPingFreshFn) {
        var built = LeaderElectionFsm.leaderElectionFsm(self,
                                                        proposalHandler,
                                                        expectedCluster,
                                                        router,
                                                        proposalRetryDelay,
                                                        baseElectionDelay,
                                                        perRankDelay,
                                                        FsmObserver.noop(),
                                                        rabiaTermSupplier,
                                                        consensusReadySupplier,
                                                        currentLeaderFromKvSupplier,
                                                        leaderPingFreshFn);

        return new FsmBackedLeaderManager(built.fsm(), built.context(), proposalHandler.isEmpty());
    }

    /// FSM-backed implementation. `localMode=true` means no consensus; we synthesize
    /// `LeaderCommitted` events locally to drive the FSM.
    record FsmBackedLeaderManager(Fsm<LeaderElectionState, ClusterFsmEvent> fsm,
                                  LeaderElectionContext context,
                                  boolean localMode) implements LeaderManager {
        @Override
        public org.pragmatica.lang.Unit installReactExecutor(ReactExecutor executor) {
            return context.installReactExecutor(executor);
        }

        @Override
        public org.pragmatica.lang.Unit installReplaySource(BooleanSupplier replaying) {
            return context.installReplaySource(replaying);
        }

        @Override
        public boolean replaying() {
            return context.replaying();
        }

        @Override
        public org.pragmatica.lang.Unit installVoterConfiguration(org.pragmatica.consensus.rabia.VoterConfiguration configuration) {
            if (configuration.contains(context.self())) {
                installLeaderEligibility(configuration.members());
                fsm.dispatch(new LeaderElectionEvents.VoterReadmitted());
            } else {
                fsm.dispatch(new LeaderElectionEvents.PassiveDirectory(configuration.members()));
            }

            return org.pragmatica.lang.Unit.unit();
        }

        @Override
        public org.pragmatica.lang.Unit installPassiveCoreDirectory(List<NodeId> members) {
            fsm.dispatch(new LeaderElectionEvents.PassiveDirectory(members));

            return org.pragmatica.lang.Unit.unit();
        }

        /// A leader that lost eligibility is removed view-free (#3044): no read of the view, no re-dispatched view.
        private void installLeaderEligibility(List<NodeId> members) {
            var previous = context.currentLeader();

            context.installVoters(members);
            previous.filter(node -> !context.isEligible(node))
                    .onPresent(node -> fsm.dispatch(new LeaderElectionEvents.PeerRemovedFromView(node)));
        }

        @Override
        public Unit installKvSyncProgress(Supplier<Boolean> pending) {
            context.installKvSyncPending(pending);

            return Unit.unit();
        }

        @Override
        public Unit enableLeaderPreVote() {
            context.enablePreVote();

            return Unit.unit();
        }

        @Contract
        @Override
        public void leaderPreVoteRequest(LeaderPreVoteRequest request) {
            context.preVote().onPresent(preVote -> preVote.onRequest(request));
        }

        @Contract
        @Override
        public void leaderPreVoteResponse(LeaderPreVoteResponse response) {
            context.preVote().onPresent(preVote -> preVote.onResponse(response));
        }

        @Override
        public Option<NodeId> leader() {
            return context.currentLeader();
        }

        @Override
        public boolean isLeader() {
            return context.isLeader();
        }

        @Override
        public Option<Long> currentLeaderEpoch() {
            return context.isLeader()
                   ? Option.some(context.rabiaTermSupplier().get())
                   : Option.none();
        }

        @Contract
        @Override
        public void onLeaderCommitted(NodeId leader) {
            if (!context.isEligible(leader)) {
                return;
            }

            fsm.dispatch(new LeaderElectionEvents.LeaderCommitted(leader));
        }

        @Contract
        @Override
        public void onLeaderCommitted(NodeId leader, long viewSequence) {
            if (!context.isEligible(leader)) {
                return;
            }

            context.observeViewSequence(viewSequence);
            // Carry the committed sequence on the event so the `Led` swap fence (FIX 2) can reject
            // a later-arriving lower-sequence commit. The sequence-less `onLeaderCommitted(leader)`
            // path (local mode / test stubs) dispatches `LeaderCommitted.NO_SEQUENCE` and bypasses
            // the fence, as before.
            fsm.dispatch(new LeaderElectionEvents.LeaderCommitted(leader, viewSequence));
        }

        @Contract
        @Override
        public void triggerElection() {
            fsm.dispatch(new LeaderElectionEvents.ConsensusReady());
        }

        @Contract
        @Override
        public void stop() {
            fsm.dispatch(new ClusterFsmEvent.Shutdown());
        }

        @Contract
        @Override
        public void peerJoined(PeerJoined peerJoined) {
            dispatchNodeAdded(peerJoined.nodeId(), peerJoined.topology(), peerJoined.sequence());
            if (localMode) {
                electLocallyIfPossible();
            }
        }

        @Contract
        @Override
        public void peerDisconnected(PeerDisconnected peerDisconnected) {
            var nodeGone = new ClusterFsmEvent.NodeGone(peerDisconnected.nodeId(),
                                                        peerDisconnected.topology()
                                                                        .stream()
                                                                        .filter(context::isEligible)
                                                                        .toList(),
                                                        peerDisconnected.sequence());

            if (localMode) {
                disconnectLocally(nodeGone);
            } else {
                fsm.dispatch(nodeGone);
            }
        }

        /// Local mode needs the new leader to appear BEFORE the `NodeGone` event, so the FSM stays in `Led` (different
        /// leader) rather than going `Led` → `ReElecting` → `Led`, which would emit an intermediate "no leader"
        /// notification (#3044). The view is applied first, through the same fence the FSM uses: a stale eviction
        /// (older than a view already applied) neither adopts nor dispatches. An applied one adopts from the STORED
        /// view, then dispatches; the FSM's own `applyTopology` finds the event already applied, so `Led` does not
        /// lose the leader the adoption has just moved.
        private void disconnectLocally(ClusterFsmEvent.NodeGone nodeGone) {
            if (context.applyTopology(nodeGone)) {
                electLocallyIfPossible();
                fsm.dispatch(nodeGone);
            }
        }

        @Contract
        @Override
        public void peerObservedFaulty(PeerObservedFaulty peerObservedFaulty) {
            // SWIM-FAULTY removes exactly the faulty peer, view-free (#3044): SWIM's snapshot is not a sequenced
            // transport view, so it never replaces the stored one (it would also drop SUSPECT peers and re-add peers
            // the transport has evicted).
            // Local mode removes the peer first and, only if it was removed, adopts from the STORED view and then
            // dispatches (#3044), so `Led` never sees an intermediate "no leader"; the FSM's removal then finds the peer
            // gone. A refused removal (the last node stays) neither adopts nor dispatches: in local mode the leader is the
            // first entry of the stored view, so nothing was removed and nothing may move, and a dispatch would make `Led`
            // lose a leader that no local commit can restore.
            // Non-local mode dispatches even when nothing was removed: a removal is never stale, and `Led` loses a
            // removed leader unconditionally even when the stored view no longer holds it.
            if (localMode) {
                removeLocally(peerObservedFaulty.nodeId());
            } else {
                fsm.dispatch(new LeaderElectionEvents.PeerRemovedFromView(peerObservedFaulty.nodeId()));
            }
        }

        private void removeLocally(NodeId nodeId) {
            if (context.removeFromView(nodeId)) {
                electLocallyIfPossible();
                fsm.dispatch(new LeaderElectionEvents.PeerRemovedFromView(nodeId));
            }
        }

        @Contract
        @Override
        public void peerReconnected(PeerReconnected peerReconnected) {
            // A peer evicted and re-attached was removed from the stored topology by the eviction's NodeGone, and the
            // FSM replaces its topology wholesale from the last event, so a reconnect must restore it the way a join
            // does; otherwise a follower keeps suspecting a leader whose link is back (leader not in topology).
            // A reconnect that changes nothing (supersede, stale replace) dispatches nothing: an event would
            // reschedule an Electing node's rank staircase for no reason. Its sequence is still recorded (#2971): the
            // stored view already equals this reconnect's, so the write changes no content, but an older eviction
            // routed late must find the fence moved past it, or its view would drop a peer whose link is back.
            if (restoresTheView(peerReconnected.nodeId(), peerReconnected.topology())) {
                dispatchNodeAdded(peerReconnected.nodeId(), peerReconnected.topology(), peerReconnected.sequence());
            } else {
                context.applyTopology(nodeAdded(peerReconnected.nodeId(),
                                                peerReconnected.topology(),
                                                peerReconnected.sequence()));
            }
        }

        private boolean restoresTheView(NodeId nodeId, List<NodeId> topology) {
            var stored = context.currentTopology();
            var eligibleView = topology.stream().filter(context::isEligible).sorted().toList();
            var peerMissing = context.isEligible(nodeId) && !stored.contains(nodeId);

            return peerMissing || !stored.equals(eligibleView);
        }

        private void dispatchNodeAdded(NodeId nodeId, List<NodeId> topology, long sequence) {
            fsm.dispatch(nodeAdded(nodeId, topology, sequence));
        }

        private ClusterFsmEvent.NodeAdded nodeAdded(NodeId nodeId, List<NodeId> topology, long sequence) {
            return new ClusterFsmEvent.NodeAdded(nodeId,
                                                 topology.stream().filter(context::isEligible).toList(),
                                                 sequence);
        }

        @Contract
        @Override
        public void selfShutdown(SelfShutdown selfShutdown) {
            // Self-shutdown is not a cluster decision — local view collapsing.
            fsm.dispatch(new ClusterFsmEvent.QuorumDisappeared());
        }

        @Contract
        @Override
        public void watchClusterState(ClusterStateNotification clusterState) {
            if (!clusterState.advanceSequence(context.quorumSequence())) {
                return;
            }

            switch (clusterState.state()) {
                case ACTIVE -> onClusterActive();
                case PASSIVE -> fsm.dispatch(new ClusterFsmEvent.QuorumDisappeared());
            }
        }

        private void onClusterActive() {
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
