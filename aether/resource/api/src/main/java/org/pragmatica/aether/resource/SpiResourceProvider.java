// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.pragmatica.config.ConfigError;
import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.ProviderBasedConfigService;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.ResourceCapacityExhausted;
import org.pragmatica.aether.slice.SliceLoadingFailure;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Option.option;


public final class SpiResourceProvider implements ResourceProvider {
    private final Map<Class<?>, List<ResourceFactory<?, ?>>> factories;

    /// Scope standing for a caller the provider cannot attribute to a slice — the context-free
    /// `provide(type, section)` overload, which carries no slice id (the wrapper chain in
    /// `SliceLoadingContext` only injects one on the context overload).
    ///
    /// Every unattributed caller shares this one scope, which is the genuinely SHARED cache the
    /// plain overload has always been. It also pins: `releaseAll(sliceId)` can never equal it, so
    /// an unattributed holder's resource is never closed out from under it (#268 R2). Turning an
    /// unattributable release into a bounded leak is the safe direction; the alternative is
    /// use-after-close on a live connection pool.
    private static final String UNATTRIBUTED_SCOPE = "<unattributed>";

    /// The ONLY lifecycle state. One entry per `(type, section, scope)`, inserted by
    /// `computeIfAbsent` and removed only by `remove(key, value)` — the failed-provision eviction
    /// and `releaseAll` — so every entry has exactly one remover and the remover is the one that
    /// closes. A second map (a consumer set per key) used to sit beside this one and the pair was
    /// updated non-atomically: a release could observe the set drained and close what a
    /// concurrent provision was about to hand out, or the provision could insert here AFTER the
    /// release had dropped the set, leaving an entry no later release could reach (review of
    /// #900, SF-2: 187 of 3000 racing rounds). With the scope in the key the set was always
    /// `{scope}` and counted nothing, so it is gone rather than made atomic.
    private final Map<CacheKey, Promise<Provisioned<?>>> promiseCache;
    private final Map<Class<?>, Object> runtimeExtensions;
    private final Fn2<Result<?>, String, Class<?>> configLoader;

    private SpiResourceProvider(Fn2<Result<?>, String, Class<?>> configLoader) {
        this(configLoader, discoverFactories());
    }

    /// Provider over an ALREADY-DISCOVERED factory set, rather than over a `ServiceLoader` scan of
    /// the thread-context classloader.
    ///
    /// The scanning constructor above can only ever see what the node's own classloader can see, and
    /// it runs once at node boot. A resource type defined later by a `SliceClassLoader` is therefore
    /// unreachable through it (#773). This entry point lets a caller that DOES hold the slice's
    /// loader hand over the factories it found there; the resulting map is frozen exactly like the
    /// node's, and the node's own instance is not touched.
    private SpiResourceProvider(Fn2<Result<?>, String, Class<?>> configLoader, List<ResourceFactory<?, ?>> discovered) {
        this.configLoader = configLoader;
        this.promiseCache = new ConcurrentHashMap<>();
        this.runtimeExtensions = new ConcurrentHashMap<>();
        this.factories = indexByResourceType(discovered);
    }

    private static List<ResourceFactory<?, ?>> discoverFactories() {
        var discovered = new ArrayList<ResourceFactory<?, ?>>();

        ServiceLoader.load(ResourceFactory.class).stream().map(ServiceLoader.Provider::get).forEach(discovered::add);

        return List.copyOf(discovered);
    }

    private static Map<Class<?>, List<ResourceFactory<?, ?>>> indexByResourceType(List<ResourceFactory<?, ?>> discovered) {
        Map<Class<?>, List<ResourceFactory<?, ?>>> factoryMap = new ConcurrentHashMap<>();

        discovered.forEach(factory -> factoryMap.computeIfAbsent(factory.resourceType(),
                                                                 _ -> new ArrayList<>())
                                                .add(factory));
        factoryMap.replaceAll((_, list) -> sortByPriorityDescending(list));

        return Map.copyOf(factoryMap);
    }

