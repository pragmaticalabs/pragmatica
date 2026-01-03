package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.e2e.containers.AetherCluster;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E tests for cluster formation and quorum behavior.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>3-node cluster formation</li>
 *   <li>Quorum establishment</li>
 *   <li>Leader election</li>
 *   <li>Node visibility across cluster</li>
 * </ul>
 */
class ClusterFormationE2ETest {
    private static final Path PROJECT_ROOT = Path.of(System.getProperty("project.basedir", ".."));
    private AetherCluster cluster;

    @BeforeEach
    void setUp() {
        cluster = AetherCluster.create(3, PROJECT_ROOT);
    }

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.close();
        }
    }

    @Test
    void threeNodeCluster_formsQuorum_andElectsLeader() {
        cluster.start();
        cluster.awaitQuorum();

        // All nodes should be healthy
        cluster.awaitAllHealthy();
        assertThat(cluster.runningNodeCount()).isEqualTo(3);

        // Health endpoint should report healthy with quorum
        var health = cluster.anyNode().getHealth();
        assertThat(health).contains("\"status\"");

        // Wait for leader to be elected (may take a moment after quorum)
        cluster.awaitLeader();
        var leader = cluster.leader();
        assertThat(leader).isPresent();
    }

    @Test
    void cluster_nodesVisibleToAllMembers() {
        cluster.start();
        cluster.awaitQuorum();

        // Each node should report 2 connected peers via /health endpoint
        // (The /nodes endpoint uses metrics which may not be exchanged yet)
        for (var node : cluster.nodes()) {
            var health = node.getHealth();
            assertThat(health).contains("\"connectedPeers\":2");
            assertThat(health).contains("\"nodeCount\":3");
        }
    }

    @Test
    void cluster_statusConsistent_acrossNodes() {
        cluster.start();
        cluster.awaitQuorum();
        cluster.awaitLeader();

        // Collect leader info from all nodes
        var statuses = cluster.nodes().stream()
                              .map(node -> node.getStatus())
                              .toList();

        // All should report the same leader
        var leaderNode = cluster.leader().orElseThrow();
        for (var status : statuses) {
            assertThat(status).contains(leaderNode.nodeId());
        }
    }

    @Test
    void cluster_metricsAvailable_afterFormation() {
        cluster.start();
        cluster.awaitQuorum();

        var metrics = cluster.anyNode().getMetrics();

        // Metrics should contain expected fields
        assertThat(metrics).doesNotContain("\"error\"");
    }
}
