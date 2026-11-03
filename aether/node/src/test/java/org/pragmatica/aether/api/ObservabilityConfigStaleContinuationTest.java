// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.List;

import org.pragmatica.aether.slice.ObservabilityStrategyCell;
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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

/// #2616 (the DynamicConfigManager F4/W-9 hazard): the configs map and the live cells are written by the commit's own
/// notification handler. `setConfig`/`removeConfig` used to write them again in the apply continuation, so a later
/// commit B absorbed between A's commit and A's continuation was overwritten by A, and the live cell re-swapped to A's
/// strategy. The continuation must not touch either.
@Timeout(30)
class ObservabilityConfigStaleContinuationTest {
    private static final String ARTIFACT = "com.example:stale";
    private static final String METHOD = "handle";

    @Test
    void setConfig_continuation_doesNotOverwriteALaterCommit() {
        var applyA = Promise.<List<Object>> promise();
        var registry = registryApplying(applyA);

        registry.setConfig(ARTIFACT, METHOD, true, false, false, false, 1);
        registry.onObservabilityConfigPut(put(true, false, false, false, 1));
        registry.onObservabilityConfigPut(put(false, true, false, true, 7));
        applyA.succeed(List.of());
        var stored = registry.getConfig(ARTIFACT, METHOD);

        assertThat(stored.depth()).as("B was committed after A; A's continuation must not resurrect A").isEqualTo(7);
        assertThat(stored.metrics()).isTrue();
        assertThat(stored.logging()).isFalse();
    }

    @Test
    void setConfig_continuation_doesNotReswapTheLiveCellToAnOlderCommit() {
        var applyA = Promise.<List<Object>> promise();
        var registry = registryApplying(applyA);
        var cell = ObservabilityStrategyCell.observabilityStrategyCell(ARTIFACT, METHOD);

        registry.register(cell);
        registry.setConfig(ARTIFACT, METHOD, true, false, false, false, 1);
        registry.onObservabilityConfigPut(put(true, false, false, false, 1));
        registry.onObservabilityConfigPut(put(false, true, false, true, 7));
        var strategyAfterB = cell.strategy();
        applyA.succeed(List.of());

        assertThat(cell.strategy()).as("the continuation must not re-resolve the cell").isSameAs(strategyAfterB);
    }

    @Test
    void removeConfig_continuation_doesNotRemoveALaterCommit() {
        var applyA = Promise.<List<Object>> promise();
        var registry = registryApplying(applyA);

        registry.onObservabilityConfigPut(put(true, false, false, false, 1));
        registry.removeConfig(ARTIFACT, METHOD);
        registry.onObservabilityConfigRemove(remove());
        registry.onObservabilityConfigPut(put(false, true, false, true, 7));
        applyA.succeed(List.of());

        assertThat(registry.allConfigs()).as("B re-set the key after A's removal; A's continuation must not drop it")
                                         .containsKey(ARTIFACT + "/" + METHOD);
        assertThat(registry.getConfig(ARTIFACT, METHOD).depth()).isEqualTo(7);
    }

    @Test
    void removeConfig_continuation_doesNotReswapTheLiveCell() {
        var applyA = Promise.<List<Object>> promise();
        var registry = registryApplying(applyA);
        var cell = ObservabilityStrategyCell.observabilityStrategyCell(ARTIFACT, METHOD);

        registry.register(cell);
        registry.onObservabilityConfigPut(put(true, false, false, false, 1));
        registry.removeConfig(ARTIFACT, METHOD);
        registry.onObservabilityConfigRemove(remove());
        registry.onObservabilityConfigPut(put(false, true, false, true, 7));
        var strategyAfterB = cell.strategy();
        applyA.succeed(List.of());

        assertThat(cell.strategy()).as("the removal continuation must not re-resolve the cell").isSameAs(strategyAfterB);
    }

    @Test
    void setConfig_continuation_leavesTheMapToTheCommit() {
        var applyA = Promise.<List<Object>> promise();
        var registry = registryApplying(applyA);

        registry.setConfig(ARTIFACT, METHOD, true, false, false, false, 1);
        applyA.succeed(List.of());

        assertThat(registry.allConfigs()).as("only a commit's own onObservabilityConfigPut writes the map").isEmpty();
    }

    @Test
    void removeConfig_continuation_leavesTheMapToTheCommit() {
        var applyA = Promise.<List<Object>> promise();
        var registry = registryApplying(applyA);

        registry.onObservabilityConfigPut(put(true, false, false, false, 1));
        registry.removeConfig(ARTIFACT, METHOD);
        applyA.succeed(List.of());

        assertThat(registry.allConfigs()).as("only a commit's own onObservabilityConfigRemove drops the key")
                                         .containsKey(ARTIFACT + "/" + METHOD);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ObservabilityConfigRegistry registryApplying(Promise<List<Object>> apply) {
        var node = (RabiaNode<KVCommand<AetherKey>>) Mockito.mock(RabiaNode.class);

        Mockito.when(node.apply(Mockito.anyList())).thenReturn((Promise) apply);

        return ObservabilityConfigRegistry.observabilityConfigRegistry(node,
                                                                       (KVStore<AetherKey, AetherValue>) Mockito.mock(KVStore.class));
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
        return new ValueRemove<>(new KVCommand.Remove<>(ObservabilityConfigKey.observabilityConfigKey(ARTIFACT, METHOD)),
                                 Option.none());
    }
}
