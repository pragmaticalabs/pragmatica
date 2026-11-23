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
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderCommitted;
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
import org.pragmatica.consensus.topology.TransportView;
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

    /// Wires the transport's sequenced view (#3047): after a view-free removal (SWIM-FAULTY, lost eligibility) the
    /// election re-reads it a bounded period later, so a node removed while its transport link stayed up returns
    /// without waiting for the next transport observation. Default no-op for implementations without an election FSM,
    /// and for local election, which has no such view.
    default Unit installTransportView(Supplier<Option<TransportView>> view) {
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
    ///   - `LeaderKey` read as the committed `LeaderCommitted(leader, viewSequence)` as `currentLeaderFromKvSupplier`
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
                                       Supplier<Option<LeaderCommitted>> currentLeaderFromKvSupplier) {
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
                                       Supplier<Option<LeaderCommitted>> currentLeaderFromKvSupplier,
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
                                       Supplier<Option<LeaderCommitted>> currentLeaderFromKvSupplier) {
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
                                                Supplier<Option<LeaderCommitted>> currentLeaderFromKvSupplier,
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
    ///
    /// #3587: every entry point that delivers a leader-election event, or reads and writes election state on the way to
    /// one, enters the node's one serial executor ([LeaderElectionContext#serially], [LeaderElectionContext#submit]), as
    /// the FSM's own timers and proposal completions do: every event of the node is handled in one total order, so a
    /// handler's check-then-act is atomic against every other event. A call made while another thread is draining
    /// returns before its event is handled. The install-time wiring (`install*`, `enableLeaderPreVote`) and the reads
    /// (`leader`, `isLeader`, `currentLeaderEpoch`, `replaying`) are not events and stay on the caller's thread.
    /// `stop` handles every earlier event, then `Shutdown`, and rejects every later one ([LeaderElectionContext#stop]).
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
            context.serially(() -> applyVoterConfiguration(configuration));

            return org.pragmatica.lang.Unit.unit();
        }

        private void applyVoterConfiguration(org.pragmatica.consensus.rabia.VoterConfiguration configuration) {
            if (configuration.contains(context.self())) {
                installLeaderEligibility(configuration.members());
                context.submit(new LeaderElectionEvents.VoterReadmitted());
            } else {
                context.submit(new LeaderElectionEvents.PassiveDirectory(configuration.members()));
            }
        }

        @Override
        public org.pragmatica.lang.Unit installPassiveCoreDirectory(List<NodeId> members) {
            context.submit(new LeaderElectionEvents.PassiveDirectory(members));

            return org.pragmatica.lang.Unit.unit();
        }

        /// A leader that lost eligibility is removed view-free (#3044): no read of the view, no re-dispatched view.
        private void installLeaderEligibility(List<NodeId> members) {
            var previous = context.currentLeader();

            context.installVoters(members);
            previous.filter(node -> !context.isEligible(node))
                    .onPresent(node -> context.submit(new LeaderElectionEvents.PeerRemovedFromView(node)));
        }

        @Override
        public Unit installKvSyncProgress(Supplier<Boolean> pending) {
            context.installKvSyncPending(pending);

            return Unit.unit();
        }

        @Override
        public Unit installTransportView(Supplier<Option<TransportView>> view) {
            context.installTransportView(view, LeaderElectionContext.DEFAULT_REASSERT_PERIOD);

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
            context.serially(() -> context.preVote()
                                          .onPresent(preVote -> preVote.onRequest(request)));
        }

        @Contract
        @Override
        public void leaderPreVoteResponse(LeaderPreVoteResponse response) {
            context.serially(() -> context.preVote()
                                          .onPresent(preVote -> preVote.onResponse(response)));
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
            context.serially(() -> commitLeader(leader));
        }

        private void commitLeader(NodeId leader) {
            if (!context.isEligible(leader)) {
                return;
            }

            context.submit(new LeaderElectionEvents.LeaderCommitted(leader));
        }

        @Contract
        @Override
        public void onLeaderCommitted(NodeId leader, long viewSequence) {
            context.serially(() -> commitLeader(leader, viewSequence));
        }

        private void commitLeader(NodeId leader, long viewSequence) {
            if (!context.isEligible(leader)) {
                return;
            }

            context.observeViewSequence(viewSequence);
            // Carry the committed sequence on the event so the `Led` swap fence (FIX 2) can reject
            // a later-arriving lower-sequence commit. The sequence-less `onLeaderCommitted(leader)`
            // path (local mode / test stubs) dispatches `LeaderCommitted.NO_SEQUENCE` and bypasses
            // the fence, as before.
            context.submit(new LeaderElectionEvents.LeaderCommitted(leader, viewSequence));
        }

        @Contract
        @Override
        public void triggerElection() {
            context.submit(new LeaderElectionEvents.ConsensusReady());
        }

        @Contract
        @Override
        public void stop() {
            context.stop();
        }

        @Contract
        @Override
        public void peerJoined(PeerJoined peerJoined) {
            context.serially(() -> dispatchView(nodeAdded(peerJoined.nodeId(),
                                                          peerJoined.topology(),
                                                          peerJoined.sequence())));
        }

        @Contract
        @Override
        public void peerDisconnected(PeerDisconnected peerDisconnected) {
            context.serially(() -> disconnectPeer(peerDisconnected));
        }

        private void disconnectPeer(PeerDisconnected peerDisconnected) {
            var nodeGone = new ClusterFsmEvent.NodeGone(peerDisconnected.nodeId(),
                                                        peerDisconnected.topology()
                                                                        .stream()
                                                                        .filter(context::isEligible)
                                                                        .toList(),
                                                        peerDisconnected.sequence());

            dispatchView(nodeGone);
        }

        private void dispatchView(ClusterFsmEvent.TopologyChange event) {
            dispatchViewChange(() -> context.applyTopology(event), event);
        }

        /// Every view change (a sequenced view, a view-free removal) takes this one path. Cluster mode dispatches it.
        /// Local mode needs the new leader to appear BEFORE the event, so `Led` stays in `Led` (another leader) instead
        /// of going `Led` -> `ReElecting` -> `Led`, which would emit an intermediate "no leader" notification (#3044):
        /// the change is applied first, through the storage the FSM uses, then the local leader is elected from the
        /// stored view, then the event is dispatched. A change the storage does not apply (a stale view; a removal of an
        /// absent node, of self or of the last node) neither elects nor dispatches: an unchanged view moves nothing. The
        /// FSM finds the change already applied and holds the leader to the stored view (I2): an empty view elects
        /// nothing, so `Led` loses it. A reconnect never takes this path (see [#peerReconnected]): it never elects.
        private void dispatchViewChange(BooleanSupplier applyLocally, ClusterFsmEvent event) {
            if (!localMode) {
                context.submit(event);
            } else if (applyLocally.getAsBoolean()) {
                electLocallyIfPossible();
                context.submit(event);
            }
        }

        @Contract
        @Override
        public void peerObservedFaulty(PeerObservedFaulty peerObservedFaulty) {
            context.serially(() -> removeFaultyPeer(peerObservedFaulty));
        }

        private void removeFaultyPeer(PeerObservedFaulty peerObservedFaulty) {
            // SWIM-FAULTY removes exactly the faulty peer, view-free (#3044): SWIM's snapshot is not a sequenced
            // transport view, so it never replaces the stored one (it would also drop SUSPECT peers and re-add peers
            // the transport has evicted). It takes the one view-change path; cluster mode dispatches even when the
            // storage removes nothing, because a removal is never stale and `Led` loses a leader named failed.
            var nodeId = peerObservedFaulty.nodeId();

            dispatchViewChange(() -> context.removeFromView(nodeId),
                               new LeaderElectionEvents.PeerRemovedFromView(nodeId));
        }

        @Contract
        @Override
        public void peerReconnected(PeerReconnected peerReconnected) {
            context.serially(() -> reconnectPeer(peerReconnected));
        }

        private void reconnectPeer(PeerReconnected peerReconnected) {
            // A peer evicted and re-attached was removed from the stored topology by the eviction's NodeGone, and the
            // FSM replaces its topology wholesale from the last event, so a reconnect must restore it the way a join
            // does; otherwise a follower keeps suspecting a leader whose link is back (leader not in topology).
            // A reconnect that changes nothing (supersede, stale replace) dispatches nothing: an event would
            // reschedule an Electing node's rank staircase for no reason. Its sequence is still recorded (#2971): the
            // stored view already equals this reconnect's, so the write changes no content, but an older eviction
            // routed late must find the fence moved past it, or its view would drop a peer whose link is back.
            // A reconnect never elects, in local mode either (#3044): it is dispatched to the FSM, which stores the view
            // and holds the leader to it (I2), but it never swaps a leader that is still in the view for the view's first
            // node. A lower node coming back is not a reason to move the leader; I3's election ticks cover a node that
            // holds no leader.
            if (restoresTheView(peerReconnected.nodeId(), peerReconnected.topology())) {
                context.submit(nodeAdded(peerReconnected.nodeId(),
                                         peerReconnected.topology(),
                                         peerReconnected.sequence()));
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

        private ClusterFsmEvent.NodeAdded nodeAdded(NodeId nodeId, List<NodeId> topology, long sequence) {
            return new ClusterFsmEvent.NodeAdded(nodeId,
                                                 topology.stream().filter(context::isEligible).toList(),
                                                 sequence);
        }

        @Contract
        @Override
        public void selfShutdown(SelfShutdown selfShutdown) {
            // Self-shutdown is not a cluster decision — local view collapsing.
            context.submit(new ClusterFsmEvent.QuorumDisappeared());
        }

        @Contract
        @Override
        public void watchClusterState(ClusterStateNotification clusterState) {
            context.serially(() -> applyClusterState(clusterState));
        }

        private void applyClusterState(ClusterStateNotification clusterState) {
            if (!clusterState.advanceSequence(context.quorumSequence())) {
                return;
            }

            switch (clusterState.state()) {
                case ACTIVE -> onClusterActive();
                case PASSIVE -> context.submit(new ClusterFsmEvent.QuorumDisappeared());
            }
        }

        private void onClusterActive() {
            context.submit(new ClusterFsmEvent.QuorumEstablished());
            if (localMode) {
                context.submit(new LeaderElectionEvents.ConsensusReady());
                electLocallyIfPossible();
            }
        }

        /// No external FSM-state read here: each state's handler ignores or logs a `LeaderCommitted` that does not fit
        /// it. An empty applied view has no candidate and commits nothing (I1: it is the view, not the one it replaced).
        private void electLocallyIfPossible() {
            context.localLeaderCandidate()
                   .onPresent(leader -> context.submit(new LeaderElectionEvents.LeaderCommitted(leader)));
        }
    }
}
