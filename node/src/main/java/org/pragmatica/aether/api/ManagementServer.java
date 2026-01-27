package org.pragmatica.aether.api;

import org.pragmatica.aether.api.routes.AlertRoutes;
import org.pragmatica.aether.api.routes.ControllerRoutes;
import org.pragmatica.aether.api.routes.DashboardRoutes;
import org.pragmatica.aether.api.routes.MetricsRoutes;
import org.pragmatica.aether.api.routes.RepositoryRoutes;
import org.pragmatica.aether.api.routes.RollingUpdateRoutes;
import org.pragmatica.aether.api.routes.RouteHandler;
import org.pragmatica.aether.api.routes.SliceRoutes;
import org.pragmatica.aether.api.routes.StatusRoutes;
import org.pragmatica.aether.metrics.observability.ObservabilityRegistry;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.http.server.HttpServer;
import org.pragmatica.http.server.HttpServerConfig;
import org.pragmatica.http.server.RequestContext;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.http.websocket.WebSocketEndpoint;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.net.tcp.TlsConfig;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;

/**
 * HTTP management API server for cluster administration.
 *
 * <p>Exposes REST endpoints for:
 * <ul>
 *   <li>GET /api/status - Cluster status</li>
 *   <li>GET /api/nodes - List active nodes</li>
 *   <li>GET /api/slices - List deployed slices</li>
 *   <li>GET /api/metrics - Cluster metrics</li>
 *   <li>GET /api/artifact-metrics - Artifact storage and deployment metrics</li>
 *   <li>GET /repository/info/{groupPath}/{artifactId}/{version} - Artifact metadata</li>
 * </ul>
 *
 * <p>Uses pragmatica-lite's HttpServer infrastructure.
 */
public interface ManagementServer {
    Promise<Unit> start();

    Promise<Unit> stop();

    static ManagementServer managementServer(int port,
                                             Supplier<AetherNode> nodeSupplier,
                                             AlertManager alertManager,
                                             Option<TlsConfig> tls) {
        return new ManagementServerImpl(port, nodeSupplier, alertManager, tls);
    }
}

class ManagementServerImpl implements ManagementServer {
    private static final Logger log = LoggerFactory.getLogger(ManagementServerImpl.class);
    private static final int MAX_CONTENT_LENGTH = 64 * 1024 * 1024;

    // 64MB for artifact uploads
    private final int port;
    private final Supplier<AetherNode> nodeSupplier;
    private final AlertManager alertManager;
    private final DashboardMetricsPublisher metricsPublisher;
    private final ObservabilityRegistry observability;
    private final Option<TlsConfig> tls;
    private final AtomicReference<HttpServer> serverRef = new AtomicReference<>();

    // Route handlers
    private final List<RouteHandler> routes;

    ManagementServerImpl(int port,
                         Supplier<AetherNode> nodeSupplier,
                         AlertManager alertManager,
                         Option<TlsConfig> tls) {
        this.port = port;
        this.nodeSupplier = nodeSupplier;
        this.alertManager = alertManager;
        this.metricsPublisher = new DashboardMetricsPublisher(nodeSupplier, alertManager);
        this.observability = ObservabilityRegistry.prometheus();
        this.tls = tls;
        // Initialize route handlers
        this.routes = List.of(StatusRoutes.statusRoutes(nodeSupplier),
                              SliceRoutes.sliceRoutes(nodeSupplier),
                              MetricsRoutes.metricsRoutes(nodeSupplier, observability),
                              AlertRoutes.alertRoutes(alertManager),
                              RollingUpdateRoutes.rollingUpdateRoutes(nodeSupplier),
                              RepositoryRoutes.repositoryRoutes(nodeSupplier),
                              ControllerRoutes.controllerRoutes(nodeSupplier),
                              DashboardRoutes.dashboardRoutes());
    }

    @Override
    public Promise<Unit> start() {
        var wsHandler = new DashboardWebSocketHandler(metricsPublisher);
        var wsEndpoint = WebSocketEndpoint.webSocketEndpoint("/ws/dashboard", wsHandler);
        var config = HttpServerConfig.httpServerConfig("management", port)
                                     .withMaxContentLength(MAX_CONTENT_LENGTH)
                                     .withWebSocket(wsEndpoint);
        // Add TLS if configured
        var finalConfig = tls.map(config::withTls)
                             .or(config);
        return HttpServer.httpServer(finalConfig, this::handleRequest)
                         .map(server -> {
                                  serverRef.set(server);
                                  metricsPublisher.start();
                                  var protocol = tls.isPresent()
                                                 ? "HTTPS"
                                                 : "HTTP";
                                  log.info("{} management server started on port {} (dashboard at /dashboard)",
                                           protocol,
                                           port);
                                  return unit();
                              })
                         .onFailure(cause -> log.error("Failed to start management server on port {}: {}",
                                                       port,
                                                       cause.message()));
    }

    @Override
    public Promise<Unit> stop() {
        metricsPublisher.stop();
        var server = serverRef.get();
        if (server != null) {
            return server.stop()
                         .onSuccess(_ -> log.info("Management server stopped"));
        }
        log.info("Management server stopped");
        return Promise.success(unit());
    }

    private void handleRequest(RequestContext ctx, ResponseWriter response) {
        var path = ctx.path();
        var method = ctx.method();
        log.debug("Received {} {}", method, path);
        // Try each route handler
        for (var handler : routes) {
            if (handler.handle(ctx, response)) {
                return;
            }
        }
        // No route matched
        response.notFound();
    }
}
