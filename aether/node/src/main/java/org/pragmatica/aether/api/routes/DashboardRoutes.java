package org.pragmatica.aether.api.routes;

import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.http.routing.StaticFileRouteSource;

import java.util.stream.Stream;

/// Routes for dashboard static files.
/// Serves static files from classpath:/dashboard/
public final class DashboardRoutes implements RouteSource {
    private DashboardRoutes() {}

    public static DashboardRoutes dashboardRoutes() {
        return new DashboardRoutes();
    }

    @Override
    public Stream<Route<?>> routes() {
        return StaticFileRouteSource.staticFiles("/dashboard", "dashboard/")
                                    .routes();
    }
}
