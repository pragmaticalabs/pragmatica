// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// #1278: the life fence applies to the REAL stream config value, not only to the fence's own test type — while a life
/// of a stream name is committed, a config Put carrying another life is refused in the applier; after the removal
/// applied, the new life commits.
class StreamConfigLifeFenceTest {
    private static final StreamConfigKey KEY = StreamConfigKey.streamConfigKey("orders");

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
    void put_anotherLifeWhileOneIsCommitted_isRefused_andCommitsAfterTheRemoval() {
        put(life(11));
        put(life(22));

        assertThat(committedIncarnation()).as("the committed life keeps the name").isEqualTo(11L);

        store.process(store.createBatch(List.of(new KVCommand.Remove<AetherKey>(KEY))));
        put(life(22));

        assertThat(committedIncarnation()).as("a new life commits once the old one's removal applied").isEqualTo(22L);
    }

    private void put(StreamConfigValue value) {
        store.process(store.createBatch(List.of(new KVCommand.Put<AetherKey, AetherValue>(KEY, value))));
    }

    private long committedIncarnation() {
        return store.get(KEY)
                    .map(value -> ((StreamConfigValue) value).config().incarnation())
                    .or(-1L);
    }

    private static StreamConfigValue life(long incarnation) {
        return StreamConfigValue.streamConfigValue(StreamConfig.streamConfig("orders").withIncarnation(incarnation));
    }
}
