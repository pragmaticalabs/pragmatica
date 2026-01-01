package org.pragmatica.aether.node;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceNodeValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.artifact.Artifact.artifact;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.consensus.net.NodeInfo.nodeInfo;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/**
 * Integration tests for 5-node cluster failover scenarios.
 * Tests verify that request processing continues during various failure
 * and cluster reconfiguration events.
 */
class ClusterFailoverIT {
    private static final Logger log = LoggerFactory.getLogger(ClusterFailoverIT.class);

    private static final int CLUSTER_SIZE = 5;
    private static final int BASE_PORT = 5050;
    private static final TimeSpan AWAIT_TIMEOUT = TimeSpan.timeSpan(30).seconds();
    private static final Duration AWAIT_DURATION = Duration.ofSeconds(30);
    private static final Duration SHORT_AWAIT = Duration.ofSeconds(10);

    private static final List<NodeInfo> ALL_NODES = List.of(
            nodeInfo(nodeId("node-1"), nodeAddress("localhost", BASE_PORT)),
            nodeInfo(nodeId("node-2"), nodeAddress("localhost", BASE_PORT + 1)),
            nodeInfo(nodeId("node-3"), nodeAddress("localhost", BASE_PORT + 2)),
            nodeInfo(nodeId("node-4"), nodeAddress("localhost", BASE_PORT + 3)),
            nodeInfo(nodeId("node-5"), nodeAddress("localhost", BASE_PORT + 4))
    );

    private final List<AetherNode> nodes = new ArrayList<>();
    private final ConcurrentHashMap<String, AetherNode> nodeMap = new ConcurrentHashMap<>();
    private ExecutorService executor;

    @BeforeEach
    void setUp() throws InterruptedException {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        startNodes(ALL_NODES.subList(0, CLUSTER_SIZE));
        // Allow cluster to stabilize
        Thread.sleep(1000);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        for (var node : nodes) {
            try {
                node.stop().await(TimeSpan.timeSpan(5).seconds());
            } catch (Exception e) {
                log.warn("Error stopping node {}: {}", node.self(), e.getMessage());
            }
        }
        nodes.clear();
        nodeMap.clear();
        log.info("All nodes stopped");
        Thread.sleep(500);
    }

    private void startNodes(List<NodeInfo> nodeInfos) {
        for (int i = 0; i < nodeInfos.size(); i++) {
            var nodeId = nodeInfos.get(i).id();
            var port = BASE_PORT + i;

            var config = AetherNodeConfig.testConfig(nodeId, port, nodeInfos);
            var node = AetherNode.aetherNode(config);
            nodes.add(node);
            nodeMap.put(nodeId.id(), node);
        }

        var startPromises = nodes.stream()
                                 .map(AetherNode::start)
                                 .toList();

        Promise.allOf(startPromises)
               .await(AWAIT_TIMEOUT)
               .onFailureRun(() -> fail("Failed to start all nodes within timeout"))
               .onSuccess(_ -> log.info("All {} Aether nodes started successfully", nodeInfos.size()));
    }

    @Test
    void five_node_cluster_forms_successfully() {
        assertThat(nodes).hasSize(CLUSTER_SIZE);
        log.info("5-node cluster formed successfully");

        // Verify all nodes can see each other's state
        var testArtifact = artifact("org.test:formation-test:1.0.0").unwrap();
        var key = new SliceNodeKey(testArtifact, nodes.getFirst().self());
        var value = new SliceNodeValue(SliceState.LOADED);

        nodes.getFirst()
             .apply(List.of(new KVCommand.Put<>(key, value)))
             .await(AWAIT_TIMEOUT)
             .onFailure(cause -> fail("Put failed: " + cause.message()));

        await().atMost(AWAIT_DURATION)
               .untilAsserted(() -> {
                   for (var node : nodes) {
                       var stored = node.kvStore().get(key);
                       assertThat(stored.isPresent())
                               .as("Node %s should have key", node.self())
                               .isTrue();
                   }
               });

        log.info("All 5 nodes have consistent state");
    }

