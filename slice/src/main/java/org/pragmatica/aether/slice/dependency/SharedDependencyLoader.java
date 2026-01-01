package org.pragmatica.aether.slice.dependency;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.SharedLibraryClassLoader;
import org.pragmatica.aether.slice.SliceClassLoader;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles loading of shared dependencies into SharedLibraryClassLoader
 * and creates SliceClassLoader with appropriate parent and conflict overrides.
 */
public interface SharedDependencyLoader {
    Logger log = LoggerFactory.getLogger(SharedDependencyLoader.class);

    /**
     * Process API dependencies from [api] section.
     * <p>
     * API JARs (with -api classifier) contain typed interfaces used by generated proxies.
     * They are loaded into SharedLibraryClassLoader for cross-slice type visibility.
     *
     * @param dependencies        List of API dependencies from [api] section
     * @param sharedLibraryLoader The shared library classloader
     * @param repository          Repository to locate artifacts
     * @return Promise that completes when all API JARs are loaded
     */
    static Promise<Void> processApiDependencies(List<ArtifactDependency> dependencies,
                                                SharedLibraryClassLoader sharedLibraryLoader,
                                                Repository repository) {
        if (dependencies.isEmpty()) {
            return Promise.success(null);
        }
        return processApiSequentially(dependencies, sharedLibraryLoader, repository);
    }

    private static Promise<Void> processApiSequentially(List<ArtifactDependency> dependencies,
                                                        SharedLibraryClassLoader sharedLibraryLoader,
                                                        Repository repository) {
        if (dependencies.isEmpty()) {
            return Promise.success(null);
        }
        var dependency = dependencies.getFirst();
        var remaining = dependencies.subList(1, dependencies.size());
        return loadApiIntoShared(dependency, sharedLibraryLoader, repository)
               .flatMap(_ -> processApiSequentially(remaining, sharedLibraryLoader, repository));
    }

    private static Promise<Void> loadApiIntoShared(ArtifactDependency dependency,
                                                   SharedLibraryClassLoader sharedLibraryLoader,
                                                   Repository repository) {
        // Check if already loaded using fold pattern
        return sharedLibraryLoader.checkCompatibility(dependency)
                                  .fold(() -> toArtifact(dependency)
                                              .async()
                                              .flatMap(repository::locate)
                                              .map(location -> {
                                                       var version = extractVersion(dependency.versionPattern());
                                                       sharedLibraryLoader.addArtifact(
        dependency.groupId(),
        dependency.artifactId(),
        version,
        location.url());
                                                       log.debug("Loaded API dependency {} into SharedLibraryClassLoader",
                                                                 dependency.asString());
                                                       return null;
                                                   }),
                                        // Already loaded - API JARs don't have version conflicts (they're interfaces)
        _ -> {
                                            log.debug("API dependency {} already loaded",
                                                      dependency.asString());
                                            return Promise.success(null);
                                        });
    }

    /**
     * Result of processing shared dependencies for a slice.
     *
     * @param sliceClassLoader   The ClassLoader to use for loading the slice
     * @param conflictingJarUrls URLs of JARs that conflict with shared versions (loaded into slice)
     */
    record SharedDependencyResult(
    SliceClassLoader sliceClassLoader,
    List<URL> conflictingJarUrls) {}

    /**
     * Process shared dependencies for a slice.
     * <p>
     * For each shared dependency:
     * - If not loaded: load into SharedLibraryClassLoader
     * - If loaded and compatible: reuse
     * - If loaded and conflict: add to slice's conflict list
     *
     * @param dependencies           List of shared dependencies from [shared] section
     * @param sharedLibraryLoader    The shared library classloader
     * @param repository             Repository to locate artifacts
     * @param sliceJarUrl            URL of the slice JAR
     * @return SliceClassLoader configured with appropriate parent and conflict overrides
     */
    static Promise<SharedDependencyResult> processSharedDependencies(List<ArtifactDependency> dependencies,
                                                                     SharedLibraryClassLoader sharedLibraryLoader,
                                                                     Repository repository,
                                                                     URL sliceJarUrl) {
        var conflictUrls = new ArrayList<URL>();
        return processSequentially(dependencies, sharedLibraryLoader, repository, conflictUrls)
               .map(_ -> {
                        // Create slice classloader with slice JAR + any conflict JARs
        var urls = new ArrayList<URL>();
                        urls.add(sliceJarUrl);
                        urls.addAll(conflictUrls);
                        var sliceLoader = new SliceClassLoader(
        urls.toArray(URL[]::new),
        sharedLibraryLoader);
                        return new SharedDependencyResult(sliceLoader,
                                                          List.copyOf(conflictUrls));
                    });
    }

