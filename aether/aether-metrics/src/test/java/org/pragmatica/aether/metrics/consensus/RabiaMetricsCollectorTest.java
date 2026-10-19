// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.metrics.consensus;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.rabia.Phase;
import org.pragmatica.consensus.rabia.StateValue;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The #674 pins. The three vote-traffic recorders were EMPTY BODIES — the engine called them all
/// along (`RabiaEngine` vote/fast-path handlers), and every call vanished, so round-1/round-2 vote
/// volume — the quantity that grows with coordination load — was never counted anywhere. These
/// tests pin the counting, the snapshot/reset split, and the wire vocabulary the Prometheus gauges
/// and the HTTP consensus block share.
class RabiaMetricsCollectorTest {
    private static final NodeId NODE = new NodeId("node-1");
    private static final Phase PHASE = new Phase(1);

    /// THE #674 pin: a recorded vote is a counted vote. Before the fix these three calls were
    /// swallowed and this test's assertions read 0.
    @Test
    void recordVoteRounds_incrementTheirCounters() {
        var collector = RabiaMetricsCollector.rabiaMetricsCollector();

        collector.recordVoteRound1(NODE, PHASE, StateValue.V0);
        collector.recordVoteRound1(NODE, PHASE, StateValue.V1);
        collector.recordVoteRound2(NODE, PHASE, StateValue.V0);

        var snapshot = collector.snapshot();

        assertThat(snapshot.voteRound1Count()).isEqualTo(2);
        assertThat(snapshot.voteRound2Count()).isEqualTo(1);
        assertThat(snapshot.fastPathCount()).as("no fast path since #1390").isZero();
    }

    /// `snapshot()` must not consume: a differencing consumer (the coordination-slope shape) reads
    /// the totals repeatedly over its own window.
    @Test
    void snapshot_preservesVoteCounters() {
        var collector = RabiaMetricsCollector.rabiaMetricsCollector();

        collector.recordVoteRound1(NODE, PHASE, StateValue.V0);
        collector.snapshot();

        assertThat(collector.snapshot().voteRound1Count()).isEqualTo(1);
    }

    /// The reset variant zeroes the vote counters with the other totals — a half-reset would skew
    /// any consumer of the resetting path.
    @Test
    void snapshotAndReset_resetsVoteCounters() {
        var collector = RabiaMetricsCollector.rabiaMetricsCollector();

        collector.recordVoteRound1(NODE, PHASE, StateValue.V0);
        collector.recordVoteRound2(NODE, PHASE, StateValue.V0);

        var first = collector.snapshotAndReset();

        assertThat(first.voteRound1Count()).isEqualTo(1);

        var second = collector.snapshot();

        assertThat(second.voteRound1Count()).isZero();
        assertThat(second.voteRound2Count()).isZero();
        assertThat(second.fastPathCount()).isZero();
    }

    /// The wire vocabulary: `counterMap()`'s keys ARE the Prometheus gauge names
    /// (`ObservabilityRegistry.registerConsensusMetrics` binds each by exact key), so a drifted key
    /// silently freezes its gauge at 0. Pinned entry-by-entry with live values.
    @Test
    void counterMap_carriesTheExactGaugeVocabulary() {
        var collector = RabiaMetricsCollector.rabiaMetricsCollector();

        collector.recordProposal(NODE, PHASE);
        collector.recordDecision(NODE, PHASE, StateValue.V0, 1_000_000L);
        collector.recordVoteRound1(NODE, PHASE, StateValue.V0);
        collector.recordVoteRound2(NODE, PHASE, StateValue.V0);
        collector.recordSyncAttempt(NODE, true);
        collector.recordSyncAttempt(NODE, false);
        collector.updatePendingBatches(NODE, 3);

        var map = collector.snapshot().counterMap();

        assertThat(map).containsOnlyKeys("consensus_decisions_total",
                                         "consensus_proposals_total",
                                         "consensus_vote_round1_total",
                                         "consensus_vote_round2_total",
                                         "consensus_fast_path_total",
                                         "consensus_sync_success_total",
                                         "consensus_sync_failure_total",
                                         "consensus_pending_batches",
                                         "consensus_oldest_pending_batch_age_ms",
                                         "consensus_apply_timeouts_total");
        assertThat(map.get("consensus_vote_round1_total").longValue()).isEqualTo(1);
        assertThat(map.get("consensus_pending_batches").intValue()).isEqualTo(3);
    }

    /// #1117: the age is read from the engine's probe when the snapshot is taken, so a batch that is stuck keeps
    /// reading older with no event to push it; the sink starts with a probe that reads 0.
    @Test
    void oldestPendingBatchAge_isReadFromTheRegisteredProbeAtSnapshotTime() {
        var collector = RabiaMetricsCollector.rabiaMetricsCollector();
        var age = new java.util.concurrent.atomic.AtomicLong(0L);

        assertThat(collector.snapshot().oldestPendingBatchAgeMs()).as("no probe registered yet").isZero();

        collector.registerOldestPendingBatchAgeProbe(NODE, age::get);
        age.set(12_345L);

        assertThat(collector.snapshot().oldestPendingBatchAgeMs()).isEqualTo(12_345L);
        assertThat(collector.snapshot().counterMap().get("consensus_oldest_pending_batch_age_ms").longValue())
            .as("the Prometheus vocabulary carries the same level")
            .isEqualTo(12_345L);

        age.set(20_000L);

        assertThat(collector.snapshotAndReset().oldestPendingBatchAgeMs())
            .as("a level is read, never reset")
            .isEqualTo(20_000L);
        assertThat(collector.snapshot().oldestPendingBatchAgeMs()).isEqualTo(20_000L);
    }

    @Test
    void applyTimeouts_areCounted_andReset() {
        var collector = RabiaMetricsCollector.rabiaMetricsCollector();

        collector.recordApplyTimeout(NODE);
        collector.recordApplyTimeout(NODE);

        assertThat(collector.snapshot().applyTimeoutCount()).isEqualTo(2);
        assertThat(collector.snapshot().counterMap().get("consensus_apply_timeouts_total").longValue()).isEqualTo(2);
        assertThat(collector.snapshotAndReset().applyTimeoutCount()).isEqualTo(2);
        assertThat(collector.snapshot().applyTimeoutCount()).as("a total, differenced by the consumer, resets like its siblings").isZero();
    }
}
