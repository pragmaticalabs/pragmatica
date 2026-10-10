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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.ReadPreference;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #2087, multi-node: a stream entry is not added until its acknowledgements arrive, so NO read path of the owner or of a
/// replica serves an offset above the acknowledged high-water -- and a failover between a read and the acknowledgement
/// therefore cannot take back, or replace, anything a consumer saw.
///
/// Five real nodes, one partition at the default replication (RF 3, CF 2): the committed in-sync set is the owner plus two
/// replicas, and an acknowledgement needs every member. Three records are acknowledged first (the control: the replica
/// serves them, so the instrument can see records, and the owner's announcement reaches it over the wire). Then the far
/// replica is cut off, and two more records are published: the owner appends them and `near` receives and holds them, but
/// the far member never confirms, so they stay unacknowledged. While they are in that state the owner and `near` are
/// read through every path they serve (local read, the read served to a forwarded consumer, the router's read, the
/// visible bounds): none returns an offset above the third record. Then the owner is killed -- between the read and the
/// acknowledgement -- the cut heals, a replacement joins, and after the new owner has taken over, what was read is still
/// there, unchanged, on every node.
///
/// Before #2087 `near` served the two unacknowledged records (its own log end was its visible position), the discriminating
/// assertion below.
@PortBudget
class EmberReadCommittedFailoverTest {
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
    private static final TimeSpan ADD_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final long LEADER_BUDGET_MS = 90_000L;
    private static final long ISR_BUDGET_MS = 60_000L;
    private static final long REPLICA_VISIBLE_BUDGET_MS = 30_000L;
    private static final long FAILOVER_BUDGET_MS = 180_000L;
    private static final int ACKED = 3;
    private static final int UNACKED = 2;
    private static final int PARTITION = 0;
    private static final String NAMESPACE = "ember";
    private static final String VERSION = "1.0.0";
    private static final String ENGINE_KEY = NAMESPACE + ":rcfailover:" + VERSION;

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
    @Timeout(900)
    void unacknowledgedRecords_areServedByNoReadPath_andAFailoverBeforeTheirAckTakesNothingBack() {
        cluster = EmberTestPorts.startedCluster(PORTS, this::fiveNodes, START_BOUND);
        awaitLeader();

        var created = post(leaderPort(), "/api/v1/streams/" + NAMESPACE + "/rcfailover/" + VERSION, "{\"partitions\":1}");

        assertThat(created.acked()).as("create: %s", created.body()).isTrue();

        var record = awaitFullIsr();
        var owner = cluster.getNode(record.owner().id()).unwrap();
        var near = cluster.getNode(record.isr().stream().filter(member -> !member.equals(record.owner())).findFirst().orElseThrow().id()).unwrap();
        var far = record.isr().stream().filter(member -> !member.equals(record.owner()) && !member.id().equals(near.self().id())).findFirst().orElseThrow();

        for (var i = 0; i < ACKED; i++) {
            var control = publishAcked(owner, "acked-" + i);

            assertThat(control.acked()).as("control: acknowledged with the whole in-sync set reachable: %s", control.body()).isTrue();
        }

        awaitServes(near, ACKED);
        assertThat(payloads(near, "near, acknowledged")).as("control: the replica serves the acknowledged records, and the owner's position reached it")
                                                       .containsExactly("acked-0", "acked-1", "acked-2");

        var observed = new ArrayList<>(payloads(owner, "owner, acknowledged"));
        var others = cluster.allNodes().stream().map(AetherNode::self).filter(id -> !id.equals(far)).collect(Collectors.toSet());

        cut(Set.of(far), others);
        var unacked = startPublishes(owner, UNACKED, 90);

        awaitHolds(near, ACKED + UNACKED);
        assertThat(near.streamPartitionManager().partitionBuffer(ENGINE_KEY, PARTITION).map(ring -> ring.headOffset()).or(-1L))
            .as("control: the replica HOLDS the unacknowledged records").isEqualTo((long) (ACKED + UNACKED - 1));

        for (var node : List.of(owner, near)) {
            var who = node == owner ? "owner" : "replica";

            assertThat(payloads(node, who + " local read")).as("%s: local read serves only the acknowledged records", who)
                                                           .containsExactly("acked-0", "acked-1", "acked-2");
            assertThat(servedPayloads(node)).as("%s: the read served to a forwarded consumer", who)
                                            .containsExactly("acked-0", "acked-1", "acked-2");
            for (var preference : List.of(ReadPreference.GOVERNOR, ReadPreference.NEAREST, ReadPreference.ANY_REPLICA)) {
                assertThat(routed(node, preference)).as("%s: the router's %s read", who, preference).containsExactly("acked-0", "acked-1", "acked-2");
            }
            assertThat(node.streamPartitionManager().visibleBounds(ENGINE_KEY, PARTITION).map(bounds -> bounds.visibleHead()).or(-2L))
                .as("%s: the bounds a cursor is built from", who)
                .isEqualTo((long) (ACKED - 1));
        }

        // The failover between the read and the acknowledgement.
        var killedOwner = owner.self().id();

        cluster.killNode(killedOwner).await(STOP_BOUND).onFailure(cause -> org.junit.jupiter.api.Assertions.fail("owner kill failed: " + cause.message()));
        cluster.allNodes().forEach(node -> node.partitionFrom(Set.of()));
        var outcomes = unacked.stream().map(CompletableFuture::join).toList();

        assertThat(outcomes.stream().filter(Response::acked).toList()).as("the unacknowledged publishes were never acknowledged: %s", outcomes).isEmpty();

        cluster.addNode().await(ADD_BOUND).onFailure(cause -> org.junit.jupiter.api.Assertions.fail("replacement did not join: " + cause.message()));

        var newOwner = awaitNewOwner(killedOwner);
        var after = publishAcked(newOwner, "after-failover");

        assertThat(after.acked()).as("the new owner acknowledges once its in-sync set confirms: %s", after.body()).isTrue();
        awaitServesAll(newOwner, "after-failover");

        for (var node : cluster.allNodes()) {
            var served = payloads(node, "after failover " + node.self().id());

            assertThat(served.subList(0, Math.min(served.size(), observed.size())))
                .as("%s still serves, unchanged, what was read before the failover", node.self().id())
                .isEqualTo(observed.subList(0, Math.min(served.size(), observed.size())));
        }
        assertThat(payloads(newOwner, "new owner")).as("the new lineage extends what was read").startsWith("acked-0", "acked-1", "acked-2").endsWith("after-failover");
    }

