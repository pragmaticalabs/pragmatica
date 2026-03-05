package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.pragmatica.aether.slice.SliceState;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import org.pragmatica.aether.ember.EmberCluster;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// Integration tests for EmberCluster startup, blueprint deployment, and shutdown.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EmberClusterIntegrationTest {
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(180);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final String TEST_ARTIFACT = TestArtifacts.ECHO_SLICE;

    private EmberCluster cluster;
    private HttpClient httpClient;

    private static final int BASE_PORT = 12500;
    private static final int BASE_MGMT_PORT = 12600;

    @BeforeAll
    void setUp() {
        cluster = emberCluster(3, BASE_PORT, BASE_MGMT_PORT, "fci");
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

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            cluster.stop()
                   .await();
        }
    }

    @Test
    @Order(1)
    void clusterStartup_withThreeNodes_electsLeader() {
        assertThat(cluster.nodeCount()).isEqualTo(3);
        assertThat(cluster.currentLeader().isPresent()).isTrue();

        var leaderId = cluster.currentLeader().unwrap();
        assertThat(leaderId).startsWith("fci-");
    }

    @Test
    @Order(2)
    void blueprintDeployment_deploysSlices_andReachesActiveState() {
        var leaderPort = cluster.getLeaderManagementPort()
                                .unwrap();

        var blueprintContent = """
            id = "org.test:blueprint:1.0.0"

            [[slices]]
            artifact = "%s"
            instances = 1
            """.formatted(TEST_ARTIFACT);
        deployBlueprint(leaderPort, blueprintContent);

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .untilAsserted(() -> {
                   var slicesStatus = cluster.slicesStatus();
                   assertThat(slicesStatus).isNotEmpty();

                   var allActive = slicesStatus.stream()
                                               .allMatch(status -> status.state().equals(SliceState.ACTIVE.name()));
                   assertThat(allActive)
                       .as("All slices should reach ACTIVE state. Current: %s", slicesStatus)
                       .isTrue();
               });

        var slicesStatus = cluster.slicesStatus();
        assertThat(slicesStatus).hasSize(1);

        var echoSlice = slicesStatus.stream()
                                    .filter(s -> s.artifact().contains("echo-slice"))
                                    .findFirst()
                                    .orElseThrow();
        assertThat(echoSlice.instances()).hasSize(1);
    }

    @Test
    @Order(3)
    void clusterShutdown_stopsAllNodes_gracefully() {
        assertThat(cluster.nodeCount()).isEqualTo(3);

        cluster.stop()
               .await()
               .onFailure(cause -> {
                   throw new AssertionError("Cluster stop failed: " + cause.message());
               });

        assertThat(cluster.nodeCount()).isZero();

        // Nullify cluster so tearDown does not try to stop it again
        cluster = null;
    }

    private void deployBlueprint(int port, String blueprintContent) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/blueprint"))
                                 .header("Content-Type", "application/toml")
                                 .POST(HttpRequest.BodyPublishers.ofString(blueprintContent))
                                 .timeout(Duration.ofSeconds(10))
                                 .build();

        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode())
                .as("Blueprint deployment should succeed. Response: %s", response.body())
                .isEqualTo(200);
        } catch (IOException | InterruptedException e) {
            throw new AssertionError("Blueprint deployment request failed: " + e.getMessage(), e);
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
