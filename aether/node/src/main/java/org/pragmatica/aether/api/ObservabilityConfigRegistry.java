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
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


// Write-side registry for the per-injection-point system-observability cell (#277). It owns the
// last-known AspectObservabilityConfig snapshot per config scope AND the live ObservabilityStrategyCell
// minted for each dispatch injection point. On a KV-update event it re-resolves the affected cells against
// the scope hierarchy, translates each effective snapshot into an "around" strategy, and swaps it
// wholesale into the live cell (push-on-event), so the per-call hot path never performs a registry lookup.
//
// Config scopes, nearest-scope whole-snapshot override (never per-field merge). For a cell keyed
// `base/method`: the method-scope config (exact key `base/method`) wins if present; else the artifact
// scope (`base/*`); else the global scope (`*/*`); else the BASELINE. Scopes are plain-value keys in the
// same `configs` map — a wildcard `*` is just a string — so no distinct key type is needed. A put/remove
// at ANY scope re-resolves every AFFECTED live cell: a method put touches that key's cells; an artifact
// put touches every live cell with prefix `base/`; a global put touches every live cell. Removal at a
// scope re-resolves too (falling back to the next-broader scope, NOT necessarily identity). This is
// control-plane work: the artifact/global fan-out scans `instances` — acceptable off the hot path.
//
// Absence-default posture (variant C): a cell with NO config at any scope resolves to the BASELINE
// strategy (all ambient facets on: logging + metrics/counting + tracing, spans off), not identity. An
// explicit `allOff()` config at any scope resolves to identity — the operator's deliberate darkening. An
// explicit non-off config composes the SAME facet bodies the baseline uses (ObservabilityBaseline.compose),
// selected by its toggles (#277 increment 5b). The baseline collaborators (sampler, trace store, logger)
// are injected through ObservabilityBaseline at construction; a counting-only baseline (observability
// disabled) layers no ambient facets, so absence == counting there.
//
// Distinct from ObservabilityDepthRegistry (depth / trace-rate tuning, read-on-demand): this registry
// holds live cell references and swaps their behaviour. It IS the ObservabilityCellRegistrar the dispatch
// seams (increment 2) call to register(cell) at slice load / route publish and deregister(cell) at
// unload / unpublish. Multiple cells can share one injection-point key (e.g. the north-south route cell
// and the east-west bridge cell of the same method) — all are held in a per-key set and swapped together.
public class ObservabilityConfigRegistry implements ObservabilityCellRegistrar {
    private static final Logger log = LoggerFactory.getLogger(ObservabilityConfigRegistry.class);
    private static final String WILDCARD = "*";
    private static final String GLOBAL_KEY = WILDCARD + "/" + WILDCARD;

    private final RabiaNode<KVCommand<AetherKey>> clusterNode;
    private final KVStore<AetherKey, AetherValue> kvStore;
    private final ObservabilityBaseline baseline;
    private final Map<String, AspectObservabilityConfig> configs = new ConcurrentHashMap<>();
    private final Map<String, Set<ObservabilityStrategyCell>> instances = new ConcurrentHashMap<>();

    private ObservabilityConfigRegistry(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                        KVStore<AetherKey, AetherValue> kvStore,
                                        ObservabilityBaseline baseline) {
        this.clusterNode = clusterNode;
        this.kvStore = kvStore;
        this.baseline = baseline;
    }

    public static ObservabilityConfigRegistry observabilityConfigRegistry(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                                                          KVStore<AetherKey, AetherValue> kvStore) {
        return observabilityConfigRegistry(clusterNode, kvStore, ObservabilityBaseline.countingOnly());
    }

