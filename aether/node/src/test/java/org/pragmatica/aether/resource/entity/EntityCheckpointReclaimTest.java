// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.StreamEntityLogSubstrate;
import org.pragmatica.aether.node.StreamEntityLogSubstrate.CheckpointReclaim;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.EntityCheckpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.EntityFoldCheckpointValue;
import org.pragmatica.aether.stream.EvictionListener;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.Retry;
import org.pragmatica.lang.utils.Retry.BackoffStrategy;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.MetadataStore;
import org.pragmatica.storage.StorageInstance;
import org.pragmatica.storage.StorageTier;
import org.pragmatica.storage.TierLevel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.node.StreamEntityLogSubstrate.CheckpointReclaim.checkpointReclaim;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// Streams PR-3c (spec 6.4 item 1, I-15): a checkpoint writer reclaims, through `StorageInstance#delete`, the block its confirmed pointer
/// swap displaced once a grace period has passed (NT-61), deletes its own new block at once when the fenced pointer put was refused (NT-62),
/// and gives every block an identity of its own, `(keyspace, partition, throughOffset)` in the frame header, so no two pointers can name one
/// block. The header is a flag day for the block layout: a build without it must read a new block as "no usable checkpoint".
///
/// The second tier records every put and delete and stands in for the shared DHT tier; a delete it refuses is the write quorum being
/// unreachable.
class EntityCheckpointReclaimTest {
    private static final String KEYSPACE = "orders";
    private static final long ONE_GB = 1024L * 1024 * 1024;
    private static final TimeSpan GRACE = TimeSpan.timeSpan(2).seconds();
    private static final TimeSpan LONG_GRACE = TimeSpan.timeSpan(1).hours();
    private static final int DELETE_ATTEMPTS = 3;
    private static final Retry FAST_RETRY = Retry.retry()
                                                 .attempts(DELETE_ATTEMPTS)
                                                 .strategy(BackoffStrategy.fixed().interval(TimeSpan.timeSpan(50).millis()));
    private static final byte[] LINEAGE = "a recorded lineage".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OLD_MARKER = "LNGCKPT1".getBytes(StandardCharsets.US_ASCII);

