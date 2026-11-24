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
import java.util.HashSet;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.StreamingConfig;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.ReadPreference;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #946, multi-node: an `ANY_REPLICA` read of a user stream (the router the `GET /api/events` read-back also uses; `/api/events`
/// itself is not read here) answers from a node that is up while every peer it would have selected is dead.
///
/// Five real nodes, one partition at the default replication (RF 3): the committed in-sync set is three of them. Five, not
/// three, so that killing both peers leaves a consensus majority: the owner's local read needs a valid node lease, and a
/// 3-node cluster that lost two nodes has no quorum to renew it ("owner admission paused"), which fails the read for a reason that is not #946. Records are
/// acknowledged first, and the owner -- the node whose replica registry lists the other two as caught-up peers -- serves them through the router (the control: the instrument can see
/// the records and the router reaches them). Then both other nodes are black-holed -- the survivor's registry lists them as
/// caught-up peers for the whole read (membership and ISR changes are held beyond it), which is the state a degraded cluster is in until membership catches up -- and the survivor is read again
/// through the same router. The read must return the acknowledged records from the survivor's own caught-up copy; before #946 it
/// forwarded to a dead peer, retried the other, and surfaced the failure.
@PortBudget
class EmberAnyReplicaReadFallbackTest {
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
    private static final TimeSpan READ_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan READ_FORWARD = TimeSpan.timeSpan(3).seconds();
    private static final TimeSpan ISR_LAG_MAX = TimeSpan.timeSpan(10).minutes();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final long LEADER_BUDGET_MS = 90_000L;
    private static final long ISR_BUDGET_MS = 60_000L;
    private static final long REPLICA_VISIBLE_BUDGET_MS = 30_000L;
    private static final long OWNER_ACTIVE_BUDGET_MS = 60_000L;
    private static final int ACKED = 3;
    private static final int PARTITION = 0;
    private static final String NAMESPACE = "ember";
    private static final String VERSION = "1.0.0";
    private static final String ENGINE_KEY = NAMESPACE + ":anyreplica:" + VERSION;

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
            assertThat(cluster.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped")).isEqualTo("stopped");
        }
    }

    @Test
    @Timeout(600)
    void anyReplicaRead_isServedFromTheSurvivingCopy_whenEveryCaughtUpPeerIsDead() {
        cluster = EmberTestPorts.startedCluster(PORTS, this::clusterNodes, START_BOUND);
        awaitLeader();
        assertThat(post(leaderPort(), "/api/v1/streams/" + NAMESPACE + "/anyreplica/" + VERSION, "{\"partitions\":1}").acked()).isTrue();

        var record = awaitFullIsr();
        var owner = cluster.getNode(record.owner().id()).unwrap();
        var peers = record.isr().stream().filter(member -> !member.equals(record.owner())).toList();
        var survivor = owner;
        var killed = peers.stream().map(member -> member.id()).toList();

        // The owner admits writes only once activated for the partition; an ISR of three says nothing about that. A publish
        // that lands earlier is refused (OwnerNotActivated), so wait for the one condition that admits it: the owner's own gate.
        awaitUntil(() -> owner.streamPartitionManager().mayServeAsOwner(ENGINE_KEY, PARTITION),
                   OWNER_ACTIVE_BUDGET_MS,
                   "the owner is activated for " + ENGINE_KEY + "[" + PARTITION + "]");

        for (var i = 0; i < ACKED; i++) {
            assertThat(publishAcked(owner, "acked-" + i)).as("offset of acked-" + i).isEqualTo(i);
        }

        awaitUntil(() -> peers.stream().allMatch(member -> payloads(cluster.getNode(member.id()).unwrap()).size() == ACKED),
                   REPLICA_VISIBLE_BUDGET_MS,
                   "every replica serves the acknowledged records");
        awaitUntil(() -> listsAllAsCaughtUp(survivor, killed),
                   REPLICA_VISIBLE_BUDGET_MS,
                   "the survivor's replica registry lists both peers CAUGHT_UP before the kill: " + registryRows(survivor));
        assertThat(outcome(routed(survivor))).as("control: the router reads the records while every peer is alive")
                                                                         .isEqualTo(List.of("acked-0", "acked-1", "acked-2"));

        // Silent death, not a stop: a killed node leaves membership, and the next reconcile pass unregisters it from the survivor's
        // registry, so a read that started after that pass would take the local step and pass without the fix. A black-holed node
        // stays a member (raised SWIM/membership timeouts, ISR shrink pushed beyond the test) and silently drops the forward: no
        // producer of "unregister" exists inside the read window, and each forward fails by timing out (bounded by READ_FORWARD).
        killed.forEach(id -> cluster.blackhole(id)
                                    .await(STOP_BOUND)
                                    .onFailure(cause -> Assertions.fail("blackhole of " + id + " failed: " + cause.message())));

        // The path ran only if the registry listed both dead peers CAUGHT_UP for the whole read: then the router picked one,
        // forwarded to a silent node, retried the other, and degraded. The same rows bracket the read (before and after), so
        // the router's decision snapshot, taken between the two, is the one asserted; nothing is inferred from timing.
        var registryBeforeRead = registryRows(survivor);

        assertThat(listsAllAsCaughtUp(survivor, killed)).as("registry before the read lists both silent peers CAUGHT_UP: " + registryBeforeRead)
                                                        .isTrue();

        var read = outcome(routed(survivor));
        var registryAfterRead = registryRows(survivor);

        assertThat(registryAfterRead).as("the registry did not change across the read (no reconcile pass reached it)")
                                     .isEqualTo(registryBeforeRead);
        assertThat(read).as("the survivor answers from its own copy; registry across the read: " + registryBeforeRead)
                        .isEqualTo(List.of("acked-0", "acked-1", "acked-2"));
    }

    private boolean listsAllAsCaughtUp(AetherNode node, List<String> peerIds) {
        var rows = node.streamReadRouter().replicaSnapshot(ENGINE_KEY, PARTITION).replicas();

        return peerIds.stream()
                      .allMatch(id -> rows.stream()
                                          .anyMatch(row -> row.nodeId().equals(id) && "CAUGHT_UP".equals(row.state()) && row.confirmedOffset() >= ACKED - 1));
    }

    private String registryRows(AetherNode node) {
        return node.streamReadRouter()
                   .replicaSnapshot(ENGINE_KEY, PARTITION)
                   .replicas()
                   .stream()
                   .map(row -> row.nodeId() + "=" + row.state() + "@" + row.confirmedOffset())
                   .toList()
                   .toString();
    }

    /// Appends and waits for the acknowledgement of the offset the append returned. A refused append fails the test with its
    /// cause: a refusal is never turned into an offset, so no record is counted as acknowledged that does not exist.
    private long publishAcked(AetherNode owner, String payload) {
        var deadline = System.currentTimeMillis() + ISR_BUDGET_MS;
        var manager = owner.streamPartitionManager();
        var confirmations = manager.confirmationFactorFor(ENGINE_KEY) - 1;
        var offset = manager.publishLocalAtFloor(ENGINE_KEY, PARTITION, payload.getBytes(StandardCharsets.UTF_8), System.currentTimeMillis(), confirmations)
                            .async()
                            .await(TimeSpan.timeSpan(30).seconds())
                            .fold(cause -> Assertions.<Long>fail("append of " + payload + " refused: " + cause.message()),
                                  value -> value);
        var last = awaitedAck(manager, confirmations, offset);

        while (!last.acked() && System.currentTimeMillis() < deadline) {
            sleepQuietly(500L);
            last = awaitedAck(manager, confirmations, offset);
        }

        assertThat(last.acked()).as("offset " + offset + " of " + payload + " acknowledged: " + last.body()).isTrue();

        return offset;
    }

    private static Response awaitedAck(StreamPartitionManager manager, int confirmations, long offset) {
        return manager.awaitReplication(ENGINE_KEY, PARTITION, offset, confirmations)
                      .await(TimeSpan.timeSpan(30).seconds())
                      .fold(cause -> new Response(503, cause.message()), _ -> new Response(200, "offset " + offset));
    }

    private List<String> payloads(AetherNode node) {
        return node.streamPartitionManager()
                   .readLocal(ENGINE_KEY, PARTITION, 0L, 100)
                   .map(EmberAnyReplicaReadFallbackTest::texts)
                   .or(List.of());
    }

    private Result<List<String>> routed(AetherNode node) {
        return node.streamReadRouter()
                   .read(ENGINE_KEY, PARTITION, 0L, 100, ReadPreference.ANY_REPLICA)
                   .await(READ_BOUND)
                   .map(EmberAnyReplicaReadFallbackTest::texts);
    }

    private static List<String> outcome(Result<List<String>> read) {
        return read.fold(cause -> List.of("read failed: " + cause.message()), texts -> texts);
    }

    private static List<String> texts(List<RawEvent> events) {
        return events.stream().map(event -> new String(event.data(), StandardCharsets.UTF_8)).toList();
    }

    private static void awaitUntil(BooleanSupplier condition, long budgetMs, String what) {
        var deadline = System.currentTimeMillis() + budgetMs;

        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            sleepQuietly(200L);
        }
        assertThat(condition.getAsBoolean()).as("within %dms: %s", budgetMs, what).isTrue();
    }

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
        var holders = new int[]{0};

        cluster.allNodes()
               .forEach(node -> node.kvStore()
                                    .getTyped(AetherKey.StreamPartitionOwnershipKey.streamPartitionOwnershipKey(ENGINE_KEY, PARTITION),
                                              StreamPartitionOwnershipValue.class)
                                    .onPresent(value -> {
                                        seen.add(value);
                                        holders[0]++;
                                    }));

        // Every live node must hold the record and all must agree: a node still without it is a cluster still settling.
        return seen.size() == 1 && holders[0] == cluster.allNodes().size()
               ? Option.some(seen.iterator().next())
               : Option.none();
    }

    private EmberCluster clusterNodes(int basePort) {
        var built = emberCluster(CLUSTER_SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, "anyreplica");
        var defaults = StreamingConfig.streamingConfig();

        built.withDataBaseDir(dataDir);
        // Hold the producers of a registry change beyond the read window: membership (SWIM suspect 60 s, split 60 s) and the
        // committed ISR (isrLagMax); and bound each forward by READ_FORWARD, so the read ends in about two of them.
        built.withRaisedSwimTimeouts();
        built.withStreamingConfig(StreamingConfig.streamingConfig(defaults.publishForwardTimeout(),
                                                                  READ_FORWARD,
                                                                  defaults.maxReadResponseBytes())
                                                 .withIsrLagMax(ISR_LAG_MAX));

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
