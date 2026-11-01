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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.config.StreamingConfig;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.StreamWriteRouter;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.StreamTripwireSupport.engineKey;
import static org.pragmatica.aether.ember.StreamTripwireSupport.sleepQuietly;

/// NT-106 (#2201 C-03, spec 4.4 item 5, F-07): the steady-state soak of the floor headroom on a 3-node cluster.
///
/// Every node's off-heap stream budget is [#BUDGET] (`[streaming] max_memory_bytes`, small so the fill is short). Two RF 3
/// streams whose rings may grow far past the budget are each published with more than the whole budget, so an owner's ring
/// alone could fill it. The test waits until every node's allocation is past the growth share (three quarters of the budget),
/// then until no node's allocation has moved for [#ALLOCATION_QUIESCE_MS], longer than the 20 s source wait a fresh replica
/// can sit in before it applies anything. Then a stream is created. Its floors are admitted against the
/// WHOLE budget, so it materializes on every node: the rings were refused growth at the share and froze there.
///
/// Red under MT-37 (growth admitted against the whole budget): the rings then grow to within one segment of the budget, and
/// the late stream's floor (more than a segment) is refused on every node, "deferred metadata-only" with a WARN.
@PortBudget
class EmberStreamFloorHeadroomTest {
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan PUBLISH_BOUND = TimeSpan.timeSpan(30).seconds();
    private static final long MIB = 1024 * 1024L;
    private static final long SEGMENT = 256 * 1024L;
    private static final long BUDGET = 32 * MIB;
    private static final long GROWTH_SHARE = BUDGET - BUDGET / 4;
    private static final int PAYLOAD = 4 * 1024;
    private static final int BATCH = 64;
    /// Published per fill stream: more than the whole budget, so the owner's ring alone fills it when growth is allowed past
    /// the share, however far a replica lags.
    private static final long FILL_BYTES_PER_STREAM = 40 * MIB;
    private static final int LATE_PARTITIONS = 4;
    private static final int RESHUFFLE_CONCURRENCY = 256;
    private static final long QUIESCE_MS = 10_000L;
    private static final long ALLOCATION_QUIESCE_MS = 30_000L;
    private static final long SETTLE_BUDGET_MS = 120_000L;
    private static final List<String> FILLS = List.of("f106a", "f106b");
    private static final String LATE = "late106";

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
    @Timeout(600)
    void afterTheRingsHaveFilledTheGrowthShare_aLaterStreamsFloorsAreAdmittedOnEveryNode() {
        cluster = EmberTestPorts.startedCluster(StreamTripwireSupport.PORTS, this::threeNodes, START_BOUND);
        StreamTripwireSupport.awaitLeader(cluster);
        cluster.allNodes()
               .forEach(node -> assertThat(node.streamPartitionManager().maxTotalBytes()).as("precondition: [streaming] max_memory_bytes reached %s",
                                                                                             node.self().id())
                                                                                         .isEqualTo(BUDGET));

        var capture = StreamTripwireSupport.captureLogs();

        try {
            FILLS.forEach(this::fill);
            StreamTripwireSupport.awaitUntil(() -> allocated().stream().allMatch(bytes -> bytes > GROWTH_SHARE - SEGMENT),
                                             SETTLE_BUDGET_MS,
                                             "precondition: every node's rings took the growth share");
            awaitAllocationsQuiesced();

            StreamTripwireSupport.createStream(cluster.allNodes().getFirst(),
                                               StreamConfig.streamConfig(engineKey(LATE),
                                                                         LATE_PARTITIONS,
                                                                         RetentionPolicy.retentionPolicy(1_000, 64 * MIB, 3_600_000L),
                                                                         "earliest"));

            var lateCopies = settledCopies();
            var deferrals = StreamTripwireSupport.logLines(capture, Level.WARN)
                                                 .stream()
                                                 .filter(line -> line.contains("Off-heap budget exhausted materializing " + engineKey(LATE)))
                                                 .toList();

            assertThat(lateCopies).as("NT-106: the late stream's %d partitions materialized on every node (allocated per node: %s)",
                                      LATE_PARTITIONS,
                                      allocated())
                                  .containsExactly(LATE_PARTITIONS, LATE_PARTITIONS, LATE_PARTITIONS);
            assertThat(deferrals).as("no floor of the late stream was deferred for budget").isEmpty();
        } finally {
            capture.close();
        }
    }

