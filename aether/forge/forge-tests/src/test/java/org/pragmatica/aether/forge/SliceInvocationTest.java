// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.awaitility.core.ConditionTimeoutException;
import org.pragmatica.http.HttpResult;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.lang.io.TimeSpan;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import org.pragmatica.aether.ember.EmberCluster;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

/// Tests for slice method invocation.
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
/// Note: Full invocation testing requires slices with defined methods.
/// The echo-slice has an echo method. Tests focus on infrastructure and error handling
/// rather than successful invocations.
/// #556 forge SMOKE set (deployment + invocation leg): one of three classes `./forge.sh` runs by default, so a
/// developer gets a real multi-node cluster signal before pushing instead of discovering a
/// cluster-level regression in CI. Keep this set small — its value is that people actually run it.
@Tag("Smoke")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SliceInvocationTest {
    private static final int BASE_PORT = 6000;
    private static final int BASE_MGMT_PORT = 6100;
    private static final int BASE_APP_HTTP_PORT = 6200;
    private static final TimeSpan WAIT_BOUND = TimeSpan.timeSpan(240).seconds();
    private static final Duration WAIT_TIMEOUT = WAIT_BOUND.duration();
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final String TEST_ARTIFACT = TestArtifacts.ECHO_SLICE;
    private static final String BLUEPRINT_ID = "forge.test:slice-invocation:1.0.0";
    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";
    private static final Pattern OUTCOME_TIMESTAMP = Pattern.compile("\"timestampMs\":(\\d+)");

    private EmberCluster cluster;
    private final HttpOperations http = jdkHttpOperations();
    private long applyStartedAtMs;

    @BeforeAll
    void setUp() {
        cluster = emberCluster(3, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "si");
        // Bounded, like every other wait in this class. Untimed, this await() ignores JUnit's 8-minute
        // interrupt (PromiseImpl.await re-parks until resolved) and a start that never settles ends only
        // at failsafe's 30-minute fork wall with no test named — observed 2026-09-06 (see #727 report).
        cluster.start()
               .await(WAIT_BOUND)
               .onFailure(cause -> {
                   throw new AssertionError("Cluster start failed: " + cause.message()
                                            + "\nCluster state at expiry:\n" + clusterSnapshot());
               });

        awaitFormation("cluster leader elected (3 nodes, ports " + BASE_PORT + "+)",
                       () -> cluster.currentLeader().isPresent());
        awaitFormation("all 3 nodes report healthy after leader election", this::allNodesHealthy);
    }

    @BeforeEach
    void cleanUp() {
        // Undeploy any slices left by previous tests — and WAIT for it. #727's 240s member is this
        // delete racing the next test's apply of the same artifact: under load the node's unload and
        // the new ACTIVATE directive cross ("state is ACTIVATE but not found in SliceStore"), the
        // leader classes that deterministic and rolls the blueprint back, and the deploy wait then
        // polls for a slice that no longer exists (reproduced 2026-09-06, 3x CPU oversubscription).
        var leaderPort = cluster.getLeaderManagementPort().or(anyMgmtPort());
        httpRequestDelete(leaderPort, "/api/v1/blueprints/" + BLUEPRINT_ID);
        awaitEchoSliceUndeployed();
    }

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            cluster.stop()
                   .await(WAIT_BOUND)
                   .onFailure(cause -> {
                       throw new AssertionError("Cluster stop did not complete: " + cause.message());
                   });
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

            assertThat(response).containsAnyOf("error", "not found", "Not Found", "File not found");
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

            awaitEchoSliceDeployed();

            var routes = getRoutes();
            assertThat(routes).doesNotContain("\"error\"");
        }
    }

    @Nested
    class ErrorHandling {

        @Test
        void invokeWithMalformedBody_returnsError() {
            var response = invokePost("/api/test", "not valid json");

            assertThat(response).containsAnyOf("error", "Bad Request", "400", "not found", "Not Found");
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

            awaitEchoSliceDeployed();

            undeploy(TEST_ARTIFACT);
            awaitEchoSliceUndeployed();

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

            awaitEchoSliceDeployed();

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

    // #727 wait helpers: every 240s wait in this class dies with a NAME and a STATE, never blind.
    //
    // The ceiling behind three CI class-level ERRORs at 240.5s is WAIT_TIMEOUT, and each occurrence
    // reported only "Condition with Lambda expression in SliceInvocationTest was not fulfilled" — not
    // which condition, not which node, not what it was reporting. The settling run this ticket asked
    // for (2026-09-06, quiet 16-core box, 10 sequential runs) puts formation-to-healthy at ~7s with
    // no run above 37s wall, so 240s is ~30x the quiet-box time: not a margin problem, and raising it
    // would only make a stalled formation fail slower. The ceiling stays; the failure now says what
    // it waited for and what every node answered at expiry, which is what the next occurrence needs.

    private void awaitFormation(String alias, Callable<Boolean> condition) {
        try {
            await().alias(alias)
                   .atMost(WAIT_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .until(condition);
        } catch (ConditionTimeoutException timeout) {
            throw new AssertionError(timeout.getMessage() + "\nCluster state at expiry:\n" + clusterSnapshot(),
                                     timeout);
        }
    }

    private void awaitEchoSliceDeployed() {
        awaitSlices("echo-slice present in /api/v1/slices/status after blueprint apply",
                    slices -> slices.contains("echo-slice"),
                    this::failFastOnDeploymentFailure);
    }

    // Under ALL_OR_NOTHING a deterministic slice failure rolls the blueprint back and removes it from
    // the KV store, so /api/v1/slices/status shows nothing for the artifact and a poll on it has
    // nothing left to see — that is how the 240s occurrences went blind. The terminal outcome survives
    // the removal at the status URL (#759); only an outcome recorded AFTER this test's own apply
    // counts, because a stale ROLLED_BACK from an earlier test stays readable until the next apply's
    // live entry replicates to the queried node.
    private void failFastOnDeploymentFailure() {
        var status = httpRequest("GET", anyMgmtPort(), "/api/v1/blueprints/status/" + BLUEPRINT_ID, null);
        if (isTerminalFailure(status) && outcomeTimestampMs(status) >= applyStartedAtMs) {
            throw new AssertionError("Blueprint " + BLUEPRINT_ID + " failed after apply: " + status);
        }
        if (sliceHasFailed(TEST_ARTIFACT)) {
            throw new AssertionError("Slice deployment failed: " + TEST_ARTIFACT);
        }
    }

    private static boolean isTerminalFailure(String status) {
        return status.contains("\"overallStatus\":\"FAILED\"") || status.contains("\"overallStatus\":\"ROLLED_BACK\"");
    }

    private static long outcomeTimestampMs(String status) {
        var matcher = OUTCOME_TIMESTAMP.matcher(status);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : 0L;
    }

    // The undeploy wait used to be a bare `!getSlices().contains("echo-slice")`: an error body from the
    // status endpoint contains no "echo-slice" either, so a failing query satisfied "undeployed". The
    // shared fail-fast below turns that into a named failure instead of a false green.
    private void awaitEchoSliceUndeployed() {
        awaitSlices("echo-slice absent from /api/v1/slices/status after blueprint delete",
                    slices -> !slices.contains("echo-slice"),
                    () -> {});
    }

    private void awaitSlices(String alias, Predicate<String> condition, Runnable extraFailFast) {
        try {
            await().alias(alias)
                   .atMost(WAIT_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .failFast(() -> {
                       var slices = getSlices();
                       if (slices.contains("\"error\"")) {
                           throw new AssertionError("Slice query failed: " + slices);
                       }
                       extraFailFast.run();
                   })
                   .until(() -> condition.test(getSlices()));
        } catch (ConditionTimeoutException timeout) {
            throw new AssertionError(timeout.getMessage()
                                     + "\nLast /api/v1/slices/status: " + getSlices()
                                     + "\nCluster state at expiry:\n" + clusterSnapshot(),
                                     timeout);
        }
    }

    private String clusterSnapshot() {
        var status = cluster.status();
        var nodes = status.nodes().stream().map(this::nodeSnapshot).collect(Collectors.joining("\n"));
        return "  leader=" + status.leaderId() + "\n" + nodes;
    }

    private String nodeSnapshot(EmberCluster.NodeStatus node) {
        return "  " + node.id() + " state=" + node.state() + " leader=" + node.isLeader()
               + " health=" + healthBody(node.mgmtPort());
    }

    private String healthBody(int port) {
        return http.sendString(healthRequest(port))
                   .await()
                   .fold(cause -> "unreachable (" + cause.message() + ")",
                         response -> response.statusCode() + " " + response.body());
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
        return httpRequest("GET", anyMgmtPort(), "/api/v1/routes", null);
    }

    private String getSlices() {
        // Use /api/v1/slices/status for cluster-wide view (reads from KVStore)
        return httpRequest("GET", anyMgmtPort(), "/api/v1/slices/status", null);
    }

    private String getHealth(int port) {
        return httpRequest("GET", port, "/api/v1/health", null);
    }

    private String getStatus(int port) {
        return httpRequest("GET", port, "/api/v1/nodes/status", null);
    }

    private String deploy(String artifact, int instances) {
        var blueprint = """
            id = "%s"

            [[slices]]
            artifact = "%s"
            instances = %d
            """.formatted(BLUEPRINT_ID, artifact, instances);
        var leaderPort = cluster.getLeaderManagementPort().or(anyMgmtPort());
        applyStartedAtMs = System.currentTimeMillis();
        return postBlueprintWithRetry(leaderPort, blueprint);
    }

    private String postBlueprintWithRetry(int port, String body) {
        String lastResponse = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            lastResponse = httpRequestBlueprint(port, body);
            if (!lastResponse.contains("\"error\"")) {
                return lastResponse;
            }
            if (attempt < 3) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return lastResponse;
    }

    private String httpRequestBlueprint(int port, String body) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/v1/blueprints"))
                                 .header("Content-Type", "application/toml")
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(Duration.ofSeconds(10))
                                 .build();
        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }

    private void assertDeploymentSucceeded(String response) {
        assertThat(response)
            .describedAs("Deployment response")
            .doesNotContain("\"error\"")
            .contains("\"status\":\"applied\"");
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
        var leaderPort = cluster.getLeaderManagementPort().or(anyMgmtPort());
        httpRequestDelete(leaderPort, "/api/v1/blueprints/" + BLUEPRINT_ID);
    }

    private String httpRequestDelete(int port, String path) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .DELETE()
                                 .timeout(Duration.ofSeconds(10))
                                 .build();
        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
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

        return http.sendString(builder.build())
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
        return http.sendString(healthRequest(port))
                   .await()
                   .map(r -> r.statusCode() == 200 && r.body().contains("\"quorum\":true"))
                   .or(false);
    }

    private static HttpRequest healthRequest(int port) {
        return HttpRequest.newBuilder()
                          .uri(URI.create("http://localhost:" + port + "/api/v1/health"))
                          .GET()
                          .timeout(Duration.ofSeconds(5))
                          .build();
    }
}
