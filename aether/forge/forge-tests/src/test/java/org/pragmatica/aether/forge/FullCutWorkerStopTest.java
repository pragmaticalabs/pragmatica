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
import org.pragmatica.aether.api.ClusterEventAggregator;
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

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// #3326 item 2: a worker cut off from EVERY core (SWIM and QUIC, so its core pings stop at the cut) stops itself, by the
/// core-absence fence or by the retirement of its own directive, and nothing the cluster re-placed keeps running on it.
///
/// Which path ran is read from the operator warning each path raises on the worker's own event aggregator
/// (`core-absence-fence`, `own-directive-retired`); the aggregator outlives the stopped node, so the observation does not race
/// the node's deregistration. The other fixture ([FalseDeadWorkerRejoinTest]) keeps QUIC open, so the worker's pings end only
/// when the cores evict it and the fence cannot precede the removal there; this one cuts QUIC at the cut.
///
/// Bounds, from the code: the fence fires at last ping + `coreAbsence` + one detector tick (`CoreAbsenceDetector.evaluate`;
/// the last ping is at most one ping interval, 1 s, before the cut; the tick is the 1 s ping cadence). The cores' removal of
/// the worker is NOT bounded below the fence here: with QUIC cut too the link loss makes it prompt (2 of 12 runs on bigboy had a
/// core hold the worker Dead at the fence, 5.3-5.5 s after the cut), so no order between the fence and the removal is asserted;
/// the number of cores holding the worker Dead at the stop is logged. The cut cannot deliver the removal of the worker's
/// directive to it, so `own-directive-retired` is accepted but not expected under this cut.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
class FullCutWorkerStopTest {
    private static final Logger log = LoggerFactory.getLogger(FullCutWorkerStopTest.class);
    private static final TimeSpan BUDGET = TimeSpan.timeSpan(180).seconds();
    private static final TimeSpan CORE_ABSENCE = TimeSpan.timeSpan(5).seconds();
    private static final TimeSpan COMMUNITY_ABSENCE = TimeSpan.timeSpan(20).seconds();
    /// Fence bound after the cut: coreAbsence + one ping interval (last ping before the cut) + one tick = 7 s, plus a 3 s margin
    /// for scheduler jitter on a loaded host.
    private static final Duration FENCE_BOUND = CORE_ABSENCE.duration().plusSeconds(2 + 3);
    /// The larger of the two bounds: the cores' removal (SWIM suspect 10 s stretched up to x8 = 80 s, plus the removal) = 90 s,
    /// plus a 30 s margin.
    private static final Duration STOP_BOUND = Duration.ofSeconds(90 + 30);
    private static final String FENCE_CODE = "core-absence-fence";
    private static final String RETIRED_CODE = "own-directive-retired";
    private final EmberCluster cluster = EmberCluster.emberCluster(3, 33700, 33750, 33780, "full-cut");

    @AfterEach void stop() {
        LifecycleAwait.bestEffort("stop full-cut fixture", cluster, cluster.stop());
    }

    @Test void workerCutOffFromEveryCore_stopsItselfByFenceOrDirectiveRetirement() {
        var id = bootWorker();
        var events = cluster.getNode(id.id()).unwrap().eventAggregator();
        var cutAt = cutEveryLink(id);

        await().alias("the worker stopped itself: fence or own-directive retirement")
               .atMost(STOP_BOUND)
               .pollInterval(Duration.ofMillis(20))
               .until(() -> stopWarningCode(events).isPresent());
        var delay = Duration.ofNanos(System.nanoTime() - cutAt);
        var path = stopWarningCode(events).unwrap();
        var coresDeadAtStop = cores(id).stream().filter(node -> isDead(node, id)).count();

        log.info("FULL-CUT-STOP path={} delayMs={} coresDeadAtStop={}", path, delay.toMillis(), coresDeadAtStop);
        assertThat(path).as("the stop path").isIn(FENCE_CODE, RETIRED_CODE);
        assertFenceWithinBound(path, delay);
        assertNothingKeepsRunningOn(id);
    }

