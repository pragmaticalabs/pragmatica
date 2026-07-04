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
/// (push-on-event), resolves each cell against the method -> artifact -> global -> baseline scope
/// hierarchy, preserves the register-before-seed load/put race fix, and that deregister drops the live
/// reference so a later KV-update cannot touch an unloaded injection point. Absence-default posture
/// (variant C): a cell with no config at any scope resolves to the counting baseline, not identity; an
/// explicit `allOff()` config resolves to the identity singleton (deliberate darkening); a non-off config
/// composes the counting strategy (embryonic metrics facet, increment 3). Swaps are observed both by
/// reference (overwriting a planted sentinel / switching identity <-> non-identity) and by behaviour (a
/// call through the cell increments the registry-visible counter).
class ObservabilityConfigRegistryTest {
    private static final String ARTIFACT = "com.example:my-slice";
    private static final String METHOD = "handle";
    private static final String WILDCARD = "*";
    private static final String KEY = ARTIFACT + "/" + METHOD;

    private KVStore<AetherKey, AetherValue> kvStore;
    private ObservabilityConfigRegistry registry;

    @BeforeEach
    void setUp() {
        kvStore = kvStoreStub();
        registry = ObservabilityConfigRegistry.observabilityConfigRegistry(null, kvStore);
    }

    @Test
    void register_seedsCell_atBaselineStrategy_countingByDefault() {
        var cell = ObservabilityStrategyCell.observabilityStrategyCell(ARTIFACT, METHOD);

        registry.register(cell);

        // Absence-default posture: no config at any scope resolves to the counting baseline, not identity.
        assertThat(cell.strategy()).isNotSameAs(InvocationStrategy.IDENTITY);
        assertThat(registry.getConfig(ARTIFACT, METHOD).allOff()).isTrue();
        assertThat(registry.invocationCount(ARTIFACT, METHOD)).isEqualTo(Option.some(0L));

        cell.around(() -> Promise.success("x")).await();

        assertThat(registry.invocationCount(ARTIFACT, METHOD)).isEqualTo(Option.some(1L));
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
        // Non-off config composes the counting strategy: the sentinel-free cell now holds a non-identity
        // strategy (its behaviour is exercised directly in onObservabilityConfigPut_withNonOffConfig_*).
        assertThat(cell.strategy()).isNotSameAs(InvocationStrategy.IDENTITY);
    }

