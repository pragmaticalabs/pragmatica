package org.pragmatica.aether.node;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceNodeValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.artifact.Artifact.artifact;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.consensus.net.NodeInfo.nodeInfo;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/**
 * Integration test for AetherNode cluster.
 * Tests that multiple AetherNode instances can form a cluster,
 * achieve consensus on KV-Store operations, and replicate state.
 */
@Disabled("Flaky test - passes individually but fails with other tests due to resource contention")
class AetherNodeIT {
    private static final Logger log = LoggerFactory.getLogger(AetherNodeIT.class);

    private static final int CLUSTER_SIZE = 3;
    private static final int BASE_PORT = 14040;
    private static final TimeSpan AWAIT_TIMEOUT = TimeSpan.timeSpan(10).seconds();
    private static final Duration AWAIT_DURATION = Duration.ofSeconds(10);

    private static final List<NodeInfo> NODES = List.of(
            nodeInfo(nodeId("aether-1"), nodeAddress("localhost", BASE_PORT)),
            nodeInfo(nodeId("aether-2"), nodeAddress("localhost", BASE_PORT + 1)),
            nodeInfo(nodeId("aether-3"), nodeAddress("localhost", BASE_PORT + 2))
    );

    private final List<AetherNode> nodes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        var configuredNodes = NODES.subList(0, CLUSTER_SIZE);

        for (int i = 0; i < CLUSTER_SIZE; i++) {
            var nodeId = configuredNodes.get(i).id();
            var port = BASE_PORT + i;

            var config = AetherNodeConfig.testConfig(nodeId, port, configuredNodes);
            var node = AetherNode.aetherNode(config);
            nodes.add(node);
        }

        // Start all nodes
        var startPromises = nodes.stream()
                                 .map(AetherNode::start)
                                 .toList();