    private static List<ResourceFactory<?, ?>> sortByPriorityDescending(List<ResourceFactory<?, ?>> list) {
        list.sort(Comparator.<ResourceFactory<?, ?>> comparingInt(ResourceFactory::priority).reversed());

        return List.copyOf(list);
    }

    public static SpiResourceProvider spiResourceProvider() {
        return new SpiResourceProvider(SpiResourceProvider::loadFromConfigService);
    }

    static Result<?> loadFromConfigService(String section, Class<?> configClass) {
        return ConfigService.instance()
                            .toResult(ResourceProvisioningError.ConfigServiceNotAvailable.INSTANCE)
                            .flatMap(svc -> svc.config(section, configClass));
    }

    public static SpiResourceProvider spiResourceProvider(Fn2<Result<?>, String, Class<?>> configLoader) {
        return new SpiResourceProvider(configLoader);
    }

    public static SpiResourceProvider spiResourceProvider(Function<String, Result<?>> configLoader) {
        return new SpiResourceProvider((section, configClass) -> configLoader.apply(section));
    }

    /// Provider scoped to a set of factories the caller discovered itself — see the matching
    /// constructor. Used to give a slice its own overlay over the node-wide provider (#773).
    public static SpiResourceProvider spiResourceProvider(List<ResourceFactory<?, ?>> factories,
                                                          Fn2<Result<?>, String, Class<?>> configLoader) {
        return new SpiResourceProvider(configLoader, factories);
    }

    @SuppressWarnings("JBCT-RET-01")
    public <T> void registerExtension(Class<T> type, T instance) {
        runtimeExtensions.put(type, instance);
    }

    @Override
    public <T> Promise<T> provide(Class<T> resourceType, String configSection) {
        return provideShared(resourceType, configSection, Option.none());
    }

    @Override
    public <T> Promise<T> provide(Class<T> resourceType, String configSection, ProvisioningContext context) {
        return provideShared(resourceType, configSection, Option.some(context));
    }

