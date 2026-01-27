package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.pragmatica.aether.slice.SliceState;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.forge.ForgeCluster.forgeCluster;

/**
 * Integration tests for ForgeCluster startup, blueprint deployment, and shutdown.
 */
class ForgeClusterIntegrationTest {
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(180);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final Path BLUEPRINT_PATH = Path.of("../examples/ecommerce/place-order/target/blueprint.toml");

    private ForgeCluster cluster;
    private HttpClient httpClient;

    private static final int BASE_PORT = 5770;
    private static final int BASE_MGMT_PORT = 5870;

    @BeforeEach
    void setUp(TestInfo testInfo) {
        int portOffset = getPortOffset(testInfo);
        cluster = forgeCluster(3, BASE_PORT + portOffset, BASE_MGMT_PORT + portOffset, "fci");
        httpClient = HttpClient.newBuilder()
                               .connectTimeout(Duration.ofSeconds(5))
                               .build();
    }

    private int getPortOffset(TestInfo testInfo) {
        return switch (testInfo.getTestMethod().map(m -> m.getName()).orElse("")) {
            case "clusterStartup_withThreeNodes_electsLeader" -> 0;
            case "blueprintDeployment_deploysSlices_andReachesActiveState" -> 5;
            case "clusterShutdown_stopsAllNodes_gracefully" -> 10;
            default -> 15;
        };
    }

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop()
                   .await();
        }
    }

    @Test
    void clusterStartup_withThreeNodes_electsLeader() {
        cluster.start()
               .await()
               .onFailure(cause -> {
                   throw new AssertionError("Cluster start failed: " + cause.message());
               });

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader().isPresent());

        assertThat(cluster.nodeCount()).isEqualTo(3);
        assertThat(cluster.currentLeader().isPresent()).isTrue();

        var leaderId = cluster.currentLeader().unwrap();
        assertThat(leaderId).startsWith("fci-");
    }

    @Test
    void blueprintDeployment_deploysSlices_andReachesActiveState() throws IOException {
        cluster.start()
               .await()
               .onFailure(cause -> {
                   throw new AssertionError("Cluster start failed: " + cause.message());
               });

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader().isPresent());

        // Wait for all nodes to be healthy (have quorum) before deploying
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(this::allNodesHealthy);

        // Additional stabilization time for consensus to be fully ready
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        var leaderPort = cluster.getLeaderManagementPort()
                                .unwrap();

        var blueprintContent = Files.readString(BLUEPRINT_PATH);
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
        assertThat(slicesStatus).hasSize(5);

        var inventorySlice = slicesStatus.stream()
                                         .filter(s -> s.artifact().contains("inventory"))
                                         .findFirst()
                                         .orElseThrow();
        assertThat(inventorySlice.instances()).hasSize(1);

        var placeOrderSlice = slicesStatus.stream()
                                          .filter(s -> s.artifact().contains("place-order"))
                                          .findFirst()
                                          .orElseThrow();
        assertThat(placeOrderSlice.instances()).hasSize(1);
    }

    @Test
    void clusterShutdown_stopsAllNodes_gracefully() {
        cluster.start()
               .await()
               .onFailure(cause -> {
                   throw new AssertionError("Cluster start failed: " + cause.message());
               });

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader().isPresent());

        assertThat(cluster.nodeCount()).isEqualTo(3);

        cluster.stop()
               .await()
               .onFailure(cause -> {
                   throw new AssertionError("Cluster stop failed: " + cause.message());
               });

        assertThat(cluster.nodeCount()).isZero();
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
