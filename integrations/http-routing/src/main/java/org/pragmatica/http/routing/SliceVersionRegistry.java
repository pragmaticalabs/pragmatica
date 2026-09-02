package org.pragmatica.http.routing;

import java.util.List;

import org.pragmatica.lang.Option;


/// Per-slice API version registry (#198 §6.4).
///
/// Captures the version-agnostic deployment metadata a versioned slice needs to be exposed in
/// EITHER detection mode at registration time: the version segment is no longer baked into route
/// paths at codegen, so the runtime composes the mounted path (path mode, the default) or selects
/// the version from a request header (header mode, a later step) using this registry.
///
/// Emitted as a constant on the generated `{Slice}Routes` artifact and exposed via
/// [RouteSource#versionRegistry()]. Unversioned slices expose [#UNVERSIONED].
///
/// @param apiPrefix            version-agnostic base prefix (e.g. `/api/orders`); empty for unversioned slices
/// @param requireVersionHeader whether a version header is required (header-mode flag, used by a later step)
/// @param defaultVersion       the version selected when none is specified, if any version declared `defaultIfMissing`
/// @param versions             per-version metadata in declaration order (empty for unversioned slices)
public record SliceVersionRegistry(String apiPrefix,
                                   boolean requireVersionHeader,
                                   Option<Integer> defaultVersion,
                                   List<VersionInfo> versions) {
    public SliceVersionRegistry {
        versions = List.copyOf(versions);
    }

    /// The registry exposed by an unversioned slice: no API prefix, no header requirement, no versions.
    public static final SliceVersionRegistry UNVERSIONED = new SliceVersionRegistry("", false, Option.none(), List.of());

    /// Factory for a versioned slice's registry.
    ///
    /// @param apiPrefix            version-agnostic base prefix
    /// @param requireVersionHeader header-mode flag
    /// @param defaultVersion       default version, if any
    /// @param versions             per-version metadata
    /// @return the version registry
    public static SliceVersionRegistry sliceVersionRegistry(String apiPrefix,
                                                            boolean requireVersionHeader,
                                                            Option<Integer> defaultVersion,
                                                            List<VersionInfo> versions) {
        return new SliceVersionRegistry(apiPrefix, requireVersionHeader, defaultVersion, versions);
    }

    /// Whether this slice declares API versions.
    public boolean isVersioned() {
        return ! versions.isEmpty();
    }

    /// Per-version metadata (#198 §6.4).
    ///
    /// @param version    the numeric version (the `N` in `[vN.routes]`)
    /// @param deprecated whether this version is marked deprecated
    /// @param sunset     optional RFC3339/ISO sunset date
    public record VersionInfo(int version, boolean deprecated, Option<String> sunset) {
        /// Factory for per-version metadata.
        ///
        /// @param version    the numeric version
        /// @param deprecated deprecation flag
        /// @param sunset     optional sunset date
        /// @return the version info
        public static VersionInfo versionInfo(int version, boolean deprecated, Option<String> sunset) {
            return new VersionInfo(version, deprecated, sunset);
        }
    }
}
