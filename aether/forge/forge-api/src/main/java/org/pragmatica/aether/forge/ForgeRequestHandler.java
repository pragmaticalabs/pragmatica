package org.pragmatica.aether.forge;

import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.server.RequestContext;
import org.pragmatica.http.server.ResponseWriter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes HTTP requests to the appropriate handler (API or static files).
 * Handles CORS preflight requests.
 */
public final class ForgeRequestHandler {
    private static final Logger log = LoggerFactory.getLogger(ForgeRequestHandler.class);

    private static final String ACCESS_CONTROL_ALLOW_ORIGIN = "Access-Control-Allow-Origin";
    private static final String ACCESS_CONTROL_ALLOW_METHODS = "Access-Control-Allow-Methods";
    private static final String ACCESS_CONTROL_ALLOW_HEADERS = "Access-Control-Allow-Headers";

    private final ForgeApiHandler apiHandler;
    private final StaticFileHandler staticHandler;

    private ForgeRequestHandler(ForgeApiHandler apiHandler, StaticFileHandler staticHandler) {
        this.apiHandler = apiHandler;
        this.staticHandler = staticHandler;
    }

    public static ForgeRequestHandler forgeRequestHandler(ForgeApiHandler apiHandler, StaticFileHandler staticHandler) {
        return new ForgeRequestHandler(apiHandler, staticHandler);
    }

    public void handle(RequestContext request, ResponseWriter response) {
        var path = request.path();
        log.debug("Request: {} {}", request.method(), path);
        try{
            // Handle CORS preflight
            if (request.method() == org.pragmatica.http.HttpMethod.OPTIONS) {
                handleCors(response);
                return;
            }
            // Route to appropriate handler
            if (path.startsWith("/api/")) {
                apiHandler.handle(request, withCors(response));
            } else {
                staticHandler.handle(request, response);
            }
        } catch (Exception e) {
            log.error("Error handling request: {}", e.getMessage(), e);
            response.error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private void handleCors(ResponseWriter response) {
        response.header(ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                .header(ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS")
                .header(ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type")
                .write(HttpStatus.OK, new byte[0], CommonContentType.TEXT_PLAIN);
    }

    private ResponseWriter withCors(ResponseWriter response) {
        return response.header(ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                       .header(ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS")
                       .header(ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type");
    }
}
