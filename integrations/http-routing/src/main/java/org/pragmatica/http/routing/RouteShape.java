package org.pragmatica.http.routing;

import java.util.List;


/// What distinguishes routes that share a base path: the base [#path()], the number of trailing segments the route
/// consumes ([#pathParamCount()], spacers included) and its literal [#spacers()]. [RouteShapeSelector] picks among
/// shapes by exactly these three, so anything that can present them -- a [Route] in a slice's router, or a route
/// entry replicated to a node that does not host it -- resolves a request path to the same sibling (#1678).
public interface RouteShape {
    String path();

    default int pathParamCount() {
        return 0;
    }

    default List<String> spacers() {
        return List.of();
    }

    /// The position of each [#spacers()] entry among the route's trailing segments (0-based, counting real
    /// parameters), parallel to `spacers()`. EMPTY means "not carried": a shape that does not know its positions
    /// (a replicated route entry, #1678) is matched by set membership alone, as before (#755).
    default List<Integer> spacerSlots() {
        return List.of();
    }
}
