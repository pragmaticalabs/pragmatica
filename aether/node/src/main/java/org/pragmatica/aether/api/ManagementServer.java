package org.pragmatica.aether.api;

import org.pragmatica.aether.api.routes.AlertRoutes;
import org.pragmatica.aether.api.routes.ConfigRoutes;
import org.pragmatica.aether.api.routes.ControllerRoutes;
import org.pragmatica.aether.dashboard.StaticFileHandler;
import org.pragmatica.aether.api.routes.LogLevelRoutes;
import org.pragmatica.aether.api.routes.ManagementRouter;
import org.pragmatica.aether.api.routes.MavenProtocolRoutes;
import org.pragmatica.aether.api.routes.MetricsRoutes;
import org.pragmatica.aether.api.routes.NodeLifecycleRoutes;
import org.pragmatica.aether.api.routes.ObservabilityRoutes;
import org.pragmatica.aether.api.routes.RepositoryRoutes;
import org.pragmatica.aether.api.routes.RollingUpdateRoutes;
import org.pragmatica.aether.api.routes.RouteHandler;
import org.pragmatica.aether.api.routes.ScheduledTaskRoutes;
import org.pragmatica.aether.api.routes.SliceRoutes;
import org.pragmatica.aether.api.routes.StatusRoutes;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.RouteSecurityPolicy;
import org.pragmatica.aether.http.security.AuditLog;
import org.pragmatica.aether.http.security.SecurityError;
import org.pragmatica.aether.http.security.SecurityValidator;
import org.pragmatica.aether.invoke.InvocationTraceStore;
import org.pragmatica.aether.invoke.ScheduledTaskManager;
import org.pragmatica.aether.invoke.ScheduledTaskRegistry;
import org.pragmatica.aether.deployment.DeploymentMap.SliceDeploymentInfo;
import org.pragmatica.aether.deployment.DeploymentMap.SliceInstanceInfo;
import org.pragmatica.aether.metrics.observability.ObservabilityRegistry;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.http.server.HttpServer;
import org.pragmatica.http.server.HttpServerConfig;
import org.pragmatica.http.server.RequestContext;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.http.websocket.WebSocketEndpoint;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.net.tcp.TlsConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;

/// HTTP management API server for cluster administration.
///
///
/// Exposes REST endpoints for:
///
///   - GET /api/status - Cluster status
///   - GET /api/nodes - List active nodes
///   - GET /api/slices - List deployed slices
///   - GET /api/metrics - Cluster metrics
///   - GET /api/artifact-metrics - Artifact storage and deployment metrics
///   - GET /repository/info/{groupPath}/{artifactId}/{version} - Artifact metadata
///
///
///
/// Uses pragmatica-lite's HttpServer infrastructure.
public interface ManagementServer {
    Promise<Unit> start();

    Promise<Unit> stop();

    static ManagementServer managementServer(int port,
                                             Supplier<AetherNode> nodeSupplier,
                                             AlertManager alertManager,
                                             ObservabilityDepthRegistry depthRegistry,
                                             InvocationTraceStore traceStore,
                                             LogLevelRegistry logLevelRegistry,
                                             Option<DynamicConfigManager> dynamicConfigManager,
                                             ScheduledTaskRegistry scheduledTaskRegistry,
                                             ScheduledTaskManager scheduledTaskManager,
                                             Option<TlsConfig> tls,
                                             SecurityValidator securityValidator,
                                             boolean securityEnabled) {
        return new ManagementServerImpl(port,
                                        nodeSupplier,
                                        alertManager,
                                        depthRegistry,
                                        traceStore,
                                        logLevelRegistry,
                                        dynamicConfigManager,
                                        scheduledTaskRegistry,
                                        scheduledTaskManager,
                                        tls,
                                        securityValidator,
                                        securityEnabled);
    }
}

class ManagementServerImpl implements ManagementServer {
    private static final Logger log = LoggerFactory.getLogger(ManagementServerImpl.class);
    private static final int MAX_CONTENT_LENGTH = 64 * 1024 * 1024;

