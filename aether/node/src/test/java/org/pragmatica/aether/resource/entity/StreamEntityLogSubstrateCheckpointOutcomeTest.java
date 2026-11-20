// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import io.netty.buffer.ByteBuf;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.StreamEntityLogSubstrate;
import org.pragmatica.aether.resource.entity.EntityLogSubstrate.CheckpointLoad;
import org.pragmatica.aether.resource.entity.EntityLogSubstrate.UnreadableCause;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.EntityCheckpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.EntityFoldCheckpointValue;
import org.pragmatica.aether.stream.EvictionListener;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.MetadataStore;
import org.pragmatica.storage.StorageInstance;
import org.pragmatica.testlogging.LogCapture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #3303 A0b (I-LOST, T-A4 (a), (a'), (b)): `loadCheckpointOutcome` answers `Loaded`, `Absent` or `Unreadable(cause)` and never "none" for a
/// block a committed pointer names. The legacy `loadCheckpoint` keeps its collapse to none (pinned by `EntityCheckpointReclaimTest`).
class StreamEntityLogSubstrateCheckpointOutcomeTest {
    private static final String KEYSPACE = "orders";
    private static final long ONE_GB = 1024L * 1024 * 1024;

    private StreamPartitionManager manager;
    private StorageInstance storage;
    private KVStore<AetherKey, AetherValue> kv;
    private LogCapture warns;

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager(64L * 1024 * 1024, EvictionListener.NOOP, ReplicationManager.NONE, Option.none(), (_, _) -> -1L);
        storage = StorageInstance.storageInstance("outcome", List.of(MemoryTier.memoryTier(ONE_GB)), MetadataStore.inMemoryMetadataStore("outcome"));
        kv = new KVStore<>(MessageRouter.mutable(), noopSerializer(), noopDeserializer());
        warns = LogCapture.capture(StreamEntityLogSubstrate.class, Level.WARN, true);
    }

    @AfterEach
    void tearDown() {
        warns.close();
        manager.close();
        storage.shutdown();
    }

    /// (a): the pointer names a block and the storage read FAILS: `Unreadable(FETCH_FAILED)`, WARN naming the block.
    @Test
    void aFailedFetch_isUnreadableFetchFailed_andWarns() {
        var blockId = blockIdOf("never stored");

        point(0, blockId.hexString());

        var outcome = substrate(failingGets(storage)).loadCheckpointOutcome(KEYSPACE, 0).await().unwrap();

        assertThat(outcome).isEqualTo(CheckpointLoad.unreadable(UnreadableCause.FETCH_FAILED, blockId.hexString()));
        assertThat(warns.messages()).as("WARN with the block id").anyMatch(m -> m.contains(blockId.hexString()));
    }

    /// (a'): the pointer names a block storage answers "none" for: `Unreadable(BLOCK_LOST)`, WARN naming the block.
    @Test
    void aBlockStorageAnswersNoneFor_isUnreadableBlockLost_andWarns() {
        var blockId = blockIdOf("never stored");

        point(0, blockId.hexString());

        var outcome = substrate(storage).loadCheckpointOutcome(KEYSPACE, 0).await().unwrap();

        assertThat(outcome).isEqualTo(CheckpointLoad.unreadable(UnreadableCause.BLOCK_LOST, blockId.hexString()));
        assertThat(warns.messages()).as("WARN with the block id").anyMatch(m -> m.contains(blockId.hexString()));
    }

    /// (b): the pointer's block id is not hex: `Unreadable(UNDECODABLE)`.
    @Test
    void aPointerThatIsNotHex_isUnreadableUndecodable() {
        point(0, "not-a-hex-block-id");

        var outcome = substrate(storage).loadCheckpointOutcome(KEYSPACE, 0).await().unwrap();

        assertThat(outcome).isEqualTo(CheckpointLoad.unreadable(UnreadableCause.UNDECODABLE, "not-a-hex-block-id"));
        assertThat(warns.messages()).as("WARN with the block id").anyMatch(m -> m.contains("not-a-hex-block-id"));
    }

    @Test
    void noPointer_isAbsent() {
        var outcome = substrate(storage).loadCheckpointOutcome(KEYSPACE, 0).await().unwrap();

        assertThat(outcome).isEqualTo(CheckpointLoad.absent());
        assertThat(warns.messages()).as("an absent checkpoint is not a warning").isEmpty();
    }

    @Test
    void aReadableCheckpoint_isLoaded() {
        var substrate = substrate(storage);
        var snapshot = EntityFoldSnapshot.encode(Map.of("k", "v".getBytes(StandardCharsets.UTF_8)), Map.of());

        assertThat(substrate.saveCheckpoint(KEYSPACE, 0, 7L, snapshot, Option.none()).await().isSuccess()).as("control: the save resolves").isTrue();
        var outcome = substrate.loadCheckpointOutcome(KEYSPACE, 0).await().unwrap();

        assertThat(outcome).isInstanceOfSatisfying(CheckpointLoad.Loaded.class,
                                                   loaded -> {
                                                       assertThat(loaded.checkpoint().throughOffset()).isEqualTo(7L);
                                                       assertThat(loaded.checkpoint().snapshot()).isEqualTo(snapshot);
                                                   });
    }

    /// The legacy answer stays collapsed (the `[guess]` of the brief): same lost block, `loadCheckpoint` is none.
    @Test
    void theLegacyLoad_stillCollapsesALostBlockToNone() {
        point(0, blockIdOf("never stored").hexString());

        assertThat(substrate(storage).loadCheckpoint(KEYSPACE, 0).await().unwrap().isEmpty()).isTrue();
    }

    private static BlockId blockIdOf(String content) {
        return BlockId.blockId(content.getBytes(StandardCharsets.UTF_8)).unwrap();
    }

    private void point(int partition, String blockIdHex) {
        var command = new KVCommand.Put<AetherKey, AetherValue>(EntityCheckpointKey.entityCheckpointKey(KEYSPACE, partition),
                                                                EntityFoldCheckpointValue.entityFoldCheckpointValue(1L, blockIdHex));

        kv.process(kv.createBatch(List.of(command)));
    }

    private EntityLogSubstrate substrate(StorageInstance blocks) {
        return StreamEntityLogSubstrate.streamEntityLogSubstrate(manager,
                                                                 (_, _) -> new StreamPartitionManager.ReplicaCatchupSource.CatchupView(0, true),
                                                                 null,
                                                                 new SegmentIndex(),
                                                                 EvictionListener.NOOP,
                                                                 blocks,
                                                                 kv,
                                                                 commands -> {
                                                                     kv.process(kv.createBatch(commands));
                                                                     return Promise.success(List.of());
                                                                 });
    }

    /// The real storage, except that every `get` fails (the shared tier being unreachable).
    private static StorageInstance failingGets(StorageInstance real) {
        return (StorageInstance) Proxy.newProxyInstance(StorageInstance.class.getClassLoader(),
                                                        new Class<?>[]{StorageInstance.class},
                                                        (_, method, args) -> invoke(real, method, args));
    }

    private static Object invoke(StorageInstance real, java.lang.reflect.Method method, Object[] args) throws Throwable {
        if (method.getName().equals("get")) {
            return Causes.cause("shared tier unreachable").promise();
        }

        try {
            return method.invoke(real, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
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
