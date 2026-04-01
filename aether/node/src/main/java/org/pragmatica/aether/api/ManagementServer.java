package org.pragmatica.aether.api;

import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.api.routes.AlertRoutes;
import org.pragmatica.aether.api.routes.BackupRoutes;
import org.pragmatica.aether.api.routes.ClusterConfigRoutes;
import org.pragmatica.aether.api.routes.ClusterTopologyRoutes;
import org.pragmatica.aether.api.routes.ConfigRoutes;
import org.pragmatica.aether.api.routes.ControllerRoutes;
import org.pragmatica.aether.api.routes.DeployRoutes;
import org.pragmatica.aether.dashboard.StaticFileHandler;
import org.pragmatica.aether.api.routes.LogLevelRoutes;
import org.pragmatica.aether.api.routes.ManagementRouter;
import org.pragmatica.aether.api.routes.MavenProtocolRoutes;
import org.pragmatica.aether.api.routes.MetricsRoutes;
import org.pragmatica.aether.api.routes.NodeLifecycleRoutes;
import org.pragmatica.aether.api.routes.ObservabilityRoutes;
import org.pragmatica.aether.api.routes.RepositoryRoutes;
import org.pragmatica.aether.api.routes.AbTestRoutes;
import org.pragmatica.aether.api.routes.RouteHandler;
import org.pragmatica.aether.api.routes.ScheduledTaskRoutes;
import org.pragmatica.aether.api.routes.SchemaRoutes;
import org.pragmatica.aether.api.routes.SliceRoutes;
import org.pragmatica.aether.api.routes.StatusRoutes;
import org.pragmatica.aether.api.routes.StorageRoutes;
import org.pragmatica.aether.api.routes.StreamRoutes;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.RoleEnforcer;
import org.pragmatica.aether.http.handler.security.RoutePermission;
import org.pragmatica.aether.http.handler.security.RoutePermissionRegistry;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.http.security.AuditLog;
import org.pragmatica.aether.http.security.SecurityError;
import org.pragmatica.aether.api.OperationalEvent;
import org.pragmatica.aether.http.security.SecurityValidator;
import org.pragmatica.aether.invoke.InvocationTraceStore;
import org.pragmatica.aether.invoke.ScheduledTaskManager;
import org.pragmatica.aether.invoke.ScheduledTaskRegistry;
import org.pragmatica.aether.invoke.ScheduledTaskStateRegistry;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.deployment.DeploymentMap.SliceDeploymentInfo;
import org.pragmatica.aether.deployment.DeploymentMap.SliceInstanceInfo;
import org.pragmatica.aether.metrics.observability.HttpRequestObserver;
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
import org.pragmatica.net.tcp.QuicSslContextFactory;
import org.pragmatica.http.websocket.WebSocketEndpoint;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.net.tcp.TlsConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import io.netty.channel.EventLoopGroup;
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

    /// Rotate TLS certificate by restarting HTTP servers with the new bundle.
    Promise<Unit> rotateCertificate(org.pragmatica.net.tcp.security.CertificateBundle newBundle);

    static ManagementServer managementServer(int port,
                                             Supplier<AetherNode> nodeSupplier,
                                             AlertManager alertManager,
                                             ObservabilityDepthRegistry depthRegistry,
                                             InvocationTraceStore traceStore,
                                             LogLevelRegistry logLevelRegistry,
                                             Option<DynamicConfigManager> dynamicConfigManager,
                                             ScheduledTaskRegistry scheduledTaskRegistry,
                                             ScheduledTaskManager scheduledTaskManager,
                                             SliceInvoker sliceInvoker,
                                             ScheduledTaskStateRegistry scheduledTaskStateRegistry,
                                             Option<TlsConfig> tls,
                                             SecurityValidator securityValidator,
                                             boolean securityEnabled,
                                             Option<EventLoopGroup> bossGroup,
                                             Option<EventLoopGroup> workerGroup,
                                             HttpProtocol httpProtocol) {
        return new ManagementServerImpl(port,
                                        nodeSupplier,
                                        alertManager,
                                        depthRegistry,
                                        traceStore,
                                        logLevelRegistry,
                                        dynamicConfigManager,
                                        scheduledTaskRegistry,
                                        scheduledTaskManager,
                                        sliceInvoker,
                                        scheduledTaskStateRegistry,
                                        tls,
                                        securityValidator,
                                        securityEnabled,
                                        bossGroup,
                                        workerGroup,
                                        httpProtocol);
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
    private final HttpRequestObserver requestObserver;
    private final Option<TlsConfig> tls;
    private final SecurityValidator securityValidator;
    private final boolean securityEnabled;
    private final Option<EventLoopGroup> bossGroup;
    private final Option<EventLoopGroup> workerGroup;
    private final WebSocketAuthenticator wsAuthenticator;
    private final HttpProtocol httpProtocol;
    private final AtomicReference<HttpServer> serverRef = new AtomicReference<>();
    private final AtomicReference<HttpServer> h3ServerRef = new AtomicReference<>();

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
                         SliceInvoker sliceInvoker,
                         ScheduledTaskStateRegistry scheduledTaskStateRegistry,
                         Option<TlsConfig> tls,
                         SecurityValidator securityValidator,
                         boolean securityEnabled,
                         Option<EventLoopGroup> bossGroup,
                         Option<EventLoopGroup> workerGroup,
                         HttpProtocol httpProtocol) {
        this.port = port;
        this.nodeSupplier = nodeSupplier;
        this.alertManager = alertManager;
        this.depthRegistry = depthRegistry;
        this.traceStore = traceStore;
        this.logLevelRegistry = logLevelRegistry;
        this.securityValidator = securityValidator;
        this.securityEnabled = securityEnabled;
        this.bossGroup = bossGroup;
        this.workerGroup = workerGroup;
        this.httpProtocol = httpProtocol;
        this.wsAuthenticator = WebSocketAuthenticator.webSocketAuthenticator(securityValidator, securityEnabled);
        this.metricsPublisher = DashboardMetricsPublisher.dashboardMetricsPublisher(nodeSupplier, alertManager);
        this.statusWsHandler = new StatusWebSocketHandler(wsAuthenticator);
        this.statusWsPublisher = StatusWebSocketPublisher.statusWebSocketPublisher(statusWsHandler,
                                                                                   () -> buildStatusJson(nodeSupplier));
        this.eventWsHandler = new EventWebSocketHandler(wsAuthenticator);
        this.eventWsPublisher = EventWebSocketPublisher.eventWebSocketPublisher(eventWsHandler,
                                                                                since -> nodeSupplier.get().eventAggregator()
                                                                                                         .eventsSince(since),
                                                                                ManagementServerImpl::buildEventsJson);
        this.staticFileHandler = StaticFileHandler.staticFileHandler();
        this.observability = ObservabilityRegistry.prometheus();
        this.requestObserver = HttpRequestObserver.httpRequestObserver(observability);
        this.tls = tls;
        this.probeJsonMapper = JsonMapper.defaultJsonMapper();
        // Route-based router for migrated routes — build route sources dynamically
        var routeSources = new ArrayList<RouteSource>();
        this.statusRoutes = StatusRoutes.statusRoutes(nodeSupplier,
                                                      () -> nodeSupplier.get().appHttpServer());
        routeSources.add(statusRoutes);
        routeSources.add(AlertRoutes.alertRoutes(alertManager));
        routeSources.add(LogLevelRoutes.logLevelRoutes(logLevelRegistry));
        routeSources.add(ObservabilityRoutes.observabilityRoutes(depthRegistry, traceStore));
        routeSources.add(ControllerRoutes.controllerRoutes(nodeSupplier));
        routeSources.add(SliceRoutes.sliceRoutes(nodeSupplier));
        routeSources.add(MetricsRoutes.metricsRoutes(nodeSupplier, observability));
        routeSources.add(DeployRoutes.deployRoutes(nodeSupplier));
        routeSources.add(AbTestRoutes.abTestRoutes(nodeSupplier));
        routeSources.add(NodeLifecycleRoutes.nodeLifecycleRoutes(nodeSupplier));
        routeSources.add(RepositoryRoutes.repositoryRoutes(nodeSupplier));
        routeSources.add(ScheduledTaskRoutes.scheduledTaskRoutes(scheduledTaskRegistry,
                                                                 scheduledTaskManager,
                                                                 nodeSupplier,
                                                                 sliceInvoker,
                                                                 scheduledTaskStateRegistry));
        routeSources.add(ClusterTopologyRoutes.clusterTopologyRoutes(nodeSupplier));
        routeSources.add(ClusterConfigRoutes.clusterConfigRoutes(nodeSupplier));
        routeSources.add(BackupRoutes.backupRoutes(() -> nodeSupplier.get().backupService(),
                                                   nodeSupplier));
        routeSources.add(SchemaRoutes.schemaRoutes(nodeSupplier));
        routeSources.add(StreamRoutes.streamRoutes(nodeSupplier));
        routeSources.add(StorageRoutes.storageRoutes(nodeSupplier));
        dynamicConfigManager.onPresent(dcm -> routeSources.add(ConfigRoutes.configRoutes(dcm, nodeSupplier)));
        this.router = ManagementRouter.managementRouter(routeSources.toArray(RouteSource[]::new));
        // Legacy routes using RouteHandler for dynamic content types
        this.legacyRoutes = List.of(MavenProtocolRoutes.mavenProtocolRoutes(nodeSupplier));
    }

    @Override public Promise<Unit> start() {
        log.info("Starting management server on port {} (protocol: {})", port, httpProtocol);
        if ( httpProtocol.includesH1()) {
        return startH1Server().flatMap(_ -> httpProtocol.includesH3()
                                           ? startH3Server()
                                           : Promise.success(unit()));}
        return startH3Server();
    }

    private Promise<Unit> startH1Server() {
        var serverConfig = buildServerConfig();
        java.util.function.BiConsumer<RequestContext, ResponseWriter> handler = httpProtocol == HttpProtocol.BOTH
                                                                                ? this::handleRequestWithAltSvc
                                                                                : this::handleRequest;
        var serverPromise = bossGroup.flatMap(bg -> workerGroup.map(wg -> HttpServer.httpServer(serverConfig,
                                                                                                handler,
                                                                                                bg,
                                                                                                wg)))
        .or(HttpServer.httpServer(serverConfig, handler));
        return serverPromise.map(this::registerStartedH1Server)
        .onFailure(cause -> log.error("Failed to start management server on port {}: {}", port, cause.message()));
    }

    private Promise<Unit> startH3Server() {
        var quicTls = tls.map(QuicSslContextFactory::createServer).or(QuicSslContextFactory.createSelfSignedServer());
        return quicTls.onFailure(cause -> log.error("Failed to create QUIC SSL context for management server: {}",
                                                    cause.message())).map(this::startH3WithSslContext)
                                .or(Promise.success(unit()));
    }

    private Promise<Unit> startH3WithSslContext(io.netty.handler.codec.quic.QuicSslContext quicSslContext) {
        var serverConfig = HttpServerConfig.httpServerConfig("management-h3", port)
        .withMaxContentLength(MAX_CONTENT_LENGTH);
        var serverPromise = workerGroup.map(wg -> HttpServer.http3Server(serverConfig,
                                                                         quicSslContext,
                                                                         this::handleRequest,
                                                                         wg))
        .or(HttpServer.http3Server(serverConfig, quicSslContext, this::handleRequest));
        return serverPromise.map(this::registerStartedH3Server)
        .onFailure(cause -> log.error("Failed to start management HTTP/3 server on port {}: {}", port, cause.message()));
    }

    private HttpServerConfig buildServerConfig() {
        var wsHandler = new DashboardWebSocketHandler(metricsPublisher, wsAuthenticator);
        var wsEndpoint = WebSocketEndpoint.webSocketEndpoint("/ws/dashboard", wsHandler);
        var statusWsEndpoint = WebSocketEndpoint.webSocketEndpoint("/ws/status", statusWsHandler);
        var eventWsEndpoint = WebSocketEndpoint.webSocketEndpoint("/ws/events", eventWsHandler);
        var config = HttpServerConfig.httpServerConfig("management", port).withMaxContentLength(MAX_CONTENT_LENGTH)
                                                      .withWebSocket(wsEndpoint)
                                                      .withWebSocket(statusWsEndpoint)
                                                      .withWebSocket(eventWsEndpoint);
        return tls.map(config::withTls).or(config);
    }

    private Unit registerStartedH1Server(HttpServer server) {
        serverRef.set(server);
        onServerStarted(server);
        return unit();
    }

    private Unit registerStartedH3Server(HttpServer server) {
        h3ServerRef.set(server);
        log.info("Management HTTP/3 QUIC server started on port {}", server.port());
        return unit();
    }

    private void handleRequestWithAltSvc(RequestContext request, ResponseWriter response) {
        response.header("Alt-Svc", "h3=\":" + port + "\"; ma=3600");
        handleRequest(request, response);
    }

    @Override public Promise<Unit> stop() {
        metricsPublisher.stop();
        statusWsPublisher.stop();
        eventWsPublisher.stop();
        var h1Stop = Option.option(serverRef.get()).map(server -> server.stop()
        .onSuccessRun(() -> log.info("Management HTTP/1.1 server stopped")))
                                  .or(Promise.success(unit()));
        var h3Stop = Option.option(h3ServerRef.get()).map(server -> server.stop()
        .onSuccessRun(() -> log.info("Management HTTP/3 server stopped")))
                                  .or(Promise.success(unit()));
        return h1Stop.flatMap(_ -> h3Stop);
    }

    @Override public Promise<Unit> rotateCertificate(org.pragmatica.net.tcp.security.CertificateBundle newBundle) {
        log.info("Rotating management server TLS certificate");
        return stopHttpServers().flatMap(_ -> restartWithNewBundle(newBundle));
    }

    private Promise<Unit> stopHttpServers() {
        var h1Stop = Option.option(serverRef.getAndSet(null)).map(HttpServer::stop)
                                  .or(Promise.success(unit()));
        var h3Stop = Option.option(h3ServerRef.getAndSet(null)).map(HttpServer::stop)
                                  .or(Promise.success(unit()));
        return h1Stop.flatMap(_ -> h3Stop);
    }

    @SuppressWarnings("JBCT-PAT-01") // Lifecycle: rebuild TLS config from bundle and restart servers
    private Promise<Unit> restartWithNewBundle(org.pragmatica.net.tcp.security.CertificateBundle newBundle) {
        var newTlsConfig = buildTlsFromBundle(newBundle);
        var protocol = httpProtocol;
        if ( protocol.includesH1()) {
        return restartH1WithTls(newTlsConfig).flatMap(_ -> protocol.includesH3()
                                                          ? restartH3WithBundle(newBundle)
                                                          : Promise.success(unit()));}
        return restartH3WithBundle(newBundle);
    }

    private Promise<Unit> restartH1WithTls(Option<TlsConfig> newTls) {
        var wsHandler = new DashboardWebSocketHandler(metricsPublisher, wsAuthenticator);
        var wsEndpoint = WebSocketEndpoint.webSocketEndpoint("/ws/dashboard", wsHandler);
        var statusWsEndpoint = WebSocketEndpoint.webSocketEndpoint("/ws/status", statusWsHandler);
        var eventWsEndpoint = WebSocketEndpoint.webSocketEndpoint("/ws/events", eventWsHandler);
        var config = HttpServerConfig.httpServerConfig("management", port).withMaxContentLength(MAX_CONTENT_LENGTH)
                                                      .withWebSocket(wsEndpoint)
                                                      .withWebSocket(statusWsEndpoint)
                                                      .withWebSocket(eventWsEndpoint);
        var serverConfig = newTls.map(config::withTls).or(config);
        java.util.function.BiConsumer<RequestContext, ResponseWriter> handler = httpProtocol == HttpProtocol.BOTH
                                                                                ? this::handleRequestWithAltSvc
                                                                                : this::handleRequest;
        var serverPromise = bossGroup.flatMap(bg -> workerGroup.map(wg -> HttpServer.httpServer(serverConfig,
                                                                                                handler,
                                                                                                bg,
                                                                                                wg)))
        .or(HttpServer.httpServer(serverConfig, handler));
        return serverPromise.map(this::registerStartedH1Server).onSuccess(_ -> log.info("Management HTTP/1.1 server restarted with new certificate"))
                                .onFailure(cause -> log.error("Failed to restart management HTTP/1.1 server: {}",
                                                              cause.message()));
    }

    private Promise<Unit> restartH3WithBundle(org.pragmatica.net.tcp.security.CertificateBundle newBundle) {
        var quicTls = QuicSslContextFactory.createServerFromBundle(newBundle);
        return quicTls.map(this::startH3WithSslContext).onFailure(cause -> log.error("Failed to create QUIC SSL context for management server rotation: {}",
                                                                                     cause.message()))
                          .or(Promise.success(unit()));
    }

    private static Option<TlsConfig> buildTlsFromBundle(org.pragmatica.net.tcp.security.CertificateBundle bundle) {
        var identity = new TlsConfig.Identity.FromProvider(bundle.certificatePem(), bundle.privateKeyPem());
        var trust = new TlsConfig.Trust.FromCaBytes(bundle.caCertificatePem());
        return Option.some(new TlsConfig.Server(identity, Option.some(trust)));
    }

    private void onServerStarted(HttpServer server) {
        metricsPublisher.start();
        statusWsPublisher.start();
        eventWsPublisher.start();
        observability.registerTransportMetrics(() -> nodeSupplier.get().transportMetrics());
        var transport = tls.isPresent()
                        ? "HTTPS"
                        : "HTTP";
        log.info("{} management server started on port {} (protocol: {}, dashboard at /)", transport, port, httpProtocol);
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @SuppressWarnings("JBCT-PAT-01") // Sequencer: gathers node data, delegates to focused helpers
    private static String buildStatusJson(Supplier<AetherNode> nodeSupplier) {
        var node = nodeSupplier.get();
        var leaderId = node.leader().map(leader -> leader.id())
                                  .or("");
        var allMetrics = node.metricsCollector().allMetrics();
        var deployments = node.deploymentMap().allDeployments();
        var sb = new StringBuilder(4096);
        sb.append("{\"uptimeSeconds\":").append(node.uptimeSeconds());
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
        for ( var entry : allMetrics.entrySet()) {
            if ( !first) sb.append(",");
            appendSingleNodeMetric(sb,
                                   entry.getKey().id(),
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
        sb.append("{\"nodeId\":\"").append(escapeJson(nodeId))
                 .append("\"");
        sb.append(",\"isLeader\":").append(leaderId.equals(nodeId));
        sb.append(",\"cpuUsage\":").append(cpuUsage);
        sb.append(",\"heapUsedMb\":").append((long)(heapUsed / 1024 / 1024));
        sb.append(",\"heapMaxMb\":").append((long)(heapMax / 1024 / 1024));
        sb.append("}");
    }

    /// Iteration: appends the `slices` JSON array from deployment info.
    @SuppressWarnings("JBCT-PAT-01")
    private static void appendSlices(StringBuilder sb, List<SliceDeploymentInfo> deployments) {
        sb.append(",\"slices\":[");
        boolean first = true;
        for ( var info : deployments) {
            if ( !first) sb.append(",");
            appendSingleSlice(sb, info);
            first = false;
        }
        sb.append("]");
    }

    /// Sequencer: appends a single slice JSON object with its nested instances array.
    @SuppressWarnings("JBCT-PAT-01")
    private static void appendSingleSlice(StringBuilder sb, SliceDeploymentInfo info) {
        sb.append("{\"artifact\":\"").append(escapeJson(info.artifact()))
                 .append("\"");
        sb.append(",\"state\":\"").append(info.aggregateState().name())
                 .append("\"");
        appendSliceInstances(sb, info.instances());
        sb.append("}");
    }

    /// Iteration: appends the `instances` JSON array for a slice.
    @SuppressWarnings("JBCT-PAT-01")
    private static void appendSliceInstances(StringBuilder sb, List<SliceInstanceInfo> instances) {
        sb.append(",\"instances\":[");
        boolean first = true;
        for ( var inst : instances) {
            if ( !first) sb.append(",");
            sb.append("{\"nodeId\":\"").append(escapeJson(inst.nodeId()))
                     .append("\"");
            sb.append(",\"state\":\"").append(inst.state().name())
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
        for ( var entry : allMetrics.entrySet()) {
            if ( !first) sb.append(",");
            var nodeId = entry.getKey().id();
            sb.append("{\"id\":\"").append(escapeJson(nodeId))
                     .append("\"");
            sb.append(",\"isLeader\":").append(leaderId.equals(nodeId));
            sb.append("}");
            first = false;
        }
        sb.append("],\"leaderId\":\"");
        sb.append(escapeJson(leaderId));
        sb.append("\",\"nodeCount\":").append(allMetrics.size());
        sb.append("}");
    }

    @SuppressWarnings("JBCT-PAT-01")
    static String buildEventsJson(List<ClusterEvent> events) {
        var sb = new StringBuilder(256);
        sb.append("[");
        var first = true;
        for ( var event : events) {
            if ( !first) sb.append(",");
            appendEventJson(sb, event);
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static void appendEventJson(StringBuilder sb, ClusterEvent event) {
        sb.append("{\"timestamp\":\"").append(event.timestamp())
                 .append("\"");
        sb.append(",\"type\":\"").append(event.type().name())
                 .append("\"");
        sb.append(",\"severity\":\"").append(event.severity().name())
                 .append("\"");
        sb.append(",\"summary\":\"").append(escapeJson(event.summary()))
                 .append("\"");
        sb.append(",\"details\":{");
        var firstDetail = true;
        for ( var entry : event.details().entrySet()) {
            if ( !firstDetail) sb.append(",");
            sb.append("\"").append(escapeJson(entry.getKey()))
                     .append("\":\"")
                     .append(escapeJson(entry.getValue()))
                     .append("\"");
            firstDetail = false;
        }
        sb.append("}}");
    }

    @SuppressWarnings("JBCT-PAT-01") // HTTP dispatcher: inherently mixes condition checks and iteration over legacy routes
    private void handleRequest(RequestContext ctx, ResponseWriter response) {
        var startTime = System.nanoTime();
        var path = ctx.path();
        var method = ctx.method();
        var methodName = method.name();
        log.debug("Received {} {}", method, path);
        var instrumented = InstrumentedResponseWriter.instrumentedResponseWriter(response);
        // Probe endpoints — handled before router for HTTP status code control
        if ( handleProbeRequest(path, instrumented)) {
            recordRequestMetrics(methodName, path, instrumented, startTime);
            return;
        }
        // Security check — probes already bypassed
        if ( securityEnabled && !validateManagementSecurity(ctx, instrumented, path, method)) {
            recordRequestMetrics(methodName, path, instrumented, startTime);
            return;
        }
        // Try route-based routing first
        if ( router.handle(ctx, instrumented)) {
            recordRequestMetrics(methodName, path, instrumented, startTime);
            return;
        }
        // Fall back to legacy route handlers
        for ( var handler : legacyRoutes) {
        if ( handler.handle(ctx, instrumented)) {
            recordRequestMetrics(methodName, path, instrumented, startTime);
            return;
        }}
        // No route matched — fall back to static dashboard files
        staticFileHandler.handle(ctx, instrumented);
        recordRequestMetrics(methodName, path, instrumented, startTime);
    }

    private void recordRequestMetrics(String method, String path, InstrumentedResponseWriter writer, long startTime) {
        var durationNanos = System.nanoTime() - startTime;
        requestObserver.recordRequest(method, path, writer.statusCategory(), durationNanos);
    }

    @SuppressWarnings("JBCT-PAT-01")
    private boolean handleProbeRequest(String path, ResponseWriter response) {
        if ( "/health/live".equals(path)) {
            writeProbeJson(response, statusRoutes.buildLivenessResponse(), HttpStatus.OK);
            return true;
        }
        if ( "/health/ready".equals(path)) {
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
        probeJsonMapper.writeAsString(value).onSuccess(json -> response.respond(httpStatus, json))
                                     .onFailure(cause -> response.error(HttpStatus.INTERNAL_SERVER_ERROR,
                                                                        cause.message()));
    }

    @SuppressWarnings("JBCT-PAT-01")
    private boolean validateManagementSecurity(RequestContext ctx,
                                               ResponseWriter response,
                                               String path,
                                               HttpMethod method) {
        var httpContext = toManagementRequestContext(ctx, path);
        var policy = SecurityPolicy.apiKeyRequired();
        var methodName = method.name();
        var permission = RoutePermissionRegistry.resolve(methodName, path);
        return securityValidator.validate(httpContext, policy).flatMap(sc -> enforceAndAuditDenial(sc,
                                                                                                   permission,
                                                                                                   methodName,
                                                                                                   path))
                                         .onFailure(cause -> handleManagementSecurityFailure(response,
                                                                                             cause,
                                                                                             path,
                                                                                             methodName))
                                         .onSuccess(sc -> logManagementAccess(sc, methodName, path))
                                         .isSuccess();
    }

    private Result<org.pragmatica.aether.http.handler.security.SecurityContext> enforceAndAuditDenial(org.pragmatica.aether.http.handler.security.SecurityContext sc,
                                                                                                      RoutePermission permission,
                                                                                                      String method,
                                                                                                      String path) {
        return RoleEnforcer.enforce(sc, permission).onFailure(_ -> auditAccessDenied(sc, method, path, permission));
    }

    private void auditAccessDenied(org.pragmatica.aether.http.handler.security.SecurityContext sc,
                                   String method,
                                   String path,
                                   RoutePermission permission) {
        var principal = sc.isAuthenticated()
                        ? sc.principal().value()
                        : "anonymous";
        var actualRole = sc.authorizationRole().name();
        var requiredRole = permission.minimumRole().name();
        AuditLog.accessDenied(principal, method, path, actualRole, requiredRole);
        nodeSupplier.get()
        .route(OperationalEvent.AccessDenied.accessDenied(principal, method, path, actualRole, requiredRole));
    }

    private static void logManagementAccess(org.pragmatica.aether.http.handler.security.SecurityContext securityContext,
                                            String method,
                                            String path) {
        var principal = securityContext.isAuthenticated()
                        ? securityContext.principal().value()
                        : "anonymous";
        AuditLog.managementAccess("mgmt", principal, method, path);
    }

    private static HttpRequestContext toManagementRequestContext(RequestContext ctx, String path) {
        return HttpRequestContext.httpRequestContext(path,
                                                     ctx.method().name(),
                                                     ctx.queryParams().asMap(),
                                                     ctx.headers().asMap(),
                                                     ctx.body(),
                                                     "mgmt");
    }

    @SuppressWarnings("JBCT-PAT-01")
    private void handleManagementSecurityFailure(ResponseWriter response, Cause cause, String path, String method) {
        AuditLog.authFailure("mgmt", cause.message(), method, path);
        var status = resolveSecurityErrorStatus(cause);
        requestObserver.recordSecurityDenial(classifyDenialType(cause), method, path);
        if ( status == HttpStatus.UNAUTHORIZED) {
        response.header("WWW-Authenticate", "ApiKey realm=\"Aether\"").error(status, cause.message());} else
        {
        response.error(status, cause.message());}
    }

    private static String classifyDenialType(Cause cause) {
        return switch (cause) {case SecurityError.MissingCredentials _ -> "auth_failure";case SecurityError.InvalidCredentials _ -> "auth_failure";case SecurityError.AccessDenied _ -> "insufficient_role";case RoleEnforcer.AuthorizationError.AccessDenied _ -> "insufficient_role";default -> "auth_failure";};
    }

    private static HttpStatus resolveSecurityErrorStatus(Cause cause) {
        return switch (cause) {case SecurityError.MissingCredentials _ -> HttpStatus.UNAUTHORIZED;case SecurityError.InvalidCredentials _ -> HttpStatus.FORBIDDEN;case SecurityError.AccessDenied _ -> HttpStatus.FORBIDDEN;case RoleEnforcer.AuthorizationError.AccessDenied _ -> HttpStatus.FORBIDDEN;default -> HttpStatus.UNAUTHORIZED;};
    }
}

/// Response writer wrapper that captures the HTTP status code for metrics recording.
@SuppressWarnings("JBCT-RET-01") // Implements ResponseWriter interface which uses void returns
final class InstrumentedResponseWriter implements ResponseWriter {
    private final ResponseWriter delegate;
    private int statusCode;

    private InstrumentedResponseWriter(ResponseWriter delegate) {
        this.delegate = delegate;
    }

    static InstrumentedResponseWriter instrumentedResponseWriter(ResponseWriter delegate) {
        return new InstrumentedResponseWriter(delegate);
    }

    @Override public void write(org.pragmatica.http.HttpStatus status,
                                byte[] body,
                                org.pragmatica.http.ContentType contentType) {
        statusCode = status.code();
        delegate.write(status, body, contentType);
    }

    @Override public ResponseWriter header(String name, String value) {
        delegate.header(name, value);
        return this;
    }

    /// Returns the status category: "2xx", "4xx", or "5xx".
    String statusCategory() {
        if ( statusCode >= 500) {
        return "5xx";}
        if ( statusCode >= 400) {
        return "4xx";}
        return "2xx";
    }
}
