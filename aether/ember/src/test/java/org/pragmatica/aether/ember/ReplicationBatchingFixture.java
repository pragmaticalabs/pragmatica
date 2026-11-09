// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.stream.StreamWriteRouter;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.StreamTripwireSupport.PARTITION;
import static org.pragmatica.aether.ember.StreamTripwireSupport.engineKey;
import static org.pragmatica.aether.ember.StreamTripwireSupport.sleepQuietly;

/// Shared C-04 fixture (#263): a three-node Ember cluster whose nodes count, through their inbound fault filter (a pure
/// observer: it always lets the message through), the `ReplicateEvents` messages they receive for one stream; 16
/// concurrent callers make single publishes through the owner's real `StreamWriteRouter`, and one owner-local
/// `publishBatch` is the positive control. Subclasses choose the cluster: [EmberReplicationBatchingTest] without a WAL
/// (gated), [EmberReplicationBatchingWalMeasurementTest] with one (measured only).
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class ReplicationBatchingFixture {
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan PUBLISH_BOUND = TimeSpan.timeSpan(30).seconds();
    private static final long ISR_BUDGET_MS = 90_000L;
    private static final int CALLERS = 16;
    private static final int PER_CALLER = 12;
    static final int SINGLE_PUBLISHES = CALLERS * PER_CALLER;
    static final int BATCH = 100;
    private static final String STREAM = "c04";
    private static final String ENGINE_KEY = engineKey(STREAM);

    private final Map<String, AtomicLong> replicateMessages = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> replicatedEvents = new ConcurrentHashMap<>();

    private EmberCluster cluster;
    private AetherNode owner;
    private AetherNode replica;
    private Throwable bootFailure;

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            assertThat(cluster.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped")).isEqualTo("stopped");
        }
    }

    record Traffic(List<Long> messagesPerReplica, List<Long> eventsPerReplica) {}

    void boot() {
        if (bootFailure != null) {
            throw new AssertionError("the shared cluster failed to boot for an earlier test: " + bootFailure, bootFailure);
        }

        if (owner != null) {
            return;
        }

        try {
            bootCluster();
        } catch (Throwable failure) {
            bootFailure = failure;

            throw failure;
        }
    }

    private void bootCluster() {
        cluster = EmberTestPorts.startedCluster(StreamTripwireSupport.PORTS, this::threeNodes, START_BOUND);
        StreamTripwireSupport.awaitLeader(cluster);
        StreamTripwireSupport.createStream(cluster.allNodes().getFirst(), StreamTripwireSupport.smallRingStream(STREAM, 1, 100_000L, 3_600_000L));

        var record = StreamTripwireSupport.awaitFullIsr(cluster, ENGINE_KEY, ISR_BUDGET_MS);

        owner = cluster.getNode(record.owner().id()).unwrap();
        replica = cluster.allNodes().stream().filter(node -> !node.self().equals(owner.self())).findFirst().orElseThrow();
        cluster.allNodes().forEach(this::observe);
    }

    /// A pure observer on every node's inbound path: it counts and records, and lets every message through.
    private void observe(AetherNode node) {
        var id = node.self().id();

        replicateMessages.put(id, new AtomicLong());
        replicatedEvents.put(id, new AtomicLong());
        node.setInboundFaultFilter((_, message) -> observed(id, message));
    }

    private boolean observed(String nodeId, Object message) {
        if (message instanceof ReplicationMessage.ReplicateEvents replicate && ENGINE_KEY.equals(replicate.streamName())) {
            replicateMessages.get(nodeId).incrementAndGet();
            replicatedEvents.get(nodeId).addAndGet(replicate.payloads().size());
        }

        return true;
    }

    Traffic publishSinglesConcurrently(int count) {
        var before = snapshot();
        var router = StreamTripwireSupport.component(owner, "streamWriteRouter", StreamWriteRouter.class);
        try (var pool = Executors.newFixedThreadPool(CALLERS)) {
            var futures = IntStream.range(0, CALLERS)
                                   .mapToObj(caller -> pool.submit(() -> publishRun(router, caller, PER_CALLER)))
                                   .toList();

            futures.forEach(StreamTripwireSupport::joinQuietly);
        }
        sleepQuietly(1_000L);

        return deltaSince(before);
    }

    private void publishRun(StreamWriteRouter router, int caller, int count) {
        for (var i = 0; i < count; i++) {
            var offset = StreamTripwireSupport.publishOnceActivated(router, ENGINE_KEY, ("single-" + caller + "-" + i).getBytes(StandardCharsets.UTF_8));

            assertThat(offset.isSuccess()).as("single publish %d/%d: %s", caller, i, offset).isTrue();
        }
    }

    Traffic publishOneBatch(int count) {
        var before = snapshot();
        var router = StreamTripwireSupport.component(owner, "streamWriteRouter", StreamWriteRouter.class);
        var payloads = IntStream.range(0, count).mapToObj(i -> ("batch-" + i).getBytes(StandardCharsets.UTF_8)).toList();
        var outcomes = router.publishBatch(ENGINE_KEY, PARTITION, payloads, System.currentTimeMillis()).await(PUBLISH_BOUND);

        assertThat(outcomes.isSuccess()).as("batch publish: %s", outcomes).isTrue();
        sleepQuietly(1_000L);

        return deltaSince(before);
    }

    private Map<String, long[]> snapshot() {
        var snapshot = new ConcurrentHashMap<String, long[]>();

        replicateMessages.forEach((id, messages) -> snapshot.put(id, new long[] {messages.get(), replicatedEvents.get(id).get()}));

        return snapshot;
    }

    private Traffic deltaSince(Map<String, long[]> before) {
        var replicas = cluster.allNodes().stream().filter(node -> !node.self().equals(owner.self())).map(node -> node.self().id()).toList();

        return new Traffic(replicas.stream().map(id -> replicateMessages.get(id).get() - before.get(id)[0]).toList(),
                           replicas.stream().map(id -> replicatedEvents.get(id).get() - before.get(id)[1]).toList());
    }

    /// The partition's owner, once [#boot] ran.
    AetherNode ownerNode() {
        return owner;
    }

    /// The cluster under measurement; the subclass decides whether its nodes have a WAL.
    abstract EmberCluster threeNodes(int basePort);
}
