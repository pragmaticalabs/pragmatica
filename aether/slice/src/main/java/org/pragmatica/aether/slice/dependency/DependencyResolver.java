package org.pragmatica.aether.slice.dependency;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.ResourceProviderFacade;
import org.pragmatica.aether.slice.SharedLibraryClassLoader;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceBridge;
import org.pragmatica.aether.slice.DefaultSliceBridge;
import org.pragmatica.aether.slice.SliceClassLoader;
import org.pragmatica.aether.slice.SliceCreationContext;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.SliceLoadingContext;
import org.pragmatica.aether.slice.SliceManifest;
import org.pragmatica.aether.slice.SliceManifest.SliceManifestInfo;
import org.pragmatica.aether.slice.repository.Location;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.aether.slice.serialization.FurySerializerFactoryProvider;
import org.pragmatica.aether.slice.serialization.SerializerFactory;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.utils.Causes.cause;

/// Orchestrates dependency resolution for slices.
///
/// Resolution process:
/// 1. Check registry for already-loaded slice
/// 2. Locate JAR via repository
/// 3. Read manifest to get slice class name and verify artifact
/// 4. Create ClassLoader with JAR URL
/// 5. Load dependencies from META-INF/dependencies/ file
/// 6. Build dependency graph and check for cycles
/// 7. Recursively resolve dependencies (depth-first)
/// 8. Instantiate slice via factory method
/// 9. Register in registry
///
/// Thread-safe: Uses SliceRegistry for synchronization.
@SuppressWarnings({"JBCT-VO-01", "JBCT-SEQ-01", "JBCT-LAM-01", "JBCT-LAM-02", "JBCT-NEST-01", "JBCT-UTIL-02", "JBCT-ZONE-02", "JBCT-ZONE-03"})
public interface DependencyResolver {
    Logger log = LoggerFactory.getLogger(DependencyResolver.class);

    /// Result of resolving a slice, containing both the slice and its loading context.
    /// The loading context is used during activation to eagerly validate dependencies.
    record ResolvedSlice(Slice slice, SliceLoadingContext loadingContext) {}

    /// Resolve a slice using SharedLibraryClassLoader for shared dependencies.
    ///
    /// @param artifact            The slice artifact to resolve
    /// @param repository          Repository to locate artifacts
    /// @param registry            Registry to track loaded slices
    /// @param sharedLibraryLoader ClassLoader for shared dependencies
    /// @param invokerFacade       Facade for inter-slice invocations
    /// @return Promise of resolved slice
    static Promise<Slice> resolve(Artifact artifact,
                                  Repository repository,
                                  SliceRegistry registry,
                                  SharedLibraryClassLoader sharedLibraryLoader,
                                  SliceInvokerFacade invokerFacade) {
        return registry.lookup(artifact)
                       .map(Promise::success)
                       .or(() -> resolveWithSharedLoader(artifact,
                                                         repository,
                                                         registry,
                                                         sharedLibraryLoader,
                                                         invokerFacade,
                                                         new HashSet<>()));
    }

    /// Resolve a slice and return with its loading context for eager dependency validation.
    ///
    /// The returned {@link ResolvedSlice} contains both the slice and its
    /// {@link SliceLoadingContext} which should be used during activation to
    /// call {@link SliceLoadingContext#materializeAll()} before calling start().
    ///
    /// @param artifact            The slice artifact to resolve
    /// @param repository          Repository to locate artifacts
    /// @param registry            Registry to track loaded slices
    /// @param sharedLibraryLoader ClassLoader for shared dependencies
    /// @param invokerFacade       Facade for inter-slice invocations
    /// @param resourceFacade      Facade for resource provisioning
    /// @return Promise of resolved slice with loading context
    static Promise<ResolvedSlice> resolveWithContext(Artifact artifact,
                                                     Repository repository,
                                                     SliceRegistry registry,
                                                     SharedLibraryClassLoader sharedLibraryLoader,
                                                     SliceInvokerFacade invokerFacade,
                                                     ResourceProviderFacade resourceFacade) {
        var loadingContext = SliceLoadingContext.sliceLoadingContext(invokerFacade, resourceFacade);
        return registry.lookup(artifact)
                       .map(slice -> Promise.success(new ResolvedSlice(slice, loadingContext)))
                       .or(() -> resolveWithSharedLoaderAndContext(artifact,
                                                                   repository,
                                                                   registry,
                                                                   sharedLibraryLoader,
                                                                   loadingContext,
                                                                   new HashSet<>()));
    }

