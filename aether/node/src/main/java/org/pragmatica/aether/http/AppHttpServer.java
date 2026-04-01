package org.pragmatica.aether.http;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SecurityMode;
import org.pragmatica.aether.update.DeploymentManager;
import org.pragmatica.aether.update.DeploymentManager.ActiveRouting;
import org.pragmatica.aether.update.VersionRouting;
import org.pragmatica.aether.http.HttpRoutePublisher.LocalRouteInfo;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardRequest;
import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardResponse;
import org.pragmatica.aether.http.forward.HttpForwarder;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.http.handler.security.SecurityContextHolder;
import org.pragmatica.aether.http.security.AuditLog;
import org.pragmatica.aether.http.security.SecurityError;
import org.pragmatica.aether.http.security.SecurityValidator;
import org.pragmatica.aether.invoke.InvocationContext;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.metrics.observability.HttpRequestObserver;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.dht.MapSubscription;
import org.pragmatica.aether.slice.kvstore.AetherKey.HttpNodeRouteKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.HttpNodeRouteValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.topology.QuorumStateNotification;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.routing.HttpStatus;
import org.pragmatica.http.routing.ProblemDetail;
import org.pragmatica.http.server.HttpServer;
import org.pragmatica.http.server.HttpServerConfig;
import org.pragmatica.http.server.RequestContext;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.net.tcp.QuicSslContextFactory;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.channel.EventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;

/// Application HTTP server for cluster-wide HTTP routing.
///
///
/// Handles HTTP requests by:
/// <ol>
///   - Looking up routes locally via HttpRoutePublisher
///   - If not local, forwarding to remote nodes via HttpForwarder
/// </ol>
///
///
/// Separate from ManagementServer for security isolation.
@SuppressWarnings({"JBCT-RET-01", "JBCT-RET-03"})
public interface AppHttpServer {
    Promise<Unit> start();

    Promise<Unit> stop();

    /// Rotate TLS certificate by restarting HTTP servers with the new bundle.
    Promise<Unit> rotateCertificate(org.pragmatica.net.tcp.security.CertificateBundle newBundle);

    Option<Integer> boundPort();

    /// Handle KV-Store updates to rebuild router when routes change.
    @MessageReceiver void onRoutePut(ValuePut<HttpNodeRouteKey, HttpNodeRouteValue> valuePut);

    /// Handle KV-Store removals to rebuild router when routes change.
    @MessageReceiver void onRouteRemove(ValueRemove<HttpNodeRouteKey, HttpNodeRouteValue> valueRemove);

    /// Handle compound NodeRoutesKey put — triggers router rebuild.
    @SuppressWarnings("JBCT-RET-01") void onNodeRoutesPut(ValuePut<NodeRoutesKey, NodeRoutesValue> valuePut);

    /// Handle compound NodeRoutesKey remove — triggers router rebuild.
    @SuppressWarnings("JBCT-RET-01") void onNodeRoutesRemove(ValueRemove<NodeRoutesKey, NodeRoutesValue> valueRemove);

    /// Handle incoming HTTP forward request from another node.
    @MessageReceiver void onHttpForwardRequest(HttpForwardRequest request);

    /// Handle HTTP forward response from another node.
    @MessageReceiver void onHttpForwardResponse(HttpForwardResponse response);

    /// Trigger router rebuild (called when local slices deploy/undeploy).
    void rebuildRouter();

    /// Whether this server has received initial route synchronization from the KV store.
    /// Returns true if at least one route update has been processed, or if running in standalone mode.
    boolean isRouteReady();

    /// Handle quorum state changes. When quorum is established, mark route sync as complete
    /// because the KV store is operational — if no route entries exist, that IS the synced state.
    @SuppressWarnings("JBCT-RET-01") void onQuorumStateChange(QuorumStateNotification notification);

    /// Handle node removal for immediate retry of pending forwards.
    @MessageReceiver void onNodeRemoved(TopologyChangeNotification.NodeRemoved nodeRemoved);

    /// Handle node down for immediate retry of pending forwards.
    @MessageReceiver void onNodeDown(TopologyChangeNotification.NodeDown nodeDown);

    /// Get the HttpForwarder used by this server, if configured.
    Option<HttpForwarder> httpForwarder();

    /// Get the HTTP route publisher, if configured.
    /// Used to update security overrides when blueprints are published.
    Option<HttpRoutePublisher> httpRoutePublisher();

    /// Create a MapSubscription adapter for DHT events.
    default MapSubscription<HttpNodeRouteKey, HttpNodeRouteValue> asHttpRouteSubscription() {
        return new MapSubscription<>() {@Override@SuppressWarnings("JBCT-RET-01") public void onPut(HttpNodeRouteKey key, HttpNodeRouteValue value) {
            onRoutePut(new ValuePut<>(new KVCommand.Put<>(key, value), Option.none()));
        }@Override@SuppressWarnings("JBCT-RET-01") public void onRemove(HttpNodeRouteKey key) {
            onRouteRemove(new ValueRemove<>(new KVCommand.Remove<>(key), Option.none()));
        }};
    }

    static AppHttpServer appHttpServer(AppHttpConfig config,
                                       NodeId selfNodeId,
                                       HttpRouteRegistry routeRegistry,
                                       Option<HttpRoutePublisher> httpRoutePublisher,
                                       Option<ClusterNetwork> clusterNetwork,
                                       Option<Serializer> serializer,
                                       Option<Deserializer> deserializer,
                                       Option<TlsConfig> tls,
                                       Option<InvocationMetricsCollector> metricsCollector,
                                       Option<EventLoopGroup> bossGroup,
                                       Option<EventLoopGroup> workerGroup,
                                       Option<DeploymentManager> strategyCoordinator) {
        return appHttpServer(config,
                             selfNodeId,
                             routeRegistry,
                             httpRoutePublisher,
                             clusterNetwork,
                             serializer,
                             deserializer,
                             tls,
                             metricsCollector,
                             bossGroup,
                             workerGroup,
                             strategyCoordinator,
                             Option.empty());
    }

    static AppHttpServer appHttpServer(AppHttpConfig config,
                                       NodeId selfNodeId,
                                       HttpRouteRegistry routeRegistry,
                                       Option<HttpRoutePublisher> httpRoutePublisher,
                                       Option<ClusterNetwork> clusterNetwork,
                                       Option<Serializer> serializer,
                                       Option<Deserializer> deserializer,
                                       Option<TlsConfig> tls,
                                       Option<InvocationMetricsCollector> metricsCollector,
                                       Option<EventLoopGroup> bossGroup,
                                       Option<EventLoopGroup> workerGroup,
                                       Option<DeploymentManager> strategyCoordinator,
                                       Option<HttpRequestObserver> requestObserver) {
        return new AppHttpServerImpl(config,
                                     selfNodeId,
                                     routeRegistry,
                                     httpRoutePublisher,
                                     clusterNetwork,
                                     serializer,
                                     deserializer,
                                     tls,
                                     metricsCollector,
                                     bossGroup,
                                     workerGroup,
                                     strategyCoordinator,
                                     requestObserver);
    }
}

