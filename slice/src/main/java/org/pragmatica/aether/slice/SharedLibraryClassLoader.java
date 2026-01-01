package org.pragmatica.aether.slice;

import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.dependency.ArtifactDependency;
import org.pragmatica.aether.slice.dependency.CompatibilityResult;
import org.pragmatica.aether.slice.dependency.VersionPattern;
import org.pragmatica.lang.Option;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ClassLoader for shared dependencies across all slices.
 * <p>
 * This classloader sits between the Node ClassLoader and individual Slice ClassLoaders,
 * providing a layer for libraries that are shared across multiple slices.
 * <p>
 * Key behaviors:
 * <ul>
 *   <li>Uses parent-first delegation (standard behavior)</li>
 *   <li>Tracks loaded artifacts with their versions for conflict detection</li>
 *   <li>First slice to load a dependency sets the canonical version</li>
 *   <li>Subsequent slices check compatibility against loaded version</li>
 * </ul>
 * <p>
 * Thread-safe: Uses ConcurrentHashMap for artifact tracking and synchronized URL addition.
 *
 * @see SliceClassLoader
 * @see CompatibilityResult
 */
public class SharedLibraryClassLoader extends URLClassLoader {
    private static final Logger log = LoggerFactory.getLogger(SharedLibraryClassLoader.class);

    /**
     * Tracks loaded artifacts: artifactKey (groupId:artifactId) -> loaded Version
     */
    private final Map<String, Version> loadedArtifacts = new ConcurrentHashMap<>();

    /**
     * Create a new SharedLibraryClassLoader.
     *
     * @param parent The parent classloader (typically the Node/Application ClassLoader)
     */
    public SharedLibraryClassLoader(ClassLoader parent) {
        super(new URL[0], parent);
    }

    /**
     * Check if an artifact is already loaded and whether it's compatible.
     *
     * @param dependency The requested dependency
     * @return Empty if not loaded, or CompatibilityResult indicating compatibility
     */
    public Option<CompatibilityResult> checkCompatibility(ArtifactDependency dependency) {
        return checkCompatibility(dependency.groupId(), dependency.artifactId(), dependency.versionPattern());
    }

    /**
     * Check if an artifact is already loaded and whether it's compatible.
     *
     * @param groupId    Maven group ID
     * @param artifactId Maven artifact ID
     * @param required   Required version pattern
     * @return Empty if not loaded, or CompatibilityResult indicating compatibility
     */
    public Option<CompatibilityResult> checkCompatibility(String groupId, String artifactId, VersionPattern required) {
        var key = artifactKey(groupId, artifactId);
        var loadedVersion = loadedArtifacts.get(key);
        if (loadedVersion == null) {
            return Option.empty();
        }
        return Option.some(CompatibilityResult.check(loadedVersion, required));
    }

    /**
     * Add an artifact JAR to this classloader.
     * <p>
     * Called when the first slice loads a shared dependency.
     * Subsequent slices that request compatible versions will reuse this artifact.
     *
     * @param groupId    Maven group ID
     * @param artifactId Maven artifact ID
     * @param version    The version being loaded
     * @param jarUrl     URL to the JAR file
     */
    public synchronized void addArtifact(String groupId, String artifactId, Version version, URL jarUrl) {
        var key = artifactKey(groupId, artifactId);
        if (loadedArtifacts.containsKey(key)) {
            log.warn("Artifact {} already loaded with version {}, ignoring request to load version {}",
                     key,
                     loadedArtifacts.get(key)
                                    .withQualifier(),
                     version.withQualifier());
            return;
        }
        addURL(jarUrl);
        loadedArtifacts.put(key, version);
        log.debug("Added shared artifact {}:{} from {}", key, version.withQualifier(), jarUrl);
    }

    /**
     * Check if an artifact is already loaded.
     *
     * @param groupId    Maven group ID
     * @param artifactId Maven artifact ID
     * @return true if already loaded
     */
    public boolean isLoaded(String groupId, String artifactId) {
        return loadedArtifacts.containsKey(artifactKey(groupId, artifactId));
    }

    /**
     * Get the version of a loaded artifact.
     *
     * @param groupId    Maven group ID
     * @param artifactId Maven artifact ID
     * @return The loaded version, or empty if not loaded
     */
    public Option<Version> getLoadedVersion(String groupId, String artifactId) {
        return Option.option(loadedArtifacts.get(artifactKey(groupId, artifactId)));
    }

    /**
     * Get all loaded artifacts.
     *
     * @return Unmodifiable map of artifactKey -> Version
     */
    public Map<String, Version> getLoadedArtifacts() {
        return Map.copyOf(loadedArtifacts);
    }

    @Override
    public void close() throws IOException {
        loadedArtifacts.clear();
        super.close();
    }

    private static String artifactKey(String groupId, String artifactId) {
        return groupId + ":" + artifactId;
    }
}
