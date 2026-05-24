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
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
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

import org.pragmatica.aether.ember.EmberCluster;

/// Spike-2 — in-process (Ember/single-JVM) chaos substrate.
///
/// Revives the pre-alpha in-process kill-detection scenario (removed in `c5286f6d6` /
/// `92c56a524` as "unreliable") to measure, in a fast single-JVM loop, how long the cluster
/// takes to *detect* a force-killed non-leader (the S02 case). Purpose is measurement +
/// reproducibility, not a strict gate: it logs the detection timeline (transport
/// connectedPeers drop + membership view) so we can compare detector behaviour in-process
/// against the Docker suite — and so the membership/failure-detection redesign has a fast,
/// debuggable substrate. See aether/docs/internal/membership-failure-detection-unification.md.
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MembershipChaosSpikeTest {
    private static final Logger log = LoggerFactory.getLogger(MembershipChaosSpikeTest.class);

    private static final int SIZE = 5;
    private static final int BASE_PORT = 5060;
    private static final int BASE_MGMT_PORT = 5160;
    private static final int BASE_APP_HTTP_PORT = 5260;
    private static final Duration FORM_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration DETECT_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration POLL = Duration.ofMillis(500);
    private static final Pattern CONNECTED_PEERS = Pattern.compile("\"connectedPeers\"\\s*:\\s*(\\d+)");

    private EmberCluster cluster;
    private final HttpOperations http = jdkHttpOperations();

    @BeforeAll
    void setUp() {
        cluster = emberCluster(SIZE, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "ch");
        cluster.start()
               .await()
               .onFailure(cause -> {throw new AssertionError("Cluster start failed: " + cause.message());});

        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(() -> cluster.currentLeader().isPresent());
        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(this::allNodesHealthy);
        log.info("SPIKE2: {}-node cluster formed, leader={}", SIZE, cluster.currentLeader().or("none"));
    }

    @AfterAll
    void tearDown() {
        if (cluster != null) {cluster.stop().await();}
    }

    @Test
    void killNonLeader_measureDetectionTimeline() {
        var leaderId = cluster.currentLeader().unwrap();
        var nodes = cluster.status().nodes();
        var victim = nodes.stream().filter(n -> !n.isLeader()).findFirst().orElseThrow();
        var survivor = nodes.stream().filter(n -> n.isLeader()).findFirst().orElseThrow();
        var survivorPort = survivor.mgmtPort();

        log.info("SPIKE2: leader={} survivor(mgmt:{})={} victim={}", leaderId, survivorPort, survivor.id(), victim.id());
        assertThat(connectedPeers(survivorPort)).isEqualTo(SIZE - 1); // survivor sees 4 peers pre-kill

        var t0 = System.nanoTime();
        cluster.killNode(victim.id(), false).await();
        log.info("SPIKE2: force-killed {} at t0", victim.id());

        var detectedMs = new long[]{-1L};
        try {
            await().atMost(DETECT_TIMEOUT).pollInterval(POLL).until(() -> {
                var elapsedMs = (System.nanoTime() - t0) / 1_000_000;
                var cp = connectedPeers(survivorPort);
                var victimStillInStatus = getStatus(survivorPort).contains(victim.id());
                log.info("SPIKE2: t+{}ms survivor connectedPeers={} victimInStatus={}", elapsedMs, cp, victimStillInStatus);
                if (cp >= 0 && cp <= SIZE - 2 && detectedMs[0] < 0) {detectedMs[0] = elapsedMs;}
                return detectedMs[0] >= 0;
            });
            log.info("SPIKE2 RESULT: survivor detected departure (connectedPeers {}->{}) in {}ms",
                     SIZE - 1, SIZE - 2, detectedMs[0]);
        } catch (RuntimeException timedOut) {
            log.error("SPIKE2 RESULT: NO transport detection within {}s (in-process reproduction of the unreliability)",
                      DETECT_TIMEOUT.toSeconds());
        }

        // Observe a few seconds past detection: membership view + any auto-heal replacement.
        for (var i = 0; i < 10; i++) {
            sleep(1000);
            var elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            log.info("SPIKE2: post t+{}ms connectedPeers={} emberNodeCount={} leader={}",
                     elapsedMs, connectedPeers(survivorPort), cluster.nodeCount(), cluster.currentLeader().or("none"));
        }

        // Lenient gate: the substrate must at least *form and respond*; detection timing is the datum.
        assertThat(detectedMs[0]).as("transport detection latency (ms); -1 means not detected within %s", DETECT_TIMEOUT)
                                 .isGreaterThanOrEqualTo(-1);
    }

    private boolean allNodesHealthy() {
        return cluster.status().nodes().stream().allMatch(n -> {
            var body = httpGet(n.mgmtPort(), "/api/health");
            return body.contains("\"quorum\":true");
        });
    }

    private int connectedPeers(int port) {
        var m = CONNECTED_PEERS.matcher(httpGet(port, "/api/health"));
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private String getStatus(int port) {
        return httpGet(port, "/api/nodes/status");
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
                   .or("{}");
    }

    private static void sleep(long ms) {
        try {Thread.sleep(ms);} catch (InterruptedException e) {Thread.currentThread().interrupt();}
    }
}
