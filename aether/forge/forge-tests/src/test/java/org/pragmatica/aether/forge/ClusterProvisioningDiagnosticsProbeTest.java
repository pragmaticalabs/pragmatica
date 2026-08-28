// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.TerminalOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

/// #336 observability — surface-layer probe for `GET /api/cluster/provisioning`. Forms a small Ember
/// cluster, queries the provisioning-diagnostics endpoint on the leader's management port, and asserts
/// the JSON parses and the key fields are present and internally consistent:
///   - `leader` is `true` on the leader endpoint (the diagnostics view is leader-only);
///   - `configuredCoreCount` matches the formed cluster size;
///   - the nested `circuitBreaker` object is present;
///   - `deficit` is non-negative.
///
/// This validates the REST route + serialization wiring, not the reconciler logic — a settled cluster
/// at its configured size should report `leader=true`, `configuredCoreCount=<size>`, and a present
/// circuit-breaker object regardless of whether any deficit is in flight.
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClusterProvisioningDiagnosticsProbeTest {
    private static final Logger log = LoggerFactory.getLogger(ClusterProvisioningDiagnosticsProbeTest.class);

    private static final int CORES = 5;
    private static final int BASE_PORT = 5690;
    private static final int BASE_MGMT_PORT = 5790;
    private static final int BASE_APP_HTTP_PORT = 5890;

    private static final Duration FORM_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration POLL = Duration.ofMillis(500);

    private static final Pattern LEADER = Pattern.compile("\"leader\"\\s*:\\s*(true|false)");
    private static final Pattern CONFIGURED = Pattern.compile("\"configuredCoreCount\"\\s*:\\s*(\\d+)");
    private static final Pattern DEFICIT = Pattern.compile("\"deficit\"\\s*:\\s*(-?\\d+)");
    private static final Pattern CIRCUIT_BREAKER = Pattern.compile("\"circuitBreaker\"\\s*:\\s*\\{");
    private static final Pattern CONSECUTIVE_FAILURES = Pattern.compile("\"consecutiveFailures\"\\s*:\\s*(\\d+)");

    private EmberCluster cluster;
    private final HttpOperations http = jdkHttpOperations();

    @BeforeAll
    @TerminalOperation
    void setUp() {
        cluster = emberCluster(CORES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "provisioning");
        cluster.start()
               .await()
               .onFailure(ClusterProvisioningDiagnosticsProbeTest::failStart);

        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(() -> cluster.currentLeader().isPresent());
        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(() -> countedCores() == CORES);
        log.info("PROVISIONING-PROBE: {}-core cluster formed, leader={}, countedCores={}",
                 CORES, cluster.currentLeader().or("none"), countedCores());
    }

    @AfterAll
    @TerminalOperation
    void tearDown() {
        Option.option(cluster).onPresent(c -> c.stop().await());
    }

    @Test
    @TerminalOperation
    void getProvisioning_reportsLeaderAndConfiguredCount_onSettledCluster() {
        var leaderPort = cluster.getLeaderManagementPort()
                                .toResult(ProbeError.NO_LEADER)
                                .onFailure(ClusterProvisioningDiagnosticsProbeTest::failScenario)
                                .or(-1);

        var json = httpGet(leaderPort, "/api/v1/cluster/provisioning");
        log.info("PROVISIONING-PROBE: GET /api/cluster/provisioning -> {}", json);

        assertThat(matchGroup(LEADER, json))
            .as("leader endpoint must report leader=true (diagnostics are leader-only): %s", json)
            .isEqualTo("true");

        assertThat(intGroup(CONFIGURED, json))
            .as("configuredCoreCount must match the formed cluster size %d: %s", CORES, json)
            .isEqualTo(CORES);

        assertThat(CIRCUIT_BREAKER.matcher(json).find())
            .as("circuitBreaker object must be present in the response: %s", json)
            .isTrue();

        assertThat(intGroup(CONSECUTIVE_FAILURES, json))
            .as("circuitBreaker.consecutiveFailures must be present and non-negative: %s", json)
            .isGreaterThanOrEqualTo(0);

        assertThat(intGroup(DEFICIT, json))
            .as("deficit must be present and clamped non-negative: %s", json)
            .isGreaterThanOrEqualTo(0);
    }

    private int countedCores() {
        return cluster.currentLeader()
                      .flatMap(cluster::getNode)
                      .map(node -> node.membershipFsm().coreCountedMembers().size())
                      .or(0);
    }

    private static String matchGroup(Pattern pattern, String json) {
        var matcher = pattern.matcher(json);

        return matcher.find()
               ? matcher.group(1)
               : "";
    }

    private static int intGroup(Pattern pattern, String json) {
        var matcher = pattern.matcher(json);

        return matcher.find()
               ? Integer.parseInt(matcher.group(1))
               : -1;
    }

    @TerminalOperation
    private String httpGet(int port, String path) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .GET()
                                 .timeout(Duration.ofSeconds(5))
                                 .build();

        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or("{}");
    }

    private static void failStart(Cause cause) {
        throw new AssertionError("Cluster start failed: " + cause.message());
    }

    private static void failScenario(Cause cause) {
        throw new AssertionError("Scenario setup failed: " + cause.message());
    }

    private enum ProbeError implements Cause {
        NO_LEADER("No leader available to receive the provisioning-diagnostics request");

        private final String message;

        ProbeError(String message) {
            this.message = message;
        }

        @Override
        public String message() {
            return message;
        }
    }
}
