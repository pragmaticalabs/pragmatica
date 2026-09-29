// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.pragmatica.aether.config.ApiKeyEntry;
import org.pragmatica.aether.config.SecurityMode;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.forge.api.OperatorKey;
import org.pragmatica.aether.forge.load.ConfigurableLoadRunner;
import org.pragmatica.aether.forge.simulator.EntryPointMetrics;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.QueryParams;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// #1105 — with a sibling `aether.toml` declaring API keys the embedded nodes run `SecurityMode.API_KEY`, and every
/// Forge dashboard proxy call must carry the operator's key or the node refuses it. Drives the production
/// [ForgeApiHandler] (built through its public factory, exactly as `ForgeServer` builds it) against a REAL
/// three-node Ember cluster whose management API enforces keys, and reads the status the dashboard would see.
///
/// The keyless handler is the in-run CONTROL: it proves the cluster actually refuses an unkeyed call, so a 200 on
/// the keyed handler is evidence the key was sent, not that nothing was checked. The control runs only after the
/// keyed calls have succeeded, so its refusal cannot be a cluster that is not ready yet.
///
/// Also pins the three proxy targets that were unversioned and answered 404 on every node (the node serves only
/// `/api/v1/...`): `/api/alerts/active`, `/api/alerts/history`, `/api/thresholds` (behind Forge's
/// `/api/alerts/thresholds`) and `/api/metrics/history`.
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ForgeProxyApiKeyForgeTest {
    private static final int BASE_PORT = 15500;
    private static final int BASE_MGMT_PORT = 15700;
    private static final int BASE_APP_HTTP_PORT = 15800;
    private static final int NODES = 3;
    private static final String NODE_PREFIX = "fpk";
    private static final String OPERATOR_KEY = "forge-1105-operator-key";
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final long RESPONSE_SECONDS = 20;

    private EmberCluster cluster;
    private ForgeApiHandler keyed;
    private ForgeApiHandler keyless;

    @BeforeAll
    void setUp() {
        cluster = emberCluster(NODES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, NODE_PREFIX);
        // MUST precede start(): every node reads the security mode and the key map at construction.
        cluster.withAppHttpSecurity(SecurityMode.API_KEY,
                                    Map.of(OPERATOR_KEY,
                                           ApiKeyEntry.apiKeyEntry("forge-1105-operator", Set.of("service"), "ADMIN")));
        LifecycleAwait.settled("cluster start in setUp()", cluster, cluster.start());
        await().alias("leader elected")
               .atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader()
                                   .isPresent());
        keyed = handler(() -> Option.some(OPERATOR_KEY));
        keyless = handler(OperatorKey.none());
    }

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            LifecycleAwait.bestEffort("cluster stop in tearDown()", cluster, cluster.stop());
        }
    }

    @Test
    void proxiedDashboardCalls_carryTheOperatorKey_andReachVersionedNodeRoutes() throws Exception {
        for (var path : List.of("/api/traces/stats",
                                 "/api/alerts/active",
                                 "/api/alerts/history",
                                 "/api/alerts/thresholds",
                                 "/api/metrics/history")) {
            await().alias("keyed proxy call " + path + " answers 200")
                   .atMost(WAIT_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .until(() -> get(keyed, path).status() == HttpStatus.OK);
        }

        // v1533 F2: the versioned POST and DELETE threshold targets, through the same keyed handler.
        var metric = "forge.1105.test.metric";

        await().alias("keyed proxy POST /api/alerts/thresholds answers 200")
               .atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> send(keyed,
                                 HttpMethod.POST,
                                 "/api/alerts/thresholds",
                                 "{\"metric\":\"" + metric + "\",\"warning\":0.7,\"critical\":0.9}").status() == HttpStatus.OK);
        await().alias("keyed proxy DELETE /api/alerts/thresholds/{metric} answers 200")
               .atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> send(keyed, HttpMethod.DELETE, "/api/alerts/thresholds/" + metric, "").status() == HttpStatus.OK);

        var refused = get(keyless, "/api/traces/stats");

        assertThat(refused.status()).as("control: a keyless proxy call must be refused by the API_KEY cluster, or the keyed "
                                        + "200s above prove nothing")
                                    .isNotEqualTo(HttpStatus.OK);
        assertThat(refused.body()).as("control: the refusal is the node's authentication, not some other failure")
                                  .containsAnyOf("HTTP 401", "HTTP 403");
    }

    /// v1533 F1: Forge's own event poll (`ForgeServer.pollNodeEvents`) carries the operator key too. Under `API_KEY`
    /// the keyed fetch returns a non-empty event timeline; a keyless fetch of the same route is refused in the same run.
    @Test
    void forgeEventPoll_carriesTheOperatorKey_andReadsTheTimeline() {
        var port = cluster.getLeaderManagementPort().unwrap();

        await().alias("keyed event poll returns a non-empty timeline")
               .atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> ForgeServer.fetchNodeEvents(() -> Option.some(OPERATOR_KEY), port, "")
                                       .fold(_ -> false, body -> body.startsWith("[") && body.contains("\"type\"")));

        var keyless = ForgeServer.fetchNodeEvents(OperatorKey.none(), port, "");

        assertThat(keyless.isFailure()).as("control: the cluster refuses a keyless event poll").isTrue();
        keyless.onFailure(cause -> assertThat(cause.message()).containsAnyOf("HTTP 401", "HTTP 403"));
    }

    private ForgeApiHandler handler(OperatorKey operatorKey) {
        var metrics = ForgeMetrics.forgeMetrics();
        var loadRunner = ConfigurableLoadRunner.configurableLoadRunner(cluster::getAvailableAppHttpPorts,
                                                                       metrics,
                                                                       EntryPointMetrics.entryPointMetrics());

        return ForgeApiHandler.forgeApiHandler(cluster, metrics, loadRunner, operatorKey);
    }

    private static Captured get(ForgeApiHandler handler, String path) throws Exception {
        return send(handler, HttpMethod.GET, path, "");
    }

    private static Captured send(ForgeApiHandler handler, HttpMethod method, String path, String body) throws Exception {
        var writer = new CapturingWriter();

        handler.handle(new TestRequest(method, path, body), writer);

        return writer.captured.get(RESPONSE_SECONDS, TimeUnit.SECONDS);
    }

    private record Captured(HttpStatus status, String body) {}

    private record TestRequest(HttpMethod method, String path, String payload) implements HttpRequest {
        @Override
        public String requestId() {
            return "forge-1105-test";
        }

        @Override
        public Headers headers() {
            return payload.isEmpty()
                   ? Headers.empty()
                   : Headers.fromSingleValueMap(Map.of("Content-Type", "application/json"));
        }

        @Override
        public QueryParams queryParams() {
            return QueryParams.empty();
        }

        @Override
        public byte[] body() {
            return payload.getBytes(StandardCharsets.UTF_8);
        }
    }

    private static final class CapturingWriter implements ResponseWriter {
        private final CompletableFuture<Captured> captured = new CompletableFuture<>();

        @Override
        public void write(HttpStatus status, byte[] body, ContentType contentType) {
            captured.complete(new Captured(status, new String(body, StandardCharsets.UTF_8)));
        }

        @Override
        public ResponseWriter header(String name, String value) {
            return this;
        }
    }
}
