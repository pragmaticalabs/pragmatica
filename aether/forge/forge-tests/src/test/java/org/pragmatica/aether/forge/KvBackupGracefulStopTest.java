// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;

import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

/// Know 3b01e624f1 (ruling 1): a GRACEFUL whole-cluster stop flushes the KV backup before it completes, so a stream created inside the backup's
/// debounce window (500 ms quiet) is in the restored state with the SAME incarnation. Without the flush the restore predates the stream's config,
/// the restart mints another incarnation, and the stream's acknowledged records stay under the old one (the cause of the
/// `StreamCrashDurabilityTest` red). `AetherNodeBackupCoordinatorStopWiringTest` pins the node's wiring as source text only; this is the
/// behavioural pin, and it is red when `AetherNode.stop()` does not call `KvBackupService.stopFlushed`.
///
/// The inputs are fixed, the outcome is not free of timing: the backup is settled first (control), the stream is created, and the graceful stop is
/// issued within a few milliseconds of its commit (control: under 300 ms, inside the quiet window), where a stop without the flush loses it every time.
/// All five nodes stop at once, so peers dropping the quorum demote the leader while its flush is being scheduled. #2506: the flush used to read
/// leadership on the backup worker and was skipped silently when the demotion won (red 1 of 2 nightly runs; 3 of 3 with an 80 ms worker delay).
/// It now decides leadership when the stop is requested, so the flush runs whenever the leader was asked to stop as leader. The residual case, a
/// leader demoted BEFORE its own stop call, is reported by the node (`BACKUP_STOP_FLUSH_FAILED`) but not closed (#2506 item 3), so this test can
/// still go red in that window; the red is the tripwire, and `KvBackupServiceTest.GracefulStop` pins the ordering deterministically.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
class KvBackupGracefulStopTest {
    private static final int BASE_PORT = 14500;
    private static final int BASE_MGMT_PORT = 14600;
    private static final int BASE_APP_HTTP_PORT = 14700;
    private static final int NODES = 5;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final Pattern NODE_COUNT_FIELD = Pattern.compile("\"nodeCount\"\\s*:\\s*(\\d+)");
    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";
    private static final String STREAM_ADDRESS = "probe:quiet:1.0.0";

    private final HttpOperations http = jdkHttpOperations();
    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            LifecycleAwait.bestEffort("cluster stop in tearDown()", cluster, cluster.stop());
            cluster = null;
        }
    }

    @Test
    void gracefulStopInsideTheQuietWindow_streamConfigSurvivesTheRestore(@TempDir Path dir) {
        startCluster(dir);
        // control 1: the backup settles, so the only unflushed change is the one made below
        LockSupport.parkNanos(Duration.ofSeconds(12).toNanos());

        var key = StreamConfigKey.streamConfigKey(STREAM_ADDRESS);

        assertThat(committedIncarnation(key)).as("control: the stream does not exist yet").isEqualTo(-1L);

        var response = httpPost(cluster.getLeaderManagementPort().or(anyMgmtPort()), "/api/v1/streams/probe/quiet/1.0.0", "{\"partitions\":1}");

        assertThat(response).as("create response").doesNotContain("\"error\"");

        var t0 = System.nanoTime();
        var committed = committedIncarnation(key);

        while (committed < 0 && System.nanoTime() - t0 < Duration.ofSeconds(5).toNanos()) {
            LockSupport.parkNanos(Duration.ofMillis(5).toNanos());
            committed = committedIncarnation(key);
        }

        assertThat(committed).as("the stream config was committed").isGreaterThan(0L);

        var insideMs = Duration.ofNanos(System.nanoTime() - t0).toMillis();

        LifecycleAwait.settled("cluster stop", cluster, cluster.stop());
        assertThat(insideMs).as("control: the stop was issued inside the 500 ms quiet window").isLessThan(300L);

        startAndAwaitReady();

        assertThat(committedIncarnation(key)).as("the stream config committed inside the quiet window is in the restored state, same life")
                                             .isEqualTo(committed);
    }

    private void startCluster(Path dir) {
        var configProvider = ConfigurationProvider.builder().withSystemProperties("aether.").withEnvironment("AETHER_").build();
        var remote = dir.resolve("kv-backup-remote.git");

        cluster = emberCluster(NODES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "scd", Option.some(configProvider));
        cluster.withDataBaseDir(dir);
        runGit(dir, "init", "--quiet", "--bare", remote.toString());
        cluster.withKvBackup(dir.resolve("kv-backup-nodes"), remote.toString(), org.pragmatica.aether.config.BackupConfig.RestoreMode.AUTO);
        startAndAwaitReady();
    }

    private void startAndAwaitReady() {
        LifecycleAwait.settled("cluster start", cluster, cluster.start());
        await().atMost(WAIT_TIMEOUT).pollInterval(POLL_INTERVAL).until(() -> cluster.currentLeader().isPresent());
        await().atMost(WAIT_TIMEOUT).pollInterval(POLL_INTERVAL).until(this::allNodesHealthy);
        await().atMost(WAIT_TIMEOUT).pollInterval(POLL_INTERVAL).until(this::allNodesAreMembers);
    }

    /// The smallest incarnation of the stream's committed config over the nodes, or -1 when none holds it.
    private long committedIncarnation(StreamConfigKey key) {
        var seen = new java.util.TreeSet<Long>();

        for (var node : cluster.allNodes()) {
            node.kvStore().getTyped(key, StreamConfigValue.class).onPresent(value -> seen.add(value.config().incarnation()));
        }

        return seen.isEmpty() ? -1L : seen.first();
    }

    private int anyMgmtPort() {
        return cluster.status().nodes().getFirst().mgmtPort();
    }

    private boolean allNodesHealthy() {
        return cluster.status().nodes().stream().allMatch(node -> healthOk(node.mgmtPort()));
    }

    private boolean healthOk(int port) {
        return get(port).map(r -> r.statusCode() == 200 && r.body().contains("\"quorum\":true")).or(false);
    }

    private boolean allNodesAreMembers() {
        return get(cluster.getLeaderManagementPort().or(anyMgmtPort())).map(r -> r.statusCode() == 200 && hasFullMembership(r.body())).or(false);
    }

    private static boolean hasFullMembership(String body) {
        var matcher = NODE_COUNT_FIELD.matcher(body);

        return body.contains("\"quorum\":true") && matcher.find() && Integer.parseInt(matcher.group(1)) >= NODES;
    }

    private org.pragmatica.lang.Result<HttpResult<String>> get(int port) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/v1/health"))
                                 .GET()
                                 .timeout(Duration.ofSeconds(5))
                                 .build();

        return http.sendString(request).await();
    }

    private String httpPost(int port, String path, String body) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(Duration.ofSeconds(15))
                                 .build();

        return http.sendString(request).await().map(HttpResult::body).or(ERROR_FALLBACK);
    }

    private static void runGit(Path cwd, String... args) {
        var command = new ArrayList<String>();

        command.add("git");
        command.addAll(List.of(args));
        try {
            var process = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).start();

            process.getInputStream().readAllBytes();
            if (process.waitFor() != 0) {
                throw new IllegalStateException("git " + String.join(" ", args) + " failed");
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
