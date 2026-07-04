// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.pragmatica.aether.slice.AspectObservabilityConfig;
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
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageReceiver;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


// Write-side registry for the per-injection-point system-observability cell (#277). It owns the
// last-known AspectObservabilityConfig snapshot per injection point (keyed `artifactBase + "/" +
// methodName`) AND the live ObservabilityStrategyCell minted for each dispatch injection point. On a
// KV-update event it translates the new snapshot into an "around" strategy and swaps it wholesale into
// the live cell (push-on-event), so the per-call hot path never performs a registry lookup.
//
// Distinct from ObservabilityDepthRegistry (depth / trace-rate tuning, read-on-demand): this registry
// holds live cell references and swaps their behaviour. The dispatch seams (increment 2) call
// register(key, cell) at slice load and deregister(key) at unload.
public class ObservabilityConfigRegistry {
    private static final Logger log = LoggerFactory.getLogger(ObservabilityConfigRegistry.class);

    private final RabiaNode<KVCommand<AetherKey>> clusterNode;
    private final KVStore<AetherKey, AetherValue> kvStore;
    private final Map<String, AspectObservabilityConfig> configs = new ConcurrentHashMap<>();
    private final Map<String, ObservabilityStrategyCell> instances = new ConcurrentHashMap<>();

    private ObservabilityConfigRegistry(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                        KVStore<AetherKey, AetherValue> kvStore) {
        this.clusterNode = clusterNode;
        this.kvStore = kvStore;
    }

    public static ObservabilityConfigRegistry observabilityConfigRegistry(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                                                          KVStore<AetherKey, AetherValue> kvStore) {
        var registry = new ObservabilityConfigRegistry(clusterNode, kvStore);

        registry.loadFromKvStore();

        return registry;
    }

    private void loadFromKvStore() {
        kvStore.forEach(ObservabilityConfigKey.class, ObservabilityConfigValue.class, this::loadEntry);
        log.info("Loaded {} observability configs from KV-Store", configs.size());
    }

    private void loadEntry(ObservabilityConfigKey key, ObservabilityConfigValue value) {
        var registryKey = key.artifactBase() + "/" + key.methodName();

        configs.put(registryKey, snapshotOf(value));
        log.debug("Loaded observability config from KV-Store: {} -> logging={} metrics={} spans={} tracing={} depth={}",
                  registryKey,
                  value.logging(),
                  value.metrics(),
                  value.spans(),
                  value.tracing(),
                  value.depth());
    }

    /// Registers a live ObservabilityStrategyCell for an injection point, then seeds it with the
    /// strategy for the last-known snapshot (or OFF) so subsequent KV-update events swap its behaviour
    /// in place. Registering BEFORE seeding closes the load/put race: a concurrent KV put either finds
    /// the cell and swaps it, or lands before registration and is picked up by the seed read — both
    /// converge on the strategy for the latest snapshot.
    public Unit register(String key, ObservabilityStrategyCell cell) {
        instances.put(key, cell);
        cell.swap(strategyFor(configs.getOrDefault(key, AspectObservabilityConfig.OFF)));

        return Unit.unit();
    }

    /// Drops the live cell for an injection point (on unload) so a later KV-update does not retain it.
    public Unit deregister(String key) {
        instances.remove(key);

        return Unit.unit();
    }

    @MessageReceiver
    @Contract
    public void onObservabilityConfigPut(ValuePut<ObservabilityConfigKey, ObservabilityConfigValue> valuePut) {
        var key = valuePut.cause().key();
        var value = valuePut.cause().value();
        var registryKey = key.artifactBase() + "/" + key.methodName();
        var snapshot = snapshotOf(value);

        configs.put(registryKey, snapshot);
        Option.option(instances.get(registryKey)).onPresent(cell -> cell.swap(strategyFor(snapshot)));
        log.debug("Observability config updated from cluster: {} -> logging={} metrics={} spans={} tracing={} depth={}",
                  registryKey,
                  value.logging(),
                  value.metrics(),
                  value.spans(),
                  value.tracing(),
                  value.depth());
    }