@SuppressWarnings({"JBCT-RET-01", "JBCT-RET-03"}) class AppHttpServerImpl implements AppHttpServer {
    private static final Logger log = LoggerFactory.getLogger(AppHttpServerImpl.class);

    private final AppHttpConfig config;
    private final NodeId selfNodeId;
    private final HttpRouteRegistry routeRegistry;
    private final Option<HttpRoutePublisher> httpRoutePublisher;
    private final Option<ClusterNetwork> clusterNetwork;
    private final Option<Serializer> serializer;
    private final Option<Deserializer> deserializer;
    private final SecurityValidator securityValidator;
    private final Option<TlsConfig> tls;
    private final Option<InvocationMetricsCollector> metricsCollector;
    private final Option<EventLoopGroup> bossGroup;
    private final Option<EventLoopGroup> workerGroup;
    private final Option<DeploymentManager> strategyCoordinator;
    private final Option<HttpRequestObserver> requestObserver;
    private final Option<HttpForwarder> httpForwarder;
    private final AtomicReference<HttpServer> serverRef = new AtomicReference<>();
    private final AtomicReference<HttpServer> h3ServerRef = new AtomicReference<>();
    private final AtomicReference<RouteTable> routeTableRef = new AtomicReference<>(RouteTable.empty());
    private final AtomicBoolean routeSyncReceived = new AtomicBoolean(false);

    AppHttpServerImpl(AppHttpConfig config,
                      NodeId selfNodeId,
                      HttpRouteRegistry routeRegistry,
                      Option<HttpRoutePublisher> httpRoutePublisher,
                      Option<ClusterNetwork> clusterNetwork,
                      Option<Serializer> serializer,
                      Option<Deserializer> deserializer,
                      Option<TlsConfig> tls,
                      Option<InvocationMetricsCollector> metricsCollector,
                      Option<EventLoopGroup> bossGroup,
                      Option<EventLoopGroup> workerGroup,
                      Option<DeploymentManager> strategyCoordinator,
                      Option<HttpRequestObserver> requestObserver) {
        this.config = config;
        this.selfNodeId = selfNodeId;
        this.routeRegistry = routeRegistry;
        this.httpRoutePublisher = httpRoutePublisher;
        this.clusterNetwork = clusterNetwork;
        this.serializer = serializer;
        this.deserializer = deserializer;
        this.securityValidator = buildSecurityValidator(config);
        this.tls = tls;
        this.metricsCollector = metricsCollector;
        this.bossGroup = bossGroup;
        this.workerGroup = workerGroup;
        this.strategyCoordinator = strategyCoordinator;
        this.requestObserver = requestObserver;
        this.httpForwarder = buildHttpForwarder(selfNodeId,
                                                routeRegistry,
                                                clusterNetwork,
                                                serializer,
                                                deserializer,
                                                config);
    }

    private static SecurityValidator buildSecurityValidator(AppHttpConfig config) {
        return switch (config.securityMode()) {case API_KEY -> SecurityValidator.apiKeyValidator(config.apiKeys());case JWT -> config.jwtConfig().map(SecurityValidator::jwtValidator)
                                                                                                                                               .or(SecurityValidator.noOpValidator());case NONE -> SecurityValidator.noOpValidator();};
    }

    private static Option<HttpForwarder> buildHttpForwarder(NodeId selfNodeId,
                                                            HttpRouteRegistry routeRegistry,
                                                            Option<ClusterNetwork> clusterNetwork,
                                                            Option<Serializer> serializer,
                                                            Option<Deserializer> deserializer,
                                                            AppHttpConfig config) {
        return clusterNetwork.flatMap(net -> serializer.flatMap(ser -> deserializer.map(des -> HttpForwarder.httpForwarder(selfNodeId,
                                                                                                                           routeRegistry,
                                                                                                                           net,
                                                                                                                           ser,
                                                                                                                           des,
                                                                                                                           config.forwardTimeout()))));
    }

    @Override public Option<HttpForwarder> httpForwarder() {
        return httpForwarder;
    }

    @Override public Option<HttpRoutePublisher> httpRoutePublisher() {
        return httpRoutePublisher;
    }

    @Override public Promise<Unit> start() {
        if ( !config.enabled()) {
            log.info("App HTTP server is disabled");
            return Promise.success(unit());
        }
        log.info("Starting App HTTP server on port {} (protocol: {})", config.port(), config.httpProtocol());
        rebuildRouter();
        var protocol = config.httpProtocol();
        if ( protocol.includesH1()) {
        return startH1Server().flatMap(_ -> protocol.includesH3()
                                           ? startH3Server()
                                           : Promise.success(unit()));}
        return startH3Server();
    }

    private Promise<Unit> startH1Server() {
        var serverConfig = buildServerConfig();
        java.util.function.BiConsumer<org.pragmatica.http.server.RequestContext, org.pragmatica.http.server.ResponseWriter> handler = config.httpProtocol() == HttpProtocol.BOTH
                                                                                                                                      ? this::handleRequestWithAltSvc
                                                                                                                                      : this::handleRequest;
        var serverPromise = bossGroup.flatMap(bg -> workerGroup.map(wg -> HttpServer.httpServer(serverConfig,
                                                                                                handler,
                                                                                                bg,
                                                                                                wg)))
        .or(HttpServer.httpServer(serverConfig, handler));
        return serverPromise.map(this::registerStartedH1Server)
        .onFailure(cause -> log.error("Failed to start App HTTP server on port {}: {}", config.port(), cause.message()));
    }

    private Promise<Unit> startH3Server() {
        var quicTls = tls.map(QuicSslContextFactory::createServer).or(QuicSslContextFactory.createSelfSignedServer());
        return quicTls.onFailure(cause -> log.error("Failed to create QUIC SSL context: {}",
                                                    cause.message())).map(this::startH3WithSslContext)
                                .or(Promise.success(unit()));
    }

    private Promise<Unit> startH3WithSslContext(io.netty.handler.codec.quic.QuicSslContext quicSslContext) {
        var serverConfig = HttpServerConfig.httpServerConfig("app-http-h3", config.port())
        .withMaxContentLength(config.maxRequestSize());
        var serverPromise = workerGroup.map(wg -> HttpServer.http3Server(serverConfig,
                                                                         quicSslContext,
                                                                         this::handleRequest,
                                                                         wg))
        .or(HttpServer.http3Server(serverConfig, quicSslContext, this::handleRequest));
        return serverPromise.map(this::registerStartedH3Server)
        .onFailure(cause -> log.error("Failed to start App HTTP/3 server on port {}: {}", config.port(), cause.message()));
    }

