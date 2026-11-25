// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.stream.ClusterCursorStore;
import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.RewindEpoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerAssignmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerAssignmentValue.AssignmentToken;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamCursorCheckpointValue;
import org.pragmatica.aether.stream.ConsumerFence;
import org.pragmatica.aether.stream.StreamConsumerRuntime;
import org.pragmatica.aether.stream.StreamConsumerRuntime.IdlePolicy;
import org.pragmatica.aether.stream.StreamWriteRouter;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.aether.ember.StreamTripwireSupport.awaitUntil;
import static org.pragmatica.aether.ember.StreamTripwireSupport.engineKey;

/// #3283: a worker successor resumes from the committed cursor its predecessor left, and a fresh group on a worker starts at
/// the reset point.
///
/// 3 cores and 2 workers. One stream partition holds 10 events. Worker A is assigned the partition and commits a cursor
/// checkpoint at offset 6 through its real cluster `apply` (forwarded to a core, #3116). The partition is then assigned to
/// worker B, and B's real `StreamConsumerRuntime` (the node's own, wired by `StreamAssembly`) subscribes under that assignment.
/// A worker's replica excludes cursor checkpoints, so B's mirror never holds A's checkpoint (asserted, the premise). Before the
/// fix B resumed from that empty mirror and started at the reset point (earliest), redelivering 0..5; now its resume reads the
/// checkpoint through consensus and B delivers exactly 6..9. A second group with no checkpoint, read ABSENT the same way,
/// starts at the reset point and delivers 0..9.
@PortBudget
class EmberWorkerCursorResumeTest {
    private static final int CORES = 3;
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
    private static final String STREAM = "w3283";
    private static final String ENGINE_KEY = engineKey(STREAM);
    private static final int PARTITION = StreamTripwireSupport.PARTITION;
    private static final String GROUP = "w3283-group";
    private static final String FRESH_GROUP = "w3283-fresh";
    private static final int EVENTS = 10;
    private static final long COMMITTED_OFFSET = 6L;

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }
    }

    private static EmberCluster cores(int base) {
        return emberCluster(CORES, base, base + MGMT_OFFSET, base + APP_OFFSET, STREAM);
    }

    @Test
    @Timeout(900)
    void workerSuccessor_resumesFromThePredecessorsCommittedCursor_freshGroupStartsAtTheResetPoint() {
        cluster = EmberTestPorts.startedCluster(PORTS, EmberWorkerCursorResumeTest::cores, START_BOUND);
        StreamTripwireSupport.awaitLeader(cluster);
        StreamTripwireSupport.createStream(leader(), StreamTripwireSupport.smallRingStream(STREAM, 1, 1_000L, 3_600_000L));
        publishEvents(StreamTripwireSupport.awaitFullIsr(cluster, ENGINE_KEY, WAIT_BOUND_MS).owner());

        var predecessor = startedWorker();
        var successor = startedWorker();
        var cursorKey = AetherKey.StreamCursorCheckpointKey.streamCursorCheckpointKey(ENGINE_KEY, PARTITION, GROUP);
        var predecessorEpoch = leader().currentGenerationEpoch().withCounter(1L);
        var successorEpoch = leader().currentGenerationEpoch().withCounter(2L);

        assign(predecessor, predecessorEpoch);
        commitFromWorker(predecessor, cursorKey, AssignmentToken.assignmentToken(predecessor, predecessorEpoch));
        awaitUntil(() -> committedOffset(leader(), cursorKey).equals(Option.some(COMMITTED_OFFSET)),
                   WAIT_BOUND_MS,
                   "the predecessor's forwarded checkpoint is committed at " + COMMITTED_OFFSET + ", leader reads " + committedOffset(leader(), cursorKey));
        assertThat(committedOffset(node(successor), cursorKey)).as("premise: a worker's mirror never holds a cursor checkpoint")
                                                               .isEqualTo(Option.none());

        assign(successor, successorEpoch);
        var runtime = StreamTripwireSupport.component(node(successor), "streamConsumerRuntime", StreamConsumerRuntime.class);
        var resumed = new CopyOnWriteArrayList<Long>();
        var fresh = new CopyOnWriteArrayList<Long>();

        subscribe(runtime, GROUP, successorEpoch, resumed);
        subscribe(runtime, FRESH_GROUP, successorEpoch, fresh);
        awaitUntil(() -> resumed.contains((long) EVENTS - 1) && fresh.contains((long) EVENTS - 1),
                   WAIT_BOUND_MS,
                   "both groups on the successor reach the last event: resumed=" + resumed + " fresh=" + fresh);

        assertThat(resumed).as("the successor resumes at the committed cursor: no redelivery of 0..5, no skip")
                           .containsExactlyElementsOf(offsets(COMMITTED_OFFSET));
        assertThat(fresh).as("a group whose cursor the read proves ABSENT starts at the reset point (earliest)")
                         .containsExactlyElementsOf(offsets(0L));
    }

    private void publishEvents(NodeId owner) {
        var router = StreamTripwireSupport.component(node(owner), "streamWriteRouter", StreamWriteRouter.class);

        IntStream.range(0, EVENTS)
                 .forEach(event -> assertThat(StreamTripwireSupport.publishOnceActivated(router,
                                                                                         ENGINE_KEY,
                                                                                         ("e" + event).getBytes(StandardCharsets.UTF_8))
                                                                   .isSuccess()).as("publish of event %d is acknowledged", event)
                                                                                .isTrue());
    }

    private NodeId startedWorker() {
        var added = cluster.addWorkerNode().await(START_BOUND);

        assertThat(added.isSuccess()).as("the worker process starts").isTrue();
        var workerId = added.unwrap();

        awaitUntil(node(workerId)::isReady, WAIT_BOUND_MS, workerId.id() + " reports ready");

        return workerId;
    }

    /// The assignment the declarative manager would commit, written through the leader.
    private void assign(NodeId assignee, Epoch epoch) {
        var clock = StreamTripwireSupport.component(leader(), "hlcClock", HlcClock.class);
        var key = AetherKey.ConsumerAssignmentKey.consumerAssignmentKey(ENGINE_KEY, PARTITION, GROUP);
        var record = ConsumerAssignmentValue.consumerAssignmentValue(assignee, epoch, 1L, clock.now());
        var written = leader().<Object> apply(List.of(new KVCommand.Put<AetherKey, AetherValue>(key, record)))
                              .await(COMMIT_BOUND);

        assertThat(written.isSuccess()).as("assignment of %s to %s committed: %s", GROUP, assignee.id(), written).isTrue();
        awaitUntil(() -> node(assignee).kvStore()
                                       .getTyped(key, ConsumerAssignmentValue.class)
                                       .filter(value -> value.assignee().equals(assignee))
                                       .isPresent(),
                   WAIT_BOUND_MS,
                   assignee.id() + " sees its own assignment");
    }

    /// The checkpoint the predecessor's cursor store publishes, sent through that worker's own cluster `apply` (forwarded).
    private void commitFromWorker(NodeId worker, AetherKey.StreamCursorCheckpointKey key, AssignmentToken token) {
        var written = node(worker).<Object> apply(List.of(ClusterCursorStore.checkpointCommand(key,
                                                                                              COMMITTED_OFFSET,
                                                                                              token,
                                                                                              RewindEpoch.NONE)))
                                  .await(COMMIT_BOUND);

        assertThat(written.isSuccess()).as("the worker's forwarded checkpoint is applied: %s", written).isTrue();
    }

    private static void subscribe(StreamConsumerRuntime runtime, String group, Epoch epoch, List<Long> delivered) {
        var fence = new ConsumerFence() {
            @Override
            public Epoch epoch() {
                return epoch;
            }

            @Override
            public boolean admitted() {
                return true;
            }
        };
        var result = runtime.subscribe(ENGINE_KEY,
                                       PARTITION,
                                       ConsumerConfig.consumerConfig(group),
                                       (offset, _, _) -> record(delivered, offset),
                                       IdlePolicy.KEEP_UNTIL_UNSUBSCRIBED,
                                       fence);

        assertThat(result.isSuccess()).as("subscribe %s: %s", group, result).isTrue();
    }

    private static Promise<Unit> record(List<Long> delivered, long offset) {
        delivered.add(offset);

        return Promise.unitPromise();
    }

    private static List<Long> offsets(long from) {
        return LongStream.range(from, EVENTS)
                         .boxed()
                         .toList();
    }

    private static Option<Long> committedOffset(AetherNode node, AetherKey.StreamCursorCheckpointKey key) {
        return node.kvStore()
                   .getTyped(key, StreamCursorCheckpointValue.class)
                   .map(StreamCursorCheckpointValue::committedOffset);
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
