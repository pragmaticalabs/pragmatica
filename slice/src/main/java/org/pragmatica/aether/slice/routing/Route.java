package org.pragmatica.aether.slice.routing;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.List;

public record Route(String pattern, RouteTarget target, List<Binding> bindings) {
    private static final Cause EMPTY_PATTERN = Causes.cause("Route pattern cannot be empty");
    private static final Cause NULL_TARGET = Causes.cause("Route target cannot be null");

    public static Result<Route> route(String pattern, RouteTarget target, List<Binding> bindings) {
        if (pattern == null || pattern.isBlank()) {
            return EMPTY_PATTERN.result();
        }
        if (target == null) {
            return NULL_TARGET.result();
        }
        return Result.success(new Route(pattern, target, List.copyOf(bindings)));
    }
}
