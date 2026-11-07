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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.config.BackupConfig.RestoreMode;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.ReadPreference;
import org.pragmatica.aether.stream.OffHeapRingBuffer;
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
/// - Acceptance (PR-2f flip; OWNER ruling S2 (a), know `3cde22c5af`): the first read served after the restart -- one consumer
///   read through the router -- covers the acked tail with the replicas' acks still held off, and the owner's visible head
///   then reports it. PR-2f's acked marker records the owner's visible position on its tick and at a graceful stop; the
///   marker is trusted, and raises visible, only at the owner's first admission, which a read triggers. The tripwire that
///   pinned the bounded stall (visible below the tail for 15 s, then caught up by re-acks) is deleted with this flip.
/// - #2654: life 2's first ownership record carries an incarnation strictly above life 1's, because no ownership epoch is
///   minted before the life's cluster incarnation commits.
/// - Widened (OWNER ruling `ab33af11c6`, mechanism `e10cf838c9`): the acceptance covers a GOVERNOR read on a NON-owner too,
///   which forwards to the owner while its copy is stale: (a) no announcement from the current owner in this life, or (b)
///   its visible position below the announced one. Each arm has its own control and goes red with its condition removed.
@PortBudget
class EmberOwnerRestartAckedTailTripwireTest {
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(180).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(90).seconds();
    private static final int ACKED = 20;
    private static final int LAGGED = 5;
    private static final long ACKED_TAIL = ACKED - 1;
    private static final long ISR_BUDGET_MS = 90_000L;
    private static final long REBUILD_BUDGET_MS = 240_000L;
    private static final long BACKUP_QUIET_MS = 10_000L;
    private static final long FIRST_READ_BOUND_MS = 60_000L;
    private static final TimeSpan READ_BOUND = TimeSpan.timeSpan(15).seconds();
    private static final String STREAM = "b04";
    private static final String ENGINE_KEY = engineKey(STREAM);