    private void handleRequestWithAltSvc(org.pragmatica.http.server.RequestContext request,
                                         org.pragmatica.http.server.ResponseWriter response) {
        response.header("Alt-Svc", "h3=\":" + config.port() + "\"; ma=3600");
        handleRequest(request, response);
    }

    private HttpServerConfig buildServerConfig() {
        var serverConfig = HttpServerConfig.httpServerConfig("app-http", config.port())
        .withMaxContentLength(config.maxRequestSize());
        return tls.fold(() -> serverConfig, serverConfig::withTls);
    }

    private Unit registerStartedH1Server(HttpServer server) {
        serverRef.set(server);
        log.info("App HTTP server started on port {} (HTTP/1.1)", server.port());
        return unit();
    }

    private Unit registerStartedH3Server(HttpServer server) {
        h3ServerRef.set(server);
        log.info("App HTTP server started on port {} (HTTP/3 QUIC)", server.port());
        return unit();
    }

    @Override public Promise<Unit> stop() {
        var h1Stop = Option.option(serverRef.get()).map(server -> server.stop()
        .onSuccessRun(() -> log.info("App HTTP/1.1 server stopped")))
                                  .or(Promise.success(unit()));
        var h3Stop = Option.option(h3ServerRef.get()).map(server -> server.stop()
        .onSuccessRun(() -> log.info("App HTTP/3 server stopped")))
                                  .or(Promise.success(unit()));
        return h1Stop.flatMap(_ -> h3Stop);
    }

    @Override public Promise<Unit> rotateCertificate(org.pragmatica.net.tcp.security.CertificateBundle newBundle) {
        if ( !config.enabled()) {
        return Promise.success(unit());}
        log.info("Rotating app HTTP server TLS certificate");
        return stopHttpServers().flatMap(_ -> restartWithNewBundle(newBundle));
    }

    private Promise<Unit> stopHttpServers() {
        var h1Stop = Option.option(serverRef.getAndSet(null)).map(HttpServer::stop)
                                  .or(Promise.success(unit()));
        var h3Stop = Option.option(h3ServerRef.getAndSet(null)).map(HttpServer::stop)
                                  .or(Promise.success(unit()));
        return h1Stop.flatMap(_ -> h3Stop);
    }

    @SuppressWarnings("JBCT-PAT-01") // Lifecycle: rebuild TLS from bundle and restart
    private Promise<Unit> restartWithNewBundle(org.pragmatica.net.tcp.security.CertificateBundle newBundle) {
        var newTlsConfig = buildTlsFromBundle(newBundle);
        var protocol = config.httpProtocol();
        if ( protocol.includesH1()) {
        return restartH1WithTls(newTlsConfig).flatMap(_ -> protocol.includesH3()
                                                          ? restartH3WithBundle(newBundle)
                                                          : Promise.success(unit()));}
        return restartH3WithBundle(newBundle);
    }

    private Promise<Unit> restartH1WithTls(Option<TlsConfig> newTls) {
        var serverConfig = HttpServerConfig.httpServerConfig("app-http", config.port())
        .withMaxContentLength(config.maxRequestSize());
        var finalConfig = newTls.map(serverConfig::withTls).or(serverConfig);
        java.util.function.BiConsumer<RequestContext, ResponseWriter> handler = config.httpProtocol() == HttpProtocol.BOTH
                                                                                ? this::handleRequestWithAltSvc
                                                                                : this::handleRequest;
        var serverPromise = bossGroup.flatMap(bg -> workerGroup.map(wg -> HttpServer.httpServer(finalConfig,
                                                                                                handler,
                                                                                                bg,
                                                                                                wg)))
        .or(HttpServer.httpServer(finalConfig, handler));
        return serverPromise.map(this::registerStartedH1Server).onSuccess(_ -> log.info("App HTTP/1.1 server restarted with new certificate"))
                                .onFailure(cause -> log.error("Failed to restart app HTTP/1.1 server: {}",
                                                              cause.message()));
    }

    private Promise<Unit> restartH3WithBundle(org.pragmatica.net.tcp.security.CertificateBundle newBundle) {
        var quicTls = QuicSslContextFactory.createServerFromBundle(newBundle);
        return quicTls.map(this::startH3WithSslContext).onFailure(cause -> log.error("Failed to create QUIC SSL context for app server rotation: {}",
                                                                                     cause.message()))
                          .or(Promise.success(unit()));
    }

    private static Option<TlsConfig> buildTlsFromBundle(org.pragmatica.net.tcp.security.CertificateBundle newBundle) {
        var identity = new TlsConfig.Identity.FromProvider(newBundle.certificatePem(), newBundle.privateKeyPem());
        var trust = new TlsConfig.Trust.FromCaBytes(newBundle.caCertificatePem());
        return Option.some(new TlsConfig.Server(identity, Option.some(trust)));
    }

    @Override public Option<Integer> boundPort() {
        return Option.option(serverRef.get()).map(HttpServer::port);
    }

    // ================== Router Rebuild ==================
    @Override public void rebuildRouter() {
        var localRoutes = httpRoutePublisher.map(HttpRoutePublisher::allLocalRoutes).or(Set.of());
        var localIdentities = localRoutes.stream().map(HttpNodeRouteKey::routeIdentity)
                                                .collect(java.util.stream.Collectors.toSet());
        var remoteRoutes = routeRegistry.allRoutes().stream()
                                                  .filter(route -> !localIdentities.contains(route.httpMethod() + ":" + route.pathPrefix()))
                                                  .toList();
        var newTable = RouteTable.routeTable(localRoutes, remoteRoutes);
        routeTableRef.set(newTable);
        log.debug("Router rebuilt: {} local routes, {} remote routes", localRoutes.size(), remoteRoutes.size());
    }

    @Override public boolean isRouteReady() {
        return routeSyncReceived.get() || httpRoutePublisher.isEmpty();
    }

    @Override public void onQuorumStateChange(QuorumStateNotification notification) {
        if ( notification.state() == QuorumStateNotification.State.ESTABLISHED) {
            routeSyncReceived.set(true);
            log.info("Quorum established — marking route sync complete");
        }
    }

    @Override public void onRoutePut(ValuePut<HttpNodeRouteKey, HttpNodeRouteValue> valuePut) {
        routeSyncReceived.set(true);
        log.debug("HttpNodeRouteKey added, rebuilding router");
        rebuildRouter();
    }

    @Override public void onRouteRemove(ValueRemove<HttpNodeRouteKey, HttpNodeRouteValue> valueRemove) {
        routeSyncReceived.set(true);
        log.debug("HttpNodeRouteKey removed, rebuilding router");
        rebuildRouter();
    }

    @Override public void onNodeRoutesPut(ValuePut<NodeRoutesKey, NodeRoutesValue> valuePut) {
        routeSyncReceived.set(true);
        log.debug("NodeRoutesKey added, rebuilding router");
        rebuildRouter();
    }

    @Override public void onNodeRoutesRemove(ValueRemove<NodeRoutesKey, NodeRoutesValue> valueRemove) {
        routeSyncReceived.set(true);
        log.debug("NodeRoutesKey removed, rebuilding router");
        rebuildRouter();
    }

