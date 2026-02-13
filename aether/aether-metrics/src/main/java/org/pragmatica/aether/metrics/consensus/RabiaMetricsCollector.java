package org.pragmatica.aether.metrics.consensus;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.rabia.ConsensusMetrics;
import org.pragmatica.consensus.rabia.Phase;
import org.pragmatica.consensus.rabia.StateValue;
import org.pragmatica.lang.Option;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

import static org.pragmatica.lang.Option.option;

/// Implementation of ConsensusMetrics that collects Rabia consensus statistics.
///
/// Thread-safe: uses atomic operations for all counters.
public final class RabiaMetricsCollector implements ConsensusMetrics {
    private final AtomicLong decisionsCount = new AtomicLong();
    private final AtomicLong proposalsCount = new AtomicLong();
    private final AtomicLong syncSuccessCount = new AtomicLong();
    private final AtomicLong syncFailureCount = new AtomicLong();
    private final AtomicInteger pendingBatches = new AtomicInteger();
    private final LongAdder totalDecisionLatencyNs = new LongAdder();
    private final AtomicReference<String> role = new AtomicReference<>("FOLLOWER");
    private final AtomicReference<Option<String>> leaderId = new AtomicReference<>(Option.empty());

    private RabiaMetricsCollector() {}

    public static RabiaMetricsCollector rabiaMetricsCollector() {
        return new RabiaMetricsCollector();
    }

    @Override
    public void recordDecision(NodeId nodeId, Phase phase, StateValue stateValue, long durationNs) {
        decisionsCount.incrementAndGet();
        totalDecisionLatencyNs.add(durationNs);
    }

    @Override
    public void recordProposal(NodeId nodeId, Phase phase) {
        proposalsCount.incrementAndGet();
    }

    @Override
    public void recordVoteRound1(NodeId nodeId, Phase phase, StateValue stateValue) {}

    @Override
    public void recordVoteRound2(NodeId nodeId, Phase phase, StateValue stateValue) {}

    @Override
    public void recordFastPath(NodeId nodeId, Phase phase, StateValue value) {}

    @Override
    public void recordSyncAttempt(NodeId nodeId, boolean success) {
        if (success) {
            syncSuccessCount.incrementAndGet();
        } else {
            syncFailureCount.incrementAndGet();
        }
    }

    @Override
    public void updatePendingBatches(NodeId nodeId, int count) {
        pendingBatches.set(count);
    }

    /// Update the current role (called externally when leader changes).
    public void updateRole(boolean isLeader, Option<String> currentLeaderId) {
        role.set(isLeader
                 ? "LEADER"
                 : "FOLLOWER");
        leaderId.set(currentLeaderId);
    }

    /// Take a snapshot of current metrics.
    public RabiaMetrics snapshot() {
        return new RabiaMetrics(role.get(),
                                leaderId.get(),
                                pendingBatches.get(),
                                decisionsCount.get(),
                                proposalsCount.get(),
                                syncSuccessCount.get(),
                                syncFailureCount.get(),
                                totalDecisionLatencyNs.sum());
    }

    /// Take a snapshot and reset counters (for delta-based reporting).
    public RabiaMetrics snapshotAndReset() {
        return new RabiaMetrics(role.get(),
                                leaderId.get(),
                                pendingBatches.get(),
                                decisionsCount.getAndSet(0),
                                proposalsCount.getAndSet(0),
                                syncSuccessCount.getAndSet(0),
                                syncFailureCount.getAndSet(0),
                                totalDecisionLatencyNs.sumThenReset());
    }
}
