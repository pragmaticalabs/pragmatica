package org.pragmatica.aether.lb;

import org.pragmatica.aether.http.HttpRouteRegistry;
import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardResponse;
import org.pragmatica.aether.http.forward.HttpForwarder;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.aether.node.NodeCodecs;
import org.pragmatica.aether.node.health.CoreSwimHealthDetector;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.node.passive.PassiveNode;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVNotificationRouter;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.server.HttpServer;
import org.pragmatica.http.server.HttpServerConfig;
import org.pragmatica.http.server.RequestContext;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.messaging.MessageRouter.Entry;
import org.pragmatica.messaging.MessageRouter.Entry.SealedBuilder;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.Serializer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.messaging.MessageRouter.Entry.route;

/// Passive cluster node that acts as a smart HTTP load balancer.
///
/// Joins the cluster network but never participates in consensus or quorum.
/// Receives committed Decision messages to build a local route table,
/// then forwards HTTP requests to the correct active node via the
/// cluster's internal binary protocol.
///
/// Benefits over HTTP-proxy LB:
/// - No HTTP re-serialization (binary protocol over persistent connections)
/// - Smart routing: sends directly to the node hosting the target slice
/// - Automatic failover via existing retry mechanism
/// - Live topology awareness via cluster events (no health-check polling)
@SuppressWarnings({"JBCT-RET-01", "JBCT-RET-03", "JBCT-EX-01"})
public final class AetherPassiveLB {
    private static final Logger log = LoggerFactory.getLogger(AetherPassiveLB.class);
    private static final int MAX_CONTENT_LENGTH = 16 * 1024 * 1024;

    private final PassiveLBConfig config;
    private final PassiveNode<AetherKey, AetherValue> passiveNode;
    private final HttpRouteRegistry routeRegistry;
    private final HttpForwarder httpForwarder;
    private final CoreSwimHealthDetector swimHealthDetector;
    private volatile Option<HttpServer> httpServer = Option.empty();

    private AetherPassiveLB(PassiveLBConfig config,
                            PassiveNode<AetherKey, AetherValue> passiveNode,
                            HttpRouteRegistry routeRegistry,
                            HttpForwarder httpForwarder,
                            CoreSwimHealthDetector swimHealthDetector) {
        this.config = config;
        this.passiveNode = passiveNode;
        this.routeRegistry = routeRegistry;
        this.httpForwarder = httpForwarder;
        this.swimHealthDetector = swimHealthDetector;
    }

    /// Create a passive LB node.
    public static AetherPassiveLB aetherPassiveLB(PassiveLBConfig config) {
        var nodeCodec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
        Serializer serializer = nodeCodec;
        Deserializer deserializer = nodeCodec;
        // Include self in coreNodes — TcpTopologyManager requires self to be present
        var allNodes = new ArrayList<>(config.clusterNodes());
        allNodes.add(config.selfInfo());
        var topologyConfig = new TopologyConfig(config.selfInfo()
                                                      .id(),
                                                config.clusterSize(),
                                                timeSpan(1).seconds(),
                                                timeSpan(10).seconds(),
                                                List.copyOf(allNodes));
        var passiveNode = PassiveNode.<AetherKey, AetherValue> passiveNode(topologyConfig, serializer, deserializer)
                                     .unwrap();
        var routeRegistry = HttpRouteRegistry.httpRouteRegistry();
        var httpForwarder = HttpForwarder.httpForwarder(config.selfInfo()
                                                              .id(),
                                                        routeRegistry,
                                                        passiveNode.network(),
                                                        serializer,
                                                        deserializer,
                                                        config.forwardTimeoutMs());
        // Create SWIM-based health detector for core node failure detection
        var swimHealthDetector = CoreSwimHealthDetector.coreSwimHealthDetector(passiveNode.delegateRouter(),
                                                                               topologyConfig,
                                                                               serializer,
                                                                               deserializer);
        wireRoutes(passiveNode, routeRegistry, httpForwarder);
        return new AetherPassiveLB(config, passiveNode, routeRegistry, httpForwarder, swimHealthDetector);
    }

    /// Start the passive LB: start cluster network, then HTTP server.
    public Promise<Unit> start() {
        log.info("Starting passive LB on HTTP port {}, cluster port {}",
                 config.httpPort(),
                 config.selfInfo()
                       .address()
                       .port());
        return passiveNode.start()
                          .flatMap(_ -> deferSwimStart())
                          .flatMap(_ -> startHttpServer())
                          .onSuccess(_ -> log.info("Passive LB started on port {}",
                                                   config.httpPort()))
                          .onFailure(cause -> log.error("Failed to start passive LB: {}",
                                                        cause.message()));
    }