    // ================== Request Handling ==================
    private void handleRequest(RequestContext request, ResponseWriter response) {
        var requestId = request.requestId();
        // Set request ID in invocation context for the entire request handling scope
        InvocationContext.runWithRequestId(requestId, () -> handleRequestInScope(request, response, requestId));
    }

    private void handleRequestInScope(RequestContext request, ResponseWriter response, String requestId) {
        var method = request.method().name();
        var path = request.path();
        log.trace("Received {} {} [{}]", method, path, requestId);
        var routeTable = routeTableRef.get();
        var normalizedPath = normalizePath(path);
        // Health endpoint — always returns 200 (no route table dependency)
        if ( isHealthEndpoint(normalizedPath)) {
            sendHealthResponse(response, requestId);
            return;
        }
        // Route lookup FIRST — then security based on per-route policy
        var effectivePolicy = resolveEffectivePolicy(method, normalizedPath, routeTable);
        // If policy requires auth but no validator is configured (SecurityMode=NONE), reject immediately
        if ( requiresAuthentication(effectivePolicy) && config.securityMode() == SecurityMode.NONE) {
            handleSecurityFailure(response, SecurityError.NO_VALIDATOR_CONFIGURED, path, requestId, method);
            return;
        }
        var httpContext = toHttpRequestContext(request, requestId);
        securityValidator.validate(httpContext, effectivePolicy).flatMap(ctx -> enforceRoleIfRequired(ctx,
                                                                                                      effectivePolicy))
                                  .apply(cause -> handleSecurityFailure(response, cause, path, requestId, method),
                                         ctx -> dispatchAuthenticated(request,
                                                                      response,
                                                                      routeTable,
                                                                      ctx,
                                                                      method,
                                                                      normalizedPath,
                                                                      path,
                                                                      requestId));
    }

    /// Resolves the effective security policy for a request.
    /// Per-route policy ALWAYS wins over global SecurityMode.
    /// SecurityMode is the fallback for routes without explicit security (defaulting to PUBLIC).
    private SecurityPolicy resolveEffectivePolicy(String method, String normalizedPath, RouteTable routeTable) {
        var routePolicy = findRouteSecurityPolicy(method, normalizedPath, routeTable);
        return routePolicy.or(globalSecurityPolicy());
    }

    private Option<SecurityPolicy> findRouteSecurityPolicy(String method,
                                                           String normalizedPath,
                                                           RouteTable routeTable) {
        // Try local route first
        var localPolicy = httpRoutePublisher.flatMap(pub -> pub.findLocalRoute(method, normalizedPath)).map(LocalRouteInfo::security)
                                                    .filter(AppHttpServerImpl::isExplicitPolicy);
        if ( localPolicy.isPresent()) {
        return localPolicy;}
        // Try remote route — security is carried in RouteInfo
        return findMatchingRemoteRoute(routeTable.remoteRoutes(),
                                       method,
                                       normalizedPath).map(route -> SecurityPolicy.fromString(route.security()))
                                      .filter(AppHttpServerImpl::isExplicitPolicy);
    }

    private static boolean isExplicitPolicy(SecurityPolicy policy) {
        return ! (policy instanceof SecurityPolicy.Public());
    }

    private SecurityPolicy globalSecurityPolicy() {
        return switch (config.securityMode()) {case API_KEY -> SecurityPolicy.apiKeyRequired();case JWT -> SecurityPolicy.bearerTokenRequired();case NONE -> SecurityPolicy.publicRoute();};
    }

    private static boolean requiresAuthentication(SecurityPolicy policy) {
        return ! (policy instanceof SecurityPolicy.Public());
    }

    private static Result<SecurityContext> enforceRoleIfRequired(SecurityContext context, SecurityPolicy policy) {
        if ( policy instanceof SecurityPolicy.RoleRequired(var roleName)) {
        return context.hasRole(roleName)
               ? Result.success(context)
               : new SecurityError.InsufficientRole("Access denied: role '" + roleName + "' required").result();}
        return Result.success(context);
    }

    @SuppressWarnings("JBCT-RET-01")
    private void dispatchAuthenticated(RequestContext request,
                                       ResponseWriter response,
                                       RouteTable routeTable,
                                       SecurityContext securityContext,
                                       String method,
                                       String normalizedPath,
                                       String path,
                                       String requestId) {
        var principal = securityContext.principal().value();
        if ( config.securityEnabled()) {
        AuditLog.authSuccess(requestId, principal, method, path);}
        ScopedValue.where(SecurityContextHolder.scopedValue(), securityContext)
        .run(() -> InvocationContext.runWithContext(requestId,
                                                    principal,
                                                    selfNodeId.id(),
                                                    0,
                                                    true,
                                                    () -> dispatchToRoute(request,
                                                                          response,
                                                                          routeTable,
                                                                          method,
                                                                          normalizedPath,
                                                                          requestId)));
    }

    @SuppressWarnings("JBCT-PAT-01") // Dispatcher method — decomposition would scatter routing logic
    private void dispatchToRoute(RequestContext request,
                                 ResponseWriter response,
                                 RouteTable routeTable,
                                 String method,
                                 String normalizedPath,
                                 String requestId) {
        // Try local route first
        if ( httpRoutePublisher.isPresent()) {
            var localRouteOpt = findMatchingLocalRoute(routeTable.localRoutes(), method, normalizedPath);
            if ( localRouteOpt.isPresent()) {
                // Check deployment strategy routing before serving locally
                if ( shouldForwardForStrategy(localRouteOpt.unwrap(), method, normalizedPath, routeTable)) {
                    var remoteRouteOpt = findMatchingRemoteRoute(routeTable.remoteRoutes(), method, normalizedPath);
                    if ( remoteRouteOpt.isPresent()) {
                        log.debug("Deployment strategy routing — forwarding {} {} to remote [{}]",
                                  method,
                                  normalizedPath,
                                  requestId);
                        handleRemoteRoute(request, response, remoteRouteOpt.unwrap(), requestId);
                        return;
                    }
                }
                handleLocalRoute(request, response, localRouteOpt.unwrap(), requestId);
                return;
            }
        }
        // Try remote route
        var remoteRouteOpt = findMatchingRemoteRoute(routeTable.remoteRoutes(), method, normalizedPath);
        if ( remoteRouteOpt.isPresent()) {
            handleRemoteRoute(request, response, remoteRouteOpt.unwrap(), requestId);
            return;
        }
        // No route found — distinguish between "not synced yet" and "genuinely missing"
        if ( !routeSyncReceived.get() && httpRoutePublisher.isPresent()) {
            log.debug("Route not yet available for {} {} [{}] — node starting, routes not synchronized",
                      method,
                      request.path(),
                      requestId);
            sendProblem(response,
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Node starting, routes not yet synchronized",
                        request.path(),
                        requestId);
        } else























        {
            log.warn("No route found for {} {} [{}]", method, request.path(), requestId);
            sendProblem(response,
                        HttpStatus.NOT_FOUND,
                        "No route found for " + method + " " + request.path(),
                        request.path(),
                        requestId);
        }
    }

