// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.stream.ClusterCursorStore;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.generation.RewindEpoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerAssignmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamCursorCheckpointValue;
import org.pragmatica.aether.slice.kvstore.ReplicaFilter;
import org.pragmatica.aether.stream.StreamConsumerRuntime;
import org.pragmatica.aether.stream.StreamConsumerRuntime.SubscriptionSnapshot;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.aether.ember.StreamTripwireSupport.awaitUntil;
import static org.pragmatica.aether.ember.StreamTripwireSupport.engineKey;

/// #3283 (G1): a worker-held DECLARED consumer is restarted when its group is rewound, through the node's own wiring
/// (`StreamAssembly` passes `CommittedCursorRead.committedEpochs` to `StreamConsumerManager`).
///
/// 3 cores and 3 workers (the shape of `EmberWorkerStreamConsumerTest`, whose fixture run is green). The slice is a real jar in a staged repository
/// (see [WorkerConsumerSlice]) deployed WORKERS_ONLY through a blueprint apply, so the one worker the system picks loads it, writes its own ACTIVE row, and its `StreamConsumerManager` attaches the declared partition through its real runtime. A checkpoint with a newer
/// rewind epoch is then committed through the leader under the committed assignment's token, as a projection rebuild does. A worker's replica
/// excludes cursor checkpoints and it gets no put notification for one, so only the reconcile pass's committed-epoch source can see the rewind:
/// the worker's consumer must restart and run under the rewound epoch.
@PortBudget
class EmberWorkerRewindRestartTest {
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
    /// 12 reconcile passes (5 s each); the restart needs two (one sends the read, the next acts on its answer).
    private static final long REWIND_BOUND_MS = 60_000L;
    private static final String STREAM = "w3726r";
    private static final String ENGINE_KEY = engineKey(STREAM);
    private static final int PARTITION = 0;
    private static final String CONFIG_SECTION = "stream.w3726r";
    private static final String GROUP = "w3726r-group";
    private static final Artifact SLICE = Artifact.artifact("org.example:w3726r-consumer-slice:1.0.0").unwrap();
    private static final RewindEpoch REWOUND = RewindEpoch.rewindEpoch(1L, 1L, 1L);

    @TempDir
    Path stagedRoot;

