package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.e2e.containers.AetherNodeContainer;
import org.pragmatica.lang.utils.Causes;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// E2E test verifying recovery from leader network isolation.
///
///
/// Disconnects the current leader from the network (without killing it),
/// verifies a new leader is elected among the remaining nodes, then
/// reconnects the old leader and verifies the cluster reconverges
/// without split-brain.
class LeaderIsolationE2ETest extends AbstractE2ETest {

    private static final Pattern LEADER_PATTERN = Pattern.compile("\"leader\":\"([^\"]+)\"");

    @Override
    protected int clusterSize() {
        return 5;
    }

    @Test
    void leaderIsolation_newLeaderElected_reconvergesWithoutSplitBrain() {
        assertThat(cluster.runningNodeCount()).isEqualTo(5);

        // Identify and disconnect the current leader
        var oldLeader = cluster.leader().toResult(Causes.cause("No leader")).unwrap();
        var oldLeaderId = oldLeader.nodeId();
        cluster.disconnectNode(oldLeaderId);

        // Wait for a new leader among connected nodes
        var connectedNode = cluster.nodes().stream()
            .filter(n -> !n.nodeId().equals(oldLeaderId) && n.isRunning())
            .findFirst()
            .orElseThrow();

        await().atMost(RECOVERY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .ignoreExceptions()
               .until(() -> hasNewLeader(connectedNode, oldLeaderId));

        // Reconnect old leader and verify convergence
        cluster.reconnectNode(oldLeaderId);
        cluster.awaitClusterConverged();

        // Verify single leader — no split-brain
        assertSingleLeaderAcrossCluster();
    }

    private static boolean hasNewLeader(AetherNodeContainer node, String oldLeaderId) {
        var status = node.getStatus();
        return status.contains("\"leader\":\"") &&
               !status.contains("\"leader\":\"" + oldLeaderId + "\"");
    }

    private void assertSingleLeaderAcrossCluster() {
        var leaders = cluster.nodes().stream()
            .filter(n -> n.isRunning())
            .map(n -> extractLeader(n.getStatus()))
            .distinct()
            .toList();

        assertThat(leaders)
            .describedAs("All nodes must agree on a single leader")
            .hasSize(1);
        assertThat(leaders.getFirst())
            .describedAs("Leader must be identified")
            .isNotEmpty();
    }

    private static String extractLeader(String status) {
        var matcher = LEADER_PATTERN.matcher(status);
        return matcher.find() ? matcher.group(1) : "";
    }
}