    // ================== Deployment Strategy Routing ==================
    @SuppressWarnings("JBCT-PAT-01")
    private boolean shouldForwardForStrategy(HttpNodeRouteKey localRouteKey,
                                             String method,
                                             String normalizedPath,
                                             RouteTable routeTable) {
        if ( strategyCoordinator.isEmpty()) {
        return false;}
        var localRouteInfo = httpRoutePublisher.flatMap(pub -> pub.findLocalRoute(method, normalizedPath));
        if ( localRouteInfo.isEmpty()) {
        return false;}
        var artifactResult = Artifact.artifact(localRouteInfo.unwrap().artifactCoord());
        if ( artifactResult.isFailure()) {
        return false;}
        var artifact = artifactResult.unwrap();
        var routingOpt = strategyCoordinator.unwrap().activeRouting(artifact.base());
        if ( routingOpt.isEmpty()) {
        return false;}
        return evaluateRoutingDecision(artifact, routingOpt.unwrap(), method, normalizedPath, routeTable);
    }

    private boolean evaluateRoutingDecision(Artifact artifact,
                                            ActiveRouting activeRouting,
                                            String method,
                                            String normalizedPath,
                                            RouteTable routeTable) {
        var routing = activeRouting.routing();
        var localVersion = artifact.version();
        // If all traffic goes to one version, deterministic decision
        if ( routing.isAllOld()) {
        return localVersion.equals(activeRouting.newVersion()) && hasMatchingRemoteRoute(routeTable.remoteRoutes(),
                                                                                         method,
                                                                                         normalizedPath);}
        if ( routing.isAllNew()) {
        return localVersion.equals(activeRouting.oldVersion()) && hasMatchingRemoteRoute(routeTable.remoteRoutes(),
                                                                                         method,
                                                                                         normalizedPath);}
        // Weighted random decision
        return evaluateWeightedRouting(localVersion, activeRouting, routing, method, normalizedPath, routeTable);
    }

    private boolean evaluateWeightedRouting(Version localVersion,
                                            ActiveRouting activeRouting,
                                            VersionRouting routing,
                                            String method,
                                            String normalizedPath,
                                            RouteTable routeTable) {
        boolean localIsNew = localVersion.equals(activeRouting.newVersion());
        int random = ThreadLocalRandom.current().nextInt(routing.totalWeight());
        boolean shouldRouteToNew = random < routing.newWeight();
        // Forward only if weighted decision says "route to other version" and a remote route exists
        boolean shouldForward = localIsNew
                                ? !shouldRouteToNew
                                : shouldRouteToNew;
        return shouldForward && hasMatchingRemoteRoute(routeTable.remoteRoutes(), method, normalizedPath);
    }

    private boolean hasMatchingRemoteRoute(List<HttpRouteRegistry.RouteInfo> remoteRoutes,
                                           String method,
                                           String normalizedPath) {
        return findMatchingRemoteRoute(remoteRoutes, method, normalizedPath).isPresent();
    }

    @SuppressWarnings("JBCT-PAT-01")
    private void handleSecurityFailure(ResponseWriter response,
                                       Cause cause,
                                       String path,
                                       String requestId,
                                       String method) {
        var statusAndMessage = mapSecurityError(cause);
        AuditLog.authFailure(requestId, cause.message(), method, path);
        requestObserver.onPresent(obs -> obs.recordSecurityDenial(classifyDenialType(cause), method, path));
        if ( statusAndMessage.status() == HttpStatus.UNAUTHORIZED) {
        addAuthenticateHeader(response);}
        sendProblem(response, statusAndMessage.status(), statusAndMessage.clientMessage(), path, requestId);
    }

    private static String classifyDenialType(Cause cause) {
        if ( cause == SecurityError.NO_VALIDATOR_CONFIGURED) {
        return "no_validator";}
        return switch (cause) {case SecurityError.InsufficientRole _ -> "insufficient_role";case SecurityError.AccessDenied _ -> "insufficient_role";default -> "auth_failure";};
    }

    private record SecurityStatusMessage(HttpStatus status, String clientMessage){}

    private static SecurityStatusMessage mapSecurityError(Cause cause) {
        return switch (cause) {case SecurityError.MissingCredentials mc -> new SecurityStatusMessage(HttpStatus.UNAUTHORIZED,
                                                                                                     mc.message());case SecurityError.TokenExpired _ -> new SecurityStatusMessage(HttpStatus.UNAUTHORIZED,
                                                                                                                                                                                  "Token expired");case SecurityError.SignatureInvalid _, SecurityError.IssuerMismatch _, SecurityError.AudienceMismatch _, SecurityError.KeyNotFound _ -> new SecurityStatusMessage(HttpStatus.FORBIDDEN,
                                                                                                                                                                                                                                                                                                                                                                     "Invalid token");case SecurityError.InvalidCredentials _ -> new SecurityStatusMessage(HttpStatus.FORBIDDEN,
                                                                                                                                                                                                                                                                                                                                                                                                                                                           "Invalid credentials");case SecurityError.InsufficientRole _ -> new SecurityStatusMessage(HttpStatus.FORBIDDEN,
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     cause.message());case SecurityError.JwksFetchFailed _ -> new SecurityStatusMessage(HttpStatus.UNAUTHORIZED,
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        "Authentication temporarily unavailable");default -> new SecurityStatusMessage(HttpStatus.UNAUTHORIZED,
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       "Authentication failed");};
    }

    private void addAuthenticateHeader(ResponseWriter response) {
        switch ( config.securityMode()) {
            case JWT -> response.header("WWW-Authenticate", "Bearer realm=\"Aether\"");
            case API_KEY -> response.header("WWW-Authenticate", "ApiKey realm=\"Aether\"");
            case NONE -> {}
        }
    }

    private Option<HttpNodeRouteKey> findMatchingLocalRoute(Set<HttpNodeRouteKey> localRoutes,
                                                            String method,
                                                            String normalizedPath) {
        return Option.from(localRoutes.stream().filter(key -> key.httpMethod().equalsIgnoreCase(method))
                                             .filter(key -> pathMatchesPrefix(normalizedPath,
                                                                              key.pathPrefix()))
                                             .findFirst());
    }

    private Option<HttpRouteRegistry.RouteInfo> findMatchingRemoteRoute(List<HttpRouteRegistry.RouteInfo> remoteRoutes,
                                                                        String method,
                                                                        String normalizedPath) {
        return Option.from(remoteRoutes.stream().filter(route -> route.httpMethod().equalsIgnoreCase(method))
                                              .filter(route -> pathMatchesPrefix(normalizedPath,
                                                                                 route.pathPrefix()))
                                              .findFirst());
    }

