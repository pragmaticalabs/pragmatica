// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.pragmatica.aether.slice.kvstore.AetherValue.EpochStart;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.CommittedStreamOwnerSource;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.WalBackedStreamPartitionManagers;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.storage.AppendLog;
import org.pragmatica.utility.warning.OperatorWarningSink;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateEvents.replicateEvents;
import static org.pragmatica.aether.stream.replication.ReplicationReceiveHandler.replicationReceiveHandler;

/// v2735d window (b), #2599: the ring publishes a replicated record's head BEFORE its WAL frame is written and recorded
/// (`OffHeapRingBuffer.appendOrderedAt`: `appendLocked`, then `inOrder` → `logReplicated` → `recordReplicatedWrite`), and the head
/// is read lock-free. A released deferred ack must not cover a record in that window: the release's barrier syncs only the last
/// RECORDED write. Deterministic: the test holds the partition WAL's write section, so the receive thread is parked with offset 6
/// in the ring and its frame unwritten while a verification runs the release. Real: the WAL ([AppendLog] over a temp dir), the
/// [StreamPartitionManager] (append, head, sync, gate) and the [ReplicationReceiveHandler]. Stubbed: the network and the
/// committed ownership record.
class ReleasedAckSyncCoverTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final long BOOT_TOKEN = 7L;
    private static final Epoch E1 = Epoch.epoch(1L, 1L, 1L);
    private static final Epoch E2 = Epoch.epoch(1L, 1L, 2L);
    private static final long E2_START = 3L;
    private static final NodeId NODE_A = NodeId.nodeId("node-a").unwrap();
    private static final NodeId NODE_B = NodeId.nodeId("node-b").unwrap();
    private static final List<NodeId> MEMBERS = List.of(NODE_A, NODE_B);

    private final NodeId owner = ReplicaPlacement.rank(STREAM, PARTITION, MEMBERS).getFirst();
    private final NodeId self = owner.equals(NODE_A) ? NODE_B : NODE_A;
    private final AtomicReference<Epoch> committed = new AtomicReference<>(E1);
    private final ConcurrentLinkedQueue<ReplicationMessage.ReplicateAck> acks = new ConcurrentLinkedQueue<>();
    private final CopyOnWriteArrayList<AppendLog> logs = new CopyOnWriteArrayList<>();

    @TempDir
    Path dir;

    private StreamPartitionManager replica;
    private ReplicationReceiveHandler handler;

    @BeforeEach
    void setUp() {
        var base = AppendLog.Opener.directory(dir);

        replica = WalBackedStreamPartitionManagers.overLogs(name -> base.open(name).onSuccess(logs::add));
        replica.createStream(StreamConfig.streamConfig(STREAM));
        replica.ownerEpochSource((_, _) -> committed.get());
        replica.placementRoleSupplier((_, _) -> Role.REPLICA);
        replica.epochStarts((_, _) -> List.of(EpochStart.epochStart(E2, E2_START, Option.none())));
        handler = replicationReceiveHandler(self,
                                            replica::appendRecovered,
                                            replica::nextExpectedOffset,
                                            AcceptingReplicationTransport.accepting((_, message) -> acks.add((ReplicationMessage.ReplicateAck) message)),
                                            (_, _) -> {},
                                            replica::syncReplicated,
                                            (_, _) -> Option.some(new CommittedStreamOwnerSource.CommittedOwner(owner, committed.get())),
                                            OperatorWarningSink.handingOffTo(_ -> {}),
                                            () -> BOOT_TOKEN);
        // The production wiring (StreamAssembly).
        handler.ackGate(replica::replicaVerified);
        replica.onEpochVerified(handler::releaseDeferredAck);
    }

    @AfterEach
    void tearDown() {
        replica.close();
    }

    /// The copy holds 0..4 under E1 and E2 (started at 3) is committed, so the ack of offset 5 is deferred. Offset 6 is then
    /// applied on another thread, which parks in the WAL write with 6 in the ring. The release that a verification runs in that
    /// window acks the deferred offset 5, which the receive thread wrote and recorded before deferring it. The rule that acked
    /// the head read before the barrier sent 6 here (acks = [6] at b1e668b2cd).
    @Test
    void probeV2735d_releaseNeverAcksARecordWhoseWalFrameIsNotWritten() throws Exception {
        IntStream.range(0, 5).forEach(i -> replica.appendRecovered(STREAM, PARTITION, i, payload(i), 1000L + i, E1).unwrap());
        replica.syncReplicated(STREAM, PARTITION).await();
        committed.set(E2);
        assertThat(replica.replicaVerified(STREAM, PARTITION)).as("head 4 is at or above E2's start 3").isFalse();
        handler.onReplicateEvents(liveBatch(5));
        assertThat(acks).as("deferred").isEmpty();
        assertThat(logs).as("the partition WAL is open").isNotEmpty();

        var receive = new Thread(() -> handler.onReplicateEvents(liveBatch(6)), "receive");
        var parked = new AtomicBoolean();
        var sentInTheWindow = new AtomicReference<List<Long>>(List.of());

        holding(writeLocks(), 0, () -> releaseInTheWindow(receive, parked, sentInTheWindow));
        receive.join(TimeUnit.SECONDS.toMillis(10));

        assertThat(parked.get()).as("receive thread parked with offset 6 in the ring and its WAL frame unwritten").isTrue();
        assertThat(sentInTheWindow.get()).as("the release acks the deferred offset only, never 6 before its frame is written")
                                         .containsExactly(5L);
    }

    /// Runs with every WAL write section held: starts the receive of offset 6, waits until it is in the ring and the thread is
    /// blocked on the WAL, then verifies the copy (which runs the release) and records what was acked meanwhile.
    private void releaseInTheWindow(Thread receive, AtomicBoolean parked, AtomicReference<List<Long>> sentInTheWindow) {
        receive.start();
        parked.set(eventually(() -> replica.nextExpectedOffset(STREAM, PARTITION) == 7L
                                    && receive.getState() == Thread.State.BLOCKED));

        if (parked.get()) {
            replica.markVerifiedForEpoch(STREAM, PARTITION, E2);
            eventually(() -> !acks.isEmpty());
            sentInTheWindow.set(acks.stream().map(ReplicationMessage.ReplicateAck::confirmedOffset).toList());
        }
    }

    private List<Object> writeLocks() throws ReflectiveOperationException {
        var field = writeLockField();
        var locks = new ArrayList<Object>();

        for (var log : logs) {
            locks.add(field.get(log));
        }

        return locks;
    }

    private static Field writeLockField() throws ReflectiveOperationException {
        var field = AppendLog.class.getDeclaredField("writeLock");

        field.setAccessible(true);

        return field;
    }

    private static void holding(List<Object> locks, int index, Runnable body) {
        if (index == locks.size()) {
            body.run();

            return;
        }

        synchronized (locks.get(index)) {
            holding(locks, index + 1, body);
        }
    }

    private static boolean eventually(BooleanSupplier condition) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        return condition.getAsBoolean();
    }

    private ReplicationMessage.ReplicateEvents liveBatch(int offset) {
        return replicateEvents(owner, STREAM, PARTITION, offset, List.of(payload(offset)), List.of(1000L + offset), E2);
    }

    private static byte[] payload(int i) {
        return ("event-" + i).getBytes(UTF_8);
    }
}