    /// Resolve a slice and return with its loading context for eager dependency validation.
    /// Uses a no-op resource provider (for backward compatibility).
    ///
    /// @param artifact            The slice artifact to resolve
    /// @param repository          Repository to locate artifacts
    /// @param registry            Registry to track loaded slices
    /// @param sharedLibraryLoader ClassLoader for shared dependencies
    /// @param invokerFacade       Facade for inter-slice invocations
    /// @return Promise of resolved slice with loading context
    static Promise<ResolvedSlice> resolveWithContext(Artifact artifact,
                                                     Repository repository,
                                                     SliceRegistry registry,
                                                     SharedLibraryClassLoader sharedLibraryLoader,
                                                     SliceInvokerFacade invokerFacade) {
        return resolveWithContext(artifact,
                                  repository,
                                  registry,
                                  sharedLibraryLoader,
                                  invokerFacade,
                                  noOpResourceProvider());
    }

    /// Returns a no-op resource provider for backward compatibility.
    /// This provider fails for any resource request.
    private static ResourceProviderFacade noOpResourceProvider() {
        return new ResourceProviderFacade() {
            @Override
            public <T> Promise<T> provide(Class<T> resourceType, String configSection) {
                return cause("Resource provisioning not configured. "
                             + "Use resolveWithContext(artifact, repo, registry, loader, invoker, resourceFacade) "
                             + "to enable resource provisioning.")
                .promise();
            }
        };
    }

    private static Promise<ResolvedSlice> resolveWithSharedLoaderAndContext(Artifact artifact,
                                                                            Repository repository,
                                                                            SliceRegistry registry,
                                                                            SharedLibraryClassLoader sharedLibraryLoader,
                                                                            SliceLoadingContext loadingContext,
                                                                            Set<String> resolutionPath) {
        log.info("Resolving artifact {} with shared loader and context", artifact.asString());
        var artifactKey = artifact.asString();
        if (resolutionPath.contains(artifactKey)) {
            log.error("Circular dependency detected for {}", artifactKey);
            return circularDependencyDetected(artifactKey).promise();
        }
        resolutionPath.add(artifactKey);
        return repository.locate(artifact)
                         .onSuccess(location -> log.info("Located artifact {} at {}",
                                                         artifact.asString(),
                                                         location.url()))
                         .flatMap(location -> loadFromLocationWithSharedAndContext(artifact,
                                                                                   location,
                                                                                   repository,
                                                                                   registry,
                                                                                   sharedLibraryLoader,
                                                                                   loadingContext,
                                                                                   resolutionPath))
                         .onSuccess(_ -> {
                                        log.info("Resolved artifact {} with context",
                                                 artifact.asString());
                                        resolutionPath.remove(artifactKey);
                                    })
                         .onFailure(cause -> {
                                        log.error("Failed to resolve artifact {}: {}",
                                                  artifact.asString(),
                                                  cause.message());
                                        resolutionPath.remove(artifactKey);
                                    });
    }

    private static Promise<ResolvedSlice> loadFromLocationWithSharedAndContext(Artifact artifact,
                                                                               Location location,
                                                                               Repository repository,
                                                                               SliceRegistry registry,
                                                                               SharedLibraryClassLoader sharedLibraryLoader,
                                                                               SliceLoadingContext loadingContext,
                                                                               Set<String> resolutionPath) {
        return SliceManifest.read(location.url())
                            .onFailure(cause -> log.error("Invalid slice JAR {}: {}",
                                                          artifact,
                                                          cause.message()))
                            .async()
                            .flatMap(manifest -> validateAndLoadWithSharedAndContext(artifact,
                                                                                     location,
                                                                                     manifest,
                                                                                     repository,
                                                                                     registry,
                                                                                     sharedLibraryLoader,
                                                                                     loadingContext,
                                                                                     resolutionPath));
    }

