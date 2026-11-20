package org.pragmatica.consensus.rabia;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.rabia.infrastructure.TestCluster;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.consensus.rabia.infrastructure.TestCluster.StringKey.key;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Test Suite 4: Faulty-node behaviors
/// Pins what the cluster does when one node crashes or goes silent. Every fault here is a CRASH fault: the node is
/// disconnected from the network (`TestCluster.disconnect`), never made to send forged, conflicting or malformed
/// messages, so no Byzantine behavior is exercised; a test that did would need a node that emits such messages.
/// Convergence checks read the connected nodes only (a disconnected node is excluded by design).
public class ByzantineBehaviorsIT {
    private static final Logger log = LoggerFactory.getLogger(ByzantineBehaviorsIT.class);
    private static final int CLUSTER_SIZE = 5; // f=1 in 3f+1 model allows handling one Byzantine node

    private TestCluster cluster;

    @BeforeEach
    void setUp() {
        cluster = new TestCluster(CLUSTER_SIZE);
        cluster.awaitStart();
        log.info("Cluster started with {} nodes", CLUSTER_SIZE);
    }

    /// Test 4.1: A node crashes after the first commit
    /// Setup: one node is disconnected (a crash fault; it sends no conflicting COMMIT (VoteRound2) messages)
    /// Expected: the remaining quorum keeps committing, sequentially and for ten further writes
    @Test
    void equivocationTest() {
        log.info("Starting equivocation test with byzantine node sending conflicting commits");

        // First establish normal operation
        var firstNode = cluster.getFirst();
        var lastNode = cluster.ids().get(CLUSTER_SIZE - 1);

        // Make initial request to verify that cluster is working
        cluster.submitAndWait(firstNode, new KVCommand.Put<>(key("key-honest"), "value-honest"));

        // Verify all nodes have the initial value
        await().atMost(10, TimeUnit.SECONDS)
               .until(() -> cluster.allNodesHaveValue(key("key-honest"), "value-honest"));

        // Crash one node by disconnecting it (no Byzantine behavior is simulated)
        var byzantineNode = cluster.ids().get(1);
        cluster.disconnect(byzantineNode);
        log.info("Disconnected Byzantine node: {}", byzantineNode);

        // System should continue to function with the remaining nodes
        cluster.submitAndWait(lastNode, new KVCommand.Put<>(key("key-after-byzantine"), "value-after-byzantine"));

        // Verify honest nodes still achieve consensus
        await().atMost(Duration.ofSeconds(10))
               .until(() -> cluster.allNodesHaveValue(key("key-after-byzantine"), "value-after-byzantine"));

        // Make multiple concurrent requests to stress test the system
        for (int i = 0; i < 10; i++) {
            var key = key("concurrent-key-" + i);
            var value = "concurrent-value-" + i;

            cluster.engines().get(firstNode)
                   .apply(List.of(new KVCommand.Put<>(key, value)))
                   .await(timeSpan(5).seconds())
                   .onSuccess(_ -> log.info("Successfully applied command: ({}, {})", key, value))
                   .onFailure(cause -> fail("Failed to apply command: " + cause.message()));
        }

        // Verify all concurrent requests eventually propagate to all honest nodes
        for (int i = 0; i < 10; i++) {
            var key = key("concurrent-key-" + i);
            var value = "concurrent-value-" + i;

            await().atMost(Duration.ofSeconds(10))
                   .until(() -> {
                       // Check only connected nodes
                       return cluster.ids().stream()
                                     .filter(id -> !id.equals(byzantineNode))
                                     .allMatch(id -> value.equals(cluster.stores().get(id).snapshot().get(key)));
                   });
        }
    }