    private Response publishAcked(AetherNode owner, String payload) {
        var deadline = System.currentTimeMillis() + ISR_BUDGET_MS;
        var last = publish(owner, payload, 30);

        while (!last.acked() && System.currentTimeMillis() < deadline) {
            sleepQuietly(500L);
            last = publish(owner, payload, 30);
        }

        return last;
    }

    /// The owner's own local publish path (what `StreamWriteRouter` runs for a self-owned partition): the owner-admitted
    /// append at the replica floor, then the confirmation barrier. Returned unresolved: the far member never confirms.
    private List<CompletableFuture<Response>> startPublishes(AetherNode owner, int count, int waitSeconds) {
        var manager = owner.streamPartitionManager();
        var confirmations = manager.confirmationFactorFor(ENGINE_KEY) - 1;
        var futures = new ArrayList<CompletableFuture<Response>>();

        for (var i = 0; i < count; i++) {
            var payload = ("unacked-" + i).getBytes(StandardCharsets.UTF_8);

            futures.add(CompletableFuture.supplyAsync(() -> manager.publishLocalAtFloor(ENGINE_KEY,
                                                                                        PARTITION,
                                                                                        payload,
                                                                                        System.currentTimeMillis(),
                                                                                        confirmations)
                                                                   .async()
                                                                   .flatMap(offset -> manager.awaitReplication(ENGINE_KEY,
                                                                                                               PARTITION,
                                                                                                               offset,
                                                                                                               confirmations)
                                                                                             .map(_ -> offset))
                                                                   .await(TimeSpan.timeSpan(waitSeconds).seconds())
                                                                   .fold(cause -> new Response(503, cause.message()),
                                                                         offset -> new Response(200, "offset " + offset))));
            sleepQuietly(100L);
        }

        return futures;
    }