    private static Promise<ResolvedSlice> validateAndLoadWithSharedAndContext(Artifact artifact,
                                                                              Location location,
                                                                              SliceManifestInfo manifest,
                                                                              Repository repository,
                                                                              SliceRegistry registry,
                                                                              SharedLibraryClassLoader sharedLibraryLoader,
                                                                              SliceLoadingContext loadingContext,
                                                                              Set<String> resolutionPath) {
        if (!manifest.artifact()
                     .equals(artifact)) {
            log.error("Artifact mismatch: requested {} but JAR declares {}", artifact, manifest.artifact());
            return artifactMismatch(artifact, manifest.artifact()).promise();
        }
        return DependencyFile.load(manifest.sliceClassName(),
                                   createTempLoader(location.url(),
                                                    sharedLibraryLoader))
                             .async()
                             .flatMap(depFile -> processSharedAndLoadSliceWithContext(manifest,
                                                                                      location,
                                                                                      depFile,
                                                                                      repository,
                                                                                      registry,
                                                                                      sharedLibraryLoader,
                                                                                      loadingContext,
                                                                                      resolutionPath));
    }

    private static Promise<ResolvedSlice> processSharedAndLoadSliceWithContext(SliceManifestInfo manifest,
                                                                               Location location,
                                                                               DependencyFile depFile,
                                                                               Repository repository,
                                                                               SliceRegistry registry,
                                                                               SharedLibraryClassLoader sharedLibraryLoader,
                                                                               SliceLoadingContext loadingContext,
                                                                               Set<String> resolutionPath) {
        return SharedDependencyLoader.processSharedDependencies(depFile.shared(),
                                                                sharedLibraryLoader,
                                                                repository,
                                                                location.url())
                                     .flatMap(sharedResult -> SharedDependencyLoader.processInfraDependencies(depFile.infra(),
                                                                                                              sharedLibraryLoader,
                                                                                                              repository)
                                                                                    .map(_ -> sharedResult))
                                     .flatMap(sharedResult -> addSliceDependencyJarsToClassLoader(depFile.slices(),
                                                                                                  sharedResult,
                                                                                                  repository))
                                     .flatMap(sharedResult -> loadSliceClassAndResolveDepsWithContext(manifest,
                                                                                                      depFile,
                                                                                                      sharedResult,
                                                                                                      repository,
                                                                                                      registry,
                                                                                                      sharedLibraryLoader,
                                                                                                      loadingContext,
                                                                                                      resolutionPath));
    }

    private static Promise<ResolvedSlice> loadSliceClassAndResolveDepsWithContext(SliceManifestInfo manifest,
                                                                                  DependencyFile depFile,
                                                                                  SharedDependencyLoader.SharedDependencyResult sharedResult,
                                                                                  Repository repository,
                                                                                  SliceRegistry registry,
                                                                                  SharedLibraryClassLoader sharedLibraryLoader,
                                                                                  SliceLoadingContext loadingContext,
                                                                                  Set<String> resolutionPath) {
        return loadClass(manifest.sliceClassName(), sharedResult.sliceClassLoader())
        .flatMap(sliceClass -> resolveSliceDependenciesWithContext(manifest.artifact(),
                                                                   sliceClass,
                                                                   depFile.slices(),
                                                                   sharedResult.sliceClassLoader(),
                                                                   repository,
                                                                   registry,
                                                                   sharedLibraryLoader,
                                                                   loadingContext,
                                                                   resolutionPath));
    }

    private static Promise<ResolvedSlice> resolveSliceDependenciesWithContext(Artifact artifact,
                                                                              Class<?> sliceClass,
                                                                              List<ArtifactDependency> sliceDeps,
                                                                              ClassLoader classLoader,
                                                                              Repository repository,
                                                                              SliceRegistry registry,
                                                                              SharedLibraryClassLoader sharedLibraryLoader,
                                                                              SliceLoadingContext loadingContext,
                                                                              Set<String> resolutionPath) {
        log.info("Resolving {} slice dependencies for {} with context", sliceDeps.size(), artifact.asString());
        if (sliceDeps.isEmpty()) {
            log.info("No slice dependencies for {}, creating slice directly with context", artifact.asString());
            return createSliceFromClass(sliceClass,
                                        loadingContext,
                                        List.of(),
                                        List.of()).flatMap(slice -> registerSlice(artifact, slice, registry))
                                       .map(slice -> new ResolvedSlice(slice, loadingContext));
        }
        return resolveArtifactDependenciesWithContextSequentially(sliceDeps,
                                                                  repository,
                                                                  registry,
                                                                  sharedLibraryLoader,
                                                                  loadingContext,
                                                                  resolutionPath,
                                                                  List.of()).onSuccess(resolved -> log.info("Resolved all {} slice dependencies for {} with context",
                                                                                                            resolved.size(),
                                                                                                            artifact.asString()))
                                                                 .flatMap(resolvedSlices -> createAndRegisterSliceWithContext(artifact,
                                                                                                                              sliceClass,
                                                                                                                              loadingContext,
                                                                                                                              resolvedSlices,
                                                                                                                              sliceDeps,
                                                                                                                              registry));
    }

