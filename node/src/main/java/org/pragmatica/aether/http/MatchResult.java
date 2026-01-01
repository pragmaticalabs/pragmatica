package org.pragmatica.aether.http;

import org.pragmatica.aether.slice.routing.Route;

import java.util.Map;

/**
 * Result of matching a request to a route.
 */
public record MatchResult(
 Route route,
 Map<String, String> pathVariables) {
    public static MatchResult matchResult(Route route, Map<String, String> pathVariables) {
        return new MatchResult(route, pathVariables);
    }
}
