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
 * Tests for TTM (Tiny Time Mixers) predictive scaling.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>TTM status API endpoint</li>
 *   <li>TTM disabled state (no model file)</li>
 *   <li>TTM state consistency across cluster</li>
 *   <li>TTM leader-only behavior</li>
 * </ul>
 *
 * <p>Note: These tests verify TTM infrastructure, not prediction accuracy.
 * Prediction accuracy testing requires a trained ONNX model and is better
 * suited for unit tests with mocked predictors.
 */
@Execution(ExecutionMode.SAME_THREAD)
class TtmTest {
    private static final int BASE_PORT = 5590;
    private static final int BASE_MGMT_PORT = 5690;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    private ForgeCluster cluster;
    private HttpClient httpClient;

    @BeforeEach
    void setUp(TestInfo testInfo) {
        int portOffset = getPortOffset(testInfo);
        cluster = forgeCluster(3, BASE_PORT + portOffset, BASE_MGMT_PORT + portOffset, "tm");
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
    }

    private int getPortOffset(TestInfo testInfo) {
        return switch (testInfo.getTestMethod().map(m -> m.getName()).orElse("")) {
            case "ttmStatus_returnsValidJson" -> 0;
            case "ttmStatus_showsDisabledByDefault" -> 5;
            case "ttmStatus_includesConfigurationDetails" -> 10;
            case "ttmStatus_availableOnAllNodes" -> 15;
            case "ttmStatus_consistentAcrossCluster" -> 20;
            case "ttmStatus_survivesLeaderFailure" -> 25;
            case "ttmStatus_showsNoForecastWhenDisabled" -> 30;
            default -> 35;
        };
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (cluster != null) {
            cluster.stop()
                   .await();
            Thread.sleep(2000);
        }
    }

    @Nested
    class TtmStatusEndpoint {

        @Test
        void ttmStatus_returnsValidJson() {
            var status = getTtmStatusFromAnyNode();

            assertThat(status).doesNotContain("\"error\"");
            assertThat(status).contains("\"enabled\":");
            assertThat(status).contains("\"state\":");
        }

        @Test
        void ttmStatus_showsDisabledByDefault() {
            var status = getTtmStatusFromAnyNode();

            // TTM is disabled by default (no model file)
            assertThat(status).contains("\"enabled\":false");
            assertThat(status).contains("\"state\":\"STOPPED\"");
        }

        @Test
        void ttmStatus_includesConfigurationDetails() {
            var status = getTtmStatusFromAnyNode();

            assertThat(status).contains("\"inputWindowMinutes\":");
            assertThat(status).contains("\"evaluationIntervalMs\":");
            assertThat(status).contains("\"confidenceThreshold\":");
        }
    }

    @Nested
    class TtmClusterBehavior {

        @Test
        void ttmStatus_availableOnAllNodes() {
            // All nodes should expose TTM status endpoint
            for (var node : cluster.status().nodes()) {
                var status = getTtmStatus(node.mgmtPort());
                assertThat(status).doesNotContain("\"error\"");
                assertThat(status).contains("\"state\":");
            }
        }

        @Test
        void ttmStatus_consistentAcrossCluster() {
            // All nodes should report consistent TTM state
            var statuses = cluster.status().nodes().stream()
                                  .map(node -> getTtmStatus(node.mgmtPort()))
                                  .toList();

            // All should show disabled (no model)
            for (var status : statuses) {
                assertThat(status).contains("\"enabled\":false");
            }
        }

        @Test
        void ttmStatus_survivesLeaderFailure() {
            // Get initial status
            var initialStatus = getTtmStatusFromAnyNode();
            assertThat(initialStatus).doesNotContain("\"error\"");

            // Kill the actual leader
            var leaderId = cluster.currentLeader().unwrap();
            cluster.killNode(leaderId)
                   .await();

            // Wait for new quorum
            await().atMost(WAIT_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .until(() -> cluster.currentLeader().isPresent());

            // TTM status should still be available
            var newStatus = getTtmStatusFromAnyNode();
            assertThat(newStatus).doesNotContain("\"error\"");
            assertThat(newStatus).contains("\"state\":");
        }
    }

    @Nested
    class TtmNoForecastWhenDisabled {

        @Test
        void ttmStatus_showsNoForecastWhenDisabled() {
            var status = getTtmStatusFromAnyNode();

            // When disabled, no forecast should be present
            assertThat(status).contains("\"hasForecast\":false");
            assertThat(status).doesNotContain("\"lastForecast\"");
        }
    }

    private String getTtmStatusFromAnyNode() {
        var nodes = cluster.status().nodes();
        if (nodes.isEmpty()) {
            return "";
        }
        return getTtmStatus(nodes.getFirst().mgmtPort());
    }

    private String getTtmStatus(int port) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/ttm/status"))
                                 .GET()
                                 .timeout(Duration.ofSeconds(5))
                                 .build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (IOException | InterruptedException e) {
            return "error: " + e.getMessage();
        }
    }
}
