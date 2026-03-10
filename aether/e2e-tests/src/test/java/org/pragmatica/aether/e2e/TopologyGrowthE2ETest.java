package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// E2E test for dynamic cluster growth — adding nodes to a running cluster.
///
///
/// Verifies that new nodes can join a converged cluster and that all nodes
/// reach agreement on topology and leader after expansion.
class TopologyGrowthE2ETest extends AbstractE2ETest {

    @Test
    void addNodes_clusterGrows_allNodesConverge() {
        cluster.awaitClusterConverged();
        assertThat(cluster.runningNodeCount()).isEqualTo(3);

        cluster.addNode();
        cluster.addNode();

        assertThat(cluster.runningNodeCount()).isEqualTo(5);

        cluster.awaitClusterConverged();
        cluster.awaitAllHealthy();

        assertThat(cluster.size()).isEqualTo(5);
    }
}
