// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Option;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

/// #1337 reachability, end to end on a real cluster: TWO slices of one blueprint share the topic address `tier-orders`; the
/// publisher slice declares it EPHEMERAL (no `durability`), the subscriber slice declares it `durable` and handles it with
/// the `(TierOrder, MessageContext)` shape. Both jars compile and load, and nothing at deploy time sees the disagreement.
///
/// A second topic, `tier-bare`, is the control: ephemeral on both sides with a bare handler. It is published in the same call,
/// so a delivery on the control and none on the mismatch cannot be a harness artefact.
///
/// What the fix is pinned to, observed from OUTSIDE the nodes: the publish still answers `published` (the ephemeral tier is
/// at-most-once), the context handler is never handed the bare event, and the cluster event log carries the
/// `topic-tier-mismatch` operator warning naming the topic, the publishing slice and the subscribing method.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TopicTierMismatchForgeTest {
    private static final int NODES = 3;
    private static final int BASE_PORT = 19500;
    private static final int BASE_MGMT_PORT = 19600;
    private static final int BASE_APP_HTTP_PORT = 19700;

    private static final String PUBLISHER_SLICE = "org.pragmatica.aether.test:test-topic-tier-tier-publisher-slice:1.0.0";
    private static final String SUBSCRIBER_SLICE = "org.pragmatica.aether.test:test-topic-tier-tier-subscriber-slice:1.0.0";
    private static final String BLUEPRINT_ID = "forge.test:topic-tier:1.0.0";
    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final Duration SETTLE = Duration.ofSeconds(10);

    private static final Pattern INSTANCE_ID = Pattern.compile("\"instanceId\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern CONTEXT_ORDERS = Pattern.compile("\"contextOrders\"\\s*:\\s*\\[([^\\]]*)\\]");
    private static final Pattern BARE_ORDERS = Pattern.compile("\"bareOrders\"\\s*:\\s*\\[([^\\]]*)\\]");

    private EmberCluster cluster;
    private final HttpOperations http = jdkHttpOperations();

    @BeforeAll
    void setUp(@TempDir Path baseDir) {
        var configProvider = ConfigurationProvider.builder()
                                                  .withSystemProperties("aether.")
                                                  .withEnvironment("AETHER_")
                                                  .build();

        cluster = emberCluster(NODES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "ttm", Option.some(configProvider));
        cluster.withDataBaseDir(baseDir);
        LifecycleAwait.settled("cluster start in setUp()", cluster, cluster.start());

        await().atMost(WAIT_TIMEOUT).pollInterval(POLL_INTERVAL).until(() -> cluster.currentLeader().isPresent());
        await().atMost(WAIT_TIMEOUT).pollInterval(POLL_INTERVAL).until(this::allNodesHealthy);

        deploy();

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(this::failIfSliceFailed)
               .until(this::bothSlicesAnswer);
    }

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            httpDelete(anyMgmtPort(), "/api/v1/blueprints/" + BLUEPRINT_ID);
            LifecycleAwait.bestEffort("cluster stop in tearDown()", cluster, cluster.stop());
        }
    }

    @Test
    void ephemeralPublish_toDurableContextSubscriber_isRefusedAndNamed_whileTheControlIsDelivered() {
        var id = "tier-1";
        var published = httpPost(appPort(), "/api/tier-pub/publish-order", "{\"orderId\":\"%s\"}".formatted(id));

        assertThat(published).describedAs("the ephemeral publish keeps its at-most-once result").contains("published");

        // The control proves the harness delivers: the bare handler receives the same event, once cluster-wide.
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .untilAsserted(() -> assertThat(deliveries(BARE_ORDERS, id)).describedAs("control (tier-bare) deliveries of %s", id)
                                                                         .isEqualTo(1));

        // The mismatch is named in the cluster event log by the publisher's node.
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .untilAsserted(() -> assertThat(events()).describedAs("operator warning for the refused delivery")
                                                        .anyMatch(event -> event.contains("topic-tier-mismatch")
                                                                           && !event.contains("topic-tier-mismatch-resolved")
                                                                           && event.contains("tier-orders")
                                                                           && event.contains(PUBLISHER_SLICE)
                                                                           && event.contains(SUBSCRIBER_SLICE)
                                                                           && event.contains("onContextOrder")));

        // And the context handler was never handed the bare event, and stays that way.
        sleep(SETTLE);
        assertThat(deliveries(CONTEXT_ORDERS, id)).describedAs("context handler deliveries of %s", id).isZero();
        assertThat(sliceStates(SUBSCRIBER_SLICE)).describedAs("the refusal is not a slice defect: the subscriber stays deployed")
                                                 .doesNotContain("FAILED");
    }

    // --- fixture driving -----------------------------------------------------

    private void deploy() {
        var blueprint = """
            id = "%s"

            [[slices]]
            artifact = "%s"
            instances = %d
            minAvailable = %d
            maxInstances = %d

            [[slices]]
            artifact = "%s"
            instances = %d
            minAvailable = %d
            maxInstances = %d
            """.formatted(BLUEPRINT_ID, PUBLISHER_SLICE, NODES, NODES, NODES, SUBSCRIBER_SLICE, NODES, NODES, NODES);
        var response = httpPostToml(cluster.getLeaderManagementPort().or(anyMgmtPort()), "/api/v1/blueprints", blueprint);

        assertThat(response).describedAs("topic-tier blueprint deployment")
                            .doesNotContain("\"error\"")
                            .contains("\"status\":\"applied\"");
    }

    private boolean bothSlicesAnswer() {
        var ports = cluster.getAvailableAppHttpPorts();

        return ports.size() == NODES
               && ports.stream().allMatch(port -> httpPost(port, "/api/tier-sub/status", "{}").contains("contextOrders"));
    }

    /// How many times `id` was recorded by the handler family `pattern` names, summed over every distinct subscriber instance.
    private int deliveries(Pattern pattern, String id) {
        var seenInstances = new ArrayList<String>();
        var total = 0;

        for (var port : cluster.getAvailableAppHttpPorts()) {
            var body = httpPost(port, "/api/tier-sub/status", "{}");
            var instance = INSTANCE_ID.matcher(body);
            var orders = pattern.matcher(body);

            if (instance.find() && orders.find() && !seenInstances.contains(instance.group(1))) {
                seenInstances.add(instance.group(1));
                total += orders.group(1).split("\"" + id + "\"", -1).length - 1;
            }
        }

        return total;
    }

    private List<String> events() {
        return cluster.status().nodes().stream().map(node -> httpGet(node.mgmtPort(), "/api/v1/events")).toList();
    }

    private String sliceStates(String artifact) {
        return cluster.slicesStatus()
                      .stream()
                      .filter(status -> status.artifact().equals(artifact))
                      .map(status -> status.state())
                      .toList()
                      .toString();
    }

    private void failIfSliceFailed() {
        var failed = cluster.slicesStatus()
                            .stream()
                            .anyMatch(status -> (status.artifact().equals(PUBLISHER_SLICE) || status.artifact().equals(SUBSCRIBER_SLICE))
                                                && status.state().equals("FAILED"));

        if (failed) {
            throw new AssertionError("topic-tier slice deployment FAILED");
        }
    }

    private int appPort() {
        return cluster.getAvailableAppHttpPorts()
                      .stream()
                      .findFirst()
                      .orElseThrow(() -> new AssertionError("No app-http route is ready"));
    }

    private int anyMgmtPort() {
        return cluster.status().nodes().getFirst().mgmtPort();
    }

    private boolean allNodesHealthy() {
        return cluster.status().nodes().stream().allMatch(node -> checkNodeHealth(node.mgmtPort()));
    }

    private boolean checkNodeHealth(int port) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/v1/health"))
                                 .GET()
                                 .timeout(Duration.ofSeconds(5))
                                 .build();

        return http.sendString(request)
                   .await()
                   .map(response -> response.statusCode() == 200 && response.body().contains("\"quorum\":true"))
                   .or(false);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String httpPostToml(int port, String path, String body) {
        return send(HttpRequest.newBuilder()
                               .uri(URI.create("http://localhost:" + port + path))
                               .header("Content-Type", "application/toml")
                               .POST(HttpRequest.BodyPublishers.ofString(body))
                               .timeout(Duration.ofSeconds(10))
                               .build());
    }

    private String httpPost(int port, String path, String body) {
        return send(HttpRequest.newBuilder()
                               .uri(URI.create("http://localhost:" + port + path))
                               .header("Content-Type", "application/json")
                               .POST(HttpRequest.BodyPublishers.ofString(body))
                               .timeout(Duration.ofSeconds(15))
                               .build());
    }

    private String httpGet(int port, String path) {
        return send(HttpRequest.newBuilder()
                               .uri(URI.create("http://localhost:" + port + path))
                               .GET()
                               .timeout(Duration.ofSeconds(10))
                               .build());
    }

    private String httpDelete(int port, String path) {
        return send(HttpRequest.newBuilder()
                               .uri(URI.create("http://localhost:" + port + path))
                               .DELETE()
                               .timeout(Duration.ofSeconds(10))
                               .build());
    }

    private String send(HttpRequest request) {
        return http.sendString(request).await().map(HttpResult::body).or(ERROR_FALLBACK);
    }
}
