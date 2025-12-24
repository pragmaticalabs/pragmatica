package org.pragmatica.aether.slice.routing;

import org.pragmatica.lang.Result;

import java.util.List;

public record Route(String pattern, RouteTarget target, List<Binding> bindings) {
    public static Result<Route> route(String pattern, RouteTarget target, List<Binding> bindings) {
        return Result.success(new Route(pattern, target, bindings));
    }
}