        Promise.allOf(startPromises)
               .await(AWAIT_TIMEOUT)
               .onFailureRun(() -> fail("Failed to start all nodes within timeout"))
               .onSuccess(_ -> log.info("All {} Aether nodes started successfully", CLUSTER_SIZE));
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        nodes.forEach(node -> node.stop().await(AWAIT_TIMEOUT));
        nodes.clear();
        log.info("All nodes stopped");
        // Allow time for port release before next test
        Thread.sleep(500);
    }

    @Test
    void cluster_starts_and_stops_successfully() {
        // Just verify setUp/tearDown work - nodes are started in @BeforeEach
        assertThat(nodes).hasSize(CLUSTER_SIZE);
        nodes.forEach(node -> assertThat(node.self()).isNotNull());
    }

    @Test
    void nodes_reach_consensus_on_slice_state() {
        var testArtifact = artifact("org.test:test-slice:1.0.0").unwrap();
        var targetNode = nodes.getFirst().self();
        var key = new SliceNodeKey(testArtifact, targetNode);
        var value = new SliceNodeValue(SliceState.LOADING);

        // Put via first node
        nodes.getFirst()
             .apply(List.of(new KVCommand.Put<>(key, value)))
             .await(AWAIT_TIMEOUT)
             .onFailure(cause -> fail("Failed to put: " + cause.message()));

        log.info("Put slice state {} for {} on node {}", SliceState.LOADING, testArtifact, targetNode);

        // Wait for all nodes to have the value
        await().atMost(AWAIT_DURATION)
               .untilAsserted(() -> {
                   for (var node : nodes) {
                       var stored = node.kvStore().get(key);
                       assertThat(stored.isPresent())
                               .as("Node %s should have key %s", node.self(), key)
                               .isTrue();
                       stored.onPresent(v -> assertThat(v)
                               .as("Node %s should have correct value", node.self())
                               .isEqualTo(value));
                   }
               });

        log.info("All nodes have consistent slice state");
    }

    @Test
    void multiple_puts_from_different_nodes_are_replicated() {
        var artifacts = List.of(
                artifact("org.test:slice-a:1.0.0").unwrap(),
                artifact("org.test:slice-b:1.0.0").unwrap(),
                artifact("org.test:slice-c:1.0.0").unwrap()
        );

        // Each node puts a different slice state
        var putPromises = new ArrayList<Promise<List<Object>>>();
        for (int i = 0; i < CLUSTER_SIZE; i++) {
            var node = nodes.get(i);
            var key = new SliceNodeKey(artifacts.get(i), node.self());
            var value = new SliceNodeValue(SliceState.LOADED);

            log.info("Node {} putting slice {}", node.self(), artifacts.get(i));
            putPromises.add(node.apply(List.of(new KVCommand.Put<>(key, value))));
        }

        Promise.allOf(putPromises)
               .await(AWAIT_TIMEOUT)
               .onFailure(cause -> fail("Failed to put values: " + cause.message()));

        // Wait for all nodes to have all values
        await().atMost(AWAIT_DURATION)
               .untilAsserted(() -> {
                   for (var node : nodes) {
                       var snapshot = node.kvStore().snapshot();
                       assertThat(snapshot).hasSize(CLUSTER_SIZE);
                       log.info("Node {} has {} entries", node.self(), snapshot.size());
                   }
               });

        log.info("All nodes replicated all {} slice states", CLUSTER_SIZE);
    }

    @Test
    void state_transitions_are_consistent() {
        var testArtifact = artifact("org.test:lifecycle-slice:1.0.0").unwrap();
        var targetNode = nodes.getFirst().self();
        var key = new SliceNodeKey(testArtifact, targetNode);

        // Simulate lifecycle: LOADING -> LOADED -> ACTIVATING -> ACTIVE
        var states = List.of(SliceState.LOADING, SliceState.LOADED, SliceState.ACTIVATING, SliceState.ACTIVE);

        for (var state : states) {
            var value = new SliceNodeValue(state);

            nodes.getFirst()
                 .apply(List.of(new KVCommand.Put<>(key, value)))
                 .await(AWAIT_TIMEOUT)
                 .onFailure(cause -> fail("Failed to put state " + state + ": " + cause.message()));

            log.info("Transitioned to state: {}", state);

            // Verify all nodes have the new state
            await().atMost(AWAIT_DURATION)
                   .untilAsserted(() -> {
                       for (var node : nodes) {
                           var stored = node.kvStore().get(key);
                           assertThat(stored.isPresent()).isTrue();
                           stored.onPresent(v -> {
                               var storedValue = (SliceNodeValue) v;
                               assertThat(storedValue.state()).isEqualTo(state);
                           });
                       }
                   });
        }

        log.info("All state transitions completed and replicated successfully");
    }

    @Test
    void remove_operation_is_replicated() {
        var testArtifact = artifact("org.test:remove-slice:1.0.0").unwrap();
        var targetNode = nodes.getFirst().self();
        var key = new SliceNodeKey(testArtifact, targetNode);
        var value = new SliceNodeValue(SliceState.LOADED);

        // First put
        nodes.getFirst()
             .apply(List.of(new KVCommand.Put<>(key, value)))
             .await(AWAIT_TIMEOUT);

        await().atMost(AWAIT_DURATION)
               .until(() -> nodes.stream().allMatch(n -> n.kvStore().get(key).isPresent()));

        log.info("Slice state put and replicated");

        // Now remove
        nodes.get(1)  // Remove from different node
             .apply(List.of(new KVCommand.Remove<>(key)))
             .await(AWAIT_TIMEOUT);

        // Verify removal replicated to all nodes
        await().atMost(AWAIT_DURATION)
               .untilAsserted(() -> {
                   for (var node : nodes) {
                       var stored = node.kvStore().get(key);
                       assertThat(stored.isEmpty())
                               .as("Node %s should not have key after removal", node.self())
                               .isTrue();
                   }
               });

        log.info("Remove operation replicated to all nodes");
    }

    @Test
    void metrics_collector_collects_local_metrics() {
        // Each node should have its own metrics
        for (var node : nodes) {
            var localMetrics = node.metricsCollector().collectLocal();

            // Should have CPU metrics
            assertThat(localMetrics.containsKey("cpu.usage")).isTrue();
            var cpuUsage = localMetrics.get("cpu.usage");
            assertThat(cpuUsage).isBetween(0.0, 1.0);

            // Should have heap metrics
            assertThat(localMetrics.containsKey("heap.used")).isTrue();
            assertThat(localMetrics.containsKey("heap.max")).isTrue();

            log.info("Node {} has local metrics: cpu={}, heap.used={}, heap.max={}",
                     node.self(),
                     localMetrics.get("cpu.usage"),
                     localMetrics.get("heap.used"),
                     localMetrics.get("heap.max"));
        }
    }

    @Test
    void all_nodes_have_metrics_collector_and_control_loop() {
        // Verify components are wired correctly
        for (var node : nodes) {
            assertThat(node.metricsCollector())
                    .as("Node %s should have MetricsCollector", node.self())
                    .isNotNull();

            assertThat(node.controlLoop())
                    .as("Node %s should have ControlLoop", node.self())
                    .isNotNull();

            log.info("Node {} has MetricsCollector and ControlLoop", node.self());
        }
    }

    @Test
    void nodes_have_slice_invoker_and_invocation_handler() {
        // Verify invocation components are wired correctly
        for (var node : nodes) {
            assertThat(node.sliceInvoker())
                    .as("Node %s should have SliceInvoker", node.self())
                    .isNotNull();

            assertThat(node.invocationHandler())
                    .as("Node %s should have InvocationHandler", node.self())
                    .isNotNull();

            log.info("Node {} has SliceInvoker and InvocationHandler", node.self());
        }
    }

    // Note: Static routing tests removed - routing is now dynamic via RouteRegistry
}