    private StreamPartitionManager manager;
    private RecordingTier shared;
    private StorageInstance storage;
    private KVStore<AetherKey, AetherValue> kv;

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager(64L * 1024 * 1024, EvictionListener.NOOP, ReplicationManager.NONE, Option.none(), (_, _) -> -1L);
        shared = new RecordingTier();
        storage = StorageInstance.storageInstance("entity-ckpt",
                                                  List.of(MemoryTier.memoryTier(ONE_GB), shared),
                                                  MetadataStore.inMemoryMetadataStore("entity-ckpt"));
        kv = new KVStore<>(MessageRouter.mutable(), noopSerializer(), noopDeserializer());
    }

    @AfterEach
    void tearDown() {
        manager.close();
        storage.shutdown();
    }

    /// NT-61: the block the replaced pointer named is deleted after the grace, and not before; the block the new pointer names is kept.
    @Test
    void aConfirmedSwap_deletesTheDisplacedBlock_onceTheGraceHasPassed_andNotBefore() {
        var substrate = substrate(checkpointReclaim(GRACE, FAST_RETRY));
        var displaced = save(substrate, 0, 1L, snapshot("v1"), Option.none());
        var current = save(substrate, 0, 2L, snapshot("v2"), Option.none());

        assertThat(current).as("control: the pointer moved to a new block").isNotEqualTo(displaced);
        pause(GRACE.millis() / 2);
        assertThat(readable(displaced)).as("not before the grace has passed").isTrue();
        eventually("the displaced block is deleted once the grace has passed", 15_000L, () -> !readable(displaced));
        assertThat(shared.deletes).as("deleted through StorageInstance#delete, which reaches the shared tier").contains(displaced);
        assertThat(readable(current)).as("the block the pointer names is kept").isTrue();
        assertThat(pointed(0)).isEqualTo(current);
    }

    /// NT-62: a put the fenced applier refused (a higher claim is already committed) deletes the writer's own new block at once, and
    /// nothing else: the block the committed pointer names stays.
    @Test
    void aRefusedPointerPut_deletesOnlyTheWritersOwnBlock_atOnce() {
        var substrate = substrate(checkpointReclaim(LONG_GRACE, FAST_RETRY));
        var committed = save(substrate, 0, 5L, snapshot("v5"), Option.none());

        save(substrate, 0, 2L, snapshot("v2"), Option.none());
        var own = shared.puts.getLast();

        assertThat(pointed(0)).as("control: the lower claim was refused").isEqualTo(committed);
        assertThat(own).as("control: the refused save wrote a block of its own").isNotEqualTo(committed);
        eventually("the refused writer's own block is deleted at once (the grace is an hour)", 5_000L, () -> !readable(own));
        assertThat(readable(committed)).as("the block the committed pointer names is untouched").isTrue();
        assertThat(shared.deletes).as("nothing but the writer's own block is deleted").containsOnly(own);
    }

    /// The flag day's compatibility condition (spec 6.4 Migration): a block in the new layout, read by the rc5 reader, is "no usable
    /// checkpoint" on every path an rc5 fold takes: the rc5 frame reader finds no lineage (a lineage-recording substrate discards the
    /// checkpoint) and the rc5 snapshot decoder refuses the first byte (any substrate discards it).
    @Test
    void aNewBlock_readByTheRc5Reader_isNoUsableCheckpoint() {
        var substrate = substrate(checkpointReclaim(LONG_GRACE, FAST_RETRY));
        var withLineage = save(substrate, 0, 3L, snapshot("v3"), Option.some(LINEAGE));
        var withoutLineage = save(substrate, 1, 3L, snapshot("v3"), Option.none());

        assertThat(List.of(withLineage, withoutLineage)).allSatisfy(id -> {
            var old = rc5Unframe(3L, block(id));

            assertThat(old.lineage().isEmpty()).as("the rc5 frame reader finds no lineage").isTrue();
            assertThat(EntityFoldSnapshot.decode(old.snapshot()).isFailure()).as("the rc5 snapshot decoder refuses the block").isTrue();
        });
        var loaded = substrate.loadCheckpoint(KEYSPACE, 0).await().unwrap().unwrap();

        assertThat(loaded.snapshot()).as("control: this build reads its own block").isEqualTo(snapshot("v3"));
        assertThat(loaded.lineage().map(Arrays::toString).or("none")).isEqualTo(Arrays.toString(LINEAGE));
        assertThat(EntityFoldSnapshot.decode(loaded.snapshot()).isSuccess()).isTrue();
    }

    /// NT-63's identity clause, at component level: byte-identical snapshots (with and without a lineage) in two partitions, or at two
    /// claims, are different blocks, so a delete by one pointer cannot remove another pointer's block.
    @Test
    void identicalSnapshots_inTwoPartitionsOrAtTwoClaims_areDifferentBlocks() {
        var substrate = substrate(checkpointReclaim(LONG_GRACE, FAST_RETRY));
        var bytes = snapshot("same");

        assertThat(save(substrate, 0, 4L, bytes, Option.none())).as("empty lineage, two partitions").isNotEqualTo(save(substrate, 1, 4L, bytes, Option.none()));
        assertThat(save(substrate, 2, 4L, bytes, Option.some(LINEAGE))).as("same lineage, two partitions").isNotEqualTo(save(substrate, 3, 4L, bytes, Option.some(LINEAGE)));
        assertThat(save(substrate, 4, 4L, bytes, Option.none())).as("one partition, two claims").isNotEqualTo(save(substrate, 4, 5L, bytes, Option.none()));
    }

    /// A delete the shared tier refuses (its quorum unreachable) is retried, and the block leaves once the store accepts.
    @Test
    void aRefusedDelete_isRetried_untilTheStoreAcceptsIt() {
        var substrate = substrate(checkpointReclaim(TimeSpan.timeSpan(0).millis(), FAST_RETRY));
        var displaced = save(substrate, 0, 1L, snapshot("v1"), Option.none());

        shared.refuseDeletes.set(DELETE_ATTEMPTS - 1);
        save(substrate, 0, 2L, snapshot("v2"), Option.none());
        eventually("the displaced block is deleted after the refusals", 5_000L, () -> !readable(displaced));
        assertThat(shared.deletes.stream().filter(displaced::equals).count()).as("one refused attempt per refusal, then the accepted one")
                                                                           .isEqualTo(DELETE_ATTEMPTS);
    }

    /// The retry is bounded: a delete the store keeps refusing stops after the bound, and the block is left (a leak, logged), never
    /// retried for ever.
    @Test
    void aDeleteTheStoreKeepsRefusing_stopsAfterTheBound() {
        var substrate = substrate(checkpointReclaim(TimeSpan.timeSpan(0).millis(), FAST_RETRY));
        var displaced = save(substrate, 0, 1L, snapshot("v1"), Option.none());

        shared.refuseDeletes.set(Integer.MAX_VALUE);
        save(substrate, 0, 2L, snapshot("v2"), Option.none());
        eventually("the bound is spent", 5_000L, () -> shared.deletes.size() >= DELETE_ATTEMPTS);
        pause(500L);
        assertThat(shared.deletes).as("exactly the bound, then no more").hasSize(DELETE_ATTEMPTS).containsOnly(displaced);
    }

    private EntityLogSubstrate substrate(CheckpointReclaim reclaim) {
        return StreamEntityLogSubstrate.streamEntityLogSubstrate(manager,
                                                                 (_, _) -> new StreamPartitionManager.ReplicaCatchupSource.CatchupView(0, true),
                                                                 null,
                                                                 new SegmentIndex(),
                                                                 EvictionListener.NOOP,
                                                                 storage,
                                                                 kv,
                                                                 commands -> {
                                                                     kv.process(kv.createBatch(commands));

                                                                     return Promise.success(List.of());
                                                                 },
                                                                 reclaim);
    }

    /// Saves, and answers the block the committed pointer names afterwards.
    private BlockId save(EntityLogSubstrate substrate, int partition, long through, byte[] snapshot, Option<byte[]> lineage) {
        assertThat(substrate.saveCheckpoint(KEYSPACE, partition, through, snapshot, lineage).await().isSuccess()).as("the save resolves").isTrue();

        return pointed(partition);
    }

    private BlockId pointed(int partition) {
        var pointer = kv.getTyped(EntityCheckpointKey.entityCheckpointKey(KEYSPACE, partition), EntityFoldCheckpointValue.class)
                        .or(() -> fail("no pointer for partition " + partition));

        return BlockId.fromHex(pointer.blockIdHex()).unwrap();
    }

    private boolean readable(BlockId id) {
        return storage.get(id).await().unwrap().isPresent();
    }

    private byte[] block(BlockId id) {
        return storage.get(id).await().unwrap().or(() -> fail("block " + id + " is not readable"));
    }

    private static byte[] snapshot(String value) {
        return EntityFoldSnapshot.encode(Map.of("k", value.getBytes(StandardCharsets.UTF_8)), Map.of());
    }

    /// The rc5 reader, as `CheckpointFrame#unframe` was at `ffc0b6260d`: a block that does not start with `LNGCKPT1` is the snapshot alone,
    /// with no lineage.
    private static EntityLogSubstrate.EntityCheckpoint rc5Unframe(long throughOffset, byte[] block) {
        if (block.length < OLD_MARKER.length + Integer.BYTES || !Arrays.equals(block, 0, OLD_MARKER.length, OLD_MARKER, 0, OLD_MARKER.length)) {
            return EntityLogSubstrate.EntityCheckpoint.entityCheckpoint(throughOffset, block);
        }

        var buffer = ByteBuffer.wrap(block, OLD_MARKER.length, block.length - OLD_MARKER.length);
        var length = buffer.getInt();

        if (length < 0 || length > buffer.remaining()) {
            return EntityLogSubstrate.EntityCheckpoint.entityCheckpoint(throughOffset, block);
        }

        var identity = new byte[length];

        buffer.get(identity);
        var snapshot = new byte[buffer.remaining()];

        buffer.get(snapshot);

        return EntityLogSubstrate.EntityCheckpoint.entityCheckpoint(throughOffset, snapshot, Option.some(identity));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static void eventually(String what, long budgetMs, BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + budgetMs;

        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }

            pause(20L);
        }

        fail("not within " + budgetMs + " ms: " + what);
    }

    @SuppressWarnings("JBCT-EX-01")
    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted", e);
        }
    }

    /// A shared tier that records every put and delete; `refuseDeletes` refusals are answered first, as a DHT remove that missed its
    /// write quorum.
    private static final class RecordingTier implements StorageTier {
        private final StorageTier delegate = MemoryTier.memoryTier(ONE_GB);
        final List<BlockId> puts = new CopyOnWriteArrayList<>();
        final List<BlockId> deletes = new CopyOnWriteArrayList<>();
        final AtomicInteger refuseDeletes = new AtomicInteger();

        @Override
        public Promise<Option<byte[]>> get(BlockId id) {
            return delegate.get(id);
        }

        @Override
        public Promise<Unit> put(BlockId id, byte[] content) {
            puts.add(id);

            return delegate.put(id, content);
        }

        @Override
        public Promise<Unit> delete(BlockId id) {
            deletes.add(id);

            return refuseDeletes.getAndDecrement() > 0
                   ? Causes.cause("write quorum unreachable").promise()
                   : delegate.delete(id);
        }

        @Override
        public Promise<Boolean> exists(BlockId id) {
            return delegate.exists(id);
        }

        @Override
        public TierLevel level() {
            return TierLevel.REMOTE;
        }

        @Override
        public long usedBytes() {
            return delegate.usedBytes();
        }

        @Override
        public long maxBytes() {
            return delegate.maxBytes();
        }

        @Override
        public boolean isShared() {
            return true;
        }
    }

    private static Serializer noopSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer noopDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
