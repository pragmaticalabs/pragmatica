/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package org.pragmatica.consensus.leader.fsm;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderManager.LeaderProposalHandler;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/// Mutable shared state for the leader-election FSM. All fields are mutated ONLY from the
/// dispatcher thread. `currentLeader` is published via `AtomicReference` because it is read
/// from non-dispatcher threads (the LeaderManager public API exposes a synchronous
/// `leader()` / `isLeader()` read).
public final class LeaderElectionContext {
    private final NodeId self;
    private final Option<LeaderProposalHandler> proposalHandler;
    private final List<NodeId> expectedCluster;
    private final MessageRouter router;
    private final TimeSpan proposalRetryDelay;
    private final TimeSpan baseElectionDelay;
    private final TimeSpan perRankDelay;
    private final TimeSpan proposalTimeout;
    private final int stuckElectionThreshold;

    private final AtomicReference<Option<NodeId>> currentLeader = new AtomicReference<>(Option.none());
    private List<NodeId> currentTopology = List.of();
    private long viewSequence = 0L;
    private long quorumSequence = 0L;
    private boolean proposalInFlight = false;
    private boolean hasEverHadLeader = false;
    private int electionRetryCount = 0;
    private int stuckElectionCount = 0;
    private long currentProposalEpoch = 0L;
    private boolean consensusReadyPending = false;
    private LeaderElectionEvent pendingEvent;

    public LeaderElectionContext(NodeId self,
                                 Option<LeaderProposalHandler> proposalHandler,
                                 List<NodeId> expectedCluster,
                                 MessageRouter router,
                                 TimeSpan proposalRetryDelay,
                                 TimeSpan baseElectionDelay,
                                 TimeSpan perRankDelay,
                                 TimeSpan proposalTimeout,
                                 int stuckElectionThreshold) {
        this.self = self;
        this.proposalHandler = proposalHandler;
        this.expectedCluster = List.copyOf(expectedCluster);
        this.router = router;
        this.proposalRetryDelay = proposalRetryDelay;
        this.baseElectionDelay = baseElectionDelay;
        this.perRankDelay = perRankDelay;
        this.proposalTimeout = proposalTimeout;
        this.stuckElectionThreshold = stuckElectionThreshold;
    }

    // --- identity / configuration ---

    public NodeId self() { return self; }
    public Option<LeaderProposalHandler> proposalHandler() { return proposalHandler; }
    public List<NodeId> expectedCluster() { return expectedCluster; }
    public MessageRouter router() { return router; }
    public TimeSpan proposalRetryDelay() { return proposalRetryDelay; }
    public TimeSpan baseElectionDelay() { return baseElectionDelay; }
    public TimeSpan perRankDelay() { return perRankDelay; }
    public TimeSpan proposalTimeout() { return proposalTimeout; }
    public int stuckElectionThreshold() { return stuckElectionThreshold; }

    // --- mutable state (dispatcher thread only for writes) ---

    public Option<NodeId> currentLeader() { return currentLeader.get(); }
    public void setCurrentLeader(Option<NodeId> leader) { currentLeader.set(leader); }

    public List<NodeId> currentTopology() { return currentTopology; }
    public void setCurrentTopology(List<NodeId> topology) {
        var sorted = new ArrayList<>(topology);
        Collections.sort(sorted);
        this.currentTopology = List.copyOf(sorted);
    }

    public long viewSequence() { return viewSequence; }
    public long incrementViewSequence() { return ++viewSequence; }

    public long quorumSequence() { return quorumSequence; }
    public void setQuorumSequence(long seq) { this.quorumSequence = seq; }

    public boolean proposalInFlight() { return proposalInFlight; }
    public void setProposalInFlight(boolean inFlight) { this.proposalInFlight = inFlight; }

    public boolean hasEverHadLeader() { return hasEverHadLeader; }
    public void markHasEverHadLeader() { this.hasEverHadLeader = true; }

    public int electionRetryCount() { return electionRetryCount; }
    public int incrementElectionRetryCount() { return ++electionRetryCount; }
    public void resetElectionRetryCount() { this.electionRetryCount = 0; }

    public int stuckElectionCount() { return stuckElectionCount; }
    public int incrementStuckElectionCount() { return ++stuckElectionCount; }
    public void resetStuckElectionCount() { this.stuckElectionCount = 0; }

    public long currentProposalEpoch() { return currentProposalEpoch; }
    public long nextProposalEpoch() { return ++currentProposalEpoch; }

    public boolean consumeConsensusReadyPending() {
        if (consensusReadyPending) {
            consensusReadyPending = false;
            return true;
        }
        return false;
    }

    public void markConsensusReadyPending() {
        this.consensusReadyPending = true;
    }

    public LeaderElectionEvent pendingEvent() { return pendingEvent; }
    public void setPendingEvent(LeaderElectionEvent event) { this.pendingEvent = event; }

    // --- derived helpers ---

    /// Candidate pool used for election.
    /// Initial election (hasEverHadLeader==false) uses expectedCluster unfiltered so every node
    /// agrees on the same candidate regardless of local topology view.
    /// Re-election filters expectedCluster by current topology, falling back to topology itself
    /// if the intersection is empty (handles the degraded case where expectedCluster drifted).
    /// After `stuckElectionThreshold` consecutive failures, relaxes to raw topology so a stuck
    /// election can progress even when expectedCluster cannot be satisfied.
    public List<NodeId> candidatePool() {
        if (expectedCluster.isEmpty() || stuckElectionCount >= stuckElectionThreshold) {
            return currentTopology;
        }
        if (!hasEverHadLeader) {
            return expectedCluster;
        }
        var filtered = expectedCluster.stream()
                                      .filter(currentTopology::contains)
                                      .toList();
        return filtered.isEmpty() ? currentTopology : filtered;
    }

    public int rankOfSelf() {
        var pool = expectedCluster.isEmpty() ? currentTopology : expectedCluster;
        var sorted = pool.stream().sorted().toList();
        var rank = sorted.indexOf(self);
        return rank >= 0 ? rank : sorted.size();
    }

    public boolean isLeader() {
        return currentLeader.get().filter(self::equals).isPresent();
    }
}
