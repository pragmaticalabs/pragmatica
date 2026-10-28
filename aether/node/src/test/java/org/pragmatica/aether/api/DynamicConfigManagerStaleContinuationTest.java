// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.List;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConfigValue;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.DynamicConfigurationProvider;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// F4 / W-9: the overlay is written by the commit's own `onConfigPut` (inside `KVStore.mutate`, before the apply
/// promise resolves). `setConfig`'s continuation used to write the same value again, after the fact: if a later
/// commit B was applied between A's apply and A's continuation, the continuation put A's value over B's, silently
/// (it notifies nobody). The continuation must not touch the overlay.
class DynamicConfigManagerStaleContinuationTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final String KEY = "database.pool_size";

    private final DynamicConfigurationProvider provider = DynamicConfigurationProvider.dynamicConfigurationProvider(ConfigurationProvider.builder()
                                                                                                                                       .build());

    @Test
    void setConfig_continuation_doesNotOverwriteALaterCommit() {
        var applyA = Promise.<List<Object>> promise();
        var manager = managerApplying(applyA);

        manager.setConfig(KEY, "A");
        manager.onConfigPut(put("A"));
        manager.onConfigPut(put("B"));
        applyA.succeed(List.of());

        assertThat(manager.overrides()).as("B was committed after A; A's continuation must not resurrect A")
                                       .containsEntry(KEY, "B");
    }

    @Test
    void setConfig_continuation_leavesTheOverlayToTheCommit() {
        var applyA = Promise.<List<Object>> promise();
        var manager = managerApplying(applyA);

        manager.setConfig(KEY, "A");
        applyA.succeed(List.of());

        assertThat(manager.overrides()).as("only a commit's own onConfigPut writes the overlay").isEmpty();
    }

    @Test
    void setNodeConfig_continuation_leavesTheOverlayToTheCommit() {
        var applyA = Promise.<List<Object>> promise();
        var manager = managerApplying(applyA);

        manager.setNodeConfig(KEY, "A", SELF);
        applyA.succeed(List.of());

        assertThat(manager.overrides()).as("only a commit's own onConfigPut writes the overlay").isEmpty();
    }

    @Test
    void removeConfig_continuation_leavesTheOverlayToTheCommit() {
        var applyA = Promise.<List<Object>> promise();
        var manager = managerApplying(applyA);

        manager.onConfigPut(put("B"));
        manager.removeConfig(KEY);
        applyA.succeed(List.of());

        assertThat(manager.overrides()).as("only a commit's own onConfigRemove drops the key").containsEntry(KEY, "B");
    }

    @Test
    void removeNodeConfig_continuation_leavesTheOverlayToTheCommit() {
        var applyA = Promise.<List<Object>> promise();
        var manager = managerApplying(applyA);

        manager.onConfigPut(put("B"));
        manager.removeNodeConfig(KEY, SELF);
        applyA.succeed(List.of());

        assertThat(manager.overrides()).as("only a commit's own onConfigRemove drops the key").containsEntry(KEY, "B");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private DynamicConfigManager managerApplying(Promise<List<Object>> apply) {
        var node = (RabiaNode<KVCommand<AetherKey>>) mock(RabiaNode.class);

        when(node.apply(anyList())).thenReturn((Promise) apply);

        return DynamicConfigManager.dynamicConfigManager(node, emptyStore(), provider, SELF);
    }

    private static ValuePut<ConfigKey, ConfigValue> put(String value) {
        var key = ConfigKey.forKey(KEY);

        return new ValuePut<>(new KVCommand.Put<>(key, ConfigValue.configValue(KEY, value)), Option.none());
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
