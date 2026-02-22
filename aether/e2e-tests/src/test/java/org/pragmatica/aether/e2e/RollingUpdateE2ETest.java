package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.e2e.containers.AetherCluster;
import org.pragmatica.aether.e2e.containers.AetherNodeContainer;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.e2e.TestEnvironment.adapt;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// E2E tests for rolling update functionality.
///
///
/// Tests cover:
///
///   - Two-stage rolling update (deploy then route)
///   - Traffic ratio-based routing
///   - Health-based auto-progression
///   - Rollback scenarios
///   - Request continuity during updates
///
///
///
/// Note: These tests require Docker and the echo-slice test artifact.
/// Uses echo-slice at current version (OLD) and patch-bumped version (NEW) for version transition testing.
/// Run with: mvn test -pl e2e-tests -Dtest=RollingUpdateE2ETest
///
///
/// This test class uses a shared cluster for all tests to reduce startup overhead.
/// Tests run in order and each test cleans up previous state before running.
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
class RollingUpdateE2ETest {
    private static final Path PROJECT_ROOT = Path.of(System.getProperty("project.basedir", ".."));
    private static final String TEST_ARTIFACT_VERSION = System.getProperty("project.version", "0.17.0");
    private static final String ARTIFACT_BASE = "org.pragmatica-lite.aether.test:echo-slice-echo-service";
    private static final String OLD_VERSION = ARTIFACT_BASE + ":" + TEST_ARTIFACT_VERSION;
    private static final String NEW_VERSION = ARTIFACT_BASE + ":" + AetherCluster.ROLLING_UPDATE_NEW_VERSION;
    private static final Duration UPDATE_TIMEOUT = adapt(Duration.ofSeconds(120));

    // Common timeouts (CI gets 2x via adapt())
    private static final Duration DEPLOY_TIMEOUT = adapt(timeSpan(3).minutes().duration());
    private static final Duration POLL_INTERVAL = timeSpan(2).seconds().duration();
    private static final Duration CLEANUP_TIMEOUT = adapt(timeSpan(2).minutes().duration());

    private static AetherCluster cluster;

    @BeforeAll
    static void createCluster() {
        cluster = AetherCluster.aetherCluster(5, PROJECT_ROOT);
        cluster.start();
        cluster.awaitQuorum();
        cluster.awaitAllHealthy();
        cluster.awaitLeader();
        cluster.uploadTestArtifacts();
    }

    @AfterAll
    static void destroyCluster() {
        if (cluster != null) {
            cluster.close();
        }
    }

    @BeforeEach
    void cleanupAndPrepare() {
        // Wait for cluster stability
        cluster.awaitLeader();
        cluster.awaitAllHealthy();

        // Cancel any active rolling updates
        cancelActiveRollingUpdates();

        // Undeploy all slices
        undeployAllSlices();

        // Wait for clean state
        awaitNoSlices();

        // Deploy OLD_VERSION baseline
        deployOldVersion();
    }

