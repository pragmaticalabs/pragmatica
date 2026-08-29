// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.TimeoutsConfig.ForwardingTimeouts;
import org.pragmatica.aether.http.forward.HttpForwarder;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.management.route.MatchedRoute;
import org.pragmatica.aether.management.route.RouteTarget;
import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.aether.slice.stream.SystemStreams;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.ContentCategory;
import org.pragmatica.http.ContentType;
import org.pragmatica.aether.api.routes.AlertRoutes;
import org.pragmatica.aether.api.routes.ApiKeyRoutes;
import org.pragmatica.aether.api.routes.BackupRoutes;
import org.pragmatica.aether.api.routes.ClusterAwaitQuiescedRoute;
import org.pragmatica.aether.api.routes.ClusterConfigRoutes;
import org.pragmatica.aether.deployment.cluster.ClusterConfigApplier;
import org.pragmatica.aether.api.routes.ClusterGenerationRoutes;
import org.pragmatica.aether.api.routes.ClusterJournalRoutes;
import org.pragmatica.aether.api.routes.ClusterTopologyRoutes;
import org.pragmatica.aether.api.routes.ConfigRoutes;
import org.pragmatica.aether.api.routes.ControllerRoutes;
import org.pragmatica.aether.api.routes.DeployRoutes;
import org.pragmatica.aether.api.routes.DhtRoutes;
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
import org.pragmatica.aether.api.routes.ProblemResponses;
import org.pragmatica.aether.api.routes.SliceRoutes;
import org.pragmatica.aether.api.routes.StatusRoutes;
import org.pragmatica.aether.api.routes.RetentionRoutes;
import org.pragmatica.aether.api.routes.StorageRoutes;
import org.pragmatica.aether.api.routes.StreamManager;
import org.pragmatica.aether.api.routes.StreamRoutes;
import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardRequest;
import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardResponse;
import org.pragmatica.aether.http.forward.HttpForwardMessage.Pipeline;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.aether.http.handler.security.RoleEnforcer;
import org.pragmatica.aether.http.handler.security.RoutePermission;
import org.pragmatica.aether.http.handler.security.RoutePermissionRegistry;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.http.handler.security.SecurityContextHolder;
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
import org.pragmatica.aether.metrics.observability.AetherMetrics;
import org.pragmatica.aether.http.AetherVersioningMetricsSink;
import org.pragmatica.lang.Contract;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.aether.api.routes.EntityCheckpointRoutes;
import org.pragmatica.aether.resource.entity.EntityCheckpointDriver;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.http.server.HttpServer;
import org.pragmatica.http.server.HttpServerConfig;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.net.tcp.QuicSslContextFactory;
import org.pragmatica.http.websocket.WebSocketEndpoint;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.Deadline;
import org.pragmatica.net.tcp.TlsConfig;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.EventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;


public interface ManagementServer {
    Promise<Unit> start();
    Promise<Unit> stop();
    Promise<Unit> rotateCertificate(org.pragmatica.net.tcp.security.CertificateBundle newBundle);
    /// The real (Prometheus-backed) meter registry this server publishes `/metrics` from.
    /// Exposed so resource provisioning (#278) can inject the node's actual `MeterRegistry` into
    /// slice-facing interceptors instead of each factory fabricating its own disconnected one.
    MeterRegistry meterRegistry();

    @SuppressWarnings("JBCT-RET-01")
    void onHttpForwardRequest(HttpForwardRequest request);

    @SuppressWarnings("JBCT-RET-01")
    void onHttpForwardResponse(HttpForwardResponse response);

    static ManagementServer managementServer(int port,
                                             Supplier<ManageableNode> nodeSupplier,
                                             EntityCheckpointDriver entityCheckpointDriver,
                                             AlertManager alertManager,
                                             ObservabilityConfigRegistry configRegistry,
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
                                             HttpProtocol httpProtocol,
                                             ForwardingTimeouts forwardingTimeouts,
                                             Option<ClusterNetwork> clusterNetwork,
                                             Option<Serializer> serializer,
                                             Option<Deserializer> deserializer,
                                             Consumer<NodeId> drainCommandSink,
                                             Supplier<Set<NodeId>> pendingDrainsSupplier) {
        return new ManagementServerImpl(port,
                                        nodeSupplier,
                                        entityCheckpointDriver,
                                        alertManager,
                                        configRegistry,
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
                                        httpProtocol,
                                        forwardingTimeouts,
                                        clusterNetwork,
                                        serializer,
                                        deserializer,
                                        drainCommandSink,
                                        pendingDrainsSupplier);
    }
}

class ManagementServerImpl implements ManagementServer {
    private static final Logger log = LoggerFactory.getLogger(ManagementServerImpl.class);
    private static final int MAX_CONTENT_LENGTH = 64 * 1024 * 1024;

    private final int port;
    private final Supplier<ManageableNode> nodeSupplier;
    private final AlertManager alertManager;
    private final ObservabilityConfigRegistry configRegistry;
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
    private final Option<ClusterNetwork> clusterNetwork;
    private final Option<Serializer> forwardSerializer;
    private final Option<Deserializer> forwardDeserializer;
    private final ForwardingTimeouts forwardingTimeouts;

    /// Below this much remaining wire budget a forwarded management request is refused instead of
    /// dispatched: the answer cannot reach the sender before its hop timeout fires.
    private static final TimeSpan RECEIVER_BUDGET_FLOOR = TimeSpan.timeSpan(50).millis();

    private final Consumer<NodeId> drainCommandSink;
    private final Supplier<Set<NodeId>> pendingDrainsSupplier;

    private final AtomicReference<Option<HttpForwarder>> mgmtForwarderRef = new AtomicReference<>(Option.empty());

    private final AtomicReference<HttpServer> serverRef = new AtomicReference<>();
    private final AtomicReference<HttpServer> h3ServerRef = new AtomicReference<>();
    /// #642: the only route source that arms a periodic task. Held so stop() can cancel its sweep —
    /// route sources are otherwise fire-and-forget, and this one outlived its node on the shared
    /// scheduler.
    private final AtomicReference<ApiKeyRoutes> apiKeyRoutesRef = new AtomicReference<>();
    private final StaticFileHandler staticFileHandler;
    private final ManagementRouter router;
    private final StatusRoutes statusRoutes;
    private final JsonMapper probeJsonMapper;
    private final List<RouteHandler> legacyRoutes;

