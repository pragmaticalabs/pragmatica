// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.pragmatica.aether.slice.RateGuardError;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SecurityMode;
import org.pragmatica.aether.config.TimeoutsConfig.ForwardingTimeouts;
import org.pragmatica.aether.http.fsm.AppHttpContext;
import org.pragmatica.aether.http.fsm.AppHttpEvents;
import org.pragmatica.aether.http.fsm.AppHttpState;
import org.pragmatica.aether.update.DeploymentManager;
import org.pragmatica.aether.update.DeploymentManager.ActiveRouting;
import org.pragmatica.aether.update.VersionRouting;
import org.pragmatica.aether.http.HttpRoutePublisher.LocalRouteInfo;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardRequest;
import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardResponse;
import org.pragmatica.aether.http.forward.AccessibilityFilter;
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
import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.aether.dht.MapSubscription;
import org.pragmatica.aether.slice.kvstore.AetherKey.HttpNodeRouteKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.HttpNodeRouteValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.TransportObservation;
import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.ProblemDetail;
import org.pragmatica.http.server.HttpServer;
import org.pragmatica.http.server.HttpServerConfig;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Deadline;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.net.tcp.ClientAuthPolicy;
import org.pragmatica.net.tcp.QuicSslContextFactory;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.net.tcp.security.CertificateBundle;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.statemachine.Fsm;

import io.netty.channel.EventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;


public interface AppHttpServer {
    Promise<Unit> start();
    Promise<Unit> stop();
    Promise<Unit> rotateCertificate(CertificateBundle newBundle);
    Option<Integer> boundPort();

    @MessageReceiver
    @Contract
    void onRoutePut(ValuePut<HttpNodeRouteKey, HttpNodeRouteValue> valuePut);

    @MessageReceiver
    @Contract
    void onRouteRemove(ValueRemove<HttpNodeRouteKey, HttpNodeRouteValue> valueRemove);

    @Contract
    void onNodeRoutesPut(ValuePut<NodeRoutesKey, NodeRoutesValue> valuePut);

    @Contract
    void onNodeRoutesRemove(ValueRemove<NodeRoutesKey, NodeRoutesValue> valueRemove);

    @MessageReceiver
    @Contract
    void onHttpForwardRequest(HttpForwardRequest request);

    @MessageReceiver
    @Contract
    void onHttpForwardResponse(HttpForwardResponse response);

    @Contract
    void rebuildRouter();

    boolean isRouteReady();

    @Contract
    void onQuorumStateChange(ClusterStateNotification notification);

    @MessageReceiver
    @Contract
    void onNodeRemoved(MembershipDecision.NodeRemoved nodeRemoved);

    @MessageReceiver
    @Contract
    void onNodeDecommissioned(MembershipDecision.NodeDecommissioned nodeDecommissioned);

    // Self-shutdown cleanup hook: kept on TransportObservation stream because self-shutdown is not a cluster decision.
    @MessageReceiver
    @Contract
    void onSelfShutdown(TransportObservation.SelfShutdown selfShutdown);

    Option<HttpForwarder> httpForwarder();
    Option<HttpRoutePublisher> httpRoutePublisher();

    default MapSubscription<HttpNodeRouteKey, HttpNodeRouteValue> asHttpRouteSubscription() {
        return new MapSubscription<>() {
            @Override
            @Contract
            public void onPut(HttpNodeRouteKey key, HttpNodeRouteValue value) {
                onRoutePut(new ValuePut<>(new KVCommand.Put<>(key, value), Option.none()));
            }

            @Override
            @Contract
            public void onRemove(HttpNodeRouteKey key) {
                onRouteRemove(new ValueRemove<>(new KVCommand.Remove<>(key), Option.none()));
            }
        };
    }

    static AppHttpServer appHttpServer(AppHttpConfig config,
                                       ForwardingTimeouts forwardingTimeouts,
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
                             forwardingTimeouts,
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
                             Option.empty(),
                             Option.empty());
    }

    static AppHttpServer appHttpServer(AppHttpConfig config,
                                       ForwardingTimeouts forwardingTimeouts,
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
        return appHttpServer(config,
                             forwardingTimeouts,
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
                             requestObserver,
                             Option.empty());
    }

    static AppHttpServer appHttpServer(AppHttpConfig config,
                                       ForwardingTimeouts forwardingTimeouts,
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
                                       Option<HttpRequestObserver> requestObserver,
                                       Option<Fn1<Result<NodeId>, TaskGroup>> taskGroupOwnerResolver) {
        return appHttpServer(config,
                             forwardingTimeouts,
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
                             requestObserver,
                             taskGroupOwnerResolver,
                             AccessibilityFilter.IDENTITY);
    }

    static AppHttpServer appHttpServer(AppHttpConfig config,
                                       ForwardingTimeouts forwardingTimeouts,
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
                                       Option<HttpRequestObserver> requestObserver,
                                       Option<Fn1<Result<NodeId>, TaskGroup>> taskGroupOwnerResolver,
                                       AccessibilityFilter accessibilityFilter) {
        return new AppHttpServerAdapter(config,
                                        forwardingTimeouts,
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
                                        requestObserver,
                                        taskGroupOwnerResolver,
                                        accessibilityFilter);
    }
}

class AppHttpServerAdapter implements AppHttpServer {
    private static final Logger log = LoggerFactory.getLogger(AppHttpServerAdapter.class);

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
    private final AppHttpContext context;
    private final TimeSpan requestBudget;
    private volatile boolean quorumEstablished;
    private final AtomicLong routeNotReadyRejections = new AtomicLong();

