// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.dependency.DependencyResolver;
import org.pragmatica.aether.slice.dependency.SliceRegistry;
import org.pragmatica.aether.slice.repository.Location;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.IntrinsicConfigProvider;
import org.pragmatica.config.LayeredConfigProvider;
import org.pragmatica.config.NamedConfigProvider;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.utils.Causes.cause;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-LAM-01", "JBCT-LAM-02", "JBCT-NEST-01", "JBCT-UTIL-02"})
public interface SliceStore {
    static SliceStore sliceStore(SliceRegistry registry,
                                 List<Repository> repositories,
                                 SharedLibraryClassLoader sharedLibraryLoader,
                                 SliceInvokerFacade invokerFacade,
                                 SliceActionConfig config) {
        return sliceStore(registry, repositories, sharedLibraryLoader, invokerFacade, noOpResourceProvider(), config);
    }

    static SliceStore sliceStore(SliceRegistry registry,
                                 List<Repository> repositories,
                                 SharedLibraryClassLoader sharedLibraryLoader,
                                 SliceInvokerFacade invokerFacade,
                                 ResourceProviderFacade resourceFacade,
                                 SliceActionConfig config) {
        return sliceStore(registry,
                          repositories,
                          sharedLibraryLoader,
                          invokerFacade,
                          resourceFacade,
                          config,
                          Option.empty());
    }

    /// Constructor variant that accepts the node-composite (`KV-overlay ⊕ node.toml`).
    ///
    /// When present, each successfully loaded slice gets its own slice-composite
    /// (`slice.toml ⊕ nodeComposite`) attached to the `LoadedSliceEntry`. The slice.toml
    /// is the `META-INF/resources.toml` parsed from the slice JAR locally — it never
    /// round-trips through KV.
    ///
    /// @param nodeComposite Layered node-level config (KV-overlay on top, node.toml beneath);
    ///                      pass `Option.none()` to disable per-slice config (slices receive
    ///                      no `sliceConfig` on their loaded-context).
    static SliceStore sliceStore(SliceRegistry registry,
                                 List<Repository> repositories,
                                 SharedLibraryClassLoader sharedLibraryLoader,
                                 SliceInvokerFacade invokerFacade,
                                 ResourceProviderFacade resourceFacade,
                                 SliceActionConfig config,
                                 Option<ConfigurationProvider> nodeComposite) {
        return new sliceStore(registry,
                              repositories,
                              sharedLibraryLoader,
                              invokerFacade,
                              resourceFacade,
                              config,
                              nodeComposite,
                              new ConcurrentHashMap<>());
    }

    private static ResourceProviderFacade noOpResourceProvider() {
        return new ResourceProviderFacade() {
            private static final Cause NOT_CONFIGURED = cause("Resource provisioning not configured. "
                                                             + "Use AetherNodeConfig.withConfigProvider() to enable resource provisioning.");

            @Override
            public <T> Promise<T> provide(Class<T> resourceType, String configSection) {
                return NOT_CONFIGURED.promise();
            }

            @Override
            public <T> Promise<T> provide(Class<T> resourceType, String configSection, ProvisioningContext context) {
                return NOT_CONFIGURED.promise();
            }
        };
    }

    interface LoadedSlice {
        Artifact artifact();
        Slice slice();
    }

    List<LoadedSlice> loaded();
    Promise<LoadedSlice> loadSlice(Artifact artifact);
    Promise<LoadedSlice> activateSlice(Artifact artifact);
    Promise<LoadedSlice> deactivateSlice(Artifact artifact);

    Promise<Unit> unloadSlice(Artifact artifact);

    /// Return the slice-composite (`slice.toml ⊕ nodeComposite`) for a loaded slice.
    ///
    /// Returns `Option.none()` when:
    /// - The slice is not loaded
    /// - The slice's load Promise is unresolved or failed
    /// - The store was constructed without a `nodeComposite` (per-slice config disabled)
    ///
    /// Callers (e.g. `NodeDeploymentState`) should consult this during activation to
    /// resolve per-slice configuration (topics, schedules, streams, @ConfigUpdate
    /// sections) without falling back to the global `ConfigService.instance()` singleton.
    Option<ConfigurationProvider> sliceComposite(Artifact artifact);

