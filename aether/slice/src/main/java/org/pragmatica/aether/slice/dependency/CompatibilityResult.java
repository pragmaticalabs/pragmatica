package org.pragmatica.aether.slice.dependency;

import org.pragmatica.aether.artifact.Version;


/// Result of checking version compatibility between a requested pattern and a loaded version.
///
/// Used when a slice requests a shared dependency that's already loaded in the
/// SharedLibraryClassLoader to determine if the loaded version is compatible.
@SuppressWarnings("JBCT-UTIL-02") public sealed interface CompatibilityResult {
    record Compatible(Version loadedVersion) implements CompatibilityResult{}

    record Conflict(Version loadedVersion, VersionPattern required) implements CompatibilityResult{}

    default boolean isCompatible() {
        return this instanceof Compatible;
    }

    default boolean isConflict() {
        return this instanceof Conflict;
    }

    static CompatibilityResult check(Version loadedVersion, VersionPattern required) {
        if (required.matches(loadedVersion)) {return new Compatible(loadedVersion);}
        return new Conflict(loadedVersion, required);
    }

    record unused() implements CompatibilityResult{}
}
