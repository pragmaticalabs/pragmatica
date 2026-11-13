// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.aether.worker.isolation.CoreAbsenceSnapshot;
import org.pragmatica.swim.SwimHealth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// #3028 Wire: a worker that the cluster declared dead (its SWIM traffic blackholed past the departure cleanup) ends in one of
/// two paths, and which one runs is decided by config, not by a race: the worker's `coreAbsence` fence against the cores'
/// removal of it (about 14-34 s after the cut, observed).
///
/// - Fence first (`coreAbsence` below the removal time): the worker halts with `EXIT_DRAINED`; production does not restart it
///   (#3125: nothing replaces it without CTM provisioning), so the test emulates the replacement with a fresh-id
///   `addWorkerNode()`.
/// - Removal first (`coreAbsence` above removal plus heal): the cores remove the worker while it is still alive. Removal is
///   terminal (`MembershipFsm.processEvidenceRefusal`: a Dead identity needs a fresh NodeId), so the healed same-id worker is
///   refused and never rejoins; only a fresh-id replacement receives a directive. The refusal is only counted and logged on the
///   cores (`MembershipFsm.recordProcessEvidenceRefusal`); the worker is not told, and stops through its QUORUM_LOSS self-fence.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
class FalseDeadWorkerRejoinTest {
    private static final Logger log = LoggerFactory.getLogger(FalseDeadWorkerRejoinTest.class);
    private static final TimeSpan BUDGET = TimeSpan.timeSpan(180).seconds();
    private static final TimeSpan RECOVERY_BOUND = TimeSpan.timeSpan(120).seconds();
    /// Well below the first removal observed (14 s after the cut).
    private static final TimeSpan FENCE_FIRST = TimeSpan.timeSpan(5).seconds();
    /// Well above removal plus the heal; its `communityAbsence` must stay above it (#590).
    private static final TimeSpan REMOVAL_FIRST = TimeSpan.timeSpan(300).seconds();
    /// Margin after every core reports the worker Dead and before the heal. CTO's guess (10 s), past every green observation; the
    /// heal must not land in the gap before the removal is firm, or the worker's quorum flaps and it never stops (#3138).
    private static final TimeSpan DEAD_MARGIN = TimeSpan.timeSpan(10).seconds();
    private static final TimeSpan REMOVAL_FIRST_COMMUNITY = TimeSpan.timeSpan(360).seconds();
    private final EmberCluster cluster = EmberCluster.emberCluster(3, 33400, 33500, 33600, "false-dead");

    @AfterEach void stop() {
        LifecycleAwait.bestEffort("stop false-dead fixture", cluster, cluster.stop());
    }

    /// Fence path, by design: the worker loses the cores for longer than `coreAbsence`, fences and exits with `EXIT_DRAINED`;
    /// the cores remove it only after. Its replacement is a fresh id (production never relaunches the same id), and the
    /// replacement gets its directive.
    @Test void fencedWorker_exitsDrained_andFreshReplacementGetsDirective() {
        var id = bootAndCutWorker(FENCE_FIRST, defaultCommunityAbsence(), true);

        var cutAt = System.nanoTime();

        awaitDirectiveRemoved(id);
        // The fence moment itself is not externally observable (lifecycle, registry and exit code all lag past the removal), so the
        // ordering is pinned as: the removal arrived only after the whole fence window had elapsed, while the window is the seam value.
        assertThat(Duration.ofNanos(System.nanoTime() - cutAt)).as("the cores removed the worker only after its fence window elapsed")
                                                                .isGreaterThan(FENCE_FIRST.duration());
        await().alias("the fenced worker exited with EXIT_DRAINED")
               .atMost(RECOVERY_BOUND.duration())
               .until(() -> cluster.exitCodeOf(id.id()).map(code -> code == AetherNode.EXIT_DRAINED).or(false));
        healSwim();

        var replacement = LifecycleAwait.nodeSettled("admit replacement worker", cluster, cluster.addWorkerNode());

        assertThat(replacement).isNotEqualTo(id);
        await().alias("every live worker holds a committed directive")
               .atMost(RECOVERY_BOUND.duration())
               .until(() -> liveWorkers().size() == 1 && liveWorkers().stream().allMatch(this::directiveCommitted));
        assertThat(liveWorkers()).extracting(AetherNode::self).containsExactly(replacement);
    }