    @Test
    @Order(1)
    void rollingUpdate_deploysNewVersion_withoutTraffic() {
        // Start rolling update (Stage 1: Deploy)
        var response = startRollingUpdate(NEW_VERSION, 3);
        assertThat(response).doesNotContain("\"error\"");

        // Wait for new version to be deployed
        await().atMost(UPDATE_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(() -> {
                   if (sliceHasFailed(NEW_VERSION)) {
                       throw new AssertionError("Slice deployment failed: " + NEW_VERSION);
                   }
               })
               .until(() -> sliceIsActive(NEW_VERSION));

        // Both versions should be active (use cluster-wide status)
        var slicesStatus = cluster.anyNode().getSlicesStatus();
        assertThat(slicesStatus).contains(OLD_VERSION);
        assertThat(slicesStatus).contains(NEW_VERSION);

        // New version should have 0% traffic initially (routing format: "new:old")
        var updateStatus = getUpdateStatus();
        assertThat(updateStatus).contains("\"state\":\"DEPLOYED\"");
        assertThat(updateStatus).contains("\"routing\":\"0:");
    }

    @Test
    @Order(2)
    void rollingUpdate_graduallyShiftsTraffic() {
        startRollingUpdate(NEW_VERSION, 3);
        await().atMost(UPDATE_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(() -> {
                   if (sliceHasFailed(NEW_VERSION)) {
                       throw new AssertionError("Slice deployment failed: " + NEW_VERSION);
                   }
               })
               .until(() -> sliceIsActive(NEW_VERSION));

        // Shift traffic 1:3 (25% to new) - routing format is "new:old"
        adjustRouting("1:3");
        await().atMost(Duration.ofSeconds(30))
               .pollInterval(POLL_INTERVAL)
               .until(() -> getUpdateStatus().contains("\"routing\":\"1:3\""));

        // Shift traffic 1:1 (50% to new)
        adjustRouting("1:1");
        await().atMost(Duration.ofSeconds(30))
               .pollInterval(POLL_INTERVAL)
               .until(() -> getUpdateStatus().contains("\"routing\":\"1:1\""));

        // Shift traffic 3:1 (75% to new)
        adjustRouting("3:1");
        await().atMost(Duration.ofSeconds(30))
               .pollInterval(POLL_INTERVAL)
               .until(() -> getUpdateStatus().contains("\"routing\":\"3:1\""));
    }

    @Test
    @Order(3)
    @Disabled("Flaky - timeout in leader failover/partition recovery scenarios")
    void rollingUpdate_completion_removesOldVersion() {
        startRollingUpdate(NEW_VERSION, 3);
        await().atMost(UPDATE_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(() -> {
                   if (sliceHasFailed(NEW_VERSION)) {
                       throw new AssertionError("Slice deployment failed: " + NEW_VERSION);
                   }
               })
               .until(() -> sliceIsActive(NEW_VERSION));

        // Route all traffic to new version
        adjustRouting("1:0");

        // Complete the update
        completeUpdate();

        // Old version should be removed (use cluster-wide status)
        await().atMost(Duration.ofSeconds(90))
               .pollInterval(POLL_INTERVAL)
               .until(() -> {
                   var status = cluster.anyNode().getSlicesStatus();
                   return !status.contains(OLD_VERSION) && status.contains(NEW_VERSION);
               });
    }

    @Test
    @Order(4)
    @Disabled("Flaky - timeout in leader failover/partition recovery scenarios")
    void rollingUpdate_rollback_restoresOldVersion() {
        startRollingUpdate(NEW_VERSION, 3);
        await().atMost(UPDATE_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(() -> {
                   if (sliceHasFailed(NEW_VERSION)) {
                       throw new AssertionError("Slice deployment failed: " + NEW_VERSION);
                   }
               })
               .until(() -> sliceIsActive(NEW_VERSION));

        // Shift some traffic to new version
        adjustRouting("1:1");

        // Rollback
        var rollbackResponse = rollback();
        assertThat(rollbackResponse).doesNotContain("\"error\"");

        // After rollback completes, verify old version remains and new version is removed
        await().atMost(Duration.ofSeconds(90))
               .pollInterval(POLL_INTERVAL)
               .until(() -> {
                   var slicesStatus = cluster.anyNode().getSlicesStatus();
                   return slicesStatus.contains(OLD_VERSION) && !slicesStatus.contains(NEW_VERSION);
               });
    }

    @Test
    @Order(5)
    void rollingUpdate_maintainsRequestContinuity() throws InterruptedException {
        // Start background load
        var loadRunning = new java.util.concurrent.atomic.AtomicBoolean(true);
        var successfulRequests = new java.util.concurrent.atomic.AtomicInteger(0);
        var failedRequests = new java.util.concurrent.atomic.AtomicInteger(0);

        var loadThread = new Thread(() -> {
            while (loadRunning.get()) {
                try {
                    // Simulate request to slice
                    var response = cluster.anyNode().getHealth();
                    if (!response.contains("\"error\"")) {
                        successfulRequests.incrementAndGet();
                    } else {
                        failedRequests.incrementAndGet();
                    }
                } catch (Exception e) {
                    failedRequests.incrementAndGet();
                }
                sleep(Duration.ofMillis(50));
            }
        });

        loadThread.start();

        // Perform rolling update
        startRollingUpdate(NEW_VERSION, 3);
        await().atMost(UPDATE_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(() -> {
                   if (sliceHasFailed(NEW_VERSION)) {
                       throw new AssertionError("Slice deployment failed: " + NEW_VERSION);
                   }
               })
               .until(() -> sliceIsActive(NEW_VERSION));

        adjustRouting("1:3");
        sleep(Duration.ofSeconds(2));
        adjustRouting("1:1");
        sleep(Duration.ofSeconds(2));
        adjustRouting("3:1");
        sleep(Duration.ofSeconds(2));
        adjustRouting("1:0");
        completeUpdate();

        // Stop load
        loadRunning.set(false);
        loadThread.join(5000);

        // Check results
        var totalRequests = successfulRequests.get() + failedRequests.get();
        var successRate = (double) successfulRequests.get() / totalRequests;

        assertThat(totalRequests).isGreaterThan(10);
        assertThat(successRate).isGreaterThan(0.95); // 95% success rate
    }

    @Test
    @Order(6)
    void rollingUpdate_nodeFailure_continuesUpdate() {
        startRollingUpdate(NEW_VERSION, 3);
        await().atMost(UPDATE_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(() -> {
                   if (sliceHasFailed(NEW_VERSION)) {
                       throw new AssertionError("Slice deployment failed: " + NEW_VERSION);
                   }
               })
               .until(() -> sliceIsActive(NEW_VERSION));

        // Kill a node during update
        cluster.killNode("node-3");
        cluster.awaitQuorum();

        // Update should continue
        adjustRouting("1:1");

        var status = getUpdateStatus();
        assertThat(status).contains("\"state\":\"ROUTING\"");

        // Restore node
        cluster.node("node-3").start();
        cluster.awaitQuorum();

        // Complete update
        adjustRouting("1:0");
        completeUpdate();
    }

    // ===== Cleanup Helpers =====

    private void cancelActiveRollingUpdates() {
        try {
            var leader = cluster.leader()
                                .toResult(Causes.cause("No leader"))
                                .unwrap();

            // Try to complete or rollback any active updates
            var updatesJson = leader.getRollingUpdates();
            System.out.println("[DEBUG] Active rolling updates: " + updatesJson);

            // If there are active updates, try to cancel them
            if (updatesJson.contains("\"state\":")) {
                // Try rollback first
                var rollbackResult = leader.post("/api/rolling-update/current/rollback", "{}");
                System.out.println("[DEBUG] Rollback result: " + rollbackResult);

                // If rollback fails, try complete
                if (rollbackResult.contains("\"error\"")) {
                    var completeResult = leader.post("/api/rolling-update/current/complete", "{}");
                    System.out.println("[DEBUG] Complete result: " + completeResult);
                }

                // Wait for update to finish
                await().atMost(CLEANUP_TIMEOUT)
                       .pollInterval(POLL_INTERVAL)
                       .ignoreExceptions()
                       .until(() -> {
                           var status = leader.getRollingUpdates();
                           return !status.contains("\"state\":\"DEPLOYED\"") &&
                                  !status.contains("\"state\":\"ROUTING\"");
                       });
            }
        } catch (Exception e) {
            System.out.println("[DEBUG] Error canceling rolling updates: " + e.getMessage());
        }
    }

    private void undeployAllSlices() {
        try {
            var leader = cluster.leader()
                                .toResult(Causes.cause("No leader"))
                                .unwrap();

            // Get list of deployed slices (cluster-wide view)
            var slices = leader.getSlicesStatus();
            System.out.println("[DEBUG] Deployed slices: " + slices);

            // Undeploy each known slice
            if (slices.contains(OLD_VERSION)) {
                var result = leader.undeploy(OLD_VERSION);
                System.out.println("[DEBUG] Undeploy " + OLD_VERSION + ": " + result);
            }
            if (slices.contains(NEW_VERSION)) {
                var result = leader.undeploy(NEW_VERSION);
                System.out.println("[DEBUG] Undeploy " + NEW_VERSION + ": " + result);
            }
        } catch (Exception e) {
            System.out.println("[DEBUG] Error undeploying slices: " + e.getMessage());
        }
    }

    private void awaitNoSlices() {
        undeployAllSlices();
        cluster.awaitSliceUndeployed(OLD_VERSION, CLEANUP_TIMEOUT);
        cluster.awaitSliceUndeployed(NEW_VERSION, CLEANUP_TIMEOUT);
    }

    private void deployOldVersion() {
        var leader = cluster.leader()
                            .toResult(Causes.cause("No leader elected"))
                            .unwrap();
        leader.deploy(OLD_VERSION, 3);
        await().atMost(DEPLOY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(() -> {
                   if (sliceHasFailed(OLD_VERSION)) {
                       throw new AssertionError("Slice deployment failed: " + OLD_VERSION);
                   }
               })
               .until(() -> sliceIsActive(OLD_VERSION));
    }

    // ===== API Helpers =====

    private String startRollingUpdate(String newVersion, int instances) {
        // POST /api/rolling-update/start
        var artifactBase = newVersion.substring(0, newVersion.lastIndexOf(':'));
        var version = newVersion.substring(newVersion.lastIndexOf(':') + 1);
        var body = "{\"artifactBase\":\"" + artifactBase + "\",\"version\":\"" + version +
                   "\",\"instances\":" + instances + "}";
        var response = post("/api/rolling-update/start", body);
        System.out.println("[DEBUG] Rolling update start response: " + response);
        return response;
    }

    private String getUpdateStatus() {
        return get("/api/rolling-updates");
    }

    private void adjustRouting(String ratio) {
        var body = "{\"routing\":\"" + ratio + "\"}";
        post("/api/rolling-update/current/routing", body);
    }

    private void completeUpdate() {
        post("/api/rolling-update/current/complete", "{}");
    }

    private String rollback() {
        return post("/api/rolling-update/current/rollback", "{}");
    }

    private String get(String path) {
        return cluster.anyNode().get(path);
    }

    private String post(String path, String body) {
        // Rolling update operations require leader
        var leader = cluster.leader()
                            .toResult(Causes.cause("No leader"))
                            .unwrap();
        return leader.post(path, body);
    }

    private boolean sliceIsActive(String artifact) {
        try {
            var state = cluster.anyNode().getSliceState(artifact);
            System.out.println("[DEBUG] Slice " + artifact + " state: " + state);
            return "ACTIVE".equals(state);
        } catch (Exception e) {
            System.out.println("[DEBUG] Error checking slice state: " + e.getMessage());
            return false;
        }
    }

    private boolean sliceHasFailed(String artifact) {
        try {
            var state = cluster.anyNode().getSliceState(artifact);
            return "FAILED".equals(state);
        } catch (Exception e) {
            return false;
        }
    }

    // ===== Utility Helpers =====

    private void sleep(TimeSpan duration) {
        try {
            Thread.sleep(duration.duration().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
