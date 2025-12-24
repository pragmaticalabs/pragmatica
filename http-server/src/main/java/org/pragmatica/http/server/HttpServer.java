package org.pragmatica.http.server;

import org.pragmatica.http.server.impl.NettyHttpServer;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.function.BiConsumer;

/**
 * HTTP server interface.
 * <p>
 * Create instances using the factory method {@link #httpServer(HttpServerConfig, BiConsumer)}.
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * var config = HttpServerConfig.https(8443, TlsConfig.selfSigned());
 *
 * HttpServer.httpServer(config, (request, response) -> {
 *     if (request.method() == HttpMethod.GET && request.path().equals("/health")) {
 *         response.ok("{\"status\":\"UP\"}");
 *     } else {
 *         response.notFound();
 *     }
 * })
 * .flatMap(HttpServer::start)
 * .onSuccess(_ -> System.out.println("Server started"))
 * .onFailure(cause -> System.err.println("Failed: " + cause.message()));
 * }</pre>
 */
public interface HttpServer {

    /**
     * Start the server.
     *
     * @return promise that completes when server is listening
     */
    Promise<Unit> start();

    /**
     * Stop the server gracefully.
     *
     * @return promise that completes when server is stopped
     */
    Promise<Unit> stop();

    /**
     * Get the port the server is configured to listen on.
     *
     * @return configured port number
     */
    int port();

    /**
     * Create a new HTTP server.
     *
     * @param config  server configuration
     * @param handler request handler that processes each request
     * @return promise with server instance or error
     */
    static Promise<HttpServer> httpServer(
            HttpServerConfig config,
            BiConsumer<RequestContext, ResponseWriter> handler
    ) {
        return NettyHttpServer.create(config, handler);
    }
}
