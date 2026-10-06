// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionRecoveryValue;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #1730 phase 2, the multi-node FALSE-ALERT half of the divergent-tail repair: an ORDINARY failover at `confirmation_factor` 2
/// (the stream owner is killed, another member is elected, publishing continues through it) must lose no acknowledged record,
/// raise no partition-recovery flag and no divergence or truncation operator event, and must not shrink the committed ISR below
/// the live members. It also prints the numbers the epoch-verification change is judged by: how long after the new owner's record
/// commits the first acknowledged publish succeeds (the stall that the replicas' compare against the new epoch adds), and the
/// committed ISR sampled every 250 ms.
///
/// What this does NOT show, and why: a divergent tail is never constructed here. A CF2 acknowledgement needs every in-sync member,
/// so an owner cannot hold records nobody acknowledged unless its replication is cut; isolating the owner makes it self-fence and
/// exit, and a killed process cannot rejoin under the same identity (#1558, EmberSameIdentityRelaunchTest). The truncation itself is
/// pinned by `ReplicaDivergentTailRepairTest` and `StreamPartitionManagerDivergentTailTest` (real backfill, real WAL).
/// [unverified: no multi-node Ember restart-to-repair of a divergent tail].
@PortBudget
class EmberOrdinaryFailoverNoFalseAlertTest {
    private static final int CLUSTER_SIZE = 5;
    private static final int SLOTS = 2 * CLUSTER_SIZE;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_HTTP_OFFSET = 80;

    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                               EmberTestPorts.POOL_LAST,
                                                                               EmberTestPorts.POOL_STEP,
                                                                               SLOTS,
                                                                               MGMT_OFFSET,
                                                                               APP_HTTP_OFFSET);

    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(180).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final long LEADER_BUDGET_MS = 90_000L;
    private static final long ISR_BUDGET_MS = 60_000L;
    private static final int REFUSAL_RETRY_ATTEMPTS = 20;
    private static final long REFUSAL_RETRY_BUDGET_MS = 20_000L;
    private static final long REFUSAL_RETRY_PAUSE_MS = 500L;
    private static final long FAILOVER_BUDGET_MS = 240_000L;
    private static final long SAMPLE_AFTER_FIRST_ACK_MS = 15_000L;
    private static final int STREAMS = 6;
    private static final String NAMESPACE = "ember";
    private static final String VERSION = "1.0.0";
    private static final List<String> FORBIDDEN_EVENT_MARKERS = List.of("stream-divergent-tail-truncated",
                                                                         "STREAM_PARTITION_FLAGGED",
                                                                         "MARKED_DIVERGED",
                                                                         "stream-catchup-source-not-answering",
                                                                         "stream-consumer-rewound");

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
    @Timeout(900)
    void ownerKilled_atCf2_publishingContinuesThroughTheNewOwner_noLossNoFlagNoFalseEvent_isrKeepsTheLiveMembers() {
        cluster = EmberTestPorts.startedCluster(PORTS, this::fiveNodes, START_BOUND);
        awaitLeader();
        var leaderId = cluster.currentLeader().unwrap();
        var streams = java.util.stream.IntStream.range(0, STREAMS).mapToObj(i -> "failover" + i).toList();

        streams.forEach(name -> assertThat(post(leaderPort(), "/api/v1/streams/" + NAMESPACE + "/" + name + "/" + VERSION, "{\"partitions\":1}"))
                                  .as("create %s", name)
                                  .startsWith("2"));
        // Pick a stream whose owner is NOT the consensus leader, so killing it is a plain owner failover (the leader's loss is
        // EmberLeaderLossIsrShrinkTest's subject).
        var chosen = streams.stream()
                            .map(name -> new Chosen(name, awaitFullIsr(engineKey(name))))
                            .filter(candidate -> !candidate.before().owner().id().equals(leaderId))
                            .findFirst()
                            .orElseThrow(() -> new AssertionError("no stream whose owner is not the leader " + leaderId));
        var key = engineKey(chosen.name());
        var before = chosen.before();
        var ownerId = before.owner().id();
        var acked = new ArrayList<String>();

        for (var i = 0; i < 20; i++) {
            var payload = "before-" + i;

            assertThat(publishRetryingRefusal(ownerId, chosen.name(), payload)).as("publish %s before the kill", payload).startsWith("2");
            acked.add(payload);
        }

        assertThat(cluster.killNode(ownerId).await(STOP_BOUND).fold(Cause::message, _ -> "killed")).isEqualTo("killed");
        var killedAt = System.nanoTime();
        var newOwnerAt = -1L;
        var firstAckAt = -1L;
        var samples = new ArrayList<String>();
        var deadline = System.currentTimeMillis() + FAILOVER_BUDGET_MS;
        var sampleUntil = Long.MAX_VALUE;
        var minimumIsr = Integer.MAX_VALUE;
        var attempt = 0;

        while (System.currentTimeMillis() < deadline && System.currentTimeMillis() < sampleUntil) {
            var record = committedRecord(key, ownerId);

            if (newOwnerAt < 0 && record.filter(value -> !value.owner().id().equals(ownerId)).isPresent()) {
                newOwnerAt = System.nanoTime();
            }

            record.onPresent(value -> samples.add(value.owner().id() + " isr=" + value.isr().stream().map(n -> n.id()).collect(Collectors.joining(",")) + " v" + value.isrVersion()));

            if (firstAckAt < 0) {
                var payload = "after-" + attempt++;
                var response = publishResponse(anyLiveMgmtPort(ownerId), chosen.name(), payload);

                if (!response.startsWith("2")) {
                    System.out.println("FAILOVER-PUBLISH-REFUSED " + payload + " -> " + response.lines().findFirst().orElse(""));
                }

                if (response.startsWith("2")) {
                    firstAckAt = System.nanoTime();
                    acked.add(payload);
                    sampleUntil = System.currentTimeMillis() + SAMPLE_AFTER_FIRST_ACK_MS;
                }
            }

            if (firstAckAt >= 0) {
                var live = record.map(value -> value.isr().stream().filter(member -> !member.id().equals(ownerId)).count()).or(0L);

                minimumIsr = (int) Math.min(minimumIsr, live);
            }

            sleepQuietly(250L);
        }

        assertThat(firstAckAt).as("a publish was acknowledged through the new owner within %d ms of the kill", FAILOVER_BUDGET_MS).isGreaterThan(0L);
        System.out.printf("FAILOVER stream=%s killedOwner=%s ownerChangedAfterMs=%d firstAckAfterKillMs=%d firstAckAfterOwnerChangeMs=%d minLiveIsrAfterFirstAck=%d%n",
                          key,
                          ownerId,
                          newOwnerAt < 0 ? -1L : (newOwnerAt - killedAt) / 1_000_000L,
                          (firstAckAt - killedAt) / 1_000_000L,
                          newOwnerAt < 0 ? -1L : (firstAckAt - newOwnerAt) / 1_000_000L,
                          minimumIsr);
        samples.stream().distinct().forEach(sample -> System.out.println("FAILOVER-ISR " + sample));

        for (var i = 0; i < 10; i++) {
            var payload = "later-" + i;

            assertThat(publishRetryingRefusal(ownerId, chosen.name(), payload)).as("publish %s after the failover", payload).startsWith("2");
            acked.add(payload);
        }

        assertThat(minimumIsr).as("the committed ISR kept every live member after the first acknowledged publish").isGreaterThanOrEqualTo(2);
        assertThat(readBack(chosen.name(), ownerId)).as("every acknowledged record reads back").containsAll(acked);
        assertThat(recoveryFlag(key, ownerId).isEmpty()).as("no partition-recovery flag was raised by an ordinary failover").isTrue();
        assertThat(events(anyLiveMgmtPort(ownerId))).as("no divergence, truncation or flag event for an ordinary failover")
                                                    .doesNotContain(FORBIDDEN_EVENT_MARKERS.toArray(String[]::new));
    }

    private record Chosen(String name, StreamPartitionOwnershipValue before) {}

    private static String engineKey(String name) {
        return NAMESPACE + ":" + name + ":" + VERSION;
    }

    private StreamPartitionOwnershipValue awaitFullIsr(String key) {
        var deadline = System.currentTimeMillis() + ISR_BUDGET_MS;

        while (System.currentTimeMillis() < deadline) {
            var record = committedRecord(key, "").filter(value -> value.isrVersion() > 0 && value.isr().size() == 3);

            if (record.isPresent()) {
                return record.unwrap();
            }

            sleepQuietly(250L);
        }

        throw new AssertionError("no committed ISR of three for " + key + " within " + ISR_BUDGET_MS + "ms");
    }

    /// The record every live node agrees on, or none while they differ.
    private Option<StreamPartitionOwnershipValue> committedRecord(String key, String excluded) {
        var seen = new HashSet<StreamPartitionOwnershipValue>();

        cluster.allNodes()
               .stream()
               .filter(node -> !node.self().id().equals(excluded))
               .forEach(node -> node.kvStore()
                                    .getTyped(AetherKey.StreamPartitionOwnershipKey.streamPartitionOwnershipKey(key, 0), StreamPartitionOwnershipValue.class)
                                    .onPresent(seen::add));

        return seen.size() == 1
               ? Option.some(seen.iterator().next())
               : Option.none();
    }

    private Option<StreamPartitionRecoveryValue> recoveryFlag(String key, String excluded) {
        for (var node : cluster.allNodes()) {
            if (node.self().id().equals(excluded)) {
                continue;
            }

            var flag = node.kvStore().getTyped(AetherKey.StreamPartitionRecoveryKey.streamPartitionRecoveryKey(key, 0), StreamPartitionRecoveryValue.class);

            if (flag.isPresent()) {
                return flag;
            }
        }

        return Option.none();
    }

    /// The payloads the live nodes' serving reads return for the stream (the owner serves; every live node is asked).
    private List<String> readBack(String name, String excluded) {
        var key = engineKey(name);

        return cluster.allNodes()
                      .stream()
                      .filter(node -> !node.self().id().equals(excluded))
                      .flatMap(node -> node.streamPartitionManager().readServing(key, 0, 0L, 1000).fold(_ -> List.<org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent> of(), events -> events).stream())
                      .map(event -> new String(event.data(), StandardCharsets.UTF_8))
                      .map(text -> text.contains("\"data\":\"") ? text.substring(text.indexOf("\"data\":\"") + 8, text.lastIndexOf('"')) : text)
                      .distinct()
                      .toList();
    }

    /// A management port of a node that was in the cluster from the start. The leader reconciler provisions a replacement for the
    /// killed node about 15 s after the first ack, and while that node starts it answers `500 Stream config not yet visible on
    /// this node` (observed: 1 of 20 at cd7520a606, `later-0`); the test is about the original members' failover, so it
    /// addresses only them.
    private int anyLiveMgmtPort(String excluded) {
        return cluster.status()
                      .nodes()
                      .stream()
                      .filter(node -> !node.id().equals(excluded))
                      .filter(node -> node.id().startsWith("failover-"))
                      .findFirst()
                      .map(EmberCluster.NodeStatus::mgmtPort)
                      .orElseThrow();
    }

    /// The one refusal a publish documents as safe to repeat: `503 ... refused before writing, retry` (#1944: a stream config this
    /// node has not applied yet, an owner mid-promotion, a node that is not the committed owner). Nothing reached the log, so
    /// the retry cannot duplicate a record. Any other answer -- a 2xx, a 500, a timeout (`-1`), any other 503 -- is returned
    /// as is, so a real failure still fails the test. Bounded: at most [#REFUSAL_RETRY_ATTEMPTS] attempts and
    /// [#REFUSAL_RETRY_BUDGET_MS] ms in total, [#REFUSAL_RETRY_PAUSE_MS] ms apart; the port is chosen again each attempt.
    private String publishRetryingRefusal(String excluded, String name, String payload) {
        var deadline = System.currentTimeMillis() + REFUSAL_RETRY_BUDGET_MS;
        var response = publishResponse(anyLiveMgmtPort(excluded), name, payload);

        for (var attempt = 1; attempt < REFUSAL_RETRY_ATTEMPTS && isRetryableRefusal(response) && System.currentTimeMillis() < deadline; attempt++) {
            sleepQuietly(REFUSAL_RETRY_PAUSE_MS);
            response = publishResponse(anyLiveMgmtPort(excluded), name, payload);
        }

        return response;
    }

    private static boolean isRetryableRefusal(String response) {
        return response.startsWith("503") && response.contains("refused before writing, retry");
    }

    private boolean publish(int mgmtPort, String name, String payload) {
        return publishResponse(mgmtPort, name, payload).startsWith("2");
    }

    /// `status body`: an assertion on it names the refusal when a publish fails, instead of a bare false.
    private String publishResponse(int mgmtPort, String name, String payload) {
        return post(mgmtPort, "/api/v1/streams/" + NAMESPACE + "/" + name + "/" + VERSION + "/publish", "{\"data\":\"" + payload + "\"}");
    }

    private EmberCluster fiveNodes(int basePort) {
        var built = emberCluster(CLUSTER_SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, "failover");

        built.withDataBaseDir(dataDir);

        return built;
    }

    private int leaderPort() {
        return cluster.getLeaderManagementPort().or(-1);
    }

    private void awaitLeader() {
        var deadline = System.currentTimeMillis() + LEADER_BUDGET_MS;

        while (System.currentTimeMillis() < deadline && cluster.getLeaderManagementPort().isEmpty()) {
            sleepQuietly(500L);
        }

        assertThat(cluster.getLeaderManagementPort().isPresent()).as("a leader is elected").isTrue();
    }

    @SuppressWarnings("JBCT-EX-01")
    private static String events(int mgmtPort) {
        var request = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + mgmtPort + "/api/events")).timeout(REQUEST_TIMEOUT).GET().build();

        try (var client = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
        } catch (Exception e) {
            return "";
        }
    }

    /// `status body`, so a caller can assert the status class with `startsWith`.
    @SuppressWarnings("JBCT-EX-01")
    private static String post(int mgmtPort, String path, String json) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://127.0.0.1:" + mgmtPort + path))
                                 .timeout(REQUEST_TIMEOUT)
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(json))
                                 .build();

        try (var client = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            return response.statusCode() + " " + response.body();
        } catch (Exception e) {
            return "-1 " + e;
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