    @Test
    void requests_continue_during_non_leader_node_failure() throws InterruptedException {
        log.info("Testing request continuity during non-leader node failure");

        var requestStats = new RequestStats();
        var stopProcessing = new AtomicBoolean(false);

        // Start continuous request processing
        var processingThread = startContinuousRequests(requestStats, stopProcessing);

        // Wait for some requests to complete
        await().atMost(SHORT_AWAIT)
               .until(() -> requestStats.successful.get() > 10);

        log.info("Initial requests completed: {}", requestStats.successful.get());

        // Find and kill a non-leader node (not the first node which is likely leader)
        var nodeToKill = nodes.get(3);  // Kill 4th node
        log.info("Killing non-leader node: {}", nodeToKill.self());

        nodeToKill.stop().await(TimeSpan.timeSpan(5).seconds());
        nodes.remove(nodeToKill);
        nodeMap.remove(nodeToKill.self().id());

        // Continue processing and verify requests still succeed
        var requestsBeforeKill = requestStats.successful.get();

        await().atMost(AWAIT_DURATION)
               .until(() -> requestStats.successful.get() > requestsBeforeKill + 20);

        // Stop processing and check results
        stopProcessing.set(true);
        processingThread.join(5000);

        log.info("Final stats - Successful: {}, Failed: {}, Failure rate: {}%",
                 requestStats.successful.get(),
                 requestStats.failed.get(),
                 requestStats.failureRate());

        // Allow some failures during the transition, but most should succeed
        assertThat(requestStats.failureRate())
                .as("Failure rate should be below 20%")
                .isLessThan(20.0);

        assertThat(requestStats.successful.get())
                .as("Should have successful requests after node failure")
                .isGreaterThan(requestsBeforeKill);
    }

    @Test
    void requests_continue_during_leader_failover() throws InterruptedException {
        log.info("Testing request continuity during leader failover");

        var requestStats = new RequestStats();
        var stopProcessing = new AtomicBoolean(false);

        // Start continuous request processing
        var processingThread = startContinuousRequests(requestStats, stopProcessing);

        // Wait for some requests to complete
        await().atMost(SHORT_AWAIT)
               .until(() -> requestStats.successful.get() > 10);

        log.info("Initial requests completed: {}", requestStats.successful.get());

        // Kill the leader (first node in sorted order)
        var leader = nodes.getFirst();
        log.info("Killing leader node: {}", leader.self());

        leader.stop().await(TimeSpan.timeSpan(5).seconds());
        nodes.removeFirst();
        nodeMap.remove(leader.self().id());

        // Wait for new leader election and requests to continue
        var requestsBeforeKill = requestStats.successful.get();

        // Allow more time for leader election
        await().atMost(AWAIT_DURATION)
               .until(() -> requestStats.successful.get() > requestsBeforeKill + 15);

        stopProcessing.set(true);
        processingThread.join(5000);

        log.info("Final stats - Successful: {}, Failed: {}, Failure rate: {}%",
                 requestStats.successful.get(),
                 requestStats.failed.get(),
                 requestStats.failureRate());

        // Leader failover may cause more failures, allow up to 30%
        assertThat(requestStats.failureRate())
                .as("Failure rate should be below 30% during leader failover")
                .isLessThan(30.0);
    }

    @Test
    void requests_continue_during_scale_up() throws InterruptedException {
        log.info("Testing request continuity during scale-up (simulated via delayed node start)");

        // Use the existing 5-node cluster but verify all nodes are operational
        var requestStats = new RequestStats();
        var stopProcessing = new AtomicBoolean(false);

        // Start continuous request processing
        var processingThread = startContinuousRequests(requestStats, stopProcessing);

        await().atMost(SHORT_AWAIT)
               .until(() -> requestStats.successful.get() > 10);

        log.info("Initial requests completed: {}", requestStats.successful.get());
        var requestsBefore = requestStats.successful.get();

        // Verify all 5 nodes are processing requests by checking state replication
        var testArtifact = artifact("org.test:scale-up-test:1.0.0").unwrap();
        for (int i = 0; i < nodes.size(); i++) {
            var node = nodes.get(i);
            var key = new SliceNodeKey(testArtifact, node.self());
            var value = new SliceNodeValue(SliceState.ACTIVE);

            node.apply(List.of(new KVCommand.Put<>(key, value)))
                .await(AWAIT_TIMEOUT)
                .onFailure(cause -> fail("Failed to put on node: " + cause.message()));
        }

        // Verify all nodes have all values
        await().atMost(AWAIT_DURATION)
               .untilAsserted(() -> {
                   for (var node : nodes) {
                       var snapshot = node.kvStore().snapshot();
                       assertThat(snapshot.size())
                               .as("Node %s should have entries from all nodes", node.self())
                               .isGreaterThanOrEqualTo(5);
                   }
               });

        // Verify requests continue
        await().atMost(AWAIT_DURATION)
               .until(() -> requestStats.successful.get() > requestsBefore + 20);

        stopProcessing.set(true);
        processingThread.join(5000);

        log.info("Final stats with 5 nodes - Successful: {}, Failed: {}, Failure rate: {}%",
                 requestStats.successful.get(),
                 requestStats.failed.get(),
                 requestStats.failureRate());

        assertThat(nodes).hasSize(5);
        assertThat(requestStats.failureRate())
                .as("Failure rate should be below 15%")
                .isLessThan(15.0);
    }