    private EmberCluster cluster;
    private WorkerConsumerSlice slice;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }
    }

    private EmberCluster stagedCluster(int base) {
        return slice.install(emberCluster(CORES, base, base + MGMT_OFFSET, base + APP_OFFSET, STREAM));
    }

    @Test
    @Timeout(900)
    void workerDeclaredConsumer_rewoundGroup_isRestartedUnderTheRewoundEpoch() throws IOException {
        slice = WorkerConsumerSlice.stagedAt(stagedRoot, SLICE);
        cluster = EmberTestPorts.startedCluster(PORTS, this::stagedCluster, START_BOUND);
        awaitUntil(() -> cluster.currentLeader().isPresent(), WAIT_BOUND_MS, "a leader is elected");
        var workers = IntStream.range(0, WORKERS)
                               .mapToObj(_ -> startedWorker())
                               .toList();

        awaitUntil(() -> leader().membershipFsm().workerCountedMembers().containsAll(workers),
                   WAIT_BOUND_MS,
                   "the leader counts every worker as a worker member: " + workers);
        StreamTripwireSupport.createStream(leader(), StreamTripwireSupport.smallRingStream(STREAM, 1, 1_000L, 3_600_000L));
        slice.deployOnWorkers(cluster);
        slice.awaitActiveOnEveryWorker(this::leader, workers, WAIT_BOUND_MS, this::diagnostics);
        WorkerConsumerSlice.commit(leader(),
                                   registrationKey(),
                                   AetherValue.StreamRegistrationValue.streamRegistrationValue(workers.getFirst(), GROUP, false, "java.lang.String"));
        var assignmentKey = AetherKey.ConsumerAssignmentKey.consumerAssignmentKey(ENGINE_KEY, PARTITION, GROUP);

        awaitCondition("the partition is assigned to a worker",
                       () -> assignment(assignmentKey).map(ConsumerAssignmentValue::assignee).filter(workers::contains).isPresent());
        var worker = assignment(assignmentKey).unwrap().assignee();
        slice.assertAssigneeIsActiveWorker(cluster, worker, workers);
        var runtime = StreamTripwireSupport.component(node(worker), "streamConsumerRuntime", StreamConsumerRuntime.class);

        awaitUntil(() -> started(runtime).isPresent(),
                   WAIT_BOUND_MS,
                   "the worker's manager attached the declared consumer and it started: " + runtime.subscriptions());
        assertThat(started(runtime).unwrap().rewindEpoch()).as("control: before the rewind the consumer runs unrewound")
                                                          .isEqualTo(RewindEpoch.NONE);

        var cursorKey = AetherKey.StreamCursorCheckpointKey.streamCursorCheckpointKey(ENGINE_KEY, PARTITION, GROUP);

        assertThat(ReplicaFilter.replicaFilter().excludes(cursorKey)).as("premise, structural: the replica filter every worker store is built with (AetherNode, ReplicaFeedAssembly) excludes the checkpoint family, so the worker never holds a checkpoint and the epoch can only come through the consensus read")
                                                                    .isTrue();
        var token = assignment(assignmentKey).unwrap().token();
        var written = leader().<Object> apply(List.of(ClusterCursorStore.checkpointCommand(cursorKey, 0L, token, REWOUND)))
                              .await(COMMIT_BOUND);

        assertThat(written.isSuccess()).as("the rewind checkpoint is applied: %s", written).isTrue();
        awaitUntil(() -> leader().kvStore()
                                 .getTyped(cursorKey, StreamCursorCheckpointValue.class)
                                 .map(StreamCursorCheckpointValue::rewindEpoch)
                                 .equals(Option.some(REWOUND)),
                   WAIT_BOUND_MS,
                   "the leader's committed state holds the rewind (a refusal by the applier guard is not the wiring under test)");

        awaitUntil(() -> started(runtime).map(SubscriptionSnapshot::rewindEpoch).equals(Option.some(REWOUND)),
                   REWIND_BOUND_MS,
                   "the worker's consumer was restarted under the rewound epoch " + REWOUND
                   + " (StreamAssembly's committed-epoch source on a forwarding node); snapshots: " + runtime.subscriptions());
        assertThat(node(worker).kvStore().getTyped(cursorKey, StreamCursorCheckpointValue.class))
                .as("the worker's mirror lacks the checkpoint at the end, as the replica filter requires (a point-in-time read, not the premise)")
                .isEqualTo(Option.none());
    }

    private void awaitCondition(String what, BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + WAIT_BOUND_MS;

        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting: " + what + diagnostics());
            }
            StreamTripwireSupport.sleepQuietly(250L);
        }
    }

    private String diagnostics() {
        var rows = leader().kvStore()
                           .snapshot()
                           .entrySet()
                           .stream()
                           .filter(entry -> entry.getKey() instanceof AetherKey.NodeArtifactKey
                                            || entry.getKey() instanceof AetherKey.SliceTargetKey
                                            || entry.getKey() instanceof AetherKey.StreamRegistrationKey
                                            || entry.getKey() instanceof AetherKey.ConsumerAssignmentKey)
                           .map(entry -> entry.getKey() + "=" + entry.getValue())
                           .toList();

        return "; placement(leader)=" + leader().deploymentMap().byArtifact(SLICE)
               + "; workerCounted(leader)=" + leader().membershipFsm().workerCountedMembers()
               + "; rows=" + rows;
    }

    private static Option<SubscriptionSnapshot> started(StreamConsumerRuntime runtime) {
        return Option.from(runtime.subscriptions()
                                  .stream()
                                  .filter(snapshot -> snapshot.streamName().equals(ENGINE_KEY) && snapshot.partition() == PARTITION)
                                  .filter(snapshot -> !snapshot.awaitingCursorFetch())
                                  .findFirst());
    }

    private NodeId startedWorker() {
        var added = cluster.addWorkerNode().await(START_BOUND);

        assertThat(added.isSuccess()).as("the worker process starts").isTrue();
        var workerId = added.unwrap();

        awaitUntil(node(workerId)::isReady, WAIT_BOUND_MS, workerId.id() + " reports ready");

        return workerId;
    }

    private static AetherKey.StreamRegistrationKey registrationKey() {
        return AetherKey.StreamRegistrationKey.streamRegistrationKey(ENGINE_KEY,
                                                                     CONFIG_SECTION,
                                                                     SLICE,
                                                                     MethodName.methodName("onEvent").unwrap());
    }

    private Option<ConsumerAssignmentValue> assignment(AetherKey.ConsumerAssignmentKey key) {
        return leader().kvStore().getTyped(key, ConsumerAssignmentValue.class);
    }

    private AetherNode node(NodeId id) {
        return cluster.getNode(id.id()).unwrap();
    }

    private AetherNode leader() {
        return cluster.currentLeader()
                      .flatMap(cluster::getNode)
                      .unwrap();
    }
}
