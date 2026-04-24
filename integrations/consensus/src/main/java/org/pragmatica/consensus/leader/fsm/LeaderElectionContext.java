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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/// Shared context for the leader-election FSM. Holds:
/// - Configuration that never changes during the FSM's lifetime (self, expectedCluster, router,
///   delays, proposalHandler).
/// - Mutable bookkeeping that is NOT guard-visible (retry counters, viewSequence, proposal epoch).
/// - Atomic `currentTopology` shared across states — every state reads the latest topology via
///   this reference; `NodeAdded`/`NodeGone` mutate it directly (not as state transitions).
/// - State instances for the per-FSM "singletons" (`dormant`, `quorumWaiting`, `quorumLost`,
///   `stopped`, `electing`, `reElecting`) so CAS comparisons against them are stable.
///
/// Thread safety: atomic fields are thread-safe on their own. The context is referenced by all
/// state instances for a given FSM; states mutate atomic fields directly inside their handlers.
public final class LeaderElectionContext {
    public static final TimeSpan DEFAULT_PROPOSAL_RETRY_DELAY = TimeSpan.timeSpan(500).millis();
    public static final TimeSpan DEFAULT_BASE_ELECTION_DELAY = TimeSpan.timeSpan(2).seconds();
    public static final TimeSpan DEFAULT_PER_RANK_DELAY = TimeSpan.timeSpan(1).seconds();
    public static final TimeSpan DEFAULT_MIN_PROPOSAL_TIMEOUT = TimeSpan.timeSpan(5).seconds();
    public static final int DEFAULT_STUCK_ELECTION_THRESHOLD = 10;

    private final NodeId self;
    private final Option<LeaderProposalHandler> proposalHandler;
    private final List<NodeId> expectedCluster;
    private final MessageRouter router;
    private final TimeSpan proposalRetryDelay;
    private final TimeSpan baseElectionDelay;
    private final TimeSpan perRankDelay;
    private final TimeSpan proposalTimeout;
    private final int stuckElectionThreshold;

    // Per-FSM "singletons" — one instance per state class, shared for the lifetime of this FSM.
    // Built in the constructor via the constructor-driven initial-state factory, so the fields
    // are `final` and CAS comparisons against them return the same reference.
    private final LeaderElectionState.Dormant dormant;
    private final LeaderElectionState.QuorumWaiting quorumWaiting;
    private final LeaderElectionState.Electing electing;
    private final LeaderElectionState.ReElecting reElecting;
    private final LeaderElectionState.QuorumLost quorumLost;
    private final LeaderElectionState.Stopped stopped;

    // Per-FSM Fsm reference — bound at construction time via the constructor-driven initial-state
    // factory. Replaces the global static FSM_REF.
    private final Fsm<LeaderElectionState, ClusterFsmEvent> fsm;

    private final AtomicReference<Option<NodeId>> currentLeader = new AtomicReference<>(Option.none());
    private final AtomicReference<Option<NodeId>> lastNotifiedLeader = new AtomicReference<>(Option.none());
    private final AtomicReference<List<NodeId>> currentTopology = new AtomicReference<>(List.of());
    private final AtomicBoolean consensusReadyPending = new AtomicBoolean(false);
    private final AtomicBoolean hasEverHadLeader = new AtomicBoolean(false);
    private final AtomicBoolean proposalInFlight = new AtomicBoolean(false);
    private final AtomicInteger electionRetryCount = new AtomicInteger(0);
    private final AtomicInteger stuckElectionCount = new AtomicInteger(0);
    private final AtomicLong viewSequence = new AtomicLong(0);
    private final AtomicLong proposalEpoch = new AtomicLong(0);
    private final AtomicLong quorumSequence = new AtomicLong(0);

    LeaderElectionContext(Fsm<LeaderElectionState, ClusterFsmEvent> fsm,
                          NodeId self,
                          Option<LeaderProposalHandler> proposalHandler,
                          List<NodeId> expectedCluster,
                          MessageRouter router,
                          TimeSpan proposalRetryDelay,
                          TimeSpan baseElectionDelay,
                          TimeSpan perRankDelay,
                          TimeSpan proposalTimeout,
                          int stuckElectionThreshold) {
        this.fsm = fsm;
        this.self = self;
        this.proposalHandler = proposalHandler;
        this.expectedCluster = List.copyOf(expectedCluster);
        this.router = router;
        this.proposalRetryDelay = proposalRetryDelay;
        this.baseElectionDelay = baseElectionDelay;
        this.perRankDelay = perRankDelay;
        this.proposalTimeout = proposalTimeout;
        this.stuckElectionThreshold = stuckElectionThreshold;
        this.dormant = new LeaderElectionState.Dormant(this);
        this.quorumWaiting = new LeaderElectionState.QuorumWaiting(this);
        this.electing = new LeaderElectionState.Electing(this);
        this.reElecting = new LeaderElectionState.ReElecting(this);
        this.quorumLost = new LeaderElectionState.QuorumLost(this);
        this.stopped = new LeaderElectionState.Stopped(this);
    }

