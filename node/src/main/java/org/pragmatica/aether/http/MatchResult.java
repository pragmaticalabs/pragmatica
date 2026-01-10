package org.pragmatica.aether.http;

import org.pragmatica.aether.slice.routing.Route;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.Map;

/**
 * Result of matching a request to a route.
 */
public record MatchResult(Route route,
                          Map<String, String> pathVariables) {
    private static final Cause NULL_ROUTE = Causes.cause("Route cannot be null");
    private static final Cause NULL_PATH_VARIABLES = Causes.cause("Path variables map cannot be null");

    public static Result<MatchResult> matchResult(Route route, Map<String, String> pathVariables) {
        if (route == null) {
            return NULL_ROUTE.result();
        }
        if (pathVariables == null) {
            return NULL_PATH_VARIABLES.result();
        }
        return Result.success(new MatchResult(route, Map.copyOf(pathVariables)));
    }
}
