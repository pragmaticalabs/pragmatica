package org.pragmatica.aether.http;

import org.pragmatica.aether.slice.routing.Route;
import org.pragmatica.aether.slice.routing.RoutingSection;
import org.pragmatica.lang.Option;

import java.util.ArrayList;
import java.util.List;

/**
 * Matches incoming HTTP requests to configured routes.
 */
public interface RouteMatcher {

    /**
     * Find a matching route for the given request.
     */
    Option<MatchResult> match(HttpMethod method, String path);

    /**
     * Create a RouteMatcher from routing sections.
     */
    static RouteMatcher routeMatcher(List<RoutingSection> sections) {
        var compiledRoutes = new ArrayList<CompiledRoute>();

        for (var section : sections) {
            if (!"http".equals(section.protocol()) && !"https".equals(section.protocol())) {
                continue; // Only process HTTP routes
            }

            for (var route : section.routes()) {
                var pattern = PathPattern.compile(route.pattern());
                compiledRoutes.add(new CompiledRoute(pattern, route));
            }
        }

        return new RouteMatcherImpl(compiledRoutes);
    }
}

record CompiledRoute(PathPattern pattern, Route route) {}

class RouteMatcherImpl implements RouteMatcher {

    private final List<CompiledRoute> compiledRoutes;

    RouteMatcherImpl(List<CompiledRoute> compiledRoutes) {
        this.compiledRoutes = compiledRoutes;
    }

    @Override
    public Option<MatchResult> match(HttpMethod method, String path) {
        for (var compiled : compiledRoutes) {
            var matchOpt = compiled.pattern().match(method, path);
            if (matchOpt.isPresent()) {
                return matchOpt.map(vars -> MatchResult.matchResult(compiled.route(), vars));
            }
        }
        return Option.none();
    }
}
