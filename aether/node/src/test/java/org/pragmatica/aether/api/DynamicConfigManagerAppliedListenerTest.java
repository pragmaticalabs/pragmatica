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


/// #381: the trigger of the runtime config-change push. A committed `ConfigKey` put or remove that
/// this node honours is applied to the overlay provider and THEN reported to the applied-listeners
/// with the dotted key — so a listener that reads through the provider (the slices' facades do) sees
/// the new value. A key scoped to another node is neither applied nor reported.
class DynamicConfigManagerAppliedListenerTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final NodeId OTHER = NodeId.nodeId("other").unwrap();

    private final DynamicConfigurationProvider provider = DynamicConfigurationProvider.dynamicConfigurationProvider(ConfigurationProvider.builder()
                                                                                                                                       .build());
    private final List<String> seen = new CopyOnWriteArrayList<>();
    private final List<Option<String>> valueAtNotification = new CopyOnWriteArrayList<>();
    // The consensus node is only reached by setConfig/removeConfig, which this test never calls.
    private final DynamicConfigManager manager = DynamicConfigManager.dynamicConfigManager(null, emptyStore(), provider, SELF);

    @Test
    void clusterWidePut_isAppliedToTheOverlay_thenReportedWithTheKey() {
        manager.onApplied(key -> {
            seen.add(key);
            valueAtNotification.add(Option.option(provider.overlayMap().get(key)));
        });

        manager.onConfigPut(put(ConfigKey.forKey("database.pool_size"), "42"));

        assertThat(seen).containsExactly("database.pool_size");
        assertThat(valueAtNotification).as("the overlay already holds the value when the listener runs")
                                       .containsExactly(Option.some("42"));
    }

    @Test
    void remove_isReportedAfterTheOverlayDroppedTheKey() {
        manager.onConfigPut(put(ConfigKey.forKey("database.pool_size"), "42"));
        manager.onApplied(key -> valueAtNotification.add(Option.option(provider.overlayMap().get(key))));

        manager.onConfigRemove(new ValueRemove<>(new KVCommand.Remove<>(ConfigKey.forKey("database.pool_size"), Option.none()),
                                                 Option.none()));

        assertThat(valueAtNotification).containsExactly(Option.none());
    }

    @Test
    void putScopedToAnotherNode_isNeitherAppliedNorReported() {
        manager.onApplied(seen::add);

        manager.onConfigPut(put(ConfigKey.forKey("database.pool_size", OTHER), "42"));

        assertThat(seen).isEmpty();
        assertThat(provider.overlayMap()).doesNotContainKey("database.pool_size");
    }

    private static ValuePut<ConfigKey, ConfigValue> put(ConfigKey key, String value) {
        return new ValuePut<>(new KVCommand.Put<>(key, ConfigValue.configValue(key.key(), value)), Option.none());
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