    private boolean pathMatchesPrefix(String normalizedPath, String pathPrefix) {
        var normalizedPrefix = normalizePath(pathPrefix);
        return normalizedPath.equals(normalizedPrefix) ||
        (normalizedPath.length() > normalizedPrefix.length() &&
        normalizedPath.startsWith(normalizedPrefix));
    }

    @SuppressWarnings("JBCT-NULL-01") // Adapter boundary: path may be null from external HTTP request parsing
    private String normalizePath(String path) {
        if ( path == null || path.isBlank()) {
        return "/";}
        var normalized = path.strip();
        if ( !normalized.startsWith("/")) {
        normalized = "/" + normalized;}
        if ( !normalized.endsWith("/")) {
        normalized = normalized + "/";}
        return normalized;
    }

    // ================== Health Endpoint ==================
    private static boolean isHealthEndpoint(String normalizedPath) {
        return "/health/".equals(normalizedPath);
    }

    private void sendHealthResponse(ResponseWriter response, String requestId) {
        var body = "{\"status\":\"UP\",\"nodeId\":\"" + selfNodeId.id() + "\"}";
        response.header(ResponseWriter.X_REQUEST_ID, requestId).header("X-Node-Id",
                                                                       selfNodeId.id())
                       .write(org.pragmatica.http.HttpStatus.OK,
                              body.getBytes(StandardCharsets.UTF_8),
                              CommonContentType.APPLICATION_JSON);
    }

    // ================== Local Route Handling ==================
    private void handleLocalRoute(RequestContext request,
                                  ResponseWriter response,
                                  HttpNodeRouteKey routeKey,
                                  String requestId) {
        log.trace("Handling local route {} {} [{}]", routeKey.httpMethod(), routeKey.pathPrefix(), requestId);
        httpRoutePublisher.flatMap(pub -> pub.findLocalRouter(routeKey.httpMethod(),
                                                              routeKey.pathPrefix())).onEmpty(() -> handleMissingLocalRouter(response,
                                                                                                                             request.path(),
                                                                                                                             routeKey,
                                                                                                                             requestId))
                                  .onPresent(router -> invokeLocalRouter(request, response, router, routeKey, requestId));
    }

    private void handleMissingLocalRouter(ResponseWriter response,
                                          String path,
                                          HttpNodeRouteKey routeKey,
                                          String requestId) {
        log.error("Local router not found for route {} [{}]", routeKey, requestId);
        sendProblem(response, HttpStatus.INTERNAL_SERVER_ERROR, "Local router not found", path, requestId);
    }

    private void invokeLocalRouter(RequestContext request,
                                   ResponseWriter response,
                                   SliceRouter router,
                                   HttpNodeRouteKey routeKey,
                                   String requestId) {
        var context = toHttpRequestContext(request, requestId);
        var routeInfo = resolveRouteInfo(routeKey.httpMethod(), routeKey.pathPrefix());
        var startTime = System.nanoTime();
        routeInfo.onPresent(info -> recordMetricsStart(info));
        router.handle(context).onSuccess(responseData -> handleLocalRouterSuccess(response,
                                                                                  responseData,
                                                                                  requestId,
                                                                                  routeInfo,
                                                                                  startTime,
                                                                                  context))
                     .onFailure(cause -> handleLocalRouterFailure(response,
                                                                  request.path(),
                                                                  requestId,
                                                                  cause,
                                                                  routeInfo,
                                                                  startTime,
                                                                  context));
    }

    private void handleLocalRouterSuccess(ResponseWriter response,
                                          HttpResponseData responseData,
                                          String requestId,
                                          Option<ResolvedRoute> routeInfo,
                                          long startTime,
                                          HttpRequestContext context) {
        routeInfo.onPresent(info -> recordMetricsSuccess(info,
                                                         startTime,
                                                         context.body().length,
                                                         responseData.body().length));
        sendResponse(response, responseData, requestId);
    }

    private void handleLocalRouterFailure(ResponseWriter response,
                                          String path,
                                          String requestId,
                                          Cause cause,
                                          Option<ResolvedRoute> routeInfo,
                                          long startTime,
                                          HttpRequestContext context) {
        routeInfo.onPresent(info -> recordMetricsFailure(info, startTime, context.body().length, cause));
        handleLocalRouteFailure(response, path, requestId, cause);
    }

    private void handleLocalRouteFailure(ResponseWriter response, String path, String requestId, Cause cause) {
        log.error("Failed to handle local route [{}]: {}", requestId, cause.message());
        sendProblem(response,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Request processing failed: " + cause.message(),
                    path,
                    requestId);
    }

    private HttpRequestContext toHttpRequestContext(RequestContext request, String requestId) {
        return HttpRequestContext.httpRequestContext(request.path(),
                                                     request.method().name(),
                                                     request.queryParams().asMap(),
                                                     request.headers().asMap(),
                                                     request.body(),
                                                     requestId);
    }

    // ================== Remote Route Handling (delegated to HttpForwarder) ==================
    private void handleRemoteRoute(RequestContext request,
                                   ResponseWriter response,
                                   HttpRouteRegistry.RouteInfo route,
                                   String requestId) {
        log.trace("Handling remote route {} {} -> {} nodes [{}]",
                  route.httpMethod(),
                  route.pathPrefix(),
                  route.nodes().size(),
                  requestId);
        if ( httpForwarder.isEmpty()) {
            log.error("HTTP forwarding not configured [{}]", requestId);
            sendProblem(response,
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "HTTP forwarding not available",
                        request.path(),
                        requestId);
            return;
        }
        var context = toHttpRequestContext(request, requestId);
        httpForwarder.unwrap().forward(context,
                                       route.httpMethod(),
                                       route.pathPrefix(),
                                       requestId)
                            .onSuccess(responseData -> sendResponse(response, responseData, requestId))
                            .onFailure(cause -> sendProblem(response,
                                                            HttpStatus.GATEWAY_TIMEOUT,
                                                            cause.message(),
                                                            request.path(),
                                                            requestId));
    }

    // ================== Forward Message Handlers ==================
    @Override public void onHttpForwardRequest(HttpForwardRequest request) {
        log.trace("Received HttpForwardRequest [{}] correlationId={}", request.requestId(), request.correlationId());
        if ( deserializer.isEmpty() || serializer.isEmpty() || clusterNetwork.isEmpty()) {
            log.error("[{}] Cannot handle forward request - missing dependencies", request.requestId());
            return;
        }
        var des = deserializer.unwrap();
        var ser = serializer.unwrap();
        var network = clusterNetwork.unwrap();
        // Deserialize the request context
        Result.<HttpRequestContext, byte[]>lift1(des::decode,
                                                 request.requestData())
              .onFailure(cause -> logAndSendForwardError(network, request, "Deserialization failed", cause))
              .onSuccess(context -> dispatchForwardedRequest(context, request, network, ser));
    }

