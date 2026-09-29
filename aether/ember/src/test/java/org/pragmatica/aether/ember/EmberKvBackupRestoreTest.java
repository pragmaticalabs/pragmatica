// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.pragmatica.aether.api.ClusterEvent;
import org.pragmatica.aether.config.BackupConfig.RestoreMode;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.NodeCodecs;
import org.pragmatica.aether.node.backup.RestoreGate;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterIncarnationKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DhtPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.BackupRestoreOutcome;
import org.pragmatica.aether.slice.kvstore.AetherValue.BackupRestoreValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterIncarnationValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConfigValue;
import org.pragmatica.aether.slice.kvstore.BackupEntryCodec;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.serialization.FrameworkCodecs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #1533 — the restore gate and the restore decision on a real three-node in-JVM cluster: real Rabia, real
/// leader election, real git.
///
/// - **The gate is central.** While the restore cannot read its backup (a remote that does not exist yet),
///   a cluster-state write submitted DIRECTLY to any node's consensus entry — a producer that knows nothing
///   of the restore — is refused with `RestorePending`. Once the remote appears the decision commits and
///   the same write lands. Removing the guard from the engine's submit path, or its installation in
///   `AetherNode`, turns the first assertion red.
/// - **§6.4.** Consensus runs in memory, so a node that starts late with an OLD backup of another lineage in
///   its directory installs nothing of it: it ends holding the running cluster's lineage.
class EmberKvBackupRestoreTest {
    static final String INCARNATION_ID = "01K4ZT9Q6W3X8Y2B7C5D1INST0";
    private static final int CLUSTER_SIZE = 3;
    private static final int SLOTS = 2 * CLUSTER_SIZE;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_HTTP_OFFSET = 80;
    private static final int FIRST_CANDIDATE_BASE = 33700;
    private static final int LAST_CANDIDATE_BASE = 35500;
    private static final int CANDIDATE_STEP = 200;
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan APPLY_BOUND = TimeSpan.timeSpan(15).seconds();
    private static final long DECISION_BUDGET_MS = 150_000L;
    private static final String PREFIX = "kvr";
    private static final String OLD_LINEAGE = "01K0000000000000000000OLD1";

