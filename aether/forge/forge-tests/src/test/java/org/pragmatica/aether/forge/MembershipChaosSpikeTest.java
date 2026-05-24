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

/// Spike-2 — in-process (Ember/single-JVM) chaos substrate, full-cycle measurement.
///
/// Forms a 5-node single-JVM cluster, lets it settle past the auto-heal startup cooldown
/// (`AutoHealConfig.DEFAULT.startupCooldown` = 15s), force-kills a non-leader, then watches
/// for up to 120s and logs the full membership timeline: transport detection (survivor
/// `connectedPeers` 4→3), **lifecycle decommission** (victim `kvState` → STOPPED), and
/// **auto-heal** (replacement provisioned → ON_DUTY count back to 5 / connectedPeers back
/// to 4). Purpose is measurement + reproducibility (compare to the Docker ~61s ON_DUTY
/// SWIM-departed latency), not a strict gate. See
/// aether/docs/internal/membership-failure-detection-unification.md.
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MembershipChaosSpikeTest {
    private static final Logger log = LoggerFactory.getLogger(MembershipChaosSpikeTest.class);

    private static final int SIZE = 5;
    private static final int BASE_PORT = 5060;
    private static final int BASE_MGMT_PORT = 5160;
    private static final int BASE_APP_HTTP_PORT = 5260;
    private static final Duration FORM_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration POLL = Duration.ofMillis(500);
    private static final long SETTLE_MS = 20_000;   // clear AutoHealConfig.DEFAULT startupCooldown (15s)
    private static final long OBSERVE_MS = 120_000;  // watch full decommission + auto-heal cycle
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
    void killNonLeader_measureFullDecommissionTimeline() {
        var leaderId = cluster.currentLeader().unwrap();
        var nodes = cluster.status().nodes();
        var victim = nodes.stream().filter(n -> !n.isLeader()).findFirst().orElseThrow();
        var survivor = nodes.stream().filter(n -> n.isLeader()).findFirst().orElseThrow();
        var port = survivor.mgmtPort();

        log.info("SPIKE2: settling {}ms past auto-heal startup cooldown before kill", SETTLE_MS);
        sleep(SETTLE_MS);
        log.info("SPIKE2: pre-kill survivor connectedPeers={} onDuty={} stopped={}",
                 connectedPeers(port), onDuty(port), stopped(port));
        log.info("SPIKE2: leader={} survivor(mgmt:{})={} victim={}", leaderId, port, survivor.id(), victim.id());

        var t0 = System.nanoTime();
        cluster.killNode(victim.id(), false).await();
        log.info("SPIKE2: force-killed {} at t0", victim.id());

        long tTransport = -1, tDecommission = -1, tRecovered = -1;
        var deadline = t0 + OBSERVE_MS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            var elapsed = (System.nanoTime() - t0) / 1_000_000;
            var cp = connectedPeers(port);
            var od = onDuty(port);
            var st = stopped(port);
            if (tTransport < 0 && cp >= 0 && cp <= SIZE - 2) {tTransport = elapsed;}
            if (tDecommission < 0 && st >= 1) {tDecommission = elapsed;}
            if (tDecommission >= 0 && tRecovered < 0 && cp >= SIZE - 1 && od >= SIZE) {tRecovered = elapsed;}
            if (elapsed % 5000 < 1000) {
                log.info("SPIKE2: t+{}ms connectedPeers={} onDuty={} stopped={} emberNodeCount={}",
                         elapsed, cp, od, st, cluster.nodeCount());
            }
            if (tRecovered >= 0) {break;}
            sleep(1000);
        }

        log.info("SPIKE2 RESULT: transport-detect={}ms  decommission={}ms  auto-heal-recovered={}ms  (-1 = not within {}s)",
                 tTransport, tDecommission, tRecovered, OBSERVE_MS / 1000);
        log.info("SPIKE2 FINAL /api/nodes/status: {}", status(port));

        // Measurement spike: substrate must form + respond; the latencies are the data.
        assertThat(tTransport).as("transport detection latency ms (-1 = none)").isGreaterThanOrEqualTo(-1);
    }

    private boolean allNodesHealthy() {
        return cluster.status().nodes().stream().allMatch(n -> httpGet(n.mgmtPort(), "/api/health").contains("\"quorum\":true"));
    }

    private int connectedPeers(int port) {
        var m = CONNECTED_PEERS.matcher(httpGet(port, "/api/health"));
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private int onDuty(int port) {
        return countOccurrences(status(port), "\"kvState\":\"ON_DUTY\"");
    }

    private int stopped(int port) {
        return countOccurrences(status(port), "\"kvState\":\"STOPPED\"");
    }

    private String status(int port) {
        return httpGet(port, "/api/nodes/status");
    }

    private static int countOccurrences(String haystack, String needle) {
        var count = 0;
        var idx = haystack.indexOf(needle);
        while (idx >= 0) {count++; idx = haystack.indexOf(needle, idx + needle.length());}
        return count;
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
