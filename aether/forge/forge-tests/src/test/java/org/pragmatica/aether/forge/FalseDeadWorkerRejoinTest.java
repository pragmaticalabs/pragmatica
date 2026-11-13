// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
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
import org.pragmatica.swim.SwimHealth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
///   cores (`MembershipFsm.recordProcessEvidenceRefusal`); no `IdentityRefused` / `HelloRefused` reaches the worker, so it is
///   never told to exit with `EXIT_IDENTITY_REFUSED` (see the tripwire below).
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
    /// Wait between the directive's removal and the heal so the removal is terminal (Dead) before the worker returns.
    private static final TimeSpan REMOVAL_SETTLE = TimeSpan.timeSpan(30).seconds();
    private static final TimeSpan TRIPWIRE_WINDOW = TimeSpan.timeSpan(45).seconds();
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

        await().alias("the worker fenced and exited with EXIT_DRAINED before the cores removed it")
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
    /// directive. The refusal count is logged, not asserted: it depends on the removed worker's retry cadence.
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

    /// TRIPWIRE for the product defect behind the disabled test below: today the removed worker keeps retrying and is never
    /// told its identity is refused (cores only log it), so it does not stop. Fails when the worker starts stopping on its own:
    /// then enable `removedWorker_stopsAfterRemoval` and delete this test.
    @Test void removedWorker_currentlyKeepsRetrying_tripwire() {
        var id = removeWorkerThenHeal();

        await().alias("the cores refused the removed identity (positive control: the refusal path was reached)")
               .atMost(RECOVERY_BOUND.duration())
               .until(() -> coreRefusals() > 0);
        await().during(TRIPWIRE_WINDOW.duration())
               .atMost(TRIPWIRE_WINDOW.duration().plusSeconds(30))
               .alias("the removed worker now stops on its own: enable removedWorker_stopsAfterRemoval and delete this tripwire")
               .until(() -> cluster.exitCodeOf(id.id()).isEmpty());
    }

    /// The intended behaviour: a removed worker stops (any exit, expected `EXIT_IDENTITY_REFUSED`) within `RECOVERY_BOUND` of
    /// the heal instead of retrying forever. Disabled until the cores tell the worker its identity is refused.
    @Disabled("product defect: the cores refuse a removed worker's identity without telling it, so it retries forever; enable when fixed and delete the tripwire")
    @Test void removedWorker_stopsAfterRemoval() {
        var id = removeWorkerThenHeal();

        await().alias("the removed worker stopped")
               .atMost(RECOVERY_BOUND.duration())
               .until(() -> cluster.exitCodeOf(id.id()).isPresent());
        assertThat(cluster.exitCodeOf(id.id())).isEqualTo(Option.some(AetherNode.EXIT_IDENTITY_REFUSED));
    }

    /// Boots, cuts the worker (transport too), waits for its removal, lets the removal finalize, then heals.
    private NodeId removeWorkerThenHeal() {
        var id = bootAndCutWorker(REMOVAL_FIRST, REMOVAL_FIRST_COMMUNITY, false);

        assertThat(cluster.exitCodeOf(id.id())).as("the worker was removed before its fence could fire").isEqualTo(Option.none());
        // The directive disappears before the identity is terminally Dead; healing in that gap lets the same id back in (rc5
        // runs 4 and 5 of the former rejoin test). The settle is a marked guess, observed first refusal about 37 s after the cut.
        await().pollDelay(REMOVAL_SETTLE.duration()).atMost(REMOVAL_SETTLE.duration().plusSeconds(5)).until(() -> true);
        healSwim();
        return id;
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
        return leader().kvStore().getTyped(new AetherKey.ActivationDirectiveKey(node.self()), AetherValue.ActivationDirectiveValue.class).isPresent();
    }

    private List<AetherNode> liveWorkers() {
        var cores = leader().coreNodeIds();

        return cluster.allNodes().stream().filter(node -> !cores.contains(node.self())).toList();
    }

    private TimeSpan defaultCommunityAbsence() {
        return TimeSpan.timeSpan(20).seconds();
    }

    private NodeId bootAndCutWorker(TimeSpan coreAbsence, TimeSpan communityAbsence, boolean keepQuic) {
        LifecycleAwait.settled("start false-dead fixture", cluster, cluster.start());
        await().atMost(BUDGET.duration()).until(() -> cluster.currentLeader().isPresent());
        // Set after the cores started: only the worker created next gets the fence window under test.
        cluster.withCoreAbsenceForNewNodes(coreAbsence);
        cluster.withCommunityAbsenceForNewNodes(communityAbsence);
        var id = LifecycleAwait.nodeSettled("admit false-dead worker", cluster, cluster.addWorkerNode());
        var worker = cluster.getNode(id.id()).unwrap();
        var key = new AetherKey.ActivationDirectiveKey(id);

        await().atMost(BUDGET.duration()).until(() -> worker.kvStore().getTyped(key, AetherValue.ActivationDirectiveValue.class).isPresent());
        await().alias("every core has observed the worker SWIM-HEALTHY before the cut (the cold-boot gate suppresses a never-HEALTHY death)")
               .atMost(BUDGET.duration())
               .until(() -> cluster.allNodes().stream().filter(node -> !node.self().equals(id)).allMatch(node -> swimHealthy(node, id)));
        cutSwim(id, keepQuic);
        await().atMost(BUDGET.duration()).until(() -> leader().kvStore().getTyped(key, AetherValue.ActivationDirectiveValue.class).isEmpty());
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
