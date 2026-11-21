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
import org.pragmatica.consensus.rabia.Phase;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    /// Assertion: a node crashes, misses phases and reconnects; its own proposal and votes of the first phase, held back
    /// until after the reconnect, then reach a healthy node. Each one is answered by sending the reconnected node the
    /// decision of THAT phase (its phase and its value; a replay of the latest phase does not count), the later value of
    /// the key is not regressed, and the cluster keeps committing. That a stale ballot is not processed as live is pinned
    /// per ballot type, on the slot's own state, by `RabiaStalePhaseGuardsTest` in the consensus module.
    @Test
    void staleViewNumbers() {
        log.info("Starting stale phase redelivery test");

        var clientNode = cluster.getFirst();
        var laggingNode = cluster.ids().get(1);
        var healthyNode = cluster.ids().get(2);
        var peer = cluster.ids().get(3);
        var hot = key("hot");

        cluster.submitAndWait(clientNode, new KVCommand.Put<>(hot, "first"));
        await().atMost(TIMEOUT)
               .pollInterval(100, TimeUnit.MILLISECONDS)
               .until(() -> cluster.allNodesHaveValue(hot, "first"));
        await().atMost(TIMEOUT)
               .until(() -> ballotsSentBy(laggingNode, healthyNode).size() == 3);

        // The lagging node's own ballots, held back: everything recorded so far belongs to phases decided from here on.
        var stale = ballotsSentBy(laggingNode, healthyNode);

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

        // Each stale ballot must be answered by sending the reconnected sender the decision of the ballot's own phase.
        // Voters do not send each other their decisions, so the value is checked against a second replica's replay of
        // the same phase: two replicas that decided the phase must replay the same value.
        for (var ballot : stale) {
            var phase = phaseOf(ballot);
            var replay = replayedDecision(healthyNode, laggingNode, ballot, phase);
            var reference = replayedDecision(peer, laggingNode, ballot, phase);

            assertEquals(reference.stateValue(), replay.stateValue(), "replayed decision of phase " + phase);
            assertEquals(reference.value(), replay.value(), "replayed decision of phase " + phase);
        }

        // Redeliver the stale ballots to every other node; the key must not go back to "first".
        for (var id : cluster.ids()) {
            if (!id.equals(laggingNode)) {
                stale.forEach(ballot -> cluster.deliver(id, ballot));
            }
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

    /// The first proposal, first round-1 vote and first round-2 vote that `sender` sent to `receiver`, as recorded by
    /// the delivery log (a node's own broadcasts are not delivered back to it, so the sender and receiver differ).
    private List<RabiaProtocolMessage> ballotsSentBy(NodeId sender, NodeId receiver) {
        var own = cluster.deliveredTo(receiver)
                         .stream()
                         .filter(message -> message.sender().equals(sender))
                         .toList();

        return Stream.of(Synchronous.Propose.class, Synchronous.VoteRound1.class, Synchronous.VoteRound2.class)
                     .flatMap(type -> own.stream().filter(type::isInstance).limit(1))
                     .toList();
    }

    private static Phase phaseOf(RabiaProtocolMessage ballot) {
        return switch (ballot) {
            case Synchronous.Propose<?> propose -> propose.phase();
            case Synchronous.VoteRound1 vote -> vote.phase();
            case Synchronous.VoteRound2 vote -> vote.phase();
            default -> throw new IllegalArgumentException("Not a ballot: " + ballot);
        };
    }

    /// Hands `ballot` to `replica` and waits for the decision of `phase` that `replica` sends back to `sender`.
    private Synchronous.Decision<?> replayedDecision(NodeId replica, NodeId sender, RabiaProtocolMessage ballot, Phase phase) {
        var before = decisionsOf(sender, replica, phase).size();

        cluster.deliver(replica, ballot);
        await().atMost(Duration.ofSeconds(10))
               .until(() -> decisionsOf(sender, replica, phase).size() > before);

        return decisionsOf(sender, replica, phase).getLast();
    }

    /// The decisions of `phase` that `sender` sent to `receiver`, in delivery order.
    private List<Synchronous.Decision<?>> decisionsOf(NodeId receiver, NodeId sender, Phase phase) {
        return cluster.deliveredTo(receiver)
                      .stream()
                      .filter(message -> message.sender().equals(sender))
                      .filter(message -> message instanceof Synchronous.Decision<?>)
                      .<Synchronous.Decision<?>>map(message -> (Synchronous.Decision<?>) message)
                      .filter(decision -> decision.phase().equals(phase))
                      .toList();
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
