// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.dependency.DependencyResolver;
import org.pragmatica.aether.slice.dependency.SliceRegistry;
import org.pragmatica.aether.slice.repository.Location;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.serialization.SliceCodec;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.utils.Causes.cause;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-LAM-01", "JBCT-LAM-02", "JBCT-NEST-01", "JBCT-UTIL-02"})
public interface SliceStore {
    /// Store with resource provisioning disabled entirely — no node provider, and therefore no
    /// slice-scoped overlay either.
    ///
    /// Named for what it omits, deliberately. `SliceStore` is a public interface, so its statics are
    /// public and a caller in another module can reach this; if it were just another `sliceStore`
    /// arm, opting out of resource provisioning would be what you get for writing fewer arguments,
    /// and #773 is exactly the class of defect that hides in that silence. The name makes the
    /// omission legible at the call site and greppable across the tree.
    static SliceStore sliceStoreWithoutResourceProvisioning(SliceRegistry registry,
                                                            List<Repository> repositories,
                                                            SharedLibraryClassLoader sharedLibraryLoader,
                                                            SliceInvokerFacade invokerFacade,
                                                            SliceActionConfig config) {
        return sliceStore(registry,
                          repositories,
                          sharedLibraryLoader,
                          invokerFacade,
                          noOpResourceProvider(),
                          config,
                          Option.empty(),
                          Option.empty(),
                          Option.empty(),
                          SliceLoadingContext.noResourceOverlay());
    }