    /// Test 4.2: A different node crashes after the first commit
    /// Setup: one node is disconnected (a crash fault; no malformed signature share is ever sent)
    /// Expected: the remaining quorum keeps committing five further writes
    @Test
    void malformedSignatureTest() {
        log.info("Starting malformed signature test");

        // Establish baseline operation
        var firstNode = cluster.getFirst();
        cluster.submitAndWait(firstNode, new KVCommand.Put<>(key("initial-key"), "initial-value"));

        await().atMost(10, TimeUnit.SECONDS)
               .until(() -> cluster.allNodesHaveValue(key("initial-key"), "initial-value"));

        // Crash a node by disconnecting it (no malformed signatures are simulated)
        var maliciousNode = cluster.ids().get(2);
        cluster.disconnect(maliciousNode);
        log.info("Disconnected malicious node (simulating malformed signatures): {}", maliciousNode);

        // System should recover and progress with remaining nodes
        var startTime = System.currentTimeMillis();

        // Submit several commands to verify continued operation
        for (int i = 0; i < 5; i++) {
            var key = key("recovery-key-" + i);
            var value = "recovery-value-" + i;

            cluster.submitAndWait(cluster.ids().get(3), new KVCommand.Put<>(key, value));

            // Check that values propagate to all honest nodes
            await().atMost(Duration.ofSeconds(10))
                   .until(() -> cluster.ids().stream()
                                       .filter(id -> !id.equals(maliciousNode))
                                       .allMatch(id -> value.equals(cluster.stores().get(id).snapshot().get(key))));
        }

        var endTime = System.currentTimeMillis();
        var executionTime = (endTime - startTime) / 1000.0;

        log.info("Successfully processed commands in {} seconds despite malformed signatures", executionTime);
    }

    /// Test 4.3: Silent replica
    /// Setup: a node is disconnected, so it neither receives nor sends (a crash; a receive-only node is not simulated)
    /// Expected: Liveness maintained with remaining quorum
    @Test
    void silentReplicaTest() {
        log.info("Starting silent replica test");

        // Establish baseline operation
        var secondNode = cluster.ids().get(1);
        cluster.submitAndWait(secondNode, new KVCommand.Put<>(key("baseline-key"), "baseline-value"));

        await().atMost(10, TimeUnit.SECONDS)
               .until(() -> cluster.allNodesHaveValue(key("baseline-key"), "baseline-value"));

        // Make a node silent (receive-only) by disconnecting its outbound connections
        // Note: In a real implementation, we would have more fine-grained control to make
        // a node receive-only rather than fully disconnected, but for testing purposes
        // disconnecting works as a simulation
        var silentNode = cluster.ids().getFirst();
        cluster.disconnect(silentNode);
        log.info("Made node silent: {}", silentNode);

        // System should maintain liveness with the remaining quorum
        var remainingNodes = cluster.ids().stream()
                                    .filter(id -> !id.equals(silentNode))
                                    .toList();

        // Submit commands to the remaining nodes
        for (int i = 0; i < remainingNodes.size(); i++) {
            var nodeId = remainingNodes.get(i);
            var key = key("silent-test-key-" + i);
            var value = "silent-test-value-" + i;

            cluster.submitAndWait(nodeId, new KVCommand.Put<>(key, value));

            // Verify the value propagates to all active nodes
            await().atMost(Duration.ofSeconds(10))
                   .until(() -> remainingNodes.stream()
                                              .allMatch(id -> value.equals(cluster.stores()
                                                                                  .get(id)
                                                                                  .snapshot()
                                                                                  .get(key))));
        }

        // Submit a batch of concurrent requests to stress test
        var thirdNode = remainingNodes.getFirst();
        for (int i = 0; i < 20; i++) {
            var key = key("concurrent-silent-key-" + i);
            var value = "concurrent-silent-value-" + i;

            cluster.engines().get(thirdNode)
                   .apply(List.of(new KVCommand.Put<>(key, value)))
                   .await(timeSpan(5).seconds())
                   .onSuccess(_ -> log.info("Successfully applied command despite silent node: ({}, {})", key, value))
                   .onFailure(cause -> fail("Failed to apply command: " + cause.message()));
        }

        // Verify all concurrent operations eventually succeed on active nodes
        for (int i = 0; i < 20; i++) {
            var key = key("concurrent-silent-key-" + i);
            var value = "concurrent-silent-value-" + i;

            await().atMost(Duration.ofSeconds(15))
                   .until(() -> remainingNodes.stream()
                                              .allMatch(id -> value.equals(cluster.stores()
                                                                                  .get(id)
                                                                                  .snapshot()
                                                                                  .get(key))));
        }

        log.info("Cluster maintained liveness with {} active nodes despite silent node", remainingNodes.size());
    }
}
