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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.forward.StreamForwardMessage;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.quic.QuicClusterNetwork;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #2574 end to end: the first publish to a new `confirmation_factor` 2 stream is acknowledged even when every replica's FIRST
/// catch-up read for it is refused by the transport, as a backpressured FORWARD lane refuses it (CI logged exactly this
/// refusal from the `stream-partition-backfill` thread within 30 ms of the stream's creation in every failing
/// `EmberOrdinaryFailoverNoFalseAlertTest` job). The refusal is injected with the test-only outbound refusal filter of the
/// transport ([QuicClusterNetwork#setOutboundRefusalFilter], on the QUIC transport only); it fires once per node, on that node's
/// first catch-up `ReadForward` for the stream.
///
/// Before #2574 the refused read was invisible: the read waited out its timeout (2 s), the offset-0 gap trigger joined that
/// dead flight and pulled nothing, and the owner's wait for every in-sync replica outlived the entry node's forward hop, so the
/// client got 503 "outcome unknown" (the spike's probe d74P1, 5 / 5 red). Now the refused read fails at once (F1), its
/// flight holds the partition's slot for the first refusal hold (~250 ms) and the joined trigger re-runs it (F2), so the
/// replica pulls offset 0 and acknowledges it inside the publish's budget.
@PortBudget
class EmberFirstPublishRefusedCatchupTest {
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
    void firstPublish_toANewCf2Stream_isAcknowledged_whenEveryReplicasFirstCatchupReadIsRefused() {
        cluster = EmberTestPorts.startedCluster(PORTS, this::threeNodes, START_BOUND);
        awaitLeader();
        var refusedBy = ConcurrentHashMap.<String>newKeySet();

        cluster.allNodes().forEach(node -> refuseFirstCatchupRead(node, refusedBy));

        assertThat(post(leaderPort(), "/api/v1/streams/" + NAMESPACE + "/" + NAME + "/" + VERSION, "{\"partitions\":1}"))
            .as("create %s", NAME)
            .startsWith("2");
        var owner = awaitFullIsr().owner().id();
        var governor = streamingGovernor();
        var startedAt = System.nanoTime();
        var response = publishRetryingRefusal(governor, "first-0");
        var elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        System.out.printf("REFUSED-CATCHUP stream=%s owner=%s governor=%s entry=%s refusedBy=%s firstPublishMs=%d -> %s%n",
                          ENGINE_KEY,
                          owner,
                          governor,
                          entryNode(governor).id(),
                          refusedBy,
                          elapsedMs,
                          response.lines().findFirst().orElse(""));
        // Every placed node starts a catch-up at the config Put, before the ownership record exists, so the eventual owner
        // may be refused too; what matters is that a replica that must acknowledge the publish was.
        assertThat(refusedBy.stream().filter(node -> !node.equals(owner)).toList())
            .as("the fault fired on a replica: its first catch-up read was refused")
            .isNotEmpty();
        assertThat(response).as("the first publish is acknowledged, not 503 outcome unknown").startsWith("2");

        for (var i = 1; i < 5; i++) {
            assertThat(publishRetryingRefusal(governor, "first-" + i)).as("publish first-%d", i).startsWith("2");
        }
    }

    /// Refuses `node`'s FIRST catch-up read of the test stream, once, on whichever target it is sent to.
    private static void refuseFirstCatchupRead(AetherNode node, Set<String> refusedBy) {
        var fired = new AtomicBoolean();
        var self = node.self().id();

        ((QuicClusterNetwork) StreamTripwireSupport.component(node, "clusterNode", RabiaNode.class)
                                                   .network()).setOutboundRefusalFilter((_, message) -> isTestCatchupRead(message)
                                                                       && fired.compareAndSet(false, true)
                                                                       && refusedBy.add(self));
    }

    private static boolean isTestCatchupRead(Object message) {
        return message instanceof StreamForwardMessage.ReadForward read && read.catchup() && read.streamName().equals(ENGINE_KEY);
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

    /// A publish entered at a node that is not the STREAMING governor, so the management layer forwards it to the governor
    /// over the HTTP forward hop (a share of the 10 s request budget, 2.5 s) -- the hop whose expiry the CI failure reported
    /// as 503 "outcome unknown". Only the one refusal a publish documents as safe to repeat (`503 ... refused before writing,
    /// retry`) is retried, bounded; any other answer, the 503 "outcome unknown" included, is returned as is.
    private String publishRetryingRefusal(String governor, String payload) {
        var deadline = System.currentTimeMillis() + REFUSAL_RETRY_BUDGET_MS;
        var response = publishResponse(entryNode(governor).mgmtPort(), payload);

        for (var attempt = 1; attempt < REFUSAL_RETRY_ATTEMPTS && isRetryableRefusal(response) && System.currentTimeMillis() < deadline; attempt++) {
            System.out.printf("PUBLISH-REFUSAL-RETRY %s attempt=%d -> %s%n", payload, attempt, response.lines().findFirst().orElse(""));
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