    private void logAndSendForwardError(ClusterNetwork network,
                                        HttpForwardRequest request,
                                        String prefix,
                                        Cause cause) {
        log.error("{} [{}]: {}", prefix, request.requestId(), cause.message());
        sendForwardError(network, request, prefix + ": " + cause.message());
    }

    @SuppressWarnings("JBCT-RET-01")
    private void dispatchForwardedRequest(HttpRequestContext context,
                                          HttpForwardRequest request,
                                          ClusterNetwork network,
                                          Serializer ser) {
        var method = context.method();
        var path = context.path();
        var normalizedPath = normalizePath(path);
        var routerOpt = httpRoutePublisher.flatMap(pub -> findLocalRouterForPath(pub, method, normalizedPath));
        if ( routerOpt.isEmpty()) {
            log.warn("No local router for forwarded request {} {} [{}]", method, path, request.requestId());
            sendForwardError(network, request, "Route not found locally");
            return;
        }
        var routeInfo = resolveRouteInfo(method, normalizedPath);
        var startTime = System.nanoTime();
        routeInfo.onPresent(this::recordMetricsStart);
        routerOpt.unwrap().handle(context)
                        .onSuccess(responseData -> handleForwardSuccess(network,
                                                                        request,
                                                                        ser,
                                                                        responseData,
                                                                        routeInfo,
                                                                        startTime,
                                                                        context))
                        .onFailure(cause -> handleForwardFailure(network, request, cause, routeInfo, startTime, context));
    }

    private void handleForwardSuccess(ClusterNetwork network,
                                      HttpForwardRequest request,
                                      Serializer ser,
                                      HttpResponseData responseData,
                                      Option<ResolvedRoute> routeInfo,
                                      long startTime,
                                      HttpRequestContext context) {
        routeInfo.onPresent(info -> recordMetricsSuccess(info,
                                                         startTime,
                                                         context.body().length,
                                                         responseData.body().length));
        sendForwardSuccess(network, request, ser, responseData);
    }

    private void handleForwardFailure(ClusterNetwork network,
                                      HttpForwardRequest request,
                                      Cause cause,
                                      Option<ResolvedRoute> routeInfo,
                                      long startTime,
                                      HttpRequestContext context) {
        routeInfo.onPresent(info -> recordMetricsFailure(info, startTime, context.body().length, cause));
        sendForwardError(network, request, cause.message());
    }

    private Option<SliceRouter> findLocalRouterForPath(HttpRoutePublisher pub,
                                                       String method,
                                                       String normalizedPath) {
        return findMatchingLocalRoute(pub.allLocalRoutes(), method, normalizedPath)
        .flatMap(key -> pub.findLocalRouter(key.httpMethod(), key.pathPrefix()));
    }

    private void sendForwardSuccess(ClusterNetwork network,
                                    HttpForwardRequest request,
                                    Serializer ser,
                                    HttpResponseData responseData) {
        Result.lift1(ser::encode, responseData).onSuccess(payload -> sendForwardPayload(network, request, payload))
                    .onFailure(cause -> logAndSendForwardError(network, request, "Response serialization failed", cause));
    }

    private void sendForwardPayload(ClusterNetwork network,
                                    HttpForwardRequest request,
                                    byte[] payload) {
        var forwardResponse = new HttpForwardResponse(selfNodeId,
                                                      request.correlationId(),
                                                      request.requestId(),
                                                      true,
                                                      payload);
        network.send(request.sender(), forwardResponse);
        log.trace("Sent forward success response [{}]", request.requestId());
    }

    private void sendForwardError(ClusterNetwork network,
                                  HttpForwardRequest request,
                                  String errorMessage) {
        var forwardResponse = new HttpForwardResponse(selfNodeId,
                                                      request.correlationId(),
                                                      request.requestId(),
                                                      false,
                                                      errorMessage.getBytes(StandardCharsets.UTF_8));
        network.send(request.sender(), forwardResponse);
        log.trace("Sent forward error response [{}]: {}", request.requestId(), errorMessage);
    }

    @Override public void onHttpForwardResponse(HttpForwardResponse response) {
        httpForwarder.onPresent(fwd -> fwd.onHttpForwardResponse(response));
    }

    @Override public void onNodeRemoved(TopologyChangeNotification.NodeRemoved nodeRemoved) {
        httpForwarder.onPresent(fwd -> fwd.onNodeRemoved(nodeRemoved));
    }

    @Override public void onNodeDown(TopologyChangeNotification.NodeDown nodeDown) {
        httpForwarder.onPresent(fwd -> fwd.onNodeDown(nodeDown));
    }

    // ================== Response Helpers ==================
    private static final JsonMapper JSON_MAPPER = JsonMapper.defaultJsonMapper();
    private static final org.pragmatica.http.ContentType CONTENT_TYPE_PROBLEM = org.pragmatica.http.ContentType.contentType("application/problem+json",
                                                                                                                            org.pragmatica.http.ContentCategory.JSON);
    private static final Map<Integer, org.pragmatica.http.HttpStatus> STATUS_MAP = Map.ofEntries(Map.entry(200,
                                                                                                           org.pragmatica.http.HttpStatus.OK),
                                                                                                 Map.entry(201,
                                                                                                           org.pragmatica.http.HttpStatus.CREATED),
                                                                                                 Map.entry(202,
                                                                                                           org.pragmatica.http.HttpStatus.ACCEPTED),
                                                                                                 Map.entry(204,
                                                                                                           org.pragmatica.http.HttpStatus.NO_CONTENT),
                                                                                                 Map.entry(301,
                                                                                                           org.pragmatica.http.HttpStatus.MOVED_PERMANENTLY),
                                                                                                 Map.entry(302,
                                                                                                           org.pragmatica.http.HttpStatus.FOUND),
                                                                                                 Map.entry(304,
                                                                                                           org.pragmatica.http.HttpStatus.NOT_MODIFIED),
                                                                                                 Map.entry(307,
                                                                                                           org.pragmatica.http.HttpStatus.TEMPORARY_REDIRECT),
                                                                                                 Map.entry(308,
                                                                                                           org.pragmatica.http.HttpStatus.PERMANENT_REDIRECT),
                                                                                                 Map.entry(400,
                                                                                                           org.pragmatica.http.HttpStatus.BAD_REQUEST),
                                                                                                 Map.entry(401,
                                                                                                           org.pragmatica.http.HttpStatus.UNAUTHORIZED),
                                                                                                 Map.entry(403,
                                                                                                           org.pragmatica.http.HttpStatus.FORBIDDEN),
                                                                                                 Map.entry(404,
                                                                                                           org.pragmatica.http.HttpStatus.NOT_FOUND),
                                                                                                 Map.entry(405,
                                                                                                           org.pragmatica.http.HttpStatus.METHOD_NOT_ALLOWED),
                                                                                                 Map.entry(409,
                                                                                                           org.pragmatica.http.HttpStatus.CONFLICT),
                                                                                                 Map.entry(422,
                                                                                                           org.pragmatica.http.HttpStatus.UNPROCESSABLE_ENTITY),
                                                                                                 Map.entry(429,
                                                                                                           org.pragmatica.http.HttpStatus.TOO_MANY_REQUESTS),
                                                                                                 Map.entry(500,
                                                                                                           org.pragmatica.http.HttpStatus.INTERNAL_SERVER_ERROR),
                                                                                                 Map.entry(501,
                                                                                                           org.pragmatica.http.HttpStatus.NOT_IMPLEMENTED),
                                                                                                 Map.entry(502,
                                                                                                           org.pragmatica.http.HttpStatus.BAD_GATEWAY),
                                                                                                 Map.entry(503,
                                                                                                           org.pragmatica.http.HttpStatus.SERVICE_UNAVAILABLE),
                                                                                                 Map.entry(504,
                                                                                                           org.pragmatica.http.HttpStatus.GATEWAY_TIMEOUT));