    @TempDir
    Path temp;

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            assertThat(cluster.stop()
                              .await(STOP_BOUND)
                              .fold(Cause::message, _ -> "stopped")).isEqualTo("stopped");
        }
    }

    @Test
    @Timeout(420)
    void aBlockedRestore_refusesClusterStateWritesOnEveryNode_untilTheDecisionCommits() {
        var remote = temp.resolve("remote.git");

        startCluster(remote, Set.of());

        for (var node : cluster.allNodes()) {
            assertThat(applyProbe(node)).as("a cluster-state write before the restore decision, submitted to %s",
                                            node.self())
                                        .isInstanceOf(RestoreGate.RestorePending.class);
        }
        assertThat(cluster.allNodes()).allSatisfy(node -> assertThat(RestoreGate.decision(node.kvStore())).isEqualTo(Option.none()));
        // Long enough for several restore passes to meet the missing remote: the gate must STAY closed —
        // an unreadable backup is never a fresh boot.
        pause(10_000);
        assertThat(cluster.allNodes()).allSatisfy(node -> assertThat(RestoreGate.decision(node.kvStore())).isEqualTo(Option.none()));
        assertThat(applyProbe(cluster.allNodes()
                                     .getFirst())).as("still refused while the backup cannot be read")
                                                  .isInstanceOf(RestoreGate.RestorePending.class);
        // Runtime state is never held hostage by the restore: the DHT core partition is owned while
        // cluster-state writes are still refused (the config seed is submitted apart from it).
        awaitTrue("the DHT core partition is owned while the restore is blocked",
                  () -> cluster.allNodes()
                               .stream()
                               .allMatch(node -> node.kvStore()
                                                     .get(DhtPartitionOwnershipKey.dhtPartitionOwnershipKey("core"))
                                                     .isPresent()));

        git(temp, "init", "--quiet", "--bare", remote.toString());

        awaitTrue("every node holds the FRESH decision", () -> allHold(BackupRestoreOutcome.FRESH));
        for (var node : cluster.allNodes()) {
            assertThat(applyProbe(node)).as("the same write after the decision, on %s", node.self())
                                        .isNull();
        }
        // The seed the gate refused is re-driven by the decision's commit, not left to a later event.
        awaitTrue("the cluster config is seeded after the decision",
                  () -> cluster.allNodes()
                               .stream()
                               .allMatch(node -> node.kvStore()
                                                     .get(ClusterConfigKey.CURRENT)
                                                     .isPresent()));
        awaitTrue("genesis minted after the decision",
                  () -> cluster.allNodes()
                               .stream()
                               .allMatch(node -> node.kvStore()
                                                     .get(ClusterIncarnationKey.clusterIncarnationKey())
                                                     .isPresent()));
        // #1533 × #1617: the blocked restore is also an OperatorWarning cluster event, raised through the
        // node's sink — it reaches the cluster event log, not only the deciding leader's log file.
        awaitTrue("the blocked restore is in the cluster event log as an OperatorWarning",
                  () -> cluster.allNodes()
                               .stream()
                               .anyMatch(node -> node.eventAggregator()
                                                     .events()
                                                     .await(TimeSpan.timeSpan(5).seconds())
                                                     .map(events -> events.stream()
                                                                          .anyMatch(EmberKvBackupRestoreTest::isBlockedRestoreWarning))
                                                     .or(false)));
    }

    private static boolean isBlockedRestoreWarning(ClusterEvent event) {
        return event instanceof ClusterEvent.OperatorWarning warning && "backup-restore-blocked".equals(warning.details()
                                                                                                             .get("code"));
    }

    @Test
    @Timeout(420)
    void aLateNodeWithAnOldBackupOfAnotherLineage_installsNothingOfIt() {
        var lateNode = PREFIX + "-" + CLUSTER_SIZE;
        var remote = temp.resolve("remote.git");

        git(temp, "init", "--quiet", "--bare", remote.toString());
        seedOldLocalBackup(temp.resolve("nodes")
                               .resolve(lateNode)
                               .resolve("kv-backup"));
        startCluster(remote, Set.of(lateNode));
        awaitTrue("the running nodes hold a decision and a lineage",
                  () -> cluster.allNodes()
                               .stream()
                               .allMatch(node -> node.kvStore()
                                                     .get(ClusterIncarnationKey.clusterIncarnationKey())
                                                     .isPresent()));
        var running = lineageOf(cluster.allNodes()
                                       .getFirst());

        assertThat(cluster.startHeldBackNodes()
                          .await(START_BOUND)
                          .fold(Cause::message, _ -> "started")).isEqualTo("started");
        awaitTrue("the late node has synced the running cluster's lineage",
                  () -> cluster.getNode(lateNode)
                               .map(node -> lineageOf(node).equals(running))
                               .or(false));
        var late = cluster.getNode(lateNode)
                          .unwrap();

        assertThat(running).isNotEqualTo(OLD_LINEAGE);
        assertThat(late.kvStore()
                       .get(ConfigKey.forKey("l1-only"))).as("nothing of the old lineage is installed")
                                                         .isEqualTo(Option.none());
        assertThat(RestoreGate.decision(late.kvStore())
                              .map(BackupRestoreValue::outcome)).isEqualTo(Option.some(BackupRestoreOutcome.FRESH));
    }

    // --- helpers ---
    private void startCluster(Path remote, Set<String> heldBack) {
        var basePort = freeBasePort();

        cluster = emberCluster(CLUSTER_SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, PREFIX);
        cluster.withKvBackup(temp.resolve("nodes"), remote.toString(), RestoreMode.AUTO);
        assertThat(cluster.start(heldBack)
                          .await(START_BOUND)
                          .fold(Cause::message, _ -> "started")).isEqualTo("started");
        awaitTrue("a leader is elected", () -> cluster.currentLeader()
                                                      .isPresent());
    }

    /// The failure a direct consensus submission of a cluster-state write meets, or `null` when it lands.
    private static Cause applyProbe(AetherNode node) {
        List<KVCommand<AetherKey>> commands = List.of(new KVCommand.Put<>(ConfigKey.forKey("probe-" + node.self()
                                                                                                          .id()),
                                                                          ConfigValue.configValue("probe", "v")));

        return node.<Object> apply(commands)
                   .await(APPLY_BOUND)
                   .fold(cause -> cause, _ -> null);
    }

    private boolean allHold(BackupRestoreOutcome outcome) {
        return cluster.allNodes()
                      .stream()
                      .allMatch(node -> RestoreGate.decision(node.kvStore())
                                                   .map(BackupRestoreValue::outcome)
                                                   .equals(Option.some(outcome)));
    }

    private static String lineageOf(AetherNode node) {
        return node.kvStore()
                   .getTyped(ClusterIncarnationKey.clusterIncarnationKey(), ClusterIncarnationValue.class)
                   .map(ClusterIncarnationValue::lineageId)
                   .or("");
    }

    /// A local backup repository of ANOTHER, older lineage, as the old path would have left behind.
    private void seedOldLocalBackup(Path repository) {
        var codec = BackupEntryCodec.backupEntryCodec(NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs()));
        var entries = new HashMap<AetherKey, AetherValue>();

        entries.put(ConfigKey.forKey("l1-only"), ConfigValue.configValue("l1-only", "v"));
        entries.put(ClusterIncarnationKey.clusterIncarnationKey(),
                    ClusterIncarnationValue.clusterIncarnationValue(OLD_LINEAGE, 7, INCARNATION_ID));
        try {
            Files.createDirectories(repository);
            git(repository, "init", "--quiet", "--initial-branch=kv-backup");
            Files.writeString(repository.resolve("kv-backup.txt"),
                              codec.encode(900, entries)
                                   .unwrap());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        git(repository, "add", "kv-backup.txt");
        git(repository,
            "-c",
            "user.email=s@x",
            "-c",
            "user.name=seed",
            "commit",
            "--quiet",
            "-m",
            "kv backup lineage=" + OLD_LINEAGE + " incarnation=7 revision=900");
    }

    private static void awaitTrue(String what, BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + DECISION_BUDGET_MS;

        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting: " + what);
            }
            sleepQuietly();
        }
    }

    private static void sleepQuietly() {
        pause(250);
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread()
                  .interrupt();
        }
    }

    private static String git(Path dir, String... args) {
        var command = new ArrayList<>(List.of("git", "-C", dir.toString()));

        command.addAll(List.of(args));
        try {
            var process = new ProcessBuilder(command).redirectErrorStream(true)
                                                     .start();
            var output = new String(process.getInputStream()
                                           .readAllBytes(),
                                    StandardCharsets.UTF_8);

            if (process.waitFor() != 0) {
                throw new AssertionError(String.join(" ", command) + " failed: " + output);
            }

            return output;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread()
                  .interrupt();

            throw new AssertionError(e);
        }
    }

    /// The first candidate base whose whole block binds free right now (same helper as
    /// `EmberBootstrapAdminKeyAuthTest`, on a disjoint candidate range).
    private static int freeBasePort() {
        for (int base = FIRST_CANDIDATE_BASE; base <= LAST_CANDIDATE_BASE; base += CANDIDATE_STEP) {
            if (blockIsFree(base)) {
                return base;
            }
        }
        throw new AssertionError("no free port block between " + FIRST_CANDIDATE_BASE + " and " + LAST_CANDIDATE_BASE);
    }

    private static boolean blockIsFree(int base) {
        for (int slot = 0; slot < SLOTS; slot++) {
            if (!udpFree(base + slot) || !tcpFree(base + slot) || !tcpFree(base + MGMT_OFFSET + slot)
                || !tcpFree(base + APP_HTTP_OFFSET + slot)) {
                return false;
            }
        }
        return true;
    }

    private static boolean tcpFree(int port) {
        try (var socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(loopback(port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean udpFree(int port) {
        try (var socket = new DatagramSocket(null)) {
            socket.setReuseAddress(false);
            socket.bind(loopback(port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static InetSocketAddress loopback(int port) {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    }
}
