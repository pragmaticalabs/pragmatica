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
import java.util.stream.IntStream;

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

/// #2683 end to end: a publish the STREAMING governor forwards to the partition's owner is acknowledged when the transport
/// refuses the forwarded frame before writing it, as a backpressured FORWARD lane refuses it. CI run 42030156322 logged
/// exactly this: the governor's FORWARD lane to the owner was unwritable for 21 ms, one frame was refused within 20 ms of
/// the publish entering, and the client got 503 "outcome unknown" 2.5 s later, when the entry node's HTTP forward hop
/// expired. The refusal is injected with the transport's test-only outbound refusal filter
/// ([QuicClusterNetwork#setOutboundRefusalFilter]) on the governor, once, on its first `PublishForward` for the stream.
///
/// Before #2683 the refused frame was invisible to `StreamForwardClient.publishRemote`: the publish waited for an ack that
/// could not come, and the hop expired into 503 "outcome unknown" (red every run). Now the refusal fails the forward at once
/// with the known, pre-append `PublishNotSent`, the write router's bounded retry re-sends it 150 ms later, and the client
/// gets 2xx.
@PortBudget
class EmberForwardedPublishRefusedTest {
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
    /// Six partitions: the chance that the governor owns every one, leaving no forwarded partition, is (1/3)^6.
    private static final int PARTITIONS = 6;
    private static final String NAMESPACE = "ember";
    private static final String NAME = "refusedforward0";
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
    void forwardedPublish_isAcknowledged_whenTheGovernorsForwardFrameIsRefusedBeforeWriting() {
        cluster = EmberTestPorts.startedCluster(PORTS, this::threeNodes, START_BOUND);
        awaitLeader();

        assertThat(post(leaderPort(), "/api/v1/streams/" + NAMESPACE + "/" + NAME + "/" + VERSION, "{\"partitions\":" + PARTITIONS + "}"))
            .as("create %s", NAME)
            .startsWith("2");
        var governor = streamingGovernor();
        var partition = forwardedPartition(governor);
        var owner = awaitFullIsr(partition).owner().id();
        var entryPort = entryNode(governor).mgmtPort();

        // Warm-up: the owner is activated and every lane is open before the fault is armed, so the one refusal below is the
        // only thing that can stand between the publish and its ack.
        assertThat(publishRetryingRefusal(entryPort, partition, "warmup")).as("warm-up publish").startsWith("2");

        var fired = new AtomicBoolean();

        refuseFirstPublishForward(node(governor), fired);
        var startedAt = System.nanoTime();
        var response = publishResponse(entryPort, partition, "refused-0");
        var elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        System.out.printf("REFUSED-FORWARD stream=%s partition=%d owner=%s governor=%s fired=%s publishMs=%d -> %s%n",
                          ENGINE_KEY,
                          partition,
                          owner,
                          governor,
                          fired.get(),
                          elapsedMs,
                          response.lines().findFirst().orElse(""));
        assertThat(fired.get()).as("the fault fired: the governor's PublishForward to the owner was refused before writing")
                               .isTrue();
        assertThat(response).as("the publish is acknowledged, not 503 outcome unknown").startsWith("2");
    }

    /// Refuses the governor's FIRST `PublishForward` for the test stream, once, whichever owner it is sent to.
    private static void refuseFirstPublishForward(AetherNode node, AtomicBoolean fired) {
        ((QuicClusterNetwork) StreamTripwireSupport.component(node, "clusterNode", RabiaNode.class)
                                                   .network()).setOutboundRefusalFilter((_, message) -> isTestPublishForward(message)
                                                                       && fired.compareAndSet(false, true));
    }

    private static boolean isTestPublishForward(Object message) {
        return message instanceof StreamForwardMessage.PublishForward publish && publish.streamName().equals(ENGINE_KEY);
    }

