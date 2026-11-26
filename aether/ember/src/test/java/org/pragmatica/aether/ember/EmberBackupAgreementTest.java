// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.pragmatica.aether.config.BackupConfig.RestoreMode;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.backup.RestoreGate;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.BackupRestoreOutcome;
import org.pragmatica.aether.slice.kvstore.AetherValue.BackupRestoreValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConfigValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.testing.PollUntil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #3525 L2 — every core member runs the same `[backup]` setting, on real nodes through the real join guard (L1).
///
/// - **One node off:** a three-node core whose third node has `[backup]` off. The other two form the quorum and keep their restore
///   gate; the third is refused AT START with the guard's `LabelAgreementRefused` (its message names the label), and never joins
///   the running set, so no ungated submitter exists while the restore is undecided.
/// - **All on, all off:** the cluster forms. With all on, the restore still decides.
///
/// Dropping the stamp, stamping a constant, naming no agreeing label, or naming a different label than the stamp turns the first
/// test red: the third node then starts and joins.
@PortBudget
class EmberBackupAgreementTest {
    private static final int CLUSTER_SIZE = 3;
    private static final int SLOTS = 2 * CLUSTER_SIZE;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_HTTP_OFFSET = 80;
    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                                EmberTestPorts.POOL_LAST,
                                                                                EmberTestPorts.POOL_STEP,
                                                                                SLOTS,
                                                                                MGMT_OFFSET,
                                                                                APP_HTTP_OFFSET);
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan APPLY_BOUND = TimeSpan.timeSpan(15).seconds();
    private static final long CONDITION_BUDGET_MS = 150_000L;
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);
    private static final String PREFIX = "bka";
    private static final String ODD_ONE = PREFIX + "-" + CLUSTER_SIZE;

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
    void oneCoreNodeWithBackupOff_isRefusedAtStartByTheGuard_andTheQuorumKeepsItsRestoreGate() {
        var remote = temp.resolve("remote.git");

        startCluster(remote, true, Set.of(ODD_ONE), Set.of(ODD_ONE));

        for (var node : cluster.allNodes()) {
            assertThat(applyProbe(node)).as("a write submitted to %s while the restore is undecided", node.self())
                                        .isInstanceOf(RestoreGate.RestorePending.class);
        }

        var refusal = cluster.startHeldBackNodes()
                             .await(START_BOUND)
                             .fold(Cause::message, _ -> "started");

        assertThat(refusal).as("the node with [backup] off must be refused at start")
                           .contains("Refusing to start")
                           .contains("label 'backup'")
                           .contains("every core member must run the same setting");
        assertThat(cluster.getNode(ODD_ONE)
                          .isPresent()).as("the refused node never joins the running set")
                                       .isFalse();
        assertThat(cluster.allNodes()).as("the quorum keeps running").hasSize(CLUSTER_SIZE - 1);
        for (var node : cluster.allNodes()) {
            assertThat(applyProbe(node)).as("the restore is still undecided and the gate still closed on %s", node.self())
                                        .isInstanceOf(RestoreGate.RestorePending.class);
        }

        git(remote);
        awaitTrue("the quorum decides the restore once the remote exists",
                  () -> cluster.allNodes()
                               .stream()
                               .allMatch(node -> decisionOf(node).equals(Option.some(BackupRestoreOutcome.FRESH))));
    }

    @Test
    @Timeout(420)
    void everyCoreNodeWithBackupOn_formsAndTheRestoreDecides() {
        var remote = temp.resolve("remote.git");

        git(remote);
        startCluster(remote, true, Set.of(), Set.of());

        assertThat(cluster.allNodes()).hasSize(CLUSTER_SIZE);
        awaitTrue("every node holds the FRESH decision",
                  () -> cluster.allNodes()
                               .stream()
                               .allMatch(node -> decisionOf(node).equals(Option.some(BackupRestoreOutcome.FRESH))));
    }

    @Test
    @Timeout(420)
    void everyCoreNodeWithBackupOff_forms() {
        startCluster(temp.resolve("remote.git"), false, Set.of(), Set.of());

        assertThat(cluster.allNodes()).hasSize(CLUSTER_SIZE);
        assertThat(cluster.allNodes()).allSatisfy(node -> assertThat(decisionOf(node)).isEqualTo(Option.none()));
    }

    // --- helpers ---
    private void startCluster(Path remote, boolean backup, Set<String> heldBack, Set<String> backupOff) {
        cluster = EmberTestPorts.startedCluster(PORTS, basePort -> {
                                                    var attempt = emberCluster(CLUSTER_SIZE,
                                                                               basePort,
                                                                               basePort + MGMT_OFFSET,
                                                                               basePort + APP_HTTP_OFFSET,
                                                                               PREFIX);

                                                    if (backup) {
                                                        attempt.withKvBackup(temp.resolve("nodes"),
                                                                             remote.toString(),
                                                                             RestoreMode.AUTO);
                                                        attempt.withoutKvBackupOn(backupOff);
                                                    }
                                                    return attempt;
                                                },
                                                attempt -> attempt.start(heldBack),
                                                EmberCluster::stop,
                                                START_BOUND);
        awaitTrue("a leader is elected",
                  () -> cluster.currentLeader()
                               .isPresent());
    }

    private static Option<BackupRestoreOutcome> decisionOf(AetherNode node) {
        return RestoreGate.decision(node.kvStore())
                          .map(BackupRestoreValue::outcome);
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

    private static void awaitTrue(String what, BooleanSupplier condition) {
        var report = PollUntil.pollUntil(condition::getAsBoolean,
                                         Boolean::booleanValue,
                                         Duration.ofMillis(CONDITION_BUDGET_MS),
                                         POLL_INTERVAL);

        assertThat(report.met()).as(() -> report.describe(what))
                                .isTrue();
    }

    /// Creates the bare remote the restore reads from.
    private static void git(Path remote) {
        try {
            var process = new ProcessBuilder("git", "init", "--quiet", "--bare", remote.toString()).redirectErrorStream(true)
                                                                                                    .start();
            var output = new String(process.getInputStream()
                                           .readAllBytes());

            if (process.waitFor() != 0) {
                throw new AssertionError("git init failed: " + output);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread()
                  .interrupt();

            throw new AssertionError(e);
        }
    }
}
