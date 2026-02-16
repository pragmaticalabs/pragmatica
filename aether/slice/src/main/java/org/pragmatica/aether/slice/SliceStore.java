package org.pragmatica.aether.slice;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.dependency.DependencyResolver;
import org.pragmatica.aether.slice.dependency.SliceRegistry;
import org.pragmatica.aether.slice.repository.Location;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.utils.Causes.cause;

@SuppressWarnings({"JBCT-VO-01", "JBCT-SEQ-01", "JBCT-LAM-01", "JBCT-LAM-02", "JBCT-NEST-01", "JBCT-UTIL-02"})
public interface SliceStore {
    /// Create a new SliceStore instance with shared library classloader.
    ///
    /// @param registry            Registry for tracking loaded slices
    /// @param repositories        Repositories to search for slice JARs
    /// @param sharedLibraryLoader ClassLoader for shared dependencies across slices
    /// @param invokerFacade       Facade for inter-slice invocation (used by generated factories)
    /// @param config              Configuration for slice lifecycle timeouts
    ///
    /// @return SliceStore implementation
    static SliceStore sliceStore(SliceRegistry registry,
                                 List<Repository> repositories,
                                 SharedLibraryClassLoader sharedLibraryLoader,
                                 SliceInvokerFacade invokerFacade,
                                 SliceActionConfig config) {
        return sliceStore(registry, repositories, sharedLibraryLoader, invokerFacade, noOpResourceProvider(), config);
    }

    /// Create a new SliceStore instance with shared library classloader and resource provider.
    ///
    /// @param registry            Registry for tracking loaded slices
    /// @param repositories        Repositories to search for slice JARs
    /// @param sharedLibraryLoader ClassLoader for shared dependencies across slices
    /// @param invokerFacade       Facade for inter-slice invocation (used by generated factories)
    /// @param resourceFacade      Facade for resource provisioning (used by generated factories)
    /// @param config              Configuration for slice lifecycle timeouts
    ///
    /// @return SliceStore implementation
    static SliceStore sliceStore(SliceRegistry registry,
                                 List<Repository> repositories,
                                 SharedLibraryClassLoader sharedLibraryLoader,
                                 SliceInvokerFacade invokerFacade,
                                 ResourceProviderFacade resourceFacade,
                                 SliceActionConfig config) {
        return new sliceStore(registry,
                              repositories,
                              sharedLibraryLoader,
                              invokerFacade,
                              resourceFacade,
                              config,
                              new ConcurrentHashMap<>());
    }

    private static ResourceProviderFacade noOpResourceProvider() {
        return new ResourceProviderFacade() {
            @Override
            public <T> Promise<T> provide(Class<T> resourceType, String configSection) {
                return cause("Resource provisioning not configured. "
                             + "Use AetherNodeConfig.withConfigProvider() to enable resource provisioning.")
                .promise();
            }
        };
    }

    interface LoadedSlice {
        Artifact artifact();

        Slice slice();
    }

    List<LoadedSlice> loaded();

    /// Load a slice into memory but do not activate it.
    /// This corresponds to the LOADING → LOADED state transition.
    Promise<LoadedSlice> loadSlice(Artifact artifact);

    /// Activate a previously loaded slice, making it ready to serve requests.
    /// This corresponds to the ACTIVATING → ACTIVE state transition.
    Promise<LoadedSlice> activateSlice(Artifact artifact);

    /// Deactivate an active slice, but keep it loaded in memory.
    /// This corresponds to the DEACTIVATING → LOADED state transition.
    Promise<LoadedSlice> deactivateSlice(Artifact artifact);

    /// Unload a slice from memory completely.
    /// This corresponds to the UNLOADING → (removed) state transition.
    Promise<Unit> unloadSlice(Artifact artifact);

    enum EntryState {
        LOADED,
        ACTIVE
    }

    record LoadedSliceEntry(Artifact artifact,
                            Slice sliceInstance,
                            SliceClassLoader classLoader,
                            SliceLoadingContext loadingContext,
                            EntryState state) implements LoadedSlice {
        @Override
        public Slice slice() {
            return sliceInstance;
        }

        LoadedSliceEntry withState(EntryState newState) {
            return new LoadedSliceEntry(artifact, sliceInstance, classLoader, loadingContext, newState);
        }

        LoadedSlice asLoadedSlice() {
            return this;
        }
    }

