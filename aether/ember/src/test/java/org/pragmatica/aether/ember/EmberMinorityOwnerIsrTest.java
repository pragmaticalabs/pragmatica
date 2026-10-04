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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #1730, multi-node: a partitioned minority owner acknowledges nothing while an in-sync replica is on the majority side.
///
/// Five real nodes, one stream partition at the default replication (RF 3, CF 2), so the committed ISR is the owner
/// plus two replicas. The cut puts the owner and ONE ISR replica (`near`) on one side and everything else — the other
/// ISR replica (`far`) included — on the other, symmetric on QUIC and SWIM (the test-only [AetherNode#partitionFrom]
/// seam). Publishes go to the owner straight after the cut, inside the window before any failure detector on either
/// side reacts: before #1730 an ack needed any CF − 1 = 1 peer, `near` gave it, and the minority acknowledged writes
/// the majority would never hold. Now an ack needs every ISR member, `far` cannot answer, and the owner cannot commit
/// an ISR without it, so every publish ends unacknowledged.
///
/// The control runs in the same cluster before the cut: the same publish to the same owner is acknowledged, so a
/// refusal after the cut is the cut's doing, not a broken publish path.
class EmberMinorityOwnerIsrTest {
    private static final int CLUSTER_SIZE = 5;
    private static final int SLOTS = 2 * CLUSTER_SIZE;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_HTTP_OFFSET = 80;
    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(SLOTS,
                                                                                MGMT_OFFSET,
                                                                                APP_HTTP_OFFSET);
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(180).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final long LEADER_BUDGET_MS = 90_000L;
    private static final long ISR_BUDGET_MS = 60_000L;
    private static final int MINORITY_PUBLISHES = 4;
    private static final String NAMESPACE = "ember";
    private static final String VERSION = "1.0.0";
    private static final String ENGINE_KEY = NAMESPACE + ":isrcut:" + VERSION;

    @TempDir
    Path dataDir;

    private EmberCluster cluster;

