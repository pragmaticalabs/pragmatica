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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.forge.ForgeCluster.forgeCluster;

/**
 * Rolling update tests using ForgeCluster.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Two-stage rolling update (deploy then route)</li>
 *   <li>Traffic ratio-based routing</li>
 *   <li>Rollback scenarios</li>
 *   <li>Request continuity during updates</li>
 *   <li>Node failure during update</li>
 * </ul>
 */
@Execution(ExecutionMode.SAME_THREAD)
class RollingUpdateTest {
    private static final int BASE_PORT = 5270;
    private static final int BASE_MGMT_PORT = 5370;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final String OLD_VERSION = "org.pragmatica-lite.aether.example:inventory:0.0.1-test";
    private static final String NEW_VERSION = "org.pragmatica-lite.aether.example:inventory:0.0.2-test";
    private static final String ARTIFACT_BASE = "org.pragmatica-lite.aether.example:inventory";
    private static final String NEW_VERSION_NUMBER = "0.0.2-test";

    private ForgeCluster cluster;
    private HttpClient httpClient;

    @BeforeEach
    void setUp(TestInfo testInfo) {
        int portOffset = getPortOffset(testInfo);
        cluster = forgeCluster(5, BASE_PORT + portOffset, BASE_MGMT_PORT + portOffset, "ru");
        httpClient = HttpClient.newBuilder()
                               .connectTimeout(Duration.ofSeconds(5))
                               .build();

        cluster.start()
               .await()
               .onFailure(cause -> {
                   throw new AssertionError("Cluster start failed: " + cause.message());
               });

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader().isPresent());

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(this::allNodesHealthy);

        // Stabilization time for consensus
        sleep(Duration.ofSeconds(3));

        // Deploy old version
        var deployResponse = deploy(OLD_VERSION, 3);
        assertDeploymentSucceeded(deployResponse);

