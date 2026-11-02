// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.pragmatica.aether.api.ClusterEvent;
import org.pragmatica.aether.config.StreamingConfig;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.dht.DHTError;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.assertj.core.api.Assertions.assertThat;


/// #2258 NT-126, the C-level clause (review R32-22): a `STREAM_SEGMENT_DHT_FULL` raised while a node is AT its cap reaches
/// the `system:cluster-events` consumer. The delivery path was the open question: the event travels in a stream on the same
/// stack whose own seals need the capped tier, so it could be held or refused exactly when it is needed.
///
/// Three nodes, every node capped at [#CAP_BYTES] (the harness applies one `[streaming]` section to all), the cap filled
/// through the real distributed DHT client until a put fails `ReplicaFull`. Each node raises its own FULL, with the subject
/// `node <id> dht prefix stream-segments`; the test reads the cluster-events log through a node's aggregator (`events()`,
/// the consumer path) and requires one FULL per node, while the cap is still in force.
@PortBudget
class EmberDhtCapFullEventTest {
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
    private static final TimeSpan PUT_BOUND = TimeSpan.timeSpan(30).seconds();
    private static final long LEADER_BUDGET_MS = 90_000L;
    private static final long DELIVERY_BUDGET_MS = 90_000L;
    private static final long CAP_BYTES = 4_096L;
    private static final int VALUE_BYTES = 1_000;
    private static final String FULL_CODE = "stream-segment-dht-full";

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
    void aFullRaisedWhileTheNodesAreAtTheirCap_reachesTheClusterEventsConsumer_onePerNode() {
        cluster = EmberTestPorts.startedCluster(PORTS, this::threeNodes, START_BOUND);
        awaitCondition("a leader is elected and the cluster-events log is readable",
                       LEADER_BUDGET_MS,
                       () -> cluster.currentLeader().isPresent() && eventsReadable());
        var writer = cluster.allNodes().getFirst();
        var refusal = fillUntilRefused(writer);

        assertThat(refusal).as("the cap refuses a client put").isInstanceOf(DHTError.ReplicaFull.class);
        var nodeIds = cluster.allNodes().stream().map(node -> node.self().id()).collect(Collectors.toSet());

        awaitCondition("every node's FULL is in the cluster-events log (nodes: " + nodeIds + ")",
                       DELIVERY_BUDGET_MS,
                       () -> subjectsOfFull(writer).containsAll(subjectsFor(nodeIds)));
        assertThat(fillAttempt(writer, "after-delivery")).as("the nodes were still at their cap when the events were read")
                                                         .isInstanceOf(DHTError.ReplicaFull.class);
    }

    private Cause fillUntilRefused(AetherNode writer) {
        for (var i = 0; i < 20; i++) {
            var failure = fillAttempt(writer, "fill-" + i);

            if (failure != null) {
                return failure;
            }
        }

        throw new AssertionError("no put was refused after 20 x " + VALUE_BYTES + " bytes against a cap of " + CAP_BYTES);
    }

    /// `null` when the put was accepted.
    private Cause fillAttempt(AetherNode writer, String name) {
        var key = ("stream-segments/ember-cap-" + name).getBytes(StandardCharsets.UTF_8);

        return writer.dhtClient()
                     .unwrap()
                     .put(key, new byte[VALUE_BYTES])
                     .await(PUT_BOUND)
                     .fold(cause -> cause, _ -> null);
    }

    private static Set<String> subjectsFor(Set<String> nodeIds) {
        return nodeIds.stream()
                      .map(id -> "node " + id + " dht prefix stream-segments")
                      .collect(Collectors.toSet());
    }

    private static Set<String> subjectsOfFull(AetherNode reader) {
        return reader.eventAggregator()
                     .events()
                     .await()
                     .map(events -> events.stream()
                                          .filter(ClusterEvent.OperatorWarning.class::isInstance)
                                          .map(ClusterEvent.OperatorWarning.class::cast)
                                          .filter(event -> FULL_CODE.equals(event.details().get("code")))
                                          .map(event -> event.details().get("subject"))
                                          .collect(Collectors.toSet()))
                     .or(Set.of());
    }

    private boolean eventsReadable() {
        return cluster.allNodes()
                      .getFirst()
                      .eventAggregator()
                      .events()
                      .await()
                      .isSuccess();
    }

    private EmberCluster threeNodes(int basePort) {
        var built = emberCluster(CLUSTER_SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, "dhtcap");

        built.withDataBaseDir(dataDir);
        built.withStreamingConfig(StreamingConfig.streamingConfig().withDhtSegmentMaxBytes(CAP_BYTES));

        return built;
    }

    @SuppressWarnings("JBCT-EX-01")
    private static void awaitCondition(String what, long budgetMs, java.util.function.BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + budgetMs;

        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }

            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waiting for: " + what, e);
            }
        }

        throw new AssertionError("not within " + budgetMs + "ms: " + what);
    }
}
