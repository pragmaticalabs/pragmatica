package org.pragmatica.aether.slice.manager;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.Slice.ActiveSlice;
import org.pragmatica.aether.slice.repository.Location;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.aether.slice.repository.maven.LocalRepository;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

public interface SliceStore {
    interface LoadedSlice {
        Artifact artifact();

        Result<ActiveSlice> slice();
    }

    // Legacy methods for backward compatibility
    Promise<ActiveSlice> load(Artifact artifact, Repository repository);

    Promise<Unit> unload(Artifact artifact);

    List<LoadedSlice> loadedSlices();


    /**
     * Load a slice into memory but do not activate it.
     * This corresponds to the LOADING → LOADED state transition.
     */
    Promise<LoadedSlice> loadSlice(Artifact artifact);

    /**
     * Activate a previously loaded slice, making it ready to serve requests.
     * This corresponds to the ACTIVATING → ACTIVE state transition.
     */
    Promise<ActiveSlice> activateSlice(Artifact artifact);

    /**
     * Deactivate an active slice, but keep it loaded in memory.
     * This corresponds to the DEACTIVATING → LOADED state transition.
     */
    Promise<LoadedSlice> deactivateSlice(Artifact artifact);

    /**
     * Unload a slice from memory completely.
     * This corresponds to the UNLOADING → (removed) state transition.
     */
    Promise<Unit> unloadSlice(Artifact artifact);

