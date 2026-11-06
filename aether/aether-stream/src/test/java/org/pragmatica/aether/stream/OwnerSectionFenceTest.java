// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import io.netty.buffer.ByteBuf;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.storage.AppendLog;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// Streams overhaul PR-2c, NT-26 on the manager (#2201 A-01): the owner-epoch FENCE is re-read inside the ordered section. A publisher at
/// E1 passes every pre-section check, then waits for the section another publish holds; inside that section the committed ownership moves
/// to E3 (the high-water advances, as observing a committed ownership Put does). When the stale publisher enters, the fence refuses it
/// before an offset exists. The provenance check cannot catch this: no E3 record was written, so the log's last epoch is still E1. Red
/// with the fence supplier of `ownerSection` replaced by an always-admit one.
@Timeout(60)
class OwnerSectionFenceTest implements ReplicationManager {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final Epoch E1 = Epoch.epoch(0, 1, 0);
    private static final Epoch E3 = Epoch.epoch(0, 3, 0);
    private static final NodeId NODE = NodeId.nodeId("node-fence").unwrap();

    @TempDir
    Path walDir;

    private final ReplicaRegistry registry = ReplicaRegistry.replicaRegistry();
    private final AtomicInteger sends = new AtomicInteger();
    private final AtomicReference<Result<Long>> stalled = new AtomicReference<>();
    private final AtomicReference<Thread> staleThread = new AtomicReference<>();
    private final OwnershipEpochHighWater highWater = OwnershipEpochHighWater.ownershipEpochHighWater(emptyStore());
    private StreamPartitionManager manager;

    @Test
    void staleEpochPublish_waitingForTheSection_isRefusedByTheFenceInside_beforeAnOffset() throws Exception {
        manager = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, this, Option.some(walDir), LastSealedOffsetSource.none());
        installHighWater();
        manager.ensureStreamMaterialized(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));

        var t0 = new Thread(() -> manager.publishLocal(STREAM, PARTITION, bytes("e1-first"), 1L, E1));

        t0.start();
        t0.join(TimeUnit.SECONDS.toMillis(30));
        staleThread.get().join(TimeUnit.SECONDS.toMillis(30));

        assertThat(stalled.get().isFailure()).as("the stale publish was refused: %s", stalled.get()).isTrue();
        stalled.get().onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.StaleEpochAppend.class));
        assertThat(manager.partitionBuffer(STREAM, PARTITION).unwrap().headOffset()).as("the refused publish took no offset").isEqualTo(0L);
        assertThat(walOffsets()).containsExactly(0L);
        manager.close();
    }

    /// Runs inside T0's section, after its WAL frame (offset 0, E1) was written.
    @Contract
    @Override
    public void replicateEvent(String streamName, int partition, long offset, byte[] payload, long timestamp, Epoch ownerEpoch) {
        if (sends.getAndIncrement() == 0) {
            moveOwnershipWhileStalePublisherWaits();
        }
    }

    @Contract
    private void moveOwnershipWhileStalePublisherWaits() {
        var stale = new Thread(() -> stalled.set(manager.publishLocal(STREAM, PARTITION, bytes("e1-stale"), 2L, E1)));

        staleThread.set(stale);
        stale.start();
        awaitBlockedOnTheSection(stale);
        highWater.onStreamPartitionOwnershipPut(ownershipPut(E3));
    }

    private static void awaitBlockedOnTheSection(Thread stale) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (stale.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(stale.getState()).as("the stale publisher waits for the section").isEqualTo(Thread.State.BLOCKED);
    }

    /// The fence-enabled factory needs a cluster node, which would add ownership checks this test does not exercise; the standalone
    /// manager gets the high-water in its place.
    private void installHighWater() throws ReflectiveOperationException {
        var field = StreamPartitionManager.class.getDeclaredField("epochHighWater");

        field.setAccessible(true);
        field.set(manager, Option.some(highWater));
    }

    private static ValuePut<StreamPartitionOwnershipKey, StreamPartitionOwnershipValue> ownershipPut(Epoch epoch) {
        var key = StreamPartitionOwnershipKey.streamPartitionOwnershipKey(STREAM, PARTITION);
        var value = StreamPartitionOwnershipValue.streamPartitionOwnershipValue(NODE, epoch, 1L, HlcTimestamp.ZERO);

        return new ValuePut<>(new Put<>(key, value), Option.none());
    }

    private List<Long> walOffsets() throws Exception {
        var records = new ArrayList<AppendLog.WalRecord>();

        GatedWalFsync.walOf(manager, STREAM, PARTITION).replay(-1L, records::add).onFailure(cause -> fail(cause.message()));
        return records.stream().map(AppendLog.WalRecord::offset).toList();
    }

    @Override
    public void handleAck(ReplicationMessage.ReplicateAck ack) {}

    @Override
    public ReplicaRegistry registry() {
        return registry;
    }

    @Override
    public Promise<Unit> awaitReplication(String streamName, int partition, long offset, int minAcks) {
        return Promise.unitPromise();
    }

    @Override
    public long replicatedThrough(String streamName, int partition, int minAcks) {
        return Long.MAX_VALUE;
    }

    @Override
    public long replicatedThrough(ReplicationMessage.ReplicateAck pending, int minAcks) {
        return Long.MAX_VALUE;
    }

    @Contract
    @Override
    public void observeAcks(AckObserver observer) {}

    private static byte[] bytes(String text) {
        return text.getBytes(UTF_8);
    }

    private static KVStore<AetherKey, AetherValue> emptyStore() {
        return new KVStore<>(MessageRouter.mutable(), TO_STRING_BYTES, UNUSED_DESERIALIZER);
    }

    private static final Serializer TO_STRING_BYTES = new Serializer() {
        @Override
        public <T> void write(ByteBuf byteBuf, T object) {
            byteBuf.writeBytes(String.valueOf(object).getBytes(UTF_8));
        }
    };

    private static final Deserializer UNUSED_DESERIALIZER = new Deserializer() {
        @Override
        public <T> T read(ByteBuf byteBuf) {
            throw new UnsupportedOperationException("unused");
        }
    };
}
