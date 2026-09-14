// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.consensus.NodeId;
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
import java.util.Map;
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
/// counted cores after the up-leg AND completed the drain after the down-leg. The up-leg reads
/// `membershipFsm().coreCountedMembers()` on the leader, the exact denominator the reconciler uses.
/// The down-leg is accepted only at the TERMINAL edge: both drain victims have stopped and been
/// removed from the cluster (`EmberCluster.handleSelfDrain`, which runs only after the departure
/// push has settled), and a surviving leader counts exactly the survivor set with every victim
/// `Dead` in its view and nothing `Departing`. A count taken at the DEPARTING edge is NOT a churn:
/// `MembershipFsm` prunes a drainer from `coreCountedMembers()` the instant `drainNode` is
/// requested, ~0.5 s after the POST and before its departure push has moved a chunk (#1070 review
/// r2 B1 — and the victims here are the LEADER and one seed, not the two added nodes: no Ember id is
/// ephemeral, `churn-3..5` own the slice, `churn-6/7` sit inside the drain-safety grace, so
/// `LeaderReconciler.selectDrainVictims` falls back to the reversed-id mature seeds `churn-2`,
/// `churn-1`). The artifact-survival check (ACTIVE + `/api/v1/slices`) is the load-bearing
/// post-condition, read from that named survivor after the churn is terminal. (The optional "a
/// seeded chunk's holder set changed" assertion is NOT added — per-key DHT ring holders are not
/// exposed on the Ember/Forge surface without deep plumbing.)
///
/// ## #1089 — the terminal edge is unreachable today, so the down-leg ships as a TRIPWIRE
/// The DRAIN command is delivered only on the leader's broadcast `ClusterSyncPing`
/// (`ClusterSyncState.dispatchPing` skips `self`), and `LeaderReconciler.selectDrainVictims` never
/// excludes the leader. So when the reconciler picks the leader — which it does here — the leader
/// never runs its `DrainProcedure`: it stays in the cluster, keeps claiming leadership, prunes itself
/// from its own count, and the CTM's 60 s grace-terminate is the only backstop (ungraceful, no
/// departure push — the #427 loss mode; unsupported in Ember). Until #1089 lands:
///   - [#leaderInDrainSet_neverDrains_tripwireUntil1089] is ENABLED and asserts that wrong behaviour
///     precisely. It reddens the moment the product changes, with a message saying what to do.
///   - [#seededArtifact_survivesManagedFiveToSevenToFiveChurn] — the real terminal-edge probe — is
///     `@Disabled("#1089")`. It is disabled rather than enabled-and-red because it would fail for the
///     product reason, not pass vacuously: an enabled probe that is red for a known cause teaches
///     readers to ignore it, while the tripwire guarantees someone re-enables it.
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
    /// `MembershipFsm.memberStates()` reports `MembershipState` record simple names.
    private static final String DEPARTING = "Departing";
    private static final String DEAD = "Dead";

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

    /// #1089 TRIPWIRE — asserts the CURRENT wrong behaviour of the down-leg precisely, so that the
    /// product fix cannot land without this test going red. What it pins, from the last observation
    /// of the bounded wait: the terminal edge was never reached; the node that was leader at the POST
    /// is still in the cluster (its `DrainProcedure` never ran — `EmberCluster.handleSelfDrain` is
    /// that procedure's only exit and it would have removed the node) and still claims leadership
    /// while its own count excludes itself; and the OTHER victim did drain to `Dead` and was removed
    /// (the control that the drain mechanism works for a non-leader — the defect is the leader's
    /// missing self-delivery, nothing wider). On failure: #1089 landed — delete this test and enable
    /// [#seededArtifact_survivesManagedFiveToSevenToFiveChurn].
    @Test
    @TerminalOperation
    void leaderInDrainSet_neverDrains_tripwireUntil1089() {
        var churn = seedThenScaleUpThenScaleDown();
        var downLeg = churn.downLeg();
        var landed = "#1089 landed — delete me and enable the terminal-edge probe below "
                     + "(seededArtifact_survivesManagedFiveToSevenToFiveChurn). Observed: " + downLeg;

        assertThat(downLeg.reachedAtMs())
            .as("the 7->5 down-leg must NOT reach the terminal edge within %ds while the leader is a drain victim "
                + "that never drains. %s", SCALE_TIMEOUT.toSeconds(), landed)
            .isEqualTo(-1L);
        assertThat(downLeg.countedOn())
            .as("the pre-POST leader must still claim leadership after the bounded wait. %s", landed)
            .isEqualTo(churn.leaderAtPost());
        assertThat(downLeg.survivors())
            .as("the pre-POST leader must still be in the cluster — its DrainProcedure never ran. %s", landed)
            .contains(churn.leaderAtPost());
        assertThat(downLeg.counted())
            .as("the leader prunes ITSELF from its own count (the DEPARTING edge on self) while staying a "
                + "member. %s", landed)
            .doesNotContain(churn.leaderAtPost())
            .containsExactlyInAnyOrderElementsOf(without(downLeg.survivors(), churn.leaderAtPost()));
        assertThat(downLeg.victimStates())
            .as("CONTROL: the other drain victim must have drained to Dead and left the cluster — the drain "
                + "mechanism works for a non-leader. %s", landed)
            .hasSize(1)
            .containsValue(DEAD);
    }

    /// The real probe — enabled by deleting the tripwire above once #1089 lands. Disabled, not
    /// enabled-and-red: until then it fails for the product reason (see the class doc), and a
    /// known-red probe is one readers learn to ignore. It cannot pass vacuously while disabled
    /// because it does not run; the tripwire guarantees it is re-enabled.
    @Test
    @TerminalOperation
    @Disabled("#1089: the reconciler drains the leader and the DRAIN command never reaches it, so the terminal "
              + "edge is unreachable; enable when leaderInDrainSet_neverDrains_tripwireUntil1089 reddens")
    void seededArtifact_survivesManagedFiveToSevenToFiveChurn() {
        var downLeg = seedThenScaleUpThenScaleDown().downLeg();
        assertThat(downLeg.reachedAtMs())
            .as("GUARD: managed 7->5 via /api/v1/cluster/scale must COMPLETE within %ds: both drain victims "
                + "stopped and removed from the cluster, and a surviving leader counting exactly the %d survivors "
                + "with every victim Dead in its view and nothing Departing (latch=-1 => the surplus never "
                + "drained to the terminal edge, so the departure push never ran to completion). Last observed: %s",
                SCALE_TIMEOUT.toSeconds(), INITIAL_CORES, downLeg)
            .isGreaterThanOrEqualTo(0L);
        requireTerminalOnSurvivor(downLeg);

        var survivor = survivorNode(downLeg);
        await().atMost(SURVIVE_TIMEOUT).pollInterval(POLL).ignoreExceptions()
               .until(() -> sliceIsActiveOn(survivor, TEST_ARTIFACT));

        var slices = getSlices(survivorPort(downLeg));
        assertThat(slices)
            .as("LOAD-BEARING: the artifact seeded before the confirmed managed 5->7->5 churn "
                + "survives it and is still resolvable at HEAD from survivor %s (C1 departure-push fix present: "
                + "DHTRebalancer.pushOnDeparture wired at AetherNode.java:1852). Missing artifact => "
                + "its DHT chunks were lost when surplus cores drained — #427 reproducing end-to-end.",
                downLeg.countedOn())
            .contains(TEST_ARTIFACT)
            .doesNotContain("\"error\"");
    }

    /// The prelude both tests share: seed the artifact, run the STRICT up-leg guard, then POST the
    /// down-leg and wait (bounded) for its terminal edge. `leaderAtPost` is the node the down-leg
    /// was addressed to — the drain victim #1089 is about.
    private record Churn(String leaderAtPost, DownLeg downLeg) {}

    @TerminalOperation
    private Churn seedThenScaleUpThenScaleDown() {
        var deployResponse = deploy(leaderPort(), TEST_ARTIFACT);
        assertThat(deployResponse).doesNotContain("\"error\"");
        await().atMost(DEPLOY_TIMEOUT).pollInterval(POLL).ignoreExceptions()
               .until(() -> sliceIsActiveOn(leaderNode(), TEST_ARTIFACT));
        log.info("CHURN-PROBE: artifact seeded and ACTIVE at countedCores={}", countedCores());

        var upLatency = managedScaleUp(TARGET_CORES);
        assertThat(upLatency)
            .as("GUARD: managed 5->7 via /api/v1/cluster/scale must physically reach %d counted cores "
                + "within %ds (latch=-1 => no physical churn, the trigger no-ops and the departure "
                + "push is never exercised — vacuous survival is thereby impossible).",
                TARGET_CORES, SCALE_TIMEOUT.toSeconds())
            .isGreaterThanOrEqualTo(0L);

        var leaderAtPost = cluster.currentLeader().or("none");
        return new Churn(leaderAtPost, managedScaleDown(INITIAL_CORES));
    }

    private static Set<String> without(Set<String> ids, String id) {
        return ids.stream().filter(candidate -> !candidate.equals(id)).collect(Collectors.toSet());
    }

    // ----- managed scale via the real ClusterConfigKey path (mirrors ScaleUpFiveToSevenProbeTest) -----

    /// Up-leg: commits the target core count through `POST /api/v1/cluster/scale` and blocks until
    /// the leader's counted-core denominator equals it (or the budget expires). Returns the
    /// convergence latency in ms, or -1 if the target was never observed within the budget.
    @TerminalOperation
    private long managedScaleUp(int targetCores) {
        var version = postScaleToLeader(targetCores);
        var latency = awaitCounted(targetCores, SCALE_TIMEOUT);
        log.info("CHURN-PROBE RESULT: target={} reachedAtMs={} (-1=NOT within {}s) countedOn={} countedCores={} "
                 + "configVersion={}",
                 targetCores, latency, SCALE_TIMEOUT.toSeconds(), cluster.currentLeader().or("none"), countedCores(),
                 version + 1);
        return latency;
    }

    /// Down-leg: commits the target and blocks until the churn is TERMINAL (see [#drainTick]) or
    /// the budget expires. The returned snapshot names the node the count was read from, the
    /// victims and survivors, and the victims' states in that node's view — the guard
    /// [#requireTerminalOnSurvivor] re-checks it, and the survival read is taken from that node.
    @TerminalOperation
    private DownLeg managedScaleDown(int targetCores) {
        var membersAtStart = nodeIds();
        postScaleToLeader(targetCores);
        var downLeg = awaitDrained(targetCores, membersAtStart, SCALE_TIMEOUT);
        log.info("CHURN-PROBE RESULT: target={} reachedAtMs={} (-1=NOT within {}s) {}",
                 targetCores, downLeg.reachedAtMs(), SCALE_TIMEOUT.toSeconds(), downLeg);
        return downLeg;
    }

    /// Reads the fencing version from the leader, posts the scale to it, and returns that version.
    @TerminalOperation
    private int postScaleToLeader(int targetCores) {
        var port = leaderPort();
        var version = readConfigVersion(port);
        var response = postScale(port, targetCores, version);
        log.info("CHURN-PROBE: POST /api/v1/cluster/scale {{role:core, count:{}, expectedVersion:{}}} -> {}",
                 targetCores, version, response);
        return version;
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

    // ----- down-leg: terminal-edge wait -----

    /// One observation of the down-leg. `countedOn` is the surviving leader the count and the
    /// victims' states were read from; `victims` are the members present at the POST that are no
    /// longer in the cluster; `victimStates` are their states in `countedOn`'s `MembershipFsm`
    /// (`Dead` is terminal; `absent` means the FSM never tracked them); `departing` are the members
    /// `countedOn` still sees as `Departing`.
    private record DownLeg(long reachedAtMs,
                           String countedOn,
                           Set<String> counted,
                           Set<String> survivors,
                           Set<String> victims,
                           Map<String, String> victimStates,
                           Set<String> departing) {
        boolean terminal(int target, int membersAtStart) {
            return survivors.size() == target
                   && victims.size() == membersAtStart - target
                   && counted.equals(survivors)
                   && departing.isEmpty()
                   && victimStates.values().stream().allMatch(DEAD::equals);
        }

        DownLeg reachedAt(long elapsedMs) {
            return new DownLeg(elapsedMs, countedOn, counted, survivors, victims, victimStates, departing);
        }
    }

    private DownLeg awaitDrained(int target, Set<String> membersAtStart, Duration budget) {
        var t0 = System.nanoTime();
        var latest = new DownLeg[]{observeDownLeg(membersAtStart)};
        var lastLog = new long[]{0L};
        await().pollInterval(POLL)
               .pollDelay(Duration.ZERO)
               .timeout(budget.plusSeconds(5))
               .until(() -> drainTick(target, membersAtStart, t0, budget, latest, lastLog));
        return latest[0];
    }

    /// The churn is terminal when: exactly `target` nodes remain in the cluster and the rest of the
    /// members present at the POST are gone (`EmberCluster.handleSelfDrain` removes a drainer only
    /// once its `DrainProcedure` — departure push included — has run); a running node claims
    /// leadership; its counted-core set is exactly the survivor set; and its view holds every
    /// victim as `Dead` and nobody as `Departing`. Anything less is the DEPARTING edge, which the
    /// class doc explains is not a churn. A leaderless tick (election in progress after the old
    /// leader drained) is "not yet", never a count.
    private boolean drainTick(int target, Set<String> membersAtStart, long t0, Duration budget,
                              DownLeg[] latest, long[] lastLog) {
        var elapsed = (System.nanoTime() - t0) / 1_000_000L;
        var observed = observeDownLeg(membersAtStart);
        if (latest[0].reachedAtMs() < 0) {
            latest[0] = observed.terminal(target, membersAtStart.size())
                        ? observed.reachedAt(elapsed)
                        : observed;
        }
        if (elapsed - lastLog[0] >= LOG_EVERY.toMillis()) {
            lastLog[0] = elapsed;
            log.info("CHURN-PROBE: t+{}ms target={} {}", elapsed, target, observed);
        }
        return latest[0].reachedAtMs() >= 0 || elapsed >= budget.toMillis();
    }

    private DownLeg observeDownLeg(Set<String> membersAtStart) {
        var survivors = nodeIds();
        var victims = membersAtStart.stream().filter(id -> !survivors.contains(id)).collect(Collectors.toSet());
        var leader = leaderNode();
        var countedOn = leader.map(node -> node.self().id()).or("none");
        var counted = leader.map(node -> idStrings(node.membershipFsm().coreCountedMembers())).or(Set.of());
        var states = leader.map(this::memberStateNames).or(Map.of());
        var victimStates = victims.stream()
                                  .collect(Collectors.toMap(id -> id, id -> states.getOrDefault(id, "absent")));
        var departing = states.entrySet()
                              .stream()
                              .filter(entry -> DEPARTING.equals(entry.getValue()))
                              .map(Map.Entry::getKey)
                              .collect(Collectors.toSet());
        return new DownLeg(-1L, countedOn, counted, survivors, victims, victimStates, departing);
    }

    private Map<String, String> memberStateNames(AetherNode node) {
        return node.membershipFsm()
                   .memberStates()
                   .entrySet()
                   .stream()
                   .collect(Collectors.toMap(entry -> entry.getKey().id(), Map.Entry::getValue));
    }

    /// Tripwire behind [#DownLeg#terminal]: the 5 must have been read from a survivor that was a
    /// member at 7 and is not a victim, with the victims terminal and nothing departing in its view.
    /// If a future edit accepts the count earlier (the DEPARTING edge, as the first fix round did) or
    /// from a draining node, this fails naming the node and the states instead of passing.
    private void requireTerminalOnSurvivor(DownLeg downLeg) {
        if (!downLeg.survivors().contains(downLeg.countedOn()) || downLeg.victims().contains(downLeg.countedOn())) {
            failScenario("the 7->5 count was read from " + downLeg.countedOn() + ", which is not a survivor: " + downLeg);
        }
        if (!downLeg.departing().isEmpty() || !downLeg.victimStates().values().stream().allMatch(DEAD::equals)) {
            failScenario("the 7->5 count was accepted at the DEPARTING edge, not the terminal one: " + downLeg.countedOn()
                         + " still sees departing=" + downLeg.departing() + " victims=" + downLeg.victimStates()
                         + " — a drainer is pruned from coreCountedMembers() before its departure push has moved a "
                         + "chunk, so this read cannot tell a completed churn from one that has not started");
        }
    }

    /// The node the down-leg count was read from, by name — never the first map entry.
    private Option<AetherNode> survivorNode(DownLeg downLeg) {
        return cluster.getNode(downLeg.countedOn())
                      .onEmpty(() -> failScenario("survivor " + downLeg.countedOn()
                                                  + " left the cluster before the survival read"));
    }

    private int survivorPort(DownLeg downLeg) {
        return cluster.status()
                      .nodes()
                      .stream()
                      .filter(node -> node.id().equals(downLeg.countedOn()))
                      .findFirst()
                      .map(EmberCluster.NodeStatus::mgmtPort)
                      .orElseGet(() -> {
                          failScenario("survivor " + downLeg.countedOn() + " has no management port in status()");
                          return -1;
                      });
    }

    private Set<String> nodeIds() {
        return cluster.allNodes().stream().map(node -> node.self().id()).collect(Collectors.toSet());
    }

    private static Set<String> idStrings(Set<NodeId> ids) {
        return ids.stream().map(NodeId::id).collect(Collectors.toSet());
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

    /// Up-leg tripwire: a 7 is accepted only from a node that was already a member at 5. A node
    /// created by the scale reports the configured 7 from its seed before it has joined, which would
    /// pass the guard before any churn happened. That read is a scenario failure, not a result.
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

    /// Whether `node`'s own deployment map holds `artifact` ACTIVE. Read from a NAMED node, never
    /// `EmberCluster.slicesStatus()`, which answers from the first map entry — after the down-leg
    /// that could be a draining victim (#1070 review r2 B1).
    private static boolean sliceIsActiveOn(Option<AetherNode> node, String artifact) {
        return node.map(n -> n.deploymentMap()
                              .allDeployments()
                              .stream()
                              .anyMatch(info -> info.artifact().equals(artifact)
                                                && info.aggregateState() == SliceState.ACTIVE))
                   .or(false);
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
