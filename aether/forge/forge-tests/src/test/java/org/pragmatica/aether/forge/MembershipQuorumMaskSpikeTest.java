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

/// Spike — in-process (Ember/single-JVM) reproduction of the QUORUM-MASK wedge (issues
/// #230/#231): the dissolution gate is computed over TRANSPORT-reachable peers, NOT over
/// actual synced Rabia voters, so transport-healthy-but-non-voting nodes inflate the count
/// above quorum and SUPPRESS the dissolution that should fire when real voters drop below it.
///
/// MECHANISM (verified by reading the wiring):
///
///   * [`SelfDrainCoordinator`] trigger #1 (`AetherNode` line ~1225) drains the node when
///     `network().connectedPeers().size() + 1 < quorumSize()` for `triggerThreshold` seconds.
///     `connectedPeers()` is the QUIC-reachable peer SET — it counts ANY node with an open
///     channel, voter or not. `quorumSize()` is pinned to the configured cluster size (3).
///   * [`TopologyObserver#evaluateQuorumState`] routes `QuorumStateNotification.DISAPPEARED`
///     (→ Rabia `Paused` + `SelfDrainCoordinator.onQuorumDisappeared`) when
///     `swimHealthyCorePeerCount + 1 < quorum`, i.e. over SWIM-HEALTHY ∩ `coreMemberIds` —
///     again a reachability/membership plane, NOT the synced-voter plane.
///
/// CONTRAST WITH [`MembershipMultiKillSpikeTest`]: that test kills leader + 2 and leaves the 2
/// survivors with NO transport-healthy non-voter present, so survivors transport-reach only each
/// other (`connectedPeers + 1 = 2 < 3`) → trigger #1 fires → they DISSOLVE. This test reproduces
/// the Docker condition where transport-healthy-but-NON-VOTING replacements are present: after the
/// same kill, the test deterministically `addNode()`s 3 replacements. They bring up QUIC + SWIM
/// (transport-healthy) but can NEVER become synced voters — consensus is already wedged below
/// quorum, so their cold-start sync round (`RabiaEngine.clusterConnected → sync`) never completes
/// quorum. They therefore MASK the survivors' transport count back to `>= quorum`, so trigger #1
/// never fires and the cluster sits "formally quorate-looking, functionally dead".
///
/// NO PRODUCTION SEAM: the split is created entirely through the public `EmberCluster.addNode()`
/// API. The added nodes are genuinely transport-healthy-but-non-voting BECAUSE consensus is below
/// quorum — exactly the chicken-and-egg the Docker replacements hit. No engine pause hook needed.
///
/// EXPECTED CORRECT (post-fix) OUTCOME — asserted here so the test FAILS NOW and PASSES once the
/// gate is recomputed over synced voters: when synced voters drop below quorum, the cluster MUST
/// cleanly DISSOLVE — every ORIGINAL survivor drains to terminal STOPPED within [`#DISSOLVE_BUDGET`]
/// regardless of transport-healthy non-voting nodes masking the count. Against CURRENT code the
/// survivors stay ON_DUTY (no self-drain) while consensus makes no progress — the wedge — and this
/// assertion fails with a message that names which outcome occurred.
///
/// INTERPRETATION SURFACE: the `[LEADER-DIAG]` (LeaderManager / LeaderElectionState) and
/// `[RABIA-DIAG]` (RabiaEngine) log streams. The decisive wedge signature is a `quorate=true`
/// survivor view (and/or a committed leader) present WHILE `[RABIA-DIAG] ... STALL PROPOSALS:`
/// shows `have < quorum` with no `[RABIA-DIAG] ... DECIDED` and no `Self-drain: ... initiating
/// drain`. Mind the `batch-N` substring trap when grepping `DECIDED`.
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MembershipQuorumMaskSpikeTest {
    private static final Logger log = LoggerFactory.getLogger(MembershipQuorumMaskSpikeTest.class);

    private static final int SIZE = 5;
    private static final int QUORUM = SIZE / 2 + 1;                  // 3
    private static final int MASK_NODES = 3;                         // restore transport count to >= quorum
    private static final int BASE_PORT = 5960;                       // distinct from chaos (5060), blackhole (5360), multikill (5660)
    private static final int BASE_MGMT_PORT = 6060;
    private static final int BASE_APP_HTTP_PORT = 6160;
    private static final Duration FORM_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration POLL = Duration.ofMillis(500);
    private static final Duration SETTLE = Duration.ofSeconds(20);   // clear 15s auto-heal cooldown
    private static final Duration MASK_SETTLE = Duration.ofSeconds(15); // let added nodes bring up transport+SWIM
    private static final Duration DISSOLVE_BUDGET = Duration.ofSeconds(120);
    private static final Duration OBSERVE_POLL = Duration.ofMillis(500);
    private static final Duration LOG_EVERY = Duration.ofMillis(1000);
    private static final long NOT_SEEN = -1L;
    private static final Pattern CONNECTED_PEERS = Pattern.compile("\"connectedPeers\"\\s*:\\s*(\\d+)");
    private static final Pattern QUORATE = Pattern.compile("\"quorate\"\\s*:\\s*(true|false)");
    private static final String ON_DUTY = "\"kvState\":\"ON_DUTY\"";

    private EmberCluster cluster;
    private final HttpOperations http = jdkHttpOperations();

    @BeforeAll
    @TerminalOperation
    void setUp() {
        cluster = emberCluster(SIZE, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "qm");
        cluster.start()
               .await()
               .onFailure(MembershipQuorumMaskSpikeTest::failStart);

        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(() -> cluster.currentLeader().isPresent());
        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(this::allNodesHealthy);
        log.info("QUORUMMASK-SPIKE: {}-node cluster formed, leader={}", SIZE, cluster.currentLeader().or("none"));
    }

    @AfterAll
    @TerminalOperation
    void tearDown() {
        Option.option(cluster).onPresent(c -> c.stop().await());
    }

    @Test
    @TerminalOperation
    void killVotersBelowQuorumWithTransportMask_shouldStillDissolve() {
        var nodes = cluster.status().nodes();

        Option.all(cluster.currentLeader(),
                   firstMatching(nodes, NodeStatus::isLeader),
                   nonLeaderVictimsAndSurvivor(nodes))
              .map(Selection::new)
              .toResult(SpikeError.INSUFFICIENT_NODES)
              .onFailure(MembershipQuorumMaskSpikeTest::failScenario)
              .onSuccess(this::runSpike);
    }

    @TerminalOperation
    private void runSpike(Selection selection) {
        var leaderVictim = selection.leaderVictim();
        var otherVictims = selection.otherVictims();
        var survivor = selection.survivor();
        var survivorPort = survivor.mgmtPort();
        var survivorId = survivor.id();
        log.info("QUORUMMASK-SPIKE: originalLeader={} survivor(observer)={} victims=[leader={}, {}, {}]",
                 selection.leaderId(), survivorId, leaderVictim.id(),
                 otherVictims.getFirst().id(), otherVictims.get(1).id());

        log.info("QUORUMMASK-SPIKE: settling {}s (auto-heal cooldown)", SETTLE.toSeconds());
        await().pollDelay(SETTLE).timeout(SETTLE.plusSeconds(5)).until(() -> true);
        log.info("QUORUMMASK-SPIKE: pre-kill survivor connectedPeers={} quorate={} onDuty={} leader={}",
                 connectedPeers(survivorPort).map(Object::toString).or("?"),
                 quorate(survivorPort).map(Object::toString).or("?"),
                 onDuty(survivorPort), cluster.currentLeader().or("none"));

        var t0 = System.nanoTime();
        killRapidly(leaderVictim, otherVictims);
        log.info("QUORUMMASK-SPIKE: killed leader {} + {} + {} in rapid succession at t0 "
                 + "(synced ORIGINAL voters now {} < quorum {})",
                 leaderVictim.id(), otherVictims.getFirst().id(), otherVictims.get(1).id(),
                 SIZE - 3, QUORUM);

        // Deterministically inject transport-healthy-but-non-voting nodes. These come up on QUIC +
        // SWIM (so survivors transport-reach them) but cannot sync to voter — consensus is already
        // wedged below quorum. They MASK the survivors' `connectedPeers + 1` count back to >= quorum,
        // suppressing SelfDrainCoordinator trigger #1. This is the Docker replacement condition.
        injectTransportMask();
        log.info("QUORUMMASK-SPIKE: requested {} transport-mask nodes; settling {}s for transport+SWIM bring-up",
                 MASK_NODES, MASK_SETTLE.toSeconds());
        await().pollDelay(MASK_SETTLE).timeout(MASK_SETTLE.plusSeconds(5)).until(() -> true);
        log.info("QUORUMMASK-SPIKE: post-mask survivor connectedPeers={} quorate={} onDuty={} leader={} nodeCount={}",
                 connectedPeers(survivorPort).map(Object::toString).or("?"),
                 quorate(survivorPort).map(Object::toString).or("?"),
                 onDuty(survivorPort), cluster.currentLeader().or("none"), cluster.nodeCount());

        var outcome = observeUntilDissolved(survivorPort, survivorId, t0);
        log.info("QUORUMMASK-SPIKE RESULT: wedgeWindowFirstSeen={}ms survivorDissolved(self->STOPPED/gone)={}ms "
                 + "(-1=WEDGE PERSISTS: survivor never dissolved within {}s)  "
                 + "finalLeader={} finalQuorate={} finalSurvivorState={} finalConnectedPeers={}",
                 outcome.wedgeMs(), outcome.dissolvedMs(), DISSOLVE_BUDGET.toSeconds(),
                 cluster.currentLeader().or("none"),
                 quorate(survivorPort).map(Object::toString).or("?"),
                 survivorState(survivorPort, survivorId),
                 connectedPeers(survivorPort).map(Object::toString).or("?"));
        log.info("QUORUMMASK-SPIKE FINAL /api/nodes/status: {}", status(survivorPort));

        assertThat(outcome.dissolvedMs())
            .as("EXPECTED (post-fix): after the synced voter set drops below quorum (%d), the cluster "
                + "DISSOLVES — the surviving ORIGINAL voter %s itself drains to terminal STOPPED (or its "
                + "process is gone) within %ds, even though %d transport-healthy-but-non-voting nodes mask "
                + "the transport count back to >= quorum.%n"
                + "-1 = WEDGE PERSISTS: the survivor stayed ON_DUTY / quorate-looking with no self-drain "
                + "for the whole budget because the dissolution gate counts transport-reachable peers, "
                + "not synced voters (#230/#231). wedgeWindowFirstSeen=%dms records when the survivor was "
                + "first observed alive + quorate + committed-leader while still ON_DUTY (the formally-"
                + "quorate-but-functionally-dead signature) — cross-check the timeline: that window "
                + "coincides with [RABIA-DIAG] STALL PROPOSALS have<quorum and no net-forward DECIDED.",
                QUORUM, survivorId, DISSOLVE_BUDGET.toSeconds(), MASK_NODES, outcome.wedgeMs())
            .isGreaterThanOrEqualTo(0L);
    }

    /// Force-kills the leader and both other victims back-to-back with NO await between cluster
    /// reactions — drops the synced ORIGINAL voter set to 2 (below quorum 3) with the leader gone,
    /// before any replacement can sync. Mirrors `MembershipMultiKillSpikeTest.killRapidly`.
    @TerminalOperation
    private void killRapidly(NodeStatus leaderVictim, List<NodeStatus> otherVictims) {
        cluster.killNode(leaderVictim.id(), false).await();
        cluster.killNode(otherVictims.getFirst().id(), false).await();
        cluster.killNode(otherVictims.get(1).id(), false).await();
    }

    /// Adds `MASK_NODES` fresh nodes via the public Ember API. Each brings up QUIC + SWIM and so
    /// becomes transport-reachable by the survivors, but cannot become a synced Rabia voter while
    /// consensus is wedged below quorum — the exact transport-healthy-but-non-voting state that
    /// inflates the survivors' `connectedPeers` count and masks the dissolution trigger.
    @TerminalOperation
    private void injectTransportMask() {
        for (var i = 0; i < MASK_NODES; i++) {
            cluster.addNode()
                   .map(id -> "added mask node " + id.id())
                   .onSuccess(log::info)
                   .onFailure(cause -> log.warn("QUORUMMASK-SPIKE: mask node add failed: {}", cause.message()))
                   .await();
        }
    }

    /// Polls the survivor's OWN lifecycle until it dissolves (self-drains to terminal STOPPED, or
    /// its process is gone from the Ember registry / unreachable) or the budget expires. Also
    /// latches the first WEDGE observation: survivor quorate-with-committed-leader yet ON_DUTY count
    /// below quorum (formally-quorate-but-functionally-dead). Both latencies in ms; -1 = never.
    private Outcome observeUntilDissolved(int survivorPort, String survivorId, long t0) {
        var dissolvedMs = new long[]{NOT_SEEN};
        var wedgeMs = new long[]{NOT_SEEN};
        var lastLog = new long[]{0L};
        await().pollInterval(OBSERVE_POLL)
               .pollDelay(Duration.ZERO)
               .timeout(DISSOLVE_BUDGET.plusSeconds(5))
               .until(() -> recordTick(survivorPort, survivorId, t0, dissolvedMs, wedgeMs, lastLog));
        return new Outcome(wedgeMs[0], dissolvedMs[0]);
    }

    private boolean recordTick(int survivorPort,
                               String survivorId,
                               long t0,
                               long[] dissolvedMs,
                               long[] wedgeMs,
                               long[] lastLog) {
        var elapsed = (System.nanoTime() - t0) / 1_000_000L;
        var body = status(survivorPort);
        // Dissolution of the SURVIVOR specifically: either its process is gone (self-drain removed
        // it from the Ember registry → /api/health is unreachable), or its OWN node entry reached
        // terminal STOPPED/DECOMMISSIONED. The aggregate STOPPED count is NOT used — that would
        // false-latch on the three killed ORIGINAL nodes whose kvState is already STOPPED.
        var survivorGone = !survivorReachable(survivorPort);
        var survivorTerminal = survivorGone || isSurvivorTerminal(body, survivorId);
        if (survivorTerminal && dissolvedMs[0] < 0) {
            dissolvedMs[0] = elapsed;
        }
        // Wedge signature: survivor is alive, reports quorate, a committed leader exists, AND the
        // survivor's OWN lifecycle is still ON_DUTY (it has not begun draining) — the cluster looks
        // formally quorate while the synced-voter set is below quorum and consensus cannot make net
        // forward progress (see the [RABIA-DIAG] STALL PROPOSALS stream). This is the
        // formally-quorate-but-functionally-dead state the dissolution gate fails to catch because
        // it counts transport-reachable peers (incl. the masking non-voters), not synced voters.
        var quorate = quorate(body).or(false);
        var leaderPresent = cluster.currentLeader().isPresent();
        var survivorOnDuty = survivorState(body, survivorId).equals("ON_DUTY");
        if (!survivorGone && quorate && leaderPresent && survivorOnDuty && wedgeMs[0] < 0) {
            wedgeMs[0] = elapsed;
        }
        maybeLog(survivorPort, survivorId, elapsed, lastLog);
        return dissolvedMs[0] >= 0 || elapsed >= DISSOLVE_BUDGET.toMillis();
    }

    private void maybeLog(int survivorPort, String survivorId, long elapsedMs, long[] lastLog) {
        if (elapsedMs - lastLog[0] < LOG_EVERY.toMillis()) {
            return;
        }
        lastLog[0] = elapsedMs;
        var body = status(survivorPort);
        log.info("QUORUMMASK-SPIKE: t+{}ms observedLeader={} quorate={} onDuty={} survivorState={} connectedPeers={} nodeCount={}",
                 elapsedMs, cluster.currentLeader().or("none"),
                 quorate(body).map(Object::toString).or("?"),
                 countOccurrences(body, ON_DUTY), survivorState(body, survivorId),
                 connectedPeers(survivorPort).map(Object::toString).or("?"), cluster.nodeCount());
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

    private Option<Boolean> quorate(int port) {
        return quorate(status(port));
    }

    private static Option<Boolean> quorate(String body) {
        var matcher = QUORATE.matcher(body);
        return matcher.find()
               ? Option.some(Boolean.parseBoolean(matcher.group(1)))
               : Option.none();
    }

    private boolean survivorReachable(int port) {
        return !httpGet(port, "/api/health").equals("{}");
    }

    /// The survivor's OWN derived/kv state from a `/api/nodes/status` body, or `gone` when its
    /// process has left the registry. Parses the per-node JSON object for `survivorId`.
    private String survivorState(int port, String survivorId) {
        return survivorReachable(port)
               ? survivorState(status(port), survivorId)
               : "gone";
    }

    private static String survivorState(String body, String survivorId) {
        var idx = body.indexOf("\"id\":\"" + survivorId + "\"");
        if (idx < 0) {
            return "absent";
        }
        var end = body.indexOf('}', idx);
        var entry = end < 0 ? body.substring(idx) : body.substring(idx, end);
        return entry.contains("\"kvState\":\"STOPPED\"") ? "STOPPED"
             : entry.contains("\"kvState\":\"DECOMMISSIONED\"") ? "DECOMMISSIONED"
             : entry.contains("\"kvState\":\"DRAINING\"") ? "DRAINING"
             : entry.contains("\"kvState\":\"ON_DUTY\"") ? "ON_DUTY"
             : "?";
    }

    /// True when the survivor's OWN node entry is in a terminal lifecycle state (STOPPED or
    /// DECOMMISSIONED) — distinct from the aggregate terminal count which includes killed peers.
    private static boolean isSurvivorTerminal(String body, String survivorId) {
        var state = survivorState(body, survivorId);
        return state.equals("STOPPED") || state.equals("DECOMMISSIONED") || state.equals("absent");
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
    /// distinct non-leader survivor (observed for dissolution). Kept separate so the survivor is
    /// provably not among the killed set.
    private record NonLeaderRoles(List<NodeStatus> victims, NodeStatus survivor) {}

    /// First-crossing latencies (ms; -1 = not observed): `wedgeMs` when the survivor was first
    /// seen formally-quorate-but-functionally-dead; `dissolvedMs` when the survivor itself reached
    /// terminal (self-drained / gone).
    private record Outcome(long wedgeMs, long dissolvedMs) {}

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