    private List<String> payloads(AetherNode node, String what) {
        return node.streamPartitionManager()
                   .readLocal(ENGINE_KEY, PARTITION, 0L, 100)
                   .map(EmberReadCommittedFailoverTest::texts)
                   .or(List.of());
    }

    private List<String> servedPayloads(AetherNode node) {
        return node.streamPartitionManager()
                   .readServing(ENGINE_KEY, PARTITION, 0L, 100)
                   .map(EmberReadCommittedFailoverTest::texts)
                   .or(List.of());
    }

    private List<String> routed(AetherNode node, ReadPreference preference) {
        return node.streamReadRouter()
                   .read(ENGINE_KEY, PARTITION, 0L, 100, preference)
                   .await(TimeSpan.timeSpan(15).seconds())
                   .map(EmberReadCommittedFailoverTest::texts)
                   .or(List.of());
    }

    private static List<String> texts(List<RawEvent> events) {
        return events.stream().map(event -> new String(event.data(), StandardCharsets.UTF_8)).toList();
    }

    private void awaitServes(AetherNode node, int count) {
        awaitUntil(() -> payloads(node, "wait").size() >= count, REPLICA_VISIBLE_BUDGET_MS, node.self().id() + " serves " + count + " records");
    }

    private void awaitServesAll(AetherNode node, String last) {
        awaitUntil(() -> payloads(node, "wait").contains(last), REPLICA_VISIBLE_BUDGET_MS, node.self().id() + " serves " + last);
    }

    private void awaitHolds(AetherNode node, int count) {
        awaitUntil(() -> node.streamPartitionManager().partitionBuffer(ENGINE_KEY, PARTITION).map(ring -> ring.headOffset() + 1).or(0L) >= count,
                   REPLICA_VISIBLE_BUDGET_MS,
                   node.self().id() + " holds " + count + " records");
    }

    private AetherNode awaitNewOwner(String killed) {
        var deadline = System.currentTimeMillis() + FAILOVER_BUDGET_MS;

        while (System.currentTimeMillis() < deadline) {
            var found = committedRecord().filter(value -> !value.owner().id().equals(killed)).flatMap(value -> cluster.getNode(value.owner().id()));

            if (found.isPresent()) {
                return found.unwrap();
            }

            sleepQuietly(500L);
        }

        throw new AssertionError("ownership did not leave the killed owner " + killed + " within " + FAILOVER_BUDGET_MS + "ms; last: " + committedRecord());
    }

    private static void awaitUntil(java.util.function.BooleanSupplier condition, long budgetMs, String what) {
        var deadline = System.currentTimeMillis() + budgetMs;

        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            sleepQuietly(200L);
        }
        assertThat(condition.getAsBoolean()).as("within %dms: %s", budgetMs, what).isTrue();
    }

    private void cut(Set<NodeId> minority, Set<NodeId> majority) {
        cluster.allNodes()
               .forEach(node -> node.partitionFrom(minority.contains(node.self())
                                                   ? majority
                                                   : minority));
    }

    private Response publish(AetherNode node, String payload, int unusedSeconds) {
        var manager = node.streamPartitionManager();
        var confirmations = manager.confirmationFactorFor(ENGINE_KEY) - 1;

        return manager.publishLocalAtFloor(ENGINE_KEY, PARTITION, payload.getBytes(StandardCharsets.UTF_8), System.currentTimeMillis(), confirmations)
                      .async()
                      .flatMap(offset -> manager.awaitReplication(ENGINE_KEY, PARTITION, offset, confirmations).map(_ -> offset))
                      .await(TimeSpan.timeSpan(unusedSeconds).seconds())
                      .fold(cause -> new Response(503, cause.message()), offset -> new Response(200, "offset " + offset));
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
                                    .getTyped(AetherKey.StreamPartitionOwnershipKey.streamPartitionOwnershipKey(ENGINE_KEY, PARTITION),
                                              StreamPartitionOwnershipValue.class)
                                    .onPresent(seen::add));

        return seen.size() == 1
               ? Option.some(seen.iterator().next())
               : Option.none();
    }

    private EmberCluster fiveNodes(int basePort) {
        var built = emberCluster(CLUSTER_SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, "rcfailover");

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