    /// The single lifecycle path both overloads take (#268).
    ///
    /// They used to implement incompatible models: the context overload registered a consumer but
    /// never cached, and the plain overload cached but never registered — so `releaseAll` drained a
    /// map nothing had been inserted into, and every context-provisioned resource leaked. Both now
    /// memoize AND register, which is what makes close mean anything.
    ///
    /// THE KEY CARRIES A SCOPE, and that is load-bearing rather than incidental. The context
    /// overload is codec-scoped — `SliceLoadingContext.CodecAwareResourceProvider` injects the
    /// DEPLOYED SLICE's codec as `Serializer`/`Deserializer` on every context call, because that
    /// codec is the only one that knows the application's own record types (#526). A cache key
    /// without a slice dimension would hand slice B the resource slice A built with A's codec,
    /// making B's application-typed stream events and DHT cache entries unencodable. Scoping the
    /// key is what keeps #526 fixed. `PublisherFactory` is a second, independent reason: it derives
    /// a topic's namespace from the provisioning slice's `Artifact`, so a shared entry would let
    /// whichever slice provisioned first decide the address for every other.
    ///
    /// Per-slice instances are NOT a behaviour change — the context overload was uncached, and
    /// generated code provisions once per slice inside its static create path, so each slice
    /// already got its own. What is new is that the instance is now memoized within the slice and
    /// released when that slice unloads. Genuinely shared pools, if ever wanted, are a deliberate
    /// design change with their own ticket — not something to acquire from a cache key.
    ///
    /// WHAT A RACE WITH `releaseAll` OF THE SAME SCOPE GUARANTEES, and by what mechanism: the
    /// entry is inserted and removed through `ConcurrentHashMap`'s per-key atomics only, so the
    /// provision is linearized against the release. Either `computeIfAbsent` observed the entry
    /// before the release removed it — the caller receives that entry's resource and the release
    /// closes it once provisioned, which is the slice unloading while it provisions (unload wins,
    /// the same as a sequential provide-then-release) — or it ran after the removal and created
    /// a fresh entry that the NEXT `releaseAll` of the scope finds. No entry is ever left in the
    /// map unreachable by its scope's release, and no entry is closed by more than one remover.
    /// What is NOT guaranteed: that a caller racing its own scope's release holds an open
    /// resource afterwards — nothing short of a lease held for the duration of use could promise
    /// that, and the slice-loading FSM sequences create before stop so the race is not reached in
    /// production. A provision that FAILS evicts its own entry (see below), so the failure is not
    /// memoized.
    @SuppressWarnings("unchecked")
    private <T> Promise<T> provideShared(Class<T> resourceType,
                                         String configSection,
                                         Option<ProvisioningContext> contextOpt) {
        var scope = provisioningScope(contextOpt);
        var key = new CacheKey(resourceType, configSection, scope);
        var cached = promiseCache.computeIfAbsent(key, _ -> createProvisioned(resourceType, configSection, contextOpt));
        // The eviction is a DEPENDENT TRANSFORM (`withFailure`), not an `onFailure` event, and it
        // is registered AHEAD of the caller's `map`. Dependents run on the resolving thread in
        // registration order, before any event reaches the executor, so by the time a caller's
        // continuation can observe the failure the entry is already gone and a retry issued from
        // that continuation provisions afresh. As an event it ran AFTER the caller's continuation,
        // which handed a retry the memoized failure (review of #900, SF-1).
        //
        // Attached OUTSIDE computeIfAbsent on purpose: an already-failed promise applies this
        // synchronously, and mutating a ConcurrentHashMap from inside its own mapping function is
        // forbidden. `remove(key, cached)` is conditional, so re-attaching per call is idempotent
        // and can never evict a newer entry.
        return (Promise<T>) cached.withFailure(_ -> promiseCache.remove(key, cached))
                                  .map(Provisioned::resource);
    }

    /// The slice a provisioning call belongs to, or [#UNATTRIBUTED_SCOPE] when the caller supplied
    /// no context to carry one.
    private static String provisioningScope(Option<ProvisioningContext> contextOpt) {
        return contextOpt.flatMap(context -> context.extension(String.class)
                                                    .option())
                         .or(UNATTRIBUTED_SCOPE);
    }

    @Override
    public boolean hasFactory(Class<?> resourceType) {
        return factories.containsKey(resourceType);
    }

    /// Release everything scoped to `sliceId`, closing each through the factory that built it.
    ///
    /// Because the key carries a scope, a slice releases exactly its own entries; the shared
    /// unattributed scope never matches a slice id and so is never released. Two #268 defects
    /// lived here: the close used `factoryList.getFirst()` rather than the factory whose
    /// `supports()` actually matched (R3 — wrong for the DB connectors, where async, R2DBC and
    /// JDBC all answer for `SqlConnector`), and the drop-to-empty test was a check-then-act over
    /// a consumer set (R5).
    ///
    /// Each matching entry is detached with `remove(key, cached)`: a conditional remove under the
    /// bin lock, so of any number of racing releases of one scope exactly one detaches a given
    /// entry and that one closes it, and a provision that replaced the entry in between is left
    /// alone. That single-remover property is the whole of what "atomic" means here; there is no
    /// second structure to keep in step (see `promiseCache`).
    ///
    /// NOTE on "last consumer": with a scoped key each entry has exactly one consumer identity, so
    /// release is per-scope, not cross-slice refcounting. No test here claims cross-slice
    /// refcounting, because nothing in the current model exercises it.
    @Override
    public Promise<Unit> releaseAll(String sliceId) {
        var closeFutures = new ArrayList<Promise<Unit>>();

        for (var entry : List.copyOf(promiseCache.entrySet())) {
            var key = entry.getKey();
            // The shared unattributed scope is released by nobody. Relying instead on "no real
            // slice is named <unattributed>" would make the pin an accident of naming rather than
            // an invariant, and a slice id that collided would quietly close a pool still in use.
            if (UNATTRIBUTED_SCOPE.equals(key.scope()) || !sliceId.equals(key.scope())) {
                continue;
            }

            var cached = entry.getValue();

            if (promiseCache.remove(key, cached)) {
                closeFutures.add(cached.flatMap(SpiResourceProvider::closeThroughOwningFactory));
            }
        }

        if (closeFutures.isEmpty()) {
            return Promise.unitPromise();
        }
        // allOf collects Results rather than short-circuiting, so one resource that fails to close
        // (or one entry holding a failed provision) cannot block the release of the others.
        return Promise.allOf(closeFutures).map(_ -> Unit.unit());
    }

