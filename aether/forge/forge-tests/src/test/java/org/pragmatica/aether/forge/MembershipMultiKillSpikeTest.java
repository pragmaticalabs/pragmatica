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

/// Spike — in-process (Ember/single-JVM) reproduction of RAPID MULTI-MEMBER LOSS including the
/// leader (issues #230/#231). Companion to [`MembershipChaosSpikeTest`] (single non-leader kill)
/// and [`MembershipBlackHoleSpikeTest`] (silent-death of one non-leader).
///
/// The single-kill spikes only ever drop the live set from 5 → 4 — quorum (3) is never lost and
/// the existing leader survives, so they exercise failure-DETECTION but never LEADER ELECTION
/// under quorum loss. This spike instead force-kills THREE nodes — the current leader plus two
/// non-leaders — in RAPID succession (no settle between kills), dropping the live ORIGINAL set
/// from 5 to 2 (BELOW quorum 3) before any replacement can heal in. This is the scenario that
/// answers the open question: when the cluster transiently loses quorum AND its leader at once,
/// does it (a) commit a NEW leader, (b) provision replacements that become synced ON_DUTY
/// voters, and (c) recover to a quorate 5-node cluster — or does it WEDGE "formally quorate but
/// functionally dead" (replacements present in topology but never synced voters, no committed
/// leader)?
///
/// EXPECTED CORRECT OUTCOME (asserted here): within [`#RECOVER_BUDGET`] the surviving/healed
/// cluster MUST expose a committed leader AND reach SIZE healthy ON_DUTY voters. Against CURRENT
/// code this assertion is expected to FAIL — a wedge surfaces as a clear assertion failure and is
/// a faithful fast reproduction of the multi-loss leader-election gap (same pattern as the
/// BlackHole spike). The `[LEADER-DIAG]` (LeaderManager) and `[RABIA-DIAG]` (consensus) log
/// streams are the interpretation surface: read them alongside the per-second timeline this test
/// emits (observed leader, healthy/ON_DUTY counts) to see whether a LeaderCommitted ever fires,
/// whether a propose resolves or stalls, and whether replacements ever become voters.
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MembershipMultiKillSpikeTest {
    private static final Logger log = LoggerFactory.getLogger(MembershipMultiKillSpikeTest.class);

    private static final int SIZE = 5;
    private static final int QUORUM = SIZE / 2 + 1;                   // 3
    private static final int BASE_PORT = 5660;                        // distinct from chaos (5060) + blackhole (5360)
    private static final int BASE_MGMT_PORT = 5760;
    private static final int BASE_APP_HTTP_PORT = 5860;
    private static final Duration FORM_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration POLL = Duration.ofMillis(500);
    private static final Duration SETTLE = Duration.ofSeconds(20);    // clear 15s auto-heal cooldown
    private static final Duration RECOVER_BUDGET = Duration.ofSeconds(120);
    private static final Duration OBSERVE_POLL = Duration.ofMillis(500);
    private static final Duration LOG_EVERY = Duration.ofMillis(1000);
    private static final long NOT_SEEN = -1L;
    private static final Pattern CONNECTED_PEERS = Pattern.compile("\"connectedPeers\"\\s*:\\s*(\\d+)");
    private static final String ON_DUTY = "\"kvState\":\"ON_DUTY\"";

    private EmberCluster cluster;
    private final HttpOperations http = jdkHttpOperations();

    @BeforeAll
    @TerminalOperation
    void setUp() {
        cluster = emberCluster(SIZE, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "mk");
        cluster.start()
               .await()
               .onFailure(MembershipMultiKillSpikeTest::failStart);

        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(() -> cluster.currentLeader().isPresent());
        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(this::allNodesHealthy);
        log.info("MULTIKILL-SPIKE: {}-node cluster formed, leader={}", SIZE, cluster.currentLeader().or("none"));
    }

    @AfterAll
    @TerminalOperation
    void tearDown() {
        Option.option(cluster).onPresent(c -> c.stop().await());
    }

    @Test
    @TerminalOperation
    void killLeaderPlusTwo_shouldElectNewLeaderAndRecoverToQuorate() {
        var nodes = cluster.status().nodes();

        Option.all(cluster.currentLeader(),
                   firstMatching(nodes, NodeStatus::isLeader),
                   nonLeaderVictimsAndSurvivor(nodes))
              .map(Selection::new)
              .toResult(SpikeError.INSUFFICIENT_NODES)
              .onFailure(MembershipMultiKillSpikeTest::failScenario)
              .onSuccess(this::runSpike);
    }

    @TerminalOperation
    private void runSpike(Selection selection) {
        var leaderVictim = selection.leaderVictim();
        var otherVictims = selection.otherVictims();
        var survivor = selection.survivor();
        var survivorPort = survivor.mgmtPort();
        log.info("MULTIKILL-SPIKE: originalLeader={} survivor(observer)={} victims=[leader={}, {}, {}]",
                 selection.leaderId(), survivor.id(), leaderVictim.id(),
                 otherVictims.getFirst().id(), otherVictims.get(1).id());

        log.info("MULTIKILL-SPIKE: settling {}s (auto-heal cooldown)", SETTLE.toSeconds());
        await().pollDelay(SETTLE).timeout(SETTLE.plusSeconds(5)).until(() -> true);
        log.info("MULTIKILL-SPIKE: pre-kill survivor connectedPeers={} onDuty={} leader={}",
                 connectedPeers(survivorPort).map(Object::toString).or("?"),
                 onDuty(survivorPort), cluster.currentLeader().or("none"));

        var t0 = System.nanoTime();
        killRapidly(leaderVictim, otherVictims);
        log.info("MULTIKILL-SPIKE: killed leader {} + {} + {} in rapid succession at t0 "
                 + "(live ORIGINAL set now {} < quorum {})",
                 leaderVictim.id(), otherVictims.getFirst().id(), otherVictims.get(1).id(),
                 SIZE - 3, QUORUM);

        var outcome = observeUntilRecovered(survivorPort, t0);
        log.info("MULTIKILL-SPIKE RESULT: newLeaderObserved={}ms recovered(leader+{}xON_DUTY)={}ms "
                 + "(-1=not within {}s)  finalLeader={} finalOnDuty={} finalConnectedPeers={}",
                 outcome.leaderMs(), SIZE, outcome.recoveredMs(), RECOVER_BUDGET.toSeconds(),
                 cluster.currentLeader().or("none"), onDuty(survivorPort),
                 connectedPeers(survivorPort).map(Object::toString).or("?"));
        log.info("MULTIKILL-SPIKE FINAL /api/nodes/status: {}", status(survivorPort));

        assertThat(outcome.recoveredMs())
            .as("EXPECTED: after killing the leader + 2 peers (transient quorum loss), the cluster "
                + "commits a NEW leader AND heals back to %d ON_DUTY voters within %ds "
                + "(-1 = WEDGE: formally quorate-looking but no committed leader or replacements "
                + "never became synced voters — reproduces the multi-loss leader-election gap)",
                SIZE, RECOVER_BUDGET.toSeconds())
            .isGreaterThanOrEqualTo(0L);
    }

    /// Force-kills the leader and both other victims back-to-back with NO await between cluster
    /// reactions — each `killNode(...).await()` blocks only on its own kill completing, not on any
    /// cluster healing. This drops the live original set to 2 before auto-heal can provision a
    /// replacement, so the cluster genuinely crosses below quorum (3) with the leader gone.
    @TerminalOperation
    private void killRapidly(NodeStatus leaderVictim, List<NodeStatus> otherVictims) {
        cluster.killNode(leaderVictim.id(), false).await();
        cluster.killNode(otherVictims.getFirst().id(), false).await();
        cluster.killNode(otherVictims.get(1).id(), false).await();
    }

    /// Polls the survivor's view until BOTH a committed leader is observed AND SIZE ON_DUTY voters
    /// are present, or the budget expires. Latches first-crossing latencies (ms; -1 = not seen).
    private Outcome observeUntilRecovered(int survivorPort, long t0) {
        var leaderMs = new long[]{NOT_SEEN};
        var recoveredMs = new long[]{NOT_SEEN};
        var lastLog = new long[]{0L};
        await().pollInterval(OBSERVE_POLL)
               .pollDelay(Duration.ZERO)
               .timeout(RECOVER_BUDGET.plusSeconds(5))
               .until(() -> recordTick(survivorPort, t0, leaderMs, recoveredMs, lastLog));
        return new Outcome(leaderMs[0], recoveredMs[0]);
    }

    private boolean recordTick(int survivorPort, long t0, long[] leaderMs, long[] recoveredMs, long[] lastLog) {
        var elapsed = (System.nanoTime() - t0) / 1_000_000L;
        var leaderPresent = cluster.currentLeader().isPresent();
        var onDuty = onDuty(survivorPort);
        if (leaderPresent && leaderMs[0] < 0) {
            leaderMs[0] = elapsed;
        }
        if (leaderPresent && onDuty >= SIZE && recoveredMs[0] < 0) {
            recoveredMs[0] = elapsed;
        }
        maybeLog(survivorPort, elapsed, onDuty, lastLog);
        return recoveredMs[0] >= 0 || elapsed >= RECOVER_BUDGET.toMillis();
    }

    private void maybeLog(int survivorPort, long elapsedMs, int onDuty, long[] lastLog) {
        if (elapsedMs - lastLog[0] < LOG_EVERY.toMillis()) {
            return;
        }
        lastLog[0] = elapsedMs;
        log.info("MULTIKILL-SPIKE: t+{}ms observedLeader={} onDuty={} connectedPeers={}",
                 elapsedMs, cluster.currentLeader().or("none"), onDuty,
                 connectedPeers(survivorPort).map(Object::toString).or("?"));
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

    private int onDuty(int port) {
        return countOccurrences(status(port), ON_DUTY);
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

    private static Option<NonLeaderRoles> nonLeaderVictimsAndSurvivor(List<NodeStatus> nodes) {
        var nonLeaders = nodes.stream().filter(n -> !n.isLeader()).toList();
        // Need 2 victims + 1 distinct survivor among the non-leaders (the leader is killed too,
        // so the survivor MUST be a non-leader that is not one of the two non-leader victims).
        return nonLeaders.size() >= 3
               ? Option.some(new NonLeaderRoles(List.of(nonLeaders.get(0), nonLeaders.get(1)), nonLeaders.get(2)))
               : Option.none();
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

    /// Raw selection from the formed cluster: the leader id, the leader node (a victim), and the
    /// non-leader roles (two non-leader victims + one distinct non-leader survivor used as the
    /// observation port). Immutable, shared read-only across the observation loop.
    private record Selection(String leaderId, NodeStatus leaderVictim, NonLeaderRoles roles) {
        List<NodeStatus> otherVictims() {
            return roles.victims();
        }

        NodeStatus survivor() {
            return roles.survivor();
        }
    }

    /// The non-leader role split: two non-leader victims (killed alongside the leader) and one
    /// distinct non-leader survivor (observed for recovery). Kept separate so the survivor is
    /// provably not among the killed set.
    private record NonLeaderRoles(List<NodeStatus> victims, NodeStatus survivor) {}

    /// First-crossing latencies (ms; -1 = not yet observed) for the two recovery signals.
    private record Outcome(long leaderMs, long recoveredMs) {}

    private enum SpikeError implements Cause {
        INSUFFICIENT_NODES("No leader, or fewer than three non-leaders (need 2 victims + 1 survivor)");

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
