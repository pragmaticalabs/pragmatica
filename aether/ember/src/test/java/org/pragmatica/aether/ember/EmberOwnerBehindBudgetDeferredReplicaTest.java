// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.config.StreamingConfig;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.StreamTripwireSupport.PARTITION;
import static org.pragmatica.aether.ember.StreamTripwireSupport.engineKey;
import static org.pragmatica.aether.ember.StreamTripwireSupport.sleepQuietly;

/// NT-149 (#2923, the multi-node test for #1759): an owner that is BEHIND catches up from a replica whose partition is
/// budget-deferred (held as a WAL, no ring), and activates at that replica's tail.
///
/// Three real nodes, one partition at the default replication (owner + two replicas). One replica's node is started with an
/// off-heap budget below one partition's floor ([#TINY_BUDGET], the per-node harness seam
/// [EmberCluster#withStreamingConfig(String, StreamingConfig)]), so its replica is deferred: it holds the partition's WAL and
/// never builds a ring. The OTHER replica, `behind`, stops receiving replication, so the owner shrinks it out of the in-sync
/// set and acknowledges records with the deferred replica alone. Then the owner is killed: the deferred replica is the only
/// node holding the acknowledged tail, `behind` becomes the owner while behind it, and can reach the tail only by pulling it
/// from the deferred replica's WAL.
///
/// Asserts: the new owner holds and serves every acknowledged record; the deferred replica still has no ring and its
/// allocated bytes did not move. Red when the owner's catch-up cannot use a deferred replica as its source (the owner then
/// never reaches the tail).
@PortBudget
class EmberOwnerBehindBudgetDeferredReplicaTest {
    private static final String PREFIX = "nt149";
    private static final String DEFERRED_NODE = PREFIX + "-3";
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

        var stream = createStreamNotOwnedByTheDeferredNode();
        var key = engineKey(stream);
        var record = StreamTripwireSupport.awaitFullIsr(cluster, key, ISR_BUDGET_MS);
        var owner = cluster.getNode(record.owner().id()).unwrap();
        var deferred = cluster.getNode(DEFERRED_NODE).unwrap();
        var behind = cluster.allNodes().stream().filter(node -> node != owner && node != deferred).findFirst().orElseThrow();

        for (var i = 0; i < EARLY; i++) {
            assertThat(publish(owner, key, "early-" + i).join()).as("early-%d acknowledged with the whole in-sync set", i).isGreaterThanOrEqualTo(0L);
        }
        awaitHolds(deferred, key, EARLY);
        awaitHolds(behind, key, EARLY);

        assertThat(deferred.streamPartitionManager().partitionBuffer(key, PARTITION).isEmpty()).as("precondition: the deferred replica built no ring").isTrue();

        // From here `behind` receives no replication: the owner shrinks it out of the in-sync set and acknowledges with `deferred` alone.
        var replicationLost = new AtomicBoolean(true);

        behind.setInboundFaultFilter((_, message) -> !(replicationLost.get() && message instanceof ReplicationMessage));

        var late = new ArrayList<CompletableFuture<Long>>();

        for (var i = 0; i < LATE; i++) {
            late.add(publish(owner, key, "late-" + i));
            sleepQuietly(100L);
        }
        late.forEach(future -> assertThat(future.join()).as("late publish acknowledged once `behind` left the in-sync set").isGreaterThanOrEqualTo(0L));
        assertThat(texts(behind, key)).as("precondition: `behind` stayed at the early records").containsExactly("early-0", "early-1", "early-2");

        var allocatedBefore = deferred.streamPartitionManager().totalAllocatedBytes();

        assertThat(deferred.streamPartitionManager().partitionBuffer(key, PARTITION).isEmpty()).as("precondition: the deferred replica still has no ring").isTrue();
        awaitWalTail(deferred, key, EARLY + LATE);

        // The owner dies; `behind` is the only candidate that can serve as owner, and it is behind the only holder of the tail.
        var killed = owner.self().id();

        cluster.killNode(killed).await(STOP_BOUND).onFailure(cause -> org.junit.jupiter.api.Assertions.fail("owner kill failed: " + cause.message()));
        replicationLost.set(false);

        var newOwner = awaitNewOwner(key, killed);

        assertThat(newOwner).as("the owner that took over is the one that was behind").isSameAs(behind);
        StreamTripwireSupport.awaitUntil(() -> texts(newOwner, key).size() == EARLY + LATE,
                                         CATCHUP_BUDGET_MS,
                                         "the new owner reached the deferred replica's tail (holds: " + texts(newOwner, key) + ")");
        assertThat(texts(newOwner, key)).containsExactly("early-0", "early-1", "early-2", "late-0", "late-1", "late-2", "late-3", "late-4");
        assertThat(deferred.streamPartitionManager().partitionBuffer(key, PARTITION).isEmpty()).as("the deferred replica stayed unmaterialized").isTrue();
        assertThat(deferred.streamPartitionManager().totalAllocatedBytes()).as("the deferred replica's budget is unchanged").isEqualTo(allocatedBefore);
    }

    /// The deferred node cannot own a partition (an owner needs a ring), so a stream whose owner landed on it is not used.
    private String createStreamNotOwnedByTheDeferredNode() {
        for (var attempt = 0; attempt < 12; attempt++) {
            var stream = "nt149s" + attempt;
            var key = engineKey(stream);

            StreamTripwireSupport.createStream(cluster.allNodes().getFirst(),
                                               StreamConfig.streamConfig(key, 1, RetentionPolicy.retentionPolicy(20_000, RING_BYTES, 3_600_000L), "earliest"));

            var record = StreamTripwireSupport.awaitFullIsr(cluster, key, ISR_BUDGET_MS);

            if (!record.owner().id().equals(DEFERRED_NODE)) {
                return stream;
            }
        }

        throw new AssertionError("every candidate stream was owned by " + DEFERRED_NODE);
    }

    private static CompletableFuture<Long> publish(AetherNode owner, String key, String payload) {
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
                                                          .fold(cause -> {
                                                                    throw new AssertionError("publish " + payload + " failed: " + cause.message());
                                                                },
                                                                offset -> offset));
    }

    private static List<String> texts(AetherNode node, String key) {
        return node.streamPartitionManager()
                   .readLocal(key, PARTITION, 0L, 100)
                   .map(events -> events.stream().map(EmberOwnerBehindBudgetDeferredReplicaTest::text).toList())
                   .or(List.of());
    }

    private static String text(RawEvent event) {
        return new String(event.data(), StandardCharsets.UTF_8);
    }

    private static void awaitHolds(AetherNode node, String key, int count) {
        StreamTripwireSupport.awaitUntil(() -> node.streamPartitionManager().appendedHead(key, PARTITION) + 1 >= count,
                                         30_000L,
                                         node.self().id() + " holds " + count + " records");
    }

    private static void awaitWalTail(AetherNode node, String key, int count) {
        StreamTripwireSupport.awaitUntil(() -> node.streamPartitionManager().appendedHead(key, PARTITION) + 1 >= count,
                                         30_000L,
                                         node.self().id() + " holds " + count + " records in its WAL");
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

        built.withStreamingConfig(DEFERRED_NODE,
                                  StreamingConfig.streamingConfig().withMemoryBudget(TINY_BUDGET, StreamingConfig.DERIVE_SEAL_PENDING_CAP_BYTES));

        return built;
    }
}
