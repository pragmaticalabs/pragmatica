package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.http.HttpResult;
import org.pragmatica.http.HttpOperations;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import org.pragmatica.aether.ember.EmberCluster;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

/// Tests for cluster formation and quorum behavior using EmberCluster.
///
///
/// Tests cover:
///
///   - 3-node cluster formation
///   - Quorum establishment
///   - Leader election
///   - Node visibility across cluster
///   - Status consistency
///   - Metrics availability
///
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClusterFormationTest {
    private static final int BASE_PORT = 5050;
    private static final int BASE_MGMT_PORT = 5150;
    private static final int BASE_APP_HTTP_PORT = 5250;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";

    private EmberCluster cluster;
    private final HttpOperations http = jdkHttpOperations();

    @BeforeAll
    void setUp() {
        cluster = emberCluster(3, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "cf");

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
    void threeNodeCluster_formsQuorum_andElectsLeader() {
        // All nodes should be running
        assertThat(cluster.nodeCount()).isEqualTo(3);

        // Health endpoint should report healthy with quorum
        var anyNodePort = cluster.status().nodes().getFirst().mgmtPort();
        var health = getHealth(anyNodePort);
        assertThat(health).contains("\"status\"");

        // Leader should be elected
        var leader = cluster.currentLeader();
        assertThat(leader.isPresent()).isTrue();
    }

    @Test
    void cluster_nodesVisibleToAllMembers() {
        // Each node should report 2 connected peers via /api/health endpoint
        for (var node : cluster.status().nodes()) {
            var health = getHealth(node.mgmtPort());
            assertThat(health).contains("\"connectedPeers\":2");
            assertThat(health).contains("\"nodeCount\":3");
        }
    }

    @Test
    void cluster_statusConsistent_acrossNodes() {
        // Collect status from all nodes
        var leaderNode = cluster.currentLeader().unwrap();

        // All nodes should report the same leader via /api/status endpoint
        for (var node : cluster.status().nodes()) {
            var status = getStatus(node.mgmtPort());
            assertThat(status).contains(leaderNode);
        }
    }

    @Test
    void cluster_metricsAvailable_afterFormation() {
        var anyNodePort = cluster.status().nodes().getFirst().mgmtPort();
        var metrics = getMetrics(anyNodePort);

        // Metrics should not contain error
        assertThat(metrics).doesNotContain("\"error\"");
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
        return http.sendString(request)
                   .await()
                   .map(r -> r.statusCode() == 200 && r.body().contains("\"quorum\":true"))
                   .or(false);
    }

    private String getHealth(int port) {
        return httpGet(port, "/api/health");
    }

    private String getStatus(int port) {
        return httpGet(port, "/api/status");
    }

    private String getMetrics(int port) {
        return httpGet(port, "/api/metrics");
    }

    private String httpGet(int port, String path) {
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
}
