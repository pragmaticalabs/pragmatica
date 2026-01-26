package org.pragmatica.aether.slice;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.dependency.DependencyResolver;
import org.pragmatica.aether.slice.dependency.DependencyResolver.ResolvedSlice;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production implementation of SliceStore that manages slice lifecycle.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Load slices from repository via DependencyResolver</li>
 *   <li>Activate slices by calling start()</li>
 *   <li>Deactivate slices by calling stop()</li>
 *   <li>Unload slices and cleanup ClassLoaders</li>
 *   <li>Track slice state (LOADED/ACTIVE)</li>
 * </ul>
 * <p>
 * Thread-safe: Uses ConcurrentHashMap for entry tracking.
 */
public interface SliceStoreImpl {
    enum EntryState {
        LOADED,
        ACTIVE
    }

    record LoadedSliceEntry(Artifact artifact,
                            Slice sliceInstance,
                            SliceClassLoader classLoader,
                            SliceLoadingContext loadingContext,
                            EntryState state) implements SliceStore.LoadedSlice {
        @Override
        public Slice slice() {
            return sliceInstance;
        }

        LoadedSliceEntry withState(EntryState newState) {
            return new LoadedSliceEntry(artifact, sliceInstance, classLoader, loadingContext, newState);
        }

        SliceStore.LoadedSlice asLoadedSlice() {
            return this;
        }
    }

    static SliceStore sliceStore(SliceRegistry registry,
                                 List<Repository> repositories,
                                 SharedLibraryClassLoader sharedLibraryLoader,
                                 SliceInvokerFacade invokerFacade,
                                 SliceActionConfig config) {
        return new SliceStoreRecord(registry,
                                    repositories,
                                    sharedLibraryLoader,
                                    invokerFacade,
                                    config,
                                    new ConcurrentHashMap<>());
    }

    /**
     * Thread-safe slice store using ConcurrentHashMap with Promise values.
     * Storing Promise<LoadedSliceEntry> allows computeIfAbsent to atomically start loading
     * and return the same Promise to concurrent callers. Operations that need the entry
     * simply flatMap on the Promise.
     */
    record SliceStoreRecord(SliceRegistry registry,
                            List<Repository> repositories,
                            SharedLibraryClassLoader sharedLibraryLoader,
                            SliceInvokerFacade invokerFacade,
                            SliceActionConfig config,
                            ConcurrentHashMap<Artifact, Promise<LoadedSliceEntry>> entries) implements SliceStore {
        private static final Logger log = LoggerFactory.getLogger(SliceStoreRecord.class);

        @Override
        public Promise<LoadedSlice> loadSlice(Artifact artifact) {
            return entries.computeIfAbsent(artifact, this::startLoading)
                          .map(entry -> (LoadedSlice) entry);
        }

        private Promise<LoadedSliceEntry> startLoading(Artifact artifact) {
            log.info("Loading slice {}", artifact);
            return locateInRepositories(artifact).flatMap(_ -> loadFromLocation(artifact))
                                       .onFailure(_ -> CompletableFuture.runAsync(() -> entries.remove(artifact)));
        }

        private Promise<LoadedSliceEntry> loadFromLocation(Artifact artifact) {
            return DependencyResolver.resolveWithContext(artifact,
                                                         compositeRepository(),
                                                         registry,
                                                         sharedLibraryLoader,
                                                         invokerFacade)
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
            log.info("Slice {} loaded successfully", artifact);
            return entry;
        }

        @Override
        public Promise<LoadedSlice> activateSlice(Artifact artifact) {
            return Option.option(entries.get(artifact))
                         .toResult(SLICE_NOT_LOADED.apply(artifact.asString()))
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
            log.info("Activating slice {}", artifact);
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
            log.info("Slice {} activated successfully", artifact);
            return activeEntry;
        }

        @Override
        public Promise<LoadedSlice> deactivateSlice(Artifact artifact) {
            return Option.option(entries.get(artifact))
                         .toResult(SLICE_NOT_LOADED.apply(artifact.asString()))
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
            log.info("Deactivating slice {}", artifact);
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
            log.info("Slice {} deactivated successfully", artifact);
            return loadedEntry;
        }

        @Override
        public Promise<Unit> unloadSlice(Artifact artifact) {
            return Option.option(entries.get(artifact))
                         .map(entryPromise -> entryPromise.flatMap(entry -> unloadEntry(artifact, entry)))
                         .or(() -> {
                                 log.debug("Slice {} not loaded, nothing to unload", artifact);
                                 return Promise.unitPromise();
                             });
        }

        private Promise<Unit> unloadEntry(Artifact artifact, LoadedSliceEntry entry) {
            log.info("Unloading slice {}", artifact);
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
            log.info("Slice {} unloaded successfully", artifact);
            return Unit.unit();
        }

        @Override
        public List<LoadedSlice> loaded() {
            return entries.values()
                          .stream()
                          .filter(Promise::isResolved)
                          .flatMap(promise -> promise.await()
                                                     .fold(_ -> java.util.stream.Stream.empty(),
                                                           entry -> java.util.stream.Stream.of(entry.asLoadedSlice())))
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
