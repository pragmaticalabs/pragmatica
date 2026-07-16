// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.CommunityKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.TerminalOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// Integration proof for the #241 community-formation loop — does N (≥ RF=3) worker joins to one
/// source drive that source's committed [`CommunityKey`] to `ACTIVE`, IN-JVM, with the Ember
/// single-process provider (no DNS, no advertise-host, no container boot)?
///
/// ## The loop under test (worker-membership-spec §3.3 / §3.4 / §4.1)
/// ```
/// worker joins (core count already at the configured cap)
///   → leader assignNodeRole → assignWorkerRole: mint FORMING community `default-w-0`,
///     commit a WORKER ActivationDirective for the joiner          [slice 1]
///   → worker reads committed communityId from its directive → activateWorkerMode;
///     self-elects governor + announces alive community members via SWIM observation
///     (through the ForwardingClusterNode — worker is observation-only)             [slice 3]
///   → leader FSM evaluateCommunityStates: liveMembers (= governor announcement
///     memberCount) ≥ RF(3) ⇒ FORMING → ACTIVE                       [slice 2]
/// ```
///
/// ## How workers are produced (no special "worker join" primitive needed)
/// Role is leader-decided by core-count, not declared by the joiner: `assignNodeRole` promotes a
/// joiner to CORE while `currentCoreCount < effectiveCoreMax`, and assigns WORKER once core count is
/// at the cap. `effectiveCoreMax` reads the committed `ClusterConfig.coreCount`, which the leader's
/// `BootstrapModule` auto-seeds from the topology baseline (`clusterSize=3`) shortly after election.
/// So we (1) form a 3-core cluster, (2) wait for the auto-seeded `ClusterConfig.coreCount=3` to
/// commit (read in-process off the leader KV — no HTTP, the committed value is what role-assignment
/// reads), then (3) `addNode()` three more times — each exceeds the cap and is minted as a worker of
/// the `default` source (no SWIM source label in-JVM ⇒ `resolveSource` falls back to
/// `DEFAULT_SOURCE = "default"`, communityId `default-w-0`).
///
/// Workers are added ONE AT A TIME with a between-adds stability gate (core count must stay at 3 and
/// the new node must survive): adding several nodes at once churns SWIM membership, the original
/// non-leader cores can be evicted, and the reconciler then backfills the deficit by promoting the
/// would-be workers to cores — which would defeat the proof.
///
/// ## What we observe
/// The committed community state is read IN-PROCESS off the leader's KV store (the same committed
/// projection the leader FSM writes) — `CommunityKey("default-w-0") → CommunityValue.state()`. On
/// stall we dump the community value, the governor announcement (`governorId` / `memberCount` — the
/// exact `liveMembers` the FSM reads), the committed core cap, the leader's counted-core set, and
/// every Ember node's role so we can tell whether the workers (a) were never assigned WORKER /
/// were promoted to core under churn, (b) were assigned but no governor announcement landed, or
/// (c) were announced but the FSM never flipped the state.
@Disabled("#336: invalid as an in-JVM gate — 8 nodes on 8 cores starves SWIM probe-acks, so nodes "
          + "genuinely reach FAULTY (LHM sawtooth = late acks, not absent), not a product bug. The "
          + "reachability-evidence fix is validated on real infra (02-chaos kill suite). See "
          + "aether/docs/internal/progress/336-reachability-evidence-fix-2026-06-30.md. Re-enable only "
          + "with relaxed in-JVM SWIM timeouts for single-JVM density.")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommunityFormationProbeTest {
    private static final Logger log = LoggerFactory.getLogger(CommunityFormationProbeTest.class);

    /// 5 cores (quorum 3) rather than 3 (quorum 2): a single worker-add flaps SWIM, and a 3-node
    /// quorum cannot survive two suspected members, so the leader is lost and the join fails. A
    /// 5-node quorum tolerates the churn — the same size `ScaleUpFiveToSevenProbeTest` forms stably.
    private static final int INITIAL_CORES = 5;
    /// RF=3 is the community viability floor (FORMING → ACTIVE requires liveMembers ≥ 3).
    private static final int WORKER_COUNT = 3;
    /// Deterministic single community for the default source: `<source>-w-0`.
    private static final String COMMUNITY_ID = "default-w-0";

    private static final int BASE_PORT = 5960;
    private static final int BASE_MGMT_PORT = 6060;
    private static final int BASE_APP_HTTP_PORT = 6160;

    private static final Duration FORM_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration SETTLE_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration ACTIVE_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration POLL = Duration.ofMillis(500);
    private static final Duration LOG_EVERY = Duration.ofSeconds(5);

    private EmberCluster cluster;

    @BeforeAll
    @TerminalOperation
    void setUp() {
        cluster = emberCluster(INITIAL_CORES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "cfm");
        cluster.start()
               .await()
               .onFailure(CommunityFormationProbeTest::failStart);

        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(() -> cluster.currentLeader().isPresent());
        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(() -> countedCores() == INITIAL_CORES);
        log.info("CFM-PROBE: {}-core cluster formed, leader={}, countedCores={}",
                 INITIAL_CORES, cluster.currentLeader().or("none"), countedCores());
    }

    @AfterAll
    @TerminalOperation
    void tearDown() {
        Option.option(cluster).onPresent(c -> c.stop().await());
    }

    @Test
    @TerminalOperation
    void workersJoinOneSource_communityReachesActive_viaLeaderFsmOnLiveMemberCount() {
        awaitCoreCapCommitted();
        IntStream.rangeClosed(1, WORKER_COUNT).forEach(this::addWorkerAndSettle);
        log.info("CFM-PROBE: {} workers added; ember.nodeCount={} countedCores={} (committed cap={})",
                 WORKER_COUNT, cluster.nodeCount(), countedCores(), committedCoreCount().or(-1));

        var t0 = System.nanoTime();
        var reachedActiveMs = observeUntilActive(t0);
        var finalState = communityState().map(Enum::name).or("ABSENT");

        log.info("CFM-PROBE RESULT: community={} reachedActiveAtMs={} (-1=NOT within {}s) finalState={}",
                 COMMUNITY_ID, reachedActiveMs, ACTIVE_TIMEOUT.toSeconds(), finalState);
        dumpDiagnostics();

        assertThat(reachedActiveMs)
            .as("EXPECTED: %d workers joining the 'default' source drive committed community '%s' to "
                + "ACTIVE within %ds (leader FSM flips FORMING→ACTIVE when governor-announced "
                + "liveMembers ≥ RF=%d). reachedActiveAtMs=%d, finalState=%s. -1 = STALL — the loop is "
                + "wired but did not complete IN-JVM. See the diagnostic dump above for whether the "
                + "workers were never assigned WORKER (promoted to core under churn), were assigned "
                + "but no governor announcement landed, or were announced but the FSM never flipped.",
                WORKER_COUNT, COMMUNITY_ID, ACTIVE_TIMEOUT.toSeconds(), WORKER_COUNT,
                reachedActiveMs, finalState)
            .isGreaterThanOrEqualTo(0L);
    }

    /// Wait for the leader's `BootstrapModule` to commit the auto-seeded `ClusterConfig.coreCount`
    /// (= topology `clusterSize` = 3). Until this lands, `effectiveCoreMax` falls back to the
    /// unlimited topology `coreMax`, and joiners would be promoted to core instead of worker.
    private void awaitCoreCapCommitted() {
        await().atMost(FORM_TIMEOUT)
               .pollInterval(POLL)
               .until(() -> committedCoreCount().filter(count -> count == INITIAL_CORES).isPresent());
        log.info("CFM-PROBE: committed core cap = {} (joiners beyond this are assigned WORKER)",
                 committedCoreCount().or(-1));
    }

    /// Add one worker and wait for the cluster to quiesce before adding the next: the core count
    /// must stay at the cap (the original cores were not evicted and the new node was not promoted)
    /// and the new node must be present in the mesh. Best-effort — if the settle window elapses we
    /// log the unstable state and proceed, so the final assertion + diagnostic dump still run.
    @TerminalOperation
    private void addWorkerAndSettle(int index) {
        var expectedNodeCount = INITIAL_CORES + index;
        cluster.addNode()
               .await()
               .onSuccess(nodeId -> log.info("CFM-PROBE: worker {}/{} joined as {}",
                                             index, WORKER_COUNT, nodeId.id()))
               .onFailure(CommunityFormationProbeTest::failScenario);

        var settled = pollUntil(SETTLE_TIMEOUT,
                                () -> cluster.currentLeader().isPresent()
                                      && countedCores() == INITIAL_CORES
                                      && cluster.nodeCount() == expectedNodeCount);
        log.info("CFM-PROBE: after worker {}/{} settled={} leader={} countedCores={} (cap={}) nodeCount={}/{}",
                 index, WORKER_COUNT, settled, cluster.currentLeader().or("none"),
                 countedCores(), INITIAL_CORES, cluster.nodeCount(), expectedNodeCount);
    }

    /// Polls the committed community state until it reaches ACTIVE or the budget expires. Returns the
    /// first-observed convergence latency in ms, or -1 if ACTIVE was never observed.
    private long observeUntilActive(long t0) {
        var latch = new long[]{-1L};
        var lastLog = new long[]{0L};
        await().pollInterval(POLL)
               .pollDelay(Duration.ZERO)
               .timeout(ACTIVE_TIMEOUT.plusSeconds(5))
               .until(() -> recordTick(t0, latch, lastLog));
        return latch[0];
    }

    private boolean recordTick(long t0, long[] latch, long[] lastLog) {
        var elapsed = (System.nanoTime() - t0) / 1_000_000L;
        if (communityState().filter(CommunityState.ACTIVE::equals).isPresent() && latch[0] < 0) {
            latch[0] = elapsed;
        }
        maybeLog(elapsed, lastLog);
        return latch[0] >= 0 || elapsed >= ACTIVE_TIMEOUT.toMillis();
    }

    private void maybeLog(long elapsedMs, long[] lastLog) {
        if (elapsedMs - lastLog[0] < LOG_EVERY.toMillis()) {
            return;
        }
        lastLog[0] = elapsedMs;
        log.info("CFM-PROBE: t+{}ms community={} state={} governor={} announcedMembers={} "
                 + "countedCores={} emberNodeCount={}",
                 elapsedMs,
                 COMMUNITY_ID,
                 communityState().map(Enum::name).or("ABSENT"),
                 governorAnnouncement().map(a -> a.governorId().id()).or("none"),
                 governorAnnouncement().map(GovernorAnnouncementValue::memberCount).or(0),
                 countedCores(),
                 cluster.nodeCount());
    }

    // ----- in-process committed-state reads (off the leader KV store) -----

    private Option<Integer> committedCoreCount() {
        return leaderOrAnyNode().flatMap(node -> node.kvStore().get(ClusterConfigKey.CURRENT))
                                .filter(ClusterConfigValue.class::isInstance)
                                .map(ClusterConfigValue.class::cast)
                                .map(ClusterConfigValue::coreCount);
    }

    private Option<CommunityValue> communityValue() {
        return leaderOrAnyNode().flatMap(node -> node.kvStore().get(CommunityKey.communityKey(COMMUNITY_ID)))
                                .filter(CommunityValue.class::isInstance)
                                .map(CommunityValue.class::cast);
    }

    private Option<CommunityState> communityState() {
        return communityValue().map(CommunityValue::state);
    }

    private Option<GovernorAnnouncementValue> governorAnnouncement() {
        return leaderOrAnyNode().flatMap(node -> node.kvStore().get(GovernorAnnouncementKey.forCommunity(COMMUNITY_ID)))
                                .filter(GovernorAnnouncementValue.class::isInstance)
                                .map(GovernorAnnouncementValue.class::cast);
    }

    private int countedCores() {
        return leaderOrAnyNode().map(node -> node.membershipFsm().coreCountedMembers().size()).or(0);
    }

    private Option<AetherNode> leaderOrAnyNode() {
        return cluster.currentLeader()
                      .flatMap(cluster::getNode)
                      .orElse(() -> Option.from(cluster.allNodes().stream().findFirst()));
    }

    /// Awaitility wrapper that returns whether the condition held within the timeout instead of
    /// throwing — used for the best-effort between-adds settle gate.
    private static boolean pollUntil(Duration timeout, java.util.concurrent.Callable<Boolean> condition) {
        try {
            await().atMost(timeout).pollInterval(POLL).until(condition);
            return true;
        } catch (org.awaitility.core.ConditionTimeoutException e) {
            return false;
        }
    }

    // ----- failure diagnostics -----

    @TerminalOperation
    private void dumpDiagnostics() {
        var leaderNode = leaderOrAnyNode();
        var countedSet = leaderNode.map(node -> node.membershipFsm().coreCountedMembers()).or(Set.of());

        log.info("CFM-PROBE DUMP: leader={} ember.nodeCount={} countedCores={} committedCap={}",
                 cluster.currentLeader().or("none"), cluster.nodeCount(), countedSet.size(),
                 committedCoreCount().or(-1));
        log.info("CFM-PROBE DUMP: community '{}' value = {}",
                 COMMUNITY_ID, communityValue().map(Object::toString).or("ABSENT"));
        log.info("CFM-PROBE DUMP: governor announcement = {}",
                 governorAnnouncement().map(Object::toString).or("ABSENT"));
        log.info("CFM-PROBE DUMP: leader counted-core set = {}", idStrings(countedSet));

        cluster.allNodes().forEach(node -> dumpNode(node, countedSet));
    }

    private void dumpNode(AetherNode node, Set<NodeId> countedSet) {
        var id = node.self();
        var inMesh = leaderOrAnyNode().map(leader -> leader.membershipView().isPresent(id)).or(false);
        log.info("CFM-PROBE DUMP: node={} leader={} inLeaderSwimView={} countedAsCore={} (else=worker)",
                 id.id(), node.isLeader(), inMesh, countedSet.contains(id));
    }

    private static String idStrings(Set<NodeId> ids) {
        return ids.stream().map(NodeId::id).sorted().collect(Collectors.joining(","));
    }

    private static void failStart(Cause cause) {
        throw new AssertionError("Cluster start failed: " + cause.message());
    }

    private static void failScenario(Cause cause) {
        throw new AssertionError("Worker join failed: " + cause.message());
    }
}
