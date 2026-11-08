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
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;

import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.quic.QuicClusterNetwork;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.assertj.core.api.Assertions.assertThat;


/// #2847 end to end: a publish whose one replica ack the transport refuses (as a backpressured FORWARD lane refuses it) is
/// acknowledged inside the entry node's management forward. The owner's await of a `confirmation_factor` 2 publish needs an
/// ack from every in-sync replica and waits up to 5 s for one (`DefaultReplicationManager.DEFAULT_ACK_TIMEOUT`); the entry
/// node's forward to the STREAMING governor gives up at 2.5 s. Before #2847 the replica sent each ack once and dropped a
/// refused one, so the publish outlived the hop and the client got 503 "outcome unknown" for a publish every replica held:
/// the `EmberFirstPublishRefusedCatchupTest` flake (#2847, run 42464551497), there under real backpressure. Here the refusal is
/// injected with the test-only outbound refusal filter of the transport ([QuicClusterNetwork#setOutboundRefusalFilter]): it
/// fires once, on one replica's first ack of an offset at or above 1, after the first publish has been acknowledged, so the
/// refused ack is the only one the second publish waits on.
@PortBudget
class EmberRefusedReplicaAckTest {
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

    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(180).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final long LEADER_BUDGET_MS = 90_000L;
    private static final long ISR_BUDGET_MS = 60_000L;
    private static final int REFUSAL_RETRY_ATTEMPTS = 20;
    private static final long REFUSAL_RETRY_BUDGET_MS = 20_000L;
    private static final long REFUSAL_RETRY_PAUSE_MS = 500L;
    private static final String NAMESPACE = "ember";
    private static final String NAME = "refusedack0";
    private static final String VERSION = "1.0.0";
    private static final String ENGINE_KEY = NAMESPACE + ":" + NAME + ":" + VERSION;

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
    @Timeout(600)
    void publish_isAcknowledgedInsideTheManagementForward_whenOneReplicasAckIsRefused() {
        cluster = EmberTestPorts.startedCluster(PORTS, this::threeNodes, START_BOUND);
        awaitLeader();
        assertThat(post(leaderPort(),
                        "/api/v1/streams/" + NAMESPACE + "/" + NAME + "/" + VERSION,
                        "{\"partitions\":1}")).as("create %s", NAME)
                  .startsWith("2");
        var owner = awaitFullIsr().owner().id();
        var governor = streamingGovernor();

        assertThat(publishRetryingRefusal(governor, "ack-0")).as("publish ack-0, before any refusal").startsWith("2");
        var replica = cluster.allNodes()
                             .stream()
                             .filter(node -> !node.self()
                                                  .id()
                                                  .equals(owner))
                             .findFirst()
                             .orElseThrow();
        var refused = new AtomicBoolean();

        refuseFirstAckAboveZero(replica, refused);
        var startedAt = System.nanoTime();
        var response = publishRetryingRefusal(governor, "ack-1");
        var elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        System.out.printf("REFUSED-ACK stream=%s owner=%s governor=%s entry=%s refusedOn=%s fired=%s publishMs=%d -> %s%n",
                          ENGINE_KEY,
                          owner,
                          governor,
                          entryNode(governor).id(),
                          replica.self().id(),
                          refused.get(),
                          elapsedMs,
                          response.lines().findFirst().orElse(""));
        assertThat(refused.get()).as("positive control: the fault fired on a replica's ack of the publish").isTrue();
        assertThat(response).as("the publish is acknowledged, not 503 outcome unknown").startsWith("2");
        for (var i = 2; i < 5; i++) {
            assertThat(publishRetryingRefusal(governor, "ack-" + i)).as("publish ack-%d", i).startsWith("2");
        }
    }

    /// Refuses `node`'s FIRST ack of the test stream at an offset at or above 1, once.
    private static void refuseFirstAckAboveZero(AetherNode node, AtomicBoolean fired) {
        ((QuicClusterNetwork) StreamTripwireSupport.component(node, "clusterNode", RabiaNode.class).network()).setOutboundRefusalFilter((_, message) -> isTestAckAboveZero(message) && fired.compareAndSet(false,
                                                                                                                                                                                                           true));
    }

    private static boolean isTestAckAboveZero(Object message) {
        return message instanceof ReplicationMessage.ReplicateAck ack
               && ack.streamName()
                     .equals(ENGINE_KEY)
               && ack.confirmedOffset() >= 1;
    }

