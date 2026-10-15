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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.config.BackupConfig.RestoreMode;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.stream.StreamWriteRouter;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.StreamTripwireSupport.PARTITION;
import static org.pragmatica.aether.ember.StreamTripwireSupport.awaitUntil;
import static org.pragmatica.aether.ember.StreamTripwireSupport.engineKey;
import static org.pragmatica.aether.ember.StreamTripwireSupport.sleepQuietly;

/// Streams overhaul tripwire, step 2 (#2201), restart visibility: B-04/B-17 - after the owner's ring is rebuilt, its acked
/// tail is invisible until the replicas acknowledge it again, because acknowledgement rows are not persisted.
///
/// Per 1b-core: the production `ReplicaRegistry` writes through `WatermarkStore.NOOP`, and a ring rebuild bumps the owner
/// epoch, so every ack row from an earlier epoch is discounted (`confirmedByNode` reads it as -1). `restoreVisible` then
/// gives the owner `min(durable, replicatedThrough)`, which is below the acked tail. 1b-core REFUTED "until the next publish
/// is acked": replicas do re-ack a quiet partition, within about one backfill tick. So the finding is a BOUNDED stall, and the
/// test pins exactly that shape rather than an unbounded one.
///
/// **Scenario.** A 3-node cluster with data directories and the change-triggered KV backup (the supported configuration for a
/// whole-cluster restart, #1532/#1533) acknowledges 20 events (confirmation factor 2). The cluster is stopped and started
/// again over the same directories; the WAL replays the 20 events into the owner's rebuilt ring. To hold the replicas' re-acks
/// off deterministically instead of racing them, every node's inbound filter drops `ReplicateAck` for the stream from the
/// instant the node object exists (an installer thread polls for new node instances while `start()` runs; the filter is a
/// transport fault, the ack-persistence gap is not simulated).
///
/// - Tripwire: with acks held off for 15 s the owner HOLDS all 20 events in its ring but its visible head stays below the
///   acked tail; when the filter is lifted the head reaches the tail inside 60 s (the stall is bounded, as 1b-core found).
///   Controls: the filter dropped at least one ack (it was live), and the ring holds the full tail.
/// - `@Disabled` acceptance: the owner's visible head covers the acked tail with the replicas' acks still held off, because
///   the acknowledgement state survives the restart (persisted per epoch, or pulled by the owner on activation).
@PortBudget
class EmberOwnerRestartAckedTailTripwireTest {
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(180).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(90).seconds();
    private static final TimeSpan PUBLISH_BOUND = TimeSpan.timeSpan(30).seconds();
    private static final int ACKED = 20;
    private static final long ACKED_TAIL = ACKED - 1;
    private static final long ISR_BUDGET_MS = 90_000L;
    private static final long REBUILD_BUDGET_MS = 240_000L;
    private static final long HOLD_MS = 15_000L;
    private static final long CATCH_UP_BOUND_MS = 60_000L;
    private static final long BACKUP_QUIET_MS = 10_000L;
    private static final String STREAM = "b04";
    private static final String ENGINE_KEY = engineKey(STREAM);