    /// Stop the passive LB.
    public Promise<Unit> stop() {
        log.info("Stopping passive LB");
        swimHealthDetector.stop();
        return stopHttpServer().flatMap(_ -> passiveNode.stop())
                             .onSuccess(_ -> log.info("Passive LB stopped"));
    }

    /// Get the HTTP port.
    public int port() {
        return config.httpPort();
    }

    private Promise<Unit> deferSwimStart() {
        SharedScheduler.schedule(() -> swimHealthDetector.start(), timeSpan(5).seconds());
        return Promise.unitPromise();
    }

    private Promise<Unit> startHttpServer() {
        var serverConfig = HttpServerConfig.httpServerConfig("passive-lb",
                                                             config.httpPort())
                                           .withMaxContentLength(MAX_CONTENT_LENGTH);
        return HttpServer.httpServer(serverConfig, this::handleRequest)
                         .onSuccess(server -> httpServer = Option.some(server))
                         .mapToUnit();
    }

    private Promise<Unit> stopHttpServer() {
        return httpServer.map(HttpServer::stop)
                         .or(Promise.success(Unit.unit()));
    }

    // ================== Request Handling ==================
    private void handleRequest(RequestContext request, ResponseWriter response) {
        var method = request.method()
                            .name();
        var path = request.path();
        var requestId = request.requestId();
        if (isHealthEndpoint(path)) {
            sendHealthResponse(response, requestId);
            return;
        }
        var routeOpt = routeRegistry.findRoute(method, path);
        if (routeOpt.isEmpty()) {
            response.error(HttpStatus.NOT_FOUND, "No route found for " + method + " " + path);
            return;
        }
        var route = routeOpt.unwrap();
        var context = HttpRequestContext.httpRequestContext(path,
                                                            method,
                                                            request.queryParams()
                                                                   .asMap(),
                                                            request.headers()
                                                                   .asMap(),
                                                            request.body(),
                                                            requestId);
        httpForwarder.forward(context,
                              route.httpMethod(),
                              route.pathPrefix(),
                              requestId)
                     .onSuccess(responseData -> sendResponse(response, responseData, requestId))
                     .onFailure(cause -> response.error(HttpStatus.BAD_GATEWAY,
                                                        cause.message()));
    }

    // ================== Response Helpers ==================
    private static boolean isHealthEndpoint(String path) {
        return "/health".equals(path) || "/health/".equals(path);
    }

    private static void sendHealthResponse(ResponseWriter response, String requestId) {
        response.header(ResponseWriter.X_REQUEST_ID, requestId)
                .ok("{\"status\":\"UP\",\"type\":\"passive-lb\"}");
    }

    private static void sendResponse(ResponseWriter response, HttpResponseData responseData, String requestId) {
        response.header(ResponseWriter.X_REQUEST_ID, requestId);
        responseData.headers()
                    .forEach(response::header);
        var status = HttpStatus.OK;
        for (var s : HttpStatus.values()) {
            if (s.code() == responseData.statusCode()) {
                status = s;
                break;
            }
        }
        response.respond(status, new String(responseData.body(), StandardCharsets.UTF_8));
    }

    // ================== Message Wiring ==================
    @SuppressWarnings({"unchecked"})
    private static void wireRoutes(PassiveNode<AetherKey, AetherValue> passiveNode,
                                   HttpRouteRegistry routeRegistry,
                                   HttpForwarder httpForwarder) {
        var kvNotificationRouter = KVNotificationRouter.<AetherKey, AetherValue> builder(AetherKey.class)
                                                       .onPut(AetherKey.HttpNodeRouteKey.class,
                                                              routeRegistry::onRoutePut)
                                                       .onRemove(AetherKey.HttpNodeRouteKey.class,
                                                                 routeRegistry::onRouteRemove)
                                                       .build();
        var topologyChangeRoutes = SealedBuilder.from(TopologyChangeNotification.class)
                                                .route(route(TopologyChangeNotification.NodeAdded.class,
                                                             (TopologyChangeNotification.NodeAdded msg) -> {}),
                                                       route(TopologyChangeNotification.NodeRemoved.class,
                                                             httpForwarder::onNodeRemoved),
                                                       route(TopologyChangeNotification.NodeDown.class,
                                                             httpForwarder::onNodeDown));
        var allEntries = new ArrayList<>(passiveNode.routeEntries());
        allEntries.add(topologyChangeRoutes);
        for (var entry : kvNotificationRouter.asRouteEntries()) {
            allEntries.add(entry);
        }
        allEntries.add(route(HttpForwardResponse.class, httpForwarder::onHttpForwardResponse));
        RabiaNode.buildAndWireRouter(passiveNode.delegateRouter(), allEntries);
    }
}
