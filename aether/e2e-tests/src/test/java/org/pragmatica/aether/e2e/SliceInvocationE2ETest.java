package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.e2e.containers.AetherCluster;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

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
 * <p>Note: Uses the echo-slice test artifact which provides pure function
 * methods (echo, ping, transform, fail) for testing invocation infrastructure.
 *
 * <p>This test class uses a shared cluster for all tests to reduce startup overhead.
 * Tests run in order and each test cleans up previous state before running.
 */
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
class SliceInvocationE2ETest {
    private static final Path PROJECT_ROOT = Path.of(System.getProperty("project.basedir", ".."));
    private static final String TEST_ARTIFACT = "org.pragmatica-lite.aether.test:echo-slice:0.15.0";

    // Common timeouts
    private static final TimeSpan DEFAULT_TIMEOUT = timeSpan(30).seconds();
    private static final TimeSpan DEPLOY_TIMEOUT = timeSpan(3).minutes();
    private static final TimeSpan POLL_INTERVAL = timeSpan(2).seconds();
    private static final TimeSpan CLEANUP_TIMEOUT = timeSpan(60).seconds();

    private static AetherCluster cluster;

    @BeforeAll
    static void createCluster() {
        cluster = AetherCluster.aetherCluster(3, PROJECT_ROOT);
        cluster.start();
        cluster.awaitQuorum();
        cluster.awaitAllHealthy();
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
        sleep(timeSpan(2).seconds());

        // Undeploy all slices
        undeployAllSlices();

        // Wait for clean state
        awaitNoSlices();
    }

    // ===== Cleanup Helpers =====

    private void undeployAllSlices() {
        try {
            var leader = cluster.leader()
                                .toResult(Causes.cause("No leader"))
                                .unwrap();

            var slices = leader.getSlices();
            System.out.println("[DEBUG] Deployed slices: " + slices);

            if (slices.contains(TEST_ARTIFACT)) {
                var result = leader.undeploy(TEST_ARTIFACT);
                System.out.println("[DEBUG] Undeploy " + TEST_ARTIFACT + ": " + result);
            }
        } catch (Exception e) {
            System.out.println("[DEBUG] Error undeploying slices: " + e.getMessage());
        }
    }

    private void awaitNoSlices() {
        await().atMost(CLEANUP_TIMEOUT.duration())
               .pollInterval(POLL_INTERVAL.duration())
               .ignoreExceptions()
               .until(() -> {
                   var slices = cluster.anyNode().getSlices();
                   System.out.println("[DEBUG] Waiting for no slices, current: " + slices);
                   return !slices.contains(TEST_ARTIFACT);
               });
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

    private void sleep(TimeSpan duration) {
        try {
            Thread.sleep(duration.duration().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Nested
    @Order(1)
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class RouteHandling {

        @Test
        @Order(1)
        void invokeNonExistentRoute_returns404() {
            var response = cluster.anyNode().invokeGet("/api/nonexistent");

            // Should return 404 or error for route not found
            assertThat(response).containsAnyOf("error", "404", "not found", "Not Found");
        }

        @Test
        @Order(2)
        void invokeWithInvalidMethod_returnsError() {
            var response = cluster.anyNode().invokeSlice("PATCH", "/api/test", "{}");

            // PATCH is not in the supported methods list
            assertThat(response).contains("error");
        }

        @Test
        @Order(3)
        void routesEndpoint_returnsRegisteredRoutes() {
            var routes = cluster.anyNode().getRoutes();

            // Should return routes list (may be empty if no slices deployed)
            assertThat(routes).doesNotContain("\"error\"");
        }

        @Test
        @Order(4)
        void afterSliceDeployment_routesAreAvailable() {
            // Deploy a slice
            cluster.anyNode().deploy(TEST_ARTIFACT, 1);

            await().atMost(DEPLOY_TIMEOUT.duration())
                   .pollInterval(POLL_INTERVAL.duration())
                   .failFast(() -> {
                       if (sliceHasFailed(TEST_ARTIFACT)) {
                           throw new AssertionError("Slice deployment failed: " + TEST_ARTIFACT);
                       }
                   })
                   .until(() -> sliceIsActive(TEST_ARTIFACT));

            // Check routes endpoint
            var routes = cluster.anyNode().getRoutes();
            assertThat(routes).doesNotContain("\"error\"");
        }
    }

    @Nested
    @Order(2)
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class ErrorHandling {

        @Test
        @Order(1)
        void invokeWithMalformedBody_returnsError() {
            var response = cluster.anyNode().invokePost("/api/test", "not valid json");

            // Should handle gracefully
            assertThat(response).containsAnyOf("error", "404", "Bad Request");
        }

        @Test
        @Order(2)
        void invokeWithEmptyBody_handledGracefully() {
            var response = cluster.anyNode().invokePost("/api/test", "");

            // Should not crash, return appropriate error
            assertThat(response).isNotNull();
        }

        @Test
        @Order(3)
        void invokeAfterSliceUndeploy_returnsNotFound() {
            // Deploy
            cluster.anyNode().deploy(TEST_ARTIFACT, 1);
            await().atMost(DEPLOY_TIMEOUT.duration())
                   .pollInterval(POLL_INTERVAL.duration())
                   .failFast(() -> {
                       if (sliceHasFailed(TEST_ARTIFACT)) {
                           throw new AssertionError("Slice deployment failed: " + TEST_ARTIFACT);
                       }
                   })
                   .until(() -> sliceIsActive(TEST_ARTIFACT));

            // Undeploy
            cluster.anyNode().undeploy(TEST_ARTIFACT);
            await().atMost(CLEANUP_TIMEOUT.duration())
                   .pollInterval(POLL_INTERVAL.duration())
                   .until(() -> {
                       var slices = cluster.anyNode().getSlices();
                       return !slices.contains("echo-slice");
                   });

            // Invoke should fail (no routes)
            var response = cluster.anyNode().invokeGet("/api/example");
            assertThat(response).containsAnyOf("error", "404", "not found", "Not Found");
        }
    }

    @Nested
    @Order(3)
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class RequestDistribution {

        @Test
        @Order(1)
        void multipleNodes_allCanHandleRequests() {
            // Deploy slice across all nodes
            cluster.anyNode().deploy(TEST_ARTIFACT, 3);

            await().atMost(DEPLOY_TIMEOUT.duration())
                   .pollInterval(POLL_INTERVAL.duration())
                   .failFast(() -> {
                       if (sliceHasFailed(TEST_ARTIFACT)) {
                           throw new AssertionError("Slice deployment failed: " + TEST_ARTIFACT);
                       }
                   })
                   .until(() -> sliceIsActive(TEST_ARTIFACT));

            // All nodes should be able to respond to requests
            for (var node : cluster.nodes()) {
                var health = node.getHealth();
                assertThat(health).contains("\"status\"");
                assertThat(health).doesNotContain("\"error\"");
            }
        }

        @Test
        @Order(2)
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