    private static Promise<Void> processSequentially(List<ArtifactDependency> dependencies,
                                                     SharedLibraryClassLoader sharedLibraryLoader,
                                                     Repository repository,
                                                     List<URL> conflictUrls) {
        if (dependencies.isEmpty()) {
            return Promise.success(null);
        }
        var dependency = dependencies.getFirst();
        var remaining = dependencies.subList(1, dependencies.size());
        return processSingleDependency(dependency, sharedLibraryLoader, repository, conflictUrls)
               .flatMap(_ -> processSequentially(remaining, sharedLibraryLoader, repository, conflictUrls));
    }

    private static Promise<Void> processSingleDependency(ArtifactDependency dependency,
                                                         SharedLibraryClassLoader sharedLibraryLoader,
                                                         Repository repository,
                                                         List<URL> conflictUrls) {
        var compatResult = sharedLibraryLoader.checkCompatibility(dependency);
        return compatResult.fold(
        // Not loaded yet - load into shared
        () -> loadIntoShared(dependency, sharedLibraryLoader, repository),
        // Already loaded - check compatibility
        result -> switch (result) {
            case CompatibilityResult.Compatible(var loadedVersion) -> {
            log.debug("Shared dependency {} compatible with loaded version {}",
                      dependency.asString(),
                      loadedVersion.withQualifier());
            yield Promise.success(null);
        }
            case CompatibilityResult.Conflict(var loadedVersion, var required) -> {
            log.info("Shared dependency {} conflicts with loaded version {}, will load into slice",
                     dependency.asString(),
                     loadedVersion.withQualifier());
            yield loadConflictIntoSlice(dependency, repository, conflictUrls);
        }
        });
    }

    private static Promise<Void> loadIntoShared(ArtifactDependency dependency,
                                                SharedLibraryClassLoader sharedLibraryLoader,
                                                Repository repository) {
        return toArtifact(dependency)
               .async()
               .flatMap(repository::locate)
               .map(location -> {
                        var version = extractVersion(dependency.versionPattern());
                        sharedLibraryLoader.addArtifact(
        dependency.groupId(),
        dependency.artifactId(),
        version,
        location.url());
                        log.debug("Loaded shared dependency {} into SharedLibraryClassLoader",
                                  dependency.asString());
                        return null;
                    });
    }

    private static Promise<Void> loadConflictIntoSlice(ArtifactDependency dependency,
                                                       Repository repository,
                                                       List<URL> conflictUrls) {
        return toArtifact(dependency)
               .async()
               .flatMap(repository::locate)
               .map(location -> {
                        conflictUrls.add(location.url());
                        log.debug("Added conflicting dependency {} to slice classloader",
                                  dependency.asString());
                        return null;
                    });
    }

    private static Result<Artifact> toArtifact(ArtifactDependency dependency) {
        var versionStr = extractVersion(dependency.versionPattern())
                         .withQualifier();
        return Artifact.artifact(dependency.groupId() + ":" + dependency.artifactId() + ":" + versionStr);
    }

    private static Version extractVersion(VersionPattern pattern) {
        return switch (pattern) {
            case VersionPattern.Exact(Version version) -> version;
            case VersionPattern.Range(Version from, _, _, _) -> from;
            case VersionPattern.Comparison(_, Version version) -> version;
            case VersionPattern.Tilde(Version version) -> version;
            case VersionPattern.Caret(Version version) -> version;
        };
    }
}
