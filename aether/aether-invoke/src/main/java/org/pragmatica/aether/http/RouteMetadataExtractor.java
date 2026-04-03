package org.pragmatica.aether.http;

import org.pragmatica.aether.http.handler.HttpRouteDefinition;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;

import java.util.List;

import static org.pragmatica.aether.http.handler.HttpRouteDefinition.httpRouteDefinition;


/// Extracts route metadata from {@link RouteSource} and converts to {@link HttpRouteDefinition} objects
/// for KV-Store publication.
///
///
/// Each route is converted with:
///
///   - httpMethod - from `route.method().name()`
///   - pathPrefix - from `route.path()`
///   - artifactCoord - passed as parameter
///   - sliceMethod - derived from route path (used as method identifier)
///   - security - from route's security policy or defaults to {@link SecurityPolicy#publicRoute()}
///
public interface RouteMetadataExtractor {
    List<HttpRouteDefinition> extract(RouteSource routes, String artifactCoord);

    static RouteMetadataExtractor routeMetadataExtractor() {
        return new RouteMetadataExtractorImpl();
    }
}

class RouteMetadataExtractorImpl implements RouteMetadataExtractor {
    @Override public List<HttpRouteDefinition> extract(RouteSource routes, String artifactCoord) {
        return routes.routes().map(route -> toDefinition(route, artifactCoord))
                            .toList();
    }

    private HttpRouteDefinition toDefinition(Route<?> route, String artifactCoord) {
        var security = resolveSecurityPolicy(route);
        return httpRouteDefinition(route.method().name(),
                                   extractPathPrefix(route.path()),
                                   artifactCoord,
                                   deriveSliceMethod(route),
                                   security);
    }

    private static SecurityPolicy resolveSecurityPolicy(Route<?> route) {
        return route.security() instanceof SecurityPolicy sp
              ? sp
              : SecurityPolicy.publicRoute();
    }

    private String extractPathPrefix(String path) {
        int placeholderIndex = path.indexOf('{');
        return placeholderIndex > 0
              ? path.substring(0, placeholderIndex)
              : path;
    }

    private String deriveSliceMethod(Route<?> route) {
        var name = route.name();
        return name.isEmpty()
              ? route.path()
              : name;
    }
}
