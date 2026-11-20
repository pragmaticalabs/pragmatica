package org.pragmatica.cluster.consensus.rabia;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.rabia.infrastructure.TestCluster;
import org.pragmatica.consensus.rabia.infrastructure.TestCluster.StringKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.cluster.net.local.LocalNetwork;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.io.TimeSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.pragmatica.consensus.rabia.infrastructure.TestCluster.StringKey.key;

/// Test Suite 2: Message Semantics
///
/// Tests message-level behaviors like duplication, out-of-order delivery,
/// message loss, and stale-phase redelivery after a crashed node reconnects.
/// The faults are crash faults and message-level faults (duplicate, delay, loss); no arm makes a node
/// send forged or conflicting messages.
public class MessageSemanticsIT {
    private static final Logger log = LoggerFactory.getLogger(MessageSemanticsIT.class);
    private static final int CLUSTER_SIZE = 5;
    private static final int COMMAND_COUNT = 500; // Reduced from 1000 for faster tests
    private static final Duration TIMEOUT = Duration.ofSeconds(120); // Increased timeout

    private TestCluster cluster;

    @BeforeEach
    void setUp() {
        cluster = new TestCluster(CLUSTER_SIZE);
        cluster.awaitStart();
        log.info("Cluster started with {} nodes", CLUSTER_SIZE);
    }

    @AfterEach
    void tearDown() {
        // Reset fault injection to ensure clean state between tests
        if (cluster != null && cluster.network() != null) {
            var injector = cluster.network().getFaultInjector();
            injector.setFault(LocalNetwork.FaultType.MESSAGE_DUPLICATE, false);
            injector.setFault(LocalNetwork.FaultType.MESSAGE_DELAY, false);
            injector.setFault(LocalNetwork.FaultType.MESSAGE_LOSS, false);
            injector.setMessageLossRate(0.0);
        }
    }

    /// Test 2.1: Duplicate PROPOSE broadcasts
    /// Assertion: Replicas ignore/coalesce duplicates; continued progress, and each committed key is applied
    /// exactly once per replica (a key applied twice emits its put notification twice; the stored value alone
    /// cannot tell, a Put being idempotent)
    @Test
    void duplicateProposeBroadcasts() {
        log.info("Starting duplicate PROPOSE broadcasts test");

        // Enable message duplication in the network
        var network = cluster.network();
        network.getFaultInjector().setFault(LocalNetwork.FaultType.MESSAGE_DUPLICATE, true);

        // Use the first node as the client
        var clientNode = cluster.getFirst();

        // Submit a batch of commands and verify they are processed correctly
        var commands = generatePutCommands(COMMAND_COUNT, "duplicate-");
        submitCommands(clientNode, commands);

        // Verify all nodes have a consistent state with all commands
        await().atMost(TIMEOUT)
               .pollInterval(1, TimeUnit.SECONDS)
               .until(() -> cluster.allNodesHaveValue(key("duplicate-" + (COMMAND_COUNT - 1)),
                                                      "value-" + (COMMAND_COUNT - 1)));

        // Verify all commands were executed exactly once
        for (int i = 0; i < COMMAND_COUNT; i++) {
            var k = key("duplicate-" + i);
            var expectedValue = "value-" + i;
            assertTrue(cluster.allNodesHaveValue(k, expectedValue),
                       "All nodes should have key " + k + " with value " + expectedValue);
        }

        // The put notifications are delivered asynchronously: let every store drain before counting.
        await().atMost(TIMEOUT)
               .until(() -> cluster.ids().stream().noneMatch(cluster::hasPendingNotifications));

        for (var id : cluster.ids()) {
            for (int i = 0; i < COMMAND_COUNT; i++) {
                var k = key("duplicate-" + i);

                assertEquals(1, cluster.putCount(id, k), "Node " + id + " must apply " + k + " exactly once");
            }
        }

        // Disable message duplication
        network.getFaultInjector().setFault(LocalNetwork.FaultType.MESSAGE_DUPLICATE, false);
    }

    /// Test 2.2: Out-of-order delivery (COMMIT before PREPARE) at one node
    /// Assertion: Buffering preserves safety
    @Test
    void outOfOrderDelivery() {
        log.info("Starting out-of-order delivery test");

        // Choose a node to receive out-of-order messages
        var targetNode = cluster.ids().get(2);
        log.info("Node {} will receive out-of-order messages", targetNode);

        // We can't directly control message order in the test framework,
        // but we can simulate high network latency which increases the chance
        // of out-of-order delivery
        cluster.network().getFaultInjector().setNodeFault(targetNode,
                                                          LocalNetwork.FaultType.MESSAGE_DELAY,
                                                          true);
        cluster.network().getFaultInjector().messageDelay(TimeSpan.timeSpan(50).millis());

        // Use a different node as the client
        var clientNode = cluster.getFirst();

        // Submit commands
        var commands = generatePutCommands(COMMAND_COUNT, "order-");
        submitCommands(clientNode, commands);

        // Verify all nodes eventually have the consistent state
        await().atMost(TIMEOUT)
               .pollInterval(1, TimeUnit.SECONDS)
               .until(() -> cluster.allNodesHaveValue(key("order-" + (COMMAND_COUNT - 1)),
                                                      "value-" + (COMMAND_COUNT - 1)));

        // Disable message delay
        cluster.network().getFaultInjector().setNodeFault(targetNode,
                                                          LocalNetwork.FaultType.MESSAGE_DELAY,
                                                          false);
    }

