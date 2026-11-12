// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.config.BackupConfig.RestoreMode;
import org.pragmatica.aether.config.StreamingConfig;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.forward.StreamForwardMessage;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.testlogging.LogCapture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.StreamTripwireSupport.PARTITION;
import static org.pragmatica.aether.ember.StreamTripwireSupport.awaitUntil;
import static org.pragmatica.aether.ember.StreamTripwireSupport.engineKey;
import static org.pragmatica.aether.ember.StreamTripwireSupport.sleepQuietly;

/// NT-149 (#2923, the multi-node test for #1759): an owner that is BEHIND catches up from a replica whose partition is
/// budget-deferred (held as a WAL, no ring), and activates at that replica's tail.
///
/// Three real nodes, one partition at the default replication (owner + two replicas), the in-sync lag bound raised so no
/// member leaves the set. Life 1: `behind` is starved of the stream's replication and of its catch-up reads, so the late
/// records are held by the owner and `deferred` only. Then the whole cluster stops and starts again over the same
/// directories (a stopped node's identity is retired and cannot return alone), with `deferred` configured below one
/// partition's floor (the per-node harness seam [EmberCluster#withStreamingConfig(String, StreamingConfig)]): its WAL holds
/// the tail and it builds no ring. In life 2 the owner is killed and `behind` is released: it takes over behind the only
/// holder of the tail, which is the budget-deferred replica, and can reach that tail only by pulling it from the replica's WAL.
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
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(180).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final long ISR_BUDGET_MS = 90_000L;
    private static final long PUBLISH_WAIT_SECONDS = 20;
    private static final long FAILOVER_BUDGET_MS = 180_000L;
    private static final long CATCHUP_BUDGET_MS = 120_000L;
    private static final long REBUILD_BUDGET_MS = 240_000L;
    private static final long BACKUP_QUIET_MS = 10_000L;
    private static final long DEFERRAL_SETTLE_MS = 15_000L;
    private static final String KEY = engineKey(STREAM);

    private final AtomicReference<NodeId> starved = new AtomicReference<>();
    private final AtomicBoolean installerDone = new AtomicBoolean(false);
    private final Set<AetherNode> filtered = ConcurrentHashMap.newKeySet();
    private EmberCluster cluster;
    private Path baseDir;

    @AfterEach
    void tearDown() {
        installerDone.set(true);
        if (cluster != null) {
            assertThat(cluster.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped")).isEqualTo("stopped");
        }
        StreamTripwireSupport.deleteTree(baseDir);
    }

    @Test
    @Timeout(1200)
    void ownerBehindABudgetDeferredReplica_catchesUpFromReplicaWal_andActivates() {
        baseDir = StreamTripwireSupport.newDataDir("nt149");

        var remote = baseDir.resolve("kv-backup-remote.git");

        git(baseDir, "init", "--quiet", "--bare", remote.toString());
        cluster = EmberTestPorts.startedCluster(StreamTripwireSupport.PORTS, basePort -> backedUpCluster(basePort, remote), START_BOUND);
        StreamTripwireSupport.awaitLeader(cluster);

        var installer = Thread.ofPlatform().name("nt149-filter-installer").start(this::installFilters);

        try {
            StreamTripwireSupport.createStream(cluster.allNodes().getFirst(),
                                               StreamConfig.streamConfig(KEY, 1, RetentionPolicy.retentionPolicy(20_000, RING_BYTES, 3_600_000L), "earliest"));

            var record = StreamTripwireSupport.awaitFullIsr(cluster, KEY, ISR_BUDGET_MS);
            var owner = cluster.getNode(record.owner().id()).unwrap();
            var replicas = cluster.allNodes().stream().filter(node -> node != owner).toList();
            var deferredId = replicas.get(0).self();
            var behindId = replicas.get(1).self();

            for (var i = 0; i < EARLY; i++) {
                assertThat(publish(owner, "early-" + i).join()).as("early-%d acknowledged with the whole in-sync set", i).isTrue();
            }
            awaitHeld(replicas.get(0), EARLY);
            awaitHeld(replicas.get(1), EARLY);

            // Life 1: `behind` stops receiving the stream, so the late records stay unacknowledged and are held by the owner and `deferred`.
            starved.set(behindId);
            for (var i = 0; i < LATE; i++) {
                publish(owner, "late-" + i);
                sleepQuietly(100L);
            }
            awaitHeld(replicas.get(0), EARLY + LATE);
            assertThat(replicas.get(1).streamPartitionManager().appendedHead(KEY, PARTITION)).as("precondition: `behind` stayed at the early records")
                                                                                        .isEqualTo((long) (EARLY - 1));
            awaitBackupQuiet(remote);

            // Life 2: the same directories, `deferred` below one partition's floor.
            assertThat(cluster.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped")).isEqualTo("stopped");
            cluster.withStreamingConfig(deferredId.id(), baseStreaming().withMemoryBudget(TINY_BUDGET, StreamingConfig.DERIVE_SEAL_PENDING_CAP_BYTES));
            assertThat(cluster.start().await(START_BOUND).fold(Cause::message, _ -> "started")).isEqualTo("started");
            awaitUntil(() -> restoredOwner(deferredId, behindId).isPresent(),
                       REBUILD_BUDGET_MS,
                       "a node other than the deferred replica and `behind` owns the restored partition with the tail in its ring");

            var restoredOwner = restoredOwner(deferredId, behindId).orElseThrow();
            var deferred = cluster.getNode(deferredId.id()).unwrap();
            var behind = cluster.getNode(behindId.id()).unwrap();

            assertThat(deferred.streamPartitionManager().maxTotalBytes()).as("precondition: the restarted replica runs with the tiny budget").isEqualTo(TINY_BUDGET);
            sleepQuietly(DEFERRAL_SETTLE_MS);
            assertThat(deferred.streamPartitionManager().partitionBuffer(KEY, PARTITION).isEmpty()).as("precondition: the deferred replica built no ring").isTrue();

            var allocatedBefore = deferred.streamPartitionManager().totalAllocatedBytes();
            var capture = StreamTripwireSupport.captureLogs();

            try {
                // The owner dies and `behind` is released: it is behind the only holder of the tail, a budget-deferred replica.
                var killed = restoredOwner.self().id();

                cluster.killNode(killed).await(STOP_BOUND).onFailure(cause -> org.junit.jupiter.api.Assertions.fail("owner kill failed: " + cause.message()));
                starved.set(null);
                assertThat(awaitNewOwner(killed)).as("the owner that took over is the one that was behind").isSameAs(behind);
                awaitUntil(() -> behind.streamPartitionManager().appendedHead(KEY, PARTITION) == EARLY + LATE - 1L,
                           CATCHUP_BUDGET_MS,
                           "the new owner reached the deferred replica's tail (head " + behind.streamPartitionManager().appendedHead(KEY, PARTITION) + ")");
                awaitUntil(() -> activated(capture), CATCHUP_BUDGET_MS, "the new owner activated at the replica's tail");
                assertThat(deferred.streamPartitionManager().partitionBuffer(KEY, PARTITION).isEmpty()).as("the deferred replica stayed unmaterialized").isTrue();
                assertThat(deferred.streamPartitionManager().totalAllocatedBytes()).as("the deferred replica's budget is unchanged").isEqualTo(allocatedBefore);
            } finally {
                capture.close();
            }
        } finally {
            installerDone.set(true);
            StreamTripwireSupport.joinQuietly(installer);
        }
    }

    private java.util.Optional<AetherNode> restoredOwner(NodeId deferredId, NodeId behindId) {
        return StreamTripwireSupport.committedRecord(cluster, KEY, PARTITION)
                                    .flatMap(value -> cluster.getNode(value.owner().id()))
                                    .filter(node -> !node.self().equals(deferredId) && !node.self().equals(behindId))
                                    .filter(node -> node.streamPartitionManager().appendedHead(KEY, PARTITION) == EARLY + LATE - 1L)
                                    .toOptional();
    }

    private static boolean activated(LogCapture capture) {
        return StreamTripwireSupport.logLines(capture, Level.INFO)
                                    .stream()
                                    .anyMatch(line -> line.contains("Owner activation of " + KEY + "[0] complete at watermark " + (EARLY + LATE - 1)));
    }

    /// The in-sync lag bound is raised so no member is shrunk out of the set while the test builds the scene.
    private static StreamingConfig baseStreaming() {
        return StreamingConfig.streamingConfig().withIsrLagMax(TimeSpan.timeSpan(30).minutes());
    }

    private void installFilters() {
        while (!installerDone.get()) {
            for (var node : cluster.allNodes()) {
                if (filtered.add(node)) {
                    node.setInboundFaultFilter((_, message) -> admitted(node, message));
                }
            }
            sleepQuietly(2L);
        }
    }

    private boolean admitted(AetherNode receiver, Object message) {
        var starvedNode = starved.get();

        return starvedNode == null || !((receiver.self().equals(starvedNode) && carriesStreamData(message)) || isCatchupReadBy(starvedNode, message));
    }

    /// Replication data for the stream: what would let the starved copy catch up.
    private static boolean carriesStreamData(Object message) {
        return switch (message) {
            case ReplicationMessage.ReplicateEvents events -> KEY.equals(events.streamName());
            case ReplicationMessage.BatchSync sync -> KEY.equals(sync.streamName());
            case ReplicationMessage.CatchupResponse response -> KEY.equals(response.streamName());
            default -> false;
        };
    }

    /// The starved copy's backfill catch-up read for the stream, dropped where it arrives (the forward path repairs a lagging copy
    /// within one round otherwise). A consumer read (`catchup` false) still passes.
    private static boolean isCatchupReadBy(NodeId starvedNode, Object message) {
        return message instanceof StreamForwardMessage.ReadForward read && read.catchup() && KEY.equals(read.streamName()) && read.sender().equals(starvedNode);
    }

    private static CompletableFuture<Boolean> publish(AetherNode owner, String payload) {
        var manager = owner.streamPartitionManager();
        var confirmations = manager.confirmationFactorFor(KEY) - 1;

        return CompletableFuture.supplyAsync(() -> manager.publishLocalAtFloor(KEY,
                                                                               PARTITION,
                                                                               payload.getBytes(StandardCharsets.UTF_8),
                                                                               System.currentTimeMillis(),
                                                                               confirmations)
                                                          .async()
                                                          .flatMap(offset -> manager.awaitReplication(KEY, PARTITION, offset, confirmations).map(_ -> offset))
                                                          .await(TimeSpan.timeSpan(PUBLISH_WAIT_SECONDS).seconds())
                                                          .isSuccess());
    }

    private static void awaitHeld(AetherNode node, int count) {
        awaitUntil(() -> node.streamPartitionManager().appendedHead(KEY, PARTITION) + 1 >= count, 30_000L, node.self().id() + " holds " + count + " records");
    }

    private AetherNode awaitNewOwner(String killed) {
        var deadline = System.currentTimeMillis() + FAILOVER_BUDGET_MS;

        while (System.currentTimeMillis() < deadline) {
            var found = StreamTripwireSupport.committedRecord(cluster, KEY, PARTITION)
                                             .filter(value -> !value.owner().id().equals(killed))
                                             .flatMap(value -> cluster.getNode(value.owner().id()));

            if (found.isPresent()) {
                return found.unwrap();
            }

            sleepQuietly(500L);
        }

        throw new AssertionError("ownership did not leave the killed owner " + killed + " within " + FAILOVER_BUDGET_MS + "ms");
    }

    private EmberCluster backedUpCluster(int basePort, Path remote) {
        var built = StreamTripwireSupport.threeNodes(basePort, PREFIX, baseDir);

        built.withKvBackup(baseDir.resolve("kv-backup-nodes"), remote.toString(), RestoreMode.AUTO);
        built.withStreamingConfig(baseStreaming());

        return built;
    }

    /// The backup is change-triggered and pushed asynchronously: wait until the shared remote's commit count has been still
    /// for [#BACKUP_QUIET_MS], so the stream's config is in it before the cluster is stopped.
    private static void awaitBackupQuiet(Path remote) {
        var last = commitCount(remote);
        var stableSince = System.currentTimeMillis();

        while (System.currentTimeMillis() - stableSince < BACKUP_QUIET_MS) {
            sleepQuietly(500L);

            var now = commitCount(remote);

            if (now != last) {
                last = now;
                stableSince = System.currentTimeMillis();
            }
        }
        assertThat(last).as("the KV backup pushed at least one commit before the restart").isPositive();
    }

    @SuppressWarnings("JBCT-EX-01")
    private static int commitCount(Path remote) {
        try {
            var process = new ProcessBuilder("git", "-C", remote.toString(), "rev-list", "--all", "--count").redirectErrorStream(true).start();
            var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();

            process.waitFor();

            return output.matches("\\d+") ? Integer.parseInt(output) : 0;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return 0;
        }
    }

    @SuppressWarnings("JBCT-EX-01")
    private static void git(Path cwd, String... args) {
        var command = new ArrayList<String>();

        command.add("git");
        command.addAll(List.of(args));
        try {
            var process = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).start();

            process.getInputStream().readAllBytes();
            assertThat(process.waitFor()).as("git %s", String.join(" ", args)).isZero();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
