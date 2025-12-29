package org.pragmatica.aether.metrics.deployment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.cluster.consensus.ProtocolMessage;
import org.pragmatica.cluster.leader.LeaderNotification;
import org.pragmatica.cluster.metrics.DeploymentMetricsMessage.DeploymentMetricsPing;
import org.pragmatica.cluster.net.ClusterNetwork;
import org.pragmatica.cluster.net.NetworkManagementOperation;
import org.pragmatica.cluster.net.NetworkMessage;
import org.pragmatica.cluster.net.NodeId;
import org.pragmatica.cluster.topology.TopologyChangeNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.message.MessageRouter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentMetricsSchedulerTest {

    private NodeId self;
    private NodeId node2;
    private NodeId node3;
    private TestClusterNetwork network;
    private TestDeploymentMetricsCollector collector;
    private DeploymentMetricsScheduler scheduler;

    @BeforeEach
    void setUp() {
        self = NodeId.randomNodeId();
        node2 = NodeId.randomNodeId();
        node3 = NodeId.randomNodeId();
        network = new TestClusterNetwork();
        collector = new TestDeploymentMetricsCollector();
        // Use short interval for tests
        scheduler = DeploymentMetricsScheduler.deploymentMetricsScheduler(self, network, collector, 50);
    }

    @AfterEach
    void tearDown() {
        scheduler.stop();
    }

    // === Leader Change Tests ===

    @Test
    void scheduler_starts_pinging_when_becoming_leader() {
        // Setup topology
        scheduler.onTopologyChange(TopologyChangeNotification.nodeAdded(node2, List.of(self, node2)));

        // Become leader
        scheduler.onLeaderChange(LeaderNotification.leaderChange(Option.option(self), true));

        // Wait for ping to be sent
        waitUntil(() -> !network.sentMessages.isEmpty(), 500);

        assertThat(network.sentMessages).isNotEmpty();
        var sentMessage = network.sentMessages.getFirst();
        assertThat(sentMessage.target()).isEqualTo(node2);
        assertThat(sentMessage.message()).isInstanceOf(DeploymentMetricsPing.class);
    }

    @Test
    void scheduler_stops_pinging_when_losing_leadership() {
        // Setup topology and become leader
        scheduler.onTopologyChange(TopologyChangeNotification.nodeAdded(node2, List.of(self, node2)));
        scheduler.onLeaderChange(LeaderNotification.leaderChange(Option.option(self), true));

        // Wait for initial ping
        waitUntil(() -> !network.sentMessages.isEmpty(), 500);

        // Lose leadership
        scheduler.onLeaderChange(LeaderNotification.leaderChange(Option.option(node2), false));

        // Clear and wait - no new pings should arrive
        network.sentMessages.clear();
        sleep(100);

        assertThat(network.sentMessages).isEmpty();
    }

    @Test
    void scheduler_does_not_ping_before_becoming_leader() {
        // Setup topology but don't become leader
        scheduler.onTopologyChange(TopologyChangeNotification.nodeAdded(node2, List.of(self, node2)));

        sleep(100);

        assertThat(network.sentMessages).isEmpty();
    }

    // === Topology Change Tests ===

    @Test
    void scheduler_tracks_topology_changes() {
        // Become leader first
        scheduler.onLeaderChange(LeaderNotification.leaderChange(Option.option(self), true));

        // Add nodes via topology changes
        scheduler.onTopologyChange(TopologyChangeNotification.nodeAdded(node2, List.of(self, node2)));
        scheduler.onTopologyChange(TopologyChangeNotification.nodeAdded(node3, List.of(self, node2, node3)));

        // Wait for pings
        waitUntil(() -> network.sentMessages.size() >= 2, 500);

        // Should have pinged both nodes
        var targets = network.sentMessages.stream()
                                         .map(SentMessage::target)
                                         .distinct()
                                         .toList();
        assertThat(targets).containsExactlyInAnyOrder(node2, node3);
    }

    @Test
    void scheduler_removes_node_from_topology_on_nodeRemoved() {
        // Setup: leader with 2 other nodes
        scheduler.onTopologyChange(TopologyChangeNotification.nodeAdded(node2, List.of(self, node2)));
        scheduler.onTopologyChange(TopologyChangeNotification.nodeAdded(node3, List.of(self, node2, node3)));
        scheduler.onLeaderChange(LeaderNotification.leaderChange(Option.option(self), true));

        // Wait for initial pings
        waitUntil(() -> network.sentMessages.size() >= 2, 500);

        // Clear messages
        network.sentMessages.clear();

        // Remove node3
        scheduler.onTopologyChange(TopologyChangeNotification.nodeRemoved(node3, List.of(self, node2)));

        // Wait for next ping round
        waitUntil(() -> !network.sentMessages.isEmpty(), 500);

        // Should only ping node2 (not node3)
        var targets = network.sentMessages.stream()
                                         .map(SentMessage::target)
                                         .distinct()
                                         .toList();
        assertThat(targets).containsOnly(node2);
    }

    @Test
    void scheduler_does_not_ping_self() {
        // Become leader with only self in topology
        scheduler.onTopologyChange(TopologyChangeNotification.nodeAdded(self, List.of(self)));
        scheduler.onLeaderChange(LeaderNotification.leaderChange(Option.option(self), true));

        sleep(100);

        // Should not have sent any pings (only self in topology)
        assertThat(network.sentMessages).isEmpty();
    }

    @Test
    void scheduler_does_not_ping_with_empty_topology() {
        // Become leader without adding topology
        scheduler.onLeaderChange(LeaderNotification.leaderChange(Option.option(self), true));

        sleep(100);

        // Should not have sent any pings (empty topology)
        assertThat(network.sentMessages).isEmpty();
    }

    // === Leader Failover Tests ===

    @Test
    void scheduler_restarts_pinging_after_regaining_leadership() {
        // Setup topology
        scheduler.onTopologyChange(TopologyChangeNotification.nodeAdded(node2, List.of(self, node2)));

        // Become leader
        scheduler.onLeaderChange(LeaderNotification.leaderChange(Option.option(self), true));

        // Wait for ping
        waitUntil(() -> !network.sentMessages.isEmpty(), 500);

        // Lose leadership
        scheduler.onLeaderChange(LeaderNotification.leaderChange(Option.option(node2), false));

        // Clear and verify no pings
        network.sentMessages.clear();
        sleep(100);
        assertThat(network.sentMessages).isEmpty();

        // Regain leadership
        scheduler.onLeaderChange(LeaderNotification.leaderChange(Option.option(self), true));

        // Wait for new pings
        waitUntil(() -> !network.sentMessages.isEmpty(), 500);

        assertThat(network.sentMessages).isNotEmpty();
    }

    // === Utilities ===

    private void waitUntil(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            sleep(10);
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // === Test Doubles ===

    record SentMessage(NodeId target, Object message) {}

    static class TestClusterNetwork implements ClusterNetwork {
        final List<SentMessage> sentMessages = new CopyOnWriteArrayList<>();

        @Override
        public <M extends ProtocolMessage> void send(NodeId target, M message) {
            sentMessages.add(new SentMessage(target, message));
        }

        @Override
        public <M extends ProtocolMessage> void broadcast(M message) {}

        @Override
        public void connect(NetworkManagementOperation.ConnectNode connectNode) {}

        @Override
        public void disconnect(NetworkManagementOperation.DisconnectNode disconnectNode) {}

        @Override
        public void listNodes(NetworkManagementOperation.ListConnectedNodes listConnectedNodes) {}

        @Override
        public void handlePing(NetworkMessage.Ping ping) {}

        @Override
        public void handlePong(NetworkMessage.Pong pong) {}

        @Override
        public Promise<Unit> start() {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.success(Unit.unit());
        }

        @Override
        public void configure(MessageRouter.MutableRouter router) {}
    }

    static class TestDeploymentMetricsCollector implements DeploymentMetricsCollector {
        @Override
        public void onDeploymentStarted(DeploymentEvent.DeploymentStarted event) {}

        @Override
        public void onStateTransition(DeploymentEvent.StateTransition event) {}

        @Override
        public void onDeploymentCompleted(DeploymentEvent.DeploymentCompleted event) {}

        @Override
        public void onDeploymentFailed(DeploymentEvent.DeploymentFailed event) {}

        @Override
        public Map<Artifact, List<DeploymentMetrics>> allDeploymentMetrics() {
            return Map.of();
        }

        @Override
        public List<DeploymentMetrics> metricsFor(Artifact artifact) {
            return List.of();
        }

        @Override
        public Map<DeploymentKey, DeploymentMetrics> inProgressDeployments() {
            return Map.of();
        }

        @Override
        public void onDeploymentMetricsPing(org.pragmatica.cluster.metrics.DeploymentMetricsMessage.DeploymentMetricsPing ping) {}

        @Override
        public void onDeploymentMetricsPong(org.pragmatica.cluster.metrics.DeploymentMetricsMessage.DeploymentMetricsPong pong) {}

        @Override
        public void onTopologyChange(TopologyChangeNotification topologyChange) {}

        @Override
        public Map<String, List<org.pragmatica.cluster.metrics.DeploymentMetricsMessage.DeploymentMetricsEntry>> collectLocalEntries() {
            return Map.of();
        }
    }
}