    /// Test 2.3: 10% random message loss for 30s
    /// Assertion: Commits proceed; observe latency increase only
    @Test
    void randomMessageLoss() {
        log.info("Starting random message loss test");

        // Configure 10% message loss
        var network = cluster.network();
        network.getFaultInjector().setFault(LocalNetwork.FaultType.MESSAGE_LOSS, true);
        network.getFaultInjector().setMessageLossRate(0.1);

        // Use the first node as the client
        var clientNode = cluster.getFirst();

        // Capture current time for latency measurement
        long startTime = System.currentTimeMillis();

        // Submit commands
        var commands = generatePutCommands(COMMAND_COUNT, "loss-");
        submitCommands(clientNode, commands);

        // Verify all nodes eventually have the consistent state
        await().atMost(TIMEOUT)
               .pollInterval(1, TimeUnit.SECONDS)
               .until(() -> cluster.allNodesHaveValue(key("loss-" + (COMMAND_COUNT - 1)),
                                                      "value-" + (COMMAND_COUNT - 1)));

        // Calculate throughput with message loss
        long endTime = System.currentTimeMillis();
        double durationSeconds = (endTime - startTime) / 1000.0;
        double throughputWithLoss = COMMAND_COUNT / durationSeconds;

        log.info("Throughput with 10% message loss: {} commands/second", throughputWithLoss);

        // Disable message loss
        network.getFaultInjector().setFault(LocalNetwork.FaultType.MESSAGE_LOSS, false);

        // Submit more commands without message loss to compare performance
        startTime = System.currentTimeMillis();
        commands = generatePutCommands(COMMAND_COUNT, "noloss-");
        submitCommands(clientNode, commands);

        await().atMost(TIMEOUT)
               .pollInterval(1, TimeUnit.SECONDS)
               .until(() -> cluster.allNodesHaveValue(key("noloss-" + (COMMAND_COUNT - 1)),
                                                      "value-" + (COMMAND_COUNT - 1)));

        endTime = System.currentTimeMillis();
        durationSeconds = (endTime - startTime) / 1000.0;
        double normalThroughput = COMMAND_COUNT / durationSeconds;

        log.info("Normal throughput: {} commands/second", normalThroughput);
        log.info("Throughput ratio (with loss/normal): {}", throughputWithLoss / normalThroughput);

        // We expect throughput with message loss to be lower, but the system should still function
        assertTrue(throughputWithLoss > 0, "System should continue to make progress with message loss");
    }

    /// Test 2.4: Stale-phase messages after a reconnect
    /// Assertion: a message of an already-decided phase, delivered late to a reconnected node and to the rest of the
    /// cluster, is discarded; the later value of the key is not regressed and the cluster keeps committing.
    /// The stale messages are the proposals and votes recorded at the start (carrying the first value of the key),
    /// replayed after the node crashed, missed phases, and reconnected.
    @Test
    void staleViewNumbers() {
        log.info("Starting stale phase redelivery test");

        var clientNode = cluster.getFirst();
        var laggingNode = cluster.ids().get(1);
        var hot = key("hot");

        cluster.submitAndWait(clientNode, new KVCommand.Put<>(hot, "first"));
        await().atMost(TIMEOUT)
               .pollInterval(100, TimeUnit.MILLISECONDS)
               .until(() -> cluster.allNodesHaveValue(hot, "first"));

        // Everything delivered so far belongs to phases that are decided from here on.
        var stale = cluster.deliveredTo(laggingNode);

        assertFalse(stale.isEmpty(), "The recording must have captured the first phase's messages");

        cluster.disconnect(laggingNode);

        // Advance several phases while the node is away, overwriting the key.
        for (int i = 0; i < 5; i++) {
            cluster.submitAndWait(clientNode, new KVCommand.Put<>(hot, "advanced-" + i));
        }

        await().atMost(TIMEOUT)
               .pollInterval(100, TimeUnit.MILLISECONDS)
               .until(() -> cluster.allNodesHaveValue(hot, "advanced-4"));

        cluster.reconnect(laggingNode);
        cluster.submitAndWait(clientNode, new KVCommand.Put<>(hot, "latest"));
        await().atMost(TIMEOUT)
               .pollInterval(100, TimeUnit.MILLISECONDS)
               .until(() -> cluster.allNodesHaveValue(hot, "latest"));

        // Redeliver the stale messages everywhere; the key must not go back to "first".
        for (var id : cluster.ids()) {
            stale.forEach(message -> cluster.deliver(id, message));
        }

        await().during(Duration.ofSeconds(3))
               .atMost(TIMEOUT)
               .until(() -> cluster.allNodesHaveValue(hot, "latest"));

        // The cluster still commits after the stale traffic.
        cluster.submitAndWait(clientNode, new KVCommand.Put<>(key("after-stale"), "ok"));
        await().atMost(TIMEOUT)
               .pollInterval(100, TimeUnit.MILLISECONDS)
               .until(() -> cluster.allNodesHaveValue(key("after-stale"), "ok"));
    }

    private List<KVCommand<StringKey>> generatePutCommands(int count, String keyPrefix) {
        var commands = new ArrayList<KVCommand<StringKey>>(count);

        for (int i = 0; i < count; i++) {
            commands.add(new KVCommand.Put<>(key(keyPrefix + i), "value-" + i));
        }
        return commands;
    }

    private void submitCommands(NodeId nodeId, List<KVCommand<StringKey>> commands) {
        var counter = new AtomicLong(0);
        var batchSize = 50; // Reduced from 100 for better granularity

        for (int i = 0; i < commands.size(); i += batchSize) {
            int end = Math.min(i + batchSize, commands.size());
            var batch = commands.subList(i, end);

            cluster.engines().get(nodeId)
                   .apply(batch)
                   .onSuccess(_ -> {
                       long completed = counter.addAndGet(batch.size());
                       if (completed % 100 == 0) {
                           log.info("Processed {} commands", completed);
                       }
                   })
                   .onFailure(cause -> log.error("Failed to apply batch: {}", cause.message()));
        }
    }
}
