package org.pragmatica.aether.lb;

import org.pragmatica.http.server.RequestContext;
import org.pragmatica.lang.Option;

import java.util.List;

/// Strategy for selecting a backend from a list of healthy backends.
public interface RoutingStrategy {
    /// Select a backend for the given request.
    ///
    /// @param healthy List of currently healthy backends
    /// @param request The incoming request
    /// @return Selected backend, or empty if no backends available
    Option<Backend> select(List<Backend> healthy, RequestContext request);
}