    /// Close through the factory that built the resource, with a close that THROWS turned into a
    /// failed promise rather than trusted to the convention.
    ///
    /// `releaseAll` applies this inline through `flatMap` on an already-resolved entry, so an
    /// exception escaping a factory's `close` — or an `AsyncCloseable.close()` the default
    /// dispatch invokes — would exit the release loop with every later entry still unreleased.
    /// Lifting it keeps "one bad resource cannot block the others" true for throws as well as for
    /// failed promises (review of #900, NOTE 2). The default close already absorbs and logs its own
    /// failures; this is the guard for the overrides and the implementors that do not.
    @SuppressWarnings("unchecked")
    private static Promise<Unit> closeThroughOwningFactory(Provisioned<?> provisioned) {
        var factory = (ResourceFactory<Object, ?>) provisioned.factory();

        return Result.lift(throwable -> closeThrew(provisioned, throwable),
                           () -> factory.close(provisioned.resource()))
                     .fold(Promise::failure, close -> close);
    }

    private static Cause closeThrew(Provisioned<?> provisioned, Throwable throwable) {
        var resourceType = provisioned.resource().getClass().getName();

        System.getLogger(SpiResourceProvider.class.getName()).log(System.Logger.Level.WARNING,
                                                                  "Resource close threw for " + resourceType
                                                                 + " — the resource is released from the cache anyway",
                                                                  throwable);

        return Causes.fromThrowable(throwable);
    }

    @SuppressWarnings("unchecked")
    private <T> Promise<Provisioned<?>> createProvisioned(Class<T> resourceType,
                                                          String configSection,
                                                          Option<ProvisioningContext> contextOpt) {
        var enrichedContext = contextOpt.map(this::enrichWithRuntimeExtensions);

        return option(factories.get(resourceType)).filter(list -> !list.isEmpty())
                     .map(factoryList -> loadConfigAndInvoke((List<ResourceFactory<T, ?>>)(List<?>) factoryList,
                                                             resourceType,
                                                             configSection,
                                                             enrichedContext))
                     .or(() -> new SliceLoadingFailure.Fatal.ResourceFactoryNotFound(resourceType.getName()).promise());
    }

    /// Layer the node-wide runtime extensions under whatever the caller already supplied.
    ///
    /// Runtime extensions are DEFAULTS, not overrides. A slice that deliberately supplies its own
    /// value for an extension type — the deployed slice's codec as `Serializer`/`Deserializer`,
    /// which is the only codec that knows the application's own record types — must keep it.
    /// Overwriting unconditionally is what made application-typed stream events and distributed
    /// cache entries unencodable (#526). Every other registered extension type is untouched by
    /// this rule: none of them is ever supplied by a caller today, so the guard changes nothing
    /// for them.
    private ProvisioningContext enrichWithRuntimeExtensions(ProvisioningContext context) {
        var enriched = context;

        for (var entry : runtimeExtensions.entrySet()) {
            enriched = addUnlessSupplied(enriched, context, entry);
        }

        return enriched;
    }

