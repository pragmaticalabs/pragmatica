// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.pragmatica.aether.slice.ObservabilityStrategyCell;
import org.pragmatica.aether.slice.ObservabilityStrategyCell.InvocationStrategy;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ObservabilityConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ObservabilityConfigValue;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// #277: proves the write-side registry translates KV-sourced AspectObservabilityConfig snapshots into
/// "around" strategies and swaps them into the live per-injection-point ObservabilityStrategyCell
/// (push-on-event), preserves the register-before-seed load/put race fix, and that deregister drops the
/// live reference so a later KV-update cannot touch an unloaded injection point. While facet
/// composition is a placeholder (every config resolves to IDENTITY), swaps are made observable by
/// planting a distinct sentinel strategy and asserting whether the registry overwrote it.
class ObservabilityConfigRegistryTest {
    private static final String ARTIFACT = "com.example:my-slice";
    private static final String METHOD = "handle";
    private static final String KEY = ARTIFACT + "/" + METHOD;

    private KVStore<AetherKey, AetherValue> kvStore;
    private ObservabilityConfigRegistry registry;

    @BeforeEach
    void setUp() {
        kvStore = kvStoreStub();
        registry = ObservabilityConfigRegistry.observabilityConfigRegistry(null, kvStore);
    }

    @Test
    void register_seedsCell_atIdentityStrategy() {
        var cell = ObservabilityStrategyCell.observabilityStrategyCell(ARTIFACT, METHOD);

        registry.register(cell);

        assertThat(cell.strategy()).isSameAs(InvocationStrategy.IDENTITY);
        assertThat(registry.getConfig(ARTIFACT, METHOD).allOff()).isTrue();
    }

    @Test
    void onObservabilityConfigPut_updatesConfigSnapshot_andSwapsCell() {
        var cell = ObservabilityStrategyCell.observabilityStrategyCell(ARTIFACT, METHOD);
        registry.register(cell);
        cell.swap(sentinel());

        registry.onObservabilityConfigPut(put(true, true, false, false, 3));
        var stored = registry.getConfig(ARTIFACT, METHOD);

        assertThat(stored.logging()).isTrue();
        assertThat(stored.metrics()).isTrue();
        assertThat(stored.spans()).isFalse();
        assertThat(stored.tracing()).isFalse();
        assertThat(stored.depth()).isEqualTo(3);
        assertThat(cell.strategy()).isSameAs(InvocationStrategy.IDENTITY);
    }

    @Test
    void register_afterPut_seedsFromLastKnownSnapshot() {
        registry.onObservabilityConfigPut(put(true, false, true, false, 5));

        var cell = ObservabilityStrategyCell.observabilityStrategyCell(ARTIFACT, METHOD);
        cell.swap(sentinel());
        registry.register(cell);
        var stored = registry.getConfig(ARTIFACT, METHOD);

        // The seed read the last-known config and swapped the sentinel out for its strategy.
        assertThat(cell.strategy()).isSameAs(InvocationStrategy.IDENTITY);
        assertThat(stored.logging()).isTrue();
        assertThat(stored.spans()).isTrue();
        assertThat(stored.depth()).isEqualTo(5);
    }

    @Test
    void onObservabilityConfigPut_forUnregisteredKey_updatesConfigsWithoutThrowing() {
        registry.onObservabilityConfigPut(put(true, false, false, true, 2));
        var stored = registry.getConfig(ARTIFACT, METHOD);

        assertThat(stored.logging()).isTrue();
        assertThat(stored.tracing()).isTrue();
        assertThat(stored.depth()).isEqualTo(2);
        assertThat(registry.allConfigs()).containsKey(KEY);
    }

    @Test
    void onObservabilityConfigRemove_swapsLiveCellToIdentity() {
        var cell = ObservabilityStrategyCell.observabilityStrategyCell(ARTIFACT, METHOD);
        registry.register(cell);

        registry.onObservabilityConfigPut(put(true, true, true, true, 4));
        cell.swap(sentinel());
        registry.onObservabilityConfigRemove(remove());

        assertThat(cell.strategy()).isSameAs(InvocationStrategy.IDENTITY);
        assertThat(registry.allConfigs()).doesNotContainKey(KEY);
    }