    /// The first partition whose committed owner is not the governor, so a publish entered there is forwarded to the owner.
    private int forwardedPartition(String governor) {
        var deadline = System.currentTimeMillis() + ISR_BUDGET_MS;

        while (System.currentTimeMillis() < deadline) {
            var owners = IntStream.range(0, PARTITIONS).mapToObj(this::committedRecord).toList();

            if (owners.stream().allMatch(Option::isPresent)) {
                return IntStream.range(0, PARTITIONS)
                                .filter(index -> !owners.get(index).unwrap().owner().id().equals(governor))
                                .findFirst()
                                .orElseThrow(() -> new AssertionError("the governor " + governor + " owns every partition; nothing is forwarded"));
            }

            sleepQuietly(50L);
        }

        throw new AssertionError("no committed ownership of every partition of " + ENGINE_KEY + " within " + ISR_BUDGET_MS + "ms");
    }

    private StreamPartitionOwnershipValue awaitFullIsr(int partition) {
        var deadline = System.currentTimeMillis() + ISR_BUDGET_MS;

        while (System.currentTimeMillis() < deadline) {
            var record = committedRecord(partition).filter(value -> value.isrVersion() > 0 && value.isr().size() == CLUSTER_SIZE);

            if (record.isPresent()) {
                return record.unwrap();
            }

            sleepQuietly(50L);
        }

        throw new AssertionError("no committed ISR of " + CLUSTER_SIZE + " for " + ENGINE_KEY + "[" + partition + "] within " + ISR_BUDGET_MS + "ms");
    }

    /// The record every node agrees on, or none while they differ.
    private Option<StreamPartitionOwnershipValue> committedRecord(int partition) {
        var seen = new HashSet<StreamPartitionOwnershipValue>();

        cluster.allNodes()
               .forEach(node -> node.kvStore()
                                    .getTyped(AetherKey.StreamPartitionOwnershipKey.streamPartitionOwnershipKey(ENGINE_KEY, partition),
                                              StreamPartitionOwnershipValue.class)
                                    .onPresent(seen::add));

        return seen.size() == 1
               ? Option.some(seen.iterator().next())
               : Option.none();
    }

    /// Only the one refusal a publish documents as safe to repeat (`503 ... refused before writing, retry`) is retried,
    /// bounded; any other answer is returned as is.
    private String publishRetryingRefusal(int entryPort, int partition, String payload) {
        var deadline = System.currentTimeMillis() + REFUSAL_RETRY_BUDGET_MS;
        var response = publishResponse(entryPort, partition, payload);

        for (var attempt = 1; attempt < REFUSAL_RETRY_ATTEMPTS && isRetryableRefusal(response) && System.currentTimeMillis() < deadline; attempt++) {
            System.out.printf("PUBLISH-REFUSAL-RETRY %s attempt=%d -> %s%n", payload, attempt, response.lines().findFirst().orElse(""));
            sleepQuietly(REFUSAL_RETRY_PAUSE_MS);
            response = publishResponse(entryPort, partition, payload);
        }

        return response;
    }

    private static boolean isRetryableRefusal(String response) {
        return response.startsWith("503") && response.contains("refused before writing, retry");
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

    private AetherNode node(String id) {
        return cluster.allNodes()
                      .stream()
                      .filter(node -> node.self().id().equals(id))
                      .findFirst()
                      .orElseThrow();
    }

    /// A node that is not the governor, so the management layer forwards the publish to the governor over the HTTP hop.
    private EmberCluster.NodeStatus entryNode(String governor) {
        return cluster.status()
                      .nodes()
                      .stream()
                      .filter(node -> !node.id().equals(governor))
                      .findFirst()
                      .orElseThrow();
    }

    private String publishResponse(int mgmtPort, int partition, String payload) {
        return post(mgmtPort,
                    "/api/v1/streams/" + NAMESPACE + "/" + NAME + "/" + VERSION + "/publish",
                    "{\"data\":\"" + payload + "\",\"partition\":" + partition + "}");
    }

    private EmberCluster threeNodes(int basePort) {
        var built = emberCluster(CLUSTER_SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, "refusedfw");

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