    @SuppressWarnings("unchecked")
    private static ProvisioningContext addUnlessSupplied(ProvisioningContext enriched,
                                                         ProvisioningContext supplied,
                                                         Map.Entry<Class<?>, Object> entry) {
        return supplied.hasExtension(entry.getKey())
               ? enriched
               : enriched.withExtension((Class<Object>) entry.getKey(), entry.getValue());
    }

    private <T> Promise<Provisioned<?>> loadConfigAndInvoke(List<ResourceFactory<T, ?>> factoryList,
                                                            Class<T> resourceType,
                                                            String configSection,
                                                            Option<ProvisioningContext> contextOpt) {
        return loadConfig(configSection,
                          factoryList.getFirst().configType(),
                          contextOpt).flatMap(config -> selectAndInvoke(factoryList,
                                                                        config,
                                                                        resourceType,
                                                                        configSection,
                                                                        contextOpt));
    }

    /// Select the factory whose `supports()` matches and REMEMBER it alongside the resource.
    ///
    /// The matched factory is the only one entitled to close what it built; `releaseAll` used to
    /// reach for `factoryList.getFirst()` instead, which is a different object whenever several
    /// factories answer for one resource type — exactly the DB case, where async, R2DBC and JDBC
    /// connectors all supply `SqlConnector` and are ordered by priority (#268 R3).
    @SuppressWarnings("unchecked")
    private <T, C> Promise<Provisioned<?>> selectAndInvoke(List<ResourceFactory<T, ?>> factoryList,
                                                           C config,
                                                           Class<T> resourceType,
                                                           String configSection,
                                                           Option<ProvisioningContext> contextOpt) {
        for (var factory : factoryList) {
            var typed = (ResourceFactory<T, C>) factory;

            if (typed.supports(config)) {
                return invokeProvision(typed, config, contextOpt).<Provisioned<?>> map(resource -> new Provisioned<>(resource,
                                                                                                                     typed))
                                      .mapError(cause -> classifyProvisionFailure(resourceType, configSection, cause));
            }
        }

        return new SliceLoadingFailure.Fatal.ResourceFactoryNotFound(resourceType.getName()).promise();
    }

    private static <T, C> Promise<T> invokeProvision(ResourceFactory<T, C> factory,
                                                     C config,
                                                     Option<ProvisioningContext> contextOpt) {
        return contextOpt.fold(() -> factory.provision(config), context -> factory.provision(config, context));
    }

    /// Classify a resource-provisioning failure for the slice-loading FSM (spec §6 / decision #7).
    /// A TRANSIENT capacity shortage ({@link ResourceCapacityExhausted}, e.g. `STREAM_MEMORY_EXCEEDED`)
    /// becomes {@link SliceLoadingFailure.Intermittent.ResourceUnavailable} so the deployment FSM
    /// RETRIES with backoff (the pool may clear) and surfaces `DeploymentFailed` only after
    /// MAX_RETRIES — visibly failed, not "deployed but dead". Everything else stays
    /// {@link SliceLoadingFailure.Fatal.ResourceCreationFailed} (permanent, no retry).
    private static SliceLoadingFailure classifyProvisionFailure(Class<?> resourceType,
                                                                String configSection,
                                                                Cause cause) {
        return ResourceCapacityExhausted.isTransientCapacity(cause)
               ? new SliceLoadingFailure.Intermittent.ResourceUnavailable(resourceType.getSimpleName(), cause)
               : new SliceLoadingFailure.Fatal.ResourceCreationFailed(resourceType.getSimpleName(), configSection, cause);
    }

    @SuppressWarnings("unchecked")
    private <C> Promise<C> loadConfig(String section, Class<C> configType, Option<ProvisioningContext> contextOpt) {
        var loaded = (Result<Object>) resolveConfigLoader(contextOpt).apply(section, configType);

        return topicNameFallback(section, configType, loaded).mapError(cause -> new SliceLoadingFailure.Fatal.ConfigurationFailed(section,
                                                                                                                                  cause))
                                .map(obj -> (C) obj)
                                .async();
    }

