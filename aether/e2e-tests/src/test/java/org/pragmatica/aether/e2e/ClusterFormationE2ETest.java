package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.e2e.containers.AetherCluster;
import org.pragmatica.aether.e2e.containers.AetherNodeContainer;
import org.pragmatica.lang.utils.Causes;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/// E2E tests for cluster formation and quorum behavior.
///
///
/// Tests cover:
///
///   - 5-node cluster formation
///   - Quorum establishment
///   - Leader election
///   - Node visibility across cluster
///
///
///
/// This test class uses a shared cluster (read-only tests).
class ClusterFormationE2ETest {
    private static final Path PROJECT_ROOT = Path.of(System.getProperty("project.basedir", ".."));
    private static AetherCluster cluster;

    @BeforeAll
    static void createCluster() {
        cluster = AetherCluster.aetherCluster(5, PROJECT_ROOT);
        cluster.start();
        cluster.awaitQuorum();
        cluster.awaitAllHealthy();
        cluster.uploadTestArtifacts();
    }

    @AfterAll
    static void destroyCluster() {
        if (cluster != null) {
            cluster.close();
        }
    }

    @Test
    void fiveNodeCluster_formsQuorum_andElectsLeader() {
        // All nodes should be healthy
        cluster.awaitAllHealthy();
        assertThat(cluster.runningNodeCount()).isEqualTo(5);

        // Health endpoint should report healthy with quorum
        var health = cluster.anyNode().getHealth();
        assertThat(health).contains("\"status\"");

        // Wait for leader to be elected (may take a moment after quorum)
        cluster.awaitLeader();
        var leader = cluster.leader();
        assertThat(leader.isPresent()).isTrue();
    }

    @Test
    void cluster_nodesVisibleToAllMembers() {
        // Each node should report 4 connected peers via /health endpoint
        for (var node : cluster.nodes()) {
            var health = node.getHealth();
            assertThat(health).contains("\"connectedPeers\":4");
            assertThat(health).contains("\"nodeCount\":5");
        }
    }

    @Test
    void cluster_statusConsistent_acrossNodes() {
        cluster.awaitLeader();

        // Collect leader info from all nodes
        var statuses = cluster.nodes().stream()
                              .map(AetherNodeContainer::getStatus)
                              .toList();

        // All should report the same leader
        var leaderNode = cluster.leader().toResult(Causes.cause("No leader")).unwrap();
        for (var status : statuses) {
            assertThat(status).contains(leaderNode.nodeId());
        }
    }

    @Test
    void cluster_metricsAvailable_afterFormation() {
        var metrics = cluster.anyNode().getMetrics();

        // Metrics should contain expected fields
        assertThat(metrics).doesNotContain("\"error\"");
    }
}