    AppHttpServerAdapter(AppHttpConfig config,
                         ForwardingTimeouts forwardingTimeouts,
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
                         Option<HttpRequestObserver> requestObserver,
                         Option<Fn1<Result<NodeId>, TaskGroup>> taskGroupOwnerResolver,
                         AccessibilityFilter accessibilityFilter) {
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
                                                forwardingTimeouts,
                                                taskGroupOwnerResolver,
                                                accessibilityFilter);
        this.context = buildContext(selfNodeId, this::computeRouteTable, () -> quorumEstablished);
        this.requestBudget = forwardingTimeouts.requestBudget();
    }

    private record FsmAndContext(Fsm<AppHttpState, ClusterFsmEvent> fsm, AppHttpContext context) {}

    private static AppHttpContext buildContext(NodeId selfNodeId,
                                               java.util.function.Supplier<RouteTable> routeTableSupplier,
                                               java.util.function.BooleanSupplier quorumEstablishedSupplier) {
        return buildFsmAndContext(selfNodeId, routeTableSupplier, quorumEstablishedSupplier).context();
    }

    private static FsmAndContext buildFsmAndContext(NodeId selfNodeId,
                                                    java.util.function.Supplier<RouteTable> routeTableSupplier,
                                                    java.util.function.BooleanSupplier quorumEstablishedSupplier) {
        var ctxHolder = new AtomicReference<AppHttpContext>();
        Function<Fsm<AppHttpState, ClusterFsmEvent>, AppHttpState> initialStateFactory = fsm -> buildContextAndStopped(fsm,
                                                                                                                       ctxHolder,
                                                                                                                       routeTableSupplier,
                                                                                                                       quorumEstablishedSupplier);
        var fsm = Fsm.fsm("app-http", selfNodeId.id(), initialStateFactory);

        return new FsmAndContext(fsm, ctxHolder.get());
    }

    private static AppHttpState buildContextAndStopped(Fsm<AppHttpState, ClusterFsmEvent> fsm,
                                                       AtomicReference<AppHttpContext> ctxHolder,
                                                       java.util.function.Supplier<RouteTable> routeTableSupplier,
                                                       java.util.function.BooleanSupplier quorumEstablishedSupplier) {
        var ctx = new AppHttpContext(fsm, routeTableSupplier, quorumEstablishedSupplier);

        ctxHolder.set(ctx);

        return ctx.stopped();
    }

    private static SecurityValidator buildSecurityValidator(AppHttpConfig config) {
        return switch (config.securityMode()) {
            case API_KEY -> SecurityValidator.apiKeyValidator(config.apiKeys());
            case JWT -> config.jwtConfig().map(SecurityValidator::jwtValidator).or(SecurityValidator.permitAllValidator());
            case NONE -> SecurityValidator.permitAllValidator();
        };
    }

    private static Option<HttpForwarder> buildHttpForwarder(NodeId selfNodeId,
                                                            HttpRouteRegistry routeRegistry,
                                                            Option<ClusterNetwork> clusterNetwork,
                                                            Option<Serializer> serializer,
                                                            Option<Deserializer> deserializer,
                                                            ForwardingTimeouts forwardingTimeouts,
                                                            Option<Fn1<Result<NodeId>, TaskGroup>> taskGroupOwnerResolver,
                                                            AccessibilityFilter accessibilityFilter) {
        var resolver = taskGroupOwnerResolver.or(HttpForwarder.UNASSIGNED_RESOLVER);

        return clusterNetwork.flatMap(net -> serializer.flatMap(ser -> deserializer.map(des -> HttpForwarder.httpForwarder(selfNodeId,
                                                                                                                           routeRegistry,
                                                                                                                           net,
                                                                                                                           ser,
                                                                                                                           des,
                                                                                                                           forwardingTimeouts.appTimeout(),
                                                                                                                           forwardingTimeouts.retryDelay()
                                                                                                                                             .millis(),
                                                                                                                           forwardingTimeouts.maxRetries(),
                                                                                                                           java.util.Set::of,
                                                                                                                           resolver,
                                                                                                                           HttpForwarder.NO_LEADER_RESOLVER,
                                                                                                                           accessibilityFilter))));
    }

    @Override
    public Option<HttpForwarder> httpForwarder() {
        return httpForwarder;
    }

    @Override
    public Option<HttpRoutePublisher> httpRoutePublisher() {
        return httpRoutePublisher;
    }

    @Override
    public Promise<Unit> start() {
        if (!config.enabled()) {
            log.info("App HTTP server is disabled");

            return Promise.success(unit());
        }

        verifyRouteTableSupplierWired(routeRegistry, context);
        log.info("Starting App HTTP server on port {} (protocol: {})", config.port(), config.httpProtocol());
        context.dispatch(new AppHttpEvents.StartRequested());
        var protocol = config.httpProtocol();
        var startPromise = protocol.includesH1()
                           ? startH1Server().flatMap(_ -> protocol.includesH3()
                                                          ? startH3Server()
                                                          : Promise.success(unit()))
                           : startH3Server();

        return startPromise.onSuccess(_ -> onStartCompleted());
    }

    @Contract
    @SuppressWarnings("JBCT-EX-01")
    static void verifyRouteTableSupplierWired(HttpRouteRegistry routeRegistry, AppHttpContext context) {
        java.util.Objects.requireNonNull(routeRegistry, "routeRegistry must be wired before AppHttpServer.start()");
        java.util.Objects.requireNonNull(context, "AppHttpContext must be wired before AppHttpServer.start()");
        java.util.Objects.requireNonNull(context.routeTableSupplier(),
                                         "context.routeTableSupplier must be wired before AppHttpServer.start()");
        if (context.routeTableSupplier() == AppHttpContext.EMPTY_ROUTE_TABLE_SENTINEL) {
            throw new IllegalStateException("AppHttpServer.start(): routeTableSupplier is the default "
                                           + "RouteTable::empty sentinel — RouteRegistry wiring missed");
        }
    }

    private void onStartCompleted() {
        publishRouteTable();
    }

    private Promise<Unit> startH1Server() {
        var serverConfig = buildServerConfig();
        java.util.function.BiConsumer<org.pragmatica.http.HttpRequest, org.pragmatica.http.server.ResponseWriter> handler = config.httpProtocol() == HttpProtocol.BOTH
                                                                                                                            ? this::handleRequestWithAltSvc
                                                                                                                            : this::handleRequest;
        var serverPromise = bossGroup.flatMap(bg -> workerGroup.map(wg -> HttpServer.httpServer(serverConfig,
                                                                                                handler,
                                                                                                bg,
                                                                                                wg)))
                                     .or(HttpServer.httpServer(serverConfig, handler));

        return serverPromise.map(this::registerStartedH1Server)
                            .onFailure(cause -> log.error("Failed to start App HTTP server on port {}: {}",
                                                          config.port(),
                                                          cause.message()));
    }

    private Promise<Unit> startH3Server() {
        var quicTls = tls.map(cfg -> QuicSslContextFactory.createServer(cfg, ClientAuthPolicy.NOT_REQUESTED))
                         .or(QuicSslContextFactory.createSelfSignedServer());

        return quicTls.onFailure(cause -> log.error("Failed to create QUIC SSL context: {}",
                                                    cause.message()))
                      .map(this::startH3WithSslContext)
                      .or(Promise::unitPromise)
                      .recover(AppHttpServerAdapter::logH3DisabledAndReturnUnit);
    }

    private static Unit logH3DisabledAndReturnUnit(Cause cause) {
        log.warn("H3 disabled after start failure, continuing H1-only: {}", cause.message());

        return unit();
    }

    private Promise<Unit> startH3WithSslContext(io.netty.handler.codec.quic.QuicSslContext quicSslContext) {
        var serverConfig = HttpServerConfig.httpServerConfig("app-http-h3",
                                                             config.port())
                                           .withMaxContentLength(config.maxRequestSize());
        var serverPromise = workerGroup.map(wg -> HttpServer.http3Server(serverConfig,
                                                                         quicSslContext,
                                                                         this::handleRequest,
                                                                         wg))
                                       .or(HttpServer.http3Server(serverConfig, quicSslContext, this::handleRequest));

        return serverPromise.map(this::registerStartedH3Server)
                            .onFailure(cause -> log.error("Failed to start App HTTP/3 server on port {}: {}",
                                                          config.port(),
                                                          cause.message()));
    }

    private void handleRequestWithAltSvc(org.pragmatica.http.HttpRequest request,
                                         org.pragmatica.http.server.ResponseWriter response) {
        response.header("Alt-Svc", "h3=\":" + config.port() + "\"; ma=3600");
        handleRequest(request, response);
    }

    private HttpServerConfig buildServerConfig() {
        var serverConfig = HttpServerConfig.httpServerConfig("app-http",
                                                             config.port())
                                           .withMaxContentLength(config.maxRequestSize());

        return tls.map(serverConfig::withTls)
                  .or(serverConfig);
    }

    private Unit registerStartedH1Server(HttpServer server) {
        log.info("App HTTP server started on port {} (HTTP/1.1)", server.port());
        context.dispatch(new AppHttpEvents.H1Ready(server));

        return unit();
    }

    private Unit registerStartedH3Server(HttpServer server) {
        log.info("App HTTP server started on port {} (HTTP/3 QUIC)", server.port());
        context.dispatch(new AppHttpEvents.H3Ready(server));

        return unit();
    }

    @Override
    public Promise<Unit> stop() {
        var servers = context.currentServers();

        context.dispatch(new AppHttpEvents.StopRequested());

        return context.stopServersAsync(servers.server(),
                                        servers.h3())
                      .onSuccessRun(() -> log.info("App HTTP server stopped"));
    }

    @Override
    public Promise<Unit> rotateCertificate(CertificateBundle newBundle) {
        if (!config.enabled()) {
            return Promise.success(unit());
        }

        log.info("Rotating app HTTP server TLS certificate");
        var previous = context.currentServers();
        var currentRoutes = context.currentRoutes();

        context.dispatch(new AppHttpEvents.CertRotationRequested(newBundle));

        return context.stopServersAsync(previous.server(),
                                        previous.h3())
                      .flatMap(_ -> restartWithNewBundle(newBundle))
                      .onSuccess(pair -> context.dispatch(new AppHttpEvents.CertRotationApplied(pair.server(),
                                                                                                pair.h3(),
                                                                                                currentRoutes)))
                      .mapToUnit();
    }

    private Promise<AppHttpContext.ServerPair> restartWithNewBundle(CertificateBundle newBundle) {
        var protocol = config.httpProtocol();

        if (protocol.includesH1() && protocol.includesH3()) {
            return restartDualWithBundle(newBundle);
        }

        if (protocol.includesH1()) {
            return restartH1OnlyWithBundle(newBundle);
        }

        return restartH3OnlyWithBundle(newBundle);
    }

    private Promise<AppHttpContext.ServerPair> restartDualWithBundle(CertificateBundle newBundle) {
        var newTlsConfig = buildTlsFromBundle(newBundle);

        return restartH1WithTls(newTlsConfig).flatMap(serverOpt -> restartH3WithBundle(newBundle).map(h3Opt -> new AppHttpContext.ServerPair(serverOpt,
                                                                                                                                             h3Opt)));
    }

    private Promise<AppHttpContext.ServerPair> restartH1OnlyWithBundle(CertificateBundle newBundle) {
        var newTlsConfig = buildTlsFromBundle(newBundle);

        return restartH1WithTls(newTlsConfig).map(serverOpt -> new AppHttpContext.ServerPair(serverOpt,
                                                                                             Option.<HttpServer> none()));
    }

    private Promise<AppHttpContext.ServerPair> restartH3OnlyWithBundle(CertificateBundle newBundle) {
        return restartH3WithBundle(newBundle).map(h3Opt -> new AppHttpContext.ServerPair(Option.<HttpServer> none(),
                                                                                         h3Opt));
    }

    private Promise<Option<HttpServer>> restartH1WithTls(Option<TlsConfig> newTls) {
        var serverConfig = HttpServerConfig.httpServerConfig("app-http",
                                                             config.port())
                                           .withMaxContentLength(config.maxRequestSize());
        var finalConfig = newTls.map(serverConfig::withTls).or(serverConfig);
        java.util.function.BiConsumer<HttpRequest, ResponseWriter> handler = config.httpProtocol() == HttpProtocol.BOTH
                                                                             ? this::handleRequestWithAltSvc
                                                                             : this::handleRequest;
        var serverPromise = bossGroup.flatMap(bg -> workerGroup.map(wg -> HttpServer.httpServer(finalConfig,
                                                                                                handler,
                                                                                                bg,
                                                                                                wg)))
                                     .or(HttpServer.httpServer(finalConfig, handler));

        return serverPromise.map(Option::some)
                            .onSuccessRun(() -> log.info("App HTTP/1.1 server restarted with new certificate"))
                            .onFailure(cause -> log.error("Failed to restart app HTTP/1.1 server: {}",
                                                          cause.message()));
    }

    private Promise<Option<HttpServer>> restartH3WithBundle(CertificateBundle newBundle) {
        var quicTls = QuicSslContextFactory.createServerFromBundle(newBundle, ClientAuthPolicy.NOT_REQUESTED);

        return quicTls.onFailure(cause -> log.error("Failed to create QUIC SSL context for app server rotation: {}",
                                                    cause.message()))
                      .map(this::startH3WithSslContextReturningServer)
                      .or(() -> Promise.success(Option.<HttpServer> none()))
                      .recover(AppHttpServerAdapter::logH3RotationDisabledAndReturnNone);
    }

    private static Option<HttpServer> logH3RotationDisabledAndReturnNone(Cause cause) {
        log.warn("H3 rotation disabled after bind failure, keeping previous state: {}", cause.message());

        return Option.none();
    }

    private Promise<Option<HttpServer>> startH3WithSslContextReturningServer(io.netty.handler.codec.quic.QuicSslContext quicSslContext) {
        var serverConfig = HttpServerConfig.httpServerConfig("app-http-h3",
                                                             config.port())
                                           .withMaxContentLength(config.maxRequestSize());
        var serverPromise = workerGroup.map(wg -> HttpServer.http3Server(serverConfig,
                                                                         quicSslContext,
                                                                         this::handleRequest,
                                                                         wg))
                                       .or(HttpServer.http3Server(serverConfig, quicSslContext, this::handleRequest));

        return serverPromise.map(Option::some);
    }

    private static Option<TlsConfig> buildTlsFromBundle(CertificateBundle newBundle) {
        var identity = new TlsConfig.Identity.FromProvider(newBundle.certificatePem(), newBundle.privateKeyPem());
        var trust = new TlsConfig.Trust.FromCaBytes(newBundle.caCertificatePem());

        return Option.some(new TlsConfig.Server(identity, Option.some(trust)));
    }

    @Override
    public Option<Integer> boundPort() {
        return context.boundPort();
    }

    @Override
    @Contract
    public void rebuildRouter() {
        publishRouteTable();
    }

    @Contract
    private void publishRouteTable() {
        context.publishRouteTable();
    }

    private RouteTable computeRouteTable() {
        var localRoutes = httpRoutePublisher.map(HttpRoutePublisher::allLocalRoutes).or(Set.of());
        var localIdentities = localRoutes.stream()
                                         .map(HttpNodeRouteKey::routeIdentity)
                                         .collect(java.util.stream.Collectors.toSet());
        var remoteRoutes = routeRegistry.allRoutes()
                                        .stream()
                                        .filter(route -> !localIdentities.contains(route.httpMethod()
                                                                                  + ":" + route.pathPrefix()))
                                        .toList();

        return RouteTable.routeTable(localRoutes, remoteRoutes);
    }

    @Override
    public boolean isRouteReady() {
        return context.isRouteReady() || httpRoutePublisher.isEmpty();
    }

    @Override
    @Contract
    public void onQuorumStateChange(ClusterStateNotification notification) {
        var established = notification.state() == ClusterStateNotification.State.ACTIVE;

        quorumEstablished = established;
        if (established) {
            log.info("Quorum established — marking route sync complete");
            context.dispatch(new ClusterFsmEvent.QuorumEstablished());
            publishRouteTable();
        } else {
            log.warn("Quorum disappeared — quiescing app HTTP routing (split-brain protection)");
            context.dispatch(new ClusterFsmEvent.QuorumDisappeared());
        }
    }

    @Override
    @Contract
    public void onRoutePut(ValuePut<HttpNodeRouteKey, HttpNodeRouteValue> valuePut) {
        log.debug("HttpNodeRouteKey added, rebuilding router");
        publishRouteTable();
    }

    @Override
    @Contract
    public void onRouteRemove(ValueRemove<HttpNodeRouteKey, HttpNodeRouteValue> valueRemove) {
        log.debug("HttpNodeRouteKey removed, rebuilding router");
        publishRouteTable();
    }

    @Override
    @Contract
    public void onNodeRoutesPut(ValuePut<NodeRoutesKey, NodeRoutesValue> valuePut) {
        log.debug("NodeRoutesKey added, rebuilding router");
        publishRouteTable();
    }

    @Override
    @Contract
    public void onNodeRoutesRemove(ValueRemove<NodeRoutesKey, NodeRoutesValue> valueRemove) {
        log.debug("NodeRoutesKey removed, rebuilding router");
        publishRouteTable();
    }

    private void handleRequest(HttpRequest request, ResponseWriter response) {
        var requestId = request.requestId();

        InvocationContext.runWithRequestId(requestId, () -> handleRequestInScope(request, response, requestId));
    }

    private void handleRequestInScope(HttpRequest request, ResponseWriter response, String requestId) {
        var method = request.method().name();
        var path = request.path();

        log.trace("Received {} {} [{}]", method, path, requestId);
        var routeTable = context.currentRoutes();
        var normalizedPath = normalizePath(path);

        if (isHealthEndpoint(normalizedPath)) {
            sendHealthResponse(response, requestId);

            return;
        }

        var effectivePolicy = resolveEffectivePolicy(method, normalizedPath, routeTable);

        if (requiresAuthentication(effectivePolicy) && config.securityMode() == SecurityMode.NONE) {
            handleSecurityFailure(response, SecurityError.NO_VALIDATOR_CONFIGURED, path, requestId, method);

            return;
        }

        var httpContext = toHttpRequestContext(request, requestId);

        securityValidator.validate(httpContext, effectivePolicy)
                         .flatMap(ctx -> enforceRoleIfRequired(ctx, effectivePolicy))
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

    private SecurityPolicy resolveEffectivePolicy(String method, String normalizedPath, RouteTable routeTable) {
        var routePolicy = findRouteSecurityPolicy(method, normalizedPath, routeTable);

        return routePolicy.or(globalSecurityPolicy());
    }

    private Option<SecurityPolicy> findRouteSecurityPolicy(String method,
                                                           String normalizedPath,
                                                           RouteTable routeTable) {
        var localPolicy = httpRoutePublisher.flatMap(pub -> pub.findLocalRoute(method, normalizedPath))
                                            .map(LocalRouteInfo::security)
                                            .filter(AppHttpServerAdapter::isExplicitPolicy);

        if (localPolicy.isPresent()) {
            return localPolicy;
        }

        return findMatchingRemoteRoute(routeTable.remoteRoutes(),
                                       method,
                                       normalizedPath).map(route -> SecurityPolicy.fromString(route.security()))
                                      .filter(AppHttpServerAdapter::isExplicitPolicy);
    }

    private static boolean isExplicitPolicy(SecurityPolicy policy) {
        return ! (policy instanceof SecurityPolicy.Unspecified());
    }

    private SecurityPolicy globalSecurityPolicy() {
        return switch (config.securityMode()) {
            case API_KEY -> SecurityPolicy.apiKeyRequired();
            case JWT -> SecurityPolicy.bearerTokenRequired();
            case NONE -> SecurityPolicy.publicRoute();
        };
    }

    private static boolean requiresAuthentication(SecurityPolicy policy) {
        return ! (policy instanceof SecurityPolicy.Public());
    }

    private static Result<SecurityContext> enforceRoleIfRequired(SecurityContext context, SecurityPolicy policy) {
        if (policy instanceof SecurityPolicy.RoleRequired(var roleName)) {
            return context.hasRole(roleName)
                   ? Result.success(context)
                   : new SecurityError.InsufficientRole("Access denied: role '" + roleName + "' required").result();
        }

        return Result.success(context);
    }

    @Contract
    private void dispatchAuthenticated(HttpRequest request,
                                       ResponseWriter response,
                                       RouteTable routeTable,
                                       SecurityContext securityContext,
                                       String method,
                                       String normalizedPath,
                                       String path,
                                       String requestId) {
        var principal = securityContext.principal().value();

        if (config.securityEnabled()) {
            AuditLog.authSuccess(requestId, principal, method, path);
        }

        ScopedValue.where(SecurityContextHolder.scopedValue(),
                          securityContext)
                   .run(() -> InvocationContext.runWithContext(requestId,
                                                               principal,
                                                               selfNodeId.id(),
                                                               0,
                                                               true,
                                                               () -> Deadline.runWith(Deadline.startingNow(requestBudget),
                                                                                      () -> dispatchToRoute(request,
                                                                                                            response,
                                                                                                            routeTable,
                                                                                                            method,
                                                                                                            normalizedPath,
                                                                                                            requestId))));
    }

    private void dispatchToRoute(HttpRequest request,
                                 ResponseWriter response,
                                 RouteTable routeTable,
                                 String method,
                                 String normalizedPath,
                                 String requestId) {
        // Local fast path: a request matching a locally-hosted ACTIVE slice dispatches locally
        // REGARDLESS of isRouteReady()/republish-in-progress state. The live publisher registry
        // (allLocalRoutes) is the source of truth for local availability — NOT the propagating
        // route-table snapshot, which transiently reports empty during generation churn while the
        // slice instance is still serving. Only requests needing REMOTE forwarding consult the
        // snapshot and the route-ready barrier.
        var localRouteOpt = httpRoutePublisher.flatMap(pub -> findMatchingLocalRoute(pub.allLocalRoutes(),
                                                                                     method,
                                                                                     normalizedPath));

        if (localRouteOpt.isPresent()) {
            dispatchLocalRoute(request, response, routeTable, method, normalizedPath, localRouteOpt.unwrap(), requestId);

            return;
        }

        var remoteRouteOpt = findMatchingRemoteRoute(routeTable.remoteRoutes(), method, normalizedPath);

        if (remoteRouteOpt.isPresent()) {
            dispatchRemoteRoute(request, response, remoteRouteOpt.unwrap(), requestId);

            return;
        }

        sendNoRouteFound(response, request, method, requestId);
    }

    private void dispatchLocalRoute(HttpRequest request,
                                    ResponseWriter response,
                                    RouteTable routeTable,
                                    String method,
                                    String normalizedPath,
                                    HttpNodeRouteKey localRouteKey,
                                    String requestId) {
        if (shouldForwardForStrategy(localRouteKey, method, normalizedPath, routeTable)) {
            var remoteRouteOpt = findMatchingRemoteRoute(routeTable.remoteRoutes(), method, normalizedPath);

            if (remoteRouteOpt.isPresent()) {
                log.debug("Deployment strategy routing — forwarding {} {} to remote [{}]",
                          method,
                          normalizedPath,
                          requestId);
                dispatchRemoteRoute(request, response, remoteRouteOpt.unwrap(), requestId);

                return;
            }
        }

        handleLocalRoute(request, response, localRouteKey, requestId);
    }

    private void dispatchRemoteRoute(HttpRequest request,
                                     ResponseWriter response,
                                     HttpRouteRegistry.RouteInfo route,
                                     String requestId) {
        handleRemoteRoute(request, response, route, requestId);
    }

    private void sendNoRouteFound(ResponseWriter response, HttpRequest request, String method, String requestId) {
        var routeReady = isRouteReady();

        if (!routeReady && httpRoutePublisher.isPresent()) {
            logRouteNotReadyRejection("Node starting, routes not yet synchronized",
                                      method,
                                      request.path(),
                                      requestId,
                                      routeReady,
                                      false);
            recordRouteNotReadyRejection(method, request.path());
            sendProblem(response,
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Node starting, routes not yet synchronized",
                        request.path(),
                        requestId);

            return;
        }

        if (routeRegistry.findRoute(method, request.path()).isPresent()) {
            logRouteNotReadyRejection("Route table propagating; registry has it, snapshot not refreshed yet",
                                      method,
                                      request.path(),
                                      requestId,
                                      routeReady,
                                      true);
            recordRouteNotReadyRejection(method, request.path());
            sendProblem(response,
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Route table propagating; retry after a moment",
                        request.path(),
                        requestId);

            return;
        }

        log.warn("No route found for {} {} [{}]", method, request.path(), requestId);
        sendProblem(response,
                    HttpStatus.NOT_FOUND,
                    "No route found for " + method + " " + request.path(),
                    request.path(),
                    requestId);
    }

    private static final long ROUTE_NOT_READY_LOG_EVERY_NTH = 100L;

    @Contract
    private void logRouteNotReadyRejection(String reason,
                                           String method,
                                           String path,
                                           String requestId,
                                           boolean routeReady,
                                           boolean registryHasRoute) {
        var count = routeNotReadyRejections.incrementAndGet();

        if (count == 1L || count % ROUTE_NOT_READY_LOG_EVERY_NTH == 0L) {
            log.info("503 route-not-ready ({}): {} {} [{}] routeReady={} registryHasRoute={} occurrence #{}",
                     reason,
                     method,
                     path,
                     requestId,
                     routeReady,
                     registryHasRoute,
                     count);
        }
    }

    @Contract
    private void recordRouteNotReadyRejection(String method, String path) {
        requestObserver.onPresent(obs -> obs.recordRequest(method, path, "5xx", 0L));
    }

    private record RoutingDecision(Artifact artifact, ActiveRouting activeRouting) {}

    private boolean shouldForwardForStrategy(HttpNodeRouteKey localRouteKey,
                                             String method,
                                             String normalizedPath,
                                             RouteTable routeTable) {
        return resolveRoutingDecision(method, normalizedPath).map(decision -> evaluateRoutingDecision(decision.artifact(),
                                                                                                      decision.activeRouting(),
                                                                                                      method,
                                                                                                      normalizedPath,
                                                                                                      routeTable))
                                     .or(false);
    }

    private Option<RoutingDecision> resolveRoutingDecision(String method, String normalizedPath) {
        return strategyCoordinator.flatMap(coordinator -> httpRoutePublisher.flatMap(pub -> pub.findLocalRoute(method,
                                                                                                               normalizedPath))
                                                                            .flatMap(info -> Artifact.artifact(info.artifactCoord()).option())
                                                                            .flatMap(artifact -> coordinator.activeRouting(artifact.base())
                                                                                                            .map(routing -> new RoutingDecision(artifact,
                                                                                                                                                routing))));
    }

    private boolean evaluateRoutingDecision(Artifact artifact,
                                            ActiveRouting activeRouting,
                                            String method,
                                            String normalizedPath,
                                            RouteTable routeTable) {
        var routing = activeRouting.routing();
        var localVersion = artifact.version();

        if (routing.isAllOld()) {
            return localVersion.equals(activeRouting.newVersion()) && hasMatchingRemoteRoute(routeTable.remoteRoutes(),
                                                                                             method,
                                                                                             normalizedPath);
        }

        if (routing.isAllNew()) {
            return localVersion.equals(activeRouting.oldVersion()) && hasMatchingRemoteRoute(routeTable.remoteRoutes(),
                                                                                             method,
                                                                                             normalizedPath);
        }

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

    private void handleSecurityFailure(ResponseWriter response,
                                       Cause cause,
                                       String path,
                                       String requestId,
                                       String method) {
        var statusAndMessage = mapSecurityError(cause);

        recordSecurityDenial(cause, path, requestId, method);
        maybeAddAuthenticateHeader(response, statusAndMessage.status());
        sendProblem(response, statusAndMessage.status(), statusAndMessage.clientMessage(), path, requestId);
    }

    @Contract
    private void recordSecurityDenial(Cause cause, String path, String requestId, String method) {
        AuditLog.authFailure(requestId, cause.message(), method, path);
        requestObserver.onPresent(obs -> obs.recordSecurityDenial(classifyDenialType(cause), method, path));
    }

    @Contract
    private void maybeAddAuthenticateHeader(ResponseWriter response, HttpStatus status) {
        if (status == HttpStatus.UNAUTHORIZED) {
            addAuthenticateHeader(response);
        }
    }

    private static String classifyDenialType(Cause cause) {
        if (cause == SecurityError.NO_VALIDATOR_CONFIGURED) {
            return "no_validator";
        }

        return switch (cause) {
            case SecurityError.InsufficientRole _ -> "insufficient_role";
            case SecurityError.AccessDenied _ -> "insufficient_role";
            default -> "auth_failure";
        };
    }

    private record SecurityStatusMessage(HttpStatus status, String clientMessage) {}

    private static SecurityStatusMessage mapSecurityError(Cause cause) {
        return switch (cause) {
            case SecurityError.MissingCredentials mc -> new SecurityStatusMessage(HttpStatus.UNAUTHORIZED, mc.message());
            case SecurityError.TokenExpired _ -> new SecurityStatusMessage(HttpStatus.UNAUTHORIZED, "Token expired");
            case SecurityError.SignatureInvalid _, SecurityError.IssuerMismatch _, SecurityError.AudienceMismatch _, SecurityError.KeyNotFound _ -> new SecurityStatusMessage(HttpStatus.FORBIDDEN,
                                                                                                                                                                              "Invalid token");
            case SecurityError.InvalidCredentials _ -> new SecurityStatusMessage(HttpStatus.FORBIDDEN,
                                                                                 "Invalid credentials");
            case SecurityError.InsufficientRole _ -> new SecurityStatusMessage(HttpStatus.FORBIDDEN, cause.message());
            case SecurityError.JwksFetchFailed _ -> new SecurityStatusMessage(HttpStatus.UNAUTHORIZED,
                                                                              "Authentication temporarily unavailable");
            default -> new SecurityStatusMessage(HttpStatus.UNAUTHORIZED, "Authentication failed");
        };
    }

    private void addAuthenticateHeader(ResponseWriter response) {
        switch (config.securityMode()) {
            case JWT -> response.header("WWW-Authenticate", "Bearer realm=\"Aether\"");
            case API_KEY -> response.header("WWW-Authenticate", "ApiKey realm=\"Aether\"");
            case NONE -> {}
        }
    }

    private Option<HttpNodeRouteKey> findMatchingLocalRoute(Set<HttpNodeRouteKey> localRoutes,
                                                            String method,
                                                            String normalizedPath) {
        return Option.from(localRoutes.stream()
                                      .filter(key -> key.httpMethod()
                                                        .equalsIgnoreCase(method))
                                      .filter(key -> pathMatchesPrefix(normalizedPath,
                                                                       key.pathPrefix()))
                                      .findFirst());
    }

    private Option<HttpRouteRegistry.RouteInfo> findMatchingRemoteRoute(List<HttpRouteRegistry.RouteInfo> remoteRoutes,
                                                                        String method,
                                                                        String normalizedPath) {
        return Option.from(remoteRoutes.stream()
                                       .filter(route -> route.httpMethod()
                                                             .equalsIgnoreCase(method))
                                       .filter(route -> pathMatchesPrefix(normalizedPath,
                                                                          route.pathPrefix()))
                                       .findFirst());
    }

    private boolean pathMatchesPrefix(String normalizedPath, String pathPrefix) {
        var normalizedPrefix = normalizePath(pathPrefix);

        return normalizedPath.equals(normalizedPrefix) || (normalizedPath.length() > normalizedPrefix.length() && normalizedPath.startsWith(normalizedPrefix));
    }

    private String normalizePath(String path) {
        return Option.option(path)
                     .map(String::strip)
                     .filter(s -> !s.isEmpty())
                     .map(AppHttpServerAdapter::ensureLeadingAndTrailingSlash)
                     .or("/");
    }

    private static String ensureLeadingAndTrailingSlash(String path) {
        var withLeading = path.startsWith("/")
                          ? path
                          : "/" + path;

        return withLeading.endsWith("/")
               ? withLeading
               : withLeading + "/";
    }

    private static boolean isHealthEndpoint(String normalizedPath) {
        return "/health/".equals(normalizedPath);
    }

    private void sendHealthResponse(ResponseWriter response, String requestId) {
        var body = "{\"status\":\"UP\",\"nodeId\":\"" + selfNodeId.id() + "\"}";

        response.header(ResponseWriter.X_REQUEST_ID, requestId)
                .header("X-Node-Id",
                        selfNodeId.id())
                .write(org.pragmatica.http.HttpStatus.OK,
                       body.getBytes(StandardCharsets.UTF_8),
                       CommonContentType.APPLICATION_JSON);
    }

    private void handleLocalRoute(HttpRequest request,
                                  ResponseWriter response,
                                  HttpNodeRouteKey routeKey,
                                  String requestId) {
        log.trace("Handling local route {} {} [{}]", routeKey.httpMethod(), routeKey.pathPrefix(), requestId);
        httpRoutePublisher.flatMap(pub -> pub.findLocalRouter(routeKey.httpMethod(),
                                                              routeKey.pathPrefix()))
                          .onEmpty(() -> handleMissingLocalRouter(response,
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

    private void invokeLocalRouter(HttpRequest request,
                                   ResponseWriter response,
                                   SliceRouter router,
                                   HttpNodeRouteKey routeKey,
                                   String requestId) {
        var httpCtx = toHttpRequestContext(request, requestId);
        var routeInfo = resolveRouteInfo(routeKey.httpMethod(), routeKey.pathPrefix());
        var startTime = System.nanoTime();

        routeInfo.onPresent(info -> recordMetricsStart(info));
        router.handle(httpCtx)
              .onSuccess(responseData -> handleLocalRouterSuccess(response,
                                                                  responseData,
                                                                  requestId,
                                                                  routeInfo,
                                                                  startTime,
                                                                  httpCtx))
              .onFailure(cause -> handleLocalRouterFailure(response,
                                                           request.path(),
                                                           requestId,
                                                           cause,
                                                           routeInfo,
                                                           startTime,
                                                           httpCtx));
    }

    private void handleLocalRouterSuccess(ResponseWriter response,
                                          HttpResponseData responseData,
                                          String requestId,
                                          Option<ResolvedRoute> routeInfo,
                                          long startTime,
                                          HttpRequestContext httpCtx) {
        routeInfo.onPresent(info -> recordMetricsSuccess(info,
                                                         startTime,
                                                         httpCtx.body().length,
                                                         responseData.body().length));
        sendResponse(response, responseData, requestId);
    }

    private void handleLocalRouterFailure(ResponseWriter response,
                                          String path,
                                          String requestId,
                                          Cause cause,
                                          Option<ResolvedRoute> routeInfo,
                                          long startTime,
                                          HttpRequestContext httpCtx) {
        routeInfo.onPresent(info -> recordMetricsFailure(info, startTime, httpCtx.body().length, cause));
        handleLocalRouteFailure(response, path, requestId, cause);
    }

    private void handleLocalRouteFailure(ResponseWriter response, String path, String requestId, Cause cause) {
        switch (cause) {
            case RateGuardError.LimitExceeded exceeded -> sendRateLimitResponse(response, path, requestId, exceeded);
            default -> {
                log.error("Failed to handle local route [{}]: {}", requestId, cause.message());
                sendProblem(response,
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            "Request processing failed: " + cause.message(),
                            path,
                            requestId);
            }
        }
    }

    private void sendRateLimitResponse(ResponseWriter response,
                                       String path,
                                       String requestId,
                                       RateGuardError.LimitExceeded exceeded) {
        log.debug("Rate limit exceeded for [{}]: retry after {}ms", requestId, exceeded.retryAfterMs());
        response.header("Retry-After",
                        String.valueOf(exceeded.retryAfterSeconds()));
        response.header("X-RateLimit-Limit",
                        String.valueOf(exceeded.limit()));
        response.header("X-RateLimit-Remaining",
                        String.valueOf(exceeded.remaining()));
        response.header("X-RateLimit-Reset",
                        String.valueOf(exceeded.resetAtEpochSeconds()));
        sendProblem(response, HttpStatus.TOO_MANY_REQUESTS, exceeded.message(), path, requestId);
    }

    private HttpRequestContext toHttpRequestContext(HttpRequest request, String requestId) {
        return HttpRequestContext.httpRequestContext(request.path(),
                                                     request.method().name(),
                                                     request.queryParams().asMap(),
                                                     request.headers().asMap(),
                                                     request.body(),
                                                     requestId);
    }

    private void handleRemoteRoute(HttpRequest request,
                                   ResponseWriter response,
                                   HttpRouteRegistry.RouteInfo route,
                                   String requestId) {
        log.trace("Handling remote route {} {} -> {} nodes [{}]",
                  route.httpMethod(),
                  route.pathPrefix(),
                  route.nodes().size(),
                  requestId);
        if (httpForwarder.isEmpty()) {
            log.error("HTTP forwarding not configured [{}]", requestId);
            sendProblem(response,
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "HTTP forwarding not available",
                        request.path(),
                        requestId);

            return;
        }

        var httpCtx = toHttpRequestContext(request, requestId);

        httpForwarder.unwrap()
                     .forward(httpCtx,
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

    @Override
    @Contract
    public void onHttpForwardRequest(HttpForwardRequest request) {
        log.trace("Received HttpForwardRequest [{}] correlationId={}", request.requestId(), request.correlationId());
        if (deserializer.isEmpty() || serializer.isEmpty() || clusterNetwork.isEmpty()) {
            log.error("[{}] Cannot handle forward request - missing dependencies", request.requestId());

            return;
        }

        var des = deserializer.unwrap();
        var ser = serializer.unwrap();
        var network = clusterNetwork.unwrap();

        Result.<HttpRequestContext, byte[]> lift1(des::decode,
                                                  request.requestData())
              .onFailure(cause -> logAndSendForwardError(network, request, "Deserialization failed", cause))
              .onSuccess(httpCtx -> dispatchForwardedRequest(httpCtx, request, network, ser));
    }

    private void logAndSendForwardError(ClusterNetwork network,
                                        HttpForwardRequest request,
                                        String prefix,
                                        Cause cause) {
        log.error("{} [{}]: {}", prefix, request.requestId(), cause.message());
        sendForwardError(network, request, prefix + ": " + cause.message());
    }

    @Contract
    private void dispatchForwardedRequest(HttpRequestContext httpCtx,
                                          HttpForwardRequest request,
                                          ClusterNetwork network,
                                          Serializer ser) {
        // Stage 2 of deadline propagation: the sender stamped its remaining budget onto the wire.
        // An arrived-expired request is refused without touching the router — the sender's hop
        // timeout has already fired, so computing an answer would be work nobody collects (the
        // zombie-dispatch amplification 02w measured behind every abandoned forward hop).
        var deadline = Deadline.fromWireMillis(request.remainingMillis());

        if (deadline.expired(RECEIVER_BUDGET_FLOOR)) {
            log.warn("[{}] Forwarded request arrived with {} budget remaining; refusing without dispatch",
                     request.requestId(),
                     deadline.remaining());
            sendForwardError(network, request, "Sender request budget exhausted before dispatch");

            return;
        }

        Deadline.runWith(deadline, () -> dispatchForwardedWithinBudget(httpCtx, request, network, ser));
    }

    /// Below this much remaining wire budget a forwarded request is refused instead of dispatched:
    /// the answer cannot reach the sender before its hop timeout fires.
    private static final TimeSpan RECEIVER_BUDGET_FLOOR = TimeSpan.timeSpan(50).millis();

    @Contract
    private void dispatchForwardedWithinBudget(HttpRequestContext httpCtx,
                                               HttpForwardRequest request,
                                               ClusterNetwork network,
                                               Serializer ser) {
        var method = httpCtx.method();
        var path = httpCtx.path();
        var normalizedPath = normalizePath(path);
        var routerOpt = httpRoutePublisher.flatMap(pub -> findLocalRouterForPath(pub, method, normalizedPath));

        if (routerOpt.isEmpty()) {
            log.warn("No local router for forwarded request {} {} [{}]", method, path, request.requestId());
            sendForwardError(network, request, "Route not found locally");

            return;
        }

        var routeInfo = resolveRouteInfo(method, normalizedPath);
        var startTime = System.nanoTime();

        routeInfo.onPresent(this::recordMetricsStart);
        routerOpt.unwrap()
                 .handle(httpCtx)
                 .onSuccess(responseData -> handleForwardSuccess(network,
                                                                 request,
                                                                 ser,
                                                                 responseData,
                                                                 routeInfo,
                                                                 startTime,
                                                                 httpCtx))
                 .onFailure(cause -> handleForwardFailure(network, request, cause, routeInfo, startTime, httpCtx));
    }

    private void handleForwardSuccess(ClusterNetwork network,
                                      HttpForwardRequest request,
                                      Serializer ser,
                                      HttpResponseData responseData,
                                      Option<ResolvedRoute> routeInfo,
                                      long startTime,
                                      HttpRequestContext httpCtx) {
        routeInfo.onPresent(info -> recordMetricsSuccess(info,
                                                         startTime,
                                                         httpCtx.body().length,
                                                         responseData.body().length));
        sendForwardSuccess(network, request, ser, responseData);
    }

    private void handleForwardFailure(ClusterNetwork network,
                                      HttpForwardRequest request,
                                      Cause cause,
                                      Option<ResolvedRoute> routeInfo,
                                      long startTime,
                                      HttpRequestContext httpCtx) {
        routeInfo.onPresent(info -> recordMetricsFailure(info, startTime, httpCtx.body().length, cause));
        sendForwardError(network, request, cause.message());
    }

    private Option<SliceRouter> findLocalRouterForPath(HttpRoutePublisher pub, String method, String normalizedPath) {
        return findMatchingLocalRoute(pub.allLocalRoutes(), method, normalizedPath).flatMap(key -> pub.findLocalRouter(key.httpMethod(),
                                                                                                                       key.pathPrefix()));
    }

    private void sendForwardSuccess(ClusterNetwork network,
                                    HttpForwardRequest request,
                                    Serializer ser,
                                    HttpResponseData responseData) {
        Result.lift1(ser::encode, responseData)
              .onSuccess(payload -> sendForwardPayload(network, request, payload))
              .onFailure(cause -> logAndSendForwardError(network, request, "Response serialization failed", cause));
    }

    private void sendForwardPayload(ClusterNetwork network, HttpForwardRequest request, byte[] payload) {
        var forwardResponse = new HttpForwardResponse(selfNodeId,
                                                      request.correlationId(),
                                                      request.requestId(),
                                                      true,
                                                      payload,
                                                      request.pipeline());

        network.send(request.sender(), forwardResponse);
        log.trace("Sent forward success response [{}]", request.requestId());
    }

    private void sendForwardError(ClusterNetwork network, HttpForwardRequest request, String errorMessage) {
        var forwardResponse = new HttpForwardResponse(selfNodeId,
                                                      request.correlationId(),
                                                      request.requestId(),
                                                      false,
                                                      errorMessage.getBytes(StandardCharsets.UTF_8),
                                                      request.pipeline());

        network.send(request.sender(), forwardResponse);
        log.trace("Sent forward error response [{}]: {}", request.requestId(), errorMessage);
    }

    @Override
    @Contract
    public void onHttpForwardResponse(HttpForwardResponse response) {
        httpForwarder.onPresent(fwd -> fwd.onHttpForwardResponse(response));
    }

    @Override
    @Contract
    public void onNodeRemoved(MembershipDecision.NodeRemoved nodeRemoved) {
        httpForwarder.onPresent(fwd -> fwd.onNodeRemoved(nodeRemoved));
    }

    @Override
    @Contract
    public void onNodeDecommissioned(MembershipDecision.NodeDecommissioned nodeDecommissioned) {
        httpForwarder.onPresent(fwd -> fwd.onNodeDecommissioned(nodeDecommissioned));
    }

    // Self-shutdown cleanup hook: kept on TransportObservation stream because self-shutdown is not a cluster decision.
    @Override
    @Contract
    public void onSelfShutdown(TransportObservation.SelfShutdown selfShutdown) {
        httpForwarder.onPresent(fwd -> fwd.onSelfShutdown(selfShutdown));
    }

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

        JSON_MAPPER.writeAsString(problem)
                   .onSuccess(json -> response.header(ResponseWriter.X_REQUEST_ID, requestId)
                                              .header("X-Node-Id",
                                                      selfNodeId.id())
                                              .write(toServerStatus(status.code()),
                                                     json.getBytes(StandardCharsets.UTF_8),
                                                     CONTENT_TYPE_PROBLEM))
                   .onFailure(cause -> handleProblemSerializationFailure(response, status, requestId, cause));
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
        if (responseData.statusCode() >= 400) {
            var fullBody = new String(responseData.body(), StandardCharsets.UTF_8);
            var truncatedBody = fullBody.length() > MAX_WARN_BODY_LENGTH
                                ? fullBody.substring(0, MAX_WARN_BODY_LENGTH) + "...(truncated)"
                                : fullBody;

            log.warn("HTTP error response [{}]: {} body={}", requestId, responseData.statusCode(), truncatedBody);
            log.debug("HTTP error response full body [{}]: {}", requestId, fullBody);
        } else {
            log.trace("Sending response [{}]: {} {}", requestId, responseData.statusCode(), responseData.headers());
        }

        var writer = response.header(ResponseWriter.X_REQUEST_ID, requestId).header("X-Node-Id", selfNodeId.id());

        for (var entry : responseData.headers().entrySet()) {
            writer = writer.header(entry.getKey(), entry.getValue());
        }

        var contentType = Option.option(responseData.headers().get("Content-Type"))
                                .map(AppHttpServerAdapter::resolveContentType)
                                .or(CommonContentType.APPLICATION_JSON);

        writer.write(toServerStatus(responseData.statusCode()), responseData.body(), contentType);
    }

    private static org.pragmatica.http.ContentType resolveContentType(String headerText) {
        return matchCommonContentType(headerText).or(() -> org.pragmatica.http.ContentType.contentType(headerText,
                                                                                                       guessCategory(headerText)));
    }

    private static Option<org.pragmatica.http.ContentType> matchCommonContentType(String headerText) {
        return java.util.stream.Stream.<org.pragmatica.http.ContentType> of(CommonContentType.values())
                                      .filter(ct -> ct.headerText()
                                                      .equalsIgnoreCase(headerText))
                                      .findFirst()
                                      .map(Option::option)
                                      .orElseGet(Option::none);
    }

    private static org.pragmatica.http.ContentCategory guessCategory(String headerText) {
        var lower = headerText.toLowerCase();

        if (lower.contains("json")) {
            return org.pragmatica.http.ContentCategory.JSON;
        }

        if (lower.startsWith("text/html") || lower.contains("html")) {
            return org.pragmatica.http.ContentCategory.HTML;
        }

        if (lower.contains("xml")) {
            return org.pragmatica.http.ContentCategory.XML;
        }

        if (lower.startsWith("text/")) {
            return org.pragmatica.http.ContentCategory.TEXT;
        }

        if (lower.contains("x-www-form-urlencoded")) {
            return org.pragmatica.http.ContentCategory.FORM_URLENCODED;
        }

        if (lower.contains("multipart/")) {
            return org.pragmatica.http.ContentCategory.MULTIPART;
        }

        return org.pragmatica.http.ContentCategory.BINARY;
    }

    private void sendPlainError(ResponseWriter response, HttpStatus status, String requestId) {
        var content = "{\"error\":\"" + status.message() + "\"}";

        response.header(ResponseWriter.X_REQUEST_ID, requestId)
                .header("X-Node-Id",
                        selfNodeId.id())
                .write(toServerStatus(status.code()),
                       content.getBytes(StandardCharsets.UTF_8),
                       CommonContentType.APPLICATION_JSON);
    }

    private static org.pragmatica.http.HttpStatus toServerStatus(int code) {
        return Option.option(STATUS_MAP.get(code)).or(org.pragmatica.http.HttpStatus.INTERNAL_SERVER_ERROR);
    }

    record ResolvedRoute(Artifact artifact, MethodName method) {}

    private Option<ResolvedRoute> resolveRouteInfo(String httpMethod, String path) {
        return metricsCollector.flatMap(_ -> httpRoutePublisher.flatMap(pub -> pub.findLocalRoute(httpMethod, path))
                                                               .flatMap(this::toResolvedRoute));
    }

    private Option<ResolvedRoute> toResolvedRoute(LocalRouteInfo info) {
        return Artifact.artifact(info.artifactCoord())
                       .flatMap(artifact -> MethodName.methodName(info.sliceMethod()).map(method -> new ResolvedRoute(artifact,
                                                                                                                      method)))
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
}
