package org.pragmatica.aether.api;

import org.pragmatica.aether.api.routes.AlertRoutes;
import org.pragmatica.aether.api.routes.ControllerRoutes;
import org.pragmatica.aether.api.routes.DashboardRoutes;
import org.pragmatica.aether.api.routes.DynamicAspectRoutes;
import org.pragmatica.aether.api.routes.ManagementRouter;
import org.pragmatica.aether.api.routes.MavenProtocolRoutes;
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
                                             DynamicAspectRegistry aspectManager,
                                             Option<TlsConfig> tls) {
        return new ManagementServerImpl(port, nodeSupplier, alertManager, aspectManager, tls);
    }
}

class ManagementServerImpl implements ManagementServer {
    private static final Logger log = LoggerFactory.getLogger(ManagementServerImpl.class);
    private static final int MAX_CONTENT_LENGTH = 64 * 1024 * 1024;

    // 64MB for artifact uploads
    private final int port;
    private final Supplier<AetherNode> nodeSupplier;
    private final AlertManager alertManager;
    private final DynamicAspectRegistry aspectManager;
    private final DashboardMetricsPublisher metricsPublisher;
    private final StatusWebSocketHandler statusWsHandler;
    private final StatusWebSocketPublisher statusWsPublisher;
    private final ObservabilityRegistry observability;
    private final Option<TlsConfig> tls;
    private final AtomicReference<HttpServer> serverRef = new AtomicReference<>();

    // Route-based router (new pattern)
    private final ManagementRouter router;

    // Legacy route handlers (old pattern - to be migrated)
    private final List<RouteHandler> legacyRoutes;

    ManagementServerImpl(int port,
                         Supplier<AetherNode> nodeSupplier,
                         AlertManager alertManager,
                         DynamicAspectRegistry aspectManager,
                         Option<TlsConfig> tls) {
        this.port = port;
        this.nodeSupplier = nodeSupplier;
        this.alertManager = alertManager;
        this.aspectManager = aspectManager;
        this.metricsPublisher = new DashboardMetricsPublisher(nodeSupplier, alertManager, aspectManager);
        this.statusWsHandler = new StatusWebSocketHandler();
        this.statusWsPublisher = StatusWebSocketPublisher.statusWebSocketPublisher(
            statusWsHandler,
            () -> buildStatusJson(nodeSupplier));
        this.observability = ObservabilityRegistry.prometheus();
        this.tls = tls;
        // Route-based router for migrated routes
        this.router = ManagementRouter.managementRouter(StatusRoutes.statusRoutes(nodeSupplier),
                                                        AlertRoutes.alertRoutes(alertManager),
                                                        DynamicAspectRoutes.dynamicAspectRoutes(aspectManager),
                                                        ControllerRoutes.controllerRoutes(nodeSupplier),
                                                        SliceRoutes.sliceRoutes(nodeSupplier),
                                                        MetricsRoutes.metricsRoutes(nodeSupplier, observability),
                                                        RollingUpdateRoutes.rollingUpdateRoutes(nodeSupplier),
                                                        RepositoryRoutes.repositoryRoutes(nodeSupplier),
                                                        DashboardRoutes.dashboardRoutes());
        // Legacy routes using RouteHandler for dynamic content types
        this.legacyRoutes = List.of(MavenProtocolRoutes.mavenProtocolRoutes(nodeSupplier));
    }

    @Override
    public Promise<Unit> start() {
        var wsHandler = new DashboardWebSocketHandler(metricsPublisher);
        var wsEndpoint = WebSocketEndpoint.webSocketEndpoint("/ws/dashboard", wsHandler);
        var statusWsEndpoint = WebSocketEndpoint.webSocketEndpoint("/ws/status", statusWsHandler);
        var config = HttpServerConfig.httpServerConfig("management", port)
                                     .withMaxContentLength(MAX_CONTENT_LENGTH)
                                     .withWebSocket(wsEndpoint)
                                     .withWebSocket(statusWsEndpoint);
        // Add TLS if configured
        var finalConfig = tls.map(config::withTls)
                             .or(config);
        return HttpServer.httpServer(finalConfig, this::handleRequest)
                         .withSuccess(this::onServerStarted)
                         .mapToUnit()
                         .onFailure(cause -> log.error("Failed to start management server on port {}: {}",
                                                       port,
                                                       cause.message()));
    }

