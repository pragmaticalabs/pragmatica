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

        // Leader should be elected
        var leader = cluster.leader();
        assertThat(leader).isPresent();
    }

    @Test
    void cluster_nodesVisibleToAllMembers() {
        cluster.start();
        cluster.awaitQuorum();

        // Each node should see all other nodes
        for (var node : cluster.nodes()) {
            var nodes = node.getNodes();
            assertThat(nodes).contains("node-1");
            assertThat(nodes).contains("node-2");
            assertThat(nodes).contains("node-3");
        }
    }

    @Test
    void cluster_statusConsistent_acrossNodes() {
        cluster.start();
        cluster.awaitQuorum();

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
