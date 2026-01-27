package org.pragmatica.aether.slice.dependency;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.SharedLibraryClassLoader;
import org.pragmatica.aether.slice.SliceClassLoader;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;

/**
 * Handles loading of shared dependencies into SharedLibraryClassLoader
 * and creates SliceClassLoader with appropriate parent and conflict overrides.
 */
public interface SharedDependencyLoader {
    Logger log = LoggerFactory.getLogger(SharedDependencyLoader.class);

    /**
     * Process infrastructure dependencies from [infra] section.
     * <p>
     * Infrastructure JARs are loaded into SharedLibraryClassLoader like [shared] dependencies.
     * Instance sharing is managed by infra services themselves via InfraStore at runtime.
     *
     * @param dependencies        List of infra dependencies from [infra] section
     * @param sharedLibraryLoader The shared library classloader
     * @param repository          Repository to locate artifacts
     * @return Promise that completes when all infra JARs are loaded
     */
    static Promise<Unit> processInfraDependencies(List<ArtifactDependency> dependencies,
                                                  SharedLibraryClassLoader sharedLibraryLoader,
                                                  Repository repository) {
        if (dependencies.isEmpty()) {
            return Promise.success(unit());
        }
        return processInfraSequentially(dependencies, sharedLibraryLoader, repository);
    }

    private static Promise<Unit> processInfraSequentially(List<ArtifactDependency> dependencies,
                                                          SharedLibraryClassLoader sharedLibraryLoader,
                                                          Repository repository) {
        if (dependencies.isEmpty()) {
            return Promise.success(unit());
        }
        var dependency = dependencies.getFirst();
        var remaining = dependencies.subList(1, dependencies.size());
        return loadInfraIntoShared(dependency, sharedLibraryLoader, repository)
        .flatMap(_ -> processInfraSequentially(remaining, sharedLibraryLoader, repository));
    }

    private static Promise<Unit> loadInfraIntoShared(ArtifactDependency dependency,
                                                     SharedLibraryClassLoader sharedLibraryLoader,
                                                     Repository repository) {
        return sharedLibraryLoader.checkCompatibility(dependency)
                                  .fold(() -> loadInfraArtifact(dependency, sharedLibraryLoader, repository),
                                        _ -> logInfraAlreadyLoaded(dependency));
    }

    private static Promise<Unit> loadInfraArtifact(ArtifactDependency dependency,
                                                   SharedLibraryClassLoader sharedLibraryLoader,
                                                   Repository repository) {
        return toArtifact(dependency).async()
                         .flatMap(repository::locate)
                         .map(location -> addInfraToSharedLoader(dependency,
                                                                 sharedLibraryLoader,
                                                                 location.url()));
    }

    private static Unit addInfraToSharedLoader(ArtifactDependency dependency,
                                               SharedLibraryClassLoader sharedLibraryLoader,
                                               URL url) {
        var version = extractVersion(dependency.versionPattern());
        sharedLibraryLoader.addArtifact(dependency.groupId(), dependency.artifactId(), version, url);
        log.debug("Loaded infra dependency {} into SharedLibraryClassLoader", dependency.asString());
        return unit();
    }

    private static Promise<Unit> logInfraAlreadyLoaded(ArtifactDependency dependency) {
        log.debug("Infra dependency {} already loaded", dependency.asString());
        return Promise.success(unit());
    }

    /**
     * Result of processing shared dependencies for a slice.
     *
     * @param sliceClassLoader   The ClassLoader to use for loading the slice
     * @param conflictingJarUrls URLs of JARs that conflict with shared versions (loaded into slice)
     */
    record SharedDependencyResult(SliceClassLoader sliceClassLoader,
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
        .map(_ -> createSliceClassLoader(sharedLibraryLoader, sliceJarUrl, conflictUrls));
    }

