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
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.SliceState;
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

/// #427 deliverable 4 — end-to-end evidence check: an artifact seeded before a MANAGED 5→7→5 scale
/// cycle must survive the cycle (its DHT-resident bytecode chunks are not lost when surplus cores
/// drain). Forge-level analog of the DHT-unit `DHTChurnSurvivalTest.churn_5to7to5_...` case: it
/// exercises the FULL CTM→departure-push wiring rather than calling `pushOnDeparture` directly.
///
/// ## The loss mode this guards (issue #427)
/// `DHTRebalancer.onNodeRemoved` re-replicates a key only from a SURVIVOR that is already a holder,
/// so a scale-down that prunes ALL acked holders of a key would leave zero copies. The C1 fix is
/// the departing-node push: `DHTRebalancer.pushOnDeparture(...)` (integrations/dht) pushes
/// locally-held-at-risk chunks (ack-gated) before the node halts. It is wired at
/// `AetherNode.java:1852` — `departurePush = () -> dhtRebalancer.pushOnDeparture(...)` — fed into
/// `DrainProcedure` (AetherNode.java:1857), invoked once at the INACTIVE→DRAINING transition. With
/// the fix present at HEAD this is an ENABLED green-gate: the seeded artifact survives the churn.
///
/// ## Managed scale trigger: HTTP `POST /api/cluster/scale` (the REAL ClusterConfigKey path)
/// Mirrors `ScaleUpFiveToSevenProbeTest` (its `postScale` / `readConfigVersion` /
/// `observeUntilTargetCounted` counted-core settle helpers). The endpoint commits
/// `ClusterConfigKey.CURRENT.coreCount`, whose fan-out drives `AetherNode.onClusterConfigPut` →
/// `LeaderReconciler.onConfigChange()` (AetherNode.java:3609-3610) → deficit→provision (up-leg) and
/// surplus-drain→departure-push (down-leg). This is the faithful managed path #427 targets.
///
/// CAVEAT RESOLVED: an earlier draft used `EmberCluster.setClusterSize`, which routes only to
/// `TopologyObserver.handleSetClusterSize` (TopologyObserver.java:845) — it moves the
/// `effectiveClusterSize` quorum denominator but never writes `ClusterConfigKey`, so it would NOT
/// fire the provision/drain reconciler and the churn could no-op. This version drives the KV path,
/// so the physical scale actually happens.
///
/// ## Anti-vacuous guard (repo has a standing "forge-tests vacuous pass" hazard)
/// Survival can never pass on a no-op: the test HARD-ASSERTS the cluster physically reached 7
/// counted cores after the up-leg AND settled back to 5 after the down-leg — read from
/// `membershipFsm().coreCountedMembers()`, the exact denominator the reconciler uses. If either leg
/// fails to converge within the settle budget the test FAILS (that IS the signal the trigger no-ops
/// and the departure push was never exercised). The artifact-survival check (ACTIVE + `/api/slices`)
/// is the load-bearing post-condition on top of the confirmed churn. (The optional "a seeded
/// chunk's holder set changed" assertion is NOT added — per-key DHT ring holders are not exposed on
/// the Ember/Forge surface without deep plumbing; the asserted counted-core 5→7→5 convergence is the
/// sufficient anti-vacuous guard.)
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ArtifactChurnSurvival5to7to5ProbeTest {
    private static final Logger log = LoggerFactory.getLogger(ArtifactChurnSurvival5to7to5ProbeTest.class);

    private static final int INITIAL_CORES = 5;
    private static final int TARGET_CORES = 7;
    private static final int BASE_PORT = 5680;
    private static final int BASE_MGMT_PORT = 5780;
    private static final int BASE_APP_HTTP_PORT = 5880;

    private static final String TEST_ARTIFACT = TestArtifacts.ECHO_SLICE;
    private static final String BLUEPRINT_ID = "forge.test:artifact-churn:1.0.0";
    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";

    private static final Duration FORM_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration DEPLOY_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration SCALE_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration SURVIVE_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration POLL = Duration.ofMillis(500);
    private static final Duration LOG_EVERY = Duration.ofSeconds(5);

    private static final Pattern CONFIG_VERSION = Pattern.compile("\"configVersion\"\\s*:\\s*(\\d+)");

    private EmberCluster cluster;
    private final HttpOperations http = jdkHttpOperations();

    @BeforeAll
    @TerminalOperation
    void setUp() {
        cluster = emberCluster(INITIAL_CORES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "churn");
        cluster.start()
               .await()
               .onFailure(ArtifactChurnSurvival5to7to5ProbeTest::failStart);

        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(() -> cluster.currentLeader().isPresent());
        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(this::allNodesHealthy);
        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(() -> countedCores() == INITIAL_CORES);
        log.info("CHURN-PROBE: {}-core cluster formed, leader={}, countedCores={}",
                 INITIAL_CORES, cluster.currentLeader().or("none"), countedCores());
    }

    @AfterAll
    @TerminalOperation
    void tearDown() {
        Option.option(cluster).onPresent(c -> c.stop().await());
    }

    @Test
    @TerminalOperation
    void seededArtifact_survivesManagedFiveToSevenToFiveChurn() {
        var deployResponse = deploy(leaderPort(), TEST_ARTIFACT);
        assertThat(deployResponse).doesNotContain("\"error\"");
        await().atMost(DEPLOY_TIMEOUT).pollInterval(POLL).ignoreExceptions()
               .until(() -> sliceIsActive(TEST_ARTIFACT));
        log.info("CHURN-PROBE: artifact seeded and ACTIVE at countedCores={}", countedCores());

        var upLatency = managedScale(TARGET_CORES);
        assertThat(upLatency)
            .as("GUARD: managed 5->7 via /api/cluster/scale must physically reach %d counted cores "
                + "within %ds (latch=-1 => no physical churn, the trigger no-ops and the departure "
                + "push is never exercised — vacuous survival is thereby impossible).",
                TARGET_CORES, SCALE_TIMEOUT.toSeconds())
            .isGreaterThanOrEqualTo(0L);

        var downLatency = managedScale(INITIAL_CORES);
        assertThat(downLatency)
            .as("GUARD: managed 7->5 via /api/cluster/scale must settle back to %d counted cores "
                + "within %ds (latch=-1 => surplus never drained, so the departure push never ran).",
                INITIAL_CORES, SCALE_TIMEOUT.toSeconds())
            .isGreaterThanOrEqualTo(0L);

        await().atMost(SURVIVE_TIMEOUT).pollInterval(POLL).ignoreExceptions()
               .until(() -> sliceIsActive(TEST_ARTIFACT));

        var slices = getSlices(leaderPort());
        assertThat(slices)
            .as("LOAD-BEARING: the artifact seeded before the confirmed managed 5->7->5 churn "
                + "survives it and is still resolvable at HEAD (C1 departure-push fix present: "
                + "DHTRebalancer.pushOnDeparture wired at AetherNode.java:1852). Missing artifact => "
                + "its DHT chunks were lost when surplus cores drained — #427 reproducing end-to-end.")
            .contains(TEST_ARTIFACT)
            .doesNotContain("\"error\"");
    }

    // ----- managed scale via the real ClusterConfigKey path (mirrors ScaleUpFiveToSevenProbeTest) -----

    /// Commits the target core count through `POST /api/cluster/scale` and blocks until the
    /// in-process counted-core denominator equals it (or the budget expires). Returns the
    /// convergence latency in ms, or -1 if the target was never observed within the budget.
    @TerminalOperation
    private long managedScale(int targetCores) {
        var port = leaderPort();
        var version = readConfigVersion(port);
        var response = postScale(port, targetCores, version);
        log.info("CHURN-PROBE: POST /api/cluster/scale {{coreCount:{}, expectedVersion:{}}} -> {}",
                 targetCores, version, response);
        return awaitCounted(targetCores, SCALE_TIMEOUT);
    }

    private long awaitCounted(int target, Duration budget) {
        var t0 = System.nanoTime();
        var reached = new long[]{-1L};
        var lastLog = new long[]{0L};
        await().pollInterval(POLL)
               .pollDelay(Duration.ZERO)
               .timeout(budget.plusSeconds(5))
               .until(() -> countedTick(target, t0, budget, reached, lastLog));
        return reached[0];
    }

    private boolean countedTick(int target, long t0, Duration budget, long[] reached, long[] lastLog) {
        var elapsed = (System.nanoTime() - t0) / 1_000_000L;
        if (reached[0] < 0 && countedCores() == target) {
            reached[0] = elapsed;
        }
        maybeLog(target, elapsed, lastLog);
        return reached[0] >= 0 || elapsed >= budget.toMillis();
    }

    private void maybeLog(int target, long elapsedMs, long[] lastLog) {
        if (elapsedMs - lastLog[0] < LOG_EVERY.toMillis()) {
            return;
        }
        lastLog[0] = elapsedMs;
        log.info("CHURN-PROBE: t+{}ms target={} countedCores={} emberNodeCount={} leaderPresent={}",
                 elapsedMs, target, countedCores(), cluster.nodeCount(), cluster.currentLeader().isPresent());
    }

    // ----- in-process membership reads -----

    private int countedCores() {
        return leaderOrAnyNode().map(node -> node.membershipFsm().coreCountedMembers().size()).or(0);
    }

    private Option<AetherNode> leaderOrAnyNode() {
        return cluster.currentLeader()
                      .flatMap(cluster::getNode)
                      .orElse(() -> Option.from(cluster.allNodes().stream().findFirst()));
    }

    private int leaderPort() {
        return cluster.getLeaderManagementPort().or(cluster.status().nodes().getFirst().mgmtPort());
    }

    // ----- HTTP helpers (scale trigger + slice deploy/resolve) -----

    @TerminalOperation
    private String postScale(int port, int coreCount, int expectedVersion) {
        var body = "{\"coreCount\":" + coreCount + ",\"expectedVersion\":" + expectedVersion + "}";
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/v1/cluster/scale"))
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(Duration.ofSeconds(10))
                                 .build();
        return http.sendString(request)
                   .await()
                   .map(ArtifactChurnSurvival5to7to5ProbeTest::renderResponse)
                   .or("scale POST failed (no response)");
    }

    private static String renderResponse(HttpResult result) {
        return "HTTP " + result.statusCode() + " " + result.body();
    }

    private int readConfigVersion(int port) {
        var matcher = CONFIG_VERSION.matcher(httpGet(port, "/api/v1/cluster/config"));
        return matcher.find()
               ? Integer.parseInt(matcher.group(1))
               : 0;
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

    private boolean sliceIsActive(String artifact) {
        return cluster.slicesStatus()
                      .stream()
                      .anyMatch(status -> status.artifact().equals(artifact)
                                          && status.state().equals(SliceState.ACTIVE.name()));
    }

    @TerminalOperation
    private String deploy(int port, String artifact) {
        var blueprint = """
            id = "%s"

            [[slices]]
            artifact = "%s"
            instances = 3
            """.formatted(BLUEPRINT_ID, artifact);
        return http.sendString(postBlueprint(port, blueprint))
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }

    private static HttpRequest postBlueprint(int port, String body) {
        return HttpRequest.newBuilder()
                          .uri(URI.create("http://localhost:" + port + "/api/v1/blueprints"))
                          .header("Content-Type", "application/toml")
                          .POST(HttpRequest.BodyPublishers.ofString(body))
                          .timeout(Duration.ofSeconds(10))
                          .build();
    }

    @TerminalOperation
    private String getSlices(int port) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/v1/slices"))
                                 .GET()
                                 .timeout(Duration.ofSeconds(10))
                                 .build();
        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }

    private boolean allNodesHealthy() {
        return cluster.status().nodes().stream().allMatch(node -> checkNodeHealth(node.mgmtPort()));
    }

    @TerminalOperation
    private boolean checkNodeHealth(int port) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/v1/health"))
                                 .GET()
                                 .timeout(Duration.ofSeconds(5))
                                 .build();
        return http.sendString(request)
                   .await()
                   .map(r -> r.statusCode() == 200 && r.body().contains("\"quorum\":true"))
                   .or(false);
    }

    private static void failStart(Cause cause) {
        throw new AssertionError("Cluster start failed: " + cause.message());
    }
}