    private void sendProblem(ResponseWriter response,
                             HttpStatus status,
                             String detail,
                             String instance,
                             String requestId) {
        var problem = ProblemDetail.problemDetail(status, detail, instance, requestId);
        JSON_MAPPER.writeAsString(problem).onSuccess(json -> response.header(ResponseWriter.X_REQUEST_ID, requestId).header("X-Node-Id",
                                                                                                                            selfNodeId.id())
                                                                            .write(toServerStatus(status.code()),
                                                                                   json.getBytes(StandardCharsets.UTF_8),
                                                                                   CONTENT_TYPE_PROBLEM))
                                 .onFailure(cause -> handleProblemSerializationFailure(response,
                                                                                       status,
                                                                                       requestId,
                                                                                       cause));
    }

    private void handleProblemSerializationFailure(ResponseWriter response,
                                                   HttpStatus status,
                                                   String requestId,
                                                   Cause cause) {
        log.error("[{}] Failed to serialize ProblemDetail: {}", requestId, cause.message());
        sendPlainError(response, status, requestId);
    }

    private static final int MAX_WARN_BODY_LENGTH = 200;

    private void sendResponse(ResponseWriter response, HttpResponseData responseData, String requestId) {
        if ( responseData.statusCode() >= 400) {
            var fullBody = new String(responseData.body(), StandardCharsets.UTF_8);
            var truncatedBody = fullBody.length() > MAX_WARN_BODY_LENGTH
                                ? fullBody.substring(0, MAX_WARN_BODY_LENGTH) + "...(truncated)"
                                : fullBody;
            log.warn("HTTP error response [{}]: {} body={}", requestId, responseData.statusCode(), truncatedBody);
            log.debug("HTTP error response full body [{}]: {}", requestId, fullBody);
        } else























        {
        log.trace("Sending response [{}]: {} {}", requestId, responseData.statusCode(), responseData.headers());}
        var writer = response.header(ResponseWriter.X_REQUEST_ID, requestId).header("X-Node-Id", selfNodeId.id());
        for ( var entry : responseData.headers().entrySet()) {
        writer = writer.header(entry.getKey(), entry.getValue());}
        var contentType = Option.option(responseData.headers().get("Content-Type")).map(ct -> org.pragmatica.http.ContentType.contentType(ct,
                                                                                                                                          org.pragmatica.http.ContentCategory.JSON))
                                       .or(CommonContentType.APPLICATION_JSON);
        writer.write(toServerStatus(responseData.statusCode()), responseData.body(), contentType);
    }

    private void sendPlainError(ResponseWriter response, HttpStatus status, String requestId) {
        var content = "{\"error\":\"" + status.message() + "\"}";
        response.header(ResponseWriter.X_REQUEST_ID, requestId).header("X-Node-Id",
                                                                       selfNodeId.id())
                       .write(toServerStatus(status.code()),
                              content.getBytes(StandardCharsets.UTF_8),
                              CommonContentType.APPLICATION_JSON);
    }

    private static org.pragmatica.http.HttpStatus toServerStatus(int code) {
        return Option.option(STATUS_MAP.get(code)).or(org.pragmatica.http.HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ================== Invocation Metrics ==================
    /// Resolved artifact and method name for metrics recording.
    record ResolvedRoute(Artifact artifact, MethodName method){}

    private Option<ResolvedRoute> resolveRouteInfo(String httpMethod, String path) {
        return metricsCollector.flatMap(_ -> httpRoutePublisher.flatMap(pub -> pub.findLocalRoute(httpMethod, path))
        .flatMap(this::toResolvedRoute));
    }

    private Option<ResolvedRoute> toResolvedRoute(LocalRouteInfo info) {
        return Artifact.artifact(info.artifactCoord()).flatMap(artifact -> MethodName.methodName(info.sliceMethod())
        .map(method -> new ResolvedRoute(artifact, method)))
                                .option();
    }

    private void recordMetricsStart(ResolvedRoute route) {
        metricsCollector.onPresent(mc -> mc.recordStart(route.artifact(), route.method()));
    }

    private void recordMetricsSuccess(ResolvedRoute route, long startTime, int requestBytes, int responseBytes) {
        var durationNs = System.nanoTime() - startTime;
        metricsCollector.onPresent(mc -> recordSuccessMetrics(mc, route, durationNs, requestBytes, responseBytes));
    }

    private void recordSuccessMetrics(InvocationMetricsCollector mc,
                                      ResolvedRoute route,
                                      long durationNs,
                                      int requestBytes,
                                      int responseBytes) {
        mc.recordComplete(route.artifact(), route.method());
        mc.recordSuccess(route.artifact(), route.method(), durationNs, requestBytes, responseBytes);
    }

    private void recordMetricsFailure(ResolvedRoute route, long startTime, int requestBytes, Cause cause) {
        var durationNs = System.nanoTime() - startTime;
        var errorType = cause.getClass().getSimpleName();
        metricsCollector.onPresent(mc -> recordFailureMetrics(mc, route, durationNs, requestBytes, errorType));
    }

    private void recordFailureMetrics(InvocationMetricsCollector mc,
                                      ResolvedRoute route,
                                      long durationNs,
                                      int requestBytes,
                                      String errorType) {
        mc.recordComplete(route.artifact(), route.method());
        mc.recordFailure(route.artifact(), route.method(), durationNs, requestBytes, errorType);
    }

    // ================== Route Table ==================
    /// Snapshot of current route state for thread-safe access.
    record RouteTable(Set<HttpNodeRouteKey> localRoutes,
                      List<HttpRouteRegistry.RouteInfo> remoteRoutes) {
        static RouteTable empty() {
            return new RouteTable(Set.of(), List.of());
        }

        static RouteTable routeTable(Set<HttpNodeRouteKey> localRoutes,
                                     List<HttpRouteRegistry.RouteInfo> remoteRoutes) {
            return new RouteTable(Set.copyOf(localRoutes), List.copyOf(remoteRoutes));
        }
    }
}