    /// Route a typed-topic publisher's address off the manifest-derived topic name (#396): the
    /// `section` passed for a [TopicConfig] resource is the topic name resolved from the single-source
    /// `Topic<T>` constant (the generated factory provisions the publisher by that name), so a missing
    /// resources.toml `[section]` — the author no longer writes `topic_name` — defaults to a topic
    /// named after the section instead of failing slice activation. Non-topic resources are unaffected.
    ///
    /// Only config ABSENCE takes the fallback — [ConfigError.SectionNotFound] (the section is not
    /// in resources.toml) and [ResourceProvisioningError.ConfigServiceNotAvailable] (no config
    /// service at all, e.g. minimal runtimes): in both states there is nothing declared to honor,
    /// and the topic name is derivable, which is the point of #396. A section that EXISTS but
    /// fails binding or validation (the durable-pubsub §3 constraint, inert ephemeral keys, a
    /// mistyped `durability` enum value) must fail activation loudly — recovering it into an
    /// ephemeral default would silently downgrade a declared-durable topic to fire-and-forget
    /// delivery.
    ///
    /// This is the intentional exception to #547's "no resource type silently synthesises
    /// configuration" gate: `TopicConfig` is a single-field record whose value the runtime already
    /// knows independently of `resources.toml` (the topic name), so there is nothing to synthesise —
    /// declaring it here, by design, rather than removing it, is #547's Gap-1 resolution. It also
    /// means `ConfigSectionPreflightValidator`'s deploy-time pre-flight deliberately does not check
    /// topic/stream sections at all; only generic [SliceTopology.ResourceDep] resources are gated.
    private static Result<Object> topicNameFallback(String section, Class<?> configType, Result<Object> loaded) {
        return configType.equals(TopicConfig.class)
               ? loaded.fold(cause -> recoverAbsentTopicConfig(section, cause), Result::success)
               : loaded;
    }

    private static Result<Object> recoverAbsentTopicConfig(String section, Cause cause) {
        return switch (cause) {
            case ConfigError.SectionNotFound _, ResourceProvisioningError.ConfigServiceNotAvailable _ -> Result.success(new TopicConfig(section));
            default -> cause.result();
        };
    }

    /// Resolve the configuration loader for this provisioning call.
    ///
    /// When the provided context carries a `ConfigurationProvider` extension, the per-call
    /// slice-composite is used (wrapped in `ProviderBasedConfigService` for section binding).
    /// Otherwise the loader falls back to the constructor-supplied `configLoader` (typically
    /// the global `ConfigService.instance()` singleton).
    private Fn2<Result<?>, String, Class<?>> resolveConfigLoader(Option<ProvisioningContext> contextOpt) {
        return contextOpt.flatMap(SpiResourceProvider::extractCompositeLoader)
                         .or(configLoader);
    }

    private static Option<Fn2<Result<?>, String, Class<?>>> extractCompositeLoader(ProvisioningContext context) {
        return context.extension(ConfigurationProvider.class)
                      .option()
                      .map(SpiResourceProvider::loaderFromComposite);
    }

    private static Fn2<Result<?>, String, Class<?>> loaderFromComposite(ConfigurationProvider composite) {
        var svc = ProviderBasedConfigService.providerBasedConfigService(composite);

        return (section, configClass) -> svc.config(section, configClass);
    }

    /// Identity of a cached resource. `scope` is the provisioning slice's id, or
    /// [#UNATTRIBUTED_SCOPE] for the context-free overload — see [#provideShared] for why the
    /// slice dimension is required rather than optional.
    private record CacheKey(Class<?> resourceType, String configSection, String scope) {}

    /// A provisioned resource together with the factory that actually built it, so the release
    /// path closes through the SAME factory rather than guessing at the head of the priority list.
    private record Provisioned<T>(T resource, ResourceFactory<T, ?> factory) {}
}
