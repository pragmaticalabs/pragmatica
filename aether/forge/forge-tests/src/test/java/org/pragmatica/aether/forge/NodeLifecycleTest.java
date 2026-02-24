package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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

/// Tests for node lifecycle state management.
///
///
/// Validates that nodes register ON_DUTY lifecycle state after quorum,
/// and that drain/activate/shutdown transitions work correctly via the management API.
@SuppressWarnings({"JBCT-VO-01", "JBCT-NAM-01"})
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NodeLifecycleTest {
    private static final int BASE_PORT = 13000;
    private static final int BASE_MGMT_PORT = 13100;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    private ForgeCluster cluster;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        cluster = forgeCluster(3, BASE_PORT, BASE_MGMT_PORT, "lc");
        httpClient = HttpClient.newBuilder()
                               .connectTimeout(Duration.ofSeconds(5))
                               .build();

        cluster.start()
               .await()
               .onFailure(cause -> {
                   throw new AssertionError("Cluster start failed: " + cause.message());
               });

        awaitLeader();
        awaitAllNodesHealthy();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (cluster != null) {
            cluster.stop()
                   .await();
            Thread.sleep(3000);
        }
    }

    @Test
    @Order(1)
    void selfRegistration_onDutyAfterQuorum() {
        // All 3 nodes should have ON_DUTY lifecycle in KV after cluster start
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(this::allNodesOnDuty);

        var lifecycles = getAllLifecycleStates(anyNodePort());
        assertThat(lifecycles).contains("\"state\":\"ON_DUTY\"");
        assertThat(lifecycles).contains("lc-1");
        assertThat(lifecycles).contains("lc-2");
        assertThat(lifecycles).contains("lc-3");
    }

    @Test
    @Order(2)
    void drain_changesLifecycleState() {
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(this::allNodesOnDuty);

        // POST drain to lc-2
        var drainResponse = httpPost(anyNodePort(), "/api/node/drain/lc-2", "");
        assertThat(drainResponse).contains("\"success\":true");
        assertThat(drainResponse).contains("\"state\":\"DRAINING\"");

        // Verify via GET that lc-2 is now DRAINING
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> getNodeLifecycle(anyNodePort(), "lc-2").contains("\"state\":\"DRAINING\""));
    }

    @Test
    @Order(3)
    void cancelDrain_returnsToOnDuty() {
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(this::allNodesOnDuty);

        // First drain lc-2
        httpPost(anyNodePort(), "/api/node/drain/lc-2", "");
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> getNodeLifecycle(anyNodePort(), "lc-2").contains("\"state\":\"DRAINING\""));

        // Then activate it back
        var activateResponse = httpPost(anyNodePort(), "/api/node/activate/lc-2", "");
        assertThat(activateResponse).contains("\"success\":true");
        assertThat(activateResponse).contains("\"state\":\"ON_DUTY\"");

        // Verify via GET
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> getNodeLifecycle(anyNodePort(), "lc-2").contains("\"state\":\"ON_DUTY\""));
    }

    @Test
    @Order(4)
    void remoteShutdown_writesShuttingDown() {
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(this::allNodesOnDuty);

        // POST shutdown to lc-3
        var shutdownResponse = httpPost(anyNodePort(), "/api/node/shutdown/lc-3", "");
        assertThat(shutdownResponse).contains("\"success\":true");
        assertThat(shutdownResponse).contains("\"state\":\"SHUTTING_DOWN\"");

        // Verify SHUTTING_DOWN was written to KV (check before node potentially stops)
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> getNodeLifecycle(anyNodePort(), "lc-3").contains("\"state\":\"SHUTTING_DOWN\""));
    }

    // --- Helper methods ---

    private int anyNodePort() {
        return cluster.status().nodes().getFirst().mgmtPort();
    }

    private void awaitLeader() {
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader().isPresent());
    }

    private void awaitAllNodesHealthy() {
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(this::allNodesHealthy);
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

    private boolean allNodesOnDuty() {
        var lifecycles = getAllLifecycleStates(anyNodePort());
        return lifecycles.contains("lc-1") &&
               lifecycles.contains("lc-2") &&
               lifecycles.contains("lc-3") &&
               !lifecycles.contains("\"error\"");
    }

    private String getAllLifecycleStates(int port) {
        return httpGet(port, "/api/nodes/lifecycle");
    }

    private String getNodeLifecycle(int port, String nodeId) {
        return httpGet(port, "/api/node/lifecycle/" + nodeId);
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
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private String httpPost(int port, String path, String body) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
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
}
