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
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.stream.StreamPartitionManager.StreamHydration;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #1805 — a burst of partition moves larger than `reshuffle_concurrency` must not strand a partition held but
/// unmaterialized. Three real in-JVM nodes, `reshuffle_concurrency = 1`, and a stream whose eight partitions are all
/// created at once: every node is handed several held partitions with a single slot, so all but one of each node's
/// replica materializations are refused as paced and must be re-driven by the reshuffle tick.
///
/// The pin is the end state on EVERY node: no held partition of the stream is left unmaterialized
/// (`partitionsDeferred == 0`) inside [#MATERIALIZED_BOUND_MS]. It is a guard on the happy burst — the loss path
/// itself (a role flap through NONE while queued) is not inducible from outside, and is pinned deterministically by
/// `PacedMaterializeRetryTest`. [unverified: red at the base commit — the burst alone may materialize there too]
class EmberPacedBurstTest {
    private static final int CLUSTER_SIZE = 3;
    private static final int SLOTS = 2 * CLUSTER_SIZE;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_HTTP_OFFSET = 80;
    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST, EmberTestPorts.POOL_LAST, EmberTestPorts.POOL_STEP, SLOTS, MGMT_OFFSET, APP_HTTP_OFFSET);
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final long LEADER_ELECTION_BUDGET_MS = 60_000L;
    private static final long MATERIALIZED_BOUND_MS = 60_000L;
    private static final int PARTITIONS = 8;
    private static final String ENGINE_KEY = "ember:burst:1.0.0";

    @TempDir
    Path dataDir;

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            assertThat(cluster.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped")).isEqualTo("stopped");
        }
    }

    @Test
    @Timeout(300)
    void burstLargerThanReshuffleConcurrency_leavesNoHeldPartitionUnmaterialized() {
        cluster = EmberTestPorts.startedCluster(PORTS, this::clusterWithOneReshuffleSlot, START_BOUND);
        awaitLeader();

        var created = post(cluster.getLeaderManagementPort().or(-1),
                           "/api/v1/streams/ember/burst/1.0.0",
                           "{\"partitions\":" + PARTITIONS + "}");

        assertThat(created).as("stream create").isBetween(200, 299);
        assertThat(awaitNoDeferredPartitions()).as("every held partition of %s materializes on every node inside %dms; deferred now: %s",
                                                   ENGINE_KEY,
                                                   MATERIALIZED_BOUND_MS,
                                                   deferredByNode())
                                               .isTrue();
    }

    private boolean awaitNoDeferredPartitions() {
        var deadline = System.currentTimeMillis() + MATERIALIZED_BOUND_MS;

        while (System.currentTimeMillis() < deadline) {
            if (streamSeenEverywhere() && deferredByNode().isEmpty()) {
                return true;
            }

            sleepQuietly(250L);
        }

        return false;
    }

    private boolean streamSeenEverywhere() {
        return cluster.allNodes().stream().allMatch(node -> viewOf(node) != null);
    }

    private List<String> deferredByNode() {
        return cluster.allNodes()
                      .stream()
                      .filter(node -> viewOf(node) != null && viewOf(node).partitionsDeferred() > 0)
                      .map(node -> node.self().id() + "=" + viewOf(node).partitionsDeferred())
                      .toList();
    }

    @SuppressWarnings("JBCT-RET-01")
    private static StreamHydration viewOf(AetherNode node) {
        return node.streamPartitionManager()
                   .hydrationSnapshot()
                   .streams()
                   .stream()
                   .filter(view -> ENGINE_KEY.equals(view.name()))
                   .findFirst()
                   .orElse(null);
    }

    private EmberCluster clusterWithOneReshuffleSlot(int basePort) {
        var built = emberCluster(CLUSTER_SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, "pacedburst");

        built.withDataBaseDir(dataDir);
        built.withStreamingConfig(StreamingConfig.streamingConfig(TimeSpan.timeSpan(5).seconds(),
                                                                  TimeSpan.timeSpan(2).seconds(),
                                                                  StreamingConfig.DEFAULT_MAX_READ_RESPONSE_BYTES,
                                                                  StreamingConfig.DEFAULT_READ_LINEARIZATION,
                                                                  1));

        return built;
    }

    private void awaitLeader() {
        var deadline = System.currentTimeMillis() + LEADER_ELECTION_BUDGET_MS;

        while (System.currentTimeMillis() < deadline && cluster.getLeaderManagementPort().isEmpty()) {
            sleepQuietly(500L);
        }

        assertThat(cluster.getLeaderManagementPort().isPresent()).as("a leader is elected").isTrue();
    }

    @SuppressWarnings("JBCT-EX-01")
    private static int post(int mgmtPort, String path, String json) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://127.0.0.1:" + mgmtPort + path))
                                 .timeout(REQUEST_TIMEOUT)
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(json))
                                 .build();

        try (var client = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
        } catch (Exception e) {
            // A transport failure is not a 2xx and is reported as -1 rather than read as a refusal.
            return -1;
        }
    }

    @SuppressWarnings("JBCT-EX-01")
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