    private static Promise<ResolvedSlice> createAndRegisterSliceWithContext(Artifact artifact,
                                                                            Class<?> sliceClass,
                                                                            SliceLoadingContext loadingContext,
                                                                            List<Slice> resolvedSlices,
                                                                            List<ArtifactDependency> sliceDeps,
                                                                            SliceRegistry registry) {
        log.info("Creating and registering slice {} with context", artifact.asString());
        return createSliceFromClass(sliceClass, loadingContext, resolvedSlices, sliceDeps).flatMap(slice -> registerSlice(artifact,
                                                                                                                          slice,
                                                                                                                          registry))
                                   .map(slice -> new ResolvedSlice(slice, loadingContext));
    }

    /// Resolve a slice and return a SliceBridge for isolated communication.
    ///
    /// This method resolves the slice and wraps it in a SliceBridge that handles
    /// serialization/deserialization at the boundary using byte arrays.
    ///
    /// @param artifact            The slice artifact to resolve
    /// @param repository          Repository to locate artifacts
    /// @param registry            Registry to track loaded slices
    /// @param sharedLibraryLoader ClassLoader for shared dependencies
    /// @param invokerFacade       Facade for inter-slice invocations
    /// @param serializerFactory   Factory for serialization
    /// @return Promise of resolved SliceBridge
    static Promise<SliceBridge> resolveBridge(Artifact artifact,
                                              Repository repository,
                                              SliceRegistry registry,
                                              SharedLibraryClassLoader sharedLibraryLoader,
                                              SliceInvokerFacade invokerFacade,
                                              SerializerFactory serializerFactory) {
        return resolve(artifact, repository, registry, sharedLibraryLoader, invokerFacade)
        .map(slice -> DefaultSliceBridge.defaultSliceBridge(artifact, slice, serializerFactory));
    }

    /// Resolve a slice and return a SliceBridge using default Fury serialization.
    ///
    /// @param artifact            The slice artifact to resolve
    /// @param repository          Repository to locate artifacts
    /// @param registry            Registry to track loaded slices
    /// @param sharedLibraryLoader ClassLoader for shared dependencies
    /// @param invokerFacade       Facade for inter-slice invocations
    /// @return Promise of resolved SliceBridge
    static Promise<SliceBridge> resolveBridge(Artifact artifact,
                                              Repository repository,
                                              SliceRegistry registry,
                                              SharedLibraryClassLoader sharedLibraryLoader,
                                              SliceInvokerFacade invokerFacade) {
        var serializerFactory = FurySerializerFactoryProvider.furySerializerFactoryProvider()
                                                             .createFactory(List.of());
        return resolveBridge(artifact, repository, registry, sharedLibraryLoader, invokerFacade, serializerFactory);
    }

    private static Promise<Slice> resolveWithSharedLoader(Artifact artifact,
                                                          Repository repository,
                                                          SliceRegistry registry,
                                                          SharedLibraryClassLoader sharedLibraryLoader,
                                                          SliceInvokerFacade invokerFacade,
                                                          Set<String> resolutionPath) {
        log.info("Resolving artifact {} with shared loader", artifact.asString());
        var artifactKey = artifact.asString();
        if (resolutionPath.contains(artifactKey)) {
            log.error("Circular dependency detected for {}", artifactKey);
            return circularDependencyDetected(artifactKey).promise();
        }
        resolutionPath.add(artifactKey);
        return repository.locate(artifact)
                         .onSuccess(location -> log.info("Located artifact {} at {}",
                                                         artifact.asString(),
                                                         location.url()))
                         .flatMap(location -> loadFromLocationWithShared(artifact,
                                                                         location,
                                                                         repository,
                                                                         registry,
                                                                         sharedLibraryLoader,
                                                                         invokerFacade,
                                                                         resolutionPath))
                         .onSuccess(_ -> {
                                        log.info("Resolved artifact {}",
                                                 artifact.asString());
                                        resolutionPath.remove(artifactKey);
                                    })
                         .onFailure(cause -> {
                                        log.error("Failed to resolve artifact {}: {}",
                                                  artifact.asString(),
                                                  cause.message());
                                        resolutionPath.remove(artifactKey);
                                    });
    }

