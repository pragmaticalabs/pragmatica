// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.pragmatica.aether.slice.AspectObservabilityConfig;
import org.pragmatica.aether.slice.ObservabilityCellRegistrar;
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
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageReceiver;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


// Write-side registry for the per-injection-point system-observability cell (#277). It owns the
// last-known AspectObservabilityConfig snapshot per injection point (keyed `artifactBase + "/" +
// methodName`) AND the live ObservabilityStrategyCell minted for each dispatch injection point. On a
// KV-update event it translates the new snapshot into an "around" strategy and swaps it wholesale into
// the live cell (push-on-event), so the per-call hot path never performs a registry lookup.
//
// Distinct from ObservabilityDepthRegistry (depth / trace-rate tuning, read-on-demand): this registry
// holds live cell references and swaps their behaviour. It IS the ObservabilityCellRegistrar the dispatch
// seams (increment 2) call to register(cell) at slice load / route publish and deregister(cell) at
// unload / unpublish. Multiple cells can share one injection-point key (e.g. the north-south route cell
// and the east-west bridge cell of the same method) — all are held in a per-key set and swapped together.
public class ObservabilityConfigRegistry implements ObservabilityCellRegistrar {
    private static final Logger log = LoggerFactory.getLogger(ObservabilityConfigRegistry.class);

    private final RabiaNode<KVCommand<AetherKey>> clusterNode;
    private final KVStore<AetherKey, AetherValue> kvStore;
    private final Map<String, AspectObservabilityConfig> configs = new ConcurrentHashMap<>();
    private final Map<String, Set<ObservabilityStrategyCell>> instances = new ConcurrentHashMap<>();

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

    /// Registers a live ObservabilityStrategyCell for its injection point, then seeds it with the
    /// strategy for the last-known snapshot (or OFF) so subsequent KV-update events swap its behaviour
    /// in place. Registering BEFORE seeding closes the load/put race: a concurrent KV put either finds
    /// the cell and swaps it, or lands before registration and is picked up by the seed read — both
    /// converge on the strategy for the latest snapshot. Multiple cells may share one key; each joins
    /// the key's set and all are swapped together.
    @Override
    public Unit register(ObservabilityStrategyCell cell) {
        instances.computeIfAbsent(cell.key(), _ -> ConcurrentHashMap.newKeySet())
                 .add(cell);
        cell.swap(strategyFor(cell, configs.getOrDefault(cell.key(), AspectObservabilityConfig.OFF)));

        return Unit.unit();
    }

    /// Drops the live cell for its injection point (on unload / unpublish) so a later KV-update does not
    /// retain it. The deregistered cell keeps its current strategy and works standalone — deregister only
    /// removes the registry's reference; the key's set is dropped once its last cell leaves.
    @Override
    public Unit deregister(ObservabilityStrategyCell cell) {
        Option.option(instances.get(cell.key()))
              .onPresent(set -> removeFromSet(cell.key(), set, cell));

        return Unit.unit();
    }

    private void removeFromSet(String key, Set<ObservabilityStrategyCell> set, ObservabilityStrategyCell cell) {
        set.remove(cell);

        if (set.isEmpty()) {
            instances.remove(key, set);
        }
    }

    /// Recomposes and swaps the strategy for every live cell sharing `registryKey`. Composition is
    /// per-cell (not one shared strategy) because a stateful facet — the embryonic counting metrics
    /// facet — binds to each cell's own storage slot; the north-south route cell and east-west bridge
    /// cell of one method must each count into their own AtomicLong.
    private void swapAll(String registryKey, AspectObservabilityConfig snapshot) {
        instances.getOrDefault(registryKey, Set.of())
                 .forEach(cell -> cell.swap(strategyFor(cell, snapshot)));
    }

