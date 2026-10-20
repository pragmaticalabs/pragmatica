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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

/// #1337, end to end on a real cluster: TWO slices from two jars share the topic `tier-orders`. The publisher jar's `resources.toml`
/// declares it EPHEMERAL (no `durability`); the subscriber jar's declares it `durable` and the slice handles it with the
/// `(TierOrder, MessageContext)` shape. Both jars compile and load, and nothing in either build sees the disagreement.
///
/// Before the deploy-time check this blueprint was ACCEPTED, and the first publish died in the subscriber's bridge as a
/// `ClassCastException` tagged `SliceDefect.MethodThrew`; at the runtime-refusal stage of #1337 it was accepted and every delivery
/// was refused and counted. Now the blueprint is refused at publish, with a message naming the topic, the publishing slice and the
/// subscribing method, and nothing is deployed.
///
/// `tier-bare` is the control INSIDE the same blueprint: ephemeral on both sides with a bare handler. It is published by the same
/// slice and subscribed by the same slice, so a refusal that named it would be a refusal of the wrong thing.
///
/// What this test does not reach: the runtime refusal of a delivery. No supported route deploys the two slices around the blueprint
/// check (topic addresses are namespaced by the owning blueprint, so slices deployed one by one never meet), so that path is pinned in
/// `TopicTierMismatchTest` and `TopicPublisherTest` instead.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TopicTierMismatchForgeTest {
    private static final int NODES = 3;
    private static final int BASE_PORT = 19500;
    private static final int BASE_MGMT_PORT = 19600;
    private static final int BASE_APP_HTTP_PORT = 19700;

    private static final String PUBLISHER_SLICE = "org.pragmatica.aether.test:test-topic-tier-pub-tier-publisher-slice:1.0.0";
    private static final String SUBSCRIBER_SLICE = "org.pragmatica.aether.test:test-topic-tier-sub-tier-subscriber-slice:1.0.0";
    private static final String BLUEPRINT_ID = "forge.test:topic-tier:1.0.0";
    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

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
    }

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            LifecycleAwait.bestEffort("cluster stop in tearDown()", cluster, cluster.stop());
        }
    }

    @Test
    void blueprint_ephemeralPublisherAndDurableContextSubscriber_isRefusedAtPublish_namingTheMismatch_andDeploysNothing() {
        var response = httpPostToml(cluster.getLeaderManagementPort().or(anyMgmtPort()), "/api/v1/blueprints", blueprint());

        assertThat(response).describedAs("the blueprint must be refused, not applied")
                            .doesNotContain("\"status\":\"applied\"")
                            .contains("\"status\":400")
                            .contains("topic tier mismatch")
                            .contains("tier-orders")
                            .contains("TierPublisherSlice")
                            .contains("TierSubscriberSlice.onContextOrder")
                            .contains("MessageContext");
        assertThat(response).describedAs("the control topic (ephemeral on both sides, bare handler) is not what is refused")
                            .doesNotContain("tier-bare")
                            .doesNotContain("onBareOrder");
        assertThat(cluster.slicesStatus()
                          .stream()
                          .filter(status -> status.artifact().equals(PUBLISHER_SLICE) || status.artifact().equals(SUBSCRIBER_SLICE))
                          .toList()).describedAs("a refused blueprint deploys no slice").isEmpty();
    }

    private static String blueprint() {
        return """
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

    private String httpPostToml(int port, String path, String body) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .header("Content-Type", "application/toml")
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(Duration.ofSeconds(10))
                                 .build();

        return http.sendString(request).await().map(HttpResult::body).or(ERROR_FALLBACK);
    }
}
