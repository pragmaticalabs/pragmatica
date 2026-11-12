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
import java.util.concurrent.TimeUnit;
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
/// - Removal first (`coreAbsence` above removal plus heal): the same worker is still alive at the heal and must rejoin (#3107).
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
    private static final TimeSpan REMOVAL_FIRST_COMMUNITY = TimeSpan.timeSpan(360).seconds();
    private final EmberCluster cluster = EmberCluster.emberCluster(3, 33400, 33500, 33600, "false-dead");

    @AfterEach void stop() {
        LifecycleAwait.bestEffort("stop false-dead fixture", cluster, cluster.stop());
    }

    /// Fence path, by design: the worker loses the cores for longer than `coreAbsence`, fences and exits with `EXIT_DRAINED`;
    /// the cores remove it only after. Its replacement is a fresh id (production never relaunches the same id), and the
    /// replacement gets its directive.
    @Test void fencedWorker_exitsDrained_andFreshReplacementGetsDirective() {
        var id = bootAndCutWorker(FENCE_FIRST, defaultCommunityAbsence());

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

    /// Removal path (#3107): the cores remove the worker while it is still alive (its fence is out of reach), and after the
    /// heal the SAME worker is re-admitted. Without #3107 its directive stays lost; this tripwire pins that, and when #3107
    /// merges it goes red: enable `removedWorkerHealed_rejoinsWithDirectiveAndKvLink` and delete this one.
    @Test void removedWorkerHealed_currentBehaviour_directiveStaysLost() {
        var id = bootAndCutWorker(REMOVAL_FIRST, REMOVAL_FIRST_COMMUNITY);

        assertThat(cluster.exitCodeOf(id.id())).as("the worker was removed before its fence could fire").isEqualTo(Option.none());
        var worker = cluster.getNode(id.id()).unwrap();

        healSwim();

        await().alias("TRIPWIRE: the healed worker's directive stayed lost (#3107 not merged); enable removedWorkerHealed_rejoinsWithDirectiveAndKvLink and delete this test")
               .during(RECOVERY_BOUND.duration())
               .atMost(RECOVERY_BOUND.duration().plusSeconds(60))
               .until(() -> !directiveCommitted(worker));
    }

    @Disabled("#3107 (C2) not merged: enable once it is, and delete removedWorkerHealed_currentBehaviour_directiveStaysLost")
    @Test void removedWorkerHealed_rejoinsWithDirectiveAndKvLink() {
        var id = bootAndCutWorker(REMOVAL_FIRST, REMOVAL_FIRST_COMMUNITY);
        var key = new AetherKey.ActivationDirectiveKey(id);
        var worker = cluster.getNode(id.id()).unwrap();

        assertThat(cluster.exitCodeOf(id.id())).as("the worker was removed before its fence could fire").isEqualTo(Option.none());
        var healedAt = System.nanoTime();

        healSwim();
        await().alias("the healed worker's directive is committed again")
               .atMost(RECOVERY_BOUND.duration())
               .until(() -> leader().kvStore().getTyped(key, AetherValue.ActivationDirectiveValue.class).isPresent());
        // The QUIC layer rejects the worker as a REMOVED peer until the tombstone lapses (about 30 s observed); the bound covers it.
        log.info("Healed worker's directive committed {} ms after the heal (RECOVERY_BOUND {} ms)",
                 TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - healedAt),
                 RECOVERY_BOUND.duration().toMillis());
        await().alias("the worker's KV link to the core is restored")
               .atMost(RECOVERY_BOUND.duration())
               .until(() -> worker.connectedPeerIds().stream().anyMatch(leader().coreNodeIds()::contains));
        await().alias("the worker's own projection carries its directive")
               .atMost(RECOVERY_BOUND.duration())
               .until(() -> worker.kvStore().getTyped(key, AetherValue.ActivationDirectiveValue.class).isPresent());
        assertThat(leader().coreNodeIds()).hasSize(3).doesNotContain(id);
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

    private NodeId bootAndCutWorker(TimeSpan coreAbsence, TimeSpan communityAbsence) {
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
        cutSwim(id);
        await().atMost(BUDGET.duration()).until(() -> leader().kvStore().getTyped(key, AetherValue.ActivationDirectiveValue.class).isEmpty());
        return id;
    }

    /// Cuts SWIM between the worker and every core, in both directions, and keeps QUIC up: `partitionFrom` also installs a QUIC
    /// inbound filter, which the follow-up unfilter lifts. The worker is then declared dead by SWIM alone, as in a false death.
    private void cutSwim(NodeId worker) {
        var cores = cluster.allNodes().stream().map(AetherNode::self).filter(node -> !node.equals(worker)).collect(Collectors.toSet());

        cluster.allNodes().forEach(node -> {
            node.partitionFrom(node.self().equals(worker) ? cores : Set.of(worker));
            node.setInboundFaultFilter((_, _) -> true);
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