    @MessageReceiver
    @Contract
    public void onObservabilityConfigRemove(ValueRemove<ObservabilityConfigKey, ObservabilityConfigValue> valueRemove) {
        var key = valueRemove.cause().key();
        var registryKey = key.artifactBase() + "/" + key.methodName();

        configs.remove(registryKey);
        Option.option(instances.get(registryKey)).onPresent(cell -> cell.swap(InvocationStrategy.IDENTITY));
        log.debug("Observability config removed from cluster: {}", registryKey);
    }

    @SuppressWarnings("unchecked")
    public Promise<Unit> setConfig(String artifactBase,
                                   String methodName,
                                   boolean logging,
                                   boolean metrics,
                                   boolean spans,
                                   boolean tracing,
                                   int depth) {
        var key = ObservabilityConfigKey.observabilityConfigKey(artifactBase, methodName);
        var value = ObservabilityConfigValue.observabilityConfigValue(artifactBase,
                                                                      methodName,
                                                                      logging,
                                                                      metrics,
                                                                      spans,
                                                                      tracing,
                                                                      depth);
        var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(key, value);
        var snapshot = AspectObservabilityConfig.aspectObservabilityConfig(logging, metrics, spans, tracing, depth);

        return clusterNode.<Unit> apply(List.of(command))
                          .map(_ -> applyConfig(artifactBase + "/" + methodName, snapshot))
                          .onFailure(cause -> log.error("Failed to persist observability config for {}/{}: {}",
                                                        artifactBase,
                                                        methodName,
                                                        cause.message()));
    }

    @SuppressWarnings("unchecked")
    public Promise<Unit> removeConfig(String artifactBase, String methodName) {
        var key = ObservabilityConfigKey.observabilityConfigKey(artifactBase, methodName);
        var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Remove<>(key);

        return clusterNode.<Unit> apply(List.of(command))
                          .map(_ -> removeFromRegistry(artifactBase + "/" + methodName))
                          .onFailure(cause -> log.error("Failed to persist observability config removal for {}/{}: {}",
                                                        artifactBase,
                                                        methodName,
                                                        cause.message()));
    }

    public AspectObservabilityConfig getConfig(String artifactBase, String methodName) {
        return configs.getOrDefault(artifactBase + "/" + methodName, AspectObservabilityConfig.OFF);
    }

    public Map<String, AspectObservabilityConfig> allConfigs() {
        return Map.copyOf(configs);
    }

    private Unit applyConfig(String registryKey, AspectObservabilityConfig snapshot) {
        configs.put(registryKey, snapshot);
        Option.option(instances.get(registryKey)).onPresent(cell -> cell.swap(strategyFor(snapshot)));
        log.info("Observability config set for {}: logging={} metrics={} spans={} tracing={} depth={}",
                 registryKey,
                 snapshot.logging(),
                 snapshot.metrics(),
                 snapshot.spans(),
                 snapshot.tracing(),
                 snapshot.depth());

        return Unit.unit();
    }

    private Unit removeFromRegistry(String registryKey) {
        configs.remove(registryKey);
        Option.option(instances.get(registryKey)).onPresent(cell -> cell.swap(InvocationStrategy.IDENTITY));
        log.info("Observability config removed for {}", registryKey);

        return Unit.unit();
    }

    /// Translates the last-known config for an injection point into the composed "around" strategy
    /// swapped into its cell. Placeholder with the final shape for this increment: `allOff()` configs
    /// resolve to the identity strategy (zero-cost passthrough); non-off configs resolve to identity as
    /// well until facet composition lands, so the swap seam is exercised end-to-end while per-call
    /// behaviour stays unchanged.
    private static InvocationStrategy strategyFor(AspectObservabilityConfig config) {
        if (config.allOff()) {
            return InvocationStrategy.IDENTITY;
        }

        // facet composition lands in increment 4
        return InvocationStrategy.IDENTITY;
    }

    private static AspectObservabilityConfig snapshotOf(ObservabilityConfigValue value) {
        return AspectObservabilityConfig.aspectObservabilityConfig(value.logging(),
                                                                   value.metrics(),
                                                                   value.spans(),
                                                                   value.tracing(),
                                                                   value.depth());
    }
}