    /// @param nodeComposite          Layered node-level config (KV-overlay on top, node.toml
    ///                               beneath); `Option.none()` disables per-slice config.
    /// @param nodeCodec              Parent codec each slice's own codec is layered on, so a slice's
    ///                               resources serialize the application's own record types (#526).
    /// @param secretResolver         Resolves `${secrets:path}` in the slice-intrinsic
    ///                               `resources.toml` layer (#269), all-or-nothing per key set.
    /// @param resourceOverlayBuilder Given the slice's classloader, yields the provider that answers
    ///                               for `ResourceFactory` implementations shipped INSIDE the slice
    ///                               jar, delegating everything else to the node provider (#773).
    ///                               This module cannot build one — the resource SPI is not on its
    ///                               classpath — so it is injected here and passed through, exactly
    ///                               like the slice-composite builder.
    ///
    ///                               REQUIRED, and deliberately not an `Option`: a user-defined
    ///                               resource type being unreachable is invisible at runtime — the
    ///                               slice deploys and then fails at load — so the guarantee that
    ///                               somebody supplied this is worth a compile error. Pass
    ///                               [SliceLoadingContext#noResourceOverlay] to opt out in writing.
    static SliceStore sliceStore(SliceRegistry registry,
                                 List<Repository> repositories,
                                 SharedLibraryClassLoader sharedLibraryLoader,
                                 SliceInvokerFacade invokerFacade,
                                 ResourceProviderFacade resourceFacade,
                                 SliceActionConfig config,
                                 Option<ConfigurationProvider> nodeComposite,
                                 Option<SliceCodec> nodeCodec,
                                 Option<Fn1<Promise<String>, String>> secretResolver,
                                 Fn1<Option<ResourceProviderFacade>, ClassLoader> resourceOverlayBuilder) {
        return new sliceStore(registry,
                              repositories,
                              sharedLibraryLoader,
                              invokerFacade,
                              resourceFacade,
                              config,
                              nodeComposite,
                              nodeCodec,
                              secretResolver,
                              resourceOverlayBuilder,
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
                      Option<SliceCodec> nodeCodec,
                      Option<Fn1<Promise<String>, String>> secretResolver,
                      Fn1<Option<ResourceProviderFacade>, ClassLoader> resourceOverlayBuilder,
                      ConcurrentHashMap<Artifact, Promise<LoadedSliceEntry>> entries) implements SliceStore {
        private static final Logger log = LoggerFactory.getLogger(sliceStore.class);
        private static final String SLICE_RESOURCES_TOML = "META-INF/resources.toml";

        @Override
        public Promise<LoadedSlice> loadSlice(Artifact artifact) {
            // Failed-load eviction is attached HERE, after computeIfAbsent returns — attaching it
            // inside the mapping function would fire synchronously-failed callbacks reentrantly
            // on the same key (the reason the old code needed a CompletableFuture.runAsync hop).
            return entries.computeIfAbsent(artifact, this::startLoading)
                          .onFailure(_ -> entries.remove(artifact))
                          .map(entry -> (LoadedSlice) entry);
        }

        private Promise<LoadedSliceEntry> startLoading(Artifact artifact) {
            log.debug("Loading slice {}", artifact);

            return loadFromLocation(artifact);
        }

        private Promise<LoadedSliceEntry> loadFromLocation(Artifact artifact) {
            return DependencyResolver.resolveWithContext(artifact,
                                                         compositeRepository(),
                                                         registry,
                                                         sharedLibraryLoader,
                                                         invokerFacade,
                                                         resourceFacade,
                                                         Option.some(classLoader -> buildSliceCompositeFromClassLoader(artifact,
                                                                                                                       classLoader)),
                                                         nodeCodec,
                                                         resourceOverlayBuilder)
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
        ///
        /// Key names only, never values (R5): post-#269 the intrinsic layer can carry a RESOLVED
        /// secret where before it only ever carried a literal placeholder — a value logged here
        /// would leak it. [#shadowedKeys] does the comparison and hands back key names only, so a
        /// value can never reach this call structurally, not merely by care.
        private static void logShadowedKeys(Artifact artifact,
                                            ConfigurationProvider intrinsic,
                                            ConfigurationProvider composite) {
            for (var key : shadowedKeys(intrinsic, composite)) {
                log.info("slice {} intrinsic key {} shadowed by operator override", artifact.asString(), key);
            }
        }

        /// The shadowed-key comparison, split out from [#logShadowedKeys] so the redaction (key
        /// names only, never the intrinsic/override values) is pinned directly by SliceStoreTest —
        /// this codebase's log backend (log4j2) doesn't support capturing appender output in unit
        /// tests, so the testable surface is this return value, not the log line itself.
        static List<String> shadowedKeys(ConfigurationProvider intrinsic, ConfigurationProvider composite) {
            var shadowed = new ArrayList<String>();

            for (var key : intrinsic.keys()) {
                var intrinsicValue = intrinsic.getString(key);
                var overrideValue = composite.getString(key);

                if (intrinsicValue.isPresent() && overrideValue.isPresent() && !intrinsicValue.unwrap()
                                                                                              .equals(overrideValue.unwrap())) {
                    shadowed.add(key);
                }
            }

            return shadowed;
        }

        private Option<ConfigurationProvider> loadSliceIntrinsicProviderFromClassLoader(Artifact artifact,
                                                                                        ClassLoader classLoader) {
            var tomlContent = readSliceResourcesTomlFromClassLoader(classLoader);

            if (tomlContent.isEmpty()) {
                log.debug("Slice {} has no {}; intrinsic config provider omitted", artifact, SLICE_RESOURCES_TOML);

                return Option.some(IntrinsicConfigProvider.intrinsicConfigProvider(artifact.asString(), Map.of()));
            }

            return tomlContent.flatMap(content -> parseToFlatMap(artifact, content))
                              .flatMap(values -> {
                                           log.info("Slice {} intrinsic config loaded from {}: {} keys",
                                                    artifact,
                                                    SLICE_RESOURCES_TOML,
                                                    values.size());
                                           var intrinsic = IntrinsicConfigProvider.intrinsicConfigProvider(artifact.asString(),
                                                                                                           values);

                                           return resolveIntrinsicSecrets(artifact, intrinsic, secretResolver);
                                       });
        }

        /// Resolve `${secrets:...}` placeholders in the slice-intrinsic layer, when a secret
        /// resolver is configured (#269). All-or-nothing per
        /// `ConfigurationProvider.withSecretResolution`: one failed key drops the WHOLE intrinsic
        /// layer — same convention as node.toml's own secret resolution in
        /// `AetherNode.createResourceProviderFacade`, and as this file's own pre-existing
        /// malformed-TOML-parse-failure path (see [#parseToFlatMap]) just above it. A dropped layer
        /// means every resource a slice declares ONLY in its own resources.toml (not overridden by
        /// node.toml/KV) fails at provision time as not-configured; see
        /// [#intrinsicSecretsDroppedMessage] for the operator-facing consequence line.
        ///
        /// Package-private (not private), static, and takes the resolver as an explicit parameter
        /// rather than reading `this.secretResolver` so SliceStoreTest can pin the
        /// success/failure/no-resolver paths directly, without constructing a full sliceStore.
        static Option<ConfigurationProvider> resolveIntrinsicSecrets(Artifact artifact,
                                                                     ConfigurationProvider intrinsic,
                                                                     Option<Fn1<Promise<String>, String>> secretResolver) {
            return secretResolver.fold(() -> Option.some(intrinsic),
                                       resolver -> ConfigurationProvider.withSecretResolution(intrinsic, resolver).fold(cause -> {
                                                                                                                            log.error(intrinsicSecretsDroppedMessage(artifact,
                                                                                                                                                                     cause));

                                                                                                                            return Option.<ConfigurationProvider> none();
                                                                                                                        },
                                                                                                                        Option::some));
        }

        /// The consequence-naming line for a dropped slice-intrinsic layer: names the slice, the
        /// failed key/secret path (via `cause.message()` — already safe, `SecretResolutionFailed`
        /// never carries the resolved value since resolution itself failed), and states plainly
        /// what breaks downstream so a later "not configured" provisioning error can be traced
        /// back here.
        ///
        /// Package-private (not private) and returns the built String instead of logging directly
        /// so SliceStoreTest can pin the exact wording without log-scraping — this codebase's log
        /// backend (log4j2) doesn't support capturing appender output in unit tests.
        static String intrinsicSecretsDroppedMessage(Artifact artifact, Cause cause) {
            return "Slice " + artifact.asString()
                 + " intrinsic config secret resolution failed (" + cause.message()
                 + "): dropping the ENTIRE slice-shipped config layer — resources declared only in "
                 + "this slice's resources.toml will fail as not-configured at provision time";
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
            var sliceConfig = loadingContext.sliceComposite()
                                            .orElse(() -> buildSliceCompositeFromClassLoader(artifact, classLoader));
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