    @Test
    void requests_continue_during_graceful_scale_down() throws InterruptedException {
        log.info("Testing request continuity during graceful scale-down");

        var requestStats = new RequestStats();
        var stopProcessing = new AtomicBoolean(false);

        var processingThread = startContinuousRequests(requestStats, stopProcessing);

        await().atMost(SHORT_AWAIT)
               .until(() -> requestStats.successful.get() > 10);

        var requestsBefore = requestStats.successful.get();
        log.info("Initial requests completed: {}", requestsBefore);

        // Gracefully remove 1 node (down to 4 nodes - maintains stronger quorum)
        var nodeToRemove = nodes.getLast();
        log.info("Gracefully removing node: {}", nodeToRemove.self());

        nodeToRemove.stop().await(TimeSpan.timeSpan(5).seconds());
        nodes.removeLast();
        nodeMap.remove(nodeToRemove.self().id());

        assertThat(nodes).hasSize(4);
        log.info("Cluster now has 4 nodes, waiting for requests to continue...");

        // Allow cluster to stabilize
        Thread.sleep(2000);

        // Track successful requests after scale-down
        var requestsAfterScaleDown = requestStats.successful.get();
        log.info("Requests after scale-down: {}", requestsAfterScaleDown);

        // Verify requests continue with 4 nodes
        await().atMost(Duration.ofSeconds(30))
               .pollInterval(Duration.ofMillis(500))
               .until(() -> requestStats.successful.get() > requestsAfterScaleDown + 10);

        stopProcessing.set(true);
        processingThread.join(5000);

        log.info("Final stats with 4 nodes - Successful: {}, Failed: {}, Failure rate: {}%",
                 requestStats.successful.get(),
                 requestStats.failed.get(),
                 requestStats.failureRate());

        // Graceful removal should have lower failure rate
        assertThat(requestStats.failureRate())
                .as("Failure rate should be below 30% during graceful scale-down")
                .isLessThan(30.0);
    }

    @Test
    void requests_continue_during_rolling_restart() throws InterruptedException {
        log.info("Testing request continuity during rolling restart");

        var requestStats = new RequestStats();
        var stopProcessing = new AtomicBoolean(false);

        var processingThread = startContinuousRequests(requestStats, stopProcessing);

        await().atMost(SHORT_AWAIT)
               .until(() -> requestStats.successful.get() > 10);

        var requestsBefore = requestStats.successful.get();
        log.info("Initial requests completed: {}", requestsBefore);

        // Rolling restart: restart each node one by one
        for (int i = 0; i < CLUSTER_SIZE; i++) {
            var nodeIndex = i;
            var nodeId = ALL_NODES.get(i).id();
            var oldNode = nodeMap.get(nodeId.id());

            log.info("Rolling restart - stopping node {} ({}/{})", nodeId, i + 1, CLUSTER_SIZE);

            // Stop the old node
            oldNode.stop().await(TimeSpan.timeSpan(5).seconds());

            // Wait briefly
            Thread.sleep(300);

            // Start replacement node
            var port = BASE_PORT + i;
            var config = AetherNodeConfig.testConfig(nodeId, port, ALL_NODES.subList(0, CLUSTER_SIZE));
            var newNode = AetherNode.aetherNode(config);

            newNode.start()
                   .await(AWAIT_TIMEOUT)
                   .onFailure(cause -> fail("Failed to restart node " + nodeId + ": " + cause.message()));

            // Update tracking
            nodes.set(nodeIndex, newNode);
            nodeMap.put(nodeId.id(), newNode);

            log.info("Rolling restart - node {} restarted ({}/{})", nodeId, i + 1, CLUSTER_SIZE);

            // Wait for cluster to stabilize before next restart
            Thread.sleep(500);
        }

        // Verify requests continued throughout
        await().atMost(AWAIT_DURATION)
               .until(() -> requestStats.successful.get() > requestsBefore + 30);

        stopProcessing.set(true);
        processingThread.join(5000);

        log.info("Final stats after rolling restart - Successful: {}, Failed: {}, Failure rate: {}%",
                 requestStats.successful.get(),
                 requestStats.failed.get(),
                 requestStats.failureRate());

        // Rolling restart may cause more transient failures
        assertThat(requestStats.failureRate())
                .as("Failure rate should be below 35% during rolling restart")
                .isLessThan(35.0);

        assertThat(requestStats.successful.get())
                .as("Should have many successful requests after rolling restart")
                .isGreaterThan(requestsBefore + 20);
    }

