package org.pragmatica.aether.forge;

import org.pragmatica.aether.ember.EmberCluster;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.awaitility.Awaitility.await;

/// Base class with shared test utilities for Forge tests.
///
///
/// Provides common HTTP helper methods and cluster utilities that are
/// used across multiple E2E test classes.
public abstract class ForgeTestBase {

    /// Check if all nodes in the cluster are healthy.
    ///
    /// @param cluster the EmberCluster to check
    /// @param httpClient the HTTP client to use for health checks
    /// @return true if all nodes report healthy with quorum
    protected boolean allNodesHealthy(EmberCluster cluster, HttpClient httpClient) {
        var status = cluster.status();
        return status.nodes().stream()
                     .allMatch(node -> checkNodeHealth(node.mgmtPort(), httpClient));
    }

    /// Check if a single node is healthy.
    ///
    /// @param port the management port of the node
    /// @param httpClient the HTTP client to use
    /// @return true if the node reports healthy with quorum
    protected boolean checkNodeHealth(int port, HttpClient httpClient) {
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

    /// Check if all nodes in the cluster are ready (healthy with quorum and ready flag).
    ///
    /// @param cluster the EmberCluster to check
    /// @param httpClient the HTTP client to use for health checks
    /// @return true if all nodes report ready and have quorum
    protected boolean allNodesReady(EmberCluster cluster, HttpClient httpClient) {
        var status = cluster.status();
        return status.nodes().stream()
                     .allMatch(node -> checkNodeReady(node.mgmtPort(), httpClient));
    }

    /// Check if a single node is ready.
    ///
    /// @param port the management port of the node
    /// @param httpClient the HTTP client to use
    /// @return true if the node reports ready with quorum
    protected boolean checkNodeReady(int port, HttpClient httpClient) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/health"))
                                 .GET()
                                 .timeout(Duration.ofSeconds(5))
                                 .build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200
                && response.body().contains("\"ready\":true")
                && response.body().contains("\"quorum\":true");
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /// Wait for all nodes in the cluster to become ready.
    ///
    /// @param cluster the EmberCluster to wait on
    /// @param httpClient the HTTP client to use for health checks
    /// @param timeout maximum time to wait for readiness
    protected void awaitClusterReady(EmberCluster cluster, HttpClient httpClient, Duration timeout) {
        await().atMost(timeout)
               .pollInterval(Duration.ofMillis(500))
               .until(() -> allNodesReady(cluster, httpClient));
    }

    /// Perform an HTTP GET request.
    ///
    /// @param port the port to connect to
    /// @param path the path to request
    /// @param httpClient the HTTP client to use
    /// @return the response body or error JSON
    protected String httpGet(int port, String path, HttpClient httpClient) {
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

    /// Perform an HTTP POST request.
    ///
    /// @param port the port to connect to
    /// @param path the path to request
    /// @param body the request body
    /// @param httpClient the HTTP client to use
    /// @return the response body or error JSON
    protected String httpPost(int port, String path, String body, HttpClient httpClient) {
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

    /// Get health status from any available node in the cluster.
    ///
    /// @param cluster the EmberCluster
    /// @param httpClient the HTTP client to use
    /// @return the health response or empty string if no nodes available
    protected String getHealthFromAnyNode(EmberCluster cluster, HttpClient httpClient) {
        var status = cluster.status();
        if (status.nodes().isEmpty()) {
            return "";
        }
        var port = status.nodes().getFirst().mgmtPort();
        return httpGet(port, "/api/health", httpClient);
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
