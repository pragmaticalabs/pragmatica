// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.StreamTripwireSupport.engineKey;
import static org.pragmatica.aether.ember.StreamTripwireSupport.sleepQuietly;

/// Streams overhaul tripwire, step 2 (#2201), memory budget: C-03 - the default 128 MiB off-heap budget holds about 50
/// partition copies per node.
///
/// A 3-node cluster with the DEFAULT streaming configuration (the budget is `StreamPartitionManager.DEFAULT_MAX_TOTAL_BYTES`,
/// 128 MiB, unless `STREAM_MAX_MEMORY_BYTES` is set) is asked for one stream of 64 partitions at the DEFAULT retention
/// (100,000 events, 256 MiB). Replication factor 3 on three nodes puts a copy of every partition on every node: 64 copies
/// per node, 192 in the cluster. A copy's floor is `64 + 24 * maxCount + min(256 KiB, maxBytes)` = 2,662,208 bytes
/// (the ring index is allocated up front), so 128 MiB fits 50 of them.
///
/// - The ENABLED tripwire asserts today's behaviour: no node materializes more than 51 of the stream's 64 copies, the rest
///   are "deferred metadata-only" with a WARN, and the cluster holds fewer than the 192 copies it was asked for. A control
///   stream with a small ring (4 partitions, 1,000 events) is fully materialized on every node, which shows the count can
///   see a complete placement.
/// - The `@Disabled` assertion is the acceptance criterion: the default budget accommodates a 64-partition RF 3 stream on
///   three nodes (all 192 copies materialize).
///
/// Acceptance is stated at this shape because it is the one the audit's arithmetic implies; the remedy (a RAM-derived
/// default, a lazily grown index) is the overhaul's to choose.
@PortBudget
class EmberMemoryBudgetTripwireTest {
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final int PARTITIONS = 64;
    private static final int COPIES_ASKED_FOR = PARTITIONS * StreamTripwireSupport.CLUSTER_SIZE;
    /// 128 MiB / 2,662,208 B = 50.4, and the control stream's few small rings take a sliver.
    private static final int MOST_COPIES_THE_BUDGET_HOLDS = 51;
    /// Replica materialization is paced (default 2 slots, each held through a 20 s source wait on an empty partition); this
    /// test is about the budget, so the pacing is lifted. Everything else is the node default.
    private static final int RESHUFFLE_CONCURRENCY = 256;
    private static final long QUIESCE_MS = 30_000L;
    private static final long SETTLE_BUDGET_MS = 300_000L;
    private static final String CONTROL = "c03ctl";
    private static final String BIG = "c03";

    private EmberCluster cluster;
    private Path dataDir;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            assertThat(cluster.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped")).isEqualTo("stopped");
        }
        StreamTripwireSupport.deleteTree(dataDir);
    }

    @Test
    @Timeout(900)
    void bigStreamAtDefaultRetention_tripwire_isMaterializedOnlyUpToTheBudget() {
        var capture = StreamTripwireSupport.captureLogs();

        try {
            createStreams();

            var controlCopies = settledCopies(CONTROL, 4);
            var bigCopies = settledCopies(BIG, PARTITIONS);
            var deferrals = StreamTripwireSupport.logLines(capture, Level.WARN).stream().filter(line -> line.contains("Off-heap budget exhausted materializing " + engineKey(BIG))).count();

            assertThat(controlCopies).as("control: the small control stream is fully materialized on every node (4 partitions x 3 nodes)")
                                     .containsExactly(4, 4, 4);
            assertThat(bigCopies).as("C-03 fixed? The default budget now holds the 64-partition RF 3 stream on three nodes (copies per node: %s). "
                                     + "Delete this tripwire and enable bigStreamAtDefaultRetention_isFullyMaterializedOnThreeNodes (#2201)",
                                     bigCopies)
                                 .allSatisfy(copies -> assertThat(copies).isLessThanOrEqualTo(MOST_COPIES_THE_BUDGET_HOLDS));
            assertThat(bigCopies.stream().mapToInt(Integer::intValue).sum()).as("fewer copies than the %d asked for", COPIES_ASKED_FOR).isLessThan(COPIES_ASKED_FOR);
            assertThat(deferrals).as("the refusal is a WARN 'Off-heap budget exhausted ... deferred metadata-only' naming the stream").isPositive();
        } finally {
            capture.close();
        }
    }

    @Test
    @Disabled("C-03 (#2201): enable when the default budget holds a 64-partition RF 3 stream on three nodes; RED today: at most ~50 copies per node materialize")
    @Timeout(900)
    void bigStreamAtDefaultRetention_isFullyMaterializedOnThreeNodes() {
        createStreams();

        assertThat(settledCopies(BIG, PARTITIONS)).as("copies of the %d-partition stream materialized per node", PARTITIONS)
                                                  .containsExactly(PARTITIONS, PARTITIONS, PARTITIONS);
    }

    private void createStreams() {
        cluster = EmberTestPorts.startedCluster(StreamTripwireSupport.PORTS, this::threeNodes, START_BOUND);
        StreamTripwireSupport.awaitLeader(cluster);

        var creator = cluster.allNodes().getFirst();

        StreamTripwireSupport.createStream(creator, StreamConfig.streamConfig(engineKey(CONTROL), 4, RetentionPolicy.retentionPolicy(1_000, 64L * 1024 * 1024, 3_600_000L), "earliest"));
        StreamTripwireSupport.createStream(creator, StreamConfig.streamConfig(engineKey(BIG), PARTITIONS, RetentionPolicy.retentionPolicy(), "earliest"));
    }

    /// Per node, the number of the stream's partitions whose ring is materialized there, once the count has stopped moving
    /// for [#QUIESCE_MS] (replica materialization is paced, so the count climbs for a while).
    private List<Integer> settledCopies(String stream, int partitions) {
        var deadline = System.currentTimeMillis() + SETTLE_BUDGET_MS;
        var last = copies(stream, partitions);
        var stableSince = System.currentTimeMillis();

        while (System.currentTimeMillis() < deadline) {
            sleepQuietly(1_000L);

            var now = copies(stream, partitions);

            if (!now.equals(last)) {
                last = now;
                stableSince = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - stableSince >= QUIESCE_MS) {
                return now;
            }
        }

        throw new AssertionError("the materialized-copy count of " + stream + " did not settle within " + SETTLE_BUDGET_MS + " ms; last " + last);
    }

    private List<Integer> copies(String stream, int partitions) {
        return cluster.allNodes().stream().map(node -> copiesOn(node, stream, partitions)).toList();
    }

    private static int copiesOn(AetherNode node, String stream, int partitions) {
        return (int) IntStream.range(0, partitions)
                              .filter(partition -> node.streamPartitionManager().partitionBuffer(engineKey(stream), partition).isPresent())
                              .count();
    }

    private EmberCluster threeNodes(int basePort) {
        dataDir = StreamTripwireSupport.newDataDir("c03-tripwire");

        return StreamTripwireSupport.threeNodes(basePort, "c03", dataDir, RESHUFFLE_CONCURRENCY);
    }
}
