// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import io.micrometer.core.instrument.Counter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterState;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.deployment.Fsm2System;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// E-12 (FSM replacement plan F3, #2762): the FSM2 worker arm end to end, on 3 FSM2 cores and 3 workers (the default
/// community viability floor), whatever the global selector says.
///
/// - Each worker is admitted by the leader's cluster FSM (B10 `WorkerJoin`): its activation directive is committed, it reports ready and its node
///   FSM is active, entered on the B4 edge (`QuorumEstablished`, not only the B3 heal's `ReconcileActivation`; the node half over the replica feed, know `23ca0fd65f` D.2).
/// - A target placed WORKERS_ONLY gives one worker a `SliceDesiredKey`, and that key reaches the worker's own replica (its node FSM's input).
/// - That worker's departure (B9 `WorkerLeave`) is recorded DEPARTED by the leader, which removes the worker's desired keys.
///
/// Without the core-side admission (`DeploymentWiring.workerJoin` bound as a counted drop) no directive is written and the worker never becomes
/// placeable (`worker-assignment-overdue`): the directive and desired-key waits time out. This test is the only kill of AetherNode's B4 entry
/// edge, of its attach condition and of the worker flag handed to the FSM2 system (each probed RED). AetherNode's B10, B11 and B9 (worker leave)
/// binding lines are also killed by `DeploymentWiringBootTest`; here only the B10 bindings were probed (RED), B11 and B9 were not.
@PortBudget
class EmberFsm2WorkerArmTest {
    private static final int CORES = 3;
    /// The default community viability floor: a worker community is ACTIVE, and so placeable, from three live members.
    private static final int WORKERS = 3;
    private static final int SLOTS = 2 * CORES;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_OFFSET = 80;
    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                                EmberTestPorts.POOL_LAST,
                                                                                EmberTestPorts.POOL_STEP,
                                                                                SLOTS,
                                                                                MGMT_OFFSET,
                                                                                APP_OFFSET);
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan COMMIT_BOUND = TimeSpan.timeSpan(30).seconds();
    private static final long WAIT_BOUND_MS = 120_000L;
    private static final Artifact SLICE = Artifact.artifact("org.example:e12-worker-slice:1.0.0").unwrap();

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }
    }

    private static EmberCluster fsm2Cluster(int base) {
        var built = emberCluster(CORES, base, base + MGMT_OFFSET, base + APP_OFFSET, "e12");

        built.withDeploymentEngine(DeploymentEngine.FSM2);

        return built;
    }

    @Test
    @Timeout(900)
    void fsm2Workers_areAdmittedReadyAndActive_oneReceivesItsDesiredKey_andDeparts() {
        cluster = EmberTestPorts.startedCluster(PORTS, EmberFsm2WorkerArmTest::fsm2Cluster, START_BOUND);
        awaitCondition("a leader is elected", () -> cluster.currentLeader().isPresent());

        var workers = IntStream.range(0, WORKERS)
                               .mapToObj(_ -> admittedWorker())
                               .toList();

        commitWorkersOnlyTarget();
        awaitCondition("the leader places the slice on a worker", () -> placedWorker(workers).isPresent(), this::placementState);
        var placed = placedWorker(workers).unwrap();
        var host = cluster.getNode(placed.id()).unwrap();

        awaitCondition("D.2: the worker's own SliceDesiredKey reaches its replica", () -> desiredFor(host, placed));

        cluster.killNode(placed.id()).await(STOP_BOUND);
        awaitCondition("B9: the leader records the worker DEPARTED", () -> departed(placed));
        awaitCondition("C17: the departed worker's desired keys are removed", () -> !desiredFor(leader(), placed));
    }

    /// One worker added and admitted: B10 commits its directive, it reports ready, and its node FSM (the node half, B4) is active.
    private NodeId admittedWorker() {
        var added = cluster.addWorkerNode().await(START_BOUND);

        assertThat(added.isSuccess()).as("the worker process starts").isTrue();
        var workerId = added.unwrap();
        var worker = cluster.getNode(workerId.id()).unwrap();

        assertThat(worker.deploymentWiring().engine()).as("control: the worker runs FSM2").isEqualTo(DeploymentEngine.FSM2);
        awaitCondition("B10: the leader's cluster FSM commits the activation directive of " + workerId.id(),
                       () -> leader().kvStore().getTyped(new AetherKey.ActivationDirectiveKey(workerId), AetherValue.class).isPresent());
        awaitCondition(workerId.id() + " reports ready", worker::isReady);
        awaitCondition("B4: the node FSM of " + workerId.id() + " is active", () -> nodeFsmActive(worker));
        // The B4 edge (QuorumEstablished) and the B3 level heal (ReconcileActivation) are two entry paths that race: the 5 s heal tick may land between the
        // engine's passive-client-ready flag and the B4 hook, and then the heal enters and the edge is skipped_active. Which wins is not a property of the
        // worker arm (#2842), so this pins what is: the node FSM entered exactly once. That the genuine edge enters is pinned deterministically in
        // Fsm2SystemTest (aWorker_entersOnThePassiveClientSwitch_andStepsOnlyItsOwnGoal).
        assertThat(appliedNodeInputs(worker, "QuorumEstablished") + appliedNodeInputs(worker, "ReconcileActivation"))
                .as("B4/B3: %s entered the node FSM exactly once, by the edge or the level heal", workerId.id())
                .isEqualTo(1.0);
        assertThat(worker.deploymentWiring().system().map(Fsm2System::worker)).as("the worker runs the node half").isEqualTo(Option.some(true));

        return workerId;
    }

    private Option<NodeId> placedWorker(List<NodeId> workers) {
        return Option.from(workers.stream()
                                  .filter(worker -> desiredFor(leader(), worker))
                                  .findFirst());
    }

    private static double appliedNodeInputs(AetherNode node, String input) {
        return node.deploymentWiring()
                   .meters()
                   .find("aether_deployment_fsm_inputs_total")
                   .tags("fsm", "node", "input", input, "result", "applied")
                   .counters()
                   .stream()
                   .mapToDouble(Counter::count)
                   .sum();
    }

    private static boolean nodeFsmActive(AetherNode node) {
        return node.deploymentWiring()
                   .system()
                   .map(system -> system.nodeInterpreter().model().active())
                   .or(false);
    }

    private void commitWorkersOnlyTarget() {
        var node = leader();
        var key = AetherKey.SliceTargetKey.sliceTargetKey(SLICE.base());
        var target = AetherValue.SliceTargetValue.sliceTargetValue(SLICE.version(), 1).withPlacement("WORKERS_ONLY");
        var authority = node.kvStore().getTyped(LeaderKey.INSTANCE, LeaderValue.class).unwrap();
        var id = UUID.randomUUID().toString();
        var transaction = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key,
                                                                                  id,
                                                                                  authority,
                                                                                  List.of(),
                                                                                  List.of(new KVCommand.Mutation<AetherKey, AetherValue>(key, Option.none(), Option.some(target))));
        var results = node.<Object> apply(List.of(transaction)).await(COMMIT_BOUND).unwrap();

        assertThat(results).as("the WORKERS_ONLY target commits")
                           .anyMatch(result -> result instanceof KVCommand.TransactionResult accepted && accepted.transactionId().equals(id) && accepted.accepted());
    }

    private static boolean desiredFor(AetherNode node, NodeId target) {
        return snapshot(node).keySet()
                             .stream()
                             .anyMatch(key -> key instanceof AetherKey.SliceDesiredKey desired && desired.nodeId().equals(target)
                                              && desired.artifact().equals(SLICE));
    }

    private boolean departed(NodeId target) {
        return leader().kvStore()
                       .getTyped(new AetherKey.NodeDepartureKey(target), AetherValue.NodeDepartureValue.class)
                       .filter(record -> record.state() == AetherValue.DepartureState.DEPARTED)
                       .isPresent();
    }

    private static Map<?, ?> snapshot(AetherNode node) {
        return node.kvStore().snapshot();
    }

    private AetherNode leader() {
        return cluster.currentLeader()
                      .flatMap(cluster::getNode)
                      .unwrap();
    }

    private static void awaitCondition(String what, BooleanSupplier condition) {
        awaitCondition(what, condition, () -> "");
    }

    /// The leader's placement inputs, printed when the placement wait times out: its cluster FSM's seams and the community, roster and directive rows.
    private String placementState() {
        var node = leader();
        var seams = node.deploymentWiring()
                        .system()
                        .map(system -> system.clusterInterpreter().state() instanceof ClusterState.Active active
                                       ? active.model().seams().toString()
                                       : "cluster FSM not active: " + system.clusterInterpreter().state())
                        .or("no FSM2 system");
        var rows = snapshot(node).entrySet()
                                 .stream()
                                 .filter(entry -> entry.getKey() instanceof AetherKey.CommunityKey
                                                  || entry.getKey() instanceof AetherKey.GovernorAnnouncementKey
                                                  || entry.getKey() instanceof AetherKey.ActivationDirectiveKey)
                                 .map(entry -> entry.getKey() + "=" + entry.getValue())
                                 .toList();

        return "; seams=" + seams + "; rows=" + rows;
    }

    private static void awaitCondition(String what, BooleanSupplier condition, Supplier<String> state) {
        var deadline = System.currentTimeMillis() + WAIT_BOUND_MS;

        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting: " + what + state.get());
            }
            sleep(250);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