    /// Removal path: the cores remove the worker (transport cut too, so the removal is prompt) before its fence can fire; after
    /// the heal the same id is refused (terminal removal, counted on the cores) and a fresh-id replacement receives its
    /// directive. The refusal count is logged, not asserted, here: it is read right after the heal, before the worker's retries.
    @Test void removedWorker_freshReplacementGetsDirective() {
        var id = removeWorkerThenHeal();
        var replacement = LifecycleAwait.nodeSettled("admit replacement worker", cluster, cluster.addWorkerNode());

        assertThat(replacement).isNotEqualTo(id);
        await().alias("the replacement worker holds a committed directive")
               .atMost(RECOVERY_BOUND.duration())
               .until(() -> directiveCommitted(cluster.getNode(replacement.id()).unwrap()));
        assertThat(leader().kvStore().getTyped(new AetherKey.ActivationDirectiveKey(id), AetherValue.ActivationDirectiveValue.class).isPresent())
                .as("the removed identity never regains a directive").isFalse();
        log.info("Same-id refusals counted on the cores: {}", coreRefusals());
    }

    /// FSM2 arm, admission only: on the FSM2 engine the `Directory` directive writers (`writeDirective`, `joinMint`) mint a
    /// worker's directive, which the legacy arms above never reach. The removal path is not run on FSM2: there the cores did not
    /// remove the worker's directive within `BUDGET` (observed), so a removal arm would pin nothing here.
    @Test void fsm2Worker_admissionCommitsDirective() {
        cluster.withDeploymentEngine(AetherValue.DeploymentEngine.FSM2);
        var id = bootWorker(REMOVAL_FIRST, REMOVAL_FIRST_COMMUNITY);

        assertThat(directiveCommitted(id)).as("the leader committed the FSM2 worker's directive").isTrue();
    }

    /// The removed worker stops instead of retrying forever, bounded by `RECOVERY_BOUND` after the heal. It is refused by the
    /// cores (terminal removal) but never told, so it does not exit with `EXIT_IDENTITY_REFUSED`: it stops through the QUORUM_LOSS
    /// self-fence (`EXIT_DRAINED`) about 75 s after the cut, because the refusal keeps it out of the cores' quorum. Both codes
    /// mean "replace with a fresh NodeId". If the same id were re-admitted this test times out, which is the signal.
    @Test void removedWorker_stopsAfterRemoval() {
        var id = removeWorkerThenHeal();

        await().alias("the removed worker stopped")
               .atMost(RECOVERY_BOUND.duration())
               .until(() -> cluster.exitCodeOf(id.id()).isPresent());
        assertThat(cluster.exitCodeOf(id.id()).unwrap()).isIn(AetherNode.EXIT_DRAINED, AetherNode.EXIT_IDENTITY_REFUSED);
        assertThat(coreRefusals()).as("the cores refused the removed identity's process evidence").isPositive();
        log.info("Removed worker exited with {}; same-id refusals counted on the cores: {}", cluster.exitCodeOf(id.id()).unwrap(), coreRefusals());
    }

    /// Boots, cuts the worker (transport too), waits for its removal, waits until every core holds it Dead, then heals after a
    /// fixed margin. The settle is load-bearing for the worker's STOP, not only for terminal removal: healing while the
    /// removal is not yet firm re-admits the same id or leaves the quorum flapping HELD<->LOST, and the worker never fences
    /// (#3138). The margin past Dead is the CTO's guess (10 s), past every green observation.
    private NodeId removeWorkerThenHeal() {
        var id = bootAndCutWorker(REMOVAL_FIRST, REMOVAL_FIRST_COMMUNITY, false);

        awaitDirectiveRemoved(id);
        assertThat(cluster.exitCodeOf(id.id())).as("the worker was removed before its fence could fire").isEqualTo(Option.none());
        await().alias("every core holds the removed worker Dead")
               .atMost(BUDGET.duration())
               .until(() -> cores(id).stream().allMatch(node -> isDead(node, id)));
        await().pollDelay(DEAD_MARGIN.duration()).atMost(DEAD_MARGIN.duration().plusSeconds(5)).until(() -> true);
        healSwim();
        return id;
    }

    private List<AetherNode> cores(NodeId worker) {
        return cluster.allNodes().stream().filter(node -> !node.self().equals(worker)).toList();
    }

    private static boolean isDead(AetherNode node, NodeId peer) {
        return "Dead".equals(node.membershipFsm().memberStates().get(peer));
    }