    ManagementServerImpl(int port,
                         Supplier<ManageableNode> nodeSupplier,
                         EntityCheckpointDriver entityCheckpointDriver,
                         AlertManager alertManager,
                         ObservabilityConfigRegistry configRegistry,
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
                         HttpProtocol httpProtocol,
                         ForwardingTimeouts forwardingTimeouts,
                         Option<org.pragmatica.consensus.net.ClusterNetwork> clusterNetwork,
                         Option<org.pragmatica.serialization.Serializer> serializer,
                         Option<org.pragmatica.serialization.Deserializer> deserializer,
                         Consumer<NodeId> drainCommandSink,
                         Supplier<Set<NodeId>> pendingDrainsSupplier) {
        this.port = port;
        this.nodeSupplier = nodeSupplier;
        this.alertManager = alertManager;
        this.configRegistry = configRegistry;
        this.traceStore = traceStore;
        this.logLevelRegistry = logLevelRegistry;
        this.securityValidator = securityValidator;
        this.securityEnabled = securityEnabled;
        this.bossGroup = bossGroup;
        this.workerGroup = workerGroup;
        this.httpProtocol = httpProtocol;
        this.clusterNetwork = clusterNetwork;
        this.forwardSerializer = serializer;
        this.forwardDeserializer = deserializer;
        this.forwardingTimeouts = forwardingTimeouts;
        this.drainCommandSink = drainCommandSink;
        this.pendingDrainsSupplier = pendingDrainsSupplier == null
                                     ? Set::of
                                     : pendingDrainsSupplier;
        this.wsAuthenticator = WebSocketAuthenticator.webSocketAuthenticator(securityValidator, securityEnabled);
        this.metricsPublisher = DashboardMetricsPublisher.dashboardMetricsPublisher(nodeSupplier, alertManager);
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
        this.requestObserver = HttpRequestObserver.httpRequestObserver(observability);
        this.tls = tls;
        this.probeJsonMapper = JsonMapper.defaultJsonMapper();
        var routeSources = new ArrayList<RouteSource>();

        this.statusRoutes = StatusRoutes.statusRoutes(nodeSupplier,
                                                      () -> nodeSupplier.get()
                                                                        .appHttpServer());
        routeSources.add(statusRoutes);
        routeSources.add(AlertRoutes.alertRoutes(alertManager));
        routeSources.add(org.pragmatica.aether.api.routes.CertificateRoutes.certificateRoutes(nodeSupplier));
        routeSources.add(LogLevelRoutes.logLevelRoutes(logLevelRegistry));
        routeSources.add(ObservabilityRoutes.observabilityRoutes(configRegistry, traceStore));
        routeSources.add(ControllerRoutes.controllerRoutes(nodeSupplier));
        routeSources.add(SliceRoutes.sliceRoutes(nodeSupplier));
        routeSources.add(MetricsRoutes.metricsRoutes(nodeSupplier, observability));
        routeSources.add(DeployRoutes.deployRoutes(nodeSupplier));
        routeSources.add(AbTestRoutes.abTestRoutes(nodeSupplier));
        routeSources.add(NodeLifecycleRoutes.nodeLifecycleRoutes(nodeSupplier, drainCommandSink, pendingDrainsSupplier));
        routeSources.add(RepositoryRoutes.repositoryRoutes(nodeSupplier));
        routeSources.add(ScheduledTaskRoutes.scheduledTaskRoutes(scheduledTaskRegistry,
                                                                 scheduledTaskManager,
                                                                 nodeSupplier,
                                                                 sliceInvoker,
                                                                 scheduledTaskStateRegistry));
        routeSources.add(ClusterTopologyRoutes.clusterTopologyRoutes(nodeSupplier));
        routeSources.add(ClusterJournalRoutes.clusterJournalRoutes(nodeSupplier));
        routeSources.add(ClusterGenerationRoutes.clusterGenerationRoutes(nodeSupplier));
        routeSources.add(EntityCheckpointRoutes.entityCheckpointRoutes(entityCheckpointDriver,
                                                                       () -> nodeSupplier.get()
                                                                                         .kvStore()));
        routeSources.add(ClusterAwaitQuiescedRoute.clusterAwaitQuiescedRoute(nodeSupplier));
        routeSources.add(nodeSupplier.get()
                                     .clusterTopologyManager()
                                     .map(ClusterConfigApplier::clusterConfigApplier)
                                     .map(applier -> ClusterConfigRoutes.clusterConfigRoutes(nodeSupplier, applier))
                                     .or(ClusterConfigRoutes.clusterConfigRoutes(nodeSupplier)));
        routeSources.add(BackupRoutes.backupRoutes(() -> nodeSupplier.get()
                                                                     .backupService(),
                                                   nodeSupplier));
        routeSources.add(SchemaRoutes.schemaRoutes(nodeSupplier));
        routeSources.add(StreamRoutes.streamRoutes(nodeSupplier,
                                                   nodeSupplier.get().consumerGroupCoordinator(),
                                                   nodeSupplier.get().consumerGroupRegistry()));
        routeSources.add(org.pragmatica.aether.api.routes.StreamApiRoutes.streamApiRoutes(nodeSupplier,
                                                                                          nodeSupplier.get()
                                                                                                      .streamNamespacesService(),
                                                                                          nodeSupplier.get()
                                                                                                      .consumerGroupCoordinator(),
                                                                                          nodeSupplier.get()
                                                                                                      .consumerGroupRegistry()));
        routeSources.add(org.pragmatica.aether.api.routes.StreamNamespacesRoutes.streamNamespacesRoutes(nodeSupplier.get()
                                                                                                                    .streamNamespacesService()));
        routeSources.add(StorageRoutes.storageRoutes(nodeSupplier));
        routeSources.add(RetentionRoutes.retentionRoutes(nodeSupplier));
        var apiKeyRoutes = ApiKeyRoutes.apiKeyRoutes(nodeSupplier);

        apiKeyRoutesRef.set(apiKeyRoutes);
        routeSources.add(apiKeyRoutes);
        routeSources.add(DhtRoutes.dhtRoutes(nodeSupplier));
        routeSources.add(org.pragmatica.aether.api.routes.VersionRoutes.versionRoutes(nodeSupplier));
        routeSources.add(org.pragmatica.aether.api.routes.WorkerRoutes.workerRoutes(nodeSupplier));
        // #525: turns declared-but-unbuilt routes into an honest 501 instead of a bare 404.
        // Registration order is irrelevant (route names are unique); registration itself is not —
        // dropping this source silently resurrects the dead-route class. Reasons live per-route in
        // NotImplementedRoutes, and ManagementRouteCoverageTest fails if a route loses its handler.
        routeSources.add(org.pragmatica.aether.api.routes.NotImplementedRoutes.notImplementedRoutes());
        dynamicConfigManager.onPresent(dcm -> routeSources.add(ConfigRoutes.configRoutes(dcm, nodeSupplier)));
        this.router = ManagementRouter.managementRouter(routeSources.toArray(RouteSource[]::new));
        // #520 — the publication gate inside MavenProtocolRoutes must see the SAME posture this
        // server gates management auth on. `securityEnabled` is `config.appHttp().securityEnabled()`
        // (see AetherNode), so `false` means app-HTTP `security_mode = NONE`: no SecurityContext is
        // ever bound and OPERATOR is structurally unholdable. Passing it here unifies the two
        // half-overlapping dev switches instead of leaving the route with its own env-only bypass.
        this.legacyRoutes = List.of(MavenProtocolRoutes.mavenProtocolRoutes(nodeSupplier, () -> securityEnabled));
        installVersioningMetricsSink(nodeSupplier, observability);
    }

