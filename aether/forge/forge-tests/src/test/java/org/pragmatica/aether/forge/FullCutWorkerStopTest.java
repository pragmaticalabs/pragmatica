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
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.swim.SwimHealth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

/// #3326 item 2: a worker cut off from EVERY core (SWIM and QUIC, so its core pings stop at the cut) fences itself: the
/// core-absence fence stops it within the core-absence bound of the cut, it exits drained, and the slice instance placed on it
/// is gone from the cluster after the stop.
///
/// The fence is read from the operator warning it raises on the worker's own event aggregator (`core-absence-fence`); the
/// aggregator outlives the stopped node, so the observation does not race the node's deregistration. The retirement of the
/// worker's own directive is not an alternative path here: the removal of the directive travels over the cut link and cannot
/// reach the worker, so only the fence can stop it (fence off: this test is red). The other fixture
/// ([FalseDeadWorkerRejoinTest]) keeps QUIC open, so the worker's pings end only when the cores evict it; this one cuts QUIC
/// at the cut.
///
/// Bound, from the code: the fence fires at last ping + `coreAbsence` + one detector tick (`CoreAbsenceDetector.evaluate`; the
/// last ping is at most one ping interval, 1 s, before the cut; the tick is the 1 s ping cadence). No order between the fence
/// and the cores' Dead verdict on the worker is asserted: with QUIC cut too the link loss makes the verdict prompt, and the
/// config guarantees neither first (the fence came first in 10 of 12 runs, a core held the worker Dead 0.1-0.2 s before the
/// fence in 2 of 12); the number of cores holding the worker Dead at the stop is logged.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
class FullCutWorkerStopTest {
    private static final Logger log = LoggerFactory.getLogger(FullCutWorkerStopTest.class);
    private static final TimeSpan BUDGET = TimeSpan.timeSpan(180).seconds();
    private static final TimeSpan CORE_ABSENCE = TimeSpan.timeSpan(5).seconds();
    private static final TimeSpan COMMUNITY_ABSENCE = TimeSpan.timeSpan(20).seconds();
    /// The bound under test, from the cut: coreAbsence + one ping interval (last ping before the cut) + one tick = 7 s, plus a
    /// 3 s margin for scheduler jitter on a loaded host.
    private static final Duration FENCE_BOUND = CORE_ABSENCE.duration().plusSeconds(2 + 3);
    /// A timeout, not a bound: how long the test waits for the fence before it gives up (a missing fence fails here).
    private static final Duration STOP_WAIT_TIMEOUT = Duration.ofSeconds(120);
    private static final String FENCE_CODE = "core-absence-fence";
    private final EmberCluster cluster = EmberCluster.emberCluster(3, 33700, 33750, 33780, "full-cut");

    @AfterEach void stop() {
        LifecycleAwait.bestEffort("stop full-cut fixture", cluster, cluster.stop());
    }

    @Test void workerCutOffFromEveryCore_fencesItselfWithinTheCoreAbsenceBound() {
        var id = bootWorker();
        var events = cluster.getNode(id.id()).unwrap().eventAggregator();

        deploySliceOnWorker(id);
        var cutAt = cutEveryLink(id);

        await().alias("the worker fenced itself")
               .atMost(STOP_WAIT_TIMEOUT)
               .pollInterval(Duration.ofMillis(20))
               .until(() -> fenceRaised(events));
        var delay = Duration.ofNanos(System.nanoTime() - cutAt);
        var coresDeadAtStop = cores(id).stream().filter(node -> isDead(node, id)).count();

        log.info("FULL-CUT-STOP path={} delayMs={} coresDeadAtStop={}", FENCE_CODE, delay.toMillis(), coresDeadAtStop);
        assertThat(delay).as("the fence fired within coreAbsence + ping interval + tick + margin of the cut")
                         .isLessThanOrEqualTo(FENCE_BOUND);
        assertNothingKeepsRunningOn(id);
    }

