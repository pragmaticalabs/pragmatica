package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.e2e.containers.AetherNodeContainer;
import org.pragmatica.lang.utils.Causes;

import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/**
 * Chaos testing for cluster resilience.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Random node kills with recovery</li>
 *   <li>Rapid kill/restart cycles</li>
 *   <li>Concurrent operations during chaos</li>
 *   <li>Cluster stability after chaos</li>
 * </ul>
 */
@Disabled("Flaky in containerized environments - requires longer timeouts")
class ChaosE2ETest extends AbstractE2ETest {
    private static final long CHAOS_DURATION_MS = timeSpan(30).seconds().duration().toMillis();
    private Random random;

    @Override
    protected int clusterSize() {
        return 5;
    }

    @Override
    protected void additionalSetUp() {
        random = new Random(42); // Deterministic for reproducibility
    }

    @Test
    void randomNodeKills_clusterRecovers() {
        var killCount = new AtomicInteger(0);

        // Kill random nodes, keeping quorum
        for (int i = 0; i < 5; i++) {
            var runningNodes = cluster.nodes().stream()
                                       .filter(AetherNodeContainer::isRunning)
                                       .toList();

            if (runningNodes.size() > 3) { // Keep quorum
                var victim = runningNodes.get(random.nextInt(runningNodes.size()));
                cluster.killNode(victim.nodeId());
                killCount.incrementAndGet();
            }

            sleep(timeSpan(2).seconds());
            cluster.awaitQuorum();
        }

        assertThat(killCount.get()).isGreaterThan(0);

        // Restart all killed nodes
        for (var node : cluster.nodes()) {
            if (!node.isRunning()) {
                cluster.restartNode(node.nodeId());
            }
        }

        // Full recovery
        await().atMost(RECOVERY_TIMEOUT.duration())
               .pollInterval(POLL_INTERVAL.duration())
               .until(() -> cluster.runningNodeCount() == 5);
        cluster.awaitQuorum();
    }

    @Test
    void rapidKillRestart_clusterRemainsFunctional() {
        var iterations = 10;
        var successfulOps = new AtomicInteger(0);

        for (int i = 0; i < iterations; i++) {
            // Kill node-3
            cluster.killNode("node-3");
            sleep(timeSpan(500).millis());

            // Try an operation
            try {
                var health = cluster.anyNode().getHealth();
                if (!health.contains("\"error\"")) {
                    successfulOps.incrementAndGet();
                }
            } catch (Exception ignored) {
            }

            // Restart node-3
            cluster.restartNode("node-3");
            sleep(timeSpan(500).millis());
        }

        // Most operations should succeed
        assertThat(successfulOps.get()).isGreaterThan(iterations / 2);

        // Final state should be stable
        cluster.awaitQuorum();
        assertThat(cluster.runningNodeCount()).isEqualTo(5);
    }

    @Test
    void concurrentChaos_clusterMaintainsConsistency() throws InterruptedException {
        var chaosRunning = new AtomicBoolean(true);
        var errors = new AtomicInteger(0);
        var operations = new AtomicInteger(0);

        // Chaos thread - randomly kill/restart nodes
        var chaosThread = new Thread(() -> {
            while (chaosRunning.get()) {
                try {
                    var nodes = cluster.nodes().stream()
                                        .filter(AetherNodeContainer::isRunning)
                                        .toList();

                    if (nodes.size() > 3 && random.nextBoolean()) {
                        var victim = nodes.get(random.nextInt(nodes.size()));
                        cluster.killNode(victim.nodeId());
                        sleep(timeSpan(1).seconds());
                        cluster.restartNode(victim.nodeId());
                    }
                    sleep(timeSpan(2).seconds());
                } catch (Exception e) {
                    // Ignore chaos errors
                }
            }
        });

        // Operations thread - continuously try operations
        var opsThread = new Thread(() -> {
            while (chaosRunning.get()) {
                try {
                    var health = cluster.anyNode().getHealth();
                    if (health.contains("\"error\"")) {
                        errors.incrementAndGet();
                    }
                    operations.incrementAndGet();
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
                sleep(timeSpan(100).millis());
            }
        });

        chaosThread.start();
        opsThread.start();

        // Run chaos for specified duration
        Thread.sleep(CHAOS_DURATION_MS);
        chaosRunning.set(false);

        chaosThread.join(5000);
        opsThread.join(5000);

        // Allow cluster to stabilize
        for (var node : cluster.nodes()) {
            if (!node.isRunning()) {
                cluster.restartNode(node.nodeId());
            }
        }
        cluster.awaitQuorum();

        // Check results
        assertThat(operations.get()).isGreaterThan(0);
        var errorRate = (double) errors.get() / operations.get();
        assertThat(errorRate).isLessThan(0.5); // Less than 50% error rate during chaos
    }

    @Test
    void leaderKillSpree_clusterSurvives() {
        var leaderKills = 0;

        for (int i = 0; i < 3; i++) {
            var leader = cluster.leader();
            if (leader.isPresent()) {
                cluster.killNode(leader.toResult(Causes.cause("No leader")).unwrap().nodeId());
                leaderKills++;
                sleep(timeSpan(2).seconds());

                // Should elect new leader
                await().atMost(timeSpan(15).seconds().duration())
                       .pollInterval(POLL_INTERVAL.duration())
                       .until(() -> cluster.leader().isPresent());
            }
        }

        assertThat(leaderKills).isGreaterThanOrEqualTo(2);

        // Restart all killed nodes
        for (var node : cluster.nodes()) {
            if (!node.isRunning()) {
                cluster.restartNode(node.nodeId());
            }
        }

        cluster.awaitQuorum();
        assertThat(cluster.runningNodeCount()).isEqualTo(5);
    }

    @Test
    void splitBrainRecovery_clusterReconverges() {
        // Simulate split-brain by killing nodes on one "side"
        cluster.killNode("node-1");
        cluster.killNode("node-2");
        sleep(timeSpan(5).seconds());

        // Remaining nodes (3, 4, 5) should maintain quorum
        cluster.awaitQuorum();

        // Kill one more to lose quorum
        cluster.killNode("node-3");
        sleep(timeSpan(2).seconds());

        // Now only 2 nodes - no quorum
        assertThat(cluster.runningNodeCount()).isEqualTo(2);

        // Restore all nodes
        cluster.restartNode("node-1");
        cluster.restartNode("node-2");
        cluster.restartNode("node-3");

        // Cluster should reconverge
        await().atMost(RECOVERY_TIMEOUT.duration())
               .pollInterval(POLL_INTERVAL.duration())
               .until(() -> cluster.runningNodeCount() == 5);
        cluster.awaitQuorum();

        // All nodes should agree on state
        var leader = cluster.leader().toResult(Causes.cause("No leader")).unwrap();
        for (var node : cluster.nodes()) {
            var status = node.getStatus();
            assertThat(status).contains(leader.nodeId());
        }
    }
}
