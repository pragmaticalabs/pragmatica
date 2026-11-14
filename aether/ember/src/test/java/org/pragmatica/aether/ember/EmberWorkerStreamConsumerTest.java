// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.aether.ember.StreamTripwireSupport.awaitUntil;
import static org.pragmatica.aether.ember.StreamTripwireSupport.engineKey;

/// #2600: a declarative stream consumer whose slice is ACTIVE only on a worker is assigned to that worker.
///
/// 3 cores and 3 workers. A stream is created, a consumer declaration (`StreamRegistrationKey`) for a slice is committed, and the
/// slice's observed row is committed ACTIVE on one worker only. The leader's `StreamConsumerManager` must read that worker as a live
/// candidate (its `PartitionOwnership.liveWorkers`, wired in `AetherNode` from `MembershipFsm.workerCountedMembers`, the B1 liveness source:
/// every core observes every member's descriptor) and commit the partition's `ConsumerAssignmentKey` naming it. Before the fix the
/// candidate set was the core-only view, so the slice read as "not consumed yet" and no assignment was ever written.
///
/// The slice itself is not loaded: the assignment is the admission the manager gives, and it is the part #2600 changes. That the worker then
/// attaches a runtime subscription needs a real slice jar and is covered by `M1StreamLoadTest`'s worker arm.
@PortBudget
class EmberWorkerStreamConsumerTest {
    private static final int CORES = 3;
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
    private static final String STREAM = "w2600";
    private static final String CONFIG_SECTION = "stream.w2600";
    private static final String GROUP = "w2600-group";
    private static final Artifact SLICE = Artifact.artifact("org.example:w2600-consumer-slice:1.0.0").unwrap();

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }
    }

    private static EmberCluster fsm2Cluster(int base) {
        var built = emberCluster(CORES, base, base + MGMT_OFFSET, base + APP_OFFSET, "w2600");

        built.withDeploymentEngine(DeploymentEngine.LEGACY);

        return built;
    }

    /// Under LEGACY the stream-consumer placement view (`DeploymentMap`) is fed by `NodeArtifactKey` rows. FSM2 would condemn this hand-committed row as a stray instance (no desired row), so the test pins LEGACY.
    @Test
    @Timeout(900)
    void consumerSliceActiveOnlyOnAWorker_isAssignedToThatWorker() {
        cluster = EmberTestPorts.startedCluster(PORTS, EmberWorkerStreamConsumerTest::fsm2Cluster, START_BOUND);
        awaitUntil(() -> cluster.currentLeader().isPresent(), WAIT_BOUND_MS, "a leader is elected");

        var workers = IntStream.range(0, WORKERS)
                               .mapToObj(_ -> startedWorker())
                               .toList();
        var worker = workers.getFirst();

        awaitCondition("B1: the leader's membership counts every worker as a worker member",
                       () -> workers.stream().allMatch(id -> leader().membershipFsm().workerCountedMembers().contains(id)),
                       () -> membership(worker));
        StreamTripwireSupport.createStream(leader(), StreamTripwireSupport.smallRingStream(STREAM, 1, 1_000L, 3_600_000L));
        commit(registrationKey(), AetherValue.StreamRegistrationValue.streamRegistrationValue(worker, GROUP, false, "java.lang.String"));
        commit(AetherKey.NodeArtifactKey.nodeArtifactKey(worker, SLICE), AetherValue.NodeArtifactValue.nodeArtifactValue(SliceState.ACTIVE));

        awaitCondition("the leader's DeploymentMap shows the worker ACTIVE",
                       () -> leader().deploymentMap().byArtifact(SLICE).containsKey(worker),
                       this::diagnostics);
        var assignment = AetherKey.ConsumerAssignmentKey.consumerAssignmentKey(engineKey(STREAM), 0, GROUP);

        awaitUntil(() -> assignee(assignment).equals(Option.some(worker)),
                   WAIT_BOUND_MS,
                   "the partition is assigned to the only worker the slice is ACTIVE on, got " + assignee(assignment) + diagnostics());
    }

    private NodeId startedWorker() {
        var added = cluster.addWorkerNode().await(START_BOUND);

        assertThat(added.isSuccess()).as("the worker process starts").isTrue();
        var workerId = added.unwrap();
        var node = cluster.getNode(workerId.id()).unwrap();

        awaitUntil(node::isReady, WAIT_BOUND_MS, workerId.id() + " reports ready");

        return workerId;
    }

    private static void awaitCondition(String what, BooleanSupplier condition, Supplier<String> state) {
        var deadline = System.currentTimeMillis() + WAIT_BOUND_MS;

        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting: " + what + state.get());
            }
            StreamTripwireSupport.sleepQuietly(250L);
        }
    }

    private String membership(NodeId worker) {
        var fsm = leader().membershipFsm();

        return "; workerCounted=" + fsm.workerCountedMembers() + "; counted=" + fsm.countedMembers() + "; descriptor(" + worker.id() + ")=" + fsm.memberDescriptor(worker);
    }

    /// Printed with the timeout: the committed rows the manager reads and the leader's own view of its declared consumers.
    private String diagnostics() {
        var rows = leader().kvStore()
                           .snapshot()
                           .entrySet()
                           .stream()
                           .filter(entry -> entry.getKey() instanceof AetherKey.NodeArtifactKey
                                            || entry.getKey() instanceof AetherKey.StreamRegistrationKey
                                            || entry.getKey() instanceof AetherKey.ConsumerAssignmentKey)
                           .map(entry -> entry.getKey() + "=" + entry.getValue())
                           .toList();
        var consumers = Result.lift(Causes::fromThrowable, () -> HttpClient.newHttpClient()
                                                                         .send(HttpRequest.newBuilder(URI.create("http://localhost:" + cluster.getLeaderManagementPort().or(-1) + "/api/v1/streams/declarative-consumers"))
                                                                                          .build(),
                                                                               HttpResponse.BodyHandlers.ofString())
                                                                         .body())
                              .fold(Cause::message, body -> body);

        return "; placement(leader)=" + leader().deploymentMap().byArtifact(SLICE) + "; workerCounted(leader)=" + leader().membershipFsm().workerCountedMembers() + "; rows=" + rows + "; declarative-consumers=" + consumers;
    }

    private static AetherKey.StreamRegistrationKey registrationKey() {
        return AetherKey.StreamRegistrationKey.streamRegistrationKey(engineKey(STREAM),
                                                                     CONFIG_SECTION,
                                                                     SLICE,
                                                                     MethodName.methodName("onEvent").unwrap());
    }

    private Option<NodeId> assignee(AetherKey.ConsumerAssignmentKey key) {
        return leader().kvStore()
                       .getTyped(key, AetherValue.ConsumerAssignmentValue.class)
                       .map(AetherValue.ConsumerAssignmentValue::assignee);
    }

    private void commit(AetherKey key, AetherValue value) {
        var node = leader();
        var authority = node.kvStore().getTyped(LeaderKey.INSTANCE, LeaderValue.class).unwrap();
        var id = UUID.randomUUID().toString();
        var transaction = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key,
                                                                                  id,
                                                                                  authority,
                                                                                  List.of(),
                                                                                  List.of(new KVCommand.Mutation<AetherKey, AetherValue>(key, Option.none(), Option.some(value))));
        var results = node.<Object> apply(List.of(transaction)).await(COMMIT_BOUND).unwrap();

        assertThat(results).as("the commit of %s is accepted", key)
                           .anyMatch(result -> result instanceof KVCommand.TransactionResult accepted && accepted.transactionId().equals(id) && accepted.accepted());
    }

    private AetherNode leader() {
        return cluster.currentLeader()
                      .flatMap(cluster::getNode)
                      .unwrap();
    }
}
