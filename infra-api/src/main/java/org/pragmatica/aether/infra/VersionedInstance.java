package org.pragmatica.aether.infra;

import org.pragmatica.lang.Option;

import java.util.List;

/**
 * Version-tagged instance stored in InfraStore.
 * <p>
 * Contains compatibility logic for finding compatible instances
 * from a list of available versions.
 *
 * @param version  Semantic version string (e.g., "1.2.3" or "1.2.3-SNAPSHOT")
 * @param instance The infrastructure service instance
 * @param <T>      Instance type
 */
public record VersionedInstance<T>(String version, T instance) {
    /**
     * Create a new VersionedInstance.
     *
     * @param version  Semantic version string
     * @param instance The infrastructure service instance
     * @param <T>      Instance type
     * @return New VersionedInstance
     */
    public static <T> VersionedInstance<T> versionedInstance(String version, T instance) {
        return new VersionedInstance<>(version, instance);
    }

    /**
     * Check if this instance is compatible with requested version.
     * <p>
     * Compatibility rules (semver-style):
     * <ul>
     *   <li>Major version must match</li>
     *   <li>Minor version of this instance must be >= requested</li>
     *   <li>Qualifiers (SNAPSHOT, etc.) are ignored for compatibility</li>
     * </ul>
     *
     * @param requested Version string to check against
     * @return true if this instance is compatible
     */
    public boolean isCompatibleWith(String requested) {
        return parseVersion(version).flatMap(thisV -> parseVersion(requested).map(reqV -> isCompatible(thisV, reqV)))
                           .or(() -> stripQualifier(version).equals(stripQualifier(requested)));
    }

    private static boolean isCompatible(ParsedVersion thisV, ParsedVersion reqV) {
        return thisV.major() == reqV.major() && thisV.minor() >= reqV.minor() && (thisV.minor() > reqV.minor() || thisV.patch() >= reqV.patch());
    }

    /**
     * Check if this instance has exact version match.
     *
     * @param requested Version string to check against
     * @return true if versions match exactly (ignoring qualifier)
     */
    public boolean isExactMatch(String requested) {
        return stripQualifier(version).equals(stripQualifier(requested));
    }

    /**
     * Find first compatible instance from list.
     * <p>
     * Prefers exact matches over compatible matches.
     *
     * @param instances List of available instances
     * @param version   Requested version
     * @param <T>       Instance type
     * @return Compatible instance if found
     */
    public static <T> Option<T> findCompatible(List<VersionedInstance<T>> instances, String version) {
        var exact = findExact(instances, version);
        return exact.isPresent()
               ? exact
               : instances.stream()
                          .filter(vi -> vi.isCompatibleWith(version))
                          .findFirst()
                          .map(VersionedInstance::instance)
                          .map(Option::some)
                          .orElse(Option.none());
    }

    /**
     * Find instance with exact version match.
     *
     * @param instances List of available instances
     * @param version   Requested version
     * @param <T>       Instance type
     * @return Exact match if found
     */
    public static <T> Option<T> findExact(List<VersionedInstance<T>> instances, String version) {
        return instances.stream()
                        .filter(vi -> vi.isExactMatch(version))
                        .findFirst()
                        .map(VersionedInstance::instance)
                        .map(Option::some)
                        .orElse(Option.none());
    }

    private record ParsedVersion(int major, int minor, int patch) {
        static Option<ParsedVersion> parsedVersion(int major, int minor, int patch) {
            return Option.some(new ParsedVersion(major, minor, patch));
        }
    }

    private static Option<ParsedVersion> parseVersion(String version) {
        var stripped = stripQualifier(version);
        var parts = stripped.split("\\.");
        if (parts.length < 3) {
            return Option.none();
        }
        return parseInteger(parts[0])
        .flatMap(major -> parseInteger(parts[1])
        .flatMap(minor -> parseInteger(parts[2]).map(patch -> new ParsedVersion(major, minor, patch))));
    }

    private static Option<Integer> parseInteger(String value) {
        try{
            return Option.some(Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return Option.none();
        }
    }

    private static String stripQualifier(String version) {
        var dashIndex = version.indexOf('-');
        return dashIndex > 0
               ? version.substring(0, dashIndex)
               : version;
    }
}