    // 64MB for artifact uploads
    private final int port;
    private final Supplier<AetherNode> nodeSupplier;
    private final AlertManager alertManager;
    private final ObservabilityDepthRegistry depthRegistry;
    private final InvocationTraceStore traceStore;
    private final LogLevelRegistry logLevelRegistry;
    private final DashboardMetricsPublisher metricsPublisher;
    private final StatusWebSocketHandler statusWsHandler;
    private final StatusWebSocketPublisher statusWsPublisher;
    private final EventWebSocketHandler eventWsHandler;
    private final EventWebSocketPublisher eventWsPublisher;
    private final ObservabilityRegistry observability;
    private final Option<TlsConfig> tls;
    private final SecurityValidator securityValidator;
    private final boolean securityEnabled;
    private final WebSocketAuthenticator wsAuthenticator;
    private final AtomicReference<HttpServer> serverRef = new AtomicReference<>();

    private final StaticFileHandler staticFileHandler;

    // Route-based router (new pattern)
    private final ManagementRouter router;

    // Status routes for probe endpoints (need direct access for status code control)
    private final StatusRoutes statusRoutes;
    private final JsonMapper probeJsonMapper;

    // Legacy route handlers (old pattern - to be migrated)
    private final List<RouteHandler> legacyRoutes;

    ManagementServerImpl(int port,
                         Supplier<AetherNode> nodeSupplier,
                         AlertManager alertManager,
                         ObservabilityDepthRegistry depthRegistry,
                         InvocationTraceStore traceStore,
                         LogLevelRegistry logLevelRegistry,
                         Option<DynamicConfigManager> dynamicConfigManager,
                         ScheduledTaskRegistry scheduledTaskRegistry,
                         ScheduledTaskManager scheduledTaskManager,
                         Option<TlsConfig> tls,
                         SecurityValidator securityValidator,
                         boolean securityEnabled) {
        this.port = port;
        this.nodeSupplier = nodeSupplier;
        this.alertManager = alertManager;
        this.depthRegistry = depthRegistry;
        this.traceStore = traceStore;
        this.logLevelRegistry = logLevelRegistry;
        this.securityValidator = securityValidator;
        this.securityEnabled = securityEnabled;
        this.wsAuthenticator = WebSocketAuthenticator.webSocketAuthenticator(securityValidator, securityEnabled);
        this.metricsPublisher = new DashboardMetricsPublisher(nodeSupplier, alertManager);
        this.statusWsHandler = new StatusWebSocketHandler(wsAuthenticator);
        this.statusWsPublisher = StatusWebSocketPublisher.statusWebSocketPublisher(statusWsHandler,
                                                                                   () -> buildStatusJson(nodeSupplier));
        this.eventWsHandler = new EventWebSocketHandler(wsAuthenticator);
        this.eventWsPublisher = EventWebSocketPublisher.eventWebSocketPublisher(eventWsHandler,
                                                                                since -> nodeSupplier.get()
                                                                                                     .eventAggregator()
                                                                                                     .eventsSince(since),
                                                                                ManagementServerImpl::buildEventsJson);
        this.staticFileHandler = StaticFileHandler.staticFileHandler();
        this.observability = ObservabilityRegistry.prometheus();
        this.tls = tls;
        this.probeJsonMapper = JsonMapper.defaultJsonMapper();
        // Route-based router for migrated routes — build route sources dynamically
        var routeSources = new ArrayList<RouteSource>();
        this.statusRoutes = StatusRoutes.statusRoutes(nodeSupplier,
                                                      () -> nodeSupplier.get()
                                                                        .appHttpServer());
        routeSources.add(statusRoutes);
        routeSources.add(AlertRoutes.alertRoutes(alertManager));
        routeSources.add(LogLevelRoutes.logLevelRoutes(logLevelRegistry));
        routeSources.add(ObservabilityRoutes.observabilityRoutes(depthRegistry, traceStore));
        routeSources.add(ControllerRoutes.controllerRoutes(nodeSupplier));
        routeSources.add(SliceRoutes.sliceRoutes(nodeSupplier));
        routeSources.add(MetricsRoutes.metricsRoutes(nodeSupplier, observability));
        routeSources.add(RollingUpdateRoutes.rollingUpdateRoutes(nodeSupplier));
        routeSources.add(NodeLifecycleRoutes.nodeLifecycleRoutes(nodeSupplier));
        routeSources.add(RepositoryRoutes.repositoryRoutes(nodeSupplier));
        routeSources.add(ScheduledTaskRoutes.scheduledTaskRoutes(scheduledTaskRegistry, scheduledTaskManager));
        dynamicConfigManager.onPresent(dcm -> routeSources.add(ConfigRoutes.configRoutes(dcm)));
        this.router = ManagementRouter.managementRouter(routeSources.toArray(RouteSource[]::new));
        // Legacy routes using RouteHandler for dynamic content types
        this.legacyRoutes = List.of(MavenProtocolRoutes.mavenProtocolRoutes(nodeSupplier));
    }

