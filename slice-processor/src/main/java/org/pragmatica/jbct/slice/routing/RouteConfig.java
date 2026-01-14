package org.pragmatica.jbct.slice.routing;

import java.util.HashMap;
import java.util.Map;

/**
 * Complete route configuration for a slice.
 * <p>
 * Contains:
 * <ul>
 *   <li>{@code prefix} - URL prefix for all routes (e.g., "/api/v1")</li>
 *   <li>{@code routes} - Map of handler method names to parsed route DSL</li>
 *   <li>{@code errors} - Error pattern configuration for status code mapping</li>
 * </ul>
 *
 * @param prefix URL prefix for all routes
 * @param routes map of handler name to parsed RouteDsl
 * @param errors error pattern configuration
 */
public record RouteConfig(String prefix,
                          Map<String, RouteDsl> routes,
                          ErrorPatternConfig errors) {
    /**
     * Empty configuration.
     */
    public static final RouteConfig EMPTY = routeConfig();

    /**
     * Factory method for empty configuration.
     */
    public static RouteConfig routeConfig() {
        return new RouteConfig("", Map.of(), ErrorPatternConfig.EMPTY);
    }

    /**
     * Factory method with all parameters.
     */
    public static RouteConfig routeConfig(String prefix,
                                          Map<String, RouteDsl> routes,
                                          ErrorPatternConfig errors) {
        return new RouteConfig(prefix, routes, errors);
    }

    /**
     * Merge this configuration with another, with other taking precedence.
     * <p>
     * Merging behavior:
     * <ul>
     *   <li>prefix: other's value if not empty</li>
     *   <li>routes: combined, with other's routes overriding this's</li>
     *   <li>errors: merged via ErrorPatternConfig.merge()</li>
     * </ul>
     *
     * @param other the configuration to merge with (takes precedence)
     * @return merged configuration
     */
    public RouteConfig merge(RouteConfig other) {
        if (other == null) {
            return this;
        }
        var mergedPrefix = other.prefix.isEmpty()
                           ? this.prefix
                           : other.prefix;
        var mergedRoutes = mergeRoutes(this.routes, other.routes);
        var mergedErrors = this.errors.merge(other.errors);
        return routeConfig(mergedPrefix, mergedRoutes, mergedErrors);
    }

    private static Map<String, RouteDsl> mergeRoutes(Map<String, RouteDsl> base,
                                                     Map<String, RouteDsl> overlay) {
        var merged = new HashMap<>(base);
        merged.putAll(overlay);
        return Map.copyOf(merged);
    }

    /**
     * Check if configuration has any routes defined.
     */
    public boolean hasRoutes() {
        return ! routes.isEmpty();
    }

    /**
     * Check if configuration has a prefix defined.
     */
    public boolean hasPrefix() {
        return ! prefix.isEmpty();
    }

    /**
     * Get the full path for a route, including prefix.
     *
     * @param handlerName the handler method name
     * @return full path or empty string if route not found
     */
    public String fullPath(String handlerName) {
        var route = routes.get(handlerName);
        if (route == null) {
            return "";
        }
        return prefix.isEmpty()
               ? route.pathTemplate()
               : prefix + route.pathTemplate();
    }
}