    /// Creates `stream` (one partition, a ring far larger than the budget) and publishes [#FILL_BYTES_PER_STREAM] through its
    /// owner, in batches.
    private void fill(String stream) {
        var key = engineKey(stream);

        StreamTripwireSupport.createStream(cluster.allNodes().getFirst(),
                                           StreamConfig.streamConfig(key, 1, RetentionPolicy.retentionPolicy(20_000, 256 * MIB, 3_600_000L), "earliest"));

        var record = StreamTripwireSupport.awaitFullIsr(cluster, key, 60_000L);
        var router = StreamTripwireSupport.component(cluster.getNode(record.owner().id()).unwrap(), "streamWriteRouter", StreamWriteRouter.class);
        var batches = FILL_BYTES_PER_STREAM / ((long) PAYLOAD * BATCH);

        for (var batch = 0; batch < batches; batch++) {
            var outcome = router.publishBatch(key, 0, payloads(), System.currentTimeMillis()).await(PUBLISH_BOUND);

            assertThat(outcome.isSuccess()).as("%s batch %d: %s", stream, batch, outcome).isTrue();
        }
    }

    private static List<byte[]> payloads() {
        return IntStream.range(0, BATCH).mapToObj(_ -> new byte[PAYLOAD]).toList();
    }

    /// Waits until no node's allocation has moved for [#ALLOCATION_QUIESCE_MS].
    private void awaitAllocationsQuiesced() {
        var deadline = System.currentTimeMillis() + SETTLE_BUDGET_MS;
        var last = allocated();
        var stableSince = System.currentTimeMillis();

        while (System.currentTimeMillis() < deadline) {
            sleepQuietly(500L);

            var now = allocated();

            if (!now.equals(last)) {
                last = now;
                stableSince = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - stableSince >= ALLOCATION_QUIESCE_MS) {
                return;
            }
        }

        throw new AssertionError("the allocations did not settle within " + SETTLE_BUDGET_MS + " ms; last " + last);
    }

    private List<Long> allocated() {
        return cluster.allNodes().stream().map(node -> node.streamPartitionManager().totalAllocatedBytes()).toList();
    }

    /// Per node, the late stream's materialized partitions, once the count has stopped moving for [#QUIESCE_MS].
    private List<Integer> settledCopies() {
        var deadline = System.currentTimeMillis() + SETTLE_BUDGET_MS;
        var last = copies();
        var stableSince = System.currentTimeMillis();

        while (System.currentTimeMillis() < deadline) {
            sleepQuietly(500L);

            var now = copies();

            if (!now.equals(last)) {
                last = now;
                stableSince = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - stableSince >= QUIESCE_MS) {
                return now;
            }
        }

        throw new AssertionError("the late stream's materialized-copy count did not settle within " + SETTLE_BUDGET_MS + " ms; last " + last);
    }

    private List<Integer> copies() {
        return cluster.allNodes().stream().map(EmberStreamFloorHeadroomTest::lateCopiesOn).toList();
    }

    private static int lateCopiesOn(AetherNode node) {
        return (int) IntStream.range(0, LATE_PARTITIONS)
                              .filter(partition -> node.streamPartitionManager().partitionBuffer(engineKey(LATE), partition).isPresent())
                              .count();
    }

    private EmberCluster threeNodes(int basePort) {
        dataDir = StreamTripwireSupport.newDataDir("nt106-headroom");

        var built = StreamTripwireSupport.threeNodes(basePort, "nt106", dataDir, RESHUFFLE_CONCURRENCY);
        var streaming = StreamingConfig.streamingConfig(StreamingConfig.streamingConfig().publishForwardTimeout(),
                                                        StreamingConfig.streamingConfig().readForwardTimeout(),
                                                        StreamingConfig.DEFAULT_MAX_READ_RESPONSE_BYTES,
                                                        StreamingConfig.DEFAULT_READ_LINEARIZATION,
                                                        RESHUFFLE_CONCURRENCY)
                                       .withMemoryBudget(BUDGET, StreamingConfig.DERIVE_SEAL_PENDING_CAP_BYTES);

        built.withStreamingConfig(streaming);

        return built;
    }
}
