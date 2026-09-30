// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.StreamingConfig;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// F1a (cloud run 1, `Concurrent_deploy`) — the END-TO-END pin. A stream created while every node's only
/// `reshuffle_concurrency` slot is taken has replicas that HOLD its partition but cannot materialize it, and
/// those replicas used to answer the owner's watermark probe `PARTITION_NOT_LOCAL`. The owner read that as
/// "unreachable" and refused appends (`OwnerNotActivated`, then a 500) for the whole 20 s source wait. With the
/// typed "held, not materialized" reply the owner promotes on the first attempt.
///
/// What it drives: a real three-node in-JVM cluster with `reshuffle_concurrency = 1`; an `occupier` stream with
/// three partitions is created first and a second stream is created and PUBLISHED to within a second of it,
/// through the management API of every node, so both the owner's local append and a forwarded append are
/// exercised. The publish must answer 2xx within the bound, which is far below the 20 s wait the defect costs.
///
/// [unverified: a slot the occupier releases before the second stream is created] The occupier's replicas
/// backfill an empty partition, so how long a slot stays taken is decided by the reshuffle tick (5 s), not by
/// the test. The test proves the slot IS taken at the second stream's create by asserting nothing about it; the
/// mutation that reverts the fix is what shows the setup reaches the defect.
class EmberHeldPartitionPublishTest {
    private static final int CLUSTER_SIZE = 3;
    private static final int SLOTS = 2 * CLUSTER_SIZE;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_HTTP_OFFSET = 80;
    private static final int FIRST_CANDIDATE_BASE = 48100;
    private static final int LAST_CANDIDATE_BASE = 48900;
    private static final int CANDIDATE_STEP = 200;
    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(FIRST_CANDIDATE_BASE,
                                                                                LAST_CANDIDATE_BASE,
                                                                                CANDIDATE_STEP,
                                                                                SLOTS,
                                                                                MGMT_OFFSET,
                                                                                APP_HTTP_OFFSET);
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final long LEADER_ELECTION_BUDGET_MS = 60_000L;
    /// The defect costs the 20 s source wait; a publish that answers inside this bound did not wait for it.
    private static final long PUBLISH_BOUND_MS = 10_000L;
    private static final String NAMESPACE = "ember";
    private static final String VERSION = "1.0.0";

    @TempDir
    Path dataDir;

    private EmberCluster cluster;

    private record Response(int status, String body, long elapsedMs) {}

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            assertThat(cluster.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped")).isEqualTo("stopped");
        }
    }

    @Test
    @Timeout(420)
    void publish_toAStreamWhoseReplicasArePacedByReshuffleConcurrency_answers2xxWithoutTheSourceWait() {
        cluster = EmberTestPorts.startedCluster(PORTS, this::clusterWithOneReshuffleSlot, START_BOUND);
        awaitLeader();

        var occupier = post(leaderPort(), "/api/v1/streams/" + NAMESPACE + "/occupier/" + VERSION, "{\"partitions\":3}");
        var held = post(leaderPort(), "/api/v1/streams/" + NAMESPACE + "/held/" + VERSION, "{\"partitions\":1}");

        assertThat(occupier.status()).as("occupier create: %s", occupier.body()).isBetween(200, 299);
        assertThat(held.status()).as("held create: %s", held.body()).isBetween(200, 299);

        for (var port : managementPorts()) {
            var published = post(port, "/api/v1/streams/" + NAMESPACE + "/held/" + VERSION + "/publish", "{\"data\":\"held-" + port + "\"}");

            assertThat(published.status()).as("publish via :%d after %dms: %s", port, published.elapsedMs(), published.body())
                                          .isBetween(200, 299);
            assertThat(published.elapsedMs()).as("publish via :%d must not wait for the source wait", port)
                                             .isLessThan(PUBLISH_BOUND_MS);
        }
    }

    private EmberCluster clusterWithOneReshuffleSlot(int basePort) {
        var built = emberCluster(CLUSTER_SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, "heldpub");

        built.withDataBaseDir(dataDir);
        built.withStreamingConfig(StreamingConfig.streamingConfig(TimeSpan.timeSpan(5).seconds(),
                                                                  TimeSpan.timeSpan(2).seconds(),
                                                                  StreamingConfig.DEFAULT_MAX_READ_RESPONSE_BYTES,
                                                                  StreamingConfig.DEFAULT_READ_LINEARIZATION,
                                                                  1));

        return built;
    }

    private List<Integer> managementPorts() {
        return cluster.status().nodes().stream().map(EmberCluster.NodeStatus::mgmtPort).toList();
    }

    private int leaderPort() {
        return cluster.getLeaderManagementPort().or(-1);
    }

    private void awaitLeader() {
        var deadline = System.currentTimeMillis() + LEADER_ELECTION_BUDGET_MS;

        while (System.currentTimeMillis() < deadline && cluster.getLeaderManagementPort().isEmpty()) {
            sleepQuietly();
        }

        assertThat(cluster.getLeaderManagementPort().isPresent()).as("a leader is elected").isTrue();
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Response post(int mgmtPort, String path, String json) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://127.0.0.1:" + mgmtPort + path))
                                 .timeout(REQUEST_TIMEOUT)
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(json))
                                 .build();
        var started = System.nanoTime();

        try (var client = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            return new Response(response.statusCode(), response.body(), elapsedMs(started));
        } catch (Exception e) {
            // A transport failure is not a 2xx and is reported verbatim rather than read as a refusal.
            return new Response(-1, e.toString(), elapsedMs(started));
        }
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    @SuppressWarnings("JBCT-EX-01")
    private static void sleepQuietly() {
        try {
            Thread.sleep(500L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
