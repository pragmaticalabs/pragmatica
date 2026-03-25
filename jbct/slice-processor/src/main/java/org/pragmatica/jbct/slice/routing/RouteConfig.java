package org.pragmatica.jbct.slice.routing;

import org.pragmatica.lang.Option;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/// Complete route configuration for a slice.
///
/// Contains:
///
///   - `prefix` - URL prefix for all routes (e.g., "/api/v1")
///   - `routes` - Map of handler method names to parsed route DSL
///   - `errors` - Error pattern configuration for status code mapping
///   - `securityDefault` - Default security level for routes without explicit override
///   - `overridePolicy` - Controls how operators can override security at deploy time
///   - `routeSecurity` - Per-route security level overrides
///
///
/// @param prefix URL prefix for all routes
/// @param routes map of handler name to parsed RouteDsl
/// @param errors error pattern configuration
/// @param securityDefault default security level from [security] section
/// @param overridePolicy override policy from [security] section
/// @param routeSecurity per-route security overrides
public record RouteConfig(String prefix,
                          Map<String, RouteDsl> routes,
                          ErrorPatternConfig errors,
                          RouteSecurityLevel securityDefault,
                          OverridePolicy overridePolicy,
                          Map<String, RouteSecurityLevel> routeSecurity) {
    public RouteConfig {
        routes = Collections.unmodifiableMap(new LinkedHashMap<>(routes));
        routeSecurity = Collections.unmodifiableMap(new LinkedHashMap<>(routeSecurity));
    }

    /// Empty configuration.
    public static final RouteConfig EMPTY = routeConfig();

    /// Factory method for empty configuration.
    public static RouteConfig routeConfig() {
        return new RouteConfig("",
                               Map.of(),
                               ErrorPatternConfig.EMPTY,
                               RouteSecurityLevel.PUBLIC,
                               OverridePolicy.STRENGTHEN_ONLY,
                               Map.of());
    }

    /// Factory method with all parameters.
    public static RouteConfig routeConfig(String prefix,
                                          Map<String, RouteDsl> routes,
                                          ErrorPatternConfig errors,
                                          RouteSecurityLevel securityDefault,
                                          OverridePolicy overridePolicy,
                                          Map<String, RouteSecurityLevel> routeSecurity) {
        return new RouteConfig(prefix, routes, errors, securityDefault, overridePolicy, routeSecurity);
    }

    /// Backward-compatible factory method without security parameters.
    public static RouteConfig routeConfig(String prefix,
                                          Map<String, RouteDsl> routes,
                                          ErrorPatternConfig errors) {
        return new RouteConfig(prefix,
                               routes,
                               errors,
                               RouteSecurityLevel.PUBLIC,
                               OverridePolicy.STRENGTHEN_ONLY,
                               Map.of());
    }

    /// Resolve the effective security level for a route.
    /// Uses per-route override if present, otherwise the section default.
    public RouteSecurityLevel effectiveSecurity(String handlerName) {
        return Option.option(routeSecurity.get(handlerName))
                     .or(securityDefault);
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
        return routeConfig(mergedPrefix, mergedRoutes, mergedErrors, mergedSecDefault, mergedPolicy, mergedRouteSec);
    }

    private static <V> Map<String, V> mergeMaps(Map<String, V> base, Map<String, V> overlay) {
        var merged = new LinkedHashMap<>(base);
        merged.putAll(overlay);
        return Collections.unmodifiableMap(merged);
    }

    /// Check if configuration has any routes defined.
    public boolean hasRoutes() {
        return !routes.isEmpty();
    }

    /// Check if configuration has a prefix defined.
    public boolean hasPrefix() {
        return !prefix.isEmpty();
    }

    /// Get the full path for a route, including prefix.
    ///
    /// @param handlerName the handler method name
    /// @return full path if route found, empty Option otherwise
    public Option<String> fullPath(String handlerName) {
        return Option.option(routes.get(handlerName))
                     .map(route -> prefix.isEmpty()
                                   ? route.pathTemplate()
                                   : prefix + route.pathTemplate());
    }
}
