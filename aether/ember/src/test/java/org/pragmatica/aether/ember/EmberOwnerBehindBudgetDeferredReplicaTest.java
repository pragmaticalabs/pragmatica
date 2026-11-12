// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.config.StreamingConfig;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.testlogging.LogCapture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.StreamTripwireSupport.PARTITION;
import static org.pragmatica.aether.ember.StreamTripwireSupport.engineKey;
import static org.pragmatica.aether.ember.StreamTripwireSupport.sleepQuietly;

/// NT-149 (#2923, the multi-node test for #1759): an owner that is BEHIND catches up from a replica whose partition is
/// budget-deferred (held as a WAL, no ring), and activates at that replica's tail.
///
/// Three real nodes, one partition at the default replication (owner + two replicas), the in-sync lag bound raised so no
/// member leaves the set mid-test. `behind` stops receiving replication, so the owner and `deferred` alone hold the late
/// records. `deferred` is then restarted with an off-heap budget below one partition's floor (the per-node harness seam
/// [EmberCluster#withStreamingConfig(String, StreamingConfig)]): its WAL holds the tail and it builds no ring. Then the owner
/// is killed: `behind` takes over behind the only holder of the tail, which is the budget-deferred replica, and can reach
/// that tail only by pulling it from the replica's WAL.
///
/// Asserts: the new owner holds the replica's tail and completes its activation at it; the deferred replica still has no
/// ring and its allocated bytes did not move. Red when the owner's catch-up cannot use a deferred replica as its source.
@PortBudget
class EmberOwnerBehindBudgetDeferredReplicaTest {
    private static final String PREFIX = "nt149";
    private static final String STREAM = "nt149";
    private static final long TINY_BUDGET = 64 * 1024L;
    private static final long RING_BYTES = 64L * 1024 * 1024;
    private static final int EARLY = 3;
    private static final int LATE = 5;
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final long ISR_BUDGET_MS = 90_000L;
    private static final long PUBLISH_WAIT_SECONDS = 150;
    private static final long FAILOVER_BUDGET_MS = 180_000L;
    private static final long CATCHUP_BUDGET_MS = 120_000L;
    private static final long DEFERRAL_SETTLE_MS = 15_000L;
    private static final TimeSpan ADD_BOUND = TimeSpan.timeSpan(120).seconds();

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
    void ownerBehindABudgetDeferredReplica_catchesUpFromReplicaWal_andActivates() {
        cluster = EmberTestPorts.startedCluster(StreamTripwireSupport.PORTS, this::threeNodes, START_BOUND);
        StreamTripwireSupport.awaitLeader(cluster);

        var key = engineKey(STREAM);

        StreamTripwireSupport.createStream(cluster.allNodes().getFirst(),
                                           StreamConfig.streamConfig(key, 1, RetentionPolicy.retentionPolicy(20_000, RING_BYTES, 3_600_000L), "earliest"));

        var record = StreamTripwireSupport.awaitFullIsr(cluster, key, ISR_BUDGET_MS);
        var owner = cluster.getNode(record.owner().id()).unwrap();
        var replicas = cluster.allNodes().stream().filter(node -> node != owner).toList();
        var deferred = replicas.get(0);
        var behind = replicas.get(1);

        for (var i = 0; i < EARLY; i++) {
            assertThat(publish(owner, key, "early-" + i).join().acked()).as("early-%d acknowledged with the whole in-sync set", i).isTrue();
        }
        awaitHolds(deferred, key, EARLY);
        awaitHolds(behind, key, EARLY);

        // From here `behind` receives no replication: the late records are held by the owner and `deferred` only, and stay unacknowledged.
        var replicationLost = new AtomicBoolean(true);

        behind.setInboundFaultFilter((_, message) -> !(replicationLost.get() && message instanceof ReplicationMessage));

        var late = new ArrayList<CompletableFuture<Response>>();

        for (var i = 0; i < LATE; i++) {
            late.add(publish(owner, key, "late-" + i));
            sleepQuietly(100L);
        }
        awaitHolds(deferred, key, EARLY + LATE);
        assertThat(behind.streamPartitionManager().appendedHead(key, PARTITION)).as("precondition: `behind` stayed at the early records").isEqualTo((long) (EARLY - 1));

        // `deferred` restarts with an off-heap budget below one partition's floor: its WAL holds the tail, and it can build no ring.
        var deferredId = deferred.self().id();

        cluster.killNode(deferredId).await(STOP_BOUND).onFailure(cause -> org.junit.jupiter.api.Assertions.fail("replica kill failed: " + cause.message()));
        cluster.withStreamingConfig(deferredId, tinyBudget());
        cluster.addCoreNode(deferredId).await(ADD_BOUND).onFailure(cause -> org.junit.jupiter.api.Assertions.fail("replica did not rejoin: " + cause.message()));

        var restarted = cluster.getNode(deferredId).unwrap();

        StreamTripwireSupport.awaitUntil(() -> restarted.streamPartitionManager().maxTotalBytes() == TINY_BUDGET,
                                         30_000L,
                                         "precondition: the restarted replica runs with the tiny budget");
        sleepQuietly(DEFERRAL_SETTLE_MS);
        assertThat(restarted.streamPartitionManager().partitionBuffer(key, PARTITION).isEmpty()).as("precondition: the restarted replica built no ring").isTrue();

        var allocatedBefore = restarted.streamPartitionManager().totalAllocatedBytes();
        var capture = StreamTripwireSupport.captureLogs();

        try {
            // The owner dies; `behind` is in the in-sync set, behind the only holder of the tail, and a budget-deferred one at that.
            var killed = owner.self().id();

            cluster.killNode(killed).await(STOP_BOUND).onFailure(cause -> org.junit.jupiter.api.Assertions.fail("owner kill failed: " + cause.message()));
            replicationLost.set(false);

            var newOwner = awaitNewOwner(key, killed);

            assertThat(newOwner).as("the owner that took over is the one that was behind").isSameAs(behind);
            StreamTripwireSupport.awaitUntil(() -> newOwner.streamPartitionManager().appendedHead(key, PARTITION) == EARLY + LATE - 1L,
                                             CATCHUP_BUDGET_MS,
                                             "the new owner reached the deferred replica's tail (head " + newOwner.streamPartitionManager().appendedHead(key, PARTITION) + ")");
            StreamTripwireSupport.awaitUntil(() -> activated(capture, key),
                                             CATCHUP_BUDGET_MS,
                                             "the new owner activated at the replica's tail");
            assertThat(restarted.streamPartitionManager().partitionBuffer(key, PARTITION).isEmpty()).as("the deferred replica stayed unmaterialized").isTrue();
            assertThat(restarted.streamPartitionManager().totalAllocatedBytes()).as("the deferred replica's budget is unchanged").isEqualTo(allocatedBefore);
        } finally {
            capture.close();
        }
    }