    public static ObservabilityConfigRegistry observabilityConfigRegistry(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                                                          KVStore<AetherKey, AetherValue> kvStore,
                                                                          ObservabilityBaseline baseline) {
        var registry = new ObservabilityConfigRegistry(clusterNode, kvStore, baseline);

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
    /// strategy resolved against the scope hierarchy (method -> artifact -> global -> baseline) so
    /// subsequent KV-update events swap its behaviour in place. Registering BEFORE seeding closes the
    /// load/put race: a concurrent KV put either finds the cell and swaps it, or lands before
    /// registration and is picked up by the seed read — both converge on the strategy for the latest
    /// effective snapshot. Multiple cells may share one key; each joins the key's set and all are swapped
    /// together.
    @Override
    public Unit register(ObservabilityStrategyCell cell) {
        instances.computeIfAbsent(cell.key(), _ -> ConcurrentHashMap.newKeySet()).add(cell);
        cell.swap(resolvedStrategy(cell));

        return Unit.unit();
    }

    /// Drops the live cell for its injection point (on unload / unpublish) so a later KV-update does not
    /// retain it. The deregistered cell keeps its current strategy and works standalone — deregister only
    /// removes the registry's reference; the key's set is dropped once its last cell leaves.
    @Override
    public Unit deregister(ObservabilityStrategyCell cell) {
        Option.option(instances.get(cell.key())).onPresent(set -> removeFromSet(cell.key(), set, cell));

        return Unit.unit();
    }

    private void removeFromSet(String key, Set<ObservabilityStrategyCell> set, ObservabilityStrategyCell cell) {
        set.remove(cell);
        if (set.isEmpty()) {
            instances.remove(key, set);
        }
    }

    /// Re-resolves and swaps the strategy for every live cell AFFECTED by a change at the given config
    /// scope. Method scope touches only that key's cells; artifact scope (`base/*`) touches every live
    /// cell with prefix `base/`; global scope (`*/*`) touches every live cell. Each affected cell is
    /// re-resolved independently against the full hierarchy — an artifact or global change never
    /// overrides a cell that has its own nearer-scope config. Composition is per-cell (not one shared
    /// strategy) because a stateful facet — the embryonic counting metrics facet — binds to each cell's
    /// own storage slot; the north-south route cell and east-west bridge cell of one method each count
    /// into their own AtomicLong.
    private void reresolveAffected(String artifactBase, String methodName) {
        affectedCells(artifactBase, methodName).forEach(this::reresolveCell);
    }

    private void reresolveCell(ObservabilityStrategyCell cell) {
        cell.swap(resolvedStrategy(cell));
    }

    // Fan-out over the live cells a scope change affects. The artifact/global arms scan `instances`
    // (a weakly-consistent ConcurrentHashMap traversal) — control-plane cost paid off the hot path.
    private Stream<ObservabilityStrategyCell> affectedCells(String artifactBase, String methodName) {
        return switch (scopeOf(artifactBase, methodName)) {
            case GLOBAL -> allCells();
            case ARTIFACT -> cellsUnderArtifact(artifactBase);
            case METHOD -> cellsUnderKey(artifactBase + "/" + methodName);
        };
    }

    private Stream<ObservabilityStrategyCell> allCells() {
        return instances.values()
                        .stream()
                        .flatMap(Set::stream);
    }

    private Stream<ObservabilityStrategyCell> cellsUnderArtifact(String artifactBase) {
        var prefix = artifactBase + "/";

        return instances.entrySet()
                        .stream()
                        .filter(entry -> entry.getKey()
                                              .startsWith(prefix))
                        .flatMap(entry -> entry.getValue()
                                               .stream());
    }

    private Stream<ObservabilityStrategyCell> cellsUnderKey(String registryKey) {
        return instances.getOrDefault(registryKey,
                                      Set.of())
                        .stream();
    }

    private static Scope scopeOf(String artifactBase, String methodName) {
        if (WILDCARD.equals(artifactBase) && WILDCARD.equals(methodName)) {
            return Scope.GLOBAL;
        }

        if (WILDCARD.equals(methodName)) {
            return Scope.ARTIFACT;
        }

        return Scope.METHOD;
    }

    private enum Scope {
        GLOBAL,
        ARTIFACT,
        METHOD
    }

    @MessageReceiver
    @Contract
    public void onObservabilityConfigPut(ValuePut<ObservabilityConfigKey, ObservabilityConfigValue> valuePut) {
        var key = valuePut.cause().key();
        var value = valuePut.cause().value();
        var registryKey = key.artifactBase() + "/" + key.methodName();
        var snapshot = snapshotOf(value);

        configs.put(registryKey, snapshot);
        reresolveAffected(key.artifactBase(), key.methodName());
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
        reresolveAffected(key.artifactBase(), key.methodName());
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
                          .map(_ -> applyConfig(artifactBase, methodName, snapshot))
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
                          .map(_ -> removeFromRegistry(artifactBase, methodName))
                          .onFailure(cause -> log.error("Failed to persist observability config removal for {}/{}: {}",
                                                        artifactBase,
                                                        methodName,
                                                        cause.message()));
    }

    public AspectObservabilityConfig getConfig(String artifactBase, String methodName) {
        return configs.getOrDefault(artifactBase + "/" + methodName, AspectObservabilityConfig.OFF);
    }

    /// The three-case effective posture for an injection point, distinguishing the two states `getConfig`
    /// conflates into `OFF` (absence vs. explicit all-off). The management surface reads this; the depth
    /// routes and the strategy resolution share the same scope hierarchy underneath. BASELINE = no config
    /// at any scope (fleet baseline runs); CONFIGURED = an explicit non-off config (its selected facets);
    /// DARKENED = an explicit all-off config (identity, the operator's deliberate opt-out).
    public ObservabilityState effectiveState(String artifactBase, String methodName) {
        return effectiveConfigFor(artifactBase + "/" + methodName).map(ObservabilityState::of)
                                 .or(ObservabilityState.BASELINE);
    }

    /// The effective AspectObservabilityConfig for an injection point, baseline-materialized: the nearest-
    /// scope config if present, else the baseline-equivalent set (logging + metrics + tracing on, spans off)
    /// at the baseline default depth. Lets the management surface show what actually runs even for a
    /// Baseline point (#277 increment 5b).
    public AspectObservabilityConfig effectiveConfig(String artifactBase, String methodName) {
        return effectiveConfigFor(artifactBase + "/" + methodName).or(baselineDefaults());
    }

    /// Every effective observability posture the node knows: the union of the configured/darkened scopes
    /// (replicated config keys, including the wildcard scopes) and the live baseline cells (per-node
    /// injection points with no config). Each entry carries the resolved state, the baseline-materialized
    /// effective config, and the per-node invocation count. Read path for `GET /api/observability/config`.
    /// The per-node live-cell fields (invocation count, and baseline cells with no config key) reflect the
    /// responding node — the replicated config fields are cluster-consistent.
    public List<EffectiveEntry> allEffectiveStates() {
        return Stream.concat(configs.keySet().stream(),
                             instances.keySet().stream()).distinct()
                            .map(this::effectiveEntryFor)
                            .toList();
    }

    /// The effective posture for a single injection point / config scope — the read path for
    /// `GET /api/observability/config/{artifactBase}/{methodName}`. Wildcards (`*`) are accepted as scope
    /// segments; `invocationCount` is None unless a live cell is registered for the exact key.
    public EffectiveEntry effectiveEntry(String artifactBase, String methodName) {
        return new EffectiveEntry(artifactBase,
                                  methodName,
                                  effectiveState(artifactBase, methodName),
                                  effectiveConfig(artifactBase, methodName),
                                  invocationCount(artifactBase, methodName));
    }

    private EffectiveEntry effectiveEntryFor(String key) {
        var slash = key.indexOf('/');

        return effectiveEntry(key.substring(0, slash), key.substring(slash + 1));
    }

    /// Effective depth threshold for an injection point: the nearest-scope config's depth, else the baseline
    /// default (mirrors today's ObservabilityDepthRegistry default). Read path for `/api/observability/depth`.
    public int effectiveDepth(String artifactBase, String methodName) {
        return effectiveDepthFor(artifactBase + "/" + methodName);
    }

    /// Depth-store unification (#277 increment 5a): the retired ObservabilityDepthRegistry's depth store folds
    /// into this one. Setting a depth materializes a METHOD-scope config = the current effective config (or the
    /// baseline defaults when absent) with `depth` replaced — i.e. setting depth pins this method's full config
    /// as-of-now with the new depth. It persists through the same replicated `setConfig` path.
    public Promise<Unit> setDepth(String artifactBase, String methodName, int depth) {
        var pinned = effectiveConfigFor(artifactBase + "/" + methodName).or(baselineDefaults());

        return setConfig(artifactBase,
                         methodName,
                         pinned.logging(),
                         pinned.metrics(),
                         pinned.spans(),
                         pinned.tracing(),
                         depth);
    }

    /// Removes the method-scope config entry entirely (resolution falls back to the next-broader scope, else
    /// the baseline) — the DELETE half of the unified depth store.
    public Promise<Unit> removeDepth(String artifactBase, String methodName) {
        return removeConfig(artifactBase, methodName);
    }

    // The baseline-behavior facet set used to materialize a depth pin when no config exists yet: the fleet
    // baseline reproduces failure-log + depth-leveled logging (logging) + depth-0 sampled tracing (tracing) +
    // counting (metrics); spans stay off.
    private AspectObservabilityConfig baselineDefaults() {
        return AspectObservabilityConfig.aspectObservabilityConfig(true, true, false, true, baseline.defaultDepth());
    }

    public Map<String, AspectObservabilityConfig> allConfigs() {
        return Map.copyOf(configs);
    }

    /// Live invocation count for an injection point — the read path of the embryonic metrics facet
    /// (#277 increment 3) for tests and ops. Sums the counters across every live cell sharing the key
    /// rather than reading one cell: when the north-south route cell and the east-west bridge cell of a
    /// method are both registered they each count into their own storage, so the sum is the honest total
    /// for the injection point. `None` when no live cell is registered for the key (e.g. after a slice
    /// unregisters and its cells are deregistered); `Some(0)` when cells are registered but no call has
    /// counted yet — either the baseline/metrics counter was just planted at zero, or the cell is under an
    /// explicit `allOff()` config (identity, storage untouched, read as zero).
    public Option<Long> invocationCount(String artifactBase, String methodName) {
        return Option.option(instances.get(artifactBase + "/" + methodName)).map(ObservabilityConfigRegistry::sumCounters);
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

    private Unit applyConfig(String artifactBase, String methodName, AspectObservabilityConfig snapshot) {
        configs.put(artifactBase + "/" + methodName, snapshot);
        reresolveAffected(artifactBase, methodName);
        log.info("Observability config set for {}/{}: logging={} metrics={} spans={} tracing={} depth={}",
                 artifactBase,
                 methodName,
                 snapshot.logging(),
                 snapshot.metrics(),
                 snapshot.spans(),
                 snapshot.tracing(),
                 snapshot.depth());

        return Unit.unit();
    }

    private Unit removeFromRegistry(String artifactBase, String methodName) {
        configs.remove(artifactBase + "/" + methodName);
        reresolveAffected(artifactBase, methodName);
        log.info("Observability config removed for {}/{}", artifactBase, methodName);

        return Unit.unit();
    }

    private InvocationStrategy resolvedStrategy(ObservabilityStrategyCell cell) {
        return strategyFor(cell, effectiveConfigFor(cell.key()));
    }

    /// Resolves the effective config for a cell against the scope hierarchy: the method-scope snapshot
    /// (exact key) if present, else the artifact scope (`base/*`), else the global scope (`*/*`), else
    /// `None` — the absence that resolves to the baseline. Whole-snapshot, never merged: the first scope
    /// that has a config wins entirely.
    private Option<AspectObservabilityConfig> effectiveConfigFor(String cellKey) {
        return configAt(cellKey).orElse(() -> configAt(artifactScopeKey(cellKey)))
                       .orElse(() -> configAt(GLOBAL_KEY));
    }

    private Option<AspectObservabilityConfig> configAt(String scopeKey) {
        return Option.option(configs.get(scopeKey));
    }

    // The artifact-scope key for a `base/method` cell key: `base/*`. The artifact base never contains a
    // slash (it is `groupId:artifactId`), so the first slash is the scope separator.
    private static String artifactScopeKey(String cellKey) {
        return cellKey.substring(0, cellKey.indexOf('/')) + "/" + WILDCARD;
    }

    /// The three-case absence-default posture (variant C). No config at any scope -> the BASELINE strategy
    /// (all ambient facets on: logging + metrics/counting + tracing, spans off). An explicit `allOff()`
    /// config -> the zero-cost identity singleton (the operator's deliberate darkening). Any explicit
    /// non-off config -> the facets its toggles select, composed from the SAME bodies the baseline uses
    /// (#277 increment 5b). Baseline and configured share ObservabilityBaseline.compose; only the selected
    /// facet set and depth differ.
    private InvocationStrategy strategyFor(ObservabilityStrategyCell cell,
                                           Option<AspectObservabilityConfig> effective) {
        return effective.map(config -> configuredStrategy(cell, config))
                        .or(() -> baselineStrategy(cell));
    }

    /// An explicit non-off config composes the SAME facet bodies the baseline uses (#277 increment 5b),
    /// selected by the config's toggles: the metrics facet is the counting inner when `metrics` is on (else
    /// identity), and the baseline layers the logging + tracing facets from `config.logging()` /
    /// `config.tracing()` around it at the config's own depth. `spans` is a reserved toggle with no body
    /// yet (#304). An explicit all-off config is the zero-cost identity singleton (deliberate darkening).
    private InvocationStrategy configuredStrategy(ObservabilityStrategyCell cell, AspectObservabilityConfig config) {
        return config.allOff()
               ? InvocationStrategy.IDENTITY
               : baseline.compose(innerFacet(cell, config),
                                  cell.key(),
                                  config.depth(),
                                  config.logging(),
                                  config.tracing());
    }

    // The metrics facet: the counting inner when the config selects `metrics`, else identity. The logging
    // and tracing facets are layered by ObservabilityBaseline.compose from the config's toggles — the same
    // facet bodies the baseline runs.
    private static InvocationStrategy innerFacet(ObservabilityStrategyCell cell, AspectObservabilityConfig config) {
        return config.metrics()
               ? countingStrategy(cell.storage())
               : InvocationStrategy.IDENTITY;
    }

    /// The absence default: a cell with no config at any scope runs the fleet baseline — ALL ambient facets
    /// on (logging + metrics/counting + tracing, spans off) rather than running blind. The baseline composes
    /// its facets around the shared counting inner; the counting-only baseline (observability disabled) adds
    /// none, so absence == counting. The facet is per-cell (hence the cell): each cell counts into its own
    /// AtomicLong, so the two seams of one method never share a counter. The logging ladder's depth threshold
    /// is resolved from the effective config (AspectObservabilityConfig.depth) else the baseline default; in
    /// the absence case that is always the baseline default, but the read is kept honest against the
    /// registry's own config.
    private InvocationStrategy baselineStrategy(ObservabilityStrategyCell cell) {
        return baseline.compose(countingStrategy(cell.storage()), cell.key(), effectiveDepthFor(cell.key()), true, true);
    }

    private int effectiveDepthFor(String cellKey) {
        return effectiveConfigFor(cellKey).map(AspectObservabilityConfig::depth)
                                 .or(baseline.defaultDepth());
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

    /// The three-case absence-default posture as a value (#277), resolving the getConfig absence-vs-off
    /// conflation for the management surface. BASELINE = no config at any scope; CONFIGURED carries the
    /// explicit non-off config; DARKENED carries the explicit all-off config.
    public sealed interface ObservabilityState {
        ObservabilityState BASELINE = new Baseline();

        record Baseline() implements ObservabilityState {}

        record Configured(AspectObservabilityConfig config) implements ObservabilityState {}

        record Darkened(AspectObservabilityConfig config) implements ObservabilityState {}

        static ObservabilityState of(AspectObservabilityConfig config) {
            return config.allOff()
                   ? new Darkened(config)
                   : new Configured(config);
        }
    }

    /// One effective observability posture for the management list surface (#277 increment 5b): the
    /// injection-point / config-scope identity, its resolved state, the baseline-materialized effective
    /// config (so the view can show what actually runs even for a Baseline point), and the per-node live
    /// invocation count (None when no live cell is registered for the key — e.g. a wildcard config scope).
    public record EffectiveEntry(String artifactBase,
                                 String methodName,
                                 ObservabilityState state,
                                 AspectObservabilityConfig effectiveConfig,
                                 Option<Long> invocationCount) {}
}
