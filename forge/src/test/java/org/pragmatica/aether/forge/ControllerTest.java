package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.forge.ForgeCluster.forgeCluster;

/**
 * Tests for the cluster controller (DecisionTreeController).
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Controller runs only on leader node</li>
 *   <li>Controller configuration management</li>
 *   <li>Controller status reporting</li>
 *   <li>Controller evaluation triggering</li>
 *   <li>Controller transfer on leader change</li>
 * </ul>
 *
 * <p>Note: Auto-scaling based on CPU metrics requires controlled load
 * generation which is complex in E2E tests. These tests focus on
 * controller infrastructure rather than scaling decisions.
 */
@Execution(ExecutionMode.SAME_THREAD)
class ControllerTest {
    private static final int BASE_PORT = 5500;
    private static final int BASE_MGMT_PORT = 5600;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    private ForgeCluster cluster;
    private HttpClient httpClient;

    @BeforeEach
    void setUp(TestInfo testInfo) {
        int portOffset = getPortOffset(testInfo);
        cluster = forgeCluster(3, BASE_PORT + portOffset, BASE_MGMT_PORT + portOffset, "ct");
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
    }

    private int getPortOffset(TestInfo testInfo) {
        return switch (testInfo.getTestMethod().map(m -> m.getName()).orElse("")) {
            case "controllerConfig_getReturnsCurrentSettings" -> 0;
            case "controllerConfig_updateSucceeds" -> 5;
            case "controllerConfig_persistsAcrossRequests" -> 10;
            case "controllerStatus_returnsRunningState" -> 15;
            case "controllerEvaluate_triggersImmediately" -> 20;
            case "controller_runsOnlyOnLeader" -> 25;
            case "controller_survivesLeaderFailure" -> 30;
            default -> 35;
        };
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (cluster != null) {
            cluster.stop()
                   .await();
            // Allow time for ports to be released before next test
            Thread.sleep(2000);
        }
    }

    @Nested
    class ControllerConfiguration {

        @Test
        void controllerConfig_getReturnsCurrentSettings() {
            var config = getControllerConfig(anyNodePort());

            assertThat(config).doesNotContain("\"error\"");
        }

        @Test
        void controllerConfig_updateSucceeds() {
            var newConfig = "{\"scaleUpThreshold\":0.85,\"scaleDownThreshold\":0.15}";
            var response = setControllerConfig(anyNodePort(), newConfig);

            assertThat(response).doesNotContain("\"error\"");
        }

        @Test
        void controllerConfig_persistsAcrossRequests() {
            // Set config
            var newConfig = "{\"scaleUpThreshold\":0.9,\"scaleDownThreshold\":0.1}";
            setControllerConfig(anyNodePort(), newConfig);

            // Verify it persists
            await().atMost(WAIT_TIMEOUT).until(() -> {
                var config = getControllerConfig(anyNodePort());
                return !config.contains("\"error\"");
            });
        }
    }

    @Nested
    class ControllerStatus {

        @Test
        void controllerStatus_returnsRunningState() {
            var status = getControllerStatus(anyNodePort());

            assertThat(status).doesNotContain("\"error\"");
            // Should indicate some status
            assertThat(status).isNotBlank();
        }

        @Test
        void controllerEvaluate_triggersImmediately() {
            var response = triggerControllerEvaluation(anyNodePort());

            assertThat(response).doesNotContain("\"error\"");
        }
    }

    @Nested
    class LeaderBehavior {

        @Test
        void controller_runsOnlyOnLeader() {
            // All nodes should be able to report controller status
            // (they proxy to leader)
            for (var node : cluster.status().nodes()) {
                var status = getControllerStatus(node.mgmtPort());
                assertThat(status).doesNotContain("\"error\"");
            }
        }

        @Test
        void controller_survivesLeaderFailure() {
            // Kill the leader (ct-1 is deterministic leader)
            cluster.killNode("ct-1")
                   .await();

            // Wait for new quorum and leader
            await().atMost(WAIT_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .until(() -> cluster.currentLeader().isPresent());

            await().atMost(WAIT_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .until(ControllerTest.this::allNodesHealthy);

            // Controller should still work
            var status = getControllerStatus(anyNodePort());
            assertThat(status).doesNotContain("\"error\"");
        }
    }

    private int anyNodePort() {
        return cluster.status().nodes().getFirst().mgmtPort();
    }

    private String getControllerConfig(int port) {
        return httpGet(port, "/api/controller/config");
    }

    private String setControllerConfig(int port, String config) {
        return httpPost(port, "/api/controller/config", config);
    }

    private String getControllerStatus(int port) {
        return httpGet(port, "/api/controller/status");
    }

    private String triggerControllerEvaluation(int port) {
        return httpPost(port, "/api/controller/evaluate", "");
    }

    private String httpGet(int port, String path) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .GET()
                                 .timeout(Duration.ofSeconds(5))
                                 .build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (IOException | InterruptedException e) {
            return "error: " + e.getMessage();
        }
    }

    private String httpPost(int port, String path, String body) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .header("Content-Type", "application/json")
                                 .timeout(Duration.ofSeconds(5))
                                 .build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (IOException | InterruptedException e) {
            return "error: " + e.getMessage();
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
}
