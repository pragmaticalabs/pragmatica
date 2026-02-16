package org.pragmatica.aether.environment;

import java.util.Set;

/// Describes a single route mapping: an HTTP method + path prefix → set of node IPs that serve it.
public record RouteChange(String httpMethod, String pathPrefix, Set<String> nodeIps) {
    public static RouteChange routeChange(String httpMethod, String pathPrefix, Set<String> nodeIps) {
        return new RouteChange(httpMethod, pathPrefix, Set.copyOf(nodeIps));
    }
}
