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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.stream.StreamWriteRouter;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.StreamTripwireSupport.PARTITION;
import static org.pragmatica.aether.ember.StreamTripwireSupport.engineKey;
import static org.pragmatica.aether.ember.StreamTripwireSupport.sleepQuietly;

/// Streams overhaul tripwire, step 2 (#2201), replication transport: C-04 - `ReplicationBatcher` is not wired, so every
/// single publish sends one `ReplicateEvents` per replica.
///
/// Each node counts, through its inbound fault filter (a pure observer: it always lets the message through), the
/// `ReplicateEvents` messages it receives. 192 single publishes are made through the owner's real `StreamWriteRouter`
/// from 16 concurrent callers, so a batcher would have something to coalesce. A positive control makes one owner-local
/// `publishBatch` of 100 events, the path 1b-perf found already batched (#1245): its replicas receive a handful of messages,
/// which shows the counter can see batching.
///
/// **No data directory, on purpose.** With a WAL the ordered append section does not wait on the fsync: it writes the frame
/// and only requests the group commit (`StreamPartitionManager.writeWalFrame` -> `wal.commit(writeSeq)`, StreamPartitionManager.java:3416-3422),
/// which is awaited after the section (`awaitDurable`, :3545); the fsync is `AppendLog.commit` (AppendLog.java:375) ->
/// `groupCommit` (:734) -> one `force(false)` (:760) under `syncLock` (:745). On macOS/APFS, though, the in-section frame
/// `pwrite` stalls behind the group-commit `force` already in flight, so the per-event section time exceeds the batcher's 1 ms
/// window (`ReplicationBatcher.DEFAULT_MAX_DELAY`, ReplicationBatcher.java:63) and even a wired default batcher coalesced
/// almost nothing (one mutation run: 182 messages for 192 events); on ZFS (bigboy) the same run gives 0.2-0.3. The wiring gap
/// does not depend on the WAL, so it is measured on the ephemeral-storage node, where appends are microseconds apart.
/// - Tripwire: messages per event per replica is at least 0.9.
/// - `@Disabled` acceptance: at most 0.5 (the overhaul's batching must at least halve the frames under this load).
@PortBudget
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmberReplicationBatchingTripwireTest {
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan PUBLISH_BOUND = TimeSpan.timeSpan(30).seconds();
    private static final long ISR_BUDGET_MS = 90_000L;
    private static final int CALLERS = 16;
    private static final int PER_CALLER = 12;
    private static final int SINGLE_PUBLISHES = CALLERS * PER_CALLER;
    private static final int BATCH = 100;
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

    @Test
    @Timeout(600)
    void singlePublishes_tripwire_sendOneReplicateEventsMessagePerEventPerReplica() {
        boot();

        var burst = publishSinglesConcurrently(SINGLE_PUBLISHES);
        var control = publishOneBatch(BATCH);

        assertThat(burst.eventsPerReplica()).as("control: every replica received all %d events", SINGLE_PUBLISHES).allSatisfy(events -> assertThat(events).isEqualTo((long) SINGLE_PUBLISHES));
        assertThat(burst.messagesPerReplica()).as("C-04 fixed? Single publishes are now coalesced (messages per replica %s for %d events). "
                                                 + "Delete this tripwire and enable singlePublishes_areBatchedIntoFewerMessages (#2201)",
                                                 burst.messagesPerReplica(),
                                                 SINGLE_PUBLISHES)
                                              .allSatisfy(messages -> assertThat(messages).isGreaterThanOrEqualTo((long) (0.9 * SINGLE_PUBLISHES)));
        assertThat(control.eventsPerReplica()).as("control: every replica received the %d batched events", BATCH).allSatisfy(events -> assertThat(events).isEqualTo((long) BATCH));
        assertThat(control.messagesPerReplica()).as("control: the owner-local publishBatch is already coalesced (#1245), so the counter can see batching")
                                                .allSatisfy(messages -> assertThat(messages).isLessThanOrEqualTo(3L));
    }

    @Test
    @Disabled("C-04 (#2201): enable when single publishes are batched on the wire; RED today: one ReplicateEvents per event per replica")
    @Timeout(600)
    void singlePublishes_areBatchedIntoFewerMessages() {
        boot();

        var burst = publishSinglesConcurrently(SINGLE_PUBLISHES);

        assertThat(burst.messagesPerReplica()).as("ReplicateEvents messages per replica for %d concurrent single publishes", SINGLE_PUBLISHES)
                                              .allSatisfy(messages -> assertThat(messages).isLessThanOrEqualTo((long) (0.5 * SINGLE_PUBLISHES)));
    }

    private record Traffic(List<Long> messagesPerReplica, List<Long> eventsPerReplica) {}

    private void boot() {
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

    private Traffic publishSinglesConcurrently(int count) {
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
            var offset = router.publish(ENGINE_KEY, PARTITION, ("single-" + caller + "-" + i).getBytes(StandardCharsets.UTF_8), System.currentTimeMillis())
                               .await(PUBLISH_BOUND);

            assertThat(offset.isSuccess()).as("single publish %d/%d: %s", caller, i, offset).isTrue();
        }
    }

    private Traffic publishOneBatch(int count) {
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

    private EmberCluster threeNodes(int basePort) {
        return EmberCluster.emberCluster(StreamTripwireSupport.CLUSTER_SIZE,
                                         basePort,
                                         basePort + StreamTripwireSupport.MGMT_OFFSET,
                                         basePort + StreamTripwireSupport.APP_HTTP_OFFSET,
                                         "c04");
    }
}
