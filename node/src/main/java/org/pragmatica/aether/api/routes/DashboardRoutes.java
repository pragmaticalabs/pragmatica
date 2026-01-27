package org.pragmatica.aether.api.routes;

import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.server.RequestContext;
import org.pragmatica.http.server.ResponseWriter;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes for dashboard static files.
 */
public final class DashboardRoutes implements RouteHandler {
    private static final Logger log = LoggerFactory.getLogger(DashboardRoutes.class);

    private DashboardRoutes() {}

    public static DashboardRoutes dashboardRoutes() {
        return new DashboardRoutes();
    }

    @Override
    public boolean handle(RequestContext ctx, ResponseWriter response) {
        if (ctx.method() != HttpMethod.GET) {
            return false;
        }
        var path = ctx.path();
        return switch (path) {
            case "/dashboard", "/dashboard/" -> {
                serveDashboardFile(response, "index.html", CommonContentType.TEXT_HTML);
                yield true;
            }
            case "/dashboard/style.css" -> {
                serveDashboardFile(response, "style.css", CommonContentType.TEXT_CSS);
                yield true;
            }
            case "/dashboard/dashboard.js" -> {
                serveDashboardFile(response, "dashboard.js", CommonContentType.TEXT_JAVASCRIPT);
                yield true;
            }
            default -> false;
        };
    }

    private void serveDashboardFile(ResponseWriter response, String filename, CommonContentType contentType) {
        try (InputStream is = getClass().getResourceAsStream("/dashboard/" + filename)) {
            if (is == null) {
                response.notFound();
                return;
            }
            var content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            response.header("Cache-Control", "no-cache")
                    .write(HttpStatus.OK,
                           content.getBytes(StandardCharsets.UTF_8),
                           contentType);
        } catch (Exception e) {
            log.error("Error serving dashboard file: {}", filename, e);
            response.internalError(org.pragmatica.lang.utils.Causes.fromThrowable(e));
        }
    }
}
