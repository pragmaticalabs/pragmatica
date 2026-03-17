package org.pragmatica.aether.forge;

import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.http.HttpResult;
import org.pragmatica.http.HttpOperations;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

/// Base class with shared test utilities for Forge tests.
///
///
/// Provides common HTTP helper methods and cluster utilities that are
/// used across multiple E2E test classes.
public abstract class ForgeTestBase {

    protected final HttpOperations http = jdkHttpOperations();

    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";

    /// Check if all nodes in the cluster are healthy.
    ///
    /// @param cluster the EmberCluster to check
    /// @return true if all nodes report healthy with quorum
    protected boolean allNodesHealthy(EmberCluster cluster) {
        var status = cluster.status();
        return status.nodes().stream()
                     .allMatch(node -> checkNodeHealth(node.mgmtPort()));
    }

    /// Check if a single node is healthy.
    ///
    /// @param port the management port of the node
    /// @return true if the node reports healthy with quorum
    protected boolean checkNodeHealth(int port) {
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

    /// Check if all nodes in the cluster are ready (healthy with quorum and ready flag).
    ///
    /// @param cluster the EmberCluster to check
    /// @return true if all nodes report ready and have quorum
    protected boolean allNodesReady(EmberCluster cluster) {
        var status = cluster.status();
        return status.nodes().stream()
                     .allMatch(node -> checkNodeReady(node.mgmtPort()));
    }

    /// Check if a single node is ready.
    ///
    /// @param port the management port of the node
    /// @return true if the node reports ready with quorum
    protected boolean checkNodeReady(int port) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/health"))
                                 .GET()
                                 .timeout(Duration.ofSeconds(5))
                                 .build();
        return http.sendString(request)
                   .await()
                   .map(r -> r.statusCode() == 200
                       && r.body().contains("\"ready\":true")
                       && r.body().contains("\"quorum\":true"))
                   .or(false);
    }

    /// Wait for all nodes in the cluster to become ready.
    ///
    /// @param cluster the EmberCluster to wait on
    /// @param timeout maximum time to wait for readiness
    protected void awaitClusterReady(EmberCluster cluster, Duration timeout) {
        await().atMost(timeout)
               .pollInterval(Duration.ofMillis(500))
               .until(() -> allNodesReady(cluster));
    }

    /// Perform an HTTP GET request.
    ///
    /// @param port the port to connect to
    /// @param path the path to request
    /// @return the response body or error JSON
    protected String httpGet(int port, String path) {
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

    /// Perform an HTTP POST request.
    ///
    /// @param port the port to connect to
    /// @param path the path to request
    /// @param body the request body
    /// @return the response body or error JSON
    protected String httpPost(int port, String path, String body) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(Duration.ofSeconds(10))
                                 .build();
        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }

    /// Get health status from any available node in the cluster.
    ///
    /// @param cluster the EmberCluster
    /// @return the health response or empty string if no nodes available
    protected String getHealthFromAnyNode(EmberCluster cluster) {
        var status = cluster.status();
        if (status.nodes().isEmpty()) {
            return "";
        }
        var port = status.nodes().getFirst().mgmtPort();
        return httpGet(port, "/api/health");
    }

    /// Sleep for the specified duration, handling interrupts gracefully.
    ///
    /// @param duration the duration to sleep
    protected void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /// Wait for a leader to be elected in the cluster.
    ///
    /// @param cluster the EmberCluster
    /// @param timeout maximum time to wait
    /// @param pollInterval interval between checks
    protected void awaitLeader(EmberCluster cluster, Duration timeout, Duration pollInterval) {
        var deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (cluster.currentLeader().isPresent()) {
                return;
            }
            sleep(pollInterval);
        }
        throw new AssertionError("Leader not elected within timeout");
    }

    /// Wait for the cluster to achieve quorum with a leader.
    ///
    /// @param cluster the EmberCluster
    /// @param timeout maximum time to wait
    /// @param pollInterval interval between checks
    protected void awaitQuorum(EmberCluster cluster, Duration timeout, Duration pollInterval) {
        var deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (cluster.nodeCount() >= 3 && cluster.currentLeader().isPresent()) {
                return;
            }
            sleep(pollInterval);
        }
        throw new AssertionError("Quorum not achieved within timeout");
    }
}
