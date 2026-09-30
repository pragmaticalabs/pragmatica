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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.StreamingConfig;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// F1a (cloud run 1, `Concurrent_deploy`) — the END-TO-END pin. A stream created while every node's only
/// `reshuffle_concurrency` slot is taken has replicas that HOLD its partition but cannot materialize it, and
/// those replicas used to answer the owner's watermark probe `PARTITION_NOT_LOCAL`. The owner read that as
/// "unreachable" and refused appends (`OwnerNotActivated`) for the whole 20 s source wait. With the typed
/// "held, not materialized" reply the owner activates on the first attempt.
///
/// What it drives: a real three-node in-JVM cluster with `reshuffle_concurrency = 1`; an `occupier` stream with
/// three partitions is created first, and a second stream immediately after it. The pin is the OWNER's promotion
/// gate, read where the append path reads it (`readServing` on the committed owner): it must open within
/// [#ACTIVATION_BOUND_MS], where the defect costs the 20 s wait.
///
/// What it does NOT pin, on purpose: that a publish to that stream is acknowledged. The stream's confirmation
/// factor is 2, and its replicas stay paced until the occupier's slots are released by the reshuffle tick, so
/// the confirmation barrier of the first publishes times out (`REPLICATION_TIMEOUT`, observed 2026-09-30 on
/// cloudbb-d1, run kept in the f-backfill report). That is the second mechanism of the same cloud finding
/// (B=500) and is not closed by the probe fix.
///
/// [unverified: the slot is still taken at the second stream's create] It is decided by the reshuffle tick, not
/// by the test. The run log shows `held[0] ... paced: node already has 1 partitions in materialize+backfill`
/// on both replicas, and the mutation that reverts the probe is what shows the setup reaches the defect.
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
    /// The defect costs the 20 s source wait (`sourceWaitBound`); an owner that opens inside this bound did not wait for it.
    private static final long ACTIVATION_BOUND_MS = 8_000L;
    private static final long OWNERSHIP_BOUND_MS = 30_000L;
    private static final String NAMESPACE = "ember";
    private static final String VERSION = "1.0.0";
    private static final String HELD_ENGINE_KEY = NAMESPACE + ":held:" + VERSION;

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
    void ownerPromotion_ofAStreamWhoseReplicasArePacedByReshuffleConcurrency_doesNotWaitForTheSourceWait() {
        cluster = EmberTestPorts.startedCluster(PORTS, this::clusterWithOneReshuffleSlot, START_BOUND);
        awaitLeader();

        var occupier = post(leaderPort(), "/api/v1/streams/" + NAMESPACE + "/occupier/" + VERSION, "{\"partitions\":3}");
        var held = post(leaderPort(), "/api/v1/streams/" + NAMESPACE + "/held/" + VERSION, "{\"partitions\":1}");

        assertThat(occupier.status()).as("occupier create: %s", occupier.body()).isBetween(200, 299);
        assertThat(held.status()).as("held create: %s", held.body()).isBetween(200, 299);

        var owner = awaitCommittedOwner(HELD_ENGINE_KEY);
        var openedAfterMs = awaitOwnerGate(owner);

        assertThat(openedAfterMs).as("the owner %s of %s must open its promotion gate inside %dms, not after the 20s source wait",
                                     owner.self().id(),
                                     HELD_ENGINE_KEY,
                                     ACTIVATION_BOUND_MS)
                                 .isLessThan(ACTIVATION_BOUND_MS);
    }

    /// The node the committed ownership record names for partition 0 — the one whose append path the gate guards.
    private AetherNode awaitCommittedOwner(String engineKey) {
        var deadline = System.currentTimeMillis() + OWNERSHIP_BOUND_MS;

        while (System.currentTimeMillis() < deadline) {
            var owner = committedOwner(engineKey);

            if (owner.isPresent()) {
                return owner.unwrap();
            }

            sleepQuietly();
        }

        throw new AssertionError("no committed owner for " + engineKey + " within " + OWNERSHIP_BOUND_MS + "ms");
    }

    private Option<AetherNode> committedOwner(String engineKey) {
        for (var node : cluster.allNodes()) {
            var record = ownerRecord(node, engineKey);

            if (record.isPresent()) {
                return cluster.getNode(record.unwrap().owner().id());
            }
        }

        return Option.none();
    }

    private static Option<StreamPartitionOwnershipValue> ownerRecord(AetherNode node, String engineKey) {
        return node.kvStore()
                   .getTyped(AetherKey.StreamPartitionOwnershipKey.streamPartitionOwnershipKey(engineKey, 0),
                             StreamPartitionOwnershipValue.class);
    }

    /// Milliseconds until the owner's serving read passes the promotion gate (`readServing` refuses with
    /// `OwnerNotActivated` until the owner has caught up), or [#ACTIVATION_BOUND_MS] doubled when it never does.
    private static long awaitOwnerGate(AetherNode owner) {
        var started = System.nanoTime();
        var deadline = System.currentTimeMillis() + 2 * ACTIVATION_BOUND_MS;

        while (System.currentTimeMillis() < deadline) {
            if (owner.streamPartitionManager().readServing(HELD_ENGINE_KEY, 0, 0L, 1).isSuccess()) {
                return elapsedMs(started);
            }

            sleepQuietly(50L);
        }

        return 2 * ACTIVATION_BOUND_MS;
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
        sleepQuietly(500L);
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