        await().atMost(Duration.ofSeconds(60))
               .pollInterval(POLL_INTERVAL)
               .failFast(() -> {
                   var slices = getSlices();
                   if (slices.contains("\"error\"")) {
                       throw new AssertionError("Slice query failed: " + slices);
                   }
                   if (sliceHasFailed(OLD_VERSION)) {
                       throw new AssertionError("Slice deployment failed: " + OLD_VERSION);
                   }
               })
               .until(() -> sliceIsActive(OLD_VERSION));
    }

    private int getPortOffset(TestInfo testInfo) {
        return switch (testInfo.getTestMethod().map(m -> m.getName()).orElse("")) {
            case "rollingUpdate_deploysNewVersion_withoutTraffic" -> 0;
            case "rollingUpdate_graduallyShiftsTraffic" -> 20;
            case "rollingUpdate_completion_removesOldVersion" -> 40;
            case "rollingUpdate_rollback_restoresOldVersion" -> 60;
            case "rollingUpdate_maintainsRequestContinuity" -> 80;
            case "rollingUpdate_nodeFailure_continuesUpdate" -> 100;
            default -> 120;
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
    void rollingUpdate_deploysNewVersion_withoutTraffic() {
        var response = startRollingUpdate(NEW_VERSION_NUMBER, 3);
        assertThat(response).doesNotContain("\"error\"");

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(() -> {
                   if (sliceHasFailed(NEW_VERSION)) {
                       throw new AssertionError("Slice deployment failed: " + NEW_VERSION);
                   }
               })
               .until(() -> sliceIsActive(NEW_VERSION));

        var slices = getSlices();
        assertThat(slices).contains(OLD_VERSION);
        assertThat(slices).contains(NEW_VERSION);

        var updateStatus = getUpdateStatus();
        assertThat(updateStatus).contains("\"state\":\"DEPLOYED\"");
    }

    @Test
    void rollingUpdate_graduallyShiftsTraffic() {
        startRollingUpdate(NEW_VERSION_NUMBER, 3);
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(() -> {
                   if (sliceHasFailed(NEW_VERSION)) {
                       throw new AssertionError("Slice deployment failed: " + NEW_VERSION);
                   }
               })
               .until(() -> sliceIsActive(NEW_VERSION));

        // Shift traffic 1:3 (25% to new)
        adjustRouting("1:3");

        var status = getUpdateStatus();
        assertThat(status).contains("\"routing\":\"1:3\"");

        // Shift traffic 1:1 (50% to new)
        adjustRouting("1:1");

        status = getUpdateStatus();
        assertThat(status).contains("\"routing\":\"1:1\"");

        // Shift traffic 3:1 (75% to new)
        adjustRouting("3:1");

        status = getUpdateStatus();
        assertThat(status).contains("\"routing\":\"3:1\"");
    }

    @Test
    void rollingUpdate_completion_removesOldVersion() {
        startRollingUpdate(NEW_VERSION_NUMBER, 3);
        await().atMost(WAIT_TIMEOUT)
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

        // Old version should be removed
        await().atMost(Duration.ofSeconds(60))
               .pollInterval(POLL_INTERVAL)
               .until(() -> {
                   var slices = getSlices();
                   return !slices.contains(OLD_VERSION) && slices.contains(NEW_VERSION);
               });
    }

    @Test
    void rollingUpdate_rollback_restoresOldVersion() {
        startRollingUpdate(NEW_VERSION_NUMBER, 3);
        await().atMost(WAIT_TIMEOUT)
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

        // After rollback completes, update is removed from active list (terminal state)
        // Verify by checking that new version slices are removed and old version remains
        await().atMost(Duration.ofSeconds(30))
               .pollInterval(POLL_INTERVAL)
               .until(() -> {
                   var slices = getSlices();
                   return slices.contains(OLD_VERSION) && !slices.contains(NEW_VERSION);
               });
    }

    @Test
    void rollingUpdate_maintainsRequestContinuity() throws InterruptedException {
        var loadRunning = new AtomicBoolean(true);
        var successfulRequests = new AtomicInteger(0);
        var failedRequests = new AtomicInteger(0);

        var loadThread = new Thread(() -> {
            while (loadRunning.get()) {
                try {
                    var response = getHealth();
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
        startRollingUpdate(NEW_VERSION_NUMBER, 3);
        await().atMost(WAIT_TIMEOUT)
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
        assertThat(successRate).isGreaterThan(0.95);
    }

    @Test
    void rollingUpdate_nodeFailure_continuesUpdate() {
        startRollingUpdate(NEW_VERSION_NUMBER, 3);
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(() -> {
                   if (sliceHasFailed(NEW_VERSION)) {
                       throw new AssertionError("Slice deployment failed: " + NEW_VERSION);
                   }
               })
               .until(() -> sliceIsActive(NEW_VERSION));

        // Kill a non-leader node during update
        var leaderId = cluster.currentLeader().or("ru-1");
        var nodeToKill = leaderId.equals("ru-3") ? "ru-4" : "ru-3";

        cluster.killNode(nodeToKill)
               .await();

        // Wait for quorum to stabilize
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader().isPresent());

        // Update should continue
        adjustRouting("1:1");

        var status = getUpdateStatus();
        assertThat(status).contains("\"state\":\"ROUTING\"");

        // Add a new node to replace the killed one
        cluster.addNode()
               .await();

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader().isPresent());

        // Complete update
        adjustRouting("1:0");
        completeUpdate();
    }

    // ===== HTTP Helpers =====

    private String get(String path) {
        var port = getLeaderPort();
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api" + path))
                                 .GET()
                                 .timeout(Duration.ofSeconds(10))
                                 .build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (IOException | InterruptedException e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private String post(String path, String body) {
        var port = getLeaderPort();
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api" + path))
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(Duration.ofSeconds(10))
                                 .build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (IOException | InterruptedException e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private int getLeaderPort() {
        return cluster.getLeaderManagementPort()
                      .or(BASE_MGMT_PORT);
    }

    // ===== API Helpers =====

    private String deploy(String artifact, int instances) {
        var body = "{\"artifact\":\"" + artifact + "\",\"instances\":" + instances + "}";
        return post("/deploy", body);
    }

    private void assertDeploymentSucceeded(String response) {
        assertThat(response)
            .describedAs("Deployment response")
            .doesNotContain("\"error\"")
            .contains("\"status\":\"deployed\"");
    }

    private String startRollingUpdate(String newVersion, int instances) {
        var body = "{\"artifactBase\":\"" + ARTIFACT_BASE + "\",\"version\":\"" + newVersion +
                   "\",\"instances\":" + instances + "}";
        return post("/rolling-update/start", body);
    }

    private String getUpdateStatus() {
        return get("/rolling-updates");
    }

    private void adjustRouting(String ratio) {
        var body = "{\"routing\":\"" + ratio + "\"}";
        post("/rolling-update/current/routing", body);
    }

    private void completeUpdate() {
        post("/rolling-update/current/complete", "{}");
    }

    private String rollback() {
        return post("/rolling-update/current/rollback", "{}");
    }

    private String getSlices() {
        var slicesStatus = cluster.slicesStatus();
        var artifacts = slicesStatus.stream()
                                    .map(s -> "\"" + s.artifact() + "\"")
                                    .toList();
        return "{\"slices\":[" + String.join(",", artifacts) + "]}";
    }

    private String getHealth() {
        return get("/health");
    }

    private boolean sliceIsActive(String artifact) {
        try {
            var slicesStatus = cluster.slicesStatus();
            return slicesStatus.stream()
                               .anyMatch(s -> s.artifact().equals(artifact) &&
                                              s.state().equals("ACTIVE"));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean sliceHasFailed(String artifact) {
        try {
            var slicesStatus = cluster.slicesStatus();
            return slicesStatus.stream()
                               .anyMatch(s -> s.artifact().equals(artifact) &&
                                              s.state().equals("FAILED"));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean allNodesHealthy() {
        var status = cluster.status();
        return status.nodes().stream()
                     .allMatch(node -> checkNodeHealth(node.mgmtPort()));
    }

    private boolean checkNodeHealth(int port) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/health"))
                                 .GET()
                                 .timeout(Duration.ofSeconds(5))
                                 .build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && response.body().contains("\"quorum\":true");
        } catch (IOException | InterruptedException e) {
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