    /// (b) When the stop was the fence, it fired no later than the core-absence bound after the cut.
    private static void assertFenceWithinBound(String path, Duration delay) {
        if (FENCE_CODE.equals(path)) {
            assertThat(delay).as("the fence fired within coreAbsence + ping interval + tick + margin of the cut")
                             .isLessThanOrEqualTo(FENCE_BOUND);
        }
    }

    /// (c) The stopped worker is out of the registry with a recorded exit, and no slice instance is placed on it. No slice is
    /// deployed in this fixture, so the slice check holds vacuously; the registry and exit checks are the substantive ones.
    private void assertNothingKeepsRunningOn(NodeId id) {
        await().alias("the stopped worker reported its exit")
               .atMost(BUDGET.duration())
               .until(() -> cluster.exitCodeOf(id.id()).isPresent());
        assertThat(cluster.exitCodeOf(id.id()).unwrap()).isEqualTo(AetherNode.EXIT_DRAINED);
        assertThat(cluster.getNode(id.id())).as("the stopped worker is no longer a running node").isEqualTo(Option.none());
        assertThat(cluster.slicesStatus().stream()
                          .flatMap(slice -> slice.instances().stream())
                          .filter(instance -> instance.nodeId().equals(id.id()))
                          .count()).as("no slice instance is placed on the stopped worker").isZero();
    }

    private static Option<String> stopWarningCode(ClusterEventAggregator events) {
        return events.lastRaisedOperatorWarning()
                     .flatMap(event -> Option.option(event.details().get("code")))
                     .filter(code -> FENCE_CODE.equals(code) || RETIRED_CODE.equals(code));
    }

    private List<AetherNode> cores(NodeId worker) {
        return cluster.allNodes().stream().filter(node -> !node.self().equals(worker)).toList();
    }

    private static boolean isDead(AetherNode node, NodeId peer) {
        return "Dead".equals(node.membershipFsm().memberStates().get(peer));
    }

    private NodeId bootWorker() {
        LifecycleAwait.settled("start full-cut fixture", cluster, cluster.start());
        await().atMost(BUDGET.duration()).until(() -> cluster.currentLeader().isPresent());
        // Set after the cores started: only the worker created next gets the fence window under test.
        cluster.withCoreAbsenceForNewNodes(CORE_ABSENCE);
        cluster.withCommunityAbsenceForNewNodes(COMMUNITY_ABSENCE);
        var id = LifecycleAwait.nodeSettled("admit full-cut worker", cluster, cluster.addWorkerNode());
        var worker = cluster.getNode(id.id()).unwrap();

        assertThat(worker.coreAbsenceSnapshot().map(snapshot -> snapshot.thresholdMs()))
                .as("the worker's effective core-absence threshold is the seam value")
                .isEqualTo(Option.some(CORE_ABSENCE.duration().toMillis()));
        var key = new AetherKey.ActivationDirectiveKey(id);

        await().atMost(BUDGET.duration()).until(() -> worker.kvStore().getTyped(key, AetherValue.ActivationDirectiveValue.class).isPresent());
        await().alias("the fence is armed: the worker accepted a core ping")
               .atMost(BUDGET.duration())
               .until(() -> worker.coreAbsenceSnapshot().map(snapshot -> snapshot.armed()).or(false));
        await().alias("every core has observed the worker SWIM-HEALTHY before the cut")
               .atMost(BUDGET.duration())
               .until(() -> cores(id).stream().allMatch(node -> swimHealthy(node, id)));
        return id;
    }

    /// Cuts SWIM and QUIC between the worker and every core, in both directions (`partitionFrom` installs the QUIC inbound
    /// filter too; it is not lifted here), and returns the moment the cut was made.
    private long cutEveryLink(NodeId worker) {
        var cores = cores(worker).stream().map(AetherNode::self).collect(Collectors.toSet());

        cluster.allNodes().forEach(node -> node.partitionFrom(node.self().equals(worker) ? cores : Set.of(worker)));
        return System.nanoTime();
    }

    private static boolean swimHealthy(AetherNode node, NodeId peer) {
        return node.membershipView().get(peer).map(view -> view.swimHealth() == SwimHealth.HEALTHY).or(false);
    }
}