    static SliceStore sliceManager() {
        record loadedSlice(Artifact artifact, Result<ActiveSlice> slice) implements LoadedSlice {}

        record LoadedSliceWithUnstartedSlice(Artifact artifact, Slice unstartedSlice) implements LoadedSlice {
            @Override
            public Result<ActiveSlice> slice() {
                // Return a result that will start the slice when accessed
                return unstartedSlice.start().await();
            }
        }

        record store(Map<Artifact, Promise<ActiveSlice>> instances,
                     Map<Artifact, SliceClassLoader> classLoaders,
                     Map<Artifact, LoadedSlice> loadedInstances) implements
                SliceStore {
            private static final Logger log = LoggerFactory.getLogger(SliceStore.class);
            private static final Fn1<Cause, String> NO_SERVICE = Causes.forValue("No service found in {}");

            @Override
            public Promise<ActiveSlice> load(Artifact artifact, Repository repository) {
                log.debug("Loading slice {}", artifact);

                return instances.computeIfAbsent(artifact, _ -> loadSlice(artifact, repository));
            }

            @Override
            public Promise<Unit> unload(Artifact artifact) {
                var output = Promise.<Unit>promise();

                instances.computeIfPresent(artifact, (key, promise) -> {
                    // This covers all cases, including one when loading is not finished yet
                    promise.flatMap(ActiveSlice::stop)
                           .withResult(_ -> cleanupClassLoader(classLoaders.get(key)))
                           .withResult(_ -> output.succeed(Unit.unit()));
                    // Signal that key is removed from the map
                    return null;
                });

                return output;
            }

            @Override
            public List<LoadedSlice> loadedSlices() {
                return instances.entrySet()
                                .stream()
                                .filter(store::loadingFinished)
                                .map(this::asLoadedSlice)
                                .toList();
            }

            @Override
            public Promise<LoadedSlice> loadSlice(Artifact artifact) {
                log.debug("Loading slice {} (new lifecycle)", artifact);

                // If already loaded, return existing
                var existing = loadedInstances.get(artifact);
                if (existing != null) {
                    return Promise.success(existing);
                }

                // Find a repository to load from (simplified - would use repository registry in full implementation)
                var repository = findRepository(artifact);

                return repository.locate(artifact)
                                 .withSuccess(location -> log.debug("Repository provided: {}", location))
                                 .withFailure(cause -> log.warn("Unable to load {}, cause: {}", artifact, cause))
                                 .flatMap(location -> loadSliceFromLocation(location, artifact))
                                 .onSuccess(loadedSlice -> loadedInstances.put(artifact, loadedSlice));
            }

            @Override
            public Promise<ActiveSlice> activateSlice(Artifact artifact) {
                log.debug("Activating slice {}", artifact);

                var loadedSlice = loadedInstances.get(artifact);
                if (loadedSlice == null) {
                    return Causes.cause("Slice not loaded: " + artifact).promise();
                }

                return loadedSlice.slice()
                                  .map(activeSlice -> {
                                      // Move from loaded to active tracking
                                      instances.put(artifact, Promise.success(activeSlice));
                                      return activeSlice;
                                  })
                                  .fold(cause -> cause.promise(), Promise::success);
            }

            @Override
            public Promise<LoadedSlice> deactivateSlice(Artifact artifact) {
                log.debug("Deactivating slice {}", artifact);

                var activePromise = instances.get(artifact);
                if (activePromise == null) {
                    return Causes.cause("Slice not active: " + artifact).promise();
                }

                return activePromise.flatMap(activeSlice ->
                                                     activeSlice.stop()
                                                                .onSuccess(_ -> {
                                                                    // Move from active back to loaded only on successful stop
                                                                    instances.remove(artifact);
                                                                    var loadedSlice = new loadedSlice(artifact,
                                                                                                      Result.success(
                                                                                                              activeSlice));
                                                                    loadedInstances.put(artifact, loadedSlice);
                                                                })
                                                                .onFailure(cause -> {
                                                                    // Log stop failure but keep slice in active state
                                                                    log.error(
                                                                            "Failed to stop slice {} during deactivation: {}",
                                                                            artifact,
                                                                            cause.message());
                                                                })
                                                                .map(_ -> {
                                                                    // Return loaded slice even if stop failed (slice remains tracked as active)
                                                                    var loadedSlice = new loadedSlice(artifact,
                                                                                                      Result.success(
                                                                                                              activeSlice));
                                                                    return loadedSlice;
                                                                })
                );
            }

            @Override
            public Promise<Unit> unloadSlice(Artifact artifact) {
                log.debug("Unloading slice {}", artifact);

                var output = Promise.<Unit>promise();

                // Check if slice is currently active - this should be an error
                var activePromise = instances.get(artifact);
                if (activePromise != null) {
                    var errorMessage = "Attempt to unload active slice: " + artifact + " - this is a bug in logic";
                    log.error(errorMessage);
                    output.fail(Causes.cause(errorMessage));
                    return output;
                }

                // Handle if slice is loaded but not active
                var loaded = loadedInstances.remove(artifact);
                if (loaded != null) {
                    cleanupSlice(artifact);
                    output.succeed(Unit.unit());
                    return output;
                }

                // Slice not found
                output.fail(Causes.cause("Slice not found: " + artifact));
                return output;
            }

            private LoadedSlice asLoadedSlice(Map.Entry<Artifact, Promise<ActiveSlice>> entry) {
                return new loadedSlice(entry.getKey(), entry.getValue().await());
            }

            private static boolean loadingFinished(Map.Entry<Artifact, Promise<ActiveSlice>> entry) {
                return entry.getValue().isResolved();
            }

            private Promise<ActiveSlice> loadSlice(Artifact artifact, Repository repository) {
                return repository.locate(artifact)
                                 .withSuccess(location -> log.debug("Repository provided: {}", location))
                                 .withFailure(cause -> log.warn("Unable to load {}, cause: {}", artifact, cause))
                                 .flatMap(this::loadService);
            }

            // Suppressed because there is a cleanup, just not with try-with-resource
            @SuppressWarnings("resource")
            private Promise<ActiveSlice> loadService(Location location) {
                var loader = new SliceClassLoader(new URL[]{location.url()}, Slice.class.getClassLoader());

                return Promise.lift(Causes::fromThrowable,
                                    () -> ServiceLoader.load(Slice.class, loader))
                              .flatMap(serviceLoader -> loadService(serviceLoader, location))
                              .onSuccessRun(() -> classLoaders.put(location.artifact(), loader))
                              .onFailureRun(() -> cleanupClassLoader(loader));
            }

            private static void cleanupClassLoader(SliceClassLoader loader) {
                try {
                    loader.close();
                } catch (IOException e) {
                    log.error("Error closing classLoader", e);
                }
            }

            private Promise<ActiveSlice> loadService(ServiceLoader<Slice> serviceLoader, Location location) {
                var it = serviceLoader.iterator();

                return it.hasNext()
                        ? Promise.success(it.next()).flatMap(Slice::start)
                        : NO_SERVICE.apply(location.url().toString()).promise();
            }

            private Promise<LoadedSlice> loadSliceFromLocation(Location location, Artifact artifact) {
                var loader = new SliceClassLoader(new URL[]{location.url()}, Slice.class.getClassLoader());

                return Promise.lift(Causes::fromThrowable,
                                    () -> ServiceLoader.load(Slice.class, loader))
                              .flatMap(serviceLoader -> loadSliceService(serviceLoader, location, artifact))
                              .onSuccessRun(() -> classLoaders.put(location.artifact(), loader))
                              .onFailureRun(() -> cleanupClassLoader(loader));
            }

            private Promise<LoadedSlice> loadSliceService(ServiceLoader<Slice> serviceLoader,
                                                          Location location,
                                                          Artifact artifact) {
                var it = serviceLoader.iterator();

                if (it.hasNext()) {
                    var slice = it.next();
                    // Create a LoadedSlice with the slice loaded but not started
                    // Slice should not be automatically activated - activation is a separate step
                    // Store the unstarted slice so activation can call start() later
                    var loadedSlice = new LoadedSliceWithUnstartedSlice(artifact, slice);
                    return Promise.success(loadedSlice);
                } else {
                    return NO_SERVICE.apply(location.url().toString()).promise();
                }
            }

            private Repository findRepository(Artifact artifact) {
                // Simplified implementation - in reality this would query a repository registry
                // For now, return a local repository
                return LocalRepository.localRepository();
            }

            private void cleanupSlice(Artifact artifact) {
                var classLoader = classLoaders.remove(artifact);
                if (classLoader != null) {
                    cleanupClassLoader(classLoader);
                }
            }
        }

        return new store(new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
    }
}