    private final AtomicBoolean dropAcks = new AtomicBoolean(false);
    private final AtomicReference<AetherNode> starved = new AtomicReference<>();
    private final AtomicLong droppedAcks = new AtomicLong();
    private final Set<AetherNode> filtered = ConcurrentHashMap.newKeySet();
    private EmberCluster cluster;
    private long lifeOneIncarnation = -1L;
    private long lifeTwoIncarnation = -1L;
    private Path baseDir;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            assertThat(cluster.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped")).isEqualTo("stopped");
        }
        StreamTripwireSupport.deleteTree(baseDir);
    }

    /// The acceptance (OWNER rulings S2 (a) `3cde22c5af` and WIDEN `ab33af11c6`, mechanism `e10cf838c9`): every read served
    /// after the restart covers the acked tail with the replicas' acks still held off, including a GOVERNOR read on a
    /// non-owner. The owner's acked marker is restored at its first owner admission, which is demand-driven; a non-owner never
    /// raises from the marker (`6665558916` S5), so its GOVERNOR read is forwarded to the owner while its copy is stale.
    /// - Arm (a): before any announcement from the owner in this life, the non-owner forwards (red with condition (a) of
    ///   `StreamPartitionManager.behindCommittedOwner` mutated out: its held copy serves `[]`).
    /// - The owner's visible head reaches the acked tail with replica acks still held off.
    /// - Arm (b): acks flow again, the non-owner's inbound replication data is dropped, the owner's ISR drops it, and a pressure
    ///   record plus [#LAGGED] more events are acknowledged by the owner and the third node, so the non-owner holds an
    ///   announcement above its own visible position. Its GOVERNOR read is forwarded (red with condition (b) mutated out: its
    ///   copy serves at most the first [#ACKED]).
    @Test
    @Timeout(1200)
    void everyReadAfterColdRestart_coversTheAckedTailWithoutReplicaAcks() {
        var owner = restartWithAcksHeldOff();
        var nonOwner = cluster.allNodes().stream().filter(node -> !node.self().equals(owner.self())).findFirst().orElseThrow();

        assertThat(ringHead(owner)).as("control: the whole acked tail is in the owner's rebuilt ring").isEqualTo(ACKED_TAIL);
        assertThat(ringHead(nonOwner)).as("control: the non-owner holds the replayed tail in its own ring, so a local read would not fail PARTITION_NOT_LOCAL").isEqualTo(ACKED_TAIL);
        assertThat(committedThrough(nonOwner)).as("control (arm a): the non-owner has no announcement from the owner in this life").isEqualTo(-1L);

        assertThat(firstServedRead(nonOwner)).as("arm (a): the first non-owner GOVERNOR read after the restart covers the acked tail")
                                             .hasSize(ACKED);
        assertThat(firstServedRead(owner)).as("the first owner-served read after the restart covers the acked tail").hasSize(ACKED);
        assertThat(highestVisibleOver(owner, 5_000L)).as("the owner's visible head, with replica acks held off").isEqualTo(ACKED_TAIL);
        assertThat(droppedAcks.get()).as("control: the inbound filter was live and dropped replica acks after the restart, so the tail was not released by an ack")
                                     .isPositive();

        var tail = publishLagging(owner, nonOwner);

        assertThat(visibleHead(nonOwner)).as("control (arm b): the non-owner's visible position is below the announced one").isLessThan(committedThrough(nonOwner));
        assertThat(firstServedRead(nonOwner)).as("arm (b): a non-owner GOVERNOR read while its copy lags the announcement covers the acked tail")
                                             .hasSize((int) tail + 1);
    }

    /// Arm (b) setup: release the replica acks, starve `nonOwner` of replication data for the stream, wait until the owner's
    /// committed ISR excludes it, and acknowledge [#LAGGED] more events. A confirmation needs EVERY member of the committed ISR
    /// other than the owner (`DefaultReplicationManager.isrAckSet`, #1730), so while the starved copy is in the ISR no publish is
    /// acknowledged (each ends `PublishOutcomeUnknown(REPLICATION_TIMEOUT)`); the third node's ack suffices only after the
    /// owner's `IsrMonitor` shrinks the ISR. That shrink needs the owner's head above the starved copy for `isr_lag_max`, so one
    /// pressure record is published first: it is appended to the owner's ring whatever its outcome, and it is acknowledged by
    /// the shrink (the third node holds it). Announcements still reach `nonOwner` outside the ISR: `announceCommitted` sends to
    /// the registered replica set, not the ISR, and the starvation filter does not drop `CommitAdvance`. Returns the new acked
    /// tail once `nonOwner` holds an announcement covering it.
    private long publishLagging(AetherNode owner, AetherNode nonOwner) {
        starved.set(nonOwner);
        dropAcks.set(false);

        var router = StreamTripwireSupport.component(owner, "streamWriteRouter", StreamWriteRouter.class);
        var pressure = StreamTripwireSupport.publishOnceActivated(router, ENGINE_KEY, "pressure".getBytes(StandardCharsets.UTF_8));

        assertThat(ringHead(owner)).as("control (arm b): the pressure record (outcome %s) is in the owner's ring, past the starved copy", pressure)
                                   .isEqualTo(ACKED_TAIL + 1);

        var shrunk = StreamTripwireSupport.awaitIsr(cluster, ENGINE_KEY, 2, ISR_BUDGET_MS);

        assertThat(shrunk.isr()).as("control (arm b): the owner's ISR dropped the starved non-owner").doesNotContain(nonOwner.self());

        var tail = -1L;

        for (var i = 0; i < LAGGED; i++) {
            var offset = StreamTripwireSupport.publishOnceActivated(router, ENGINE_KEY, ("lagged-" + i).getBytes(StandardCharsets.UTF_8));

            assertThat(offset.isSuccess()).as("acknowledged lagging publish %d: %s", i, offset).isTrue();
            tail = offset.unwrap();
        }
        assertThat(tail).as("control (arm b): the lagged publishes follow the pressure record").isEqualTo(ACKED_TAIL + 1 + LAGGED);

        var acked = tail;

        awaitUntil(() -> committedThrough(nonOwner) >= acked, 30_000L, "the owner's announcement of the new tail reaches the starved non-owner outside the ISR");

        return tail;
    }

    private static long committedThrough(AetherNode node) {
        return node.streamPartitionManager().committedThrough(ENGINE_KEY, PARTITION);
    }

    /// The first successful GOVERNOR read of the partition from offset 0 through `node`'s router; a refused read (the
    /// owner's promotion still pending) is retried inside [#FIRST_READ_BOUND_MS].
    private List<OffHeapRingBuffer.RawEvent> firstServedRead(AetherNode node) {
        var deadline = System.currentTimeMillis() + FIRST_READ_BOUND_MS;

        while (System.currentTimeMillis() < deadline) {
            var served = node.streamReadRouter().read(ENGINE_KEY, PARTITION, 0L, 100, ReadPreference.GOVERNOR).await(READ_BOUND);

            if (served.isSuccess()) {
                return served.unwrap();
            }
            sleepQuietly(200L);
        }

        throw new AssertionError("no read was served within " + FIRST_READ_BOUND_MS + " ms after the restart");
    }

    /// #2654: after the whole-cluster restore the new life's ownership record must outrank every epoch life 1 committed, so its
    /// incarnation is strictly above life 1's. The control is the genesis half of the same defect: before the fix life 1's
    /// first record could be minted at incarnation 0, ahead of the registrar's genesis commit.
    @Test
    @Timeout(1200)
    void ownerAfterColdRestart_lifeTwoOwnershipIncarnation_isAboveLifeOnes() {
        restartWithAcksHeldOff();

        assertThat(lifeOneIncarnation).as("control: life 1's ownership record carries the committed genesis incarnation").isPositive();
        assertThat(lifeTwoIncarnation).as("life 2's first ownership record's incarnation, against life 1's").isGreaterThan(lifeOneIncarnation);
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

        lifeOneIncarnation = record.ownerEpoch().incarnation();
        var router = StreamTripwireSupport.component(firstOwner, "streamWriteRouter", StreamWriteRouter.class);

        for (var i = 0; i < ACKED; i++) {
            var offset = StreamTripwireSupport.publishOnceActivated(router, ENGINE_KEY, ("acked-" + i).getBytes(StandardCharsets.UTF_8));

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
            lifeTwoIncarnation = StreamTripwireSupport.committedRecord(cluster, ENGINE_KEY, PARTITION).map(value -> value.ownerEpoch().incarnation()).or(-1L);
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
                    node.setInboundFaultFilter((_, message) -> admitted(node, message));
                }
            }
            sleepQuietly(2L);
        }
    }

    private boolean admitted(AetherNode receiver, Object message) {
        if (receiver == starved.get() && carriesStreamData(message)) {
            return false;
        }
        if (dropAcks.get() && message instanceof ReplicationMessage.ReplicateAck ack && ENGINE_KEY.equals(ack.streamName())) {
            droppedAcks.incrementAndGet();

            return false;
        }

        return true;
    }

    /// Replication data for the stream: what would let a starved copy catch up.
    private static boolean carriesStreamData(Object message) {
        return switch (message) {
            case ReplicationMessage.ReplicateEvents events -> ENGINE_KEY.equals(events.streamName());
            case ReplicationMessage.BatchSync sync -> ENGINE_KEY.equals(sync.streamName());
            case ReplicationMessage.CatchupResponse response -> ENGINE_KEY.equals(response.streamName());
            default -> false;
        };
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
