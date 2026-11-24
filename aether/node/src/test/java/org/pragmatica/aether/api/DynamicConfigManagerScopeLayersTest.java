// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

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

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;


/// #2756: a cluster-wide value and this node's node-scoped override for one dotted key are two layers, not one
/// overlay entry. The effective value is the node-scoped one if present, else the cluster-wide one, whatever the
/// arrival order; a remove clears only its own layer. The same holds for the initial KV load, whose iteration
/// order is unspecified (`KVStore.snapshot()` is a `Map.copyOf`), so the load tests drive both orders explicitly.
class DynamicConfigManagerScopeLayersTest {
    private static final String KEY = "database.pool_size";
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final NodeId OTHER = NodeId.nodeId("other").unwrap();

    private final DynamicConfigurationProvider provider = DynamicConfigurationProvider.dynamicConfigurationProvider(ConfigurationProvider.builder()
                                                                                                                                       .build());
    private final List<String> seen = new CopyOnWriteArrayList<>();
    private final DynamicConfigManager manager = DynamicConfigManager.dynamicConfigManager(null, emptyStore(), provider, SELF);

    @Test
    void removingTheNodeOverride_keepsTheClusterWideValue() {
        manager.onConfigPut(put(ConfigKey.forKey(KEY), "cluster"));
        manager.onConfigPut(put(ConfigKey.forKey(KEY, SELF), "node"));

        manager.onConfigRemove(remove(ConfigKey.forKey(KEY, SELF)));

        assertThat(provider.overlayMap()).containsEntry(KEY, "cluster");
    }

    @Test
    void removingTheClusterWideValue_keepsTheNodeOverride() {
        manager.onConfigPut(put(ConfigKey.forKey(KEY), "cluster"));
        manager.onConfigPut(put(ConfigKey.forKey(KEY, SELF), "node"));

        manager.onConfigRemove(remove(ConfigKey.forKey(KEY)));

        assertThat(provider.overlayMap()).containsEntry(KEY, "node");
    }

    @Test
    void clusterPutAfterNodePut_keepsTheNodeValue() {
        manager.onConfigPut(put(ConfigKey.forKey(KEY, SELF), "node"));

        manager.onConfigPut(put(ConfigKey.forKey(KEY), "cluster"));

        assertThat(provider.overlayMap()).containsEntry(KEY, "node");
    }

    @Test
    void nodePutAfterClusterPut_winsOverTheClusterValue() {
        manager.onConfigPut(put(ConfigKey.forKey(KEY), "cluster"));

        manager.onConfigPut(put(ConfigKey.forKey(KEY, SELF), "node"));

        assertThat(provider.overlayMap()).containsEntry(KEY, "node");
    }

    @Test
    void removingBothLayers_dropsTheKeyFromTheOverlay() {
        manager.onConfigPut(put(ConfigKey.forKey(KEY), "cluster"));
        manager.onConfigPut(put(ConfigKey.forKey(KEY, SELF), "node"));

        manager.onConfigRemove(remove(ConfigKey.forKey(KEY, SELF)));
        manager.onConfigRemove(remove(ConfigKey.forKey(KEY)));

        assertThat(provider.overlayMap()).doesNotContainKey(KEY);
    }

    @Test
    void removingTheNodeOverrideWithNoClusterValue_dropsTheKey() {
        manager.onConfigPut(put(ConfigKey.forKey(KEY, SELF), "node"));

        manager.onConfigRemove(remove(ConfigKey.forKey(KEY, SELF)));

        assertThat(provider.overlayMap()).doesNotContainKey(KEY);
    }

    @Test
    void removingTheNodeOverride_reportsTheKeyWithTheClusterValueAlreadyInTheOverlay() {
        var valueAtNotification = new CopyOnWriteArrayList<Option<String>>();
        manager.onConfigPut(put(ConfigKey.forKey(KEY), "cluster"));
        manager.onConfigPut(put(ConfigKey.forKey(KEY, SELF), "node"));
        manager.onApplied(key -> valueAtNotification.add(Option.option(provider.overlayMap().get(key))));

        manager.onConfigRemove(remove(ConfigKey.forKey(KEY, SELF)));

        assertThat(valueAtNotification).containsExactly(Option.some("cluster"));
    }