    private StreamPartitionOwnershipValue awaitFullIsr() {
        var deadline = System.currentTimeMillis() + ISR_BUDGET_MS;

        while (System.currentTimeMillis() < deadline) {
            var record = committedRecord().filter(value -> value.isrVersion() > 0 && value.isr()
                                                                                          .size() == CLUSTER_SIZE);

            if (record.isPresent()) {
                return record.unwrap();
            }

            sleepQuietly(50L);
        }

        throw new AssertionError("no committed ISR of " + CLUSTER_SIZE
                                + " for " + ENGINE_KEY
                                + " within " + ISR_BUDGET_MS
                                + "ms");
    }

    /// The record every node agrees on, or none while they differ.
    private Option<StreamPartitionOwnershipValue> committedRecord() {
        var seen = new HashSet<StreamPartitionOwnershipValue>();

        cluster.allNodes()
               .forEach(node -> node.kvStore()
                                    .getTyped(AetherKey.StreamPartitionOwnershipKey.streamPartitionOwnershipKey(ENGINE_KEY,
                                                                                                                0),
                                              StreamPartitionOwnershipValue.class)
                                    .onPresent(seen::add));

        return seen.size() == 1
               ? Option.some(seen.iterator().next())
               : Option.none();
    }

    /// A publish entered at a node that is not the STREAMING governor, so the management layer forwards it to the governor
    /// over the HTTP forward hop (a share of the 10 s request budget, 2.5 s) -- the hop whose expiry the CI failure reported
    /// as 503 "outcome unknown". Only the one refusal a publish documents as safe to repeat (`503 ... refused before writing,
    /// retry`) is retried, bounded; any other answer, the 503 "outcome unknown" included, is returned as is.
    private String publishRetryingRefusal(String governor, String payload) {
        var deadline = System.currentTimeMillis() + REFUSAL_RETRY_BUDGET_MS;
        var response = publishResponse(entryNode(governor).mgmtPort(), payload);

        for (var attempt = 1; attempt < REFUSAL_RETRY_ATTEMPTS && isRetryableRefusal(response) && System.currentTimeMillis() < deadline; attempt++) {
            System.out.printf("PUBLISH-REFUSAL-RETRY %s attempt=%d -> %s%n",
                              payload,
                              attempt,
                              response.lines().findFirst().orElse(""));
            sleepQuietly(REFUSAL_RETRY_PAUSE_MS);
            response = publishResponse(entryNode(governor).mgmtPort(), payload);
        }

        return response;
    }

    /// The node that owns the STREAMING task group, which the management layer forwards a publish to.
    private String streamingGovernor() {
        return cluster.allNodes()
                      .getFirst()
                      .taskGroupOwnerResolver()
                      .apply(TaskGroup.STREAMING)
                      .fold(cause -> {
                                throw new AssertionError("no STREAMING governor: " + cause.message());
                            },
                            NodeId::id);
    }

    private static boolean isRetryableRefusal(String response) {
        return response.startsWith("503") && response.contains("refused before writing, retry");
    }

    private EmberCluster.NodeStatus entryNode(String governor) {
        return cluster.status()
                      .nodes()
                      .stream()
                      .filter(node -> !node.id()
                                           .equals(governor))
                      .findFirst()
                      .orElseThrow();
    }

    private String publishResponse(int mgmtPort, String payload) {
        return post(mgmtPort,
                    "/api/v1/streams/" + NAMESPACE + "/" + NAME + "/" + VERSION + "/publish",
                    "{\"data\":\"" + payload + "\"}");
    }

    private EmberCluster threeNodes(int basePort) {
        var built = emberCluster(CLUSTER_SIZE,
                                 basePort,
                                 basePort + MGMT_OFFSET,
                                 basePort + APP_HTTP_OFFSET,
                                 "refusedack");

        built.withDataBaseDir(dataDir);

        return built;
    }

    private int leaderPort() {
        return cluster.getLeaderManagementPort()
                      .or(-1);
    }

    private void awaitLeader() {
        var deadline = System.currentTimeMillis() + LEADER_BUDGET_MS;

        while (System.currentTimeMillis() < deadline && cluster.getLeaderManagementPort().isEmpty()) {
            sleepQuietly(500L);
        }

        assertThat(cluster.getLeaderManagementPort().isPresent()).as("a leader is elected").isTrue();
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
