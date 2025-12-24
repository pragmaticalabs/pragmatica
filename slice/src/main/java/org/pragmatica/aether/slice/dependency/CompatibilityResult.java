package org.pragmatica.aether.slice.dependency;

import org.pragmatica.aether.artifact.Version;

/**
 * Result of checking version compatibility between a requested pattern and a loaded version.
 * <p>
 * Used when a slice requests a shared dependency that's already loaded in the
 * SharedLibraryClassLoader to determine if the loaded version is compatible.
 */
public sealed interface CompatibilityResult {

    /**
     * The loaded version is compatible with the requested pattern.
     * The slice can use the already-loaded version.
     *
     * @param loadedVersion The version currently loaded in SharedLibraryClassLoader
     */
    record Compatible(Version loadedVersion) implements CompatibilityResult {}

    /**
     * The loaded version is NOT compatible with the requested pattern.
     * The slice must load its own version into its SliceClassLoader.
     *
     * @param loadedVersion The version currently loaded in SharedLibraryClassLoader
     * @param required      The version pattern requested by the slice
     */
    record Conflict(Version loadedVersion, VersionPattern required) implements CompatibilityResult {}

    /**
     * Check if this result indicates compatibility.
     */
    default boolean isCompatible() {
        return this instanceof Compatible;
    }

    /**
     * Check if this result indicates a conflict.
     */
    default boolean isConflict() {
        return this instanceof Conflict;
    }

    /**
     * Check compatibility between a loaded version and a required pattern.
     *
     * @param loadedVersion The version currently loaded
     * @param required      The version pattern required by the slice
     * @return Compatible if version matches pattern, Conflict otherwise
     */
    static CompatibilityResult check(Version loadedVersion, VersionPattern required) {
        if (required.matches(loadedVersion)) {
            return new Compatible(loadedVersion);
        }
        return new Conflict(loadedVersion, required);
    }
}
