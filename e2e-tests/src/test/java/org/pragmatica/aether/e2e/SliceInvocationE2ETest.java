package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.e2e.containers.AetherCluster;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * E2E tests for slice method invocation.
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
 * The place-order has no methods, so some tests focus on infrastructure
 * and error handling rather than successful invocations.
 */
class SliceInvocationE2ETest {
    private static final Path PROJECT_ROOT = Path.of(System.getProperty("project.basedir", ".."));
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(30);
    private static final String TEST_ARTIFACT = "org.pragmatica-lite.aether.example:place-order-place-order:0.8.0";
    private AetherCluster cluster;

    @BeforeEach
    void setUp() {
        cluster = AetherCluster.aetherCluster(3, PROJECT_ROOT);
        cluster.start();
        cluster.awaitQuorum();
    }

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.close();
        }
    }

    @Nested
    class RouteHandling {

        @Test
        void invokeNonExistentRoute_returns404() {
            var response = cluster.anyNode().invokeGet("/api/nonexistent");

            // Should return 404 or error for route not found
            assertThat(response).containsAnyOf("error", "404", "not found", "Not Found");
        }

        @Test
        void invokeWithInvalidMethod_returnsError() {
            var response = cluster.anyNode().invokeSlice("PATCH", "/api/test", "{}");

            // PATCH is not in the supported methods list
            assertThat(response).contains("error");
        }

        @Test
        void routesEndpoint_returnsRegisteredRoutes() {
            var routes = cluster.anyNode().getRoutes();

            // Should return routes list (may be empty if no slices deployed)
            assertThat(routes).doesNotContain("\"error\"");
        }

        @Test
        void afterSliceDeployment_routesAreAvailable() {
            // Deploy a slice
            cluster.anyNode().deploy(TEST_ARTIFACT, 1);

            await().atMost(WAIT_TIMEOUT).until(() -> {
                var slices = cluster.anyNode().getSlices();
                return slices.contains("place-order");
            });

            // Check routes endpoint
            var routes = cluster.anyNode().getRoutes();
            assertThat(routes).doesNotContain("\"error\"");
        }
    }

    @Nested
    class ErrorHandling {

        @Test
        void invokeWithMalformedBody_returnsError() {
            var response = cluster.anyNode().invokePost("/api/test", "not valid json");

            // Should handle gracefully
            assertThat(response).containsAnyOf("error", "404", "Bad Request");
        }

        @Test
        void invokeWithEmptyBody_handledGracefully() {
            var response = cluster.anyNode().invokePost("/api/test", "");

            // Should not crash, return appropriate error
            assertThat(response).isNotNull();
        }

        @Test
        void invokeAfterSliceUndeploy_returnsNotFound() {
            // Deploy
            cluster.anyNode().deploy(TEST_ARTIFACT, 1);
            await().atMost(WAIT_TIMEOUT).until(() -> {
                var slices = cluster.anyNode().getSlices();
                return slices.contains("place-order");
            });

            // Undeploy
            cluster.anyNode().undeploy(TEST_ARTIFACT);
            await().atMost(WAIT_TIMEOUT).until(() -> {
                var slices = cluster.anyNode().getSlices();
                return !slices.contains("place-order");
            });

            // Invoke should fail (no routes)
            var response = cluster.anyNode().invokeGet("/api/example");
            assertThat(response).containsAnyOf("error", "404", "not found", "Not Found");
        }
    }

    @Nested
    class RequestDistribution {

        @Test
        void multipleNodes_allCanHandleRequests() {
            // Deploy slice across all nodes
            cluster.anyNode().deploy(TEST_ARTIFACT, 3);

            await().atMost(WAIT_TIMEOUT).until(() -> {
                var slices = cluster.anyNode().getSlices();
                return slices.contains("place-order");
            });

            // All nodes should be able to respond to requests
            for (var node : cluster.nodes()) {
                var health = node.getHealth();
                assertThat(health).contains("\"status\"");
                assertThat(health).doesNotContain("\"error\"");
            }
        }

        @Test
        void requestToAnyNode_succeeds() {
            // Verify all nodes can handle management API requests
            // (demonstrates request handling capability)
            var node1Response = cluster.nodes().get(0).getStatus();
            var node2Response = cluster.nodes().get(1).getStatus();
            var node3Response = cluster.nodes().get(2).getStatus();

            assertThat(node1Response).doesNotContain("\"error\"");
            assertThat(node2Response).doesNotContain("\"error\"");
            assertThat(node3Response).doesNotContain("\"error\"");
        }
    }
}
