// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConfigValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.DynamicConfigurationProvider;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/// F9 / W-11: the applied-listeners run on the KV applier thread, which holds `KVStore.mutationLock`. A listener
/// that throws must not abort the listeners behind it, and must not surface in the KV router's dispatch: the
/// overlay write it follows stands either way.
class DynamicConfigManagerListenerIsolationTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final String KEY = "database.pool_size";

    private final DynamicConfigurationProvider provider = DynamicConfigurationProvider.dynamicConfigurationProvider(ConfigurationProvider.builder()
                                                                                                                                       .build());
    private final List<String> secondSaw = new CopyOnWriteArrayList<>();
    private final DynamicConfigManager manager = DynamicConfigManager.dynamicConfigManager(null, emptyStore(), provider, SELF);

    @Test
    void put_runsTheLaterListener_whenAnEarlierOneThrows() {
        manager.onApplied(_ -> {
            throw new IllegalStateException("first listener fails");
        });
        manager.onApplied(secondSaw::add);

        assertThatCode(() -> manager.onConfigPut(put("42"))).doesNotThrowAnyException();

        assertThat(secondSaw).containsExactly(KEY);
        assertThat(provider.overlayMap()).containsEntry(KEY, "42");
    }

    @Test
    void remove_runsTheLaterListener_whenAnEarlierOneThrows() {
        manager.onConfigPut(put("42"));
        manager.onApplied(_ -> {
            throw new IllegalStateException("first listener fails");
        });
        manager.onApplied(secondSaw::add);

        assertThatCode(() -> manager.onConfigRemove(remove())).doesNotThrowAnyException();

        assertThat(secondSaw).containsExactly(KEY);
        assertThat(provider.overlayMap()).doesNotContainKey(KEY);
    }

    private static ValuePut<ConfigKey, ConfigValue> put(String value) {
        var key = ConfigKey.forKey(KEY);

        return new ValuePut<>(new KVCommand.Put<>(key, ConfigValue.configValue(KEY, value)), Option.none());
    }

    private static ValueRemove<ConfigKey, ConfigValue> remove() {
        return new ValueRemove<>(new KVCommand.Remove<>(ConfigKey.forKey(KEY), Option.none()), Option.none());
    }

    private static KVStore<AetherKey, AetherValue> emptyStore() {
        return new KVStore<>(MessageRouter.mutable(), new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        }, new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        });
    }
}
