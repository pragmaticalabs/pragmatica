package org.pragmatica.http.server;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.function.BiConsumer;

/**
 * HTTP server abstraction.
 */
public interface HttpServer {
    /**
     * Server port.
     */
    int port();

    /**
     * Stop the server.
     */
    Promise<Unit> stop();

    /**
     * Create and start an HTTP server.
     *
     * @param config  server configuration
     * @param handler request handler
     *
     * @return promise of the running server
     */
    static Promise<HttpServer> httpServer(HttpServerConfig config, BiConsumer<RequestContext, ResponseWriter> handler) {
        return NettyHttpServer.create(config, handler);
    }
}
