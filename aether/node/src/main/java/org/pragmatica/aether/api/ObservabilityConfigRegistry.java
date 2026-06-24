// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.slice.Aspect;
import org.pragmatica.aether.slice.AspectObservabilityConfig;
import org.pragmatica.aether.slice.ObservabilityAspect;
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
import org.pragmatica.messaging.MessageReceiver;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


// Write-side registry for the per-slice system-observability aspect (#277). It owns the last-known
// AspectObservabilityConfig snapshot per artifactBase AND the live ObservabilityAspect instance woven
// into each loaded slice. On a KV-update event it pushes the new snapshot wholesale onto the live
// instance's volatile field (push-on-event), so the per-call hot path never performs a registry lookup.
//
// Distinct from ObservabilityDepthRegistry (depth / trace-rate tuning, read-on-demand): this registry
// holds live aspect references and swaps them, keyed by artifactBase only (no methodName — the system
// observability aspect is per-slice, not per-method).
@SuppressWarnings("JBCT-RET-01")
public class ObservabilityConfigRegistry {
    private static final Logger log = LoggerFactory.getLogger(ObservabilityConfigRegistry.class);

    private final RabiaNode<KVCommand<AetherKey>> clusterNode;
    private final KVStore<AetherKey, AetherValue> kvStore;
    private final Map<String, AspectObservabilityConfig> configs = new ConcurrentHashMap<>();
    private final Map<String, ObservabilityAspect<?>> instances = new ConcurrentHashMap<>();

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
        configs.put(key.artifactBase(), snapshotOf(value));
        log.debug("Loaded observability config from KV-Store: {} -> logging={} metrics={} spans={} tracing={} depth={}",
                  key.artifactBase(),
                  value.logging(),
                  value.metrics(),
                  value.spans(),
                  value.tracing(),
                  value.depth());
    }

    /// Creates a live ObservabilityAspect for the slice, registers it, then seeds it with the last-known
    /// snapshot (or OFF) so subsequent KV-update events swap its volatile config in place. Registering
    /// BEFORE seeding closes the load/put race: a concurrent KV put either finds the instance and swaps
    /// it, or lands before registration and is picked up by the seed read — both converge on the latest.
    public Aspect<?> createAndRegister(ArtifactBase base) {
        var key = base.asString();
        ObservabilityAspect<?> aspect = ObservabilityAspect.observabilityAspect(key);

        instances.put(key, aspect);
        aspect.swap(configs.getOrDefault(key, AspectObservabilityConfig.OFF));

        return aspect;
    }

    /// Drops the live aspect reference for the slice (on unload) so a later KV-update does not retain it.
    public Unit deregister(ArtifactBase base) {
        instances.remove(base.asString());

        return Unit.unit();
    }

    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    public void onObservabilityConfigPut(ValuePut<ObservabilityConfigKey, ObservabilityConfigValue> valuePut) {
        var value = valuePut.cause().value();
        var artifactBase = valuePut.cause().key().artifactBase();
        var snapshot = snapshotOf(value);

        configs.put(artifactBase, snapshot);
        Option.option(instances.get(artifactBase)).onPresent(aspect -> aspect.swap(snapshot));
        log.debug("Observability config updated from cluster: {} -> logging={} metrics={} spans={} tracing={} depth={}",
                  artifactBase,
                  value.logging(),
                  value.metrics(),
                  value.spans(),
                  value.tracing(),
                  value.depth());
    }

    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    public void onObservabilityConfigRemove(ValueRemove<ObservabilityConfigKey, ObservabilityConfigValue> valueRemove) {
        var artifactBase = valueRemove.cause().key().artifactBase();

        configs.remove(artifactBase);
        Option.option(instances.get(artifactBase)).onPresent(aspect -> aspect.swap(AspectObservabilityConfig.OFF));
        log.debug("Observability config removed from cluster: {}", artifactBase);
    }

    @SuppressWarnings("unchecked")
    public Promise<Unit> setConfig(String artifactBase,
                                   boolean logging,
                                   boolean metrics,
                                   boolean spans,
                                   boolean tracing,
                                   int depth) {
        var key = ObservabilityConfigKey.forArtifactBase(artifactBase);
        var value = ObservabilityConfigValue.observabilityConfigValue(artifactBase,
                                                                      logging,
                                                                      metrics,
                                                                      spans,
                                                                      tracing,
                                                                      depth);
        var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(key, value);

        return clusterNode.<Unit> apply(List.of(command))
                          .map(_ -> applyConfig(artifactBase,
                                                AspectObservabilityConfig.aspectObservabilityConfig(logging,
                                                                                                    metrics,
                                                                                                    spans,
                                                                                                    tracing,
                                                                                                    depth)))
                          .onFailure(cause -> log.error("Failed to persist observability config for {}: {}",
                                                        artifactBase,
                                                        cause.message()));
    }

    @SuppressWarnings("unchecked")
    public Promise<Unit> removeConfig(String artifactBase) {
        var key = ObservabilityConfigKey.forArtifactBase(artifactBase);
        var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Remove<>(key);

        return clusterNode.<Unit> apply(List.of(command))
                          .map(_ -> removeFromRegistry(artifactBase))
                          .onFailure(cause -> log.error("Failed to persist observability config removal for {}: {}",
                                                        artifactBase,
                                                        cause.message()));
    }

    public AspectObservabilityConfig getConfig(String artifactBase) {
        return configs.getOrDefault(artifactBase, AspectObservabilityConfig.OFF);
    }

    public Map<String, AspectObservabilityConfig> allConfigs() {
        return Map.copyOf(configs);
    }

    private Unit applyConfig(String artifactBase, AspectObservabilityConfig snapshot) {
        configs.put(artifactBase, snapshot);
        Option.option(instances.get(artifactBase)).onPresent(aspect -> aspect.swap(snapshot));
        log.info("Observability config set for {}: logging={} metrics={} spans={} tracing={} depth={}",
                 artifactBase,
                 snapshot.logging(),
                 snapshot.metrics(),
                 snapshot.spans(),
                 snapshot.tracing(),
                 snapshot.depth());

        return Unit.unit();
    }

    private Unit removeFromRegistry(String artifactBase) {
        configs.remove(artifactBase);
        Option.option(instances.get(artifactBase)).onPresent(aspect -> aspect.swap(AspectObservabilityConfig.OFF));
        log.info("Observability config removed for {}", artifactBase);

        return Unit.unit();
    }

    private static AspectObservabilityConfig snapshotOf(ObservabilityConfigValue value) {
        return AspectObservabilityConfig.aspectObservabilityConfig(value.logging(),
                                                                   value.metrics(),
                                                                   value.spans(),
                                                                   value.tracing(),
                                                                   value.depth());
    }
}