    private static Promise<Slice> loadFromLocationWithShared(Artifact artifact,
                                                             Location location,
                                                             Repository repository,
                                                             SliceRegistry registry,
                                                             SharedLibraryClassLoader sharedLibraryLoader,
                                                             SliceInvokerFacade invokerFacade,
                                                             Set<String> resolutionPath) {
        return SliceManifest.read(location.url())
                            .onFailure(cause -> log.error("Invalid slice JAR {}: {}",
                                                          artifact,
                                                          cause.message()))
                            .async()
                            .flatMap(manifest -> validateAndLoadWithShared(artifact,
                                                                           location,
                                                                           manifest,
                                                                           repository,
                                                                           registry,
                                                                           sharedLibraryLoader,
                                                                           invokerFacade,
                                                                           resolutionPath));
    }

    private static Promise<Slice> validateAndLoadWithShared(Artifact artifact,
                                                            Location location,
                                                            SliceManifestInfo manifest,
                                                            Repository repository,
                                                            SliceRegistry registry,
                                                            SharedLibraryClassLoader sharedLibraryLoader,
                                                            SliceInvokerFacade invokerFacade,
                                                            Set<String> resolutionPath) {
        if (!manifest.artifact()
                     .equals(artifact)) {
            log.error("Artifact mismatch: requested {} but JAR declares {}", artifact, manifest.artifact());
            return artifactMismatch(artifact, manifest.artifact()).promise();
        }
        // Load dependency file to get shared and slice dependencies
        return DependencyFile.load(manifest.sliceClassName(),
                                   createTempLoader(location.url(),
                                                    sharedLibraryLoader))
                             .async()
                             .flatMap(depFile -> processSharedAndLoadSlice(manifest,
                                                                           location,
                                                                           depFile,
                                                                           repository,
                                                                           registry,
                                                                           sharedLibraryLoader,
                                                                           invokerFacade,
                                                                           resolutionPath));
    }

    private static SliceClassLoader createTempLoader(URL jarUrl, SharedLibraryClassLoader parent) {
        return new SliceClassLoader(new URL[]{jarUrl}, parent);
    }

    private static Promise<Slice> processSharedAndLoadSlice(SliceManifestInfo manifest,
                                                            Location location,
                                                            DependencyFile depFile,
                                                            Repository repository,
                                                            SliceRegistry registry,
                                                            SharedLibraryClassLoader sharedLibraryLoader,
                                                            SliceInvokerFacade invokerFacade,
                                                            Set<String> resolutionPath) {
        // Process shared dependencies first, then infra dependencies
        return SharedDependencyLoader.processSharedDependencies(depFile.shared(),
                                                                sharedLibraryLoader,
                                                                repository,
                                                                location.url())
                                     .flatMap(sharedResult -> SharedDependencyLoader.processInfraDependencies(depFile.infra(),
                                                                                                              sharedLibraryLoader,
                                                                                                              repository)
                                                                                    .map(_ -> sharedResult))
                                     .flatMap(sharedResult -> addSliceDependencyJarsToClassLoader(depFile.slices(),
                                                                                                  sharedResult,
                                                                                                  repository))
                                     .flatMap(sharedResult -> loadSliceClassAndResolveDeps(manifest,
                                                                                           depFile,
                                                                                           sharedResult,
                                                                                           repository,
                                                                                           registry,
                                                                                           sharedLibraryLoader,
                                                                                           invokerFacade,
                                                                                           resolutionPath));
    }

