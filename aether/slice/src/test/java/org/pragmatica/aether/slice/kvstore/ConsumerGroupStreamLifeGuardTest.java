// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConsumerGroupKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerGroupTombstone;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerGroupValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// #3041: every consumer-group key (assignment and tombstone) is guarded by its stream's committed config, in the
/// applier. A write is admitted only while the stream is committed and the write names that very life, so every record
/// of a dead stream life is refusable at the store and removing it later reopens nothing (Rabia may commit a timed-out
/// batch late and without bound, #2629, so an older Put can land on an absent key; the guard refuses it there).
///
/// Failure inputs: (c) a late older-epoch write on a live stream is refused (the epoch fence); (d) a write minted under
/// life L that commits after the stream was deleted, or deleted and recreated as L', is refused; (f) a write for a
/// stream that was never created is refused; (g) the control: a write of the live life is admitted.
class ConsumerGroupStreamLifeGuardTest {
    private static final String STREAM = "orders";
    private static final StreamConfigKey CONFIG_KEY = StreamConfigKey.streamConfigKey(STREAM);
    private static final ConsumerGroupKey GROUP_KEY = ConsumerGroupKey.consumerGroupKey("g", STREAM, 0);
    private static final NodeId NODE = new NodeId("node-a");

    private final KVStore<AetherKey, AetherValue> store = new KVStore<>(MessageRouter.mutable(), new Serializer() {
        @Override
        public <T> void write(ByteBuf buffer, T value) {}
    }, new Deserializer() {
        @Override
        public <T> T read(ByteBuf buffer) {
            return null;
        }
    });

    @Test
    void put_olderEpochOnLiveStream_isRefusedByTheEpochFence_c() {
        createStream(11);
        put(GROUP_KEY, tombstone(5, 11));
        put(GROUP_KEY, assignment(3, 11));

        assertThat(committedEpoch()).as("the tombstone's epoch still fences the older join").isEqualTo(5L);
        assertThat(store.get(GROUP_KEY).or((AetherValue) null)).isInstanceOf(ConsumerGroupTombstone.class);
    }

    @Test
    void put_afterStreamDeleted_isRefused_d() {
        createStream(11);
        deleteStream();
        put(GROUP_KEY, assignment(9, 11));

        assertThat(store.get(GROUP_KEY).isPresent()).as("a join minted under the dead life must not commit").isFalse();
    }

    @Test
    void put_tombstoneAfterStreamDeleted_isRefused_d() {
        createStream(11);
        deleteStream();
        put(GROUP_KEY, tombstone(9, 11));

        assertThat(store.get(GROUP_KEY).isPresent()).as("a leave minted under the dead life must not commit").isFalse();
    }

    @Test
    void put_afterStreamRecreatedAsNewLife_isRefused_d() {
        createStream(11);
        deleteStream();
        createStream(22);
        put(GROUP_KEY, assignment(9, 11));

        assertThat(store.get(GROUP_KEY).isPresent()).as("a join of life 11 must not commit onto life 22").isFalse();
    }

    @Test
    void put_forStreamNeverCreated_isRefused_f() {
        put(GROUP_KEY, assignment(1, 11));

        assertThat(store.get(GROUP_KEY).isPresent()).as("no committed stream, no consumer-group record").isFalse();
    }

    @Test
    void put_ofTheLiveLife_isAdmitted_g() {
        createStream(11);
        put(GROUP_KEY, assignment(1, 11));
        put(GROUP_KEY, tombstone(2, 11));

        assertThat(store.get(GROUP_KEY).or((AetherValue) null)).as("join then leave on a live stream commits both")
                                                                .isInstanceOf(ConsumerGroupTombstone.class);
        assertThat(committedEpoch()).isEqualTo(2L);
    }

    private void createStream(long incarnation) {
        put(CONFIG_KEY, StreamConfigValue.streamConfigValue(StreamConfig.streamConfig(STREAM).withIncarnation(incarnation)));
    }

    private void deleteStream() {
        store.process(store.createBatch(List.of(new KVCommand.Remove<AetherKey>(CONFIG_KEY))));
    }

    private void put(AetherKey key, AetherValue value) {
        store.process(store.createBatch(List.of(new KVCommand.Put<AetherKey, AetherValue>(key, value))));
    }

    private long committedEpoch() {
        return store.get(GROUP_KEY)
                    .map(ConsumerGroupStreamLifeGuardTest::counterOf)
                    .or(-1L);
    }

    private static long counterOf(AetherValue value) {
        return switch (value) {
            case ConsumerGroupValue assignment -> assignment.epoch().localCounter();
            case ConsumerGroupTombstone tombstone -> tombstone.epoch().localCounter();
            default -> -1L;
        };
    }

    /// The life token is the stream incarnation the record was written under; at base the value carries none, so the
    /// parameter is ignored until the guard commit adds it.
    private static ConsumerGroupValue assignment(long counter, long life) {
        return ConsumerGroupValue.consumerGroupValue(NODE, "c1", Epoch.epoch(1L, 1L, counter));
    }

    private static ConsumerGroupTombstone tombstone(long counter, long life) {
        return ConsumerGroupTombstone.consumerGroupTombstone(Epoch.epoch(1L, 1L, counter));
    }
}
