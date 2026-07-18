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
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager.CircuitBreakerState;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.consensus.NodeId;
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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

/// Diagnostic probe — does the cloud-only 5→7 scale-up stall (#336) reproduce IN-JVM with the
/// Ember single-process provider, where there is NO DNS, NO advertise-host, NO container boot, and
/// nodes come up RUNNING synchronously on localhost?
///
/// ## What #336 reports
/// On cloud, `POST /api/cluster/scale {coreCount:7}` returns HTTP 200 but the 2 new cores never
/// join — the cluster lingers at 5-6 counted cores. We want to know whether this is a
/// provider-independent LOGIC bug (config-fan-out → reconciler deficit → provision → join → count)
/// or a cloud-only networking/advertise/boot problem. If THIS probe reproduces the stall, the bug
/// is in the membership/reconciler/provision logic and is not cloud-specific. If THIS probe PASSES
/// (reaches 7), the bug lives in the cloud networking/advertise/boot layer that Ember bypasses.
///
/// ## Scale-trigger path chosen: HTTP `POST /api/cluster/scale` (leader mgmt port)
/// This is the MOST FAITHFUL reproduction of the cloud path. The endpoint does NOT call
/// `addNode()` directly; it commits `ClusterConfigKey.CURRENT.coreCount = 7` to the KV store, and
/// the committed-config fan-out then drives the SAME reconciler chain the cloud bug travels:
///   route handler → KV `ClusterConfigKey.CURRENT` Put
///     → `AetherNode.onClusterConfigPut` → `leaderReconciler.onConfigChange()`
///       → deficit = configuredCoreCount(7) − effectiveCapacity(members + in-flight)
///         → `ClusterTopologyManager.provisionReplacement(..., CORE)`
///           → `NodeLifecycleManager.provisionNode` → ember `ComputeProvider.provision`
///             → `EmberCluster.addNode()` → node.start() → SWIM join → counted as core.
///
/// We deliberately AVOID two unfaithful shortcuts:
///   - `EmberCluster.addNode()` twice raw — bypasses the CTM/reconciler deficit→provision logic we
///     most suspect (the whole point is to exercise it).
///   - `EmberCluster.setClusterSize(int)` — this routes a `SetClusterSize` topology message that
///     mutates only the consensus-side `effectiveClusterSize` atomic; it does NOT write
///     `ClusterConfigKey.CURRENT`, so `configuredCoreCountSupplier` never sees 7 and the
///     deficit→provision never fires. Using it would make the probe falsely "pass".
///
/// ## What we observe
/// The counted-core denominator is read IN-PROCESS (no HTTP polling latency) via
/// `aetherNode.membershipFsm().coreCountedMembers()` — the exact set the reconciler uses for its
/// deficit math. On failure we dump, for every node Ember knows about: AetherNode present? in the
/// leader's SWIM membership view? counted as core? plus the leader's provisioning circuit-breaker
/// state (`consecutiveFailures` = "provision failed N times") and the configured target. That tells
/// us whether new nodes (a) never got provisioned, (b) provisioned but never joined the mesh, or
/// (c) joined but aren't counted.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScaleUpFiveToSevenProbeTest {
    private static final Logger log = LoggerFactory.getLogger(ScaleUpFiveToSevenProbeTest.class);

    private static final int INITIAL_CORES = 5;
    private static final int TARGET_CORES = 7;
    // Ember's slot pool is sized 2 * targetClusterSize at start() => 10 slots for a 5-core cluster.
    // 5→7 needs 2 spare slots; headroom is ample. (Pool size is internal; see caveat in the report.)
    private static final int BASE_PORT = 5660;
    private static final int BASE_MGMT_PORT = 5760;
    private static final int BASE_APP_HTTP_PORT = 5860;

    private static final Duration FORM_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration SCALE_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration POLL = Duration.ofMillis(500);
    private static final Duration LOG_EVERY = Duration.ofSeconds(5);

    private static final Pattern CONFIG_VERSION = Pattern.compile("\"configVersion\"\\s*:\\s*(\\d+)");

    private EmberCluster cluster;
    private final HttpOperations http = jdkHttpOperations();

    @BeforeAll
    @TerminalOperation
    void setUp() {
        cluster = emberCluster(INITIAL_CORES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "scale");
        cluster.start()
               .await()
               .onFailure(ScaleUpFiveToSevenProbeTest::failStart);

        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(() -> cluster.currentLeader().isPresent());
        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(() -> countedCores() == INITIAL_CORES);
        // Membership forms BEFORE the bootstrap-seed ClusterConfigKey.CURRENT is committed to KV. On a
        // fast host the probe otherwise races ahead and POSTs /api/cluster/scale before any config is
        // stored — the endpoint then 500s "No cluster configuration stored" (and a sentinel
        // expectedVersion=0 would be rejected as an unfenced overwrite, #289). Gate on the seed config
        // being durable (configVersion >= 1) so the scale carries the real fencing version.
        await().atMost(FORM_TIMEOUT)
               .pollInterval(POLL)
               .until(() -> cluster.getLeaderManagementPort().map(port -> readConfigVersion(port) >= 1).or(false));
        log.info("SCALE-PROBE: {}-core cluster formed, leader={}, countedCores={}",
                 INITIAL_CORES, cluster.currentLeader().or("none"), countedCores());
    }

    @AfterAll
    @TerminalOperation
    void tearDown() {
        Option.option(cluster).onPresent(c -> c.stop().await());
    }

    @Test
    @TerminalOperation
    void scaleFiveToSeven_newCoresJoinAndAreCounted_viaClusterScaleEndpoint() {
        var leaderPort = cluster.getLeaderManagementPort()
                                .toResult(ProbeError.NO_LEADER)
                                .onFailure(ScaleUpFiveToSevenProbeTest::failScenario)
                                .or(-1);

        var preScaleCounted = countedCores();
        var version = readConfigVersion(leaderPort);
        log.info("SCALE-PROBE: pre-scale countedCores={} leaderMgmtPort={} configVersion={}",
                 preScaleCounted, leaderPort, version);

        var scaleResponse = postScale(leaderPort, TARGET_CORES, version);
        log.info("SCALE-PROBE: POST /api/cluster/scale {{coreCount:{}, expectedVersion:{}}} -> {}",
                 TARGET_CORES, version, scaleResponse);

        var t0 = System.nanoTime();
        var reached = observeUntilTargetCounted(leaderPort, t0);
        var finalCounted = countedCores();

        log.info("SCALE-PROBE RESULT: target={} reachedAtMs={} (-1=NOT within {}s) finalCountedCores={}",
                 TARGET_CORES, reached, SCALE_TIMEOUT.toSeconds(), finalCounted);
        dumpDiagnostics(leaderPort);

        assertThat(reached)
            .as("EXPECTED: 5→7 scale via /api/cluster/scale converges to %d counted core members "
                + "within %ds. Reached=%d, finalCounted=%d. -1 = STALL — the 2 new cores never got "
                + "counted (this is #336 reproducing IN-JVM => a provider-independent logic bug, not "
                + "cloud-only). See the diagnostic dump above for whether the new nodes were never "
                + "provisioned, provisioned-but-never-joined, or joined-but-not-counted.",
                TARGET_CORES, SCALE_TIMEOUT.toSeconds(), reached, finalCounted)
            .isGreaterThanOrEqualTo(0L);
    }

    /// Polls the in-process counted-core denominator until it reaches the target or the budget
    /// expires. Returns the first-observed convergence latency in ms, or -1 if never observed.
    private long observeUntilTargetCounted(int leaderPort, long t0) {
        var latch = new long[]{-1L};
        var lastLog = new long[]{0L};
        await().pollInterval(POLL)
               .pollDelay(Duration.ZERO)
               .timeout(SCALE_TIMEOUT.plusSeconds(5))
               .until(() -> recordTick(leaderPort, t0, latch, lastLog));
        return latch[0];
    }

    private boolean recordTick(int leaderPort, long t0, long[] latch, long[] lastLog) {
        var elapsed = (System.nanoTime() - t0) / 1_000_000L;
        if (countedCores() >= TARGET_CORES && latch[0] < 0) {
            latch[0] = elapsed;
        }
        maybeLog(leaderPort, elapsed, lastLog);
        return latch[0] >= 0 || elapsed >= SCALE_TIMEOUT.toMillis();
    }

    private void maybeLog(int leaderPort, long elapsedMs, long[] lastLog) {
        if (elapsedMs - lastLog[0] < LOG_EVERY.toMillis()) {
            return;
        }
        lastLog[0] = elapsedMs;
        log.info("SCALE-PROBE: t+{}ms countedCores={} emberNodeCount={} leaderPresent={} breaker={}",
                 elapsedMs,
                 countedCores(),
                 cluster.nodeCount(),
                 cluster.currentLeader().isPresent(),
                 breakerSummary());
    }

    // ----- in-process membership reads -----

    /// The counted-core denominator the reconciler itself uses for deficit math, read off the
    /// leader's `MembershipFsm` (falls back to any node if the leader handle is momentarily absent).
    private int countedCores() {
        return leaderOrAnyNode().map(node -> node.membershipFsm().coreCountedMembers().size()).or(0);
    }

    private Option<AetherNode> leaderOrAnyNode() {
        return cluster.currentLeader()
                      .flatMap(cluster::getNode)
                      .orElse(() -> Option.from(cluster.allNodes().stream().findFirst()));
    }

    // ----- failure diagnostics -----

    @TerminalOperation
    private void dumpDiagnostics(int leaderPort) {
        var leaderNode = leaderOrAnyNode();
        var countedSet = leaderNode.map(node -> node.membershipFsm().coreCountedMembers()).or(Set.of());
        var connectedPeers = leaderNode.map(AetherNode::connectedPeerIds).or(Set.of());

        log.info("SCALE-PROBE DUMP: leader={} configuredTarget={} countedCores={}/{} ember.nodeCount={}",
                 cluster.currentLeader().or("none"),
                 readConfigVersion(leaderPort) < 0 ? "?" : Integer.toString(TARGET_CORES),
                 countedSet.size(), TARGET_CORES, cluster.nodeCount());
        log.info("SCALE-PROBE DUMP: leader counted-core set = {}", idStrings(countedSet));
        log.info("SCALE-PROBE DUMP: leader connected-peer set = {}", idStrings(connectedPeers));
        log.info("SCALE-PROBE DUMP: provisioning circuit-breaker = {}", breakerSummary());
        log.info("SCALE-PROBE DUMP: /api/cluster/config = {}", httpGet(leaderPort, "/api/cluster/config"));
        log.info("SCALE-PROBE DUMP: /api/cluster/generation = {}", httpGet(leaderPort, "/api/cluster/generation"));
        log.info("SCALE-PROBE DUMP: /api/nodes/status = {}", httpGet(leaderPort, "/api/nodes/status"));

        cluster.allNodes().forEach(node -> dumpNode(node, countedSet, connectedPeers));
    }

    private void dumpNode(AetherNode node, Set<NodeId> countedSet, Set<NodeId> connectedPeers) {
        var id = node.self();
        var inMesh = leaderOrAnyNode().map(leader -> leader.membershipView().isPresent(id)).or(false);
        log.info("SCALE-PROBE DUMP: node={} started=true leader={} inLeaderSwimView={} countedAsCore={} "
                 + "leaderSeesAsPeer={}",
                 id.id(), node.isLeader(), inMesh, countedSet.contains(id), connectedPeers.contains(id));
    }

    /// `consecutiveFailures` here is the "provision failed N times" counter; `tripped=true` means
    /// the breaker is open and further provisioning is being deferred (a likely stall cause).
    private String breakerSummary() {
        return leaderOrAnyNode().flatMap(AetherNode::clusterTopologyManager)
                                .map(ctm -> formatBreaker(ctm.circuitBreakerState()))
                                .or("no-ctm");
    }

    private static String formatBreaker(CircuitBreakerState state) {
        return "consecutiveFailures=" + state.consecutiveFailures()
               + " tripped=" + state.tripped()
               + " trippedAt=" + state.trippedAt()
               + " nextAllowedMs=" + state.nextAllowedMs();
    }

    private static String idStrings(Set<NodeId> ids) {
        return ids.stream().map(NodeId::id).sorted().collect(Collectors.joining(","));
    }

    // ----- HTTP helpers (scale trigger + read-only diagnostics) -----

    private int readConfigVersion(int port) {
        var matcher = CONFIG_VERSION.matcher(httpGet(port, "/api/cluster/config"));
        return matcher.find()
               ? Integer.parseInt(matcher.group(1))
               : 0;
    }

    @TerminalOperation
    private String postScale(int port, int coreCount, int expectedVersion) {
        var body = "{\"coreCount\":" + coreCount + ",\"expectedVersion\":" + expectedVersion + "}";
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/cluster/scale"))
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(Duration.ofSeconds(10))
                                 .build();
        return http.sendString(request)
                   .await()
                   .map(ScaleUpFiveToSevenProbeTest::renderResponse)
                   .or("scale POST failed (no response)");
    }

    private static String renderResponse(HttpResult result) {
        return "HTTP " + result.statusCode() + " " + result.body();
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
        NO_LEADER("No leader available to receive the scale request");

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