    /// Add dependency slice JARs to the SliceClassLoader BEFORE loading the slice class.
    ///
    /// This fixes NoClassDefFoundError when getDeclaredMethods() is called on the slice class
    /// and parameter types from dependency slices need to be resolved.
    private static Promise<SharedDependencyLoader.SharedDependencyResult> addSliceDependencyJarsToClassLoader(List<ArtifactDependency> sliceDeps,
                                                                                                              SharedDependencyLoader.SharedDependencyResult sharedResult,
                                                                                                              Repository repository) {
        if (sliceDeps.isEmpty()) {
            return Promise.success(sharedResult);
        }
        log.debug("Adding {} slice dependency JARs to classloader before loading slice class", sliceDeps.size());
        return addSliceDependencyJarsSequentially(sliceDeps, sharedResult, repository);
    }

    private static Promise<SharedDependencyLoader.SharedDependencyResult> addSliceDependencyJarsSequentially(List<ArtifactDependency> sliceDeps,
                                                                                                             SharedDependencyLoader.SharedDependencyResult sharedResult,
                                                                                                             Repository repository) {
        if (sliceDeps.isEmpty()) {
            return Promise.success(sharedResult);
        }
        var dep = sliceDeps.getFirst();
        var remaining = sliceDeps.subList(1, sliceDeps.size());
        return locateAndAddSliceDependencyJar(dep, sharedResult, repository)
        .flatMap(_ -> addSliceDependencyJarsSequentially(remaining, sharedResult, repository));
    }

    private static Promise<SharedDependencyLoader.SharedDependencyResult> locateAndAddSliceDependencyJar(ArtifactDependency dependency,
                                                                                                         SharedDependencyLoader.SharedDependencyResult sharedResult,
                                                                                                         Repository repository) {
        return toArtifact(dependency).async()
                         .flatMap(repository::locate)
                         .map(location -> {
                                  sharedResult.sliceClassLoader()
                                              .addSliceDependencyUrl(location.url());
                                  log.debug("Added slice dependency JAR {} to classloader",
                                            dependency.asString());
                                  return sharedResult;
                              });
    }

    private static Promise<Slice> loadSliceClassAndResolveDeps(SliceManifestInfo manifest,
                                                               DependencyFile depFile,
                                                               SharedDependencyLoader.SharedDependencyResult sharedResult,
                                                               Repository repository,
                                                               SliceRegistry registry,
                                                               SharedLibraryClassLoader sharedLibraryLoader,
                                                               SliceInvokerFacade invokerFacade,
                                                               Set<String> resolutionPath) {
        return loadClass(manifest.sliceClassName(), sharedResult.sliceClassLoader())
        .flatMap(sliceClass -> resolveSliceDependencies(manifest.artifact(),
                                                        sliceClass,
                                                        depFile.slices(),
                                                        sharedResult.sliceClassLoader(),
                                                        repository,
                                                        registry,
                                                        sharedLibraryLoader,
                                                        invokerFacade,
                                                        resolutionPath));
    }

    private static Promise<Slice> resolveSliceDependencies(Artifact artifact,
                                                           Class<?> sliceClass,
                                                           List<ArtifactDependency> sliceDeps,
                                                           ClassLoader classLoader,
                                                           Repository repository,
                                                           SliceRegistry registry,
                                                           SharedLibraryClassLoader sharedLibraryLoader,
                                                           SliceInvokerFacade invokerFacade,
                                                           Set<String> resolutionPath) {
        log.info("Resolving {} slice dependencies for {}", sliceDeps.size(), artifact.asString());
        if (sliceDeps.isEmpty()) {
            log.info("No slice dependencies for {}, creating slice directly", artifact.asString());
            return createSliceFromClass(sliceClass, toCreationContext(invokerFacade), List.of(), List.of())
            .flatMap(slice -> registerSlice(artifact, slice, registry));
        }
        return resolveArtifactDependenciesSequentially(sliceDeps,
                                                       repository,
                                                       registry,
                                                       sharedLibraryLoader,
                                                       invokerFacade,
                                                       resolutionPath,
                                                       List.of()).onSuccess(resolved -> log.info("Resolved all {} slice dependencies for {}",
                                                                                                 resolved.size(),
                                                                                                 artifact.asString()))
                                                      .flatMap(resolvedSlices -> createAndRegisterSlice(artifact,
                                                                                                        sliceClass,
                                                                                                        invokerFacade,
                                                                                                        resolvedSlices,
                                                                                                        sliceDeps,
                                                                                                        registry));
    }