    private final AtomicBoolean dropAcks = new AtomicBoolean(false);
    private final AtomicLong droppedAcks = new AtomicLong();
    private final Set<AetherNode> filtered = ConcurrentHashMap.newKeySet();
    private EmberCluster cluster;
    private Path baseDir;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            assertThat(cluster.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped")).isEqualTo("stopped");
        }
        StreamTripwireSupport.deleteTree(baseDir);
    }

    @Test
    @Timeout(1200)
    void ownerAfterColdRestart_tripwire_holdsItsAckedTailInvisibleUntilTheReplicasReAck() {
        var owner = restartWithAcksHeldOff();

        assertThat(droppedAcks.get()).as("control: the inbound filter was live and dropped replica acks after the restart").isPositive();
        assertThat(ringHead(owner)).as("control: the WAL replay put the whole acked tail in the owner's rebuilt ring").isEqualTo(ACKED_TAIL);

        var highestVisible = highestVisibleOver(owner, HOLD_MS);

        assertThat(highestVisible).as("B-04/B-17 fixed? The rebuilt owner's visible head covers the acked tail (%d) with replica acks held off. "
                                      + "Delete this tripwire and enable ownerAfterColdRestart_seesItsAckedTailWithoutReplicaAcks (#2201)",
                                      ACKED_TAIL)
                                  .isLessThan(ACKED_TAIL);

        dropAcks.set(false);
        awaitUntil(() -> visibleHead(owner) >= ACKED_TAIL, CATCH_UP_BOUND_MS, "once the replicas' acks flow again the owner's visible head reaches the acked tail (bounded stall)");
    }

    @Test
    @Disabled("B-04/B-17 (#2201): enable when ack state survives an owner ring rebuild; RED today: the rebuilt owner's visible head stays below the acked tail while replica acks are held off")
    @Timeout(1200)
    void ownerAfterColdRestart_seesItsAckedTailWithoutReplicaAcks() {
        var owner = restartWithAcksHeldOff();

        assertThat(ringHead(owner)).as("control: the whole acked tail is in the owner's ring").isEqualTo(ACKED_TAIL);
        assertThat(highestVisibleOver(owner, 5_000L)).as("the owner's visible head, with replica acks held off, after its ring was rebuilt").isEqualTo(ACKED_TAIL);
    }

    /// Life 1: acknowledge [#ACKED] events. Then a whole-cluster stop and start over the same directories with replica acks
    /// dropped from the moment each node object exists. Returns the node that owns the partition in life 2, once its ring holds
    /// the replayed tail.
    private AetherNode restartWithAcksHeldOff() {
        baseDir = StreamTripwireSupport.newDataDir("b04-tripwire");

        var remote = baseDir.resolve("kv-backup-remote.git");

        git(baseDir, "init", "--quiet", "--bare", remote.toString());
        cluster = EmberTestPorts.startedCluster(StreamTripwireSupport.PORTS, basePort -> backedUpCluster(basePort, remote), START_BOUND);
        StreamTripwireSupport.awaitLeader(cluster);
        StreamTripwireSupport.createStream(cluster.allNodes().getFirst(), StreamTripwireSupport.smallRingStream(STREAM, 1, 100_000L, 3_600_000L));

        var record = StreamTripwireSupport.awaitFullIsr(cluster, ENGINE_KEY, ISR_BUDGET_MS);
        var firstOwner = cluster.getNode(record.owner().id()).unwrap();
        var router = StreamTripwireSupport.component(firstOwner, "streamWriteRouter", StreamWriteRouter.class);

        for (var i = 0; i < ACKED; i++) {
            var offset = router.publish(ENGINE_KEY, PARTITION, ("acked-" + i).getBytes(StandardCharsets.UTF_8), System.currentTimeMillis()).await(PUBLISH_BOUND);

            assertThat(offset.isSuccess()).as("acknowledged publish %d: %s", i, offset).isTrue();
        }
        assertThat(visibleHead(firstOwner)).as("control: in life 1 the owner sees its whole acked tail").isEqualTo(ACKED_TAIL);
        awaitBackupQuiet(remote);

        LifecycleSettled.stop(cluster, STOP_BOUND);
        dropAcks.set(true);

        var installer = Thread.ofPlatform().name("b04-filter-installer").start(this::installFilters);

        try {
            LifecycleSettled.start(cluster, START_BOUND);
            awaitUntil(() -> restoredOwner().isPresent(), REBUILD_BUDGET_MS, "a node owns the restored partition with the replayed tail in its ring");
        } finally {
            installerDone.set(true);
            StreamTripwireSupport.joinQuietly(installer);
        }

        return restoredOwner().unwrap();
    }

    private final AtomicBoolean installerDone = new AtomicBoolean(false);

    private void installFilters() {
        while (!installerDone.get()) {
            for (var node : cluster.allNodes()) {
                if (filtered.add(node)) {
                    node.setInboundFaultFilter((_, message) -> admitted(message));
                }
            }
            sleepQuietly(2L);
        }
    }

    private boolean admitted(Object message) {
        if (dropAcks.get() && message instanceof ReplicationMessage.ReplicateAck ack && ENGINE_KEY.equals(ack.streamName())) {
            droppedAcks.incrementAndGet();

            return false;
        }

        return true;
    }

    private Option<AetherNode> restoredOwner() {
        return StreamTripwireSupport.committedRecord(cluster, ENGINE_KEY, PARTITION)
                                    .flatMap(value -> cluster.getNode(value.owner().id()))
                                    .filter(node -> ringHead(node) == ACKED_TAIL);
    }

    private static long ringHead(AetherNode node) {
        return node.streamPartitionManager().partitionBuffer(ENGINE_KEY, PARTITION).map(ring -> ring.headOffset()).or(-1L);
    }

    private static long visibleHead(AetherNode node) {
        return node.streamPartitionManager().visibleBounds(ENGINE_KEY, PARTITION).map(bounds -> bounds.visibleHead()).or(-2L);
    }

    /// The highest visible head the node reported at any sample over `windowMs`.
    private static long highestVisibleOver(AetherNode node, long windowMs) {
        var deadline = System.currentTimeMillis() + windowMs;
        var highest = visibleHead(node);

        while (System.currentTimeMillis() < deadline) {
            highest = Math.max(highest, visibleHead(node));
            sleepQuietly(100L);
        }

        return highest;
    }

    private EmberCluster backedUpCluster(int basePort, Path remote) {
        var built = StreamTripwireSupport.threeNodes(basePort, "b04", baseDir);

        built.withKvBackup(baseDir.resolve("kv-backup-nodes"), remote.toString(), RestoreMode.AUTO);

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

    /// Stop and start with the bounds the tests in this module use, failing with the cause.
    private static final class LifecycleSettled {
        static void stop(EmberCluster cluster, TimeSpan bound) {
            assertThat(cluster.stop().await(bound).fold(Cause::message, _ -> "stopped")).isEqualTo("stopped");
        }

        static void start(EmberCluster cluster, TimeSpan bound) {
            assertThat(cluster.start().await(bound).fold(Cause::message, _ -> "started")).isEqualTo("started");
        }
    }
}
