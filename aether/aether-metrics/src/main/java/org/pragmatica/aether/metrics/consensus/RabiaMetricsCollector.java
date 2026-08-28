// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics.consensus;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.rabia.ConsensusMetrics;
import org.pragmatica.lang.Contract;
import org.pragmatica.consensus.rabia.Phase;
import org.pragmatica.consensus.rabia.StateValue;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Result.unitResult;


public final class RabiaMetricsCollector implements ConsensusMetrics {
    private final AtomicLong decisionsCount = new AtomicLong();
    private final AtomicLong proposalsCount = new AtomicLong();
    private final AtomicLong voteRound1Count = new AtomicLong();
    private final AtomicLong voteRound2Count = new AtomicLong();
    private final AtomicLong fastPathCount = new AtomicLong();
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
    @Contract
    public void recordDecision(NodeId nodeId, Phase phase, StateValue stateValue, long durationNs) {
        decisionsCount.incrementAndGet();
        totalDecisionLatencyNs.add(durationNs);
    }

    @Override
    @Contract
    public void recordProposal(NodeId nodeId, Phase phase) {
        proposalsCount.incrementAndGet();
    }

    /// The three vote-traffic recorders were EMPTY BODIES until #674 — round-1/round-2 vote volume
    /// is precisely the quantity that grows with coordination load, and nothing anywhere counted it,
    /// which left the #367/#368 scale ladder measuring a subsystem with no wire-visible load signal.
    /// The engine has called these all along (`RabiaEngine` vote/fast-path handlers); only the
    /// counting was missing.
    @Override
    @Contract
    public void recordVoteRound1(NodeId nodeId, Phase phase, StateValue stateValue) {
        voteRound1Count.incrementAndGet();
    }

    @Override
    @Contract
    public void recordVoteRound2(NodeId nodeId, Phase phase, StateValue stateValue) {
        voteRound2Count.incrementAndGet();
    }

    @Override
    @Contract
    public void recordFastPath(NodeId nodeId, Phase phase, StateValue value) {
        fastPathCount.incrementAndGet();
    }

    @Override
    @Contract
    public void recordSyncAttempt(NodeId nodeId, boolean success) {
        if (success) {
            syncSuccessCount.incrementAndGet();
        } else {
            syncFailureCount.incrementAndGet();
        }
    }

    @Override
    @Contract
    public void updatePendingBatches(NodeId nodeId, int count) {
        pendingBatches.set(count);
    }

    public Result<Unit> updateRole(boolean isLeader, Option<String> currentLeaderId) {
        role.set(isLeader
                 ? "LEADER"
                 : "FOLLOWER");
        leaderId.set(currentLeaderId);

        return unitResult();
    }

    public RabiaMetrics snapshot() {
        return new RabiaMetrics(role.get(),
                                leaderId.get(),
                                pendingBatches.get(),
                                decisionsCount.get(),
                                proposalsCount.get(),
                                voteRound1Count.get(),
                                voteRound2Count.get(),
                                fastPathCount.get(),
                                syncSuccessCount.get(),
                                syncFailureCount.get(),
                                totalDecisionLatencyNs.sum());
    }

    public RabiaMetrics snapshotAndReset() {
        return new RabiaMetrics(role.get(),
                                leaderId.get(),
                                pendingBatches.get(),
                                decisionsCount.getAndSet(0),
                                proposalsCount.getAndSet(0),
                                voteRound1Count.getAndSet(0),
                                voteRound2Count.getAndSet(0),
                                fastPathCount.getAndSet(0),
                                syncSuccessCount.getAndSet(0),
                                syncFailureCount.getAndSet(0),
                                totalDecisionLatencyNs.sumThenReset());
    }
}