    static TimeSpan proposalTimeoutFor(TimeSpan proposalRetryDelay) {
        return TimeSpan.timeSpan(Math.max(proposalRetryDelay.millis() * 3L,
                                          DEFAULT_MIN_PROPOSAL_TIMEOUT.millis())).millis();
    }

    // --- Configuration accessors ---

    public NodeId self() { return self; }
    public Option<LeaderProposalHandler> proposalHandler() { return proposalHandler; }
    public List<NodeId> expectedCluster() { return expectedCluster; }
    public MessageRouter router() { return router; }
    public TimeSpan proposalRetryDelay() { return proposalRetryDelay; }
    public TimeSpan baseElectionDelay() { return baseElectionDelay; }
    public TimeSpan perRankDelay() { return perRankDelay; }
    public TimeSpan proposalTimeout() { return proposalTimeout; }
    public int stuckElectionThreshold() { return stuckElectionThreshold; }

    // --- Mutable state accessors ---

    public Option<NodeId> currentLeader() { return currentLeader.get(); }
    public void setCurrentLeader(Option<NodeId> leader) { currentLeader.set(leader); }

    /// Dedup helper: returns `true` iff `leader` differs from the last notified leader and
    /// atomically records the new value. Callers that receive `true` are the ones that should
    /// emit the `LeaderChange` message; others skip to avoid duplicate notifications.
    public boolean markNotified(Option<NodeId> leader) {
        var previous = lastNotifiedLeader.getAndSet(leader);
        return !previous.equals(leader);
    }

    public List<NodeId> currentTopology() { return currentTopology.get(); }
    public void setCurrentTopology(List<NodeId> topology) {
        if (topology.isEmpty()) {
            return;
        }
        var sorted = new ArrayList<>(topology);
        Collections.sort(sorted);
        currentTopology.set(List.copyOf(sorted));
    }

    public boolean consumeConsensusReadyPending() { return consensusReadyPending.compareAndSet(true, false); }
    public void markConsensusReadyPending() { consensusReadyPending.set(true); }

    public boolean hasEverHadLeader() { return hasEverHadLeader.get(); }
    public void markHasEverHadLeader() { hasEverHadLeader.set(true); }

    public boolean tryStartProposal() { return proposalInFlight.compareAndSet(false, true); }
    public void clearProposalInFlight() { proposalInFlight.set(false); }
    public boolean proposalInFlight() { return proposalInFlight.get(); }

    public int incrementElectionRetryCount() { return electionRetryCount.incrementAndGet(); }
    public void resetElectionRetryCount() { electionRetryCount.set(0); }

    public int incrementStuckElectionCount() { return stuckElectionCount.incrementAndGet(); }
    public void resetStuckElectionCount() { stuckElectionCount.set(0); }
    public int stuckElectionCount() { return stuckElectionCount.get(); }

    public long nextViewSequence() { return viewSequence.incrementAndGet(); }
    public long nextProposalEpoch() { return proposalEpoch.incrementAndGet(); }

    public AtomicLong quorumSequence() { return quorumSequence; }

    // --- Per-FSM state instances ---

    public LeaderElectionState.Dormant dormant() { return dormant; }
    public LeaderElectionState.QuorumWaiting quorumWaiting() { return quorumWaiting; }
    public LeaderElectionState.Electing electing() { return electing; }
    public LeaderElectionState.ReElecting reElecting() { return reElecting; }
    public LeaderElectionState.QuorumLost quorumLost() { return quorumLost; }
    public LeaderElectionState.Stopped stopped() { return stopped; }

    public Fsm<LeaderElectionState, ClusterFsmEvent> fsm() {
        return fsm;
    }

    // --- Derived helpers ---

    /// Candidate pool for leader election.
    public List<NodeId> candidatePool() {
        if (expectedCluster.isEmpty() || stuckElectionCount.get() >= stuckElectionThreshold) {
            return currentTopology.get();
        }
        if (!hasEverHadLeader.get()) {
            return expectedCluster;
        }
        var topology = currentTopology.get();
        var filtered = expectedCluster.stream()
                                      .filter(topology::contains)
                                      .toList();
        return filtered.isEmpty() ? topology : filtered;
    }

    public int rankOfSelf() {
        var pool = expectedCluster.isEmpty() ? currentTopology.get() : expectedCluster;
        var sorted = pool.stream().sorted().toList();
        var rank = sorted.indexOf(self);
        return rank >= 0 ? rank : sorted.size();
    }

    public boolean isLeader() {
        return currentLeader.get().filter(self::equals).isPresent();
    }
}
