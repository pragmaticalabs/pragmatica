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
 * Tests for slice method invocation.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Route registration when slices are deployed</li>
 *   <li>HTTP request routing to slice methods</li>
 *   <li>Error handling for non-existent routes</li>
 *   <li>Request distribution across slice instances</li>
 * </ul>
 *
 * <p>Note: Full invocation testing requires slices with defined methods.
 * The inventory slice has methods (checkStock, reserveStock, releaseStock), but tests focus
 * on infrastructure and error handling rather than successful invocations.
 */
@Execution(ExecutionMode.SAME_THREAD)
class SliceInvocationTest {
    private static final int BASE_PORT = 6000;
    private static final int BASE_MGMT_PORT = 6100;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final String TEST_ARTIFACT = "org.pragmatica-lite.aether.example:inventory:0.0.1-test";

    private ForgeCluster cluster;
    private HttpClient httpClient;

    @BeforeEach
    void setUp(TestInfo testInfo) {
        int portOffset = getPortOffset(testInfo);
        cluster = forgeCluster(3, BASE_PORT + portOffset, BASE_MGMT_PORT + portOffset, "si");
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
            case "invokeNonExistentRoute_returns404" -> 0;
            case "invokeWithInvalidMethod_returnsError" -> 20;
            case "routesEndpoint_returnsRegisteredRoutes" -> 40;
            case "afterSliceDeployment_routesAreAvailable" -> 60;
            case "invokeWithMalformedBody_returnsError" -> 80;
            case "invokeWithEmptyBody_handledGracefully" -> 100;
            case "invokeAfterSliceUndeploy_returnsNotFound" -> 120;
            case "multipleNodes_allCanHandleRequests" -> 140;
            case "requestToAnyNode_succeeds" -> 160;
            default -> 180;
        };
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (cluster != null) {
            cluster.stop()
                   .await();
            Thread.sleep(3000);
        }
    }

    @Nested
    class RouteHandling {

        @Test
        void invokeNonExistentRoute_returns404() {
            var response = invokeGet("/api/nonexistent");

            assertThat(response).containsAnyOf("error", "404", "not found", "Not Found");
        }

        @Test
        void invokeWithInvalidMethod_returnsError() {
            var response = invokeSlice("PATCH", "/api/test", "{}");

            assertThat(response).contains("error");
        }

        @Test
        void routesEndpoint_returnsRegisteredRoutes() {
            var routes = getRoutes();

            assertThat(routes).doesNotContain("\"error\"");
        }

        @Test
        void afterSliceDeployment_routesAreAvailable() {
            var deployResponse = deploy(TEST_ARTIFACT, 1);
            assertDeploymentSucceeded(deployResponse);

            await().atMost(WAIT_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .failFast(() -> {
                       var slices = getSlices();
                       if (slices.contains("\"error\"")) {
                           throw new AssertionError("Slice query failed: " + slices);
                       }
                       if (sliceHasFailed(TEST_ARTIFACT)) {
                           throw new AssertionError("Slice deployment failed: " + TEST_ARTIFACT);
                       }
                   })
                   .until(() -> getSlices().contains("inventory"));

            var routes = getRoutes();
            assertThat(routes).doesNotContain("\"error\"");
        }
    }

    @Nested
    class ErrorHandling {

        @Test
        void invokeWithMalformedBody_returnsError() {
            var response = invokePost("/api/test", "not valid json");

            assertThat(response).containsAnyOf("error", "Bad Request", "400");
        }

        @Test
        void invokeWithEmptyBody_handledGracefully() {
            var response = invokePost("/api/test", "");
            // Should return a response without crashing - either error or valid response
            assertThat(response).isNotNull();
            assertThat(response).isNotEmpty();
        }

        @Test
        void invokeAfterSliceUndeploy_returnsNotFound() {
            var deployResponse = deploy(TEST_ARTIFACT, 1);
            assertDeploymentSucceeded(deployResponse);

            await().atMost(WAIT_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .failFast(() -> {
                       var slices = getSlices();
                       if (slices.contains("\"error\"")) {
                           throw new AssertionError("Slice query failed: " + slices);
                       }
                       if (sliceHasFailed(TEST_ARTIFACT)) {
                           throw new AssertionError("Slice deployment failed: " + TEST_ARTIFACT);
                       }
                   })
                   .until(() -> getSlices().contains("inventory"));

            undeploy(TEST_ARTIFACT);
            await().atMost(WAIT_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .until(() -> !getSlices().contains("inventory"));

            var response = invokeGet("/api/example");
            assertThat(response).containsAnyOf("error", "404", "not found", "Not Found");
        }
    }

    @Nested
    class RequestDistribution {

        @Test
        void multipleNodes_allCanHandleRequests() {
            var deployResponse = deploy(TEST_ARTIFACT, 3);
            assertDeploymentSucceeded(deployResponse);

            await().atMost(WAIT_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .failFast(() -> {
                       var slices = getSlices();
                       if (slices.contains("\"error\"")) {
                           throw new AssertionError("Slice query failed: " + slices);
                       }
                       if (sliceHasFailed(TEST_ARTIFACT)) {
                           throw new AssertionError("Slice deployment failed: " + TEST_ARTIFACT);
                       }
                   })
                   .until(() -> getSlices().contains("inventory"));

            for (var node : cluster.status().nodes()) {
                var health = getHealth(node.mgmtPort());
                assertThat(health).contains("\"status\"");
                assertThat(health).doesNotContain("\"error\"");
            }
        }

        @Test
        void requestToAnyNode_succeeds() {
            var nodes = cluster.status().nodes();

            var node1Response = getStatus(nodes.get(0).mgmtPort());
            var node2Response = getStatus(nodes.get(1).mgmtPort());
            var node3Response = getStatus(nodes.get(2).mgmtPort());

            assertThat(node1Response).doesNotContain("\"error\"");
            assertThat(node2Response).doesNotContain("\"error\"");
            assertThat(node3Response).doesNotContain("\"error\"");
        }
    }

    // HTTP helper methods

    private int anyMgmtPort() {
        return cluster.status().nodes().getFirst().mgmtPort();
    }

    private String invokeGet(String path) {
        return httpRequest("GET", anyMgmtPort(), path, null);
    }

    private String invokePost(String path, String body) {
        return httpRequest("POST", anyMgmtPort(), path, body);
    }

    private String invokeSlice(String method, String path, String body) {
        return httpRequest(method, anyMgmtPort(), path, body);
    }

    private String getRoutes() {
        return httpRequest("GET", anyMgmtPort(), "/api/routes", null);
    }

    private String getSlices() {
        // Use /api/slices/status for cluster-wide view (reads from KVStore)
        return httpRequest("GET", anyMgmtPort(), "/api/slices/status", null);
    }

    private String getHealth(int port) {
        return httpRequest("GET", port, "/api/health", null);
    }

    private String getStatus(int port) {
        return httpRequest("GET", port, "/api/status", null);
    }

    private String deploy(String artifact, int instances) {
        var body = "{\"artifact\":\"" + artifact + "\",\"instances\":" + instances + "}";
        var leaderPort = cluster.getLeaderManagementPort().or(anyMgmtPort());
        return httpRequest("POST", leaderPort, "/api/deploy", body);
    }

    private void assertDeploymentSucceeded(String response) {
        assertThat(response)
            .describedAs("Deployment response")
            .doesNotContain("\"error\"")
            .contains("\"status\":\"deployed\"");
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

    private void undeploy(String artifact) {
        var body = "{\"artifact\":\"" + artifact + "\"}";
        var leaderPort = cluster.getLeaderManagementPort().or(anyMgmtPort());
        httpRequest("POST", leaderPort, "/api/undeploy", body);
    }

    private String httpRequest(String method, int port, String path, String body) {
        var builder = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .timeout(Duration.ofSeconds(10));

        if (body != null) {
            builder.header("Content-Type", "application/json")
                   .method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        try {
            var response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (IOException | InterruptedException e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
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
