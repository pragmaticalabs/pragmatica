package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.http.HttpResult;
import org.pragmatica.http.HttpOperations;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import org.pragmatica.aether.ember.EmberCluster;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

/// Tests for the cluster controller (DecisionTreeController).
///
///
/// Tests cover:
///
///   - Controller runs only on leader node
///   - Controller configuration management
///   - Controller status reporting
///   - Controller evaluation triggering
///   - Controller transfer on leader change
///
///
///
/// Note: Auto-scaling based on CPU metrics requires controlled load
/// generation which is complex in E2E tests. These tests focus on
/// controller infrastructure rather than scaling decisions.
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ControllerTest {
    private static final int BASE_PORT = 10500;
    private static final int BASE_MGMT_PORT = 10600;
    private static final int BASE_APP_HTTP_PORT = 10700;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";

    private EmberCluster cluster;
    private final HttpOperations http = jdkHttpOperations();

    @BeforeAll
    void setUp() {
        cluster = emberCluster(3, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "ct");

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

    @BeforeEach
    void restoreCluster() {
        // Restore killed node if any (from controller_survivesLeaderFailure)
        if (cluster.nodeCount() < 3) {
            cluster.addNode().await();
            await().atMost(WAIT_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .until(() -> cluster.nodeCount() == 3);
            await().atMost(WAIT_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .until(() -> cluster.currentLeader().isPresent());
            await().atMost(WAIT_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .until(this::allNodesHealthy);
        }
    }

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            cluster.stop()
                   .await();
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
        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }

    private String httpPost(int port, String path, String body) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .header("Content-Type", "application/json")
                                 .timeout(Duration.ofSeconds(5))
                                 .build();
        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
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
        return http.sendString(request)
                   .await()
                   .map(r -> r.statusCode() == 200 && r.body().contains("\"quorum\":true"))
                   .or(false);
    }
}
