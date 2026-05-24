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
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.TerminalOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.ember.EmberCluster.NodeStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

/// Spike — in-process (Ember/single-JVM) reproduction of the Docker-only SILENT-DEATH
/// failure-detection bug (issues #230/#231/#232).
///
/// The sibling [`MembershipChaosSpikeTest`] force-kills a node via a CLEAN stop (`node.stop()`),
/// which closes the victim's QUIC channels — peers get an immediate transport-disconnect, fire
/// `NODE_FAILED`, and decommission completes (it PASSES in ~83s). That kill is too cooperative to
/// reproduce what we see on Docker.
///
/// This test instead BLACK-HOLES the victim ([`EmberCluster#blackhole`]): the node goes silent
/// (drops all inbound + outbound SWIM probes/acks, ClusterSync ping/pong, and consensus) WITHOUT
/// closing any QUIC channel. Peers keep seeing the link as CONNECTED — exactly the state after a
/// hard `docker kill` with QUIC MAX_IDLE_TIMEOUT disabled (persistent cluster connections).
///
/// EXPECTED CORRECT OUTCOME (asserted here): a silently-dead node MUST still be detected dead →
/// `NODE_FAILED` → terminal removal (victim's lifecycle on a survivor reaches `STOPPED`) within
/// the [`#DETECT_BUDGET`]. Against CURRENT code this assertion is expected to FAIL — that failure
/// is a faithful fast reproduction of the Docker forward-decommission bug.
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MembershipBlackHoleSpikeTest {
    private static final Logger log = LoggerFactory.getLogger(MembershipBlackHoleSpikeTest.class);

    private static final int SIZE = 5;
    private static final int BASE_PORT = 5360;
    private static final int BASE_MGMT_PORT = 5460;
    private static final int BASE_APP_HTTP_PORT = 5560;
    private static final Duration FORM_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration POLL = Duration.ofMillis(500);
    private static final Duration SETTLE = Duration.ofSeconds(20);     // clear 15s auto-heal cooldown
    private static final Duration DETECT_BUDGET = Duration.ofSeconds(60);
    private static final Duration OBSERVE_POLL = Duration.ofMillis(500);
    private static final Duration LOG_EVERY = Duration.ofMillis(2000);
    private static final Pattern CONNECTED_PEERS = Pattern.compile("\"connectedPeers\"\\s*:\\s*(\\d+)");
    private static final String STOPPED = "\"kvState\":\"STOPPED\"";
    private static final String DECOMMISSIONED = "\"kvState\":\"DECOMMISSIONED\"";

    private EmberCluster cluster;
    private final HttpOperations http = jdkHttpOperations();

    @BeforeAll
    @TerminalOperation
    void setUp() {
        cluster = emberCluster(SIZE, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "bh");
        cluster.start()
               .await()
               .onFailure(MembershipBlackHoleSpikeTest::failStart);

        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(() -> cluster.currentLeader().isPresent());
        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(this::allNodesHealthy);
        log.info("BLACKHOLE-SPIKE: {}-node cluster formed, leader={}", SIZE, cluster.currentLeader().or("none"));
    }

    @AfterAll
    @TerminalOperation
    void tearDown() {
        Option.option(cluster).onPresent(c -> c.stop().await());
    }

    @Test
    @TerminalOperation
    void blackHoleNonLeader_shouldStillBeDetectedDeadAndDecommissioned() {
        var nodes = cluster.status().nodes();

        Option.all(firstMatching(nodes, n -> !n.isLeader()),
                   firstMatching(nodes, NodeStatus::isLeader))
              .map(Selection::new)
              .toResult(SpikeError.LEADER_OR_VICTIM_UNAVAILABLE)
              .onFailure(MembershipBlackHoleSpikeTest::failScenario)
              .onSuccess(this::runSpike);
    }

    @TerminalOperation
    private void runSpike(Selection selection) {
        var victim = selection.victim();
        var survivorPort = selection.survivor().mgmtPort();
        log.info("BLACKHOLE-SPIKE: leader/survivor={} victim={} (will go silent, channels stay open)",
                 selection.survivor().id(), victim.id());

        log.info("BLACKHOLE-SPIKE: settling {}s (auto-heal cooldown)", SETTLE.toSeconds());
        await().pollDelay(SETTLE).timeout(SETTLE.plusSeconds(5)).until(() -> true);
        log.info("BLACKHOLE-SPIKE: pre-kill survivor connectedPeers={} stopped={}",
                 connectedPeers(survivorPort).map(Object::toString).or("?"), terminalCount(survivorPort));

        var t0 = System.nanoTime();
        cluster.blackhole(victim.id()).await();
        log.info("BLACKHOLE-SPIKE: black-holed {} at t0 — node is now silent but NOT disconnected", victim.id());

        var detectedMs = observeUntilDecommissioned(survivorPort, t0);
        log.info("BLACKHOLE-SPIKE RESULT: victim={} terminal-decommission={}ms (-1=NOT within {}s)  "
                 + "survivor connectedPeers={}",
                 victim.id(), detectedMs, DETECT_BUDGET.toSeconds(),
                 connectedPeers(survivorPort).map(Object::toString).or("?"));
        log.info("BLACKHOLE-SPIKE FINAL /api/nodes/status: {}", status(survivorPort));

        assertThat(detectedMs)
            .as("EXPECTED: silently-dead %s reaches terminal STOPPED/DECOMMISSIONED within %ds "
                + "(-1 = lingering ON_DUTY — reproduces the Docker silent-death bug)",
                victim.id(), DETECT_BUDGET.toSeconds())
            .isGreaterThanOrEqualTo(0L);
    }

    /// Polls the survivor's lifecycle view until the victim is decommissioned or the budget
    /// expires. Returns the first-observed terminal latency in ms, or -1 if never observed.
    private long observeUntilDecommissioned(int survivorPort, long t0) {
        var latch = new long[]{-1L};
        var lastLog = new long[]{0L};
        await().pollInterval(OBSERVE_POLL)
               .pollDelay(Duration.ZERO)
               .timeout(DETECT_BUDGET.plusSeconds(5))
               .until(() -> recordTick(survivorPort, t0, latch, lastLog));
        return latch[0];
    }

    private boolean recordTick(int survivorPort, long t0, long[] latch, long[] lastLog) {
        var elapsed = (System.nanoTime() - t0) / 1_000_000L;
        var terminal = terminalCount(survivorPort) >= 1;
        if (terminal && latch[0] < 0) {
            latch[0] = elapsed;
        }
        maybeLog(survivorPort, elapsed, lastLog);
        return latch[0] >= 0 || elapsed >= DETECT_BUDGET.toMillis();
    }

    private void maybeLog(int survivorPort, long elapsedMs, long[] lastLog) {
        if (elapsedMs - lastLog[0] < LOG_EVERY.toMillis()) {
            return;
        }
        lastLog[0] = elapsedMs;
        log.info("BLACKHOLE-SPIKE: t+{}ms survivor connectedPeers={} terminalCount={}",
                 elapsedMs, connectedPeers(survivorPort).map(Object::toString).or("?"), terminalCount(survivorPort));
    }

    private boolean allNodesHealthy() {
        return cluster.status().nodes().stream()
                      .allMatch(node -> httpGet(node.mgmtPort(), "/api/health").contains("\"quorum\":true"));
    }

    private Option<Integer> connectedPeers(int port) {
        var matcher = CONNECTED_PEERS.matcher(httpGet(port, "/api/health"));
        return matcher.find()
               ? Option.some(Integer.parseInt(matcher.group(1)))
               : Option.none();
    }

    private int terminalCount(int port) {
        var body = status(port);
        return countOccurrences(body, STOPPED) + countOccurrences(body, DECOMMISSIONED);
    }

    private String status(int port) {
        return httpGet(port, "/api/nodes/status");
    }

    private static int countOccurrences(String haystack, String needle) {
        return haystack.split(Pattern.quote(needle), -1).length - 1;
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

    private static <T> Option<T> firstMatching(List<T> items, Predicate<T> predicate) {
        return Option.from(items.stream().filter(predicate).findFirst());
    }

    private static void failStart(Cause cause) {
        throw new AssertionError("Cluster start failed: " + cause.message());
    }

    private static void failScenario(Cause cause) {
        throw new AssertionError("Scenario setup failed: " + cause.message());
    }

    /// Chosen victim (a non-leader, to be black-holed) and survivor (the leader, observed for the
    /// victim's lifecycle state). Immutable, shared read-only across the observation loop.
    private record Selection(NodeStatus victim, NodeStatus survivor) {}

    private enum SpikeError implements Cause {
        LEADER_OR_VICTIM_UNAVAILABLE("No leader, or no non-leader victim / leader survivor available");

        private final String message;

        SpikeError(String message) {
            this.message = message;
        }

        @Override
        public String message() {
            return message;
        }
    }
}