    @Test
    void nodeScopedToAnotherNode_isNotALayerOnThisNode() {
        manager.onConfigPut(put(ConfigKey.forKey(KEY), "cluster"));

        manager.onConfigPut(put(ConfigKey.forKey(KEY, OTHER), "other"));
        manager.onConfigRemove(remove(ConfigKey.forKey(KEY, OTHER)));

        assertThat(provider.overlayMap()).containsEntry(KEY, "cluster");
    }

    @Test
    void load_nodeScopedEntryFirst_nodeScopedWins() {
        var loaded = loadedFrom(ConfigKey.forKey(KEY, SELF), "node", ConfigKey.forKey(KEY), "cluster");

        assertThat(loaded.overlay).containsEntry(KEY, "node");
    }

    @Test
    void load_clusterEntryFirst_nodeScopedWins() {
        var loaded = loadedFrom(ConfigKey.forKey(KEY), "cluster", ConfigKey.forKey(KEY, SELF), "node");

        assertThat(loaded.overlay).containsEntry(KEY, "node");
    }

    @Test
    void load_thenRemovingTheNodeOverride_fallsBackToTheLoadedClusterValue() {
        var loaded = loadedFrom(ConfigKey.forKey(KEY, SELF), "node", ConfigKey.forKey(KEY), "cluster");

        loaded.manager.onConfigRemove(remove(ConfigKey.forKey(KEY, SELF)));

        assertThat(loaded.provider.overlayMap()).containsEntry(KEY, "cluster");
    }

    @Test
    void load_entryScopedToAnotherNode_isIgnored() {
        var loaded = loadedFrom(ConfigKey.forKey(KEY, OTHER), "other", ConfigKey.forKey(KEY), "cluster");

        assertThat(loaded.overlay).containsEntry(KEY, "cluster");
    }

    private record Loaded(DynamicConfigManager manager, DynamicConfigurationProvider provider, Map<String, String> overlay) {
        static Loaded loaded(DynamicConfigManager manager, DynamicConfigurationProvider provider) {
            return new Loaded(manager, provider, provider.overlayMap());
        }
    }

    /// A manager constructed over a store that yields the two entries in the given order: the load path under test.
    private static Loaded loadedFrom(ConfigKey firstKey, String firstValue, ConfigKey secondKey, String secondValue) {
        var loadedProvider = DynamicConfigurationProvider.dynamicConfigurationProvider(ConfigurationProvider.builder()
                                                                                                           .build());
        var loadedManager = DynamicConfigManager.dynamicConfigManager(null,
                                                                      storeYielding(firstKey, firstValue, secondKey, secondValue),
                                                                      loadedProvider,
                                                                      SELF);

        return Loaded.loaded(loadedManager, loadedProvider);
    }

    @SuppressWarnings("unchecked")
    private static KVStore<AetherKey, AetherValue> storeYielding(ConfigKey firstKey,
                                                                 String firstValue,
                                                                 ConfigKey secondKey,
                                                                 String secondValue) {
        KVStore<AetherKey, AetherValue> store = Mockito.mock(KVStore.class);

        Mockito.doAnswer(invocation -> deliver(invocation.getArgument(2), firstKey, firstValue, secondKey, secondValue))
               .when(store)
               .forEach(Mockito.eq(ConfigKey.class), Mockito.eq(ConfigValue.class), Mockito.any());

        return store;
    }

    private static Object deliver(BiConsumer<ConfigKey, ConfigValue> consumer,
                                ConfigKey firstKey,
                                String firstValue,
                                ConfigKey secondKey,
                                String secondValue) {
        consumer.accept(firstKey, ConfigValue.configValue(firstKey.key(), firstValue));
        consumer.accept(secondKey, ConfigValue.configValue(secondKey.key(), secondValue));
        return null;
    }

    private static ValuePut<ConfigKey, ConfigValue> put(ConfigKey key, String value) {
        return new ValuePut<>(new KVCommand.Put<>(key, ConfigValue.configValue(key.key(), value)), Option.none());
    }

    private static ValueRemove<ConfigKey, ConfigValue> remove(ConfigKey key) {
        return new ValueRemove<>(new KVCommand.Remove<>(key, Option.none()), Option.none());
    }

    @SuppressWarnings("unchecked")
    private static KVStore<AetherKey, AetherValue> emptyStore() {
        return Mockito.mock(KVStore.class);
    }
}