    private static boolean activated(LogCapture capture, String key) {
        return StreamTripwireSupport.logLines(capture, Level.INFO)
                                    .stream()
                                    .anyMatch(line -> line.contains("Owner activation of " + key + "[0] complete at watermark " + (EARLY + LATE - 1)));
    }

    private static StreamingConfig tinyBudget() {
        return baseStreaming().withMemoryBudget(TINY_BUDGET, StreamingConfig.DERIVE_SEAL_PENDING_CAP_BYTES);
    }

    /// The in-sync-set lag bound is raised so `behind` is not shrunk out of the set while the test builds the scene.
    private static StreamingConfig baseStreaming() {
        return StreamingConfig.streamingConfig().withIsrLagMax(TimeSpan.timeSpan(30).minutes());
    }

    private record Response(boolean acked, String body) {}

    private static CompletableFuture<Response> publish(AetherNode owner, String key, String payload) {
        var manager = owner.streamPartitionManager();
        var confirmations = manager.confirmationFactorFor(key) - 1;

        return CompletableFuture.supplyAsync(() -> manager.publishLocalAtFloor(key,
                                                                               PARTITION,
                                                                               payload.getBytes(StandardCharsets.UTF_8),
                                                                               System.currentTimeMillis(),
                                                                               confirmations)
                                                          .async()
                                                          .flatMap(offset -> manager.awaitReplication(key, PARTITION, offset, confirmations).map(_ -> offset))
                                                          .await(TimeSpan.timeSpan(PUBLISH_WAIT_SECONDS).seconds())
                                                          .fold(cause -> new Response(false, cause.message()), offset -> new Response(true, "offset " + offset)));
    }

    private static void awaitHolds(AetherNode node, String key, int count) {
        StreamTripwireSupport.awaitUntil(() -> node.streamPartitionManager().appendedHead(key, PARTITION) + 1 >= count,
                                         30_000L,
                                         node.self().id() + " holds " + count + " records");
    }

    private AetherNode awaitNewOwner(String key, String killed) {
        var deadline = System.currentTimeMillis() + FAILOVER_BUDGET_MS;

        while (System.currentTimeMillis() < deadline) {
            var found = StreamTripwireSupport.committedRecord(cluster, key, PARTITION)
                                             .filter((StreamPartitionOwnershipValue value) -> !value.owner().id().equals(killed))
                                             .flatMap(value -> cluster.getNode(value.owner().id()));

            if (found.isPresent()) {
                return found.unwrap();
            }

            sleepQuietly(500L);
        }

        throw new AssertionError("ownership did not leave the killed owner " + killed + " within " + FAILOVER_BUDGET_MS + "ms");
    }

    private EmberCluster threeNodes(int basePort) {
        dataDir = StreamTripwireSupport.newDataDir("nt149");

        var built = StreamTripwireSupport.threeNodes(basePort, PREFIX, dataDir);

        built.withStreamingConfig(baseStreaming());

        return built;
    }
}
