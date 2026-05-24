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
import org.pragmatica.aether.deployment.membership.PhiAccrualDetector;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPong;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.TerminalOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.ember.EmberCluster.NodeStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

/// Spike-1 — in-process (Ember/single-JVM) φ-accrual vs current-aggregator comparison (issue #231).
///
/// Forms a 5-node cluster, attaches an INDEPENDENT [`PhiAccrualDetector`] to EVERY node's
/// ClusterSync pong stream (leaderless shadow — the leader receives ZERO pongs, so a
/// leader-only φ detector never fires; φ must observe from whichever node actually sees the
/// victim's pongs). Settles past the 15s auto-heal cooldown and the K_min=8-sample φ warmup,
/// logs the per-observer pong-sender topology matrix, then force-kills a non-leader victim and
/// measures — for the SAME kill — the first-crossing latency of four signals: (1) φ-accrual
/// suspicion crossing Φ from the fastest observer, (2) the current aggregator transport signal
/// (a survivor's connectedPeers dropping to SIZE-1), (3) lifecycle decommission (victim kvState
/// → STOPPED), and (4) auto-heal recovery. See
/// `aether/docs/internal/membership-failure-detection-unification.md` §5E/§6.1.
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
    private static final Duration SETTLE = Duration.ofSeconds(25);    // clear 15s auto-heal cooldown + warm φ windows (>= K_min samples)
    private static final Duration OBSERVE = Duration.ofSeconds(120);
    private static final Duration PHI_POLL = Duration.ofMillis(250);  // fine-grained so we catch the φ crossing sub-second
    private static final Duration LOG_EVERY = Duration.ofMillis(1000);
    private static final long NOT_SEEN = -1L;
    private static final Pattern CONNECTED_PEERS = Pattern.compile("\"connectedPeers\"\\s*:\\s*(\\d+)");
    private static final String ON_DUTY = "\"kvState\":\"ON_DUTY\"";
    private static final String STOPPED = "\"kvState\":\"STOPPED\"";

    private EmberCluster cluster;
    private final HttpOperations http = jdkHttpOperations();

    @BeforeAll
    @TerminalOperation
    void setUp() {
        cluster = emberCluster(SIZE, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "ch");
        cluster.start()
               .await()
               .onFailure(MembershipChaosSpikeTest::failStart);

        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(() -> cluster.currentLeader().isPresent());
        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(this::allNodesHealthy);
        log.info("SPIKE: {}-node cluster formed, leader={}", SIZE, cluster.currentLeader().or("none"));
    }

    @AfterAll
    @TerminalOperation
    void tearDown() {
        Option.option(cluster).onPresent(c -> c.stop().await());
    }

    @Test
    @TerminalOperation
    void killNonLeader_comparePhiVsAggregatorVsDecommission() {
        var nodes = cluster.status().nodes();

        Option.all(cluster.currentLeader(),
                   firstMatching(nodes, n -> !n.isLeader()),
                   firstMatching(nodes, NodeStatus::isLeader))
              .map(Selection::new)
              .toResult(SpikeError.LEADER_OR_VICTIM_UNAVAILABLE)
              .flatMap(this::buildScenario)
              .onFailure(MembershipChaosSpikeTest::failScenario)
              .onSuccess(scenario -> runSpike(scenario, nodes));
    }

    private Result<Scenario> buildScenario(Selection selection) {
        return NodeId.nodeId(selection.victim().id())
                     .map(victimNid -> new Scenario(selection.leaderId(),
                                                    selection.victim(),
                                                    selection.survivor(),
                                                    victimNid,
                                                    selection.survivor().mgmtPort()));
    }

    @TerminalOperation
    private void runSpike(Scenario scenario, List<NodeStatus> nodes) {
        var observers = attachShadowDetectors(nodes);
        log.info("SPIKE: leader={} victim={} victimNid={} — φ-shadow attached to ALL {} nodes' pong streams",
                 scenario.leaderId(), scenario.victim().id(), scenario.victimNid().id(), nodes.size());

        log.info("SPIKE: settling {}s (auto-heal cooldown + φ warmup)", SETTLE.toSeconds());
        await().pollDelay(SETTLE).timeout(SETTLE.plusSeconds(5)).until(() -> true);
        observers.forEach(MembershipChaosSpikeTest::logTopologyRow);

        var t0 = System.nanoTime();
        cluster.killNode(scenario.victim().id(), false).await();
        log.info("SPIKE: force-killed {} at t0", scenario.victim().id());

        var crossings = observeUntilRecovered(scenario, observers, t0);
        log.info("SPIKE RESULT (same kill): φ-suspect={}ms  transport-detect={}ms  decommission={}ms  auto-heal={}ms  (-1=not within {}s)",
                 crossings.phiMs(), crossings.transportMs(), crossings.decommissionMs(), crossings.recoveredMs(), OBSERVE.toSeconds());
        log.info("SPIKE FINAL /api/nodes/status: {}", status(scenario.survivorPort()));

        assertThat(crossings.phiMs()).as("φ-accrual suspicion latency ms (-1=none)").isGreaterThanOrEqualTo(NOT_SEEN);
    }

    private Crossings observeUntilRecovered(Scenario scenario, List<PhiObserver> observers, long t0) {
        var crossings = Crossings.empty();
        var lastLog = new long[]{0L};
        await().pollInterval(PHI_POLL)
               .pollDelay(Duration.ZERO)
               .timeout(OBSERVE.plusSeconds(5))
               .until(() -> recordTick(scenario, observers, t0, crossings, lastLog));
        return crossings;
    }

    /// One observation poll: snapshots every signal, records first crossings, periodically logs
    /// the timeline. Returns `true` once auto-heal recovery is observed (terminates the loop) or
    /// once the observation budget is exhausted.
    private boolean recordTick(Scenario scenario, List<PhiObserver> observers, long t0, Crossings crossings, long[] lastLog) {
        var now = System.currentTimeMillis();
        var elapsed = (System.nanoTime() - t0) / 1_000_000L;
        var tick = sampleTick(scenario, observers, now, elapsed);

        crossings.observe(tick, SIZE);
        maybeLogTimeline(tick, lastLog);

        return crossings.recovered() || elapsed >= OBSERVE.toMillis();
    }

    private Tick sampleTick(Scenario scenario, List<PhiObserver> observers, long now, long elapsed) {
        var phi = maxPhiAcrossObservers(scenario, observers, now);
        var connectedPeers = connectedPeers(scenario.survivorPort());
        var onDuty = onDuty(scenario.survivorPort());
        var stopped = stopped(scenario.survivorPort());
        return new Tick(elapsed, phi, connectedPeers, onDuty, stopped);
    }

    /// Maximum φ(victim) across all non-victim observers, plus which observer achieved it and
    /// whether ANY observer suspects (φ>Φ). The leader observes zero pongs, so this surfaces the
    /// fastest LOCAL detector — exactly the leaderless-detection signal a node computes itself.
    private PhiReading maxPhiAcrossObservers(Scenario scenario, List<PhiObserver> observers, long now) {
        return observers.stream()
                        .filter(observer -> !observer.id().equals(scenario.victim().id()))
                        .map(observer -> observer.read(scenario.victimNid(), now))
                        .reduce(PhiReading.NONE, PhiReading::merge);
    }

    private List<PhiObserver> attachShadowDetectors(List<NodeStatus> nodes) {
        return nodes.stream()
                    .map(NodeStatus::id)
                    .flatMap(id -> attachObserver(id).stream())
                    .toList();
    }

    private Option<PhiObserver> attachObserver(String observerId) {
        return cluster.getNode(observerId)
                      .map(node -> registerObserver(observerId, node));
    }

    private PhiObserver registerObserver(String observerId, AetherNode node) {
        var observer = PhiObserver.phiObserver(observerId);
        node.metricsCollector().addPongListener(observer::onPong);
        return observer;
    }

    private static void maybeLogTimeline(Tick tick, long[] lastLog) {
        if (tick.elapsedMs() - lastLog[0] < LOG_EVERY.toMillis()) {
            return;
        }
        lastLog[0] = tick.elapsedMs();
        log.info("SPIKE: t+{}ms maxφ(victim)={} @{} connectedPeers={} onDuty={} stopped={}",
                 tick.elapsedMs(), String.format("%.2f", tick.phi().value()), tick.phi().observerId(),
                 tick.connectedPeers().map(Object::toString).or("?"), tick.onDuty(), tick.stopped());
    }

    private static void logTopologyRow(PhiObserver observer) {
        log.info("SPIKE-DBG: observer {} received pongs from = {}", observer.id(), observer.pongCounts());
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

    private int stopped(int port) {
        return countOccurrences(status(port), STOPPED);
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

    /// Raw selection from the formed cluster: the leader id plus the chosen victim (a non-leader)
    /// and survivor (the leader). Lifted to [`Scenario`] once the victim's [`NodeId`] parses.
    private record Selection(String leaderId, NodeStatus victim, NodeStatus survivor) {}

    /// Fixed scenario inputs derived once after formation — immutable, shared read-only across
    /// the observation loop.
    private record Scenario(String leaderId, NodeStatus victim, NodeStatus survivor, NodeId victimNid, int survivorPort) {}

    /// Per-observer shadow: an independent φ detector plus a concurrent per-sender pong tally.
    /// Pongs arrive from many listener-pool threads, so the tally is a thread-safe map updated
    /// with `merge`; reads (the topology log + φ queries) happen from the test thread.
    private record PhiObserver(String id, PhiAccrualDetector detector, Map<NodeId, Long> pongCounts) {
        static PhiObserver phiObserver(String id) {
            return new PhiObserver(id, PhiAccrualDetector.phiAccrualDetector(), new ConcurrentHashMap<>());
        }

        void onPong(ClusterSyncPong pong) {
            detector.heartbeat(pong.sender(), System.currentTimeMillis());
            pongCounts.merge(pong.sender(), 1L, Long::sum);
        }

        PhiReading read(NodeId victim, long now) {
            return new PhiReading(detector.phi(victim, now), id, detector.suspected(victim, now));
        }
    }

    /// A φ snapshot from a single observer; `merge` keeps the maximum φ while OR-ing suspicion,
    /// so reducing across observers yields the fastest local detector's verdict.
    private record PhiReading(double value, String observerId, boolean suspected) {
        static final PhiReading NONE = new PhiReading(0.0, "none", false);

        static PhiReading merge(PhiReading a, PhiReading b) {
            var maxByPhi = a.value() >= b.value() ? a : b;
            return new PhiReading(maxByPhi.value(), maxByPhi.observerId(), a.suspected() || b.suspected());
        }
    }

    /// One observation poll's full snapshot (growing context fed to crossing detection + logging).
    private record Tick(long elapsedMs, PhiReading phi, Option<Integer> connectedPeers, int onDuty, int stopped) {
        boolean transportDetected(int size) {
            return connectedPeers.filter(cp -> cp <= size - 2).isPresent();
        }

        boolean decommissioned() {
            return stopped >= 1;
        }

        boolean recovered(int size) {
            return connectedPeers.filter(cp -> cp >= size - 1).isPresent() && onDuty >= size;
        }
    }

    /// First-crossing latencies (ms; -1 = not yet observed). Thread-confined to the test thread
    /// (the awaitility poll runs single-threaded), so in-place latching is safe here.
    private static final class Crossings {
        private long phiMs = NOT_SEEN;
        private long transportMs = NOT_SEEN;
        private long decommissionMs = NOT_SEEN;
        private long recoveredMs = NOT_SEEN;

        static Crossings empty() {
            return new Crossings();
        }

        void observe(Tick tick, int size) {
            phiMs = latch(phiMs, tick.elapsedMs(), tick.phi().suspected());
            transportMs = latch(transportMs, tick.elapsedMs(), tick.transportDetected(size));
            decommissionMs = latch(decommissionMs, tick.elapsedMs(), tick.decommissioned());
            recoveredMs = latch(recoveredMs, tick.elapsedMs(), decommissionMs >= 0 && tick.recovered(size));
        }

        boolean recovered() {
            return recoveredMs >= 0;
        }

        long phiMs() {
            return phiMs;
        }

        long transportMs() {
            return transportMs;
        }

        long decommissionMs() {
            return decommissionMs;
        }

        long recoveredMs() {
            return recoveredMs;
        }

        private static long latch(long current, long elapsedMs, boolean crossed) {
            return current < 0 && crossed ? elapsedMs : current;
        }
    }

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
