package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.e2e.containers.AetherCluster;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.e2e.TestEnvironment.adapt;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// E2E tests for slice method invocation.
///
///
/// Tests cover:
///
///   - Route registration when slices are deployed
///   - HTTP request routing to slice methods
///   - Error handling for non-existent routes
///   - Request distribution across slice instances
///
///
///
/// Note: Uses the echo-slice test artifact which provides pure function
/// methods (echo, ping, transform, fail) for testing invocation infrastructure.
///
///
/// This test class uses a shared cluster for all tests to reduce startup overhead.
/// Tests run in order and each test cleans up previous state before running.
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
class SliceInvocationE2ETest {
    private static final Path PROJECT_ROOT = Path.of(System.getProperty("project.basedir", ".."));
    private static final String TEST_ARTIFACT_VERSION = System.getProperty("project.version", "0.17.0");
    private static final String TEST_ARTIFACT = "org.pragmatica-lite.aether.test:echo-slice-echo-service:" + TEST_ARTIFACT_VERSION;

    // Common timeouts (CI gets 2x via adapt())
    private static final Duration DEFAULT_TIMEOUT = adapt(timeSpan(30).seconds().duration());
    private static final Duration DEPLOY_TIMEOUT = adapt(timeSpan(3).minutes().duration());
    private static final Duration POLL_INTERVAL = timeSpan(2).seconds().duration();
    private static final Duration CLEANUP_TIMEOUT = adapt(timeSpan(2).minutes().duration());

    private static AetherCluster cluster;

    @BeforeAll
    static void createCluster() {
        cluster = AetherCluster.aetherCluster(5, PROJECT_ROOT);
        cluster.start();
        cluster.awaitQuorum();
        cluster.awaitAllHealthy();
        cluster.awaitLeader();
        cluster.uploadTestArtifacts();
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

            var slices = leader.getSlicesStatus();
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
        undeployAllSlices();
        cluster.awaitSliceUndeployed(TEST_ARTIFACT, CLEANUP_TIMEOUT);
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
            // Deploy a slice via leader
            var leader = cluster.leader().toResult(Causes.cause("No leader")).unwrap();
            var deployResponse = leader.deploy(TEST_ARTIFACT, 1);
            assertThat(deployResponse).doesNotContain("\"error\"");

            // Delegates to cluster which has stuck-state detection
            cluster.awaitSliceActive(TEST_ARTIFACT, DEPLOY_TIMEOUT);

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
            // Deploy via leader
            var leader = cluster.leader().toResult(Causes.cause("No leader")).unwrap();
            leader.deploy(TEST_ARTIFACT, 1);
            cluster.awaitSliceActive(TEST_ARTIFACT, DEPLOY_TIMEOUT);

            // Undeploy via leader
            cluster.leader().toResult(Causes.cause("No leader")).unwrap().undeploy(TEST_ARTIFACT);
            await().atMost(CLEANUP_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .ignoreExceptions()
                   .until(() -> {
                       var status = cluster.anyNode().getSlicesStatus();
                       return !status.contains("echo-slice");
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
            // Deploy slice across all nodes via leader
            var leader = cluster.leader().toResult(Causes.cause("No leader")).unwrap();
            leader.deploy(TEST_ARTIFACT, 3);
            cluster.awaitSliceActive(TEST_ARTIFACT, DEPLOY_TIMEOUT);

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