    enum EntryState {
        LOADED,
        ACTIVE
    }

    record LoadedSliceEntry(Artifact artifact,
                            Slice sliceInstance,
                            SliceClassLoader classLoader,
                            SliceLoadingContext loadingContext,
                            Option<ConfigurationProvider> sliceConfig,
                            EntryState state) implements LoadedSlice {
        @Override
        public Slice slice() {
            return sliceInstance;
        }

        LoadedSliceEntry withState(EntryState newState) {
            return new LoadedSliceEntry(artifact, sliceInstance, classLoader, loadingContext, sliceConfig, newState);
        }

        LoadedSlice asLoadedSlice() {
            return this;
        }
    }

    record sliceStore(SliceRegistry registry,
                      List<Repository> repositories,
                      SharedLibraryClassLoader sharedLibraryLoader,
                      SliceInvokerFacade invokerFacade,
                      ResourceProviderFacade resourceFacade,
                      SliceActionConfig config,
                      Option<ConfigurationProvider> nodeComposite,
                      ConcurrentHashMap<Artifact, Promise<LoadedSliceEntry>> entries) implements SliceStore {
        private static final Logger log = LoggerFactory.getLogger(sliceStore.class);
        private static final String SLICE_RESOURCES_TOML = "META-INF/resources.toml";

        @Override
        public Promise<LoadedSlice> loadSlice(Artifact artifact) {
            return entries.computeIfAbsent(artifact, this::startLoading)
                          .map(entry -> (LoadedSlice) entry);
        }

        private Promise<LoadedSliceEntry> startLoading(Artifact artifact) {
            log.debug("Loading slice {}", artifact);

            return loadFromLocation(artifact).onFailure(_ -> CompletableFuture.runAsync(() -> entries.remove(artifact)));
        }

        private Promise<LoadedSliceEntry> loadFromLocation(Artifact artifact) {
            return DependencyResolver.resolveWithContext(artifact,
                                                         compositeRepository(),
                                                         registry,
                                                         sharedLibraryLoader,
                                                         invokerFacade,
                                                         resourceFacade,
                                                         Option.some(classLoader -> buildSliceCompositeFromClassLoader(artifact,
                                                                                                                       classLoader)))
                                     .map(resolved -> {
                                              var sliceClassLoader = resolved.slice()
                                                                             .getClass()
                                                                             .getClassLoader();

                                              if (sliceClassLoader instanceof SliceClassLoader scl) {
                                              return createEntry(artifact,
                                                                 resolved.slice(),
                                                                 scl,
                                                                 resolved.loadingContext());
                                          }

                                              log.warn("Slice {} loaded with unexpected classloader type: {}. Resource access may be limited.",
                                                       artifact,
                                                       sliceClassLoader.getClass().getName());

                                              return createEntry(artifact,
                                                                 resolved.slice(),
                                                                 new SliceClassLoader(new URL[0], sharedLibraryLoader),
                                                                 resolved.loadingContext());
                                          })
                                     .onFailure(cause -> log.error("Failed to load slice {}: {}",
                                                                   artifact,
                                                                   cause.message()));
        }

        /// Build the slice-composite (`slice.toml ⊕ nodeComposite`) from the slice classloader.
        ///
        /// Reads `META-INF/resources.toml` via the slice classloader, parses it into a flat
        /// key/value map, wraps it in an `IntrinsicConfigProvider`, and layers it over the
        /// node-composite. Returns `Option.none()` when the store was constructed without a
        /// node-composite (per-slice config disabled).
        ///
        /// Emits one INFO log per intrinsic key whose value is shadowed by an existing KV
        /// override at slice-load time (operator override preceded slice deploy) — see
        /// [#logShadowedKeys].
        private Option<ConfigurationProvider> buildSliceCompositeFromClassLoader(Artifact artifact,
                                                                                 ClassLoader classLoader) {
            return nodeComposite.flatMap(composite -> loadSliceIntrinsicProviderFromClassLoader(artifact, classLoader).map(intrinsic -> assembleSliceComposite(artifact,
                                                                                                                                                               intrinsic,
                                                                                                                                                               composite)));
        }

        // Package-private (not private) so SliceStoreTest can pin the override precedence
        // directly — this ordering is load-bearing and was previously inverted.
        static ConfigurationProvider assembleSliceComposite(Artifact artifact,
                                                                    ConfigurationProvider intrinsic,
                                                                    ConfigurationProvider composite) {
            logShadowedKeys(artifact, intrinsic, composite);
            var labelledIntrinsic = NamedConfigProvider.namedConfigProvider("slice.toml", intrinsic);

            // Override precedence: the node-composite (operator KV-overlay ⊕ node.toml) WINS over
            // the slice's intrinsic resources.toml. The slice ships LOCAL defaults that each
            // deployment overrides with environment-specific values (see the resources.toml header
            // and logShadowedKeys above — "intrinsic shadowed by operator override"). Since
            // LayeredConfigProvider is first-wins (index 0 = top priority), the composite must come
            // FIRST; slice.toml is the fallback only for keys the deployment does not override.
            // (Identical local/deployment values — e.g. docker's node aether.toml matching the
            // slice — make the order moot, which is why this was latent until a divergent cloud
            // deployment exercised it.)
            return LayeredConfigProvider.layered(List.of(composite, labelledIntrinsic));
        }

        /// Emit one INFO log entry per intrinsic key whose value is shadowed by an existing
        /// operator override in the node-composite (typically the KV-overlay layer). Triggered
        /// at slice-load time only; subsequent reads are silent.
        private static void logShadowedKeys(Artifact artifact,
                                            ConfigurationProvider intrinsic,
                                            ConfigurationProvider composite) {
            for (var key : intrinsic.keys()) {
                var intrinsicValue = intrinsic.getString(key);
                var overrideValue = composite.getString(key);

                if (intrinsicValue.isPresent() && overrideValue.isPresent() && !intrinsicValue.unwrap().equals(overrideValue.unwrap())) {
                    log.info("slice {} intrinsic key {} shadowed by operator override (intrinsic={}, override={})",
                             artifact.asString(),
                             key,
                             intrinsicValue.unwrap(),
                             overrideValue.unwrap());
                }
            }
        }

        private Option<ConfigurationProvider> loadSliceIntrinsicProviderFromClassLoader(Artifact artifact,
                                                                                        ClassLoader classLoader) {
            var tomlContent = readSliceResourcesTomlFromClassLoader(classLoader);

            if (tomlContent.isEmpty()) {
                log.debug("Slice {} has no {}; intrinsic config provider omitted", artifact, SLICE_RESOURCES_TOML);

                return Option.some(IntrinsicConfigProvider.intrinsicConfigProvider(artifact.asString(), Map.of()));
            }

            return tomlContent.flatMap(content -> parseToFlatMap(artifact, content))
                              .map(values -> {
                                       log.info("Slice {} intrinsic config loaded from {}: {} keys",
                                                artifact,
                                                SLICE_RESOURCES_TOML,
                                                values.size());

                                       return IntrinsicConfigProvider.intrinsicConfigProvider(artifact.asString(),
                                                                                              values);
                                   });
        }

        @SuppressWarnings("JBCT-EX-01")
        private static Option<String> readSliceResourcesTomlFromClassLoader(ClassLoader classLoader) {
            try (var in = classLoader.getResourceAsStream(SLICE_RESOURCES_TOML)) {
                if (in == null) {
                    return Option.none();
                }

                return Option.some(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                log.warn("Failed to read {} from slice classloader: {}", SLICE_RESOURCES_TOML, e.getMessage());

                return Option.none();
            }
        }

        private LoadedSliceEntry createEntry(Artifact artifact,
                                             Slice slice,
                                             SliceClassLoader classLoader,
                                             SliceLoadingContext loadingContext) {
            var sliceConfig = loadingContext.sliceComposite().orElse(() -> buildSliceCompositeFromClassLoader(artifact,
                                                                                                              classLoader));
            var entry = new LoadedSliceEntry(artifact,
                                             slice,
                                             classLoader,
                                             loadingContext,
                                             sliceConfig,
                                             EntryState.LOADED);

            log.debug("Slice {} loaded", artifact);

            return entry;
        }

        private static Option<Map<String, String>> parseToFlatMap(Artifact artifact, String content) {
            return TomlParser.parse(content)
                             .map(sliceStore::flattenSections)
                             .onFailure(cause -> log.warn("Failed to parse {} for slice {}: {}",
                                                          SLICE_RESOURCES_TOML,
                                                          artifact,
                                                          cause.message()))
                             .option();
        }

        private static Map<String, String> flattenSections(TomlDocument doc) {
            var flat = new LinkedHashMap<String, String>();

            for (var sectionName : doc.sectionNames()) {
                if (sectionName.isEmpty()) {
                    continue;
                }

                var prefix = sectionName + ".";

                doc.getSection(sectionName).forEach((key, value) -> flat.put(prefix + key, value));
            }

            return flat;
        }

        @Override
        public Promise<LoadedSlice> activateSlice(Artifact artifact) {
            return option(entries.get(artifact)).toResult(SLICE_NOT_LOADED.apply(artifact.asString()))
                         .async()
                         .flatMap(entryPromise -> entryPromise.flatMap(entry -> activateEntry(artifact, entry)));
        }

        private Promise<LoadedSlice> activateEntry(Artifact artifact, LoadedSliceEntry entry) {
            if (entry.state() == EntryState.ACTIVE) {
                log.debug("Slice {} already active", artifact);

                return Promise.success(entry);
            }

            if (entry.state() != EntryState.LOADED) {
                return INVALID_STATE_TRANSITION.apply(entry.state() + " → ACTIVE").promise();
            }

            log.debug("Activating slice {}", artifact);

            return materializeHandles(artifact, entry).flatMap(_ -> entry.sliceInstance()
                                                                         .start()
                                                                         .timeout(config.startStopTimeout()))
                                     .map(_ -> transitionToActive(artifact, entry))
                                     .onFailure(cause -> log.error("Failed to activate slice {}: {}",
                                                                   artifact,
                                                                   cause.message()));
        }

        private Promise<Unit> materializeHandles(Artifact artifact, LoadedSliceEntry entry) {
            var loadingContext = entry.loadingContext();

            if (loadingContext == null) {
                log.debug("No loading context for slice {}, skipping materialization", artifact);

                return Promise.unitPromise();
            }

            log.debug("Materializing {} handles for slice {}", loadingContext.bufferedHandleCount(), artifact);

            return loadingContext.materializeAll()
                                 .onSuccess(_ -> loadingContext.markMaterialized())
                                 .async();
        }

        private LoadedSlice transitionToActive(Artifact artifact, LoadedSliceEntry entry) {
            var activeEntry = entry.withState(EntryState.ACTIVE);

            entries.put(artifact, Promise.success(activeEntry));
            log.debug("Slice {} activated", artifact);

            return activeEntry;
        }

        @Override
        public Promise<LoadedSlice> deactivateSlice(Artifact artifact) {
            return option(entries.get(artifact)).toResult(SLICE_NOT_LOADED.apply(artifact.asString()))
                         .async()
                         .flatMap(entryPromise -> entryPromise.flatMap(entry -> deactivateEntry(artifact, entry)));
        }

        private Promise<LoadedSlice> deactivateEntry(Artifact artifact, LoadedSliceEntry entry) {
            if (entry.state() == EntryState.LOADED) {
                log.debug("Slice {} already deactivated", artifact);

                return Promise.success(entry);
            }

            if (entry.state() != EntryState.ACTIVE) {
                return INVALID_STATE_TRANSITION.apply(entry.state() + " → LOADED").promise();
            }

            log.debug("Deactivating slice {}", artifact);

            return entry.sliceInstance()
                        .stop()
                        .timeout(config.startStopTimeout())
                        .map(_ -> transitionToLoaded(artifact, entry))
                        .onFailure(cause -> log.warn("Failed to deactivate slice {}: {}",
                                                     artifact,
                                                     cause.message()));
        }

        private LoadedSlice transitionToLoaded(Artifact artifact, LoadedSliceEntry entry) {
            var loadedEntry = entry.withState(EntryState.LOADED);

            entries.put(artifact, Promise.success(loadedEntry));
            log.debug("Slice {} deactivated", artifact);

            return loadedEntry;
        }

        @Override
        public Promise<Unit> unloadSlice(Artifact artifact) {
            return option(entries.remove(artifact)).map(entryPromise -> entryPromise.fold(result -> result.fold(cause -> skipFailedUnload(artifact,
                                                                                                                                          cause),
                                                                                                                entry -> unloadEntry(artifact,
                                                                                                                                     entry))))
                         .or(() -> {
                                 log.debug("Slice {} not loaded, nothing to unload", artifact);

                                 return Promise.unitPromise();
                             });
        }

        private Promise<Unit> skipFailedUnload(Artifact artifact, Cause cause) {
            log.debug("Slice {} was in failed state ({}), nothing to unload", artifact, cause.message());

            return Promise.unitPromise();
        }

        private Promise<Unit> unloadEntry(Artifact artifact, LoadedSliceEntry entry) {
            log.debug("Unloading slice {}", artifact);
            Promise<Unit> deactivatePromise = entry.state() == EntryState.ACTIVE
                                              ? entry.sliceInstance().stop().timeout(config.startStopTimeout())
                                              : Promise.unitPromise();

            return deactivatePromise.map(_ -> cleanup(artifact, entry))
                                    .onFailure(cause -> log.warn("Failed to unload slice {}: {}",
                                                                 artifact,
                                                                 cause.message()));
        }

        private Unit cleanup(Artifact artifact, LoadedSliceEntry entry) {
            registry.unregister(artifact);
            closeClassLoader(entry.classLoader());
            entries.remove(artifact);
            log.debug("Slice {} unloaded", artifact);

            return Unit.unit();
        }

        @Override
        public List<LoadedSlice> loaded() {
            return entries.values()
                          .stream()
                          .filter(Promise::isResolved)
                          .flatMap(promise -> promise.await()
                                                     .fold(_ -> Stream.empty(),
                                                           entry -> Stream.of(entry.asLoadedSlice())))
                          .toList();
        }

        @Override
        public Option<ConfigurationProvider> sliceComposite(Artifact artifact) {
            return option(entries.get(artifact)).filter(Promise::isResolved)
                         .flatMap(promise -> promise.await()
                                                    .option())
                         .flatMap(LoadedSliceEntry::sliceConfig);
        }

        private Promise<Location> locateInRepositories(Artifact artifact) {
            return locateInRepositories(artifact, repositories);
        }

        private Promise<Location> locateInRepositories(Artifact artifact, List<Repository> remainingRepos) {
            if (remainingRepos.isEmpty()) {
                return ARTIFACT_NOT_FOUND.apply(artifact.asString()).promise();
            }

            var repo = remainingRepos.getFirst();
            var rest = remainingRepos.subList(1, remainingRepos.size());

            return repo.locate(artifact)
                       .orElse(() -> locateInRepositories(artifact, rest));
        }

        private Repository compositeRepository() {
            return this::locateInRepositories;
        }

        private void closeClassLoader(SliceClassLoader classLoader) {
            try {
                classLoader.close();
            } catch (IOException e) {
                log.warn("Failed to close ClassLoader: {}", e.getMessage());
            }
        }

        private static final Fn1<Cause, String> SLICE_NOT_LOADED = Causes.forOneValue("Slice not loaded: %s");

        private static final Fn1<Cause, String> INVALID_STATE_TRANSITION = Causes.forOneValue("Invalid state transition: %s");

        private static final Fn1<Cause, String> ARTIFACT_NOT_FOUND = SliceLoadingFailure.Intermittent.ArtifactNotFound::new;
    }
}