    private void awaitDirectiveRemoved(NodeId id) {
        await().alias("the cores removed the worker's directive")
               .atMost(BUDGET.duration())
               .until(() -> !directiveCommitted(id));
    }

    private boolean directiveCommitted(NodeId id) {
        return leader().kvStore().getTyped(new AetherKey.ActivationDirectiveKey(id), AetherValue.ActivationDirectiveValue.class).isPresent();
    }

    private long coreRefusals() {
        var cores = leader().coreNodeIds();

        return cluster.allNodes().stream()
                      .filter(node -> cores.contains(node.self()))
                      .mapToLong(node -> node.transportMetrics().getOrDefault("membership_process_evidence_refusals_total", 0L).longValue())
                      .sum();
    }

    /// The directive of `node` as committed on the leader (the cores' view), not the node's own projection.
    private boolean directiveCommitted(AetherNode node) {
        return directiveCommitted(node.self());
    }

    private List<AetherNode> liveWorkers() {
        var cores = leader().coreNodeIds();

        return cluster.allNodes().stream().filter(node -> !cores.contains(node.self())).toList();
    }

    private TimeSpan defaultCommunityAbsence() {
        return TimeSpan.timeSpan(20).seconds();
    }

    private NodeId bootAndCutWorker(TimeSpan coreAbsence, TimeSpan communityAbsence, boolean keepQuic) {
        var id = bootWorker(coreAbsence, communityAbsence);

        cutSwim(id, keepQuic);
        return id;
    }

    private NodeId bootWorker(TimeSpan coreAbsence, TimeSpan communityAbsence) {
        LifecycleAwait.settled("start false-dead fixture", cluster, cluster.start());
        await().atMost(BUDGET.duration()).until(() -> cluster.currentLeader().isPresent());
        // Set after the cores started: only the worker created next gets the fence window under test.
        cluster.withCoreAbsenceForNewNodes(coreAbsence);
        cluster.withCommunityAbsenceForNewNodes(communityAbsence);
        var id = LifecycleAwait.nodeSettled("admit false-dead worker", cluster, cluster.addWorkerNode());
        var worker = cluster.getNode(id.id()).unwrap();

        assertThat(worker.coreAbsenceSnapshot().map(CoreAbsenceSnapshot::thresholdMs))
                .as("the worker's effective core-absence threshold is the seam value")
                .isEqualTo(Option.some(coreAbsence.duration().toMillis()));
        var key = new AetherKey.ActivationDirectiveKey(id);

        await().atMost(BUDGET.duration()).until(() -> worker.kvStore().getTyped(key, AetherValue.ActivationDirectiveValue.class).isPresent());
        await().alias("every core has observed the worker SWIM-HEALTHY before the cut (the cold-boot gate suppresses a never-HEALTHY death)")
               .atMost(BUDGET.duration())
               .until(() -> cluster.allNodes().stream().filter(node -> !node.self().equals(id)).allMatch(node -> swimHealthy(node, id)));
        return id;
    }

    /// Cuts SWIM between the worker and every core, in both directions. With `keepQuic` the QUIC inbound filter that
    /// `partitionFrom` also installs is lifted again, so the worker is declared dead by SWIM alone and its live QUIC links
    /// contradict a second-hand FAULTY (removal then takes 14-90 s, observed). Without it the transport is cut too and the
    /// removal is prompt, which the removal-first path needs: the worker's quorum-loss self-fence fires about 75 s after the cut.
    private void cutSwim(NodeId worker, boolean keepQuic) {
        var cores = cluster.allNodes().stream().map(AetherNode::self).filter(node -> !node.equals(worker)).collect(Collectors.toSet());

        cluster.allNodes().forEach(node -> {
            node.partitionFrom(node.self().equals(worker) ? cores : Set.of(worker));
            if (keepQuic) {
                node.setInboundFaultFilter((_, _) -> true);
            }
        });
    }

    private void healSwim() {
        cluster.allNodes().forEach(node -> node.partitionFrom(Set.of()));
    }

    private static boolean swimHealthy(AetherNode node, NodeId peer) {
        return node.membershipView().get(peer).map(view -> view.swimHealth() == SwimHealth.HEALTHY).or(false);
    }

    private AetherNode leader() {
        return cluster.currentLeader().flatMap(cluster::getNode).unwrap();
    }
}