    private static Promise<Slice> createAndRegisterSlice(Artifact artifact,
                                                         Class<?> sliceClass,
                                                         SliceInvokerFacade invokerFacade,
                                                         List<Slice> resolvedSlices,
                                                         List<ArtifactDependency> sliceDeps,
                                                         SliceRegistry registry) {
        log.info("Creating and registering slice {}", artifact.asString());
        return createSliceFromClass(sliceClass, toCreationContext(invokerFacade), resolvedSlices, sliceDeps)
        .flatMap(slice -> registerSlice(artifact, slice, registry));
    }

    private static Promise<List<Slice>> resolveArtifactDependenciesSequentially(List<ArtifactDependency> dependencies,
                                                                                Repository repository,
                                                                                SliceRegistry registry,
                                                                                SharedLibraryClassLoader sharedLibraryLoader,
                                                                                SliceInvokerFacade invokerFacade,
                                                                                Set<String> resolutionPath,
                                                                                List<Slice> accumulated) {
        if (dependencies.isEmpty()) {
            return Promise.success(accumulated);
        }
        var dep = dependencies.getFirst();
        var remaining = dependencies.subList(1, dependencies.size());
        return resolveArtifactDependency(dep, repository, registry, sharedLibraryLoader, invokerFacade, resolutionPath)
        .flatMap(slice -> resolveArtifactDependenciesSequentially(remaining,
                                                                  repository,
                                                                  registry,
                                                                  sharedLibraryLoader,
                                                                  invokerFacade,
                                                                  resolutionPath,
                                                                  appendToList(accumulated, slice)));
    }

    private static Promise<Slice> resolveArtifactDependency(ArtifactDependency dependency,
                                                            Repository repository,
                                                            SliceRegistry registry,
                                                            SharedLibraryClassLoader sharedLibraryLoader,
                                                            SliceInvokerFacade invokerFacade,
                                                            Set<String> resolutionPath) {
        // First check registry for compatible version
        var inRegistry = registry.findByArtifactKey(dependency.groupId(),
                                                    dependency.artifactId(),
                                                    dependency.versionPattern());
        if (inRegistry.isPresent()) {
            log.info("Found dependency {} in registry", dependency.asString());
            return Promise.success(inRegistry.unwrap());
        }
        log.info("Dependency {} not in registry, loading from repository", dependency.asString());
        return resolveArtifactFromRepository(dependency,
                                             repository,
                                             registry,
                                             sharedLibraryLoader,
                                             invokerFacade,
                                             resolutionPath);
    }

    private static Promise<Slice> resolveArtifactFromRepository(ArtifactDependency dependency,
                                                                Repository repository,
                                                                SliceRegistry registry,
                                                                SharedLibraryClassLoader sharedLibraryLoader,
                                                                SliceInvokerFacade invokerFacade,
                                                                Set<String> resolutionPath) {
        return toArtifact(dependency).async()
                         .flatMap(artifact -> resolveWithSharedLoader(artifact,
                                                                      repository,
                                                                      registry,
                                                                      sharedLibraryLoader,
                                                                      invokerFacade,
                                                                      resolutionPath));
    }

    private static Promise<List<Slice>> resolveArtifactDependenciesWithContextSequentially(List<ArtifactDependency> dependencies,
                                                                                           Repository repository,
                                                                                           SliceRegistry registry,
                                                                                           SharedLibraryClassLoader sharedLibraryLoader,
                                                                                           SliceLoadingContext loadingContext,
                                                                                           Set<String> resolutionPath,
                                                                                           List<Slice> accumulated) {
        if (dependencies.isEmpty()) {
            return Promise.success(accumulated);
        }
        var dep = dependencies.getFirst();
        var remaining = dependencies.subList(1, dependencies.size());
        return resolveArtifactDependencyWithContext(dep,
                                                    repository,
                                                    registry,
                                                    sharedLibraryLoader,
                                                    loadingContext,
                                                    resolutionPath)
        .flatMap(slice -> resolveArtifactDependenciesWithContextSequentially(remaining,
                                                                             repository,
                                                                             registry,
                                                                             sharedLibraryLoader,
                                                                             loadingContext,
                                                                             resolutionPath,
                                                                             appendToList(accumulated, slice)));
    }