    @Test
    void deregister_dropsLiveCell_soLaterPutDoesNotTouchIt() {
        var cell = ObservabilityStrategyCell.observabilityStrategyCell(ARTIFACT, METHOD);
        registry.register(cell);
        var planted = sentinel();
        cell.swap(planted);

        registry.deregister(cell);
        registry.onObservabilityConfigPut(put(true, true, true, true, 8));

        // The deregistered cell must keep its planted strategy: the put updates configs only.
        assertThat(cell.strategy()).isSameAs(planted);
        assertThat(registry.getConfig(ARTIFACT, METHOD).logging()).isTrue();
    }

    @Test
    void setConfig_persistsCommand_andAppliesStrategyLocally() {
        var writeRegistry = ObservabilityConfigRegistry.observabilityConfigRegistry(clusterNodeStub(), kvStore);
        var cell = ObservabilityStrategyCell.observabilityStrategyCell(ARTIFACT, METHOD);
        writeRegistry.register(cell);
        cell.swap(sentinel());

        writeRegistry.setConfig(ARTIFACT, METHOD, true, true, false, true, 6)
                     .await()
                     .onFailure(cause -> Assertions.fail(cause.message()));

        var stored = writeRegistry.getConfig(ARTIFACT, METHOD);

        assertThat(stored.logging()).isTrue();
        assertThat(stored.metrics()).isTrue();
        assertThat(stored.spans()).isFalse();
        assertThat(stored.tracing()).isTrue();
        assertThat(stored.depth()).isEqualTo(6);
        assertThat(cell.strategy()).isSameAs(InvocationStrategy.IDENTITY);
    }

    @Test
    void onObservabilityConfigPut_withNonOffConfig_stillSwapsCell() {
        var cell = ObservabilityStrategyCell.observabilityStrategyCell(ARTIFACT, METHOD);
        registry.register(cell);
        var planted = sentinel();
        cell.swap(planted);

        registry.onObservabilityConfigPut(put(true, true, true, true, 9));

        // The seam fires even while composition is a placeholder: the planted sentinel was overwritten
        // by the recomputed (identity, for now) strategy.
        assertThat(cell.strategy()).isNotSameAs(planted);
        assertThat(cell.strategy()).isSameAs(InvocationStrategy.IDENTITY);
    }

    // A distinct InvocationStrategy instance (never InvocationStrategy.IDENTITY); behaviour is
    // irrelevant, only reference identity is asserted to detect whether a swap fired.
    private static InvocationStrategy sentinel() {
        return proceed -> proceed.apply();
    }

    private static ValuePut<ObservabilityConfigKey, ObservabilityConfigValue> put(boolean logging,
                                                                                  boolean metrics,
                                                                                  boolean spans,
                                                                                  boolean tracing,
                                                                                  int depth) {
        var key = ObservabilityConfigKey.observabilityConfigKey(ARTIFACT, METHOD);
        var value = ObservabilityConfigValue.observabilityConfigValue(ARTIFACT, METHOD, logging, metrics, spans, tracing, depth);

        return new ValuePut<>(new KVCommand.Put<>(key, value), Option.none());
    }

    private static ValueRemove<ObservabilityConfigKey, ObservabilityConfigValue> remove() {
        var key = ObservabilityConfigKey.observabilityConfigKey(ARTIFACT, METHOD);

        return new ValueRemove<>(new KVCommand.Remove<>(key), Option.none());
    }

    @SuppressWarnings("unchecked")
    private static KVStore<AetherKey, AetherValue> kvStoreStub() {
        return Mockito.mock(KVStore.class);
    }

    @SuppressWarnings("unchecked")
    private static RabiaNode<KVCommand<AetherKey>> clusterNodeStub() {
        RabiaNode<KVCommand<AetherKey>> node = Mockito.mock(RabiaNode.class);

        Mockito.when(node.apply(Mockito.anyList())).thenAnswer(_ -> Promise.success(List.of(Unit.unit())));

        return node;
    }
}
