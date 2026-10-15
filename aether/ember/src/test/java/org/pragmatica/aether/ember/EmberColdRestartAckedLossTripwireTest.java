// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.config.BackupConfig.RestoreMode;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.aether.stream.StreamWriteRouter;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.StreamTripwireSupport.PARTITION;
import static org.pragmatica.aether.ember.StreamTripwireSupport.awaitUntil;
import static org.pragmatica.aether.ember.StreamTripwireSupport.engineKey;
import static org.pragmatica.aether.ember.StreamTripwireSupport.sleepQuietly;

/// Streams overhaul tripwires, step 2 (#2201), cold restart: A-15 and D-10.
///
/// **A-15 - after a whole-cluster cold restart the in-sync set is minted from placement, not from what the disks hold, so a
/// silent node whose disk is AHEAD may hold acknowledged records the new lineage lacks; they leave the live stream when that
/// node returns and is cut back.** This is documented in `OwnerActivation`'s class doc, which also says what is kept: the cut
/// first writes the records to a recovery segment on the returning node's own volume. 1b-edge narrowed the trigger to
/// `[backup]` restored AND the same NodeIds and volumes AND a node that boots late, which is the shape built here.
///
/// **What is reached and what is not.** The late nodes are held back, so they never connect and are not live placement members
/// when the new owner activates: it goes ahead because the probe set excludes them, not because `promotion_escape_after`
/// (120 s) ran out on silent LIVE members, the case the class doc describes. The consequence the finding names is the same - the
/// acknowledged records are not in the stream the new lineage serves, and they stay on the returned node's volume - but the
/// returned node is never re-added as a replica and so is not "cut back" in the repairDivergence sense. The escape-timer path
/// itself (a live member answering nothing) is [unverified: not driven here].
///
/// A 5-node cluster over data directories and the change-triggered KV backup. The stream is declared with replication factor
/// 2 and confirmation factor 2 (the declaration the engine accepts, with a WARN), so exactly two nodes - the owner `O` and
/// one replica `R1` - hold its 20 acknowledged records and the other three hold nothing. The cluster is stopped. On the cold
/// start `O` and `R1` are held back ([EmberCluster#start(Set)]): the three nodes that come up form quorum and hold none of the
/// 20, a new owner activates among them, and 5 new records are acknowledged at offsets 0..4. Then `O` and `R1` start and are given a minute to rejoin.
/// - Tripwire: the live stream (the new owner's served read) holds exactly the 5 new records and none of the 20 acknowledged
///   ones, while the 20 are still on `O`'s volume (control: nothing was destroyed, they are simply no longer in the stream).
/// - `@Disabled` acceptance: the 20 acknowledged records are still in the live stream (they were never lost, or the cut
///   re-injects them).
///
/// **D-10 - a whole-cluster restart WITHOUT `[backup]` recovers none of the acked records.** The restarted cluster has no KV,
/// so a stream of the same name is created again as a new life with a new incarnation and the old WAL is never opened. Pinned
/// at forge level by `StreamCrashDurabilityTest#fullClusterRestart_withoutBackup_recoversNothing_andLeavesTheOldWalUnread`
/// (5 nodes, a slice); this is the same behaviour at the node-wired Ember level, with the `@Disabled` acceptance beside it.
@PortBudget
class EmberColdRestartAckedLossTripwireTest {
    private static final int FIVE = 5;
    private static final EmberTestPorts.Block PORTS_FIVE = new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                                    EmberTestPorts.POOL_LAST,
                                                                                    EmberTestPorts.POOL_STEP,
                                                                                    2 * FIVE,
                                                                                    StreamTripwireSupport.MGMT_OFFSET,
                                                                                    StreamTripwireSupport.APP_HTTP_OFFSET);
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(240).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan PUBLISH_BOUND = TimeSpan.timeSpan(20).seconds();
    private static final int ACKED = 20;
    private static final int POST = 5;
    private static final long ISR_BUDGET_MS = 90_000L;
    private static final long ISR_SHRINK_BUDGET_MS = 150_000L;
    private static final long NEW_OWNER_BUDGET_MS = 420_000L;
    /// Time given to the returned nodes to rejoin and for any cut-back or backfill to finish before the live stream is read.
    private static final long SETTLE_MS = 60_000L;
    private static final long BACKUP_QUIET_MS = 10_000L;
    private static final String A15 = "a15";
    private static final String D10 = "d10";

    private EmberCluster cluster;
    private Path baseDir;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            assertThat(cluster.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped")).isEqualTo("stopped");
        }
        StreamTripwireSupport.deleteTree(baseDir);
    }

    private record Outcome(List<String> ownerServes, List<String> returnedNodeHolds, boolean acknowledgedRecordsStillOnReturnedNodesDisk) {}

    @Test
    @Timeout(2400)
    void ackedRecordsOnALateReturningNode_tripwire_leaveTheLiveStreamWhenItIsCutBack() {
        var outcome = runLateReturn();

        assertThat(outcome.acknowledgedRecordsStillOnReturnedNodesDisk()).as("control: the acknowledged records are still on the returned old owner's volume (nothing was destroyed)").isTrue();
        assertThat(outcome.ownerServes()).as("A-15 fixed? The acknowledged records are still in the live stream after the late node returned (or the new lineage adopted them). "
                                             + "Delete this tripwire and enable ackedRecordsOnALateReturningNode_stayInTheLiveStream (#2201). "
                                             + "Returned node holds: %s; acknowledged records still on the returned node's volume: %s",
                                             outcome.returnedNodeHolds(),
                                             outcome.acknowledgedRecordsStillOnReturnedNodesDisk())
                                         .isEqualTo(postPayloads());
    }

    @Test
    @Disabled("A-15 (#2201): enable when acknowledged records on a late-returning node are not lost from the live stream after a cold restart (persisted ISR, or no escape on a cold boot, or re-injection); RED today: the live stream holds only the new records")
    @Timeout(2400)
    void ackedRecordsOnALateReturningNode_stayInTheLiveStream() {
        var outcome = runLateReturn();

        assertThat(outcome.ownerServes()).as("the live stream after the late node returned").containsAll(ackedPayloads());
    }

    @Test
    @Timeout(1200)
    void wholeClusterRestartWithoutBackup_tripwire_recoversNoAckedRecord() {
        var recovered = runRestartWithoutBackup();

        assertThat(recovered).as("D-10 fixed? The re-created stream holds the acknowledged records of the old life (%d of %d). Delete this tripwire and enable "
                                 + "wholeClusterRestartWithoutBackup_recoversTheAckedRecords (#2201)",
                                 recovered.size(),
                                 ACKED)
                             .isEmpty();
    }

    @Test
    @Disabled("D-10 (#2201, #1569 storage-identity adoption): enable when a whole-cluster restart without [backup] adopts the old WAL; RED today: the old life's records are never read")
    @Timeout(1200)
    void wholeClusterRestartWithoutBackup_recoversTheAckedRecords() {
        assertThat(runRestartWithoutBackup()).as("records of the re-created stream").containsAll(ackedPayloads());
    }

    private Outcome runLateReturn() {
        baseDir = StreamTripwireSupport.newDataDir("a15-tripwire");

        var remote = baseDir.resolve("kv-backup-remote.git");

        git(baseDir, "init", "--quiet", "--bare", remote.toString());
        cluster = EmberTestPorts.startedCluster(PORTS_FIVE, basePort -> fiveNodes(basePort, remote, true), START_BOUND);
        StreamTripwireSupport.awaitLeader(cluster);
        StreamTripwireSupport.createStream(cluster.allNodes().getFirst(),
                                           StreamTripwireSupport.smallRingStream(A15, 1, 100_000L, 3_600_000L).withReplication(new ReplicationFactors(2, 2)));

        var key = engineKey(A15);
        var record = StreamTripwireSupport.awaitIsr(cluster, key, 2, ISR_BUDGET_MS);
        var ownerId = record.owner().id();
        var returningReplicaId = record.isr().stream().map(member -> member.id()).filter(id -> !id.equals(ownerId)).findFirst().orElseThrow();
        var owner = cluster.getNode(ownerId).unwrap();
        var router = StreamTripwireSupport.component(owner, "streamWriteRouter", StreamWriteRouter.class);

        for (var i = 0; i < ACKED; i++) {
            var offset = router.publish(key, PARTITION, ackedPayloadBytes(i), System.currentTimeMillis()).await(PUBLISH_BOUND);

            assertThat(offset.isSuccess()).as("acknowledged publish %d: %s", i, offset).isTrue();
        }
        awaitUntil(() -> payloadsHeld(cluster.getNode(returningReplicaId).unwrap(), key).containsAll(ackedPayloads()),
                   30_000L,
                   "control: the other copy " + returningReplicaId + " serves the acknowledged records (a replica's visible position follows the owner's announcement)");
        assertThat(cluster.allNodes().stream().filter(node -> !node.self().id().equals(ownerId) && !node.self().id().equals(returningReplicaId)).toList())
            .as("control: the three nodes that will come up first hold no copy of the partition")
            .allSatisfy(node -> assertThat(payloadsHeld(node, key)).isEmpty());
        awaitBackupQuiet(remote);
        assertThat(cluster.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped")).isEqualTo("stopped");

        // Cold start with the two nodes that hold the acknowledged records held back: the three that come up lack them.
        assertThat(cluster.start(Set.of(ownerId, returningReplicaId)).await(START_BOUND).fold(Cause::message, _ -> "started")).isEqualTo("started");
        StreamTripwireSupport.awaitLeader(cluster);
        awaitUntil(() -> liveOwner(key).isPresent(), NEW_OWNER_BUDGET_MS, "a node among the three that came up activated as owner of the restored partition");

        var newOwner = liveOwner(key).unwrap();
        var newRouter = StreamTripwireSupport.component(newOwner, "streamWriteRouter", StreamWriteRouter.class);

        for (var i = 0; i < POST; i++) {
            var offset = newRouter.publish(key, PARTITION, ("post-" + i).getBytes(StandardCharsets.UTF_8), System.currentTimeMillis()).await(PUBLISH_BOUND);

            assertThat(offset.isSuccess()).as("post-restart publish %d on %s: %s", i, newOwner.self().id(), offset).isTrue();
        }
        assertThat(cluster.startHeldBackNodes().await(START_BOUND).fold(Cause::message, _ -> "started")).isEqualTo("started");

        var returned = cluster.getNode(ownerId).unwrap();

        sleepQuietly(SETTLE_MS);

        return new Outcome(servedBy(newOwner, key), payloadsHeld(returned, key), containsOnDisk(baseDir.resolve(ownerId), "acked-19"));
    }

    private List<String> runRestartWithoutBackup() {
        baseDir = StreamTripwireSupport.newDataDir("d10-tripwire");
        cluster = EmberTestPorts.startedCluster(StreamTripwireSupport.PORTS, basePort -> StreamTripwireSupport.threeNodes(basePort, "d10", baseDir), START_BOUND);
        StreamTripwireSupport.awaitLeader(cluster);

        var key = engineKey(D10);

        StreamTripwireSupport.createStream(cluster.allNodes().getFirst(), StreamTripwireSupport.smallRingStream(D10, 1, 100_000L, 3_600_000L));

        var record = StreamTripwireSupport.awaitFullIsr(cluster, key, ISR_BUDGET_MS);
        var router = StreamTripwireSupport.component(cluster.getNode(record.owner().id()).unwrap(), "streamWriteRouter", StreamWriteRouter.class);

        for (var i = 0; i < ACKED; i++) {
            assertThat(router.publish(key, PARTITION, ackedPayloadBytes(i), System.currentTimeMillis()).await(PUBLISH_BOUND).isSuccess()).as("acknowledged publish %d", i).isTrue();
        }
        assertThat(cluster.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped")).isEqualTo("stopped");
        assertThat(cluster.start().await(START_BOUND).fold(Cause::message, _ -> "started")).isEqualTo("started");
        StreamTripwireSupport.awaitLeader(cluster);
        StreamTripwireSupport.createStream(cluster.allNodes().getFirst(), StreamTripwireSupport.smallRingStream(D10, 1, 100_000L, 3_600_000L));

        var reborn = StreamTripwireSupport.awaitFullIsr(cluster, key, ISR_BUDGET_MS);

        awaitUntil(() -> cluster.getNode(reborn.owner().id()).unwrap().streamPartitionManager().readServing(key, PARTITION, 0L, 1).isSuccess(),
                   60_000L,
                   "the re-created stream's owner serves reads");
        sleepQuietly(15_000L);

        return servedBy(cluster.getNode(reborn.owner().id()).unwrap(), key);
    }

    private org.pragmatica.lang.Option<AetherNode> liveOwner(String key) {
        return StreamTripwireSupport.committedRecord(cluster, key, PARTITION)
                                    .flatMap(value -> cluster.getNode(value.owner().id()))
                                    .filter(node -> node.streamPartitionManager().readServing(key, PARTITION, 0L, 1).isSuccess());
    }

    private static List<String> servedBy(AetherNode node, String key) {
        return node.streamPartitionManager().readServing(key, PARTITION, 0L, 1000).map(EmberColdRestartAckedLossTripwireTest::texts).or(List.of());
    }

    private static List<String> payloadsHeld(AetherNode node, String key) {
        return node.streamPartitionManager().readLocal(key, PARTITION, 0L, 1000).map(EmberColdRestartAckedLossTripwireTest::texts).or(List.of());
    }

    private static List<String> texts(List<RawEvent> events) {
        return events.stream().map(event -> new String(event.data(), StandardCharsets.UTF_8)).toList();
    }

    private static List<String> ackedPayloads() {
        return IntStream.range(0, ACKED).mapToObj(i -> "acked-" + i).toList();
    }

    private static byte[] ackedPayloadBytes(int i) {
        return ("acked-" + i).getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> postPayloads() {
        return IntStream.range(0, POST).mapToObj(i -> "post-" + i).toList();
    }

    /// Whether any file under `directory` contains `needle`: the recovery segment the cut writes is a file on the volume.
    @SuppressWarnings("JBCT-EX-01")
    private static boolean containsOnDisk(Path directory, String needle) {
        try (var files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile).anyMatch(file -> fileContains(file, needle.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("JBCT-EX-01")
    private static boolean fileContains(Path file, byte[] needle) {
        try {
            var bytes = Files.readAllBytes(file);

            return indexOf(bytes, needle) >= 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        for (var i = 0; i <= haystack.length - needle.length; i++) {
            var j = 0;

            while (j < needle.length && haystack[i + j] == needle[j]) {
                j++;
            }
            if (j == needle.length) {
                return i;
            }
        }

        return -1;
    }

    private EmberCluster fiveNodes(int basePort, Path remote, boolean withBackup) {
        var built = EmberCluster.emberCluster(FIVE,
                                              basePort,
                                              basePort + StreamTripwireSupport.MGMT_OFFSET,
                                              basePort + StreamTripwireSupport.APP_HTTP_OFFSET,
                                              "a15");

        built.withDataBaseDir(baseDir);
        if (withBackup) {
            built.withKvBackup(baseDir.resolve("kv-backup-nodes"), remote.toString(), RestoreMode.AUTO);
        }

        return built;
    }

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