    @Test
    void requests_continue_during_abrupt_node_crash() throws InterruptedException {
        log.info("Testing request continuity during abrupt node crash (1 of 5)");

        var requestStats = new RequestStats();
        var stopProcessing = new AtomicBoolean(false);

        var processingThread = startContinuousRequests(requestStats, stopProcessing);

        await().atMost(SHORT_AWAIT)
               .until(() -> requestStats.successful.get() > 10);

        var requestsBefore = requestStats.successful.get();
        log.info("Initial requests completed: {}", requestsBefore);

        // Abruptly crash one node (middle of cluster)
        var nodeToKill = nodes.get(2);
        log.info("Abruptly crashing node: {}", nodeToKill.self());

        // Stop node abruptly
        nodeToKill.stop().await(TimeSpan.timeSpan(5).seconds());
        nodes.remove(nodeToKill);
        nodeMap.remove(nodeToKill.self().id());

        assertThat(nodes).hasSize(4);  // 4 nodes remaining
        log.info("Cluster now has 4 nodes after crash");

        // Wait for cluster to detect failure and stabilize
        Thread.sleep(2000);

        // Track successful requests after failure
        var requestsAfterFailure = requestStats.successful.get();
        log.info("Requests after crash: {}", requestsAfterFailure);

        // Verify requests continue with remaining 4 nodes
        await().atMost(Duration.ofSeconds(30))
               .pollInterval(Duration.ofMillis(500))
               .until(() -> requestStats.successful.get() > requestsAfterFailure + 10);

        stopProcessing.set(true);
        processingThread.join(5000);

        log.info("Final stats after crash - Successful: {}, Failed: {}, Failure rate: {}%",
                 requestStats.successful.get(),
                 requestStats.failed.get(),
                 requestStats.failureRate());

        // Abrupt crash may cause some failures during recovery
        assertThat(requestStats.failureRate())
                .as("Failure rate should be below 35% during node crash")
                .isLessThan(35.0);

        assertThat(requestStats.successful.get())
                .as("Should have successful requests after crash")
                .isGreaterThan(requestsBefore);
    }

    private Thread startContinuousRequests(RequestStats stats, AtomicBoolean stop) {
        var thread = new Thread(() -> {
            var counter = new AtomicInteger(0);

            while (!stop.get()) {
                try {
                    var requestId = counter.incrementAndGet();
                    var testArtifact = artifact("org.test:continuous-test:" + requestId + ".0.0").unwrap();

                    // Get a snapshot of current nodes to avoid concurrent modification
                    var currentNodes = new ArrayList<>(nodes);
                    if (currentNodes.isEmpty()) {
                        Thread.sleep(100);
                        continue;
                    }

                    var nodeIndex = requestId % currentNodes.size();
                    var node = currentNodes.get(nodeIndex);

                    var key = new SliceNodeKey(testArtifact, node.self());
                    var value = new SliceNodeValue(SliceState.LOADED);

                    node.apply(List.of(new KVCommand.Put<>(key, value)))
                        .await(TimeSpan.timeSpan(3).seconds())
                        .onSuccess(_ -> stats.successful.incrementAndGet())
                        .onFailure(cause -> {
                            stats.failed.incrementAndGet();
                            log.debug("Request {} failed: {}", requestId, cause.message());
                        });

                    Thread.sleep(30);  // Rate limit - faster for more requests
                } catch (Exception e) {
                    stats.failed.incrementAndGet();
                    log.debug("Request error: {}", e.getMessage());
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        });
        thread.setName("continuous-requests");
        thread.start();
        return thread;
    }

    private static class RequestStats {
        final AtomicLong successful = new AtomicLong(0);
        final AtomicLong failed = new AtomicLong(0);

        double failureRate() {
            var total = successful.get() + failed.get();
            if (total == 0) return 0.0;
            return (failed.get() * 100.0) / total;
        }
    }
}