    private record Response(int status, String body) {
        boolean acked() {
            return status >= 200 && status < 300;
        }
    }

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.allNodes().forEach(node -> node.partitionFrom(Set.of()));
            assertThat(cluster.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped")).isEqualTo("stopped");
        }
    }

    @Test
    @Timeout(600)
    void partitionedMinorityOwner_withAnIsrReplicaOnTheMajoritySide_acknowledgesNothing() {
        cluster = EmberTestPorts.startedCluster(PORTS, this::fiveNodes, START_BOUND);
        awaitLeader();

        var created = post(leaderPort(), "/api/v1/streams/" + NAMESPACE + "/isrcut/" + VERSION, "{\"partitions\":1}");

        assertThat(created.acked()).as("create: %s", created.body()).isTrue();

        var record = awaitFullIsr();
        var owner = cluster.getNode(record.owner().id()).unwrap();
        var near = record.isr().stream().filter(member -> !member.equals(record.owner())).findFirst().orElseThrow();
        var far = record.isr().stream().filter(member -> !member.equals(record.owner()) && !member.equals(near)).findFirst().orElseThrow();

        var control = acknowledgedWithin(owner, "before-cut", ISR_BUDGET_MS);

        assertThat(control.acked()).as("control: the owner acknowledges with its whole ISR reachable: %s", control.body())
                                   .isTrue();

        var minority = Set.of(record.owner(), near);
        var majority = cluster.allNodes()
                              .stream()
                              .map(AetherNode::self)
                              .filter(id -> !minority.contains(id))
                              .collect(Collectors.toSet());

        assertThat(majority).as("the far ISR replica is on the majority side").contains(far);
        cut(minority, majority);

        var cutAt = System.nanoTime();
        var outcomes = publishConcurrently(owner, MINORITY_PUBLISHES);

        System.out.printf("ISRCUT owner=%s isr=%s near=%s control=%s outcomes=%s after %dms%n",
                          record.owner().id(),
                          record.isr(),
                          near.id(),
                          control,
                          outcomes,
                          (System.nanoTime() - cutAt) / 1_000_000L);

        assertThat(outcomes.stream().filter(Response::acked).toList()).as("acknowledged by the cut-off owner %s (ISR %s, near %s): %s",
                                                                          record.owner().id(),
                                                                          record.isr(),
                                                                          near.id(),
                                                                          outcomes)
                                                                      .isEmpty();
    }

    /// The control publish, retried while the owner is still activating (a transient refusal), until acknowledged.
    private Response acknowledgedWithin(AetherNode owner, String payload, long budgetMs) {
        var deadline = System.currentTimeMillis() + budgetMs;
        var last = publish(owner, payload);

        while (!last.acked() && System.currentTimeMillis() < deadline) {
            sleepQuietly(500L);
            last = publish(owner, payload);
        }

        return last;
    }

    private void cut(Set<NodeId> minority, Set<NodeId> majority) {
        cluster.allNodes()
               .forEach(node -> node.partitionFrom(minority.contains(node.self())
                                                   ? majority
                                                   : minority));
    }

    /// The owner's own local publish path, exactly what `StreamWriteRouter` runs for a self-owned partition: the
    /// owner-admitted append at the replica floor, then the confirmation barrier for `confirmation_factor - 1` peers.
    /// Called on the owner's manager directly because the management publish route forwards through the leader,
    /// which is on the far side of the cut and would never reach the owner's ack path.
    private List<Response> publishConcurrently(AetherNode owner, int count) {
        var manager = owner.streamPartitionManager();
        var confirmations = manager.confirmationFactorFor(ENGINE_KEY) - 1;
        var futures = new ArrayList<CompletableFuture<Response>>();

        for (var i = 0; i < count; i++) {
            var payload = ("after-cut-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8);

            futures.add(CompletableFuture.supplyAsync(() -> manager.publishLocalAtFloor(ENGINE_KEY,
                                                                                        0,
                                                                                        payload,
                                                                                        System.currentTimeMillis(),
                                                                                        confirmations)
                                                                   .async()
                                                                   .flatMap(offset -> manager.awaitReplication(ENGINE_KEY,
                                                                                                               0,
                                                                                                               offset,
                                                                                                               confirmations)
                                                                                             .map(_ -> offset))
                                                                   .await(TimeSpan.timeSpan(30).seconds())
                                                                   .fold(cause -> new Response(503, cause.message()),
                                                                         offset -> new Response(200, "offset " + offset))));
        }

        return futures.stream().map(CompletableFuture::join).toList();
    }

    private Response publish(AetherNode node, String payload) {
        return post(mgmtPort(node), "/api/v1/streams/" + NAMESPACE + "/isrcut/" + VERSION + "/publish", "{\"data\":\"" + payload + "\"}");
    }

    private int mgmtPort(AetherNode node) {
        return cluster.status()
                      .nodes()
                      .stream()
                      .filter(status -> status.id().equals(node.self().id()))
                      .findFirst()
                      .orElseThrow()
                      .mgmtPort();
    }

    /// The committed record once its ISR holds three members (owner + both replicas).
    private StreamPartitionOwnershipValue awaitFullIsr() {
        var deadline = System.currentTimeMillis() + ISR_BUDGET_MS;

        while (System.currentTimeMillis() < deadline) {
            var record = committedRecord().filter(value -> value.isrVersion() > 0 && value.isr().size() == 3);

            if (record.isPresent()) {
                return record.unwrap();
            }

            sleepQuietly(250L);
        }

        throw new AssertionError("no committed ISR of three for " + ENGINE_KEY + " within " + ISR_BUDGET_MS + "ms; last: " + committedRecord());
    }

    private Option<StreamPartitionOwnershipValue> committedRecord() {
        var seen = new HashSet<StreamPartitionOwnershipValue>();

        cluster.allNodes()
               .forEach(node -> node.kvStore()
                                    .getTyped(AetherKey.StreamPartitionOwnershipKey.streamPartitionOwnershipKey(ENGINE_KEY, 0),
                                              StreamPartitionOwnershipValue.class)
                                    .onPresent(seen::add));

        return seen.size() == 1
               ? Option.some(seen.iterator().next())
               : Option.none();
    }

    private EmberCluster fiveNodes(int basePort) {
        var built = emberCluster(CLUSTER_SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, "isrcut");

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
    private static Response post(int mgmtPort, String path, String json) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://127.0.0.1:" + mgmtPort + path))
                                 .timeout(REQUEST_TIMEOUT)
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(json))
                                 .build();

        try (var client = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            return new Response(response.statusCode(), response.body());
        } catch (Exception e) {
            return new Response(-1, e.toString());
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