    /// Thread-safe slice store using ConcurrentHashMap with Promise values.
    /// Storing Promise<LoadedSliceEntry> allows computeIfAbsent to atomically start loading
    /// and return the same Promise to concurrent callers. Operations that need the entry
    /// simply flatMap on the Promise.
    record sliceStore(SliceRegistry registry,
                      List<Repository> repositories,
                      SharedLibraryClassLoader sharedLibraryLoader,
                      SliceInvokerFacade invokerFacade,
                      ResourceProviderFacade resourceFacade,
                      SliceActionConfig config,
                      ConcurrentHashMap<Artifact, Promise<LoadedSliceEntry>> entries) implements SliceStore {
        private static final Logger log = LoggerFactory.getLogger(sliceStore.class);

        @Override
        public Promise<LoadedSlice> loadSlice(Artifact artifact) {
            return entries.computeIfAbsent(artifact, this::startLoading)
                          .map(entry -> (LoadedSlice) entry);
        }

        private Promise<LoadedSliceEntry> startLoading(Artifact artifact) {
            log.debug("Loading slice {}", artifact);
            return loadFromLocation(artifact)
            .onFailure(_ -> CompletableFuture.runAsync(() -> entries.remove(artifact)));
        }

        private Promise<LoadedSliceEntry> loadFromLocation(Artifact artifact) {
            return DependencyResolver.resolveWithContext(artifact,
                                                         compositeRepository(),
                                                         registry,
                                                         sharedLibraryLoader,
                                                         invokerFacade,
                                                         resourceFacade)
                                     .map(resolved -> {
                                              // Extract the classloader from the slice's class
            var sliceClassLoader = resolved.slice()
                                           .getClass()
                                           .getClassLoader();
                                              if (sliceClassLoader instanceof SliceClassLoader scl) {
                                                  return createEntry(artifact,
                                                                     resolved.slice(),
                                                                     scl,
                                                                     resolved.loadingContext());
                                              }
                                              // Fallback - create a minimal classloader entry
            log.warn("Slice {} loaded with unexpected classloader type: {}. Resource access may be limited.",
                     artifact,
                     sliceClassLoader.getClass()
                                     .getName());
                                              return createEntry(artifact,
                                                                 resolved.slice(),
                                                                 new SliceClassLoader(new URL[0], sharedLibraryLoader),
                                                                 resolved.loadingContext());
                                          })
                                     .onFailure(cause -> log.error("Failed to load slice {}: {}",
                                                                   artifact,
                                                                   cause.message()));
        }

        private LoadedSliceEntry createEntry(Artifact artifact,
                                             Slice slice,
                                             SliceClassLoader classLoader,
                                             SliceLoadingContext loadingContext) {
            var entry = new LoadedSliceEntry(artifact, slice, classLoader, loadingContext, EntryState.LOADED);
            log.debug("Slice {} loaded", artifact);
            return entry;
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
                return INVALID_STATE_TRANSITION.apply(entry.state() + " → ACTIVE")
                                               .promise();
            }
            log.debug("Activating slice {}", artifact);
            // Eager dependency validation: materialize all method handles before start()
            // This ensures no technical failures occur after the slice reaches ACTIVE state
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
                return INVALID_STATE_TRANSITION.apply(entry.state() + " → LOADED")
                                               .promise();
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
                                              ? entry.sliceInstance()
                                                     .stop()
                                                     .timeout(config.startStopTimeout())
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

        private Promise<Location> locateInRepositories(Artifact artifact) {
            return locateInRepositories(artifact, repositories);
        }

        private Promise<Location> locateInRepositories(Artifact artifact, List<Repository> remainingRepos) {
            if (remainingRepos.isEmpty()) {
                return ARTIFACT_NOT_FOUND.apply(artifact.asString())
                                         .promise();
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
            try{
                classLoader.close();
            } catch (IOException e) {
                log.warn("Failed to close ClassLoader: {}", e.getMessage());
            }
        }

        private static final Fn1<Cause, String> SLICE_NOT_LOADED = Causes.forOneValue("Slice not loaded: %s");

        private static final Fn1<Cause, String> INVALID_STATE_TRANSITION = Causes.forOneValue("Invalid state transition: %s");

        private static final Fn1<Cause, String> ARTIFACT_NOT_FOUND = Causes.forOneValue("Artifact not found in any repository: %s");
    }
}
