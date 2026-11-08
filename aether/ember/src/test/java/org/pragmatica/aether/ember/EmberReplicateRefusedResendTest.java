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
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.quic.QuicClusterNetwork;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #2820 end to end: a publish to a `confirmation_factor` 2 stream is acknowledged even when the owner's replicate of it to
/// an in-sync replica is refused by the transport, as a backpressured FORWARD lane refuses it (CI logged exactly that
/// refusal, "Channel to peer e01-2 not writable on stream FORWARD - refusing message (backpressure)", 5 s before
/// `EmberSealedTierRetentionTripwireTest`'s publish 27 failed with "Replication acknowledgment timed out"). The refusal is
/// injected with the test-only outbound refusal filter of the transport ([QuicClusterNetwork#setOutboundRefusalFilter]); it
/// fires once, on the first `ReplicateEvents` of the test stream sent after the filter is armed.
///
/// Before #2820 the owner discarded the refused send's outcome: the replica never received the event, nothing told it of a
/// gap until a later publish, and the owner waited its full 5 s ack bound for an ack that could not come, so the publish
/// failed. Now the owner reads the outcome and re-sends the refused chunk to that replica after a short backoff.
@PortBudget
class EmberReplicateRefusedResendTest {
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
    private static final int WARMUP_PUBLISHES = 3;
    private static final String NAMESPACE = "ember";
    private static final String NAME = "refusedreplicate0";
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
    void publish_toACf2Stream_isAcknowledged_whenTheOwnersReplicateToAReplicaIsRefusedOnce() {
        cluster = EmberTestPorts.startedCluster(PORTS, this::threeNodes, START_BOUND);
        awaitLeader();
        var armed = new AtomicBoolean();
        var refused = ConcurrentHashMap.<String>newKeySet();

        cluster.allNodes().forEach(node -> refuseFirstArmedReplicate(node, armed, refused));

        assertThat(post(leaderPort(), "/api/v1/streams/" + NAMESPACE + "/" + NAME + "/" + VERSION, "{\"partitions\":1}"))
            .as("create %s", NAME)
            .startsWith("2");
        var owner = awaitFullIsr().owner().id();
        var governor = streamingGovernor();

        for (var i = 0; i < WARMUP_PUBLISHES; i++) {
            assertThat(warmUpPublish(entryNode(governor).mgmtPort(), "warm-" + i)).as("warm-up publish %d", i).startsWith("2");
        }

        armed.set(true);
        var startedAt = System.nanoTime();
        var response = publishResponse(entryNode(governor).mgmtPort(), "refused-0");
        var elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        System.out.printf("REFUSED-REPLICATE stream=%s owner=%s governor=%s refused=%s publishMs=%d -> %s%n",
                          ENGINE_KEY,
                          owner,
                          governor,
                          refused,
                          elapsedMs,
                          response.lines().findFirst().orElse(""));
        assertThat(refused).as("the fault fired: the owner's replicate to a replica was refused").hasSize(1);
        assertThat(refused.iterator().next()).as("refused on the owner").startsWith(owner + "->");
        assertThat(response).as("the publish is acknowledged, not failed on the 5 s replication ack timeout").startsWith("2");

        for (var i = 1; i < 5; i++) {
            assertThat(publishResponse(entryNode(governor).mgmtPort(), "after-" + i)).as("publish after-%d", i).startsWith("2");
        }
    }

    /// Once `armed`, refuses the first `ReplicateEvents` of the test stream any node sends, to whichever replica, once in all.
    private static void refuseFirstArmedReplicate(AetherNode node, AtomicBoolean armed, Set<String> refused) {
        var self = node.self().id();

        ((QuicClusterNetwork) StreamTripwireSupport.component(node, "clusterNode", RabiaNode.class)
                                                   .network()).setOutboundRefusalFilter((peer, message) -> isTestReplicate(message)
                                                                       && armed.compareAndSet(true, false)
                                                                       && refused.add(self + "->" + peer.id()));
    }

    private static boolean isTestReplicate(Object message) {
        return message instanceof ReplicationMessage.ReplicateEvents replicate && replicate.streamName().equals(ENGINE_KEY);
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

    /// The warm-up publish, retried only while the owner is not yet promoted (#2916): a full ISR does not mean the owner finished
    /// activating, and that refusal is raised before anything is written, so a retry cannot duplicate. The armed publish is not
    /// routed through here: its single attempt is the scenario.
    private String warmUpPublish(int mgmtPort, String payload) {
        var response = StreamTripwireSupport.publishViaOnceActivated(mgmtPort, NAME, payload);

        return response.status() + " " + response.body();
    }

    private EmberCluster threeNodes(int basePort) {
        var built = emberCluster(CLUSTER_SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, "refusedrp");

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
