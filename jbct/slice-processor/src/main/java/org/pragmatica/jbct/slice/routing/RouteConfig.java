// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.jbct.slice.routing;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.pragmatica.lang.Option;


/// Complete route configuration for a slice.
///
/// Contains:
///
///   - `prefix` - URL prefix for all routes of an UNVERSIONED slice (e.g., "/api/v1"). Empty for
///     versioned slices: their per-route paths stay un-versioned and the mounted path is composed
///     at route-registration time from `apiPrefix` + the per-route version (#198 §6.4).
///   - `routes` - Map of handler method names to parsed route DSL. For versioned slices every entry
///     is a resolved `getV{N}` method whose path is the UN-versioned route path (no baked `/v{N}/`)
///   - `errors` - Error pattern configuration for status code mapping
///   - `securityDefault` - Default security level for routes without explicit override
///   - `overridePolicy` - Controls how operators can override security at deploy time
///   - `routeSecurity` - Per-route security level overrides
///   - `apiPrefix` - version-agnostic base prefix from the `[api]` section (#198 §4); used at
///     registration time to compose `{apiPrefix}/v{N}/{path}` for versioned routes
///   - `requireVersionHeader` - header-mode flag from `[api]` (parsed and stored now, used in a later phase)
///   - `versions` - per-version metadata keyed by version number (empty for unversioned slices)
///   - `routeVersions` - per-handler API version (handler method name to version `N`; `0` for
///     unversioned routes). Carries the version metadata from the flattened routes to codegen.
///
///
/// A slice is EITHER unversioned (flat `[routes]` + top-level `prefix`, `versions` empty) OR
/// versioned (`[vN.routes]` blocks + `[api] prefix`, `versions` populated). The two are mutually
/// exclusive; mixing them is a compile error reported by [VersionSchemaValidator].
///
/// @param prefix URL prefix for all routes (unversioned slices)
/// @param routes map of handler name to parsed RouteDsl (versioned routes keep the un-versioned path)
/// @param errors error pattern configuration
/// @param securityDefault default security level from [security] section
/// @param overridePolicy override policy from [security] section
/// @param routeSecurity per-route security overrides
/// @param apiPrefix version-agnostic base prefix from the [api] section
/// @param requireVersionHeader header-mode flag from the [api] section
/// @param versions per-version metadata keyed by version number
/// @param routeVersions per-handler API version (`0` for unversioned routes)
public record RouteConfig(String prefix,
                          Map<String, RouteDsl> routes,
                          ErrorPatternConfig errors,
                          RouteSecurityLevel securityDefault,
                          OverridePolicy overridePolicy,
                          Map<String, RouteSecurityLevel> routeSecurity,
                          String apiPrefix,
                          boolean requireVersionHeader,
                          Map<Integer, VersionConfig> versions,
                          Map<String, Integer> routeVersions) {
    public RouteConfig {
        routes = Collections.unmodifiableMap(new LinkedHashMap<>(routes));
        routeSecurity = Collections.unmodifiableMap(new LinkedHashMap<>(routeSecurity));
        versions = Collections.unmodifiableMap(new LinkedHashMap<>(versions));
        routeVersions = Collections.unmodifiableMap(new LinkedHashMap<>(routeVersions));
    }

    /// Empty configuration.
    public static final RouteConfig EMPTY = routeConfig();

    /// Factory method for empty configuration.
    public static RouteConfig routeConfig() {
        return new RouteConfig("",
                               Map.of(),
                               ErrorPatternConfig.EMPTY,
                               RouteSecurityLevel.UNSPECIFIED,
                               OverridePolicy.STRENGTHEN_ONLY,
                               Map.of(),
                               "",
                               false,
                               Map.of(),
                               Map.of());
    }

    /// Full factory method including the `[api]` and versioning fields.
    public static RouteConfig routeConfig(String prefix,
                                          Map<String, RouteDsl> routes,
                                          ErrorPatternConfig errors,
                                          RouteSecurityLevel securityDefault,
                                          OverridePolicy overridePolicy,
                                          Map<String, RouteSecurityLevel> routeSecurity,
                                          String apiPrefix,
                                          boolean requireVersionHeader,
                                          Map<Integer, VersionConfig> versions,
                                          Map<String, Integer> routeVersions) {
        return new RouteConfig(prefix,
                               routes,
                               errors,
                               securityDefault,
                               overridePolicy,
                               routeSecurity,
                               apiPrefix,
                               requireVersionHeader,
                               versions,
                               routeVersions);
    }

    /// Factory method with security parameters but no versioning (unversioned slices).
    public static RouteConfig routeConfig(String prefix,
                                          Map<String, RouteDsl> routes,
                                          ErrorPatternConfig errors,
                                          RouteSecurityLevel securityDefault,
                                          OverridePolicy overridePolicy,
                                          Map<String, RouteSecurityLevel> routeSecurity) {
        return new RouteConfig(prefix,
                               routes,
                               errors,
                               securityDefault,
                               overridePolicy,
                               routeSecurity,
                               "",
                               false,
                               Map.of(),
                               Map.of());
    }

    /// Backward-compatible factory method without security parameters.
    public static RouteConfig routeConfig(String prefix, Map<String, RouteDsl> routes, ErrorPatternConfig errors) {
        return new RouteConfig(prefix,
                               routes,
                               errors,
                               RouteSecurityLevel.UNSPECIFIED,
                               OverridePolicy.STRENGTHEN_ONLY,
                               Map.of(),
                               "",
                               false,
                               Map.of(),
                               Map.of());
    }

    /// Whether this slice declares API versions (`[vN.routes]` blocks).
    public boolean isVersioned() {
        return ! versions.isEmpty();
    }

    /// The API version of a route handler (`0` for unversioned routes).
    public int routeVersion(String handlerName) {
        return Option.option(routeVersions.get(handlerName)).or(0);
    }

    /// Resolve the effective security level for a route.
    /// Uses per-route override if present, otherwise the section default.
    public RouteSecurityLevel effectiveSecurity(String handlerName) {
        return Option.option(routeSecurity.get(handlerName)).or(securityDefault);
    }

    /// Merge this configuration with another, with other taking precedence.
    ///
    /// Merging behavior:
    ///
    ///   - prefix: other's value if not empty
    ///   - routes: combined, with other's routes overriding this's
    ///   - errors: merged via ErrorPatternConfig.merge()
    ///   - securityDefault: other's value takes precedence
    ///   - overridePolicy: other's value takes precedence
    ///   - routeSecurity: combined, with other's overrides taking precedence
    ///
    ///
    /// @param other the configuration to merge with (takes precedence)
    /// @return merged configuration
    public RouteConfig merge(Option<RouteConfig> other) {
        return other.map(this::mergeWith)
                    .or(this);
    }

    private RouteConfig mergeWith(RouteConfig other) {
        var mergedPrefix = other.prefix.isEmpty()
                           ? this.prefix
                           : other.prefix;
        var mergedRoutes = mergeMaps(this.routes, other.routes);
        var mergedErrors = this.errors.merge(Option.some(other.errors));
        var mergedSecDefault = other.securityDefault;
        var mergedPolicy = other.overridePolicy;
        var mergedRouteSec = mergeMaps(this.routeSecurity, other.routeSecurity);
        var mergedApiPrefix = other.apiPrefix.isEmpty()
                              ? this.apiPrefix
                              : other.apiPrefix;
        var mergedRequireHeader = other.requireVersionHeader || this.requireVersionHeader;
        var mergedVersions = mergeVersions(this.versions, other.versions);
        var mergedRouteVersions = mergeMaps(this.routeVersions, other.routeVersions);

        return routeConfig(mergedPrefix,
                           mergedRoutes,
                           mergedErrors,
                           mergedSecDefault,
                           mergedPolicy,
                           mergedRouteSec,
                           mergedApiPrefix,
                           mergedRequireHeader,
                           mergedVersions,
                           mergedRouteVersions);
    }

    private static <V> Map<String, V> mergeMaps(Map<String, V> base, Map<String, V> overlay) {
        var merged = new LinkedHashMap<>(base);

        merged.putAll(overlay);

        return Collections.unmodifiableMap(merged);
    }

    private static Map<Integer, VersionConfig> mergeVersions(Map<Integer, VersionConfig> base,
                                                             Map<Integer, VersionConfig> overlay) {
        var merged = new LinkedHashMap<>(base);

        merged.putAll(overlay);

        return Collections.unmodifiableMap(merged);
    }

    /// Check if configuration has any routes defined.
    public boolean hasRoutes() {
        return ! routes.isEmpty();
    }

    /// Check if configuration has a prefix defined.
    public boolean hasPrefix() {
        return ! prefix.isEmpty();
    }

    /// Get the full path for a route, including prefix.
    ///
    /// @param handlerName the handler method name
    /// @return full path if route found, empty Option otherwise
    public Option<String> fullPath(String handlerName) {
        return Option.option(routes.get(handlerName)).map(route -> prefix.isEmpty()
                                                                   ? route.pathTemplate()
                                                                   : prefix + route.pathTemplate());
    }
}
