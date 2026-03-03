package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// E2E tests for node failure and recovery in a containerized cluster.
///
///
/// These tests provide a safety net for container-specific behavior:
/// real TCP connection drops, JVM restarts, and network timeouts.
/// Detailed consensus/quorum scenarios are covered by Forge tests.
class NodeFailureE2ETest extends AbstractE2ETest {

    @Override
    protected int clusterSize() {
        return 5;
    }

    @Test
    void singleNodeFailure_clusterMaintainsQuorum() {
        assertThat(cluster.runningNodeCount()).isEqualTo(5);

        cluster.killNode("node-3");
        assertThat(cluster.runningNodeCount()).isEqualTo(4);

        cluster.awaitQuorum();

        var health = cluster.anyNode().getHealth();
        assertThat(health).contains("\"status\"");
    }

    @Test
    void leaderFailure_newLeaderElected() {
        var originalLeader = cluster.leader().toResult(Causes.cause("No leader")).unwrap();
        var originalLeaderId = originalLeader.nodeId();

        cluster.killNode(originalLeaderId);

        await().atMost(RECOVERY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .ignoreExceptions()
               .until(() -> {
                   var newLeader = cluster.leader();
                   return newLeader.isPresent() &&
                          !newLeader.toResult(Causes.cause("No leader")).unwrap().nodeId().equals(originalLeaderId);
               });

        var newLeader = cluster.leader().toResult(Causes.cause("No leader")).unwrap();
        assertThat(newLeader.nodeId()).isNotEqualTo(originalLeaderId);
    }
}
