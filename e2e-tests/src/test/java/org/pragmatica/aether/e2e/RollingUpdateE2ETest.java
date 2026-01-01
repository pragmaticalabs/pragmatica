package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.e2e.containers.AetherCluster;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * E2E tests for rolling update functionality.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Two-stage rolling update (deploy then route)</li>
 *   <li>Traffic ratio-based routing</li>
 *   <li>Health-based auto-progression</li>
 *   <li>Rollback scenarios</li>
 *   <li>Request continuity during updates</li>
 * </ul>
 *
 * <p>Note: These tests require the rolling update implementation.
 * Currently disabled until implementation is complete.
 */
@Disabled("Pending rolling update implementation")
class RollingUpdateE2ETest {
    private static final Path PROJECT_ROOT = Path.of(System.getProperty("project.basedir", ".."));
    private static final String OLD_VERSION = "org.pragmatica-lite.aether:example-slice:0.6.3";
    private static final String NEW_VERSION = "org.pragmatica-lite.aether:example-slice:0.6.4";
    private static final Duration UPDATE_TIMEOUT = Duration.ofSeconds(120);
    private AetherCluster cluster;

    @BeforeEach
    void setUp() {
        cluster = AetherCluster.create(5, PROJECT_ROOT);
        cluster.start();
        cluster.awaitQuorum();

        // Deploy old version
        cluster.anyNode().deploy(OLD_VERSION, 3);
        await().atMost(Duration.ofSeconds(60))
               .until(() -> sliceIsActive(OLD_VERSION));
    }

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.close();
        }
    }

    @Test
    void rollingUpdate_deploysNewVersion_withoutTraffic() {
        // Start rolling update (Stage 1: Deploy)
        var response = startRollingUpdate(NEW_VERSION, 3);
        assertThat(response).doesNotContain("\"error\"");

        // Wait for new version to be deployed
        await().atMost(UPDATE_TIMEOUT)
               .until(() -> sliceIsActive(NEW_VERSION));

        // Both versions should be active
        var slices = cluster.anyNode().getSlices();
        assertThat(slices).contains(OLD_VERSION);
        assertThat(slices).contains(NEW_VERSION);

        // New version should have 0% traffic initially
        var updateStatus = getUpdateStatus();
        assertThat(updateStatus).contains("\"state\":\"DEPLOYED\"");
        assertThat(updateStatus).contains("\"newWeight\":0");
    }

    @Test
    void rollingUpdate_graduallyShiftsTraffic() {
        startRollingUpdate(NEW_VERSION, 3);
        await().atMost(UPDATE_TIMEOUT)
               .until(() -> sliceIsActive(NEW_VERSION));

        // Shift traffic 1:3 (25% to new)
        adjustRouting("1:3");

        var status = getUpdateStatus();
        assertThat(status).contains("\"newWeight\":1");
        assertThat(status).contains("\"oldWeight\":3");

        // Shift traffic 1:1 (50% to new)
        adjustRouting("1:1");

        status = getUpdateStatus();
        assertThat(status).contains("\"newWeight\":1");
        assertThat(status).contains("\"oldWeight\":1");

        // Shift traffic 3:1 (75% to new)
        adjustRouting("3:1");

        status = getUpdateStatus();
        assertThat(status).contains("\"newWeight\":3");
        assertThat(status).contains("\"oldWeight\":1");
    }

    @Test
    void rollingUpdate_completion_removesOldVersion() {
        startRollingUpdate(NEW_VERSION, 3);
        await().atMost(UPDATE_TIMEOUT)
               .until(() -> sliceIsActive(NEW_VERSION));

        // Route all traffic to new version
        adjustRouting("1:0");

        // Complete the update
        completeUpdate();

        // Old version should be removed
        await().atMost(Duration.ofSeconds(30))
               .until(() -> {
                   var slices = cluster.anyNode().getSlices();
                   return !slices.contains(OLD_VERSION) && slices.contains(NEW_VERSION);
               });
    }

    @Test
    void rollingUpdate_rollback_restoresOldVersion() {
        startRollingUpdate(NEW_VERSION, 3);
        await().atMost(UPDATE_TIMEOUT)
               .until(() -> sliceIsActive(NEW_VERSION));

        // Shift some traffic to new version
        adjustRouting("1:1");

        // Rollback
        rollback();

        // All traffic should go to old version
        var status = getUpdateStatus();
        assertThat(status).contains("\"state\":\"ROLLED_BACK\"");

        // New version should be removed
        await().atMost(Duration.ofSeconds(30))
               .until(() -> {
                   var slices = cluster.anyNode().getSlices();
                   return slices.contains(OLD_VERSION) && !slices.contains(NEW_VERSION);
               });
    }

    @Test
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
    void rollingUpdate_nodeFailure_continuesUpdate() {
        startRollingUpdate(NEW_VERSION, 3);
        await().atMost(UPDATE_TIMEOUT)
               .until(() -> sliceIsActive(NEW_VERSION));

        // Kill a node during update
        cluster.killNode("node-3");
        cluster.awaitQuorum();

        // Update should continue
        adjustRouting("1:1");

        var status = getUpdateStatus();
        assertThat(status).contains("\"state\":\"ROUTING\"");

        // Restore node
        cluster.restartNode("node-3");
        cluster.awaitQuorum();

        // Complete update
        adjustRouting("1:0");
        completeUpdate();
    }

    // ===== API Helpers =====

    private String startRollingUpdate(String newVersion, int instances) {
        // POST /rolling-update/start
        var artifact = newVersion.substring(0, newVersion.lastIndexOf(':'));
        var version = newVersion.substring(newVersion.lastIndexOf(':') + 1);
        var body = "{\"artifact\":\"" + artifact + "\",\"version\":\"" + version +
                   "\",\"instances\":" + instances + "}";
        return post("/rolling-update/start", body);
    }

    private String getUpdateStatus() {
        return get("/rolling-updates");
    }

    private void adjustRouting(String ratio) {
        var parts = ratio.split(":");
        var body = "{\"newWeight\":" + parts[0] + ",\"oldWeight\":" + parts[1] + "}";
        post("/rolling-update/current/routing", body);
    }

    private void completeUpdate() {
        post("/rolling-update/current/complete", "{}");
    }

    private void rollback() {
        post("/rolling-update/current/rollback", "{}");
    }

    private String get(String path) {
        return cluster.anyNode().getHealth().replace("/health", path);
    }

    private String post(String path, String body) {
        // Using health endpoint base URL pattern
        return "{}"; // Placeholder until implementation
    }

    private boolean sliceIsActive(String artifact) {
        try {
            var slices = cluster.anyNode().getSlices();
            return slices.contains(artifact);
        } catch (Exception e) {
            return false;
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
