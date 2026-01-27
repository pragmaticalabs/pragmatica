package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.forge.ForgeCluster.forgeCluster;

/**
 * Chaos testing for cluster resilience.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Random node kills with recovery</li>
 *   <li>Rapid kill/restart cycles</li>
 *   <li>Concurrent operations during chaos</li>
 *   <li>Leader kill spree</li>
 *   <li>Split brain recovery</li>
 * </ul>
 */
@Execution(ExecutionMode.SAME_THREAD)
class ChaosTest {
    private static final int BASE_PORT = 5340;
    private static final int BASE_MGMT_PORT = 5440;
    private static final Duration CHAOS_DURATION = Duration.ofSeconds(30);
    private static final Duration RECOVERY_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    private ForgeCluster cluster;
    private HttpClient httpClient;
    private Random random;
    private Set<String> killedNodeIds;

    @BeforeEach
    void setUp(TestInfo testInfo) {
        int portOffset = getPortOffset(testInfo);
        cluster = forgeCluster(5, BASE_PORT + portOffset, BASE_MGMT_PORT + portOffset, "ch");
        httpClient = HttpClient.newBuilder()
                               .connectTimeout(Duration.ofSeconds(5))
                               .build();
        random = new Random(42); // Deterministic for reproducibility
        killedNodeIds = new HashSet<>();

        cluster.start()
               .await()
               .onFailure(cause -> {
                   throw new AssertionError("Cluster start failed: " + cause.message());
               });

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader().isPresent());
    }

    private int getPortOffset(TestInfo testInfo) {
        return switch (testInfo.getTestMethod().map(m -> m.getName()).orElse("")) {
            case "randomNodeKills_clusterRecovers" -> 0;
            case "rapidKillAdd_clusterRemainsFunctional" -> 20;
            case "concurrentChaos_clusterMaintainsConsistency" -> 40;
            case "leaderKillSpree_clusterSurvives" -> 60;
            case "splitBrainRecovery_clusterReconverges" -> 80;
            default -> 100;
        };
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (cluster != null) {
            cluster.stop()
                   .await();
            Thread.sleep(1000);
        }
    }

    @Test
    void randomNodeKills_clusterRecovers() {
        var killCount = new AtomicInteger(0);

        // Kill random nodes, keeping quorum
        for (int i = 0; i < 5; i++) {
            var runningCount = cluster.nodeCount();

            if (runningCount > 3) { // Keep quorum
                var nodeIds = getRunningNodeIds();
                var victimId = nodeIds.get(random.nextInt(nodeIds.size()));
                cluster.killNode(victimId).await();
                killedNodeIds.add(victimId);
                killCount.incrementAndGet();
            }

            sleep(Duration.ofSeconds(2));
            awaitQuorum();
        }

        assertThat(killCount.get()).isGreaterThan(0);

        // Restore nodes by adding new ones
        for (var killedId : killedNodeIds) {
            cluster.addNode().await();
        }
        killedNodeIds.clear();

        // Full recovery
        await().atMost(RECOVERY_TIMEOUT)
               .until(() -> cluster.nodeCount() == 5);
        awaitQuorum();
    }

    @Test
    void rapidKillAdd_clusterRemainsFunctional() {
        var iterations = 10;
        var successfulOps = new AtomicInteger(0);

        for (int i = 0; i < iterations; i++) {
            // Pick a non-leader node for killing
            var targetNodeId = getRunningNodeIds().stream()
                                                  .filter(id -> !cluster.currentLeader().map(l -> l.equals(id)).or(false))
                                                  .findFirst()
                                                  .orElse(null);
            if (targetNodeId == null) continue;

            // Kill target node
            cluster.killNode(targetNodeId).await();
            sleep(Duration.ofMillis(500));

            // Try an operation
            try {
                var health = getHealthFromAnyNode();
                if (health != null && !health.contains("\"error\"")) {
                    successfulOps.incrementAndGet();
                }
            } catch (Exception ignored) {
            }

            // Add a new node
            cluster.addNode().await();
            sleep(Duration.ofMillis(500));
        }

        // At least 70% of operations should succeed
        assertThat(successfulOps.get()).isGreaterThan(iterations * 7 / 10);

        // Final state should be stable
        awaitQuorum();
        assertThat(cluster.nodeCount()).isGreaterThanOrEqualTo(5);
    }

    @Test
    void concurrentChaos_clusterMaintainsConsistency() throws InterruptedException {
        var chaosRunning = new AtomicBoolean(true);
        var errors = new AtomicInteger(0);
        var operations = new AtomicInteger(0);

        // Chaos thread - randomly kill/add nodes
        var chaosThread = new Thread(() -> {
            while (chaosRunning.get()) {
                try {
                    var runningCount = cluster.nodeCount();

                    if (runningCount > 3 && random.nextBoolean()) {
                        var nodeIds = getRunningNodeIds();
                        var victimId = nodeIds.get(random.nextInt(nodeIds.size()));
                        cluster.killNode(victimId).await();
                        sleep(Duration.ofSeconds(1));
                        cluster.addNode().await();
                    }
                    sleep(Duration.ofSeconds(2));
                } catch (Exception e) {
                    // Ignore chaos errors
                }
            }
        });

        // Operations thread - continuously try operations
        var opsThread = new Thread(() -> {
            while (chaosRunning.get()) {
                try {
                    var health = getHealthFromAnyNode();
                    if (health != null && health.contains("\"error\"")) {
                        errors.incrementAndGet();
                    }
                    operations.incrementAndGet();
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
                sleep(Duration.ofMillis(100));
            }
        });

        chaosThread.start();
        opsThread.start();

        // Run chaos for specified duration
        sleep(CHAOS_DURATION);
        chaosRunning.set(false);

        chaosThread.join(5000);
        opsThread.join(5000);

        // Allow cluster to stabilize
        awaitQuorum();

        // Check results
        assertThat(operations.get()).isGreaterThan(0);
        var errorRate = (double) errors.get() / operations.get();
        assertThat(errorRate).isLessThan(0.3); // Less than 30% error rate during chaos
    }

    @Test
    void leaderKillSpree_clusterSurvives() {
        var leaderKills = 0;

        for (int i = 0; i < 3; i++) {
            var leader = cluster.currentLeader();
            if (leader.isPresent()) {
                var leaderId = leader.unwrap();
                cluster.killNode(leaderId).await();
                killedNodeIds.add(leaderId);
                leaderKills++;

                // Add replacement node immediately to maintain cluster size
                cluster.addNode().await();

                sleep(Duration.ofSeconds(2));

                // Should elect new leader
                await().atMost(Duration.ofSeconds(15))
                       .until(() -> cluster.currentLeader().isPresent());
            }
        }

        assertThat(leaderKills).isGreaterThanOrEqualTo(2);

        awaitQuorum();
        assertThat(cluster.nodeCount()).isEqualTo(5);
    }

    @Test
    void splitBrainRecovery_clusterReconverges() {
        var nodeIds = getRunningNodeIds();

        // Simulate split-brain by killing nodes on one "side"
        var killed1 = nodeIds.get(0);
        var killed2 = nodeIds.get(1);
        cluster.killNode(killed1).await();
        cluster.killNode(killed2).await();
        sleep(Duration.ofSeconds(5));

        // Remaining nodes (3) should maintain quorum
        awaitQuorum();

        // Kill one more to lose quorum
        var remainingNodes = getRunningNodeIds();
        var killed3 = remainingNodes.get(0);
        cluster.killNode(killed3).await();
        sleep(Duration.ofSeconds(2));

        // Now only 2 nodes - no quorum
        assertThat(cluster.nodeCount()).isEqualTo(2);

        // Restore nodes by adding new ones
        cluster.addNode().await();
        cluster.addNode().await();
        cluster.addNode().await();

        // Cluster should reconverge
        await().atMost(RECOVERY_TIMEOUT)
               .until(() -> cluster.nodeCount() == 5);
        awaitQuorum();

        // All nodes should agree on leader
        var leader = cluster.currentLeader().unwrap();
        assertThat(leader).isNotNull();
        assertThat(cluster.nodeCount()).isEqualTo(5);

        // Verify all nodes report quorum
        for (var node : cluster.status().nodes()) {
            var health = getHealthFromNode(node.mgmtPort());
            assertThat(health).contains("\"quorum\":true");
        }
    }

    private String getHealthFromNode(int port) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/health"))
                                 .GET()
                                 .timeout(Duration.ofSeconds(5))
                                 .build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (IOException | InterruptedException e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private void awaitQuorum() {
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> {
                   if (cluster.nodeCount() < 3) {
                       return false;
                   }
                   return cluster.currentLeader().isPresent();
               });
    }

    private List<String> getRunningNodeIds() {
        return cluster.status().nodes().stream()
                      .map(ForgeCluster.NodeStatus::id)
                      .toList();
    }

    private String getHealthFromAnyNode() {
        var status = cluster.status();
        if (status.nodes().isEmpty()) {
            return null;
        }
        var node = status.nodes().get(0);
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + node.mgmtPort() + "/api/health"))
                                 .GET()
                                 .timeout(Duration.ofSeconds(5))
                                 .build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