    private static Promise<Slice> resolveArtifactDependencyWithContext(ArtifactDependency dependency,
                                                                       Repository repository,
                                                                       SliceRegistry registry,
                                                                       SharedLibraryClassLoader sharedLibraryLoader,
                                                                       SliceLoadingContext loadingContext,
                                                                       Set<String> resolutionPath) {
        var inRegistry = registry.findByArtifactKey(dependency.groupId(),
                                                    dependency.artifactId(),
                                                    dependency.versionPattern());
        if (inRegistry.isPresent()) {
            log.info("Found dependency {} in registry", dependency.asString());
            return Promise.success(inRegistry.unwrap());
        }
        log.info("Dependency {} not in registry, loading from repository with context", dependency.asString());
        return resolveArtifactFromRepositoryWithContext(dependency,
                                                        repository,
                                                        registry,
                                                        sharedLibraryLoader,
                                                        loadingContext,
                                                        resolutionPath);
    }

    private static Promise<Slice> resolveArtifactFromRepositoryWithContext(ArtifactDependency dependency,
                                                                           Repository repository,
                                                                           SliceRegistry registry,
                                                                           SharedLibraryClassLoader sharedLibraryLoader,
                                                                           SliceLoadingContext loadingContext,
                                                                           Set<String> resolutionPath) {
        return toArtifact(dependency).async()
                         .flatMap(artifact -> resolveWithSharedLoaderAndContext(artifact,
                                                                                repository,
                                                                                registry,
                                                                                sharedLibraryLoader,
                                                                                loadingContext,
                                                                                resolutionPath))
                         .map(ResolvedSlice::slice);
    }

    private static Result<Artifact> toArtifact(ArtifactDependency dependency) {
        var versionStr = extractVersion(dependency.versionPattern()).withQualifier();
        return Artifact.artifact(dependency.groupId() + ":" + dependency.artifactId() + ":" + versionStr);
    }

    private static Version extractVersion(VersionPattern pattern) {
        return switch (pattern) {
            case VersionPattern.Exact(Version version) -> version;
            case VersionPattern.Range(Version from, _, _, _) -> from;
            case VersionPattern.Comparison(_, Version version) -> version;
            case VersionPattern.Tilde(Version version) -> version;
            case VersionPattern.Caret(Version version) -> version;
            case VersionPattern.unused _ -> Version.version("0.0.0")
                                                   .unwrap();
        };
    }

    private static Promise<Slice> createSliceFromClass(Class<?> sliceClass,
                                                       SliceCreationContext creationContext,
                                                       List<Slice> dependencies,
                                                       List<ArtifactDependency> descriptors) {
        // Convert ArtifactDependency to DependencyDescriptor for SliceFactory compatibility
        var legacyDescriptors = descriptors.stream()
                                           .map(dep -> new DependencyDescriptor(ArtifactMapper.toClassName(dep.groupId(),
                                                                                                           dep.artifactId()),
                                                                                dep.versionPattern(),
                                                                                none()))
                                           .toList();
        return SliceFactory.createSlice(sliceClass, creationContext, dependencies, legacyDescriptors);
    }

    /// Creates a minimal SliceCreationContext from an invoker facade (backward compatibility).
    private static SliceCreationContext toCreationContext(SliceInvokerFacade invokerFacade) {
        return SliceCreationContext.sliceCreationContext(invokerFacade, noOpResourceProvider());
    }

    private static Promise<Slice> registerSlice(Artifact artifact, Slice slice, SliceRegistry registry) {
        // On success: return our slice; on failure (already registered): lookup or fallback to our slice
        return registry.register(artifact, slice)
                       .map(_ -> slice)
                       .recover(_ -> registry.lookup(artifact)
                                             .or(slice))
                       .async();
    }

    private static <T> List<T> appendToList(List<T> list, T element) {
        var newList = new ArrayList<>(list);
        newList.add(element);
        return List.copyOf(newList);
    }

    private static Promise<Class<?>> loadClass(String className, ClassLoader classLoader) {
        return Promise.lift(Causes::fromThrowable, () -> classLoader.loadClass(className));
    }

    private static Cause circularDependencyDetected(String artifactKey) {
        return cause("Circular dependency detected during resolution: " + artifactKey);
    }

    private static Cause artifactMismatch(Artifact requested, Artifact declared) {
        return cause("Artifact mismatch: requested " + requested.asString() + " but JAR manifest declares " + declared.asString());
    }
}
