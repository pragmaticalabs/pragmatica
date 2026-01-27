package org.pragmatica.aether.http;

import org.pragmatica.aether.http.handler.HttpRouteDefinition;
import org.pragmatica.aether.http.handler.security.RouteSecurityPolicy;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;

import java.util.List;

import static org.pragmatica.aether.http.handler.HttpRouteDefinition.httpRouteDefinition;

/**
 * Extracts route metadata from {@link RouteSource} and converts to {@link HttpRouteDefinition} objects
 * for KV-Store publication.
 *
 * <p>Each route is converted with:
 * <ul>
 *   <li>httpMethod - from {@code route.method().name()}</li>
 *   <li>pathPrefix - from {@code route.path()}</li>
 *   <li>artifactCoord - passed as parameter</li>
 *   <li>sliceMethod - derived from route path (used as method identifier)</li>
 *   <li>security - defaults to {@link RouteSecurityPolicy#publicRoute()}</li>
 * </ul>
 */
public interface RouteMetadataExtractor {
    /**
     * Extract route definitions from a route source.
     *
     * @param routes       The route source providing routes
     * @param artifactCoord The artifact coordinate to associate with routes
     * @return List of HTTP route definitions for KV-Store publication
     */
    List<HttpRouteDefinition> extract(RouteSource routes, String artifactCoord);

    /**
     * Create a RouteMetadataExtractor instance.
     */
    static RouteMetadataExtractor routeMetadataExtractor() {
        return new RouteMetadataExtractorImpl();
    }
}

class RouteMetadataExtractorImpl implements RouteMetadataExtractor {
    @Override
    public List<HttpRouteDefinition> extract(RouteSource routes, String artifactCoord) {
        return routes.routes()
                     .map(route -> toDefinition(route, artifactCoord))
                     .toList();
    }

    private HttpRouteDefinition toDefinition(Route<?> route, String artifactCoord) {
        return httpRouteDefinition(route.method()
                                        .name(),
                                   route.path(),
                                   artifactCoord,
                                   deriveSliceMethod(route.path()),
                                   RouteSecurityPolicy.publicRoute());
    }

    private String deriveSliceMethod(String path) {
        return path;
    }
}
