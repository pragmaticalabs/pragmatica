// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import org.pragmatica.aether.api.ClusterEvent;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.slice.stream.SystemStreams;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #2077 — a silently dead follower must not leave the survivors' streams unwritable.
///
/// Five in-process nodes. Right after the leader is elected, four user streams are created and a follower that
/// holds a replica of `scope0` is blackholed (silent death; the QUIC channels stay open). The survivors must then
/// recover on their own, with no operator action and no earlier-ticket precondition: EVERY survivor reads at least
/// one cluster event, and every stream, including those whose replica set holds the dead node, accepts a publish
/// through a survivor, within [#RECOVERY_BOUND_MS] of the blackhole.
///
/// The bound is the victim's own quorum-loss self-fence (about 78 s after the blackhole on bigboy; the victim cannot
/// reach quorum, drains and stops, which closes its channels) plus failure-detection and promotion slack. Before the
/// fix the leader kept a transport-vetoed FAULTY verdict it never replayed, so its DEAD verdict, and the ISR shrink
/// that only it commits, arrived 217 s after the blackhole, and the promoted-owner catch-up then waited the source
/// bound on a peer its own membership view had already dropped.
///
/// Positive controls: every poll line names, per survivor, the membership state of the victim, the events it reads
/// and the committed `cluster-events` ownership record, so a pass cannot come from a survivor that never saw the death.
@PortBudget
class EmberBlackholedReplicaRecoveryTest {
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
    private static final long LEADER_BUDGET_MS = 90_000L;
    /// Rc4 measured 219 s and 225 s on bigboy (two runs); the victim's self-fence puts the floor near 80 s.
    static final long RECOVERY_BOUND_MS = 150_000L;
    private static final long POLL_EVERY_MS = 5_000L;
    private static final List<String> STREAMS = List.of("scope0", "scope1", "scope2", "scope3");

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            assertThat(cluster.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped")).isEqualTo("stopped");
        }
    }

    @Test
    @Timeout(900)
    void blackholedReplicaFollower_survivorsRecover_everySurvivorReadsEventsAndEveryStreamIsWritable() {
        cluster = EmberTestPorts.startedCluster(PORTS,
                                                basePort -> emberCluster(CLUSTER_SIZE,
                                                                         basePort,
                                                                         basePort + MGMT_OFFSET,
                                                                         basePort + APP_HTTP_OFFSET,
                                                                         "pdep"),
                                                START_BOUND);
        var deadline = System.currentTimeMillis() + LEADER_BUDGET_MS;

        while (System.currentTimeMillis() < deadline && cluster.getLeaderManagementPort().isEmpty()) {
            sleepQuietly(200L);
        }

        assertThat(cluster.getLeaderManagementPort().isPresent()).as("a leader is elected").isTrue();
        var leaderId = cluster.currentLeader().unwrap();
        var nodes = List.copyOf(cluster.allNodes());

        STREAMS.forEach(name -> assertThat(createWithRetry(name)).as("create " + name).startsWith("2"));
        var scope0Replicas = replicaSet(nodes, "scope0");
        var victim = nodes.stream()
                          .map(n -> n.self().id())
                          .filter(id -> !id.equals(leaderId) && scope0Replicas.contains(id))
                          .findFirst()
                          .orElseThrow();
        var victimId = NodeId.nodeId(victim).unwrap();

        System.out.printf("PROBE RECOVERY leader=%s victim=%s scope0Replicas=%s%n", leaderId, victim, scope0Replicas);
        assertThat(cluster.blackhole(victim).await(STOP_BOUND).isSuccess()).isTrue();

        var survivors = nodes.stream().filter(n -> !n.self().id().equals(victim)).toList();
        var t1 = System.currentTimeMillis();
        var recoveredAtMs = -1L;

        while (System.currentTimeMillis() - t1 < RECOVERY_BOUND_MS && recoveredAtMs < 0) {
            var unreadable = survivors.stream().filter(n -> events(n).isEmpty()).map(n -> n.self().id()).toList();
            var unwritable = STREAMS.stream().filter(name -> !publishes(survivors, name)).toList();

            System.out.printf("PROBE RECOVERY t=+%ds unreadableSurvivors=%s unwritableStreams=%s%n",
                              (System.currentTimeMillis() - t1) / 1000,
                              unreadable,
                              unwritable);
            survivors.forEach(n -> System.out.printf("PROBE RECOVERY   %s fsm[%s]=%s events=%d record=%s%n",
                                                     n.self().id(),
                                                     victim,
                                                     n.membershipFsm().memberStates().get(victimId),
                                                     events(n).size(),
                                                     clusterEventsRecord(n)));
            if (unreadable.isEmpty() && unwritable.isEmpty()) {
                recoveredAtMs = System.currentTimeMillis() - t1;
            } else {
                sleepQuietly(POLL_EVERY_MS);
            }
        }

        System.out.printf("PROBE RECOVERY recoveredAtMs=%d bound=%d%n", recoveredAtMs, RECOVERY_BOUND_MS);
        assertThat(recoveredAtMs).as("every survivor reads >= 1 cluster event and every stream accepts a publish within "
                                     + RECOVERY_BOUND_MS + " ms of the blackhole of " + victim + " (-1 = never)")
                                 .isBetween(0L, RECOVERY_BOUND_MS);
    }

    private boolean publishes(List<AetherNode> survivors, String name) {
        return survivors.stream()
                        .findFirst()
                        .map(n -> postTo(mgmtPortOf(n), "/api/v1/streams/ember/" + name + "/1.0.0/publish", "{\"data\":\"probe\"}").startsWith("2"))
                        .orElse(false);
    }

    private int mgmtPortOf(AetherNode node) {
        return cluster.status()
                      .nodes()
                      .stream()
                      .filter(i -> i.id().equals(node.self().id()))
                      .findFirst()
                      .map(i -> i.mgmtPort())
                      .orElse(-1);
    }

    private String createWithRetry(String name) {
        var deadline = System.currentTimeMillis() + 60_000L;
        var path = "/api/v1/streams/ember/" + name + "/1.0.0";
        var response = postTo(cluster.getLeaderManagementPort().or(-1), path, "{\"partitions\":1}");

        while (!response.startsWith("2") && System.currentTimeMillis() < deadline) {
            sleepQuietly(500L);
            response = postTo(cluster.getLeaderManagementPort().or(-1), path, "{\"partitions\":1}");
        }

        return response;
    }

    /// Owner and ISR of the stream's partition 0 as committed, read from every node until one holds the record.
    private static List<String> replicaSet(List<AetherNode> nodes, String name) {
        var deadline = System.currentTimeMillis() + 30_000L;
        var key = AetherKey.StreamPartitionOwnershipKey.streamPartitionOwnershipKey("ember:" + name + ":1.0.0", 0);

        while (System.currentTimeMillis() < deadline) {
            for (var n : nodes) {
                var record = n.kvStore().getTyped(key, StreamPartitionOwnershipValue.class);

                if (record.isPresent()) {
                    return record.map(v -> java.util.stream.Stream.concat(java.util.stream.Stream.of(v.owner()), v.isr().stream())
                                                                  .map(NodeId::id)
                                                                  .distinct()
                                                                  .toList())
                                 .or(List.of());
                }
            }
            sleepQuietly(500L);
        }

        return List.of();
    }

    private static String clusterEventsRecord(AetherNode n) {
        var key = AetherKey.StreamPartitionOwnershipKey.streamPartitionOwnershipKey(SystemStreams.CLUSTER_EVENTS.asString(), 0);

        return n.kvStore()
                .getTyped(key, StreamPartitionOwnershipValue.class)
                .map(v -> "owner=" + v.owner().id() + " isr=" + v.isr().stream().map(NodeId::id).toList() + " isrV=" + v.isrVersion()
                          + " fenced=" + v.fenced().stream().map(NodeId::id).toList())
                .or("<none>");
    }

    private static List<ClusterEvent> events(AetherNode node) {
        return node.eventAggregator().events().await().or(List.of());
    }

    @SuppressWarnings("JBCT-EX-01")
    private static String postTo(int mgmtPort, String path, String json) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://127.0.0.1:" + mgmtPort + path))
                                 .timeout(Duration.ofSeconds(20))
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(json))
                                 .build();

        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            return response.statusCode() + " " + response.body();
        } catch (Exception e) {
            return "-1 " + e;
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