    @Override
    public Promise<Unit> start() {
        var wsHandler = new DashboardWebSocketHandler(metricsPublisher, wsAuthenticator);
        var wsEndpoint = WebSocketEndpoint.webSocketEndpoint("/ws/dashboard", wsHandler);
        var statusWsEndpoint = WebSocketEndpoint.webSocketEndpoint("/ws/status", statusWsHandler);
        var eventWsEndpoint = WebSocketEndpoint.webSocketEndpoint("/ws/events", eventWsHandler);
        var config = HttpServerConfig.httpServerConfig("management", port)
                                     .withMaxContentLength(MAX_CONTENT_LENGTH)
                                     .withWebSocket(wsEndpoint)
                                     .withWebSocket(statusWsEndpoint)
                                     .withWebSocket(eventWsEndpoint);
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
        eventWsPublisher.stop();
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
        eventWsPublisher.start();
        var protocol = tls.isPresent()
                       ? "HTTPS"
                       : "HTTP";
        log.info("{} management server started on port {} (dashboard at /)", protocol, port);
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"");
    }

    @SuppressWarnings("JBCT-PAT-01") // Sequencer: gathers node data, delegates to focused helpers
    private static String buildStatusJson(Supplier<AetherNode> nodeSupplier) {
        var node = nodeSupplier.get();
        var leaderId = node.leader()
                           .map(leader -> leader.id())
                           .or("");
        var allMetrics = node.metricsCollector()
                             .allMetrics();
        var deployments = node.deploymentMap()
                              .allDeployments();
        var sb = new StringBuilder(4096);
        sb.append("{\"uptimeSeconds\":")
          .append(node.uptimeSeconds());
        appendNodeMetrics(sb, allMetrics, leaderId);
        appendSlices(sb, deployments);
        appendClusterInfo(sb, allMetrics, leaderId);
        sb.append("}");
        return sb.toString();
    }

    /// Iteration: appends the `nodeMetrics` JSON array from collected metrics.
    @SuppressWarnings("JBCT-PAT-01")
    private static void appendNodeMetrics(StringBuilder sb,
                                          Map<NodeId, Map<String, Double>> allMetrics,
                                          String leaderId) {
        sb.append(",\"nodeMetrics\":[");
        boolean first = true;
        for (var entry : allMetrics.entrySet()) {
            if (!first) sb.append(",");
            appendSingleNodeMetric(sb,
                                   entry.getKey()
                                        .id(),
                                   entry.getValue(),
                                   leaderId);
            first = false;
        }
        sb.append("]");
    }

    /// Leaf: appends a single node metric JSON object.
    private static void appendSingleNodeMetric(StringBuilder sb,
                                               String nodeId,
                                               Map<String, Double> metrics,
                                               String leaderId) {
        var cpuUsage = metrics.getOrDefault("cpu.usage", 0.0);
        var heapUsed = metrics.getOrDefault("heap.used", 0.0);
        var heapMax = metrics.getOrDefault("heap.max", 1.0);
        sb.append("{\"nodeId\":\"")
          .append(escapeJson(nodeId))
          .append("\"");
        sb.append(",\"isLeader\":")
          .append(leaderId.equals(nodeId));
        sb.append(",\"cpuUsage\":")
          .append(cpuUsage);
        sb.append(",\"heapUsedMb\":")
          .append((long)(heapUsed / 1024 / 1024));
        sb.append(",\"heapMaxMb\":")
          .append((long)(heapMax / 1024 / 1024));
        sb.append("}");
    }

    /// Iteration: appends the `slices` JSON array from deployment info.
    @SuppressWarnings("JBCT-PAT-01")
    private static void appendSlices(StringBuilder sb, List<SliceDeploymentInfo> deployments) {
        sb.append(",\"slices\":[");
        boolean first = true;
        for (var info : deployments) {
            if (!first) sb.append(",");
            appendSingleSlice(sb, info);
            first = false;
        }
        sb.append("]");
    }

    /// Sequencer: appends a single slice JSON object with its nested instances array.
    @SuppressWarnings("JBCT-PAT-01")
    private static void appendSingleSlice(StringBuilder sb, SliceDeploymentInfo info) {
        sb.append("{\"artifact\":\"")
          .append(escapeJson(info.artifact()))
          .append("\"");
        sb.append(",\"state\":\"")
          .append(info.aggregateState()
                      .name())
          .append("\"");
        appendSliceInstances(sb, info.instances());
        sb.append("}");
    }

    /// Iteration: appends the `instances` JSON array for a slice.
    @SuppressWarnings("JBCT-PAT-01")
    private static void appendSliceInstances(StringBuilder sb, List<SliceInstanceInfo> instances) {
        sb.append(",\"instances\":[");
        boolean first = true;
        for (var inst : instances) {
            if (!first) sb.append(",");
            sb.append("{\"nodeId\":\"")
              .append(escapeJson(inst.nodeId()))
              .append("\"");
            sb.append(",\"state\":\"")
              .append(inst.state()
                          .name())
              .append("\"}");
            first = false;
        }
        sb.append("]");
    }

    /// Sequencer: appends the `cluster` JSON object with nodes array and leader info.
    @SuppressWarnings("JBCT-PAT-01")
    private static void appendClusterInfo(StringBuilder sb,
                                          Map<NodeId, Map<String, Double>> allMetrics,
                                          String leaderId) {
        sb.append(",\"cluster\":{\"nodes\":[");
        boolean first = true;
        for (var entry : allMetrics.entrySet()) {
            if (!first) sb.append(",");
            var nodeId = entry.getKey()
                              .id();
            sb.append("{\"id\":\"")
              .append(escapeJson(nodeId))
              .append("\"");
            sb.append(",\"isLeader\":")
              .append(leaderId.equals(nodeId));
            sb.append("}");
            first = false;
        }
        sb.append("],\"leaderId\":\"");
        sb.append(escapeJson(leaderId));
        sb.append("\",\"nodeCount\":")
          .append(allMetrics.size());
        sb.append("}");
    }

    @SuppressWarnings("JBCT-PAT-01")
    static String buildEventsJson(List<ClusterEvent> events) {
        var sb = new StringBuilder(256);
        sb.append("[");
        var first = true;
        for (var event : events) {
            if (!first) sb.append(",");
            appendEventJson(sb, event);
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static void appendEventJson(StringBuilder sb, ClusterEvent event) {
        sb.append("{\"timestamp\":\"")
          .append(event.timestamp())
          .append("\"");
        sb.append(",\"type\":\"")
          .append(event.type()
                       .name())
          .append("\"");
        sb.append(",\"severity\":\"")
          .append(event.severity()
                       .name())
          .append("\"");
        sb.append(",\"summary\":\"")
          .append(escapeJson(event.summary()))
          .append("\"");
        sb.append(",\"details\":{");
        var firstDetail = true;
        for (var entry : event.details()
                              .entrySet()) {
            if (!firstDetail) sb.append(",");
            sb.append("\"")
              .append(escapeJson(entry.getKey()))
              .append("\":\"")
              .append(escapeJson(entry.getValue()))
              .append("\"");
            firstDetail = false;
        }
        sb.append("}}");
    }

    @SuppressWarnings("JBCT-PAT-01") // HTTP dispatcher: inherently mixes condition checks and iteration over legacy routes
    private void handleRequest(RequestContext ctx, ResponseWriter response) {
        var path = ctx.path();
        var method = ctx.method();
        log.debug("Received {} {}", method, path);
        // Probe endpoints — handled before router for HTTP status code control
        if (handleProbeRequest(path, response)) {
            return;
        }
        // Security check — probes already bypassed
        if (securityEnabled && !validateManagementSecurity(ctx, response, path, method)) {
            return;
        }
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
        // No route matched — fall back to static dashboard files
        staticFileHandler.handle(ctx, response);
    }

    @SuppressWarnings("JBCT-PAT-01")
    private boolean handleProbeRequest(String path, ResponseWriter response) {
        if ("/health/live".equals(path)) {
            writeProbeJson(response, statusRoutes.buildLivenessResponse(), HttpStatus.OK);
            return true;
        }
        if ("/health/ready".equals(path)) {
            var readiness = statusRoutes.buildReadinessResponse();
            var httpStatus = "UP".equals(readiness.status())
                             ? HttpStatus.OK
                             : HttpStatus.SERVICE_UNAVAILABLE;
            writeProbeJson(response, readiness, httpStatus);
            return true;
        }
        return false;
    }

    private void writeProbeJson(ResponseWriter response, Object value, HttpStatus httpStatus) {
        probeJsonMapper.writeAsString(value)
                       .onSuccess(json -> response.respond(httpStatus, json))
                       .onFailure(cause -> response.error(HttpStatus.INTERNAL_SERVER_ERROR,
                                                          cause.message()));
    }

    @SuppressWarnings("JBCT-PAT-01")
    private boolean validateManagementSecurity(RequestContext ctx,
                                               ResponseWriter response,
                                               String path,
                                               HttpMethod method) {
        var httpContext = toManagementRequestContext(ctx, path);
        var policy = RouteSecurityPolicy.apiKeyRequired();
        var methodName = method.name();
        return securityValidator.validate(httpContext, policy)
                                .onFailure(cause -> handleManagementSecurityFailure(response, cause, path, methodName))
                                .onSuccess(sc -> logManagementAccess(sc, methodName, path))
                                .isSuccess();
    }

    private static void logManagementAccess(org.pragmatica.aether.http.handler.security.SecurityContext securityContext,
                                            String method,
                                            String path) {
        var principal = securityContext.isAuthenticated()
                        ? securityContext.principal()
                                         .value()
                        : "anonymous";
        AuditLog.managementAccess("mgmt", principal, method, path);
    }

    private static HttpRequestContext toManagementRequestContext(RequestContext ctx, String path) {
        return HttpRequestContext.httpRequestContext(path,
                                                     ctx.method()
                                                        .name(),
                                                     ctx.queryParams()
                                                        .asMap(),
                                                     ctx.headers()
                                                        .asMap(),
                                                     ctx.body(),
                                                     "mgmt");
    }

    @SuppressWarnings("JBCT-PAT-01")
    private void handleManagementSecurityFailure(ResponseWriter response, Cause cause, String path, String method) {
        AuditLog.authFailure("mgmt", cause.message(), method, path);
        var status = switch (cause) {
            case SecurityError.MissingCredentials _ -> HttpStatus.UNAUTHORIZED;
            case SecurityError.InvalidCredentials _ -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.UNAUTHORIZED;
        };
        if (status == HttpStatus.UNAUTHORIZED) {
            response.header("WWW-Authenticate", "ApiKey realm=\"Aether\"")
                    .error(status,
                           cause.message());
        } else {
            response.error(status, cause.message());
        }
    }
}
