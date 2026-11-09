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
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardResponse;
import org.pragmatica.aether.http.forward.HttpForwardMessage.Pipeline;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.quic.QuicClusterNetwork;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #2569: the governor's management-forward REPLY to the entry node is refused by the transport (as a backpressured FORWARD
/// lane refuses it). Before the fix `ManagementServer` ignored the send outcome, so the entry never received the reply, its
/// forward timed out at 2.5 s and the client got 503 "outcome unknown" for an acknowledged publish. Now the reply is re-sent
/// (`ForwardReplyResender`).
@PortBudget
class EmberPublishReplyLegRefusedTest {
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
    private static final String NAMESPACE = "ember";
    private static final String NAME = "refusedcatchup0";
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
    void firstPublish_isAcknowledgedToTheClient_whenTheGovernorsReplyIsRefusedOnce() {
        var run = publishWithReplyRefusals(1);

        assertThat(run.refused()).as("positive control: the fault fired").isEqualTo(1);
        assertThat(run.response()).as("the publish is acknowledged to the client").startsWith("2");
    }

    @Test
    @Timeout(600)
    void firstPublish_isAcknowledgedToTheClient_whenTheGovernorsReplyIsRefusedThreeTimesInARow() {
        var run = publishWithReplyRefusals(3);

        assertThat(run.refused()).as("positive control: the fault fired three times").isEqualTo(3);
        assertThat(run.response()).as("the publish is acknowledged after holds of 250 and 500 ms").startsWith("2");
    }

    /// The bound: a reply refused for good is re-sent at about 0, 250, 750 and 1750 ms and then given up (the next 2 s hold
    /// exceeds what the entry's 2.5 s hop has left), so the client still sees the entry's 503 and the transport sees a handful
    /// of attempts, not a stream of them.
    @Test
    @Timeout(600)
    void firstPublish_replyRefusedForGood_isResentBoundedlyThenTheEntryAnswersOutcomeUnknown() {
        var run = publishWithReplyRefusals(Integer.MAX_VALUE);

        assertThat(run.refused()).as("sends at ~0, 250, 750, 1750 ms").isBetween(3, 5);
        assertThat(run.response()).startsWith("503").contains("outcome unknown");
    }

    private Run publishWithReplyRefusals(int limit) {
        cluster = EmberTestPorts.startedCluster(PORTS, this::threeNodes, START_BOUND);
        awaitLeader();

        assertThat(post(leaderPort(), "/api/v1/streams/" + NAMESPACE + "/" + NAME + "/" + VERSION, "{\"partitions\":1}"))
            .as("create %s", NAME)
            .startsWith("2");
        var owner = awaitFullIsr().owner().id();
        var governor = streamingGovernor();
        var refused = new AtomicInteger();

        cluster.allNodes().forEach(node -> refuseManagementReplies(node, limit, refused));

        var startedAt = System.nanoTime();
        var response = publishResponse(entryNode(governor).mgmtPort(), "first-0");
        var elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        System.out.printf("REPLY-LEG-REFUSED limit=%d owner=%s governor=%s entry=%s refusals=%d publishMs=%d -> %s%n",
                          limit, owner, governor, entryNode(governor).id(), refused.get(), elapsedMs, response.lines().findFirst().orElse(""));

        return new Run(refused.get(), response);
    }

    private record Run(int refused, String response) {}

    /// Refuses the first `limit` management forward replies (across all nodes) with the transport's own refusal outcome.
    private static void refuseManagementReplies(AetherNode node, int limit, AtomicInteger refused) {
        ((QuicClusterNetwork) StreamTripwireSupport.component(node, "clusterNode", RabiaNode.class)
                                                   .network()).setOutboundRefusalFilter((_, message) -> message instanceof HttpForwardResponse reply
                                                                       && reply.pipeline() == Pipeline.MANAGEMENT
                                                                       && refused.getAndIncrement() < limit);
    }

    private StreamPartitionOwnershipValue awaitFullIsr() {
        var deadline = System.currentTimeMillis() + ISR_BUDGET_MS;

        while (System.currentTimeMillis() < deadline) {
            var record = committedRecord().filter(value -> value.isrVersion() > 0 && value.isr().size() == CLUSTER_SIZE);

            if (record.isPresent()) {
                return record.unwrap();
            }

            sleepQuietly(50L);
        }

        throw new AssertionError("no committed ISR of " + CLUSTER_SIZE + " for " + ENGINE_KEY + " within " + ISR_BUDGET_MS + "ms");
    }

    /// The record every node agrees on, or none while they differ.
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

    private EmberCluster.NodeStatus entryNode(String governor) {
        return cluster.status()
                      .nodes()
                      .stream()
                      .filter(node -> !node.id().equals(governor))
                      .findFirst()
                      .orElseThrow();
    }

    private String publishResponse(int mgmtPort, String payload) {
        return post(mgmtPort, "/api/v1/streams/" + NAMESPACE + "/" + NAME + "/" + VERSION + "/publish", "{\"data\":\"" + payload + "\"}");
    }

    private EmberCluster threeNodes(int basePort) {
        var built = emberCluster(CLUSTER_SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, "refusedcu");

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
