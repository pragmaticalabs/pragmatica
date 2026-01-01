package org.pragmatica.aether.metrics.deployment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.metrics.deployment.DeploymentEvent.*;
import org.pragmatica.aether.metrics.deployment.DeploymentMetrics.DeploymentStatus;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.cluster.metrics.DeploymentMetricsMessage.DeploymentMetricsEntry;
import org.pragmatica.cluster.metrics.DeploymentMetricsMessage.DeploymentMetricsPing;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyChangeNotification;

import org.pragmatica.consensus.net.NetworkManagementOperation;
import org.pragmatica.consensus.net.NetworkMessage;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentMetricsCollectorTest {

    private NodeId self;
    private NodeId remoteNode;
    private TestClusterNetwork network;
    private DeploymentMetricsCollector collector;
    private Artifact artifact;

    @BeforeEach
    void setUp() {
        self = NodeId.randomNodeId();
        remoteNode = NodeId.randomNodeId();
        network = new TestClusterNetwork();
        collector = DeploymentMetricsCollector.deploymentMetricsCollector(self, network);
        artifact = Artifact.artifact("org.example:test:1.0.0").unwrap();
    }

    // === Deployment Started Tests ===

    @Test
    void onDeploymentStarted_creates_in_progress_metrics() {
        collector.onDeploymentStarted(new DeploymentStarted(artifact, self, 1000L));

        var inProgress = collector.inProgressDeployments();

        assertThat(inProgress).hasSize(1);
        var key = new DeploymentMetricsCollector.DeploymentKey(artifact, self);
        assertThat(inProgress).containsKey(key);
        assertThat(inProgress.get(key).status()).isEqualTo(DeploymentStatus.IN_PROGRESS);
        assertThat(inProgress.get(key).startTime()).isEqualTo(1000L);
    }

    // === State Transition Tests ===

    @Test
    void onStateTransition_updates_load_time() {
        collector.onDeploymentStarted(new DeploymentStarted(artifact, self, 1000L));
        collector.onStateTransition(new StateTransition(artifact, self, SliceState.LOAD, SliceState.LOAD, 1100L));

        var key = new DeploymentMetricsCollector.DeploymentKey(artifact, self);
        var metrics = collector.inProgressDeployments().get(key);

        assertThat(metrics.loadTime()).isEqualTo(1100L);
    }

    @Test
    void onStateTransition_updates_loaded_time() {
        collector.onDeploymentStarted(new DeploymentStarted(artifact, self, 1000L));
        collector.onStateTransition(new StateTransition(artifact, self, SliceState.LOADING, SliceState.LOADED, 1500L));

        var key = new DeploymentMetricsCollector.DeploymentKey(artifact, self);
        var metrics = collector.inProgressDeployments().get(key);

        assertThat(metrics.loadedTime()).isEqualTo(1500L);
    }

    @Test
    void onStateTransition_updates_activate_time() {
        collector.onDeploymentStarted(new DeploymentStarted(artifact, self, 1000L));
        collector.onStateTransition(new StateTransition(artifact, self, SliceState.LOADED, SliceState.ACTIVATE, 1600L));

        var key = new DeploymentMetricsCollector.DeploymentKey(artifact, self);
        var metrics = collector.inProgressDeployments().get(key);

        assertThat(metrics.activateTime()).isEqualTo(1600L);
    }

    @Test
    void onStateTransition_ignores_unknown_deployment() {
        // No deployment started, transition should be ignored
        collector.onStateTransition(new StateTransition(artifact, self, SliceState.LOAD, SliceState.LOADED, 1500L));

        assertThat(collector.inProgressDeployments()).isEmpty();
    }

    // === Deployment Completed Tests ===

    @Test
    void onDeploymentCompleted_moves_to_completed_metrics() {
        collector.onDeploymentStarted(new DeploymentStarted(artifact, self, 1000L));
        collector.onStateTransition(new StateTransition(artifact, self, SliceState.LOAD, SliceState.LOAD, 1100L));
        collector.onStateTransition(new StateTransition(artifact, self, SliceState.LOADING, SliceState.LOADED, 1500L));
        collector.onDeploymentCompleted(new DeploymentCompleted(artifact, self, 2000L));

        assertThat(collector.inProgressDeployments()).isEmpty();

        var completed = collector.metricsFor(artifact);
        assertThat(completed).hasSize(1);
        assertThat(completed.getFirst().status()).isEqualTo(DeploymentStatus.SUCCESS);
        assertThat(completed.getFirst().fullDeploymentTime()).isEqualTo(1000L);
    }

    @Test
    void onDeploymentCompleted_ignores_unknown_deployment() {
        collector.onDeploymentCompleted(new DeploymentCompleted(artifact, self, 2000L));

        assertThat(collector.metricsFor(artifact)).isEmpty();
    }

    // === Deployment Failed Tests ===

    @Test
    void onDeploymentFailed_at_loading_sets_failed_loading_status() {
        collector.onDeploymentStarted(new DeploymentStarted(artifact, self, 1000L));
        collector.onDeploymentFailed(new DeploymentFailed(artifact, self, SliceState.LOADING, 1200L));

        assertThat(collector.inProgressDeployments()).isEmpty();

        var completed = collector.metricsFor(artifact);
        assertThat(completed).hasSize(1);
        assertThat(completed.getFirst().status()).isEqualTo(DeploymentStatus.FAILED_LOADING);
    }

    @Test
    void onDeploymentFailed_at_activating_sets_failed_activating_status() {
        collector.onDeploymentStarted(new DeploymentStarted(artifact, self, 1000L));
        collector.onDeploymentFailed(new DeploymentFailed(artifact, self, SliceState.ACTIVATING, 1700L));

        var completed = collector.metricsFor(artifact);
        assertThat(completed).hasSize(1);
        assertThat(completed.getFirst().status()).isEqualTo(DeploymentStatus.FAILED_ACTIVATING);
    }

    // === History Retention Tests ===

    @Test
    void completed_metrics_retain_last_n_entries() {
        // Create collector with retention of 3
        collector = DeploymentMetricsCollector.deploymentMetricsCollector(self, network, 3);

        // Complete 5 deployments
        for (int i = 1; i <= 5; i++) {
            collector.onDeploymentStarted(new DeploymentStarted(artifact, self, i * 1000L));
            collector.onDeploymentCompleted(new DeploymentCompleted(artifact, self, i * 1000L + 500L));
        }

        var completed = collector.metricsFor(artifact);

        assertThat(completed).hasSize(3);
        // Most recent first (5, 4, 3)
        assertThat(completed.get(0).startTime()).isEqualTo(5000L);
        assertThat(completed.get(1).startTime()).isEqualTo(4000L);
        assertThat(completed.get(2).startTime()).isEqualTo(3000L);
    }

    // === allDeploymentMetrics Tests ===

    @Test
    void allDeploymentMetrics_returns_local_metrics() {
        collector.onDeploymentStarted(new DeploymentStarted(artifact, self, 1000L));
        collector.onDeploymentCompleted(new DeploymentCompleted(artifact, self, 2000L));

        var all = collector.allDeploymentMetrics();

        assertThat(all).containsKey(artifact);
        assertThat(all.get(artifact)).hasSize(1);
    }

    @Test
    void allDeploymentMetrics_returns_empty_when_no_metrics() {
        var all = collector.allDeploymentMetrics();

        assertThat(all).isEmpty();
    }

    // === Ping/Pong Tests ===

    @Test
    void onDeploymentMetricsPing_responds_with_pong() {
        // Add local metrics
        collector.onDeploymentStarted(new DeploymentStarted(artifact, self, 1000L));
        collector.onDeploymentCompleted(new DeploymentCompleted(artifact, self, 2000L));

        // Receive ping from remote node
        var ping = new DeploymentMetricsPing(remoteNode, Map.of());
        collector.onDeploymentMetricsPing(ping);

        // Should have sent pong
        assertThat(network.sentMessages).hasSize(1);
        assertThat(network.sentMessages.getFirst().target()).isEqualTo(remoteNode);
    }

    @Test
    void onDeploymentMetricsPing_stores_remote_metrics() {
        var remoteArtifact = Artifact.artifact("org.remote:slice:1.0.0").unwrap();
        var remoteEntry = new DeploymentMetricsEntry(
            remoteArtifact.asString(),
            remoteNode.id(),
            1000L, 1100L, 1500L, 1600L, 2000L,
            "SUCCESS"
        );

        var ping = new DeploymentMetricsPing(remoteNode, Map.of(remoteArtifact.asString(), List.of(remoteEntry)));
        collector.onDeploymentMetricsPing(ping);

        var all = collector.allDeploymentMetrics();
        assertThat(all).containsKey(remoteArtifact);
    }

    @Test
    void onDeploymentMetricsPong_stores_remote_metrics() {
        var remoteArtifact = Artifact.artifact("org.remote:slice:1.0.0").unwrap();
        var remoteEntry = new DeploymentMetricsEntry(
            remoteArtifact.asString(),
            remoteNode.id(),
            1000L, 1100L, 1500L, 1600L, 2000L,
            "SUCCESS"
        );

        var pong = new org.pragmatica.cluster.metrics.DeploymentMetricsMessage.DeploymentMetricsPong(
            remoteNode, Map.of(remoteArtifact.asString(), List.of(remoteEntry))
        );
        collector.onDeploymentMetricsPong(pong);

        var all = collector.allDeploymentMetrics();
        assertThat(all).containsKey(remoteArtifact);
    }

    @Test
    void ping_from_self_does_not_store_metrics() {
        var entry = new DeploymentMetricsEntry(
            artifact.asString(),
            self.id(),
            1000L, 1100L, 1500L, 1600L, 2000L,
            "SUCCESS"
        );

        var ping = new DeploymentMetricsPing(self, Map.of(artifact.asString(), List.of(entry)));
        collector.onDeploymentMetricsPing(ping);

        // Should not store our own metrics from ping
        assertThat(collector.allDeploymentMetrics()).isEmpty();
    }

    // === Topology Change Tests ===

    @Test
    void onTopologyChange_nodeRemoved_cleans_up_in_progress_deployments() {
        // Start deployment on remote node
        collector.onDeploymentStarted(new DeploymentStarted(artifact, remoteNode, 1000L));
        assertThat(collector.inProgressDeployments()).hasSize(1);

        // Remove remote node
        collector.onTopologyChange(TopologyChangeNotification.nodeRemoved(remoteNode, List.of(self)));

        // In-progress deployment should be removed
        assertThat(collector.inProgressDeployments()).isEmpty();
    }

    @Test
    void onTopologyChange_nodeRemoved_cleans_up_remote_metrics() {
        // Store remote metrics via ping
        var remoteEntry = new DeploymentMetricsEntry(
            artifact.asString(),
            remoteNode.id(),
            1000L, 1100L, 1500L, 1600L, 2000L,
            "SUCCESS"
        );
        var ping = new DeploymentMetricsPing(remoteNode, Map.of(artifact.asString(), List.of(remoteEntry)));
        collector.onDeploymentMetricsPing(ping);

        assertThat(collector.allDeploymentMetrics()).containsKey(artifact);

        // Remove remote node
        collector.onTopologyChange(TopologyChangeNotification.nodeRemoved(remoteNode, List.of(self)));

        // Remote metrics should be removed
        assertThat(collector.allDeploymentMetrics()).isEmpty();
    }

    @Test
    void onTopologyChange_nodeRemoved_keeps_local_metrics() {
        // Complete local deployment
        collector.onDeploymentStarted(new DeploymentStarted(artifact, self, 1000L));
        collector.onDeploymentCompleted(new DeploymentCompleted(artifact, self, 2000L));

        // Start in-progress on remote
        collector.onDeploymentStarted(new DeploymentStarted(artifact, remoteNode, 3000L));

        // Remove remote node
        collector.onTopologyChange(TopologyChangeNotification.nodeRemoved(remoteNode, List.of(self)));

        // Local metrics should remain
        assertThat(collector.metricsFor(artifact)).hasSize(1);
        assertThat(collector.metricsFor(artifact).getFirst().nodeId()).isEqualTo(self);
    }

    @Test
    void onTopologyChange_nodeAdded_is_ignored() {
        // Complete deployment
        collector.onDeploymentStarted(new DeploymentStarted(artifact, self, 1000L));
        collector.onDeploymentCompleted(new DeploymentCompleted(artifact, self, 2000L));

        var newNode = NodeId.randomNodeId();
        collector.onTopologyChange(TopologyChangeNotification.nodeAdded(newNode, List.of(self, newNode)));

        // Metrics should remain unchanged
        assertThat(collector.metricsFor(artifact)).hasSize(1);
    }

    // === Sorting Tests ===

    @Test
    void metrics_sorted_by_startTime_descending() {
        // Complete multiple deployments
        collector.onDeploymentStarted(new DeploymentStarted(artifact, self, 1000L));
        collector.onDeploymentCompleted(new DeploymentCompleted(artifact, self, 1500L));

        collector.onDeploymentStarted(new DeploymentStarted(artifact, self, 3000L));
        collector.onDeploymentCompleted(new DeploymentCompleted(artifact, self, 3500L));

        collector.onDeploymentStarted(new DeploymentStarted(artifact, self, 2000L));
        collector.onDeploymentCompleted(new DeploymentCompleted(artifact, self, 2500L));

        var metrics = collector.metricsFor(artifact);

        // Should be ordered by startTime descending (3000, 2000, 1000)
        assertThat(metrics.get(0).startTime()).isEqualTo(3000L);
        assertThat(metrics.get(1).startTime()).isEqualTo(2000L);
        assertThat(metrics.get(2).startTime()).isEqualTo(1000L);
    }

    @Test
    void failed_deployments_sorted_correctly() {
        // Failed deployment has activeTime=0 but should still be sorted by startTime
        collector.onDeploymentStarted(new DeploymentStarted(artifact, self, 1000L));
        collector.onDeploymentCompleted(new DeploymentCompleted(artifact, self, 1500L));

        collector.onDeploymentStarted(new DeploymentStarted(artifact, self, 2000L));
        collector.onDeploymentFailed(new DeploymentFailed(artifact, self, SliceState.LOADING, 2200L));

        var metrics = collector.metricsFor(artifact);

        // Failed deployment (startTime=2000) should come before completed (startTime=1000)
        assertThat(metrics.get(0).startTime()).isEqualTo(2000L);
        assertThat(metrics.get(0).status()).isEqualTo(DeploymentStatus.FAILED_LOADING);
        assertThat(metrics.get(1).startTime()).isEqualTo(1000L);
    }

    // === Test Doubles ===

    record SentMessage(NodeId target, Object message) {}

    static class TestClusterNetwork implements ClusterNetwork {
        final List<SentMessage> sentMessages = new CopyOnWriteArrayList<>();

        @Override
        public <M extends org.pragmatica.consensus.ProtocolMessage> void send(NodeId target, M message) {
            sentMessages.add(new SentMessage(target, message));
        }

        @Override
        public <M extends org.pragmatica.consensus.ProtocolMessage> void broadcast(M message) {
            // Not used in these tests
        }

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

        public void configure(MessageRouter.MutableRouter router) {}
    }
}
