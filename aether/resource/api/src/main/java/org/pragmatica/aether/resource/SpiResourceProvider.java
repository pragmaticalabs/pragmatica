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
import java.util.Set;
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

import static org.pragmatica.lang.Option.option;


public final class SpiResourceProvider implements ResourceProvider {
    private final Map<Class<?>, List<ResourceFactory<?, ?>>> factories;

    /// Consumer id standing for a caller the provider cannot attribute to a slice — the
    /// context-free `provide(type, section)` overload, which carries no slice id (the wrapper
    /// chain in `SliceLoadingContext` only injects one on the context overload).
    ///
    /// It pins the entry: `releaseAll(sliceId)` can never remove it, so a shared resource is never
    /// closed while an unattributed holder may still be using it. That is R2 of #268 — the
    /// last-consumer unload used to close a resource a plain-path caller still held. Turning an
    /// unattributable release into a bounded leak is the safe direction; the alternative is
    /// use-after-close on a connection pool.
    private static final String UNATTRIBUTED_CONSUMER = "<unattributed>";

    private final Map<CacheKey, Promise<Provisioned<?>>> promiseCache;
    private final Map<CacheKey, Set<String>> consumers;
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
        this.consumers = new ConcurrentHashMap<>();
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
    /// memoize under the same key AND register a consumer, which is what makes refcounting and
    /// close mean anything.
    ///
    /// Registration happens BEFORE provisioning so a release racing an in-flight provision cannot
    /// close a resource the caller is about to receive; a provision that then FAILS evicts its own
    /// cache entry (see below), so the failure is not memoized.
    @SuppressWarnings("unchecked")
    private <T> Promise<T> provideShared(Class<T> resourceType,
                                         String configSection,
                                         Option<ProvisioningContext> contextOpt) {
        var key = new CacheKey(resourceType, configSection);

        registerConsumer(key, contextOpt);
        var cached = promiseCache.computeIfAbsent(key, _ -> createProvisioned(resourceType, configSection, contextOpt));
        // Attached OUTSIDE computeIfAbsent on purpose: an already-failed promise fires this
        // synchronously, and mutating a ConcurrentHashMap from inside its own mapping function is
        // forbidden. `remove(key, cached)` is conditional, so re-attaching per call is idempotent
        // and can never evict a newer entry.
        cached.onFailure(_ -> promiseCache.remove(key, cached));

        return (Promise<T>) cached.map(Provisioned::resource);
    }

    private void registerConsumer(CacheKey key, Option<ProvisioningContext> contextOpt) {
        var consumerId = contextOpt.flatMap(context -> context.extension(String.class)
                                                              .option())
                                   .or(UNATTRIBUTED_CONSUMER);

        consumers.computeIfAbsent(key, _ -> ConcurrentHashMap.newKeySet()).add(consumerId);
    }

    @Override
    public boolean hasFactory(Class<?> resourceType) {
        return factories.containsKey(resourceType);
    }

    /// Release everything `sliceId` was the last consumer of.
    ///
    /// `CacheKey` carries no slice dimension, so resources ARE shared across slices by design and
    /// the model is last-consumer-releases. Two #268 defects lived here: the close used
    /// `factoryList.getFirst()` rather than the factory whose `supports()` actually matched (R3 —
    /// wrong for the DB connectors, where async/R2DBC/JDBC all answer for `SqlConnector`), and the
    /// drop-to-empty test was a check-then-act over `consumers` (R5).
    ///
    /// The emptiness test is now atomic: `computeIfPresent` returning `null` removed the mapping
    /// under the bin lock, so exactly one caller observes the transition and `promiseCache.remove`
    /// then hands the entry to exactly one closer.
    @Override
    public Promise<Unit> releaseAll(String sliceId) {
        var closeFutures = new ArrayList<Promise<Unit>>();

        for (var key : Set.copyOf(consumers.keySet())) {
            var remaining = consumers.computeIfPresent(key, (_, consumerSet) -> dropConsumer(consumerSet, sliceId));

            if (remaining != null) {
                continue;
            }

            var cached = promiseCache.remove(key);

            if (cached != null) {
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

    /// Remove one consumer, reporting the set as `null` once it is empty.
    ///
    /// `null` is `ConcurrentHashMap.computeIfPresent`'s "remove the mapping" signal, and returning
    /// it from inside the remapping function is what makes the drop-to-empty test atomic — the
    /// caller then knows it alone observed the transition.
    private static Set<String> dropConsumer(Set<String> consumerSet, String sliceId) {
        consumerSet.remove(sliceId);

        return consumerSet.isEmpty()
               ? null
               : consumerSet;
    }

    @SuppressWarnings("unchecked")
    private static Promise<Unit> closeThroughOwningFactory(Provisioned<?> provisioned) {
        var factory = (ResourceFactory<Object, ?>) provisioned.factory();

        return factory.close(provisioned.resource());
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

    private record CacheKey(Class<?> resourceType, String configSection) {}

    /// A provisioned resource together with the factory that actually built it, so the release
    /// path closes through the SAME factory rather than guessing at the head of the priority list.
    private record Provisioned<T>(T resource, ResourceFactory<T, ?> factory) {}
}
