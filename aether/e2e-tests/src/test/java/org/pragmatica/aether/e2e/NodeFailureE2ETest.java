package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.e2e.containers.AetherCluster;
import org.pragmatica.lang.utils.Causes;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.e2e.TestEnvironment.adapt;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// E2E tests for node failure and recovery scenarios.
///
///
/// Tests cover:
///
///   - Single node failure with quorum maintained
///   - Two node failure with quorum maintained
///   - Leader failure and re-election
///   - Node recovery and rejoin
///   - Rolling restart maintaining quorum
///   - Minority partition (quorum lost then recovered)
///
///
///
/// This test class uses a shared cluster for all tests to reduce startup overhead.
/// Tests run in order and each test restores all stopped nodes before running.
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
class NodeFailureE2ETest {
    private static final Path PROJECT_ROOT = Path.of(System.getProperty("project.basedir", ".."));

    // Common timeouts (CI gets 2x via adapt())
    private static final Duration RECOVERY_TIMEOUT = adapt(timeSpan(2).minutes().duration());
    private static final Duration POLL_INTERVAL = timeSpan(2).seconds().duration();

    private static AetherCluster cluster;

    @BeforeAll
    static void createCluster() {
        cluster = AetherCluster.aetherCluster(5, PROJECT_ROOT);
        cluster.start();
        cluster.awaitQuorum();
        cluster.awaitAllHealthy();
        cluster.awaitLeader();
    }

    @AfterAll
    static void destroyCluster() {
        if (cluster != null) {
            cluster.close();
        }
    }

    @BeforeEach
    void restoreAllNodes() {
        // First restart any stopped nodes
        for (var node : cluster.nodes()) {
            if (!node.isRunning()) {
                try {
                    System.out.println("[DEBUG] Restarting stopped node: " + node.nodeId());
                    cluster.node(node.nodeId()).start();
                } catch (Exception e) {
                    System.out.println("[DEBUG] Error restarting " + node.nodeId() + ": " + e.getMessage());
                }
            }
        }

        // Wait for all nodes with extended timeout (containers may take longer after chaos)
        await().atMost(adapt(Duration.ofSeconds(120)))
               .pollInterval(POLL_INTERVAL)
               .ignoreExceptions()
               .until(() -> cluster.runningNodeCount() == 5);

        cluster.awaitQuorum();
    }

    @Test
    @Order(1)
    void singleNodeFailure_clusterMaintainsQuorum() {
        assertThat(cluster.runningNodeCount()).isEqualTo(5);

        // Kill one node
        cluster.killNode("node-3");
        assertThat(cluster.runningNodeCount()).isEqualTo(4);

        // Cluster should still have quorum
        cluster.awaitQuorum();

        // Operations should still work
        var health = cluster.anyNode().getHealth();
        assertThat(health).contains("\"status\"");
    }

    @Test
    @Order(2)
    void twoNodeFailure_clusterMaintainsQuorum() {
        // Kill two nodes (5 - 2 = 3, still majority)
        cluster.killNode("node-2");
        cluster.killNode("node-4");
        assertThat(cluster.runningNodeCount()).isEqualTo(3);

        // Cluster should still have quorum
        cluster.awaitQuorum();

        var nodes = cluster.anyNode().getNodes();
        assertThat(nodes).contains("node-1");
        assertThat(nodes).contains("node-3");
        assertThat(nodes).contains("node-5");
    }

    @Test
    @Order(3)
    void leaderFailure_newLeaderElected() {
        var originalLeader = cluster.leader().toResult(Causes.cause("No leader")).unwrap();
        var originalLeaderId = originalLeader.nodeId();

        // Kill the leader
        cluster.killNode(originalLeaderId);

        // Wait for new leader election
        await().atMost(RECOVERY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .ignoreExceptions()
               .until(() -> {
                   var newLeader = cluster.leader();
                   return newLeader.isPresent() &&
                          !newLeader.toResult(Causes.cause("No leader")).unwrap().nodeId().equals(originalLeaderId);
               });

        // New leader should be different
        var newLeader = cluster.leader().toResult(Causes.cause("No leader")).unwrap();
        assertThat(newLeader.nodeId()).isNotEqualTo(originalLeaderId);
    }

}
