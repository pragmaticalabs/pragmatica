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
import org.pragmatica.lang.Option;
import org.pragmatica.lang.TerminalOperation;
import org.pragmatica.lang.parse.Number;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
/// ## Managed scale trigger: HTTP `POST /api/v1/cluster/scale` (the REAL ClusterConfigKey path)
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
/// and the departure push was never exercised). The artifact-survival check (ACTIVE + `/api/v1/slices`)
/// is the load-bearing post-condition on top of the confirmed churn. (The optional "a seeded
/// chunk's holder set changed" assertion is NOT added — per-key DHT ring holders are not exposed on
/// the Ember/Forge surface without deep plumbing; the asserted counted-core 5→7→5 convergence is the
/// sufficient anti-vacuous guard.)
///
/// Both the count and the POST target are the LEADER — the node whose own `isLeader()` holds
/// (`EmberCluster.currentLeader()`), never an arbitrary map entry. After `addNode()` the first entry is
/// a newborn: it reports the configured 7 from its seed (`MembershipFsm.seed`) before joining, and its
/// management port has no leader view, so a POST routed there answers 503 `No leader elected` (#1070
/// review B1 — the down-leg "product defect" of the first fix round was exactly that harness read).
/// A 7 is therefore accepted only from a node that was already a member at 5.
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
    private static final Pattern NEW_COUNT = Pattern.compile("\"newCount\"\\s*:\\s*(\\d+)");
    /// Read while no running node claims leadership. Never equals a target, so a leaderless tick is
    /// "not converged" rather than a fabricated count.
    private static final int NO_LEADER_COUNT = -1;

    private EmberCluster cluster;
    /// The ids of the nodes that formed the 5-core cluster — the only nodes a count may be taken from.
    private Set<String> formedNodeIds = Set.of();
    private final HttpOperations http = jdkHttpOperations();

    @BeforeAll
    @TerminalOperation
    void setUp() {
        cluster = emberCluster(INITIAL_CORES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "churn");
        LifecycleAwait.settled("cluster start in setUp()", cluster, cluster.start());

        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(() -> cluster.currentLeader().isPresent());
        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(this::allNodesHealthy);
        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(() -> countedCores() == INITIAL_CORES);
        formedNodeIds = cluster.allNodes().stream().map(node -> node.self().id()).collect(Collectors.toSet());
        log.info("CHURN-PROBE: {}-core cluster formed, leader={}, countedCores={}, formedNodes={}",
                 INITIAL_CORES, cluster.currentLeader().or("none"), countedCores(), formedNodeIds);
    }

    @AfterAll
    @TerminalOperation
    void tearDown() {
        Option.option(cluster).onPresent(c -> LifecycleAwait.bestEffort("cluster stop in tearDown()", c, c.stop()));
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
            .as("GUARD: managed 5->7 via /api/v1/cluster/scale must physically reach %d counted cores "
                + "within %ds (latch=-1 => no physical churn, the trigger no-ops and the departure "
                + "push is never exercised — vacuous survival is thereby impossible).",
                TARGET_CORES, SCALE_TIMEOUT.toSeconds())
            .isGreaterThanOrEqualTo(0L);

        var downLatency = managedScale(INITIAL_CORES);
        assertThat(downLatency)
            .as("GUARD: managed 7->5 via /api/v1/cluster/scale must settle back to %d counted cores "
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

    /// Commits the target core count through `POST /api/v1/cluster/scale` and blocks until the
    /// in-process counted-core denominator equals it (or the budget expires). Returns the
    /// convergence latency in ms, or -1 if the target was never observed within the budget.
    @TerminalOperation
    private long managedScale(int targetCores) {
        var port = leaderPort();
        var version = readConfigVersion(port);
        var response = postScale(port, targetCores, version);
        log.info("CHURN-PROBE: POST /api/v1/cluster/scale {{role:core, count:{}, expectedVersion:{}}} -> {}",
                 targetCores, version, response);
        var latency = awaitCounted(targetCores, SCALE_TIMEOUT);
        log.info("CHURN-PROBE RESULT: target={} reachedAtMs={} (-1=NOT within {}s) countedOn={} countedCores={}",
                 targetCores, latency, SCALE_TIMEOUT.toSeconds(),
                 cluster.currentLeader().or("none"), countedCores());
        return latency;
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
        var leader = leaderNode();
        if (reached[0] < 0 && countedCoresOn(leader) == target) {
            requireCountedOnFormedNode(leader, target);
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

    /// The counted-core denominator the reconciler itself uses, read off the leader's `MembershipFsm`;
    /// [#NO_LEADER_COUNT] while no running node claims leadership.
    private int countedCores() {
        return countedCoresOn(leaderNode());
    }

    private static int countedCoresOn(Option<AetherNode> node) {
        return node.map(n -> n.membershipFsm().coreCountedMembers().size()).or(NO_LEADER_COUNT);
    }

    /// The node whose own `isLeader()` holds — `EmberCluster.currentLeader()` resolves it by that
    /// self-claim. Deliberately NO fallback to an arbitrary node (see the class doc).
    private Option<AetherNode> leaderNode() {
        return cluster.currentLeader().flatMap(cluster::getNode);
    }

    /// A count is accepted only from a node that was already a member at 5. On the up-leg a node
    /// created by the scale reports the configured 7 from its seed before it has joined, which would
    /// pass the guard before any churn happened; on the down-leg leadership moving to a churn-created
    /// node would mean the fixture no longer reads the cluster it formed. Either is a scenario failure.
    private void requireCountedOnFormedNode(Option<AetherNode> counted, int target) {
        var countedOn = counted.map(node -> node.self().id()).or("none");
        if (!formedNodeIds.contains(countedOn)) {
            failScenario("counted " + target + " cores on " + countedOn + ", which is not one of the nodes that "
                         + "formed the " + INITIAL_CORES + "-core cluster " + formedNodeIds
                         + " — a node created by the churn reports its seeded configuration, not observed "
                         + "membership, so this read measures nothing; at this instant " + claimingLeaderSummary());
        }
    }

    /// What the node whose own `isLeader()` holds counts right now — the reading the probe should have taken.
    private String claimingLeaderSummary() {
        var claimant = cluster.allNodes().stream().filter(AetherNode::isLeader).findFirst();
        return claimant.map(leader -> "the node claiming leadership (" + leader.self().id() + ") counts "
                                      + leader.membershipFsm().coreCountedMembers().size())
                       .orElse("no running node claims leadership");
    }

    /// The leader's management port — the only valid target for a leader-bound route. No fallback to
    /// the first status entry: after `addNode()` that is the newborn, whose forwarder has no leader
    /// view and answers 503 `No leader elected`. No leader is a scenario failure, not a retry.
    private int leaderPort() {
        return cluster.getLeaderManagementPort()
                      .onEmpty(() -> failScenario("no running node claims leadership, so there is no management "
                                                  + "port to address"))
                      .or(-1);
    }

    private static void failScenario(String detail) {
        throw new AssertionError("Scenario failed: " + detail);
    }

    // ----- HTTP helpers (scale trigger + slice deploy/resolve) -----

    /// Posts the current `ManagementApiResponses.ScaleRequest` shape — `source` / `role` / `count` /
    /// `expectedVersion` — as `PostRestartSlowRejoinDeficitFillProbeTest` does. A blank `source` asks
    /// the server to infer it, which succeeds because the Ember cluster declares exactly one source
    /// carrying `core`.
    ///
    /// #1069: this body was the pre-#581 `{coreCount, expectedVersion}`. The server refused it with
    /// HTTP 400 `Type mismatch: expected int, got unknown`, the response was only logged, and the
    /// up-leg guard then waited out its whole budget and failed as "no physical churn".
    @TerminalOperation
    private String postScale(int port, int count, int expectedVersion) {
        if (expectedVersion < 1) {
            failScaleTrigger("was not sent: fencing version " + expectedVersion + " is unreadable or the CAS-bypass "
                             + "sentinel, and an unfenced scale could land over another writer");
        }
        var body = "{\"source\":\"\",\"role\":\"core\",\"count\":" + count
                   + ",\"expectedVersion\":" + expectedVersion + "}";
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/v1/cluster/scale"))
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(Duration.ofSeconds(10))
                                 .build();
        return http.sendString(request)
                   .await()
                   .onFailure(cause -> failScaleTrigger("got no response: " + cause.message()))
                   .map(result -> requireScaleAccepted(result, count, expectedVersion))
                   .or("scale POST failed (no response)");
    }

    /// A scale that did not land fails HERE, with the server's status and message, before any
    /// membership wait. Accepted means a 2xx that reports the requested count and the config version
    /// one past the fencing version the request carried (`ClusterConfigValue.withDesiredCount`).
    private static String requireScaleAccepted(HttpResult<String> result, int count, int expectedVersion) {
        if (result.statusCode() / 100 != 2) {
            failScaleTrigger("returned HTTP " + result.statusCode() + " " + result.body());
        }
        var newCount = jsonNumber(NEW_COUNT, result.body());
        var configVersion = jsonNumber(CONFIG_VERSION, result.body());
        if (newCount != count || configVersion != expectedVersion + 1L) {
            failScaleTrigger("was accepted but reported newCount=" + newCount + " configVersion=" + configVersion
                             + " (expected newCount=" + count + " configVersion=" + (expectedVersion + 1L)
                             + "; a different version means another writer committed between the read and the scale): "
                             + renderResponse(result));
        }
        return renderResponse(result);
    }

    private static void failScaleTrigger(String detail) {
        throw new AssertionError("SCALE TRIGGER DID NOT LAND: POST /api/v1/cluster/scale " + detail
                                 + " — nothing below this point would be measuring a scale, so the probe stops "
                                 + "here instead of reporting a convergence failure.");
    }

    private static long jsonNumber(Pattern field, String body) {
        var matcher = field.matcher(body);
        return matcher.find()
               ? Number.parseLong(matcher.group(1)).or(-1L)
               : -1L;
    }

    private static String renderResponse(HttpResult result) {
        return "HTTP " + result.statusCode() + " " + result.body();
    }

    /// -1 when the config could not be read: `httpGet` answers `{}` on a failed GET, and 0 is the
    /// server's CAS-bypass sentinel (`checkVersionAsync`: `expectedVersion != 0 && …`), so an
    /// unreadable version must never be sent as one (#1070 review NIT-1).
    private int readConfigVersion(int port) {
        var matcher = CONFIG_VERSION.matcher(httpGet(port, "/api/v1/cluster/config"));
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

}