    @Override
    public Promise<Unit> stop() {
        metricsPublisher.stop();
        statusWsPublisher.stop();
        var server = serverRef.get();
        if (server != null) {
            return server.stop()
                         .onSuccess(_ -> log.info("Management server stopped"));
        }
        log.info("Management server stopped");
        return Promise.success(unit());
    }

    private void onServerStarted(HttpServer server) {
        serverRef.set(server);
        metricsPublisher.start();
        statusWsPublisher.start();
        var protocol = tls.isPresent()
                       ? "HTTPS"
                       : "HTTP";
        log.info("{} management server started on port {} (dashboard at /dashboard)", protocol, port);
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String buildStatusJson(Supplier<AetherNode> nodeSupplier) {
        var node = nodeSupplier.get();
        var leaderId = node.leader().map(leader -> leader.id()).or("");
        var sb = new StringBuilder(4096);
        sb.append("{");
        // Uptime
        sb.append("\"uptimeSeconds\":")
          .append(node.uptimeSeconds());
        // Node metrics
        sb.append(",\"nodeMetrics\":[");
        var allMetrics = node.metricsCollector().allMetrics();
        boolean firstNode = true;
        for (var entry : allMetrics.entrySet()) {
            if (!firstNode) sb.append(",");
            var nodeId = entry.getKey().id();
            var metrics = entry.getValue();
            var cpuUsage = metrics.getOrDefault("cpu.usage", 0.0);
            var heapUsed = metrics.getOrDefault("heap.used", 0.0);
            var heapMax = metrics.getOrDefault("heap.max", 1.0);
            sb.append("{\"nodeId\":\"").append(escapeJson(nodeId)).append("\"");
            sb.append(",\"isLeader\":").append(leaderId.equals(nodeId));
            sb.append(",\"cpuUsage\":").append(cpuUsage);
            sb.append(",\"heapUsedMb\":").append((long) (heapUsed / 1024 / 1024));
            sb.append(",\"heapMaxMb\":").append((long) (heapMax / 1024 / 1024));
            sb.append("}");
            firstNode = false;
        }
        sb.append("]");
        // Slices from DeploymentMap
        sb.append(",\"slices\":[");
        var deployments = node.deploymentMap().allDeployments();
        boolean firstSlice = true;
        for (var info : deployments) {
            if (!firstSlice) sb.append(",");
            sb.append("{\"artifact\":\"").append(escapeJson(info.artifact())).append("\"");
            sb.append(",\"state\":\"").append(info.aggregateState().name()).append("\"");
            sb.append(",\"instances\":[");
            boolean firstInst = true;
            for (var inst : info.instances()) {
                if (!firstInst) sb.append(",");
                sb.append("{\"nodeId\":\"").append(escapeJson(inst.nodeId())).append("\"");
                sb.append(",\"state\":\"").append(inst.state().name()).append("\"}");
                firstInst = false;
            }
            sb.append("]}");
            firstSlice = false;
        }
        sb.append("]");
        // Cluster info
        sb.append(",\"cluster\":{\"nodes\":[");
        boolean firstClusterNode = true;
        for (var entry : allMetrics.entrySet()) {
            if (!firstClusterNode) sb.append(",");
            var nodeId = entry.getKey().id();
            sb.append("{\"id\":\"").append(escapeJson(nodeId)).append("\"");
            sb.append(",\"isLeader\":").append(leaderId.equals(nodeId));
            sb.append("}");
            firstClusterNode = false;
        }
        sb.append("],\"leaderId\":\"");
        sb.append(escapeJson(leaderId));
        sb.append("\",\"nodeCount\":").append(allMetrics.size());
        sb.append("}");
        sb.append("}");
        return sb.toString();
    }

    private void handleRequest(RequestContext ctx, ResponseWriter response) {
        var path = ctx.path();
        var method = ctx.method();
        log.debug("Received {} {}", method, path);
        // Try route-based routing first
        if (router.handle(ctx, response)) {
            return;
        }
        // Fall back to legacy route handlers
        for (var handler : legacyRoutes) {
            if (handler.handle(ctx, response)) {
                return;
            }
        }
        // No route matched
        response.notFound();
    }
}