    private static SharedDependencyResult createSliceClassLoader(SharedLibraryClassLoader sharedLibraryLoader,
                                                                 URL sliceJarUrl,
                                                                 List<URL> conflictUrls) {
        var urls = new ArrayList<URL>();
        urls.add(sliceJarUrl);
        urls.addAll(conflictUrls);
        var sliceLoader = new SliceClassLoader(urls.toArray(URL[]::new), sharedLibraryLoader);
        return new SharedDependencyResult(sliceLoader, List.copyOf(conflictUrls));
    }

    private static Promise<Unit> processSequentially(List<ArtifactDependency> dependencies,
                                                     SharedLibraryClassLoader sharedLibraryLoader,
                                                     Repository repository,
                                                     List<URL> conflictUrls) {
        if (dependencies.isEmpty()) {
            return Promise.success(unit());
        }
        var dependency = dependencies.getFirst();
        var remaining = dependencies.subList(1, dependencies.size());
        return processSingleDependency(dependency, sharedLibraryLoader, repository, conflictUrls)
        .flatMap(_ -> processSequentially(remaining, sharedLibraryLoader, repository, conflictUrls));
    }

    private static Promise<Unit> processSingleDependency(ArtifactDependency dependency,
                                                         SharedLibraryClassLoader sharedLibraryLoader,
                                                         Repository repository,
                                                         List<URL> conflictUrls) {
        return sharedLibraryLoader.checkCompatibility(dependency)
                                  .fold(() -> loadIntoShared(dependency, sharedLibraryLoader, repository),
                                        result -> handleCompatibilityResult(dependency, result, repository, conflictUrls));
    }

    private static Promise<Unit> handleCompatibilityResult(ArtifactDependency dependency,
                                                           CompatibilityResult result,
                                                           Repository repository,
                                                           List<URL> conflictUrls) {
        return switch (result) {
            case CompatibilityResult.Compatible(var loadedVersion) ->
            logCompatibleDependency(dependency, loadedVersion);
            case CompatibilityResult.Conflict(var loadedVersion, _) ->
            handleConflictingDependency(dependency, loadedVersion, repository, conflictUrls);
        };
    }

    private static Promise<Unit> logCompatibleDependency(ArtifactDependency dependency, Version loadedVersion) {
        log.debug("Shared dependency {} compatible with loaded version {}",
                  dependency.asString(),
                  loadedVersion.withQualifier());
        return Promise.success(unit());
    }

    private static Promise<Unit> handleConflictingDependency(ArtifactDependency dependency,
                                                             Version loadedVersion,
                                                             Repository repository,
                                                             List<URL> conflictUrls) {
        log.info("Shared dependency {} conflicts with loaded version {}, will load into slice",
                 dependency.asString(),
                 loadedVersion.withQualifier());
        return loadConflictIntoSlice(dependency, repository, conflictUrls);
    }

    private static Promise<Unit> loadIntoShared(ArtifactDependency dependency,
                                                SharedLibraryClassLoader sharedLibraryLoader,
                                                Repository repository) {
        return toArtifact(dependency).async()
                         .flatMap(repository::locate)
                         .map(location -> addToSharedLoader(dependency,
                                                            sharedLibraryLoader,
                                                            location.url()));
    }

    private static Unit addToSharedLoader(ArtifactDependency dependency,
                                          SharedLibraryClassLoader sharedLibraryLoader,
                                          URL url) {
        var version = extractVersion(dependency.versionPattern());
        sharedLibraryLoader.addArtifact(dependency.groupId(), dependency.artifactId(), version, url);
        log.debug("Loaded shared dependency {} into SharedLibraryClassLoader", dependency.asString());
        return unit();
    }

    private static Promise<Unit> loadConflictIntoSlice(ArtifactDependency dependency,
                                                       Repository repository,
                                                       List<URL> conflictUrls) {
        return toArtifact(dependency).async()
                         .flatMap(repository::locate)
                         .map(location -> addConflictUrl(dependency,
                                                         conflictUrls,
                                                         location.url()));
    }

    private static Unit addConflictUrl(ArtifactDependency dependency, List<URL> conflictUrls, URL url) {
        conflictUrls.add(url);
        log.debug("Added conflicting dependency {} to slice classloader", dependency.asString());
        return unit();
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
        };
    }
}