    /// Targets the echo slice (blueprint minimum: 3 instances) at WORKERS_ONLY placement, which puts an instance on the only
    /// worker, and waits until it is ACTIVE there and visible in the cluster's slice status, so the after-fence check has a subject.
    private void deploySliceOnWorker(NodeId worker) {
        var artifact = Artifact.artifact(TestArtifacts.ECHO_SLICE).unwrap();
        var key = AetherKey.SliceTargetKey.sliceTargetKey(artifact.base());
        var blueprint = """
            id = "forge.test:full-cut:1.0.0"
            [[slices]]
            artifact = "%s"
            instances = 3
            """.formatted(TestArtifacts.ECHO_SLICE);
        var applyRequest = HttpRequest.newBuilder(URI.create("http://localhost:" + mgmtPortOfLeader() + "/api/v1/blueprints"))
                                      .header("Content-Type", "application/toml")
                                      .timeout(Duration.ofSeconds(10))
                                      .POST(HttpRequest.BodyPublishers.ofString(blueprint))
                                      .build();

        assertThat(jdkHttpOperations().sendString(applyRequest).await(TimeSpan.timeSpan(10).seconds()).unwrap().body())
                .contains("\"status\":\"applied\"");
        await().atMost(BUDGET.duration()).until(() -> leader().kvStore().getTyped(key, AetherValue.SliceTargetValue.class).isPresent());
        var node = leader();
        var before = node.kvStore().getTyped(key, AetherValue.SliceTargetValue.class).unwrap();
        var txId = UUID.randomUUID().toString();
        var transaction = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key, txId,
                node.kvStore().getTyped(LeaderKey.INSTANCE, LeaderValue.class).unwrap(), List.of(),
                List.of(new KVCommand.Mutation<>(key, Option.some(before), Option.some(before.withPlacement("WORKERS_ONLY")))));

        assertThat(node.<Object>apply(List.of(transaction)).await(BUDGET).unwrap())
                .anyMatch(result -> result instanceof KVCommand.TransactionResult accepted
                                    && accepted.transactionId().equals(txId) && accepted.accepted());
        await().alias("the slice instance is ACTIVE on the worker").atMost(BUDGET.duration()).until(() -> leader().kvStore()
                .getTyped(new AetherKey.NodeArtifactKey(worker, artifact), AetherValue.NodeArtifactValue.class)
                .filter(value -> value.state() == SliceState.ACTIVE).isPresent());
        assertThat(instancesPlacedOn(worker)).as("the instrument sees the deployed instance before the cut").isEqualTo(1);
    }

    private int mgmtPortOfLeader() {
        var leaderId = leader().self();

        return cluster.status().nodes().stream().filter(status -> status.id().equals(leaderId.id())).findFirst().orElseThrow().mgmtPort();
    }

    private AetherNode leader() {
        return cluster.currentLeader().flatMap(cluster::getNode).unwrap();
    }

    private long instancesPlacedOn(NodeId worker) {
        return cluster.slicesStatus().stream()
                      .flatMap(slice -> slice.instances().stream())
                      .filter(instance -> instance.nodeId().equals(worker.id()))
                      .count();
    }

    /// The stopped worker is out of the registry with a recorded exit, and the slice instance deployed on it is gone from the
    /// cluster's slice status.
    private void assertNothingKeepsRunningOn(NodeId id) {
        await().alias("the stopped worker reported its exit")
               .atMost(BUDGET.duration())
               .until(() -> cluster.exitCodeOf(id.id()).isPresent());
        assertThat(cluster.exitCodeOf(id.id()).unwrap()).isEqualTo(AetherNode.EXIT_DRAINED);
        assertThat(cluster.getNode(id.id())).as("the stopped worker is no longer a running node").isEqualTo(Option.none());
        await().alias("no slice instance is placed on the stopped worker")
               .atMost(BUDGET.duration())
               .until(() -> instancesPlacedOn(id) == 0);
    }

    private static boolean fenceRaised(ClusterEventAggregator events) {
        return events.lastRaisedOperatorWarning()
                     .flatMap(event -> Option.option(event.details().get("code")))
                     .filter(FENCE_CODE::equals)
                     .isPresent();
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