    /// #198 §11.1: install the AetherMetrics-backed versioning sink into the node's
    /// `HttpRoutePublisher` so versioned-request / deprecated / missing-header counters reach the
    /// Micrometer registry owned here. No-op when the app HTTP server has no publisher.
    @Contract
    private static void installVersioningMetricsSink(Supplier<ManageableNode> nodeSupplier,
                                                     ObservabilityRegistry observability) {
        var sink = AetherVersioningMetricsSink.aetherVersioningMetricsSink(AetherMetrics.aetherMetrics(observability));

        nodeSupplier.get()
                    .appHttpServer()
                    .httpRoutePublisher()
                    .onPresent(publisher -> publisher.setVersioningMetricsSink(sink));
    }

    @Override
    public MeterRegistry meterRegistry() {
        return observability.registry();
    }

    @Override
    public Promise<Unit> start() {
        log.info("Starting management server on port {} (protocol: {})", port, httpProtocol);
        if (httpProtocol.includesH1()) {
            return startH1Server().flatMap(_ -> httpProtocol.includesH3()
                                                ? startH3Server()
                                                : Promise.success(unit()));
        }

        return startH3Server();
    }

    private Promise<Unit> startH1Server() {
        var serverConfig = buildServerConfig();
        java.util.function.BiConsumer<HttpRequest, ResponseWriter> handler = httpProtocol == HttpProtocol.BOTH
                                                                             ? this::handleRequestWithAltSvc
                                                                             : this::handleRequest;
        var serverPromise = bossGroup.flatMap(bg -> workerGroup.map(wg -> HttpServer.httpServer(serverConfig,
                                                                                                handler,
                                                                                                bg,
                                                                                                wg)))
                                     .or(HttpServer.httpServer(serverConfig, handler));

        return serverPromise.map(this::registerStartedH1Server)
                            .onFailure(cause -> log.error("Failed to start management server on port {}: {}",
                                                          port,
                                                          cause.message()));
    }

    private Promise<Unit> startH3Server() {
        var quicTls = tls.map(QuicSslContextFactory::createServer).or(QuicSslContextFactory.createSelfSignedServer());

        return quicTls.onFailure(cause -> log.error("Failed to create QUIC SSL context for management server: {}",
                                                    cause.message()))
                      .map(this::startH3WithSslContext)
                      .or(Promise.success(unit()));
    }

    private Promise<Unit> startH3WithSslContext(io.netty.handler.codec.quic.QuicSslContext quicSslContext) {
        var serverConfig = HttpServerConfig.httpServerConfig("management-h3", port).withMaxContentLength(MAX_CONTENT_LENGTH);
        var serverPromise = workerGroup.map(wg -> HttpServer.http3Server(serverConfig,
                                                                         quicSslContext,
                                                                         this::handleRequest,
                                                                         wg))
                                       .or(HttpServer.http3Server(serverConfig, quicSslContext, this::handleRequest));

        return serverPromise.map(this::registerStartedH3Server)
                            .onFailure(cause -> log.error("Failed to start management HTTP/3 server on port {}: {}",
                                                          port,
                                                          cause.message()));
    }