    @Test
    void register_afterPut_seedsFromLastKnownSnapshot() {
        registry.onObservabilityConfigPut(put(true, false, true, false, 5));

        var cell = ObservabilityStrategyCell.observabilityStrategyCell(ARTIFACT, METHOD);
        cell.swap(sentinel());
        registry.register(cell);
        var stored = registry.getConfig(ARTIFACT, METHOD);

        // The seed read the last-known (non-off) config and swapped the sentinel out for its strategy.
        assertThat(cell.strategy()).isNotSameAs(InvocationStrategy.IDENTITY);
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
    void onObservabilityConfigRemove_swapsLiveCellToBaseline() {
        var cell = ObservabilityStrategyCell.observabilityStrategyCell(ARTIFACT, METHOD);
        registry.register(cell);

        registry.onObservabilityConfigPut(put(true, true, true, true, 4));
        cell.swap(sentinel());
        registry.onObservabilityConfigRemove(remove());

        // With no broader-scope config, removal falls back to the counting baseline (NOT identity): the
        // sentinel is overwritten and a call counts.
        assertThat(cell.strategy()).isNotSameAs(InvocationStrategy.IDENTITY);
        assertThat(registry.allConfigs()).doesNotContainKey(KEY);

        cell.around(() -> Promise.success("x")).await();
        assertThat(registry.invocationCount(ARTIFACT, METHOD)).isEqualTo(Option.some(1L));
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
        assertThat(cell.strategy()).isNotSameAs(InvocationStrategy.IDENTITY);
    }

    @Test
    void onObservabilityConfigPut_withNonOffConfig_swapsInCountingStrategy() {
        var cell = ObservabilityStrategyCell.observabilityStrategyCell(ARTIFACT, METHOD);
        registry.register(cell);
        var planted = sentinel();
        cell.swap(planted);

        registry.onObservabilityConfigPut(put(false, true, false, false, 0));

        // Non-off composes the counting strategy (embryonic metrics facet): the planted sentinel is
        // overwritten, the counter is planted at zero, and a call through the cell increments it.
        assertThat(cell.strategy()).isNotSameAs(planted);
        assertThat(cell.strategy()).isNotSameAs(InvocationStrategy.IDENTITY);
        assertThat(registry.invocationCount(ARTIFACT, METHOD)).isEqualTo(Option.some(0L));

        cell.around(() -> Promise.success("x")).await();

        assertThat(registry.invocationCount(ARTIFACT, METHOD)).isEqualTo(Option.some(1L));
    }

    @Test
    void invocationCount_isNone_forUnregisteredKey() {
        assertThat(registry.invocationCount(ARTIFACT, METHOD)).isEqualTo(Option.<Long>none());
    }

    @Test
    void methodScope_overridesArtifactScope_wholeSnapshot() {
        var cell = ObservabilityStrategyCell.observabilityStrategyCell(ARTIFACT, METHOD);
        registry.register(cell);

        // Artifact scope darkens (all-off -> identity); the nearer method scope re-lights (metrics).
        registry.onObservabilityConfigPut(putScope(ARTIFACT, WILDCARD, false, false, false, false, 0));
        registry.onObservabilityConfigPut(putScope(ARTIFACT, METHOD, false, true, false, false, 0));

        // Nearest scope wins whole: the method's metrics config, not a merge with the artifact all-off.
        assertThat(cell.strategy()).isNotSameAs(InvocationStrategy.IDENTITY);
        cell.around(() -> Promise.success("x")).await();
        assertThat(registry.invocationCount(ARTIFACT, METHOD)).isEqualTo(Option.some(1L));
    }

    @Test
    void artifactScope_overridesGlobalScope_wholeSnapshot() {
        var cell = ObservabilityStrategyCell.observabilityStrategyCell(ARTIFACT, METHOD);
        registry.register(cell);

        // Global scope darkens (all-off); the nearer artifact scope re-lights (metrics).
        registry.onObservabilityConfigPut(putScope(WILDCARD, WILDCARD, false, false, false, false, 0));
        registry.onObservabilityConfigPut(putScope(ARTIFACT, WILDCARD, false, true, false, false, 0));

        assertThat(cell.strategy()).isNotSameAs(InvocationStrategy.IDENTITY);
        cell.around(() -> Promise.success("x")).await();
        assertThat(registry.invocationCount(ARTIFACT, METHOD)).isEqualTo(Option.some(1L));
    }

    @Test
    void removalAtMethodScope_fallsBackToArtifactConfig_notIdentity() {
        var cell = ObservabilityStrategyCell.observabilityStrategyCell(ARTIFACT, METHOD);
        registry.register(cell);

        // Artifact scope counts; the method scope darkens on top (all-off -> identity, counter frozen).
        registry.onObservabilityConfigPut(putScope(ARTIFACT, WILDCARD, false, true, false, false, 0));
        registry.onObservabilityConfigPut(putScope(ARTIFACT, METHOD, false, false, false, false, 0));
        assertThat(cell.strategy()).isSameAs(InvocationStrategy.IDENTITY);
        cell.around(() -> Promise.success("x")).await();
        assertThat(registry.invocationCount(ARTIFACT, METHOD)).isEqualTo(Option.some(0L));

        // Removing the method-scope config re-swaps to the artifact config (counting), NOT identity.
        registry.onObservabilityConfigRemove(removeScope(ARTIFACT, METHOD));

        assertThat(cell.strategy()).isNotSameAs(InvocationStrategy.IDENTITY);
        cell.around(() -> Promise.success("x")).await();
        assertThat(registry.invocationCount(ARTIFACT, METHOD)).isEqualTo(Option.some(1L));
    }

    @Test
    void removalOfLastScope_fallsBackToBaseline_countingResumes() {
        var cell = ObservabilityStrategyCell.observabilityStrategyCell(ARTIFACT, METHOD);
        registry.register(cell);

        // The only config darkens the method (all-off -> identity, baseline suppressed).
        registry.onObservabilityConfigPut(putScope(ARTIFACT, METHOD, false, false, false, false, 0));
        assertThat(cell.strategy()).isSameAs(InvocationStrategy.IDENTITY);
        cell.around(() -> Promise.success("x")).await();
        assertThat(registry.invocationCount(ARTIFACT, METHOD)).isEqualTo(Option.some(0L));

        // Removing the last scope falls back to the BASELINE: counting resumes.
        registry.onObservabilityConfigRemove(removeScope(ARTIFACT, METHOD));

        assertThat(cell.strategy()).isNotSameAs(InvocationStrategy.IDENTITY);
        cell.around(() -> Promise.success("x")).await();
        assertThat(registry.invocationCount(ARTIFACT, METHOD)).isEqualTo(Option.some(1L));
    }

    @Test
    void unregisteredConfigCell_countsByDefault_baseline() {
        var cell = ObservabilityStrategyCell.observabilityStrategyCell(ARTIFACT, METHOD);
        registry.register(cell);

        // No config at any scope: the baseline counts by default ("off means baseline, not blind").
        cell.around(() -> Promise.success("x")).await();
        cell.around(() -> Promise.success("x")).await();

        assertThat(registry.invocationCount(ARTIFACT, METHOD)).isEqualTo(Option.some(2L));
    }

    @Test
    void explicitAllOffAtGlobalScope_darkensEverything_identity() {
        var cell = ObservabilityStrategyCell.observabilityStrategyCell(ARTIFACT, METHOD);
        registry.register(cell);
        cell.around(() -> Promise.success("x")).await();
        assertThat(registry.invocationCount(ARTIFACT, METHOD)).isEqualTo(Option.some(1L));

        // Explicit all-off at the global scope darkens every cell to identity; the counter freezes.
        registry.onObservabilityConfigPut(putScope(WILDCARD, WILDCARD, false, false, false, false, 0));

        assertThat(cell.strategy()).isSameAs(InvocationStrategy.IDENTITY);
        cell.around(() -> Promise.success("x")).await();
        cell.around(() -> Promise.success("x")).await();
        assertThat(registry.invocationCount(ARTIFACT, METHOD)).isEqualTo(Option.some(1L));
    }

    @Test
    void methodScopeNonOff_underGlobalAllOff_relightsJustThatMethod() {
        var lit = ObservabilityStrategyCell.observabilityStrategyCell(ARTIFACT, "lit");
        var dark = ObservabilityStrategyCell.observabilityStrategyCell(ARTIFACT, "dark");
        registry.register(lit);
        registry.register(dark);

        // Global all-off darkens both methods.
        registry.onObservabilityConfigPut(putScope(WILDCARD, WILDCARD, false, false, false, false, 0));
        assertThat(lit.strategy()).isSameAs(InvocationStrategy.IDENTITY);
        assertThat(dark.strategy()).isSameAs(InvocationStrategy.IDENTITY);

        // A method-scope non-off config re-lights just that one method.
        registry.onObservabilityConfigPut(putScope(ARTIFACT, "lit", false, true, false, false, 0));
        assertThat(lit.strategy()).isNotSameAs(InvocationStrategy.IDENTITY);
        assertThat(dark.strategy()).isSameAs(InvocationStrategy.IDENTITY);

        lit.around(() -> Promise.success("x")).await();
        dark.around(() -> Promise.success("x")).await();
        assertThat(registry.invocationCount(ARTIFACT, "lit")).isEqualTo(Option.some(1L));
        assertThat(registry.invocationCount(ARTIFACT, "dark")).isEqualTo(Option.some(0L));
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
        return putScope(ARTIFACT, METHOD, logging, metrics, spans, tracing, depth);
    }

    private static ValuePut<ObservabilityConfigKey, ObservabilityConfigValue> putScope(String artifactBase,
                                                                                       String methodName,
                                                                                       boolean logging,
                                                                                       boolean metrics,
                                                                                       boolean spans,
                                                                                       boolean tracing,
                                                                                       int depth) {
        var key = ObservabilityConfigKey.observabilityConfigKey(artifactBase, methodName);
        var value = ObservabilityConfigValue.observabilityConfigValue(artifactBase, methodName, logging, metrics, spans, tracing, depth);

        return new ValuePut<>(new KVCommand.Put<>(key, value), Option.none());
    }

    private static ValueRemove<ObservabilityConfigKey, ObservabilityConfigValue> remove() {
        return removeScope(ARTIFACT, METHOD);
    }

    private static ValueRemove<ObservabilityConfigKey, ObservabilityConfigValue> removeScope(String artifactBase, String methodName) {
        var key = ObservabilityConfigKey.observabilityConfigKey(artifactBase, methodName);

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