    @MessageReceiver
    @Contract
    public void onObservabilityConfigPut(ValuePut<ObservabilityConfigKey, ObservabilityConfigValue> valuePut) {
        var key = valuePut.cause().key();
        var value = valuePut.cause().value();
        var registryKey = key.artifactBase() + "/" + key.methodName();
        var snapshot = snapshotOf(value);

        configs.put(registryKey, snapshot);
        swapAll(registryKey, snapshot);
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
        swapAll(registryKey, AspectObservabilityConfig.OFF);
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

    /// Live invocation count for an injection point — the read path of the embryonic metrics facet
    /// (#277 increment 3) for tests and ops. Sums the counters across every live cell sharing the key
    /// rather than reading one cell: when the north-south route cell and the east-west bridge cell of a
    /// method are both registered they each count into their own storage, so the sum is the honest total
    /// for the injection point. `None` when no live cell is registered for the key (e.g. after a slice
    /// unregisters and its cells are deregistered); `Some(0)` when cells are registered but the facet
    /// has never been activated (still identity) or was switched off before any call.
    public Option<Long> invocationCount(String artifactBase, String methodName) {
        return Option.option(instances.get(artifactBase + "/" + methodName))
                     .map(ObservabilityConfigRegistry::sumCounters);
    }

    private static long sumCounters(Set<ObservabilityStrategyCell> cells) {
        return cells.stream()
                    .mapToLong(ObservabilityConfigRegistry::counterValue)
                    .sum();
    }

    private static long counterValue(ObservabilityStrategyCell cell) {
        return Option.option(cell.storage().get())
                     .map(AtomicLong.class::cast)
                     .map(AtomicLong::get)
                     .or(0L);
    }

    private Unit applyConfig(String registryKey, AspectObservabilityConfig snapshot) {
        configs.put(registryKey, snapshot);
        swapAll(registryKey, snapshot);
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
        swapAll(registryKey, AspectObservabilityConfig.OFF);
        log.info("Observability config removed for {}", registryKey);

        return Unit.unit();
    }

    /// Translates the last-known config for an injection point into the composed "around" strategy
    /// swapped into `cell`. `allOff()` configs resolve to the zero-cost identity singleton (untouched
    /// passthrough); any non-off config resolves to the counting strategy — the metrics facet in
    /// embryonic form (#277 increment 3): it counts invocations into the cell's own storage slot. The
    /// facet is per-cell (hence the cell parameter, not just the config): each cell counts into its own
    /// AtomicLong, so the two seams of one method never share a counter. Full facet composition
    /// (logging / spans / tracing bodies, facet layering) lands in increment 4.
    private static InvocationStrategy strategyFor(ObservabilityStrategyCell cell, AspectObservabilityConfig config) {
        if (config.allOff()) {
            return InvocationStrategy.IDENTITY;
        }

        return countingStrategy(cell.storage());
    }

    /// Composes the counting strategy once, here at swap time (never per call). Plants an AtomicLong on
    /// first activation — `compareAndSet(null, ...)` keeps any prior counter across re-activation cycles,
    /// so a metrics -> off -> metrics flip resumes the count rather than resetting it — captures it, and
    /// returns a closure that increments then delegates. The hot path is one `incrementAndGet` plus one
    /// delegate, allocation-free.
    private static InvocationStrategy countingStrategy(AtomicReference<Object> storage) {
        storage.compareAndSet(null, new AtomicLong());
        var counter = (AtomicLong) storage.get();

        return proceed -> countThenProceed(counter, proceed);
    }

    private static Promise<?> countThenProceed(AtomicLong counter, Fn0<Promise<?>> proceed) {
        counter.incrementAndGet();

        return proceed.apply();
    }

    private static AspectObservabilityConfig snapshotOf(ObservabilityConfigValue value) {
        return AspectObservabilityConfig.aspectObservabilityConfig(value.logging(),
                                                                   value.metrics(),
                                                                   value.spans(),
                                                                   value.tracing(),
                                                                   value.depth());
    }
}