    private HttpServerConfig buildServerConfig() {
        var wsHandler = new DashboardWebSocketHandler(metricsPublisher, wsAuthenticator);
        var wsEndpoint = WebSocketEndpoint.webSocketEndpoint("/ws/dashboard", wsHandler);
        var statusWsEndpoint = WebSocketEndpoint.webSocketEndpoint("/ws/status", statusWsHandler);
        var eventWsEndpoint = WebSocketEndpoint.webSocketEndpoint("/ws/events", eventWsHandler);
        var config = HttpServerConfig.httpServerConfig("management", port)
                                     .withMaxContentLength(MAX_CONTENT_LENGTH)
                                     .withWebSocket(wsEndpoint)
                                     .withWebSocket(statusWsEndpoint)
                                     .withWebSocket(eventWsEndpoint);

        return tls.map(config::withTls)
                  .or(config);
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

    private void handleRequestWithAltSvc(HttpRequest request, ResponseWriter response) {
        response.header("Alt-Svc", "h3=\":" + port + "\"; ma=3600");
        handleRequest(request, response);
    }

    @Override
    public Promise<Unit> stop() {
        metricsPublisher.stop();
        statusWsPublisher.stop();
        eventWsPublisher.stop();
        Option.option(apiKeyRoutesRef.get()).onPresent(ApiKeyRoutes::stop);
        var h1Stop = Option.option(serverRef.get())
                           .map(server -> server.stop()
                                                .onSuccessRun(() -> log.info("Management HTTP/1.1 server stopped")))
                           .or(Promise.success(unit()));
        var h3Stop = Option.option(h3ServerRef.get())
                           .map(server -> server.stop()
                                                .onSuccessRun(() -> log.info("Management HTTP/3 server stopped")))
                           .or(Promise.success(unit()));

        return h1Stop.flatMap(_ -> h3Stop);
    }

    @Override
    public Promise<Unit> rotateCertificate(org.pragmatica.net.tcp.security.CertificateBundle newBundle) {
        log.info("Rotating management server TLS certificate");

        return stopHttpServers().flatMap(_ -> restartWithNewBundle(newBundle));
    }

    private Promise<Unit> stopHttpServers() {
        var h1Stop = Option.option(serverRef.getAndSet(null)).map(HttpServer::stop).or(Promise.success(unit()));
        var h3Stop = Option.option(h3ServerRef.getAndSet(null)).map(HttpServer::stop).or(Promise.success(unit()));

        return h1Stop.flatMap(_ -> h3Stop);
    }

    @SuppressWarnings("JBCT-PAT-01")
    private Promise<Unit> restartWithNewBundle(org.pragmatica.net.tcp.security.CertificateBundle newBundle) {
        var newTlsConfig = buildTlsFromBundle(newBundle);
        var protocol = httpProtocol;

        if (protocol.includesH1()) {
            return restartH1WithTls(newTlsConfig).flatMap(_ -> protocol.includesH3()
                                                               ? restartH3WithBundle(newBundle)
                                                               : Promise.success(unit()));
        }

        return restartH3WithBundle(newBundle);
    }

    private Promise<Unit> restartH1WithTls(Option<TlsConfig> newTls) {
        var wsHandler = new DashboardWebSocketHandler(metricsPublisher, wsAuthenticator);
        var wsEndpoint = WebSocketEndpoint.webSocketEndpoint("/ws/dashboard", wsHandler);
        var statusWsEndpoint = WebSocketEndpoint.webSocketEndpoint("/ws/status", statusWsHandler);
        var eventWsEndpoint = WebSocketEndpoint.webSocketEndpoint("/ws/events", eventWsHandler);
        var config = HttpServerConfig.httpServerConfig("management", port)
                                     .withMaxContentLength(MAX_CONTENT_LENGTH)
                                     .withWebSocket(wsEndpoint)
                                     .withWebSocket(statusWsEndpoint)
                                     .withWebSocket(eventWsEndpoint);
        var serverConfig = newTls.map(config::withTls).or(config);
        java.util.function.BiConsumer<HttpRequest, ResponseWriter> handler = httpProtocol == HttpProtocol.BOTH
                                                                             ? this::handleRequestWithAltSvc
                                                                             : this::handleRequest;
        var serverPromise = bossGroup.flatMap(bg -> workerGroup.map(wg -> HttpServer.httpServer(serverConfig,
                                                                                                handler,
                                                                                                bg,
                                                                                                wg)))
                                     .or(HttpServer.httpServer(serverConfig, handler));

        return serverPromise.map(this::registerStartedH1Server)
                            .onSuccess(_ -> log.info("Management HTTP/1.1 server restarted with new certificate"))
                            .onFailure(cause -> log.error("Failed to restart management HTTP/1.1 server: {}",
                                                          cause.message()));
    }

    private Promise<Unit> restartH3WithBundle(org.pragmatica.net.tcp.security.CertificateBundle newBundle) {
        var quicTls = QuicSslContextFactory.createServerFromBundle(newBundle);

        return quicTls.map(this::startH3WithSslContext)
                      .onFailure(cause -> log.error("Failed to create QUIC SSL context for management server rotation: {}",
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
        observability.registerTransportMetrics(() -> nodeSupplier.get()
                                                                 .transportMetrics());
        // #674: consensus-load counters on the Prometheus surface, same key vocabulary as the
        // comprehensive response's consensus block (RabiaMetrics.counterMap()).
        observability.registerConsensusMetrics(() -> nodeSupplier.get()
                                                                 .snapshotCollector()
                                                                 .consensusSnapshot()
                                                                 .counterMap());
        registerStreamMemoryMetrics();
        var transport = tls.isPresent()
                        ? "HTTPS"
                        : "HTTP";

        log.info("{} management server started on port {} (protocol: {}, dashboard at /)", transport, port, httpProtocol);
    }

    private void registerStreamMemoryMetrics() {
        var spm = nodeSupplier.get().streamPartitionManager();
        Supplier<Number> usedBytes = spm::totalAllocatedBytes;
        Supplier<Number> usedRatio = () -> computeStreamMemoryRatio(spm);

        observability.gauge("aether.streams.memory.used.bytes", usedBytes);
        observability.gauge("aether.streams.memory.used.ratio", usedRatio);
    }

    private static double computeStreamMemoryRatio(StreamPartitionManager spm) {
        return spm.maxTotalBytes() > 0
               ? (double) spm.totalAllocatedBytes() / spm.maxTotalBytes()
               : 0.0;
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"");
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static String buildStatusJson(Supplier<ManageableNode> nodeSupplier) {
        var node = nodeSupplier.get();
        var leaderId = node.leader().map(leader -> leader.id()).or("");
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

    @SuppressWarnings("JBCT-PAT-01")
    private static void appendNodeMetrics(StringBuilder sb,
                                          Map<NodeId, Map<String, Double>> allMetrics,
                                          String leaderId) {
        sb.append(",\"nodeMetrics\":[");
        boolean first = true;

        for (var entry : allMetrics.entrySet()) {
            if (!first) sb.append(",");

            appendSingleNodeMetric(sb,
                                   entry.getKey().id(),
                                   entry.getValue(),
                                   leaderId);
            first = false;
        }

        sb.append("]");
    }

    private static void appendSingleNodeMetric(StringBuilder sb,
                                               String nodeId,
                                               Map<String, Double> metrics,
                                               String leaderId) {
        var cpuUsage = metrics.getOrDefault("cpu.usage", 0.0);
        var heapUsed = metrics.getOrDefault("heap.used", 0.0);
        var heapMax = metrics.getOrDefault("heap.max", 1.0);

        sb.append("{\"nodeId\":\"").append(escapeJson(nodeId)).append("\"");
        sb.append(",\"isLeader\":").append(leaderId.equals(nodeId));
        sb.append(",\"cpuUsage\":").append(cpuUsage);
        sb.append(",\"heapUsedMb\":").append((long)(heapUsed / 1024 / 1024));
        sb.append(",\"heapMaxMb\":").append((long)(heapMax / 1024 / 1024));
        sb.append("}");
    }

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

    @SuppressWarnings("JBCT-PAT-01")
    private static void appendSingleSlice(StringBuilder sb, SliceDeploymentInfo info) {
        sb.append("{\"artifact\":\"").append(escapeJson(info.artifact())).append("\"");
        sb.append(",\"state\":\"").append(info.aggregateState().name()).append("\"");
        appendSliceInstances(sb, info.instances());
        sb.append("}");
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static void appendSliceInstances(StringBuilder sb, List<SliceInstanceInfo> instances) {
        sb.append(",\"instances\":[");
        boolean first = true;

        for (var inst : instances) {
            if (!first) sb.append(",");

            sb.append("{\"nodeId\":\"").append(escapeJson(inst.nodeId())).append("\"");
            sb.append(",\"state\":\"").append(inst.state().name()).append("\"}");
            first = false;
        }

        sb.append("]");
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static void appendClusterInfo(StringBuilder sb,
                                          Map<NodeId, Map<String, Double>> allMetrics,
                                          String leaderId) {
        sb.append(",\"cluster\":{\"nodes\":[");
        boolean first = true;

        for (var entry : allMetrics.entrySet()) {
            if (!first) sb.append(",");

            var nodeId = entry.getKey().id();

            sb.append("{\"id\":\"").append(escapeJson(nodeId)).append("\"");
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
        // Preserve the legacy `timestamp` JSON field name for dashboard / WS read-compat, but
        // source it from the HLC's physical-millis half (the sealed ClusterEvent carries `at()`,
        // an HlcTimestamp, instead of the old wall-clock Instant). `type` is the record variant
        // simple name (e.g. "NodeJoined") now that EventType is a sealed hierarchy, not an enum.
        sb.append("{\"timestamp\":\"").append(Instant.ofEpochMilli(event.at().physicalMillis())).append("\"");
        sb.append(",\"type\":\"").append(event.getClass().getSimpleName()).append("\"");
        sb.append(",\"severity\":\"").append(event.severity().name()).append("\"");
        sb.append(",\"summary\":\"").append(escapeJson(event.summary())).append("\"");
        sb.append(",\"details\":{");
        var firstDetail = true;

        for (var entry : event.details().entrySet()) {
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

    @SuppressWarnings("JBCT-PAT-01")
    private void handleRequest(HttpRequest ctx, ResponseWriter response) {
        var startTime = System.nanoTime();
        var path = ctx.path();
        var method = ctx.method();
        var methodName = method.name();

        log.debug("Received {} {}", method, path);
        var instrumented = InstrumentedResponseWriter.instrumentedResponseWriter(response);

        if (handleProbeRequest(path, instrumented)) {
            recordRequestMetrics(methodName, path, instrumented, startTime);

            return;
        }

        if (isDashboardPath(path)) {
            staticFileHandler.handle(ctx, instrumented);
            recordRequestMetrics(methodName, path, instrumented, startTime);

            return;
        }

        if (rejectSystemStreamWrite(ctx, instrumented, path, methodName)) {
            recordRequestMetrics(methodName, path, instrumented, startTime);

            return;
        }

        if (securityEnabled) {
            var auth = validateManagementSecurity(ctx, instrumented, path, method);

            if (auth.isFailure()) {
                recordRequestMetrics(methodName, path, instrumented, startTime);

                return;
            }

            auth.onSuccess(sc -> ScopedValue.where(SecurityContextHolder.scopedValue(),
                                                   sc)
                                            .run(() -> dispatchManagementRequest(ctx,
                                                                                 instrumented,
                                                                                 methodName,
                                                                                 startTime)));

            return;
        }

        dispatchManagementRequest(ctx, instrumented, methodName, startTime);
    }

    private void dispatchManagementRequest(HttpRequest ctx,
                                           InstrumentedResponseWriter instrumented,
                                           String methodName,
                                           long startTime) {
        var path = ctx.path();

        if (tryForwardToRouteOwner(ctx, instrumented, methodName, startTime)) {
            return;
        }

        if (router.handle(ctx, instrumented)) {
            recordRequestMetrics(methodName, path, instrumented, startTime);

            return;
        }

        for (var handler : legacyRoutes) {
            if (handler.handle(ctx, instrumented)) {
                recordRequestMetrics(methodName, path, instrumented, startTime);

                return;
            }
        }

        staticFileHandler.handle(ctx, instrumented);
        recordRequestMetrics(methodName, path, instrumented, startTime);
    }

    private boolean tryForwardToRouteOwner(HttpRequest ctx,
                                           InstrumentedResponseWriter response,
                                           String methodName,
                                           long startTime) {
        var methodOpt = parseRoutingMethod(ctx.method().name());

        if (methodOpt.isEmpty()) {
            return false;
        }

        var matched = ManagementRoute.match(methodOpt.unwrap(), ctx.path()).option();

        if (matched.isEmpty()) {
            return false;
        }

        var matchedRoute = matched.unwrap();
        var target = matchedRoute.route().target();

        return switch (target) {
            case RouteTarget.LocalNode __ -> false;
            case RouteTarget.AnyCoreNode __ -> tryForwardIfNotCore(ctx, response, methodName, startTime);
            case RouteTarget.TaskGroupTarget(var group) -> tryForwardIfNotOwner(ctx,
                                                                                response,
                                                                                methodName,
                                                                                startTime,
                                                                                group);
            case RouteTarget.LeaderNode __ -> tryForwardIfNotLeader(ctx, response, methodName, startTime);
            case RouteTarget.NodeIdParam(var paramIndex) -> tryForwardIfNotTargetNode(ctx,
                                                                                      response,
                                                                                      methodName,
                                                                                      startTime,
                                                                                      matchedRoute,
                                                                                      paramIndex);
        };
    }

    private boolean tryForwardIfNotLeader(HttpRequest ctx,
                                          InstrumentedResponseWriter response,
                                          String methodName,
                                          long startTime) {
        var node = nodeSupplier.get();

        if (node.isLeader()) {
            return false;
        }

        forwardManagementRequest(ctx, response, methodName, startTime);

        return true;
    }

    private boolean tryForwardIfNotCore(HttpRequest ctx,
                                        InstrumentedResponseWriter response,
                                        String methodName,
                                        long startTime) {
        var node = nodeSupplier.get();

        if (node.topologyConfig().coreNodes().stream().anyMatch(info -> info.id()
                                                                            .equals(node.self()))) {
            return false;
        }

        forwardManagementRequest(ctx, response, methodName, startTime);

        return true;
    }

    private boolean tryForwardIfNotOwner(HttpRequest ctx,
                                         InstrumentedResponseWriter response,
                                         String methodName,
                                         long startTime,
                                         TaskGroup group) {
        var node = nodeSupplier.get();
        var ownerResult = node.taskGroupOwnerResolver().apply(group);

        if (ownerResult.isFailure()) {
            return false;
        }

        var owner = ownerResult.unwrap();

        if (owner.equals(node.self())) {
            return false;
        }

        forwardManagementRequest(ctx, response, methodName, startTime);

        return true;
    }

    /// Forward if the path-param-named node id != local node. The forwarder also re-checks
    /// (locality + connectedness) but we short-circuit here to avoid an unnecessary forwarder
    /// instantiation when the target is local.
    private boolean tryForwardIfNotTargetNode(HttpRequest ctx,
                                              InstrumentedResponseWriter response,
                                              String methodName,
                                              long startTime,
                                              org.pragmatica.aether.management.route.MatchedRoute matched,
                                              int paramIndex) {
        var paramNames = matched.route().paramNames();

        if (paramIndex < 0 || paramIndex >= paramNames.size()) {
            return false;
        }

        var targetId = matched.params().get(paramNames.get(paramIndex));

        if (targetId == null) {
            return false;
        }

        var node = nodeSupplier.get();

        if (targetId.equals(node.self().id())) {
            return false;
        }

        forwardManagementRequest(ctx, response, methodName, startTime);

        return true;
    }

    private void forwardManagementRequest(HttpRequest ctx,
                                          InstrumentedResponseWriter response,
                                          String methodName,
                                          long startTime) {
        var path = ctx.path();
        var requestId = ctx.requestId();

        ensureMgmtForwarder().fold(() -> sendForwardUnavailable(response, path, requestId, methodName, startTime),
                                   forwarder -> forwardViaForwarder(forwarder,
                                                                    ctx,
                                                                    response,
                                                                    methodName,
                                                                    startTime,
                                                                    path,
                                                                    requestId));
    }

    /// Budget minted at the forward decision: `forwardManagement` captures the ambient deadline
    /// synchronously at entry, and every hop, task-group retry and re-query under this request
    /// consumes from that one budget.
    private Unit forwardViaForwarder(HttpForwarder forwarder,
                                     HttpRequest ctx,
                                     InstrumentedResponseWriter response,
                                     String methodName,
                                     long startTime,
                                     String path,
                                     String requestId) {
        Deadline.runWith(Deadline.startingNow(forwardingTimeouts.managementRequestBudget()),
                         () -> forwarder.forwardManagement(toManagementRequestContext(ctx, path),
                                                           requestId))
                .onSuccess(responseData -> sendForwardedResponse(response, responseData))
                .onFailure(cause -> sendForwardError(response, path, requestId, cause))
                .onResultRun(() -> recordRequestMetrics(methodName, path, response, startTime));

        return Unit.unit();
    }

    private Option<HttpForwarder> ensureMgmtForwarder() {
        var existing = mgmtForwarderRef.get();

        if (existing.isPresent()) {
            return existing;
        }

        if (clusterNetwork.isEmpty() || forwardSerializer.isEmpty() || forwardDeserializer.isEmpty()) {
            return Option.empty();
        }

        var node = nodeSupplier.get();
        var fwd = HttpForwarder.httpForwarder(node.self(),
                                              node.httpRouteRegistry(),
                                              clusterNetwork.unwrap(),
                                              forwardSerializer.unwrap(),
                                              forwardDeserializer.unwrap(),
                                              forwardingTimeouts.managementTimeout(),
                                              forwardingTimeouts.retryDelay().millis(),
                                              forwardingTimeouts.maxRetries(),
                                              () -> coreNodeIds(node),
                                              node.taskGroupOwnerResolver(),
                                              () -> nodeSupplier.get()
                                                                .leader());
        var wrapped = Option.<HttpForwarder> some(fwd);

        return mgmtForwarderRef.compareAndSet(existing, wrapped)
               ? wrapped
               : mgmtForwarderRef.get();
    }

    private static Set<NodeId> coreNodeIds(ManageableNode node) {
        return node.topologyConfig()
                   .coreNodes()
                   .stream()
                   .map(NodeInfo::id)
                   .collect(Collectors.toSet());
    }

    private static Option<org.pragmatica.http.HttpMethod> parseRoutingMethod(String raw) {
        return Result.lift(Causes::fromThrowable,
                           () -> org.pragmatica.http.HttpMethod.valueOf(raw.toUpperCase()))
                     .option();
    }

    private void sendForwardedResponse(InstrumentedResponseWriter response, HttpResponseData responseData) {
        var contentType = Option.option(responseData.headers().get("Content-Type"))
                                .map(ct -> ContentType.contentType(ct, ContentCategory.JSON))
                                .or(CommonContentType.APPLICATION_JSON);

        response.write(toServerStatus(responseData.statusCode()), responseData.body(), contentType);
    }

    private void sendForwardError(InstrumentedResponseWriter response, String path, String requestId, Cause cause) {
        log.warn("Management forward failed [{}] {}: {}", requestId, path, cause.message());
        ProblemResponses.writeProblem(response,
                                      org.pragmatica.http.HttpStatus.SERVICE_UNAVAILABLE,
                                      "Management forward failed: " + cause.message(),
                                      path,
                                      requestId);
    }

    private Unit sendForwardUnavailable(InstrumentedResponseWriter response,
                                        String path,
                                        String requestId,
                                        String methodName,
                                        long startTime) {
        log.warn("Management forwarder unavailable [{}] {} {}", requestId, methodName, path);
        ProblemResponses.writeProblem(response,
                                      org.pragmatica.http.HttpStatus.SERVICE_UNAVAILABLE,
                                      "Management forwarding not yet available",
                                      path,
                                      requestId);
        recordRequestMetrics(methodName, path, response, startTime);

        return Unit.unit();
    }

    private static HttpStatus toServerStatus(int code) {
        for (var status : HttpStatus.values()) {
            if (status.code() == code) {
                return status;
            }
        }

        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private void recordRequestMetrics(String method, String path, InstrumentedResponseWriter writer, long startTime) {
        var durationNanos = System.nanoTime() - startTime;

        requestObserver.recordRequest(method, path, writer.statusCategory(), durationNanos);
    }

    @SuppressWarnings("JBCT-RET-01")
    @Override
    public void onHttpForwardResponse(HttpForwardResponse response) {
        ensureMgmtForwarder().onPresent(fwd -> fwd.onHttpForwardResponse(response));
    }

    @SuppressWarnings({"JBCT-RET-01", "JBCT-PAT-01"})
    @Override
    public void onHttpForwardRequest(HttpForwardRequest request) {
        log.trace("Received management HttpForwardRequest [{}] correlationId={}",
                  request.requestId(),
                  request.correlationId());
        if (forwardDeserializer.isEmpty() || forwardSerializer.isEmpty() || clusterNetwork.isEmpty()) {
            log.error("[{}] Cannot handle management forward request - missing dependencies", request.requestId());

            return;
        }

        var des = forwardDeserializer.unwrap();
        var ser = forwardSerializer.unwrap();
        var network = clusterNetwork.unwrap();

        Result.<HttpRequestContext, byte[]> lift1(des::decode,
                                                  request.requestData())
              .onFailure(cause -> sendManagementForwardError(network,
                                                             request,
                                                             "Deserialization failed: " + cause.message()))
              .onSuccess(context -> dispatchManagementForward(context, request, network, ser));
    }

    @SuppressWarnings("JBCT-PAT-01")
    private void dispatchManagementForward(HttpRequestContext context,
                                           HttpForwardRequest request,
                                           ClusterNetwork network,
                                           Serializer ser) {
        // Stage 2 of deadline propagation, management pipeline: a request whose sender's budget is
        // already gone is refused before touching the router — its answer has no collector. A live
        // budget is re-minted and BOUND for the dispatch, so a handler that forwards again or waits
        // on cluster state consumes the sender's remaining budget instead of its full defaults.
        var deadline = Deadline.fromWireMillis(request.remainingMillis());

        if (deadline.expired(RECEIVER_BUDGET_FLOOR)) {
            log.warn("[{}] Management forward arrived with {} budget remaining; refusing without dispatch",
                     request.requestId(),
                     deadline.remaining());
            sendManagementForwardError(network, request, "Sender request budget exhausted before dispatch");

            return;
        }

        Deadline.runWith(deadline, () -> dispatchManagementForwardWithinBudget(context, request, network, ser));
    }

    @SuppressWarnings("JBCT-PAT-01")
    private void dispatchManagementForwardWithinBudget(HttpRequestContext context,
                                                       HttpForwardRequest request,
                                                       ClusterNetwork network,
                                                       Serializer ser) {
        var serverCtx = ForwardedRequestContext.forwardedRequestContext(context);
        var responseCapture = ForwardedResponseWriter.forwardedResponseWriter();

        if (securityEnabled) {
            var method = Result.lift(Causes::fromThrowable,
                                     () -> HttpMethod.valueOf(context.method().toUpperCase()))
                               .option();

            if (method.isEmpty()) {
                sendManagementForwardError(network, request, "Unsupported HTTP method: " + context.method());

                return;
            }

            if (validateManagementSecurity(serverCtx, responseCapture, context.path(), method.unwrap()).isFailure()) {
                responseCapture.completion()
                               .onSuccess(responseData -> sendManagementForwardSuccess(network,
                                                                                       request,
                                                                                       ser,
                                                                                       responseData))
                               .onFailure(cause -> sendManagementForwardError(network,
                                                                              request,
                                                                              cause.message()));

                return;
            }
        }

        if (router.handle(serverCtx, responseCapture)) {
            responseCapture.completion()
                           .onSuccess(responseData -> sendManagementForwardSuccess(network, request, ser, responseData))
                           .onFailure(cause -> sendManagementForwardError(network,
                                                                          request,
                                                                          cause.message()));

            return;
        }

        for (var handler : legacyRoutes) {
            if (handler.handle(serverCtx, responseCapture)) {
                responseCapture.completion()
                               .onSuccess(responseData -> sendManagementForwardSuccess(network,
                                                                                       request,
                                                                                       ser,
                                                                                       responseData))
                               .onFailure(cause -> sendManagementForwardError(network,
                                                                              request,
                                                                              cause.message()));

                return;
            }
        }

        var notFoundBody = ProblemResponses.renderProblemBytes(org.pragmatica.http.HttpStatus.NOT_FOUND,
                                                               "No route found for " + context.method()
                                                              + " " + context.path(),
                                                               context.path(),
                                                               context.requestId());
        var notFound = new HttpResponseData(HttpStatus.NOT_FOUND.code(),
                                            Map.of("Content-Type", ProblemResponses.problemContentType()),
                                            notFoundBody);

        sendManagementForwardSuccess(network, request, ser, notFound);
    }

    private void sendManagementForwardSuccess(ClusterNetwork network,
                                              HttpForwardRequest request,
                                              Serializer ser,
                                              HttpResponseData responseData) {
        Result.lift1(ser::encode, responseData)
              .onSuccess(payload -> sendManagementForwardPayload(network, request, payload))
              .onFailure(cause -> sendManagementForwardError(network,
                                                             request,
                                                             "Response serialization failed: " + cause.message()));
    }

    private void sendManagementForwardPayload(ClusterNetwork network, HttpForwardRequest request, byte[] payload) {
        var forwardResponse = new HttpForwardResponse(nodeSupplier.get().self(),
                                                      request.correlationId(),
                                                      request.requestId(),
                                                      true,
                                                      payload,
                                                      Pipeline.MANAGEMENT);

        network.send(request.sender(), forwardResponse);
        log.trace("Sent management forward success response [{}]", request.requestId());
    }

    private void sendManagementForwardError(ClusterNetwork network, HttpForwardRequest request, String errorMessage) {
        log.warn("Management forward error [{}]: {}", request.requestId(), errorMessage);
        var forwardResponse = new HttpForwardResponse(nodeSupplier.get().self(),
                                                      request.correlationId(),
                                                      request.requestId(),
                                                      false,
                                                      errorMessage.getBytes(StandardCharsets.UTF_8),
                                                      Pipeline.MANAGEMENT);

        network.send(request.sender(), forwardResponse);
    }

    private static boolean isDashboardPath(String path) {
        return "/".equals(path) || "/index.html".equals(path) || path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/vendor/") || path.endsWith(".ico");
    }

    @SuppressWarnings("JBCT-PAT-01")
    private boolean handleProbeRequest(String path, ResponseWriter response) {
        if ("/health/live".equals(path)) {
            var liveness = statusRoutes.buildLivenessResponse();
            var httpStatus = "UP".equals(liveness.status())
                             ? HttpStatus.OK
                             : HttpStatus.SERVICE_UNAVAILABLE;

            writeProbeJson(response, liveness, httpStatus);

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
                       .onFailure(cause -> ProblemResponses.writeProblem(response,
                                                                         org.pragmatica.http.HttpStatus.INTERNAL_SERVER_ERROR,
                                                                         cause.message(),
                                                                         "/health",
                                                                         ""));
    }

    /// Spec event-stream-namespaces §6.1/§12.2: writes to `system:*` streams are forbidden over the
    /// HTTP surface regardless of caller role — the framework holds the only producer reference to
    /// system streams. The compile-time SPI split already blocks app code; this is the HTTP-path
    /// guard. It runs ahead of (and independent of) the role/auth pipeline so a `system:*` publish
    /// returns 405 Method Not Allowed even when management security is disabled.
    ///
    /// Identity resolution reuses the same canonicalization the dispatch path uses:
    /// [ManagementRoute#match] resolves the concrete route + params (the same call
    /// [#resolvePermission] makes for the same request), and the target engine key is derived the
    /// same way [StreamManager#engineKey] derives it for the real handler — a bare name for the
    /// legacy flat routes ([ManagementRoute#STREAM_PUBLISH]/[ManagementRoute#STREAM_DELETE]), a
    /// canonical `(namespace, stream, version)` [ResourceAddress] for the catalog-form routes. A key
    /// is forbidden iff it names one of [SystemStreams#ALL].
    ///
    /// [ManagementRoute#STREAM_CREATE] is deliberately excluded from
    /// [#STREAM_IDENTITY_WRITE_ROUTES]: its target name is a JSON body field (`StreamCreateRequest`),
    /// not a path param, so this pre-auth gate cannot see it without a second, parallel body parser —
    /// which condition 1 rules out (identity resolution must reuse the dispatch path's
    /// canonicalization, not grow a private one). The honest consequence: CREATE's protection is
    /// **not pre-auth**. It is a separate, handler-level guard —
    /// `StreamRoutes#createFreshStream` — that runs unconditionally as the first statement of the
    /// sole method that ever mints a stream, before any state change, with no early-return path
    /// around it. That guard exists in addition to (not instead of) `createStreamWithConfig`'s
    /// idempotent create-if-absent behavior: idempotency alone only protects a name collision
    /// *after* `SystemStreamBootstrap` has registered [SystemStreams#ALL] at cluster startup: a
    /// CREATE racing ahead of bootstrap would otherwise find `streamInfo(name)` empty and mint a
    /// caller-controlled config under a reserved name. The handler-level guard is pinned
    /// adversarially in `StreamRoutesCreateSystemStreamTest`, including against a caller with full
    /// privileges naming a framework stream in the body — auth level does not matter here because
    /// the check runs regardless of it.
    ///
    /// [ManagementRoute#CONSUMER_GROUP_JOIN]/[ManagementRoute#CONSUMER_GROUP_LEAVE] are a known,
    /// currently open gap, not covered here: their target stream name travels in the request body
    /// (`JoinGroupRequest`/`LeaveGroupRequest`), not the path, and this gate only inspects
    /// method+path. It closes once these routes gain path-resolvable identity per the catalog-form
    /// reshape (management-api-versioning-spec.md §3.3). Tracked separately from this gate's own
    /// gap list because whether it needs closing at all is still an open evidence question (does
    /// joining/leaving a consumer group on a framework stream actually mutate state, or is it
    /// merely untidy) — see the dedicated ticket cross-referencing #300.
    ///
    /// A route match whose params fail to resolve to a [ResourceAddress] (malformed namespace or
    /// version) fails closed — treated as forbidden, not passed through.
    private boolean rejectSystemStreamWrite(HttpRequest ctx, ResponseWriter response, String path, String methodName) {
        if (!isSystemStreamWriteOverHttp(methodName, path)) {
            return false;
        }

        ProblemResponses.writeProblem(response,
                                      org.pragmatica.http.HttpStatus.METHOD_NOT_ALLOWED,
                                      "Writes to system:* streams are not permitted over HTTP",
                                      path,
                                      ctx.requestId());

        return true;
    }

    /// Pure decision used by [#rejectSystemStreamWrite] — package-visible so the 405 gate can be
    /// unit-tested without standing up the HTTP pipeline. A request is a system-stream write iff it
    /// route-matches one of [#STREAM_IDENTITY_WRITE_ROUTES] and the resolved engine key names a
    /// framework stream (or the route matched but the params didn't resolve at all — fail closed).
    static boolean isSystemStreamWriteOverHttp(String methodName, String path) {
        return parseRoutingMethod(methodName).flatMap(m -> ManagementRoute.match(m, path).option())
                                             .filter(matched -> STREAM_IDENTITY_WRITE_ROUTES.contains(matched.route()))
                                             .map(ManagementServerImpl::isForbiddenWrite)
                                             .or(false);
    }

    private static boolean isForbiddenWrite(MatchedRoute matched) {
        return resolveEngineKey(matched).map(SystemStreams::isForbiddenEngineKey)
                                        .or(true);
    }

    /// Mirrors [StreamManager#engineKey]'s two-shape resolution off a [MatchedRoute]'s raw params
    /// instead of an already-built [ResourceAddress].
    private static Option<String> resolveEngineKey(MatchedRoute matched) {
        return switch (matched.route()) {
            case STREAM_PUBLISH, STREAM_DELETE -> matched.param("name");
            case STREAMS_PUBLISH, STREAMS_DELETE, STREAMS_GROUP_CREATE, STREAMS_GROUP_DELETE ->
                    matched.param("namespace")
                          .flatMap(ns -> matched.param("stream")
                                                .flatMap(stream -> matched.param("version")
                                                                          .flatMap(ver -> ResourceAddress.resourceAddress(ns, stream, ver)
                                                                                                         .option())))
                          .map(StreamManager::engineKey);
            default -> Option.empty();
        };
    }

    /// Identity-bearing write routes this pre-auth path gate covers — see
    /// [#rejectSystemStreamWrite]'s doc for why [ManagementRoute#STREAM_CREATE] (covered instead by
    /// a separate, post-auth, handler-level guard) and the `CONSUMER_GROUP_*` routes (an open gap)
    /// are excluded.
    private static final Set<ManagementRoute> STREAM_IDENTITY_WRITE_ROUTES = Set.of(ManagementRoute.STREAM_PUBLISH,
                                                                                    ManagementRoute.STREAM_DELETE,
                                                                                    ManagementRoute.STREAMS_PUBLISH,
                                                                                    ManagementRoute.STREAMS_DELETE,
                                                                                    ManagementRoute.STREAMS_GROUP_CREATE,
                                                                                    ManagementRoute.STREAMS_GROUP_DELETE);

    private Result<SecurityContext> validateManagementSecurity(HttpRequest ctx,
                                                               ResponseWriter response,
                                                               String path,
                                                               HttpMethod method) {
        var httpContext = toManagementRequestContext(ctx, path);
        var policy = SecurityPolicy.apiKeyRequired();
        var methodName = method.name();
        var permission = resolvePermission(methodName, path);

        return securityValidator.validate(httpContext, policy)
                                .flatMap(sc -> enforceAndAuditDenial(sc, permission, methodName, path))
                                .onFailure(cause -> handleManagementSecurityFailure(response,
                                                                                    cause,
                                                                                    path,
                                                                                    methodName,
                                                                                    ctx.requestId()))
                                .onSuccess(sc -> logManagementAccess(sc, methodName, path));
    }

    /// #299: per-operation authorization. Resolve the minimum role by EXACT route match
    /// ([ManagementRoute#match]) against the per-operation table, rather than coarse path-prefix
    /// containment. A path with no matching ManagementRoute (e.g. an unknown or future route) falls
    /// back to the prefix registry, which itself denies-by-default (ADMIN) for unrecognized mutations.
    private static RoutePermission resolvePermission(String methodName, String path) {
        return parseRoutingMethod(methodName).flatMap(m -> ManagementRoute.match(m, path).option())
                                 .map(matched -> ManagementRoutePermissions.permissionFor(matched.route()))
                                 .or(RoutePermissionRegistry.resolve(methodName, path));
    }

    private Result<SecurityContext> enforceAndAuditDenial(SecurityContext sc,
                                                          RoutePermission permission,
                                                          String method,
                                                          String path) {
        return RoleEnforcer.enforce(sc, permission).onFailure(_ -> auditAccessDenied(sc, method, path, permission));
    }

    private void auditAccessDenied(SecurityContext sc, String method, String path, RoutePermission permission) {
        var principal = sc.isAuthenticated()
                        ? sc.principal().value()
                        : "anonymous";
        var actualRole = sc.authorizationRole().name();
        var requiredRole = permission.minimumRole().name();

        AuditLog.accessDenied(principal, method, path, actualRole, requiredRole);
        nodeSupplier.get()
                    .route(OperationalEvent.AccessDenied.accessDenied(principal, method, path, actualRole, requiredRole));
    }

    private static void logManagementAccess(SecurityContext securityContext, String method, String path) {
        var principal = securityContext.isAuthenticated()
                        ? securityContext.principal().value()
                        : "anonymous";

        AuditLog.managementAccess("mgmt", principal, method, path);
    }

    private static HttpRequestContext toManagementRequestContext(HttpRequest ctx, String path) {
        return HttpRequestContext.httpRequestContext(path,
                                                     ctx.method().name(),
                                                     ctx.queryParams().asMap(),
                                                     ctx.headers().asMap(),
                                                     ctx.body(),
                                                     "mgmt");
    }

    @SuppressWarnings("JBCT-PAT-01")
    private void handleManagementSecurityFailure(ResponseWriter response,
                                                 Cause cause,
                                                 String path,
                                                 String method,
                                                 String requestId) {
        AuditLog.authFailure("mgmt", cause.message(), method, path);
        var status = resolveSecurityErrorStatus(cause);

        requestObserver.recordSecurityDenial(classifyDenialType(cause), method, path);
        if (status == HttpStatus.UNAUTHORIZED) {
            response.header("WWW-Authenticate", "ApiKey realm=\"Aether\"");
        }

        ProblemResponses.writeProblem(response, toRoutingStatus(status), cause.message(), path, requestId);
    }

    private static org.pragmatica.http.HttpStatus toRoutingStatus(HttpStatus status) {
        for (var s : org.pragmatica.http.HttpStatus.values()) {
            if (s.code() == status.code()) {
                return s;
            }
        }

        return org.pragmatica.http.HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private static String classifyDenialType(Cause cause) {
        return switch (cause) {
            case SecurityError.MissingCredentials _ -> "auth_failure";
            case SecurityError.InvalidCredentials _ -> "auth_failure";
            case SecurityError.AccessDenied _ -> "insufficient_role";
            case RoleEnforcer.AuthorizationError.AccessDenied _ -> "insufficient_role";
            default -> "auth_failure";
        };
    }

    private static HttpStatus resolveSecurityErrorStatus(Cause cause) {
        return switch (cause) {
            case SecurityError.MissingCredentials _ -> HttpStatus.UNAUTHORIZED;
            case SecurityError.InvalidCredentials _ -> HttpStatus.FORBIDDEN;
            case SecurityError.AccessDenied _ -> HttpStatus.FORBIDDEN;
            case RoleEnforcer.AuthorizationError.AccessDenied _ -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.UNAUTHORIZED;
        };
    }
}

@SuppressWarnings("JBCT-RET-01")
final class InstrumentedResponseWriter implements ResponseWriter {
    private final ResponseWriter delegate;
    private int statusCode;

    private InstrumentedResponseWriter(ResponseWriter delegate) {
        this.delegate = delegate;
    }

    static InstrumentedResponseWriter instrumentedResponseWriter(ResponseWriter delegate) {
        return new InstrumentedResponseWriter(delegate);
    }

    @Override
    public void write(org.pragmatica.http.HttpStatus status, byte[] body, org.pragmatica.http.ContentType contentType) {
        statusCode = status.code();
        delegate.write(status, body, contentType);
    }

    @Override
    public ResponseWriter header(String name, String value) {
        delegate.header(name, value);

        return this;
    }

    String statusCategory() {
        if (statusCode >= 500) {
            return "5xx";
        }

        if (statusCode >= 400) {
            return "4xx";
        }

        return "2xx";
    }
}
