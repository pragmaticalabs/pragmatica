package org.pragmatica.aether.api;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.consensus.net.NodeInfo.nodeInfo;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/**
 * Integration tests for Management API endpoints.
 * Uses a shared 3-node cluster (Forge-style) for all tests.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ManagementApiIT {
    private static final Logger log = LoggerFactory.getLogger(ManagementApiIT.class);

    private static final int CLUSTER_SIZE = 3;
    private static final int BASE_PORT = 19050;
    private static final int BASE_MGMT_PORT = 19150;
    private static final TimeSpan AWAIT_TIMEOUT = TimeSpan.timeSpan(10).seconds();
    private static final Duration AWAIT_DURATION = Duration.ofSeconds(10);

    private final List<NodeInfo> nodeInfos = List.of(
            nodeInfo(nodeId("mgmt-1"), nodeAddress("localhost", BASE_PORT)),
            nodeInfo(nodeId("mgmt-2"), nodeAddress("localhost", BASE_PORT + 1)),
            nodeInfo(nodeId("mgmt-3"), nodeAddress("localhost", BASE_PORT + 2))
    );

    private final List<AetherNode> nodes = new ArrayList<>();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @BeforeAll
    void setUp() {
        var configuredNodes = nodeInfos.subList(0, CLUSTER_SIZE);

        for (int i = 0; i < CLUSTER_SIZE; i++) {
            var nodeId = configuredNodes.get(i).id();
            var port = BASE_PORT + i;
            var mgmtPort = BASE_MGMT_PORT + i;

            var config = testConfigWithManagement(nodeId, port, configuredNodes, mgmtPort);
            var node = AetherNode.aetherNode(config).unwrap();
            nodes.add(node);
        }

        // Start all nodes
        var startPromises = nodes.stream()
                                 .map(AetherNode::start)
                                 .toList();

        Promise.allOf(startPromises)
               .await(AWAIT_TIMEOUT)
               .onFailureRun(() -> fail("Failed to start all nodes within timeout"))
               .onSuccess(_ -> log.info("All {} Aether nodes started successfully", CLUSTER_SIZE));

        // Wait for management servers to be ready
        await().atMost(AWAIT_DURATION)
               .untilAsserted(() -> {
                   for (int i = 0; i < CLUSTER_SIZE; i++) {
                       var response = get(BASE_MGMT_PORT + i, "/status");
                       assertThat(response.statusCode()).isEqualTo(200);
                   }
               });

        // Wait for cluster quorum to form
        await().atMost(AWAIT_DURATION)
               .untilAsserted(() -> {
                   var response = get(BASE_MGMT_PORT, "/health");
                   assertThat(response.statusCode()).isEqualTo(200);
                   assertThat(response.body()).contains("\"quorum\":true");
               });
        log.info("Cluster quorum formed");

        // Wait for consensus to activate (leader must be elected)
        await().atMost(AWAIT_DURATION)
               .untilAsserted(() -> {
                   var response = get(BASE_MGMT_PORT, "/status");
                   assertThat(response.statusCode()).isEqualTo(200);
                   // Leader field should not be empty
                   assertThat(response.body()).matches(".*\"leader\":\"[^\"]+\".*");
               });
        log.info("Consensus activated, leader elected");
    }

    @AfterAll
    void tearDown() {
        nodes.forEach(node -> node.stop().await(AWAIT_TIMEOUT));
        nodes.clear();
        log.info("All nodes stopped");
    }

    @Test
    void setThreshold_persistsAndReturnsSuccess() throws Exception {
        // Set a threshold via first node
        var body = """
            {"metric": "cpu.usage", "warning": 0.7, "critical": 0.9}
            """;
        var response = post(BASE_MGMT_PORT, "/thresholds", body);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("threshold_set");
        assertThat(response.body()).contains("cpu.usage");
    }

    @Test
    void getThresholds_returnsSetThresholds() throws Exception {
        // Set a threshold
        var setBody = """
            {"metric": "memory.usage", "warning": 0.6, "critical": 0.85}
            """;
        var setResponse = post(BASE_MGMT_PORT, "/thresholds", setBody);
        assertThat(setResponse.statusCode()).isEqualTo(200);

        // Wait for threshold to be replicated and then get thresholds
        await().atMost(AWAIT_DURATION)
               .untilAsserted(() -> {
                   var response = get(BASE_MGMT_PORT, "/thresholds");
                   assertThat(response.statusCode()).isEqualTo(200);
                   assertThat(response.body()).contains("memory.usage");
               });
    }

    @Test
    void deleteThreshold_removesThreshold() throws Exception {
        // Set a threshold first
        var setBody = """
            {"metric": "disk.usage", "warning": 0.7, "critical": 0.9}
            """;
        var setResponse = post(BASE_MGMT_PORT, "/thresholds", setBody);
        assertThat(setResponse.statusCode()).isEqualTo(200);

        // Wait for it to be replicated and verify it's set
        await().atMost(AWAIT_DURATION)
               .untilAsserted(() -> {
                   var getResponse = get(BASE_MGMT_PORT, "/thresholds");
                   assertThat(getResponse.body()).contains("disk.usage");
               });

        // Delete it
        var deleteResponse = delete(BASE_MGMT_PORT, "/thresholds/disk.usage");
        assertThat(deleteResponse.statusCode()).isEqualTo(200);
        assertThat(deleteResponse.body()).contains("threshold_removed");

        // Wait for deletion to replicate and verify it's gone
        await().atMost(AWAIT_DURATION)
               .untilAsserted(() -> {
                   var getResponse = get(BASE_MGMT_PORT, "/thresholds");
                   assertThat(getResponse.body()).doesNotContain("disk.usage");
               });
    }

    @Test
    void threshold_syncsAcrossClusterNodes() throws Exception {
        // Set threshold on first node
        var setBody = """
            {"metric": "network.latency", "warning": 100, "critical": 500}
            """;
        var setResponse = post(BASE_MGMT_PORT, "/thresholds", setBody);
        assertThat(setResponse.statusCode()).isEqualTo(200);

        // Wait for sync and verify on all nodes
        await().atMost(AWAIT_DURATION)
               .untilAsserted(() -> {
                   for (int i = 0; i < CLUSTER_SIZE; i++) {
                       var response = get(BASE_MGMT_PORT + i, "/thresholds");
                       assertThat(response.body())
                               .as("Node %d should have threshold", i)
                               .contains("network.latency");
                   }
               });
    }

    @Test
    void getStrategy_returnsCurrentStrategy() throws Exception {
        var response = get(BASE_MGMT_PORT, "/invocation-metrics/strategy");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("type");
        // Default is adaptive
        assertThat(response.body()).contains("adaptive");
    }

    @Test
    void postStrategy_returnsNotImplemented() throws Exception {
        var body = """
            {"type": "fixed", "thresholdMs": 100}
            """;
        var response = post(BASE_MGMT_PORT, "/invocation-metrics/strategy", body);

        // Should return 501 Not Implemented
        assertThat(response.statusCode()).isEqualTo(501);
        assertThat(response.body()).contains("not supported");
    }

    @Test
    void getInvocationMetrics_returnsEmptyInitially() throws Exception {
        var response = get(BASE_MGMT_PORT, "/invocation-metrics");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("snapshots");
    }

    @Test
    void getSlowInvocations_returnsEmptyInitially() throws Exception {
        var response = get(BASE_MGMT_PORT, "/invocation-metrics/slow");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("slowInvocations");
    }

    @Test
    void healthEndpoint_returnsClusterHealth() throws Exception {
        var response = get(BASE_MGMT_PORT, "/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("status");
        assertThat(response.body()).contains("quorum");
        assertThat(response.body()).contains("nodeCount");
    }

    @Test
    void controllerConfig_canBeRetrievedAndUpdated() throws Exception {
        // Get current config
        var getResponse = get(BASE_MGMT_PORT, "/controller/config");
        assertThat(getResponse.statusCode()).isEqualTo(200);
        assertThat(getResponse.body()).contains("cpuScaleUpThreshold");

        // Update config
        var updateBody = """
            {"cpuScaleUpThreshold": 0.85, "cpuScaleDownThreshold": 0.25}
            """;
        var postResponse = post(BASE_MGMT_PORT, "/controller/config", updateBody);
        assertThat(postResponse.statusCode()).isEqualTo(200);
        assertThat(postResponse.body()).contains("updated");
        assertThat(postResponse.body()).contains("0.85");
    }

    @Test
    void alertsEndpoint_returnsAlertInfo() throws Exception {
        var response = get(BASE_MGMT_PORT, "/alerts");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("active");
        assertThat(response.body()).contains("history");
    }

    @Test
    void clearAlerts_clearsActiveAlerts() throws Exception {
        var response = post(BASE_MGMT_PORT, "/alerts/clear", "");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("alerts_cleared");
    }

    // Helper methods
    private HttpResponse<String> get(int port, String path) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(int port, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(int port, String path) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .DELETE()
                .timeout(Duration.ofSeconds(5))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static AetherNodeConfig testConfigWithManagement(
            org.pragmatica.consensus.NodeId self,
            int port,
            List<NodeInfo> coreNodes,
            int managementPort) {
        var topology = new org.pragmatica.consensus.topology.TopologyConfig(
                self,
                timeSpan(500).millis(),
                timeSpan(100).millis(),
                coreNodes);
        return new AetherNodeConfig(
                topology,
                org.pragmatica.consensus.rabia.ProtocolConfig.testConfig(),
                org.pragmatica.aether.slice.SliceActionConfig.defaultConfiguration(
                        org.pragmatica.aether.slice.serialization.FurySerializerFactoryProvider.furySerializerFactoryProvider()),
                org.pragmatica.aether.config.SliceConfig.defaults(),
                managementPort,
                org.pragmatica.dht.DHTConfig.FULL,
                Option.empty(),
                org.pragmatica.aether.config.TTMConfig.disabled(),
                org.pragmatica.aether.config.RollbackConfig.defaults());
    }
}
