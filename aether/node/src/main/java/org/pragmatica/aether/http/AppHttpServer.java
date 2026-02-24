package org.pragmatica.aether.http;

import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardRequest;
import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardResponse;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.aether.http.handler.security.RouteSecurityPolicy;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.http.security.AuditLog;
import org.pragmatica.aether.http.security.SecurityError;
import org.pragmatica.aether.http.security.SecurityValidator;
import org.pragmatica.aether.invoke.InvocationContext;
import org.pragmatica.aether.slice.kvstore.AetherKey.HttpRouteKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.HttpRouteValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.consensus.net.ClusterNetwork;
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
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.utility.KSUID;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Application HTTP server for cluster-wide HTTP routing.
///
///
/// Handles HTTP requests by:
/// <ol>
///   - Looking up routes locally via HttpRoutePublisher
///   - If not local, forwarding to remote nodes via HttpForwardRequest/Response
/// </ol>
///
///
/// Separate from ManagementServer for security isolation.
@SuppressWarnings({"JBCT-RET-01", "JBCT-RET-03"})
public interface AppHttpServer {
    Promise<Unit> start();

    Promise<Unit> stop();

    Option<Integer> boundPort();

    /// Handle KV-Store updates to rebuild router when routes change.
    @MessageReceiver
    void onRoutePut(ValuePut<HttpRouteKey, HttpRouteValue> valuePut);

    /// Handle KV-Store removals to rebuild router when routes change.
    @MessageReceiver
    void onRouteRemove(ValueRemove<HttpRouteKey, HttpRouteValue> valueRemove);

    /// Handle incoming HTTP forward request from another node.
    @MessageReceiver
    void onHttpForwardRequest(HttpForwardRequest request);

    /// Handle HTTP forward response from another node.
    @MessageReceiver
    void onHttpForwardResponse(HttpForwardResponse response);

    /// Trigger router rebuild (called when local slices deploy/undeploy).
    void rebuildRouter();

    /// Whether this server has received initial route synchronization from the KV store.
    /// Returns true if at least one route update has been processed, or if running in standalone mode.
    boolean isRouteReady();

    /// Handle node removal for immediate retry of pending forwards.
    @MessageReceiver
    void onNodeRemoved(TopologyChangeNotification.NodeRemoved nodeRemoved);

    /// Handle node down for immediate retry of pending forwards.
    @MessageReceiver
    void onNodeDown(TopologyChangeNotification.NodeDown nodeDown);

    static AppHttpServer appHttpServer(AppHttpConfig config,
                                       NodeId selfNodeId,
                                       HttpRouteRegistry routeRegistry,
                                       Option<HttpRoutePublisher> httpRoutePublisher,
                                       Option<ClusterNetwork> clusterNetwork,
                                       Option<Serializer> serializer,
                                       Option<Deserializer> deserializer,
                                       Option<TlsConfig> tls) {
        return new AppHttpServerImpl(config,
                                     selfNodeId,
                                     routeRegistry,
                                     httpRoutePublisher,
                                     clusterNetwork,
                                     serializer,
                                     deserializer,
                                     tls);
    }
}

@SuppressWarnings({"JBCT-RET-01", "JBCT-RET-03"})
class AppHttpServerImpl implements AppHttpServer {
    private static final Logger log = LoggerFactory.getLogger(AppHttpServerImpl.class);
    private static final int MAX_CONTENT_LENGTH = 16 * 1024 * 1024;
    private static final long RETRY_DELAY_MS = 200;

    private final AppHttpConfig config;
    private final NodeId selfNodeId;
    private final HttpRouteRegistry routeRegistry;
    private final Option<HttpRoutePublisher> httpRoutePublisher;
    private final Option<ClusterNetwork> clusterNetwork;
    private final Option<Serializer> serializer;
    private final Option<Deserializer> deserializer;
    private final SecurityValidator securityValidator;
    private final Option<TlsConfig> tls;
    private final AtomicReference<HttpServer> serverRef = new AtomicReference<>();
    private final AtomicReference<RouteTable> routeTableRef = new AtomicReference<>(RouteTable.empty());
    private final AtomicBoolean routeSyncReceived = new AtomicBoolean(false);

    // Pending HTTP forward requests awaiting responses
    private final Map<String, PendingForward> pendingForwards = new ConcurrentHashMap<>();

    // Secondary index: NodeId -> Set of correlationIds for that node (for fast lookup on node departure)
    private final Map<NodeId, Set<String>> pendingForwardsByNode = new ConcurrentHashMap<>();

    // Round-robin counter per route for load balancing
    private final Map<HttpRouteKey, AtomicInteger> roundRobinCounters = new ConcurrentHashMap<>();

    record PendingForward(Promise<HttpResponseData> promise,
                          long createdAtMs,
                          String requestId,
                          NodeId targetNode,
                          Runnable onFailure) {}

    AppHttpServerImpl(AppHttpConfig config,
                      NodeId selfNodeId,
                      HttpRouteRegistry routeRegistry,
                      Option<HttpRoutePublisher> httpRoutePublisher,
                      Option<ClusterNetwork> clusterNetwork,
                      Option<Serializer> serializer,
                      Option<Deserializer> deserializer,
                      Option<TlsConfig> tls) {
        this.config = config;
        this.selfNodeId = selfNodeId;
        this.routeRegistry = routeRegistry;
        this.httpRoutePublisher = httpRoutePublisher;
        this.clusterNetwork = clusterNetwork;
        this.serializer = serializer;
        this.deserializer = deserializer;
        this.securityValidator = config.securityEnabled()
                                 ? SecurityValidator.apiKeyValidator(config.apiKeyValues())
                                 : SecurityValidator.noOpValidator();
        this.tls = tls;
    }

    @Override
    public Promise<Unit> start() {
        if (!config.enabled()) {
            log.info("App HTTP server is disabled");
            return Promise.success(unit());
        }
        log.info("Starting App HTTP server on port {}", config.port());
        rebuildRouter();
        var serverConfig = buildServerConfig();
        return HttpServer.httpServer(serverConfig, this::handleRequest)
                         .map(this::registerStartedServer)
                         .onFailure(cause -> log.error("Failed to start App HTTP server on port {}: {}",
                                                       config.port(),
                                                       cause.message()));
    }

    private HttpServerConfig buildServerConfig() {
        var serverConfig = HttpServerConfig.httpServerConfig("app-http",
                                                             config.port())
                                           .withMaxContentLength(MAX_CONTENT_LENGTH);
        return tls.fold(() -> serverConfig, serverConfig::withTls);
    }

    private Unit registerStartedServer(HttpServer server) {
        serverRef.set(server);
        log.info("App HTTP server started on port {}", server.port());
        return unit();
    }

    @Override
    public Promise<Unit> stop() {
        return Option.option(serverRef.get())
                     .fold(() -> Promise.success(unit()),
                           server -> server.stop()
                                           .onSuccessRun(() -> log.info("App HTTP server stopped")));
    }

    @Override
    public Option<Integer> boundPort() {
        return Option.option(serverRef.get())
                     .map(HttpServer::port);
    }

    // ================== Router Rebuild ==================
    @Override
    public void rebuildRouter() {
        var localRoutes = httpRoutePublisher.map(HttpRoutePublisher::allLocalRoutes)
                                            .or(Set.of());
        var remoteRoutes = routeRegistry.allRoutes()
                                        .stream()
                                        .filter(route -> !localRoutes.contains(route.toKey()))
                                        .toList();
        var newTable = RouteTable.routeTable(localRoutes, remoteRoutes);
        routeTableRef.set(newTable);
        log.debug("Router rebuilt: {} local routes, {} remote routes", localRoutes.size(), remoteRoutes.size());
    }

    @Override
    public boolean isRouteReady() {
        return routeSyncReceived.get() || httpRoutePublisher.isEmpty();
    }

    @Override
    public void onRoutePut(ValuePut<HttpRouteKey, HttpRouteValue> valuePut) {
        routeSyncReceived.set(true);
        log.debug("HttpRouteKey added, rebuilding router");
        rebuildRouter();
    }

    @Override
    public void onRouteRemove(ValueRemove<HttpRouteKey, HttpRouteValue> valueRemove) {
        routeSyncReceived.set(true);
        log.debug("HttpRouteKey removed, rebuilding router");
        rebuildRouter();
    }

    // ================== Request Handling ==================
    private void handleRequest(RequestContext request, ResponseWriter response) {
        var requestId = request.requestId();
        // Set request ID in invocation context for the entire request handling scope
        InvocationContext.runWithRequestId(requestId, () -> handleRequestInScope(request, response, requestId));
    }

    private void handleRequestInScope(RequestContext request, ResponseWriter response, String requestId) {
        var method = request.method()
                            .name();
        var path = request.path();
        log.trace("Received {} {} [{}]", method, path, requestId);
        var routeTable = routeTableRef.get();
        var normalizedPath = normalizePath(path);
        // Health endpoint — always returns 200 (no route table dependency)
        if (isHealthEndpoint(normalizedPath)) {
            sendHealthResponse(response, requestId);
            return;
        }
        // Security validation — health endpoint already bypassed above
        var httpContext = toHttpRequestContext(request, requestId);
        var policy = resolveSecurityPolicy();
        securityValidator.validate(httpContext, policy)
                         .apply(cause -> handleSecurityFailure(response, cause, path, requestId, method),
                                ctx -> dispatchAuthenticated(request, response, routeTable, ctx, method, normalizedPath, path, requestId));
    }

    private RouteSecurityPolicy resolveSecurityPolicy() {
        return config.securityEnabled()
               ? RouteSecurityPolicy.apiKeyRequired()
               : RouteSecurityPolicy.publicRoute();
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
        var principal = securityContext.isAuthenticated()
                        ? securityContext.principal().value()
                        : null;
        AuditLog.authSuccess(requestId, principal != null ? principal : "anonymous", method, path);
        InvocationContext.runWithContext(requestId, principal, selfNodeId.id(),
                                        () -> dispatchToRoute(request, response, routeTable, method, normalizedPath, requestId));
    }

    private void dispatchToRoute(RequestContext request,
                                 ResponseWriter response,
                                 RouteTable routeTable,
                                 String method,
                                 String normalizedPath,
                                 String requestId) {
        // Try local route first
        if (httpRoutePublisher.isPresent()) {
            var localRouteOpt = findMatchingLocalRoute(routeTable.localRoutes(), method, normalizedPath);
            if (localRouteOpt.isPresent()) {
                handleLocalRoute(request, response, localRouteOpt.unwrap(), requestId);
                return;
            }
        }
        // Try remote route
        var remoteRouteOpt = findMatchingRemoteRoute(routeTable.remoteRoutes(), method, normalizedPath);
        if (remoteRouteOpt.isPresent()) {
            handleRemoteRoute(request, response, remoteRouteOpt.unwrap(), requestId);
            return;
        }
        // No route found — distinguish between "not synced yet" and "genuinely missing"
        if (!routeSyncReceived.get() && httpRoutePublisher.isPresent()) {
            log.debug("Route not yet available for {} {} [{}] — node starting, routes not synchronized",
                      method,
                      request.path(),
                      requestId);
            sendProblem(response,
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Node starting, routes not yet synchronized",
                        request.path(),
                        requestId);
        } else {
            log.warn("No route found for {} {} [{}]", method, request.path(), requestId);
            sendProblem(response, HttpStatus.NOT_FOUND,
                        "No route found for " + method + " " + request.path(), request.path(), requestId);
        }
    }

    @SuppressWarnings("JBCT-PAT-01")
    private void handleSecurityFailure(ResponseWriter response,
                                       Cause cause,
                                       String path,
                                       String requestId,
                                       String method) {
        var status = switch (cause) {
            case SecurityError.MissingCredentials _ -> HttpStatus.UNAUTHORIZED;
            case SecurityError.InvalidCredentials _ -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.UNAUTHORIZED;
        };
        AuditLog.authFailure(requestId, cause.message(), method, path);
        if (status == HttpStatus.UNAUTHORIZED) {
            response.header("WWW-Authenticate", "ApiKey realm=\"Aether\"");
        }
        sendProblem(response, status, cause.message(), path, requestId);
    }

    private Option<HttpRouteKey> findMatchingLocalRoute(Set<HttpRouteKey> localRoutes,
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
        return normalizedPath.equals(normalizedPrefix) ||
        (normalizedPath.length() > normalizedPrefix.length() &&
        normalizedPath.startsWith(normalizedPrefix));
    }

    private String normalizePath(String path) {
        // Boundary guard: path may be null from external HTTP request parsing
        if (path == null || path.isBlank()) {
            return "/";
        }
        var normalized = path.strip();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (!normalized.endsWith("/")) {
            normalized = normalized + "/";
        }
        return normalized;
    }

    // ================== Health Endpoint ==================
    private static boolean isHealthEndpoint(String normalizedPath) {
        return "/health/".equals(normalizedPath);
    }

    private void sendHealthResponse(ResponseWriter response, String requestId) {
        var body = "{\"status\":\"UP\",\"nodeId\":\"" + selfNodeId.id() + "\"}";
        response.header(ResponseWriter.X_REQUEST_ID, requestId)
                .header("X-Node-Id", selfNodeId.id())
                .write(org.pragmatica.http.HttpStatus.OK,
                       body.getBytes(StandardCharsets.UTF_8),
                       CommonContentType.APPLICATION_JSON);
    }

    // ================== Local Route Handling ==================
    private void handleLocalRoute(RequestContext request,
                                  ResponseWriter response,
                                  HttpRouteKey routeKey,
                                  String requestId) {
        log.trace("Handling local route {} {} [{}]", routeKey.httpMethod(), routeKey.pathPrefix(), requestId);
        httpRoutePublisher.flatMap(pub -> pub.findLocalRouter(routeKey.httpMethod(),
                                                              routeKey.pathPrefix()))
                          .onEmpty(() -> handleMissingLocalRouter(response, request.path(), routeKey, requestId))
                          .onPresent(router -> invokeLocalRouter(request, response, router, requestId));
    }

    private void handleMissingLocalRouter(ResponseWriter response,
                                           String path,
                                           HttpRouteKey routeKey,
                                           String requestId) {
        log.error("Local router not found for route {} [{}]", routeKey, requestId);
        sendProblem(response, HttpStatus.INTERNAL_SERVER_ERROR, "Local router not found", path, requestId);
    }

    private void invokeLocalRouter(RequestContext request,
                                   ResponseWriter response,
                                   SliceRouter router,
                                   String requestId) {
        var context = toHttpRequestContext(request, requestId);
        router.handle(context)
              .onSuccess(responseData -> sendResponse(response, responseData, requestId))
              .onFailure(cause -> handleLocalRouteFailure(response, request.path(), requestId, cause));
    }

    private void handleLocalRouteFailure(ResponseWriter response, String path, String requestId, Cause cause) {
        log.error("Failed to handle local route [{}]: {}", requestId, cause.message());
        sendProblem(response, HttpStatus.INTERNAL_SERVER_ERROR,
                    "Request processing failed: " + cause.message(), path, requestId);
    }

    private HttpRequestContext toHttpRequestContext(RequestContext request, String requestId) {
        return HttpRequestContext.httpRequestContext(request.path(),
                                                     request.method()
                                                            .name(),
                                                     request.queryParams()
                                                            .asMap(),
                                                     request.headers()
                                                            .asMap(),
                                                     request.body(),
                                                     requestId);
    }

    // ================== Remote Route Handling ==================
    private void handleRemoteRoute(RequestContext request,
                                   ResponseWriter response,
                                   HttpRouteRegistry.RouteInfo route,
                                   String requestId) {
        log.trace("Handling remote route {} {} -> {} nodes [{}]",
                  route.httpMethod(),
                  route.pathPrefix(),
                  route.nodes()
                       .size(),
                  requestId);
        // Check prerequisites
        if (clusterNetwork.isEmpty() || serializer.isEmpty() || deserializer.isEmpty()) {
            log.error("HTTP forwarding not configured [{}]", requestId);
            sendProblem(response,
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "HTTP forwarding not available",
                        request.path(),
                        requestId);
            return;
        }
        // Filter to connected nodes only
        var connectedNodes = filterConnectedNodes(route.nodes());
        if (connectedNodes.isEmpty()) {
            log.warn("No connected nodes available for route {} {} [{}]",
                     route.httpMethod(),
                     route.pathPrefix(),
                     requestId);
            sendProblem(response,
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "No available nodes for route",
                        request.path(),
                        requestId);
            return;
        }
        // Start forwarding with retry support
        forwardRequestWithRetry(request,
                                response,
                                connectedNodes,
                                Set.of(),
                                route.toKey(),
                                requestId,
                                config.forwardMaxRetries());
    }

    private List<NodeId> filterConnectedNodes(Set<NodeId> nodes) {
        if (clusterNetwork.isEmpty()) {
            return List.of();
        }
        var connected = clusterNetwork.unwrap()
                                      .connectedPeers();
        return nodes.stream()
                    .filter(connected::contains)
                    .toList();
    }

    private List<NodeId> freshCandidatesForRoute(HttpRouteKey routeKey) {
        return Option.from(routeRegistry.allRoutes()
                                        .stream()
                                        .filter(r -> r.toKey()
                                                      .equals(routeKey))
                                        .findFirst())
                     .map(r -> filterConnectedNodes(r.nodes()))
                     .or(List.of());
    }

    private NodeId selectNodeRoundRobin(HttpRouteKey routeKey, List<NodeId> nodes) {
        var counter = roundRobinCounters.computeIfAbsent(routeKey, _ -> new AtomicInteger(0));
        var index = Math.abs(counter.getAndIncrement() % nodes.size());
        return nodes.get(index);
    }

    private NodeId selectNodeFromCandidates(HttpRouteKey routeKey, List<NodeId> candidates) {
        // Use round-robin selection from candidates list
        var counter = roundRobinCounters.computeIfAbsent(routeKey, _ -> new AtomicInteger(0));
        var index = Math.abs(counter.getAndIncrement() % candidates.size());
        return candidates.get(index);
    }

    private void forwardRequestWithRetry(RequestContext request,
                                         ResponseWriter response,
                                         List<NodeId> availableNodes,
                                         Set<NodeId> triedNodes,
                                         HttpRouteKey routeKey,
                                         String requestId,
                                         int retriesRemaining) {
        // Filter out already tried nodes
        var candidates = availableNodes.stream()
                                       .filter(n -> !triedNodes.contains(n))
                                       .toList();
        if (candidates.isEmpty()) {
            if (retriesRemaining > 0) {
                // No candidates now — wait briefly for route table to heal, then re-query
                log.debug("No candidates for {} {} [{}], waiting {}ms before re-query ({} retries remaining)",
                          routeKey.httpMethod(),
                          routeKey.pathPrefix(),
                          requestId,
                          RETRY_DELAY_MS,
                          retriesRemaining);
                Promise.<Unit> promise()
                       .timeout(timeSpan(RETRY_DELAY_MS).millis())
                       .onResult(_ -> retryAfterDelay(request, response, routeKey, requestId, retriesRemaining));
                return;
            }
            log.error("No more nodes to try for {} {} [{}] after all retries exhausted",
                      routeKey.httpMethod(),
                      routeKey.pathPrefix(),
                      requestId);
            sendProblem(response,
                        HttpStatus.GATEWAY_TIMEOUT,
                        "All nodes failed or unavailable",
                        request.path(),
                        requestId);
            return;
        }
        // Select next node (round-robin from candidates)
        var targetNode = selectNodeFromCandidates(routeKey, candidates);
        var newTriedNodes = new HashSet<>(triedNodes);
        newTriedNodes.add(targetNode);
        // Forward with retry callback
        forwardRequestInternal(request,
                               response,
                               targetNode,
                               requestId,
                               () -> handleRetryOrExhausted(request, response, newTriedNodes,
                                                            routeKey, requestId, retriesRemaining));
    }

    private void retryAfterDelay(RequestContext request,
                                 ResponseWriter response,
                                 HttpRouteKey routeKey,
                                 String requestId,
                                 int retriesRemaining) {
        var freshNodes = freshCandidatesForRoute(routeKey);
        forwardRequestWithRetry(request, response, freshNodes, Set.of(),
                                routeKey, requestId, retriesRemaining - 1);
    }

    private void handleRetryOrExhausted(RequestContext request,
                                        ResponseWriter response,
                                        Set<NodeId> triedNodes,
                                        HttpRouteKey routeKey,
                                        String requestId,
                                        int retriesRemaining) {
        if (retriesRemaining > 0) {
            log.debug("Retrying request [{}], {} retries remaining, re-querying route",
                      requestId, retriesRemaining);
            var freshNodes = freshCandidatesForRoute(routeKey);
            forwardRequestWithRetry(request, response, freshNodes, triedNodes,
                                    routeKey, requestId, retriesRemaining - 1);
        } else {
            log.error("All retries exhausted for [{}]", requestId);
            sendProblem(response, HttpStatus.GATEWAY_TIMEOUT,
                        "Request failed after all retries", request.path(), requestId);
        }
    }

    private void forwardRequestInternal(RequestContext request,
                                        ResponseWriter response,
                                        NodeId targetNode,
                                        String requestId,
                                        Runnable onFailure) {
        var network = clusterNetwork.unwrap();
        // Fast path: if the target is already known to be disconnected, fail immediately
        if (!network.connectedPeers()
                    .contains(targetNode)) {
            log.debug("Target node {} already disconnected, immediate retry [{}]", targetNode, requestId);
            onFailure.run();
            return;
        }
        var ser = serializer.unwrap();
        var correlationId = KSUID.ksuid()
                                 .toString();
        var context = toHttpRequestContext(request, requestId);
        // Serialize the request context
        byte[] requestData;
        try{
            requestData = ser.encode(context);
        } catch (Exception e) {
            log.error("Failed to serialize request [{}]: {}", requestId, e.getMessage());
            sendProblem(response,
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Request serialization failed",
                        request.path(),
                        requestId);
            return;
        }
        // Create pending forward entry with onFailure callback
        var promise = Promise.<HttpResponseData>promise();
        var pending = new PendingForward(promise, System.currentTimeMillis(), requestId, targetNode, onFailure);
        pendingForwards.put(correlationId, pending);
        // Add to secondary index for fast lookup on node departure
        pendingForwardsByNode.computeIfAbsent(targetNode,
                                              _ -> ConcurrentHashMap.newKeySet())
                             .add(correlationId);
        // Set up timeout
        promise.timeout(timeSpan(config.forwardTimeoutMs()).millis())
               .onResult(_ -> removePendingForward(correlationId, targetNode));
        // Send forward request
        var forwardRequest = new HttpForwardRequest(selfNodeId, correlationId, requestId, requestData);
        network.send(targetNode, forwardRequest);
        log.trace("Forwarded request to {} [{}] correlationId={}", targetNode, requestId, correlationId);
        // Handle response
        promise.onSuccess(responseData -> sendResponse(response, responseData, requestId))
               .onFailure(cause -> handleForwardFailure(response,
                                                        request.path(),
                                                        requestId,
                                                        targetNode,
                                                        onFailure));
    }

    private void handleForwardFailure(ResponseWriter response,
                                      String path,
                                      String requestId,
                                      NodeId targetNode,
                                      Runnable onFailure) {
        log.warn("Failed to forward request [{}] to {}, attempting retry", requestId, targetNode);
        onFailure.run();
    }

    // ================== Forward Message Handlers ==================
    @Override
    public void onHttpForwardRequest(HttpForwardRequest request) {
        log.trace("Received HttpForwardRequest [{}] correlationId={}", request.requestId(), request.correlationId());
        if (deserializer.isEmpty() || serializer.isEmpty() || clusterNetwork.isEmpty()) {
            log.error("[{}] Cannot handle forward request - missing dependencies", request.requestId());
            return;
        }
        var des = deserializer.unwrap();
        var ser = serializer.unwrap();
        var network = clusterNetwork.unwrap();
        // Deserialize the request context
        HttpRequestContext context;
        try{
            context = des.decode(request.requestData());
        } catch (Exception e) {
            log.error("Failed to deserialize forward request [{}]: {}", request.requestId(), e.getMessage());
            sendForwardError(network, request, "Deserialization failed: " + e.getMessage());
            return;
        }
        // Find local router for this request
        var method = context.method();
        var path = context.path();
        var normalizedPath = normalizePath(path);
        var routerOpt = httpRoutePublisher.flatMap(pub -> findLocalRouterForPath(pub, method, normalizedPath));
        if (routerOpt.isEmpty()) {
            log.warn("No local router for forwarded request {} {} [{}]", method, path, request.requestId());
            sendForwardError(network, request, "Route not found locally");
            return;
        }
        // Handle the request locally
        routerOpt.unwrap()
                 .handle(context)
                 .onSuccess(responseData -> sendForwardSuccess(network, request, ser, responseData))
                 .onFailure(cause -> sendForwardError(network,
                                                      request,
                                                      cause.message()));
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
        try{
            var payload = ser.encode(responseData);
            var forwardResponse = new HttpForwardResponse(selfNodeId,
                                                          request.correlationId(),
                                                          request.requestId(),
                                                          true,
                                                          payload);
            network.send(request.sender(), forwardResponse);
            log.trace("Sent forward success response [{}]", request.requestId());
        } catch (Exception e) {
            log.error("Failed to serialize forward response [{}]: {}", request.requestId(), e.getMessage());
            sendForwardError(network, request, "Response serialization failed");
        }
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

    @Override
    public void onHttpForwardResponse(HttpForwardResponse response) {
        log.trace("Received HttpForwardResponse [{}] correlationId={} success={}",
                  response.requestId(),
                  response.correlationId(),
                  response.success());
        Option.option(pendingForwards.remove(response.correlationId()))
              .onEmpty(() -> log.warn("[{}] Received forward response for unknown correlationId: {}",
                                      response.requestId(),
                                      response.correlationId()))
              .onPresent(pending -> processForwardResponse(pending, response));
    }

    private void processForwardResponse(PendingForward pending, HttpForwardResponse response) {
        removeFromNodeIndex(response.correlationId(), pending.targetNode());
        if (response.success()) {
            handleSuccessfulForwardResponse(pending, response);
        } else {
            handleFailedForwardResponse(pending, response);
        }
    }

    @Override
    public void onNodeRemoved(TopologyChangeNotification.NodeRemoved nodeRemoved) {
        handleNodeDeparture(nodeRemoved.nodeId());
    }

    @Override
    public void onNodeDown(TopologyChangeNotification.NodeDown nodeDown) {
        handleNodeDeparture(nodeDown.nodeId());
    }

    private void handleNodeDeparture(NodeId departedNode) {
        routeRegistry.evictNode(departedNode);
        Option.option(pendingForwardsByNode.remove(departedNode))
              .filter(ids -> !ids.isEmpty())
              .onPresent(correlationIds -> retryPendingForwards(departedNode, correlationIds));
    }

    private void retryPendingForwards(NodeId departedNode, Set<String> correlationIds) {
        var affectedRequestIds = correlationIds.stream()
                                               .map(pendingForwards::get)
                                               .flatMap(p -> Option.option(p)
                                                                   .stream())
                                               .map(PendingForward::requestId)
                                               .limit(5)
                                               .toList();
        log.debug("Node {} departed, triggering immediate retry for {} pending forwards, requestIds={}",
                  departedNode,
                  correlationIds.size(),
                  affectedRequestIds);
        for (var correlationId : correlationIds) {
            Option.option(pendingForwards.remove(correlationId))
                  .onPresent(pending -> failPendingForwardOnDeparture(pending, departedNode));
        }
    }

    private void failPendingForwardOnDeparture(PendingForward pending, NodeId departedNode) {
        log.debug("Triggering retry for request [{}] due to node {} departure",
                  pending.requestId(), departedNode);
        pending.promise()
               .fail(Causes.cause("Target node " + departedNode + " departed"));
    }

    private void removePendingForward(String correlationId, NodeId targetNode) {
        pendingForwards.remove(correlationId);
        removeFromNodeIndex(correlationId, targetNode);
    }

    private void removeFromNodeIndex(String correlationId, NodeId targetNode) {
        Option.option(pendingForwardsByNode.get(targetNode))
              .onPresent(nodeCorrelations -> cleanupNodeCorrelation(nodeCorrelations, correlationId, targetNode));
    }

    private void cleanupNodeCorrelation(Set<String> nodeCorrelations, String correlationId, NodeId targetNode) {
        nodeCorrelations.remove(correlationId);
        if (nodeCorrelations.isEmpty()) {
            pendingForwardsByNode.remove(targetNode, nodeCorrelations);
        }
    }

    private void handleSuccessfulForwardResponse(PendingForward pending, HttpForwardResponse response) {
        if (deserializer.isEmpty()) {
            pending.promise()
                   .fail(Causes.cause("Deserializer not available"));
            return;
        }
        try{
            HttpResponseData responseData = deserializer.unwrap()
                                                        .decode(response.payload());
            pending.promise()
                   .succeed(responseData);
            log.trace("Completed forward request [{}]", pending.requestId());
        } catch (Exception e) {
            log.error("Failed to deserialize forward response [{}]: {}", pending.requestId(), e.getMessage());
            pending.promise()
                   .fail(Causes.cause("Response deserialization failed: " + e.getMessage()));
        }
    }

    private void handleFailedForwardResponse(PendingForward pending, HttpForwardResponse response) {
        var errorMessage = new String(response.payload(), StandardCharsets.UTF_8);
        log.warn("Failed to forward request [{}]: {}", pending.requestId(), errorMessage);
        pending.promise()
               .fail(Causes.cause("Remote processing failed: " + errorMessage));
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
        JSON_MAPPER.writeAsString(problem)
                   .onSuccess(json -> response.header(ResponseWriter.X_REQUEST_ID, requestId)
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
        var writer = response.header(ResponseWriter.X_REQUEST_ID, requestId);
        for (var entry : responseData.headers()
                                     .entrySet()) {
            writer = writer.header(entry.getKey(), entry.getValue());
        }
        var contentType = Option.option(responseData.headers()
                                                    .get("Content-Type"))
                                .map(ct -> org.pragmatica.http.ContentType.contentType(ct,
                                                                                       org.pragmatica.http.ContentCategory.JSON))
                                .or(CommonContentType.APPLICATION_JSON);
        writer.write(toServerStatus(responseData.statusCode()), responseData.body(), contentType);
    }

    private void sendPlainError(ResponseWriter response, HttpStatus status, String requestId) {
        var content = "{\"error\":\"" + status.message() + "\"}";
        response.header(ResponseWriter.X_REQUEST_ID, requestId)
                .write(toServerStatus(status.code()),
                       content.getBytes(StandardCharsets.UTF_8),
                       CommonContentType.APPLICATION_JSON);
    }

    private static org.pragmatica.http.HttpStatus toServerStatus(int code) {
        return Option.option(STATUS_MAP.get(code))
                     .or(org.pragmatica.http.HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ================== Route Table ==================
    /// Snapshot of current route state for thread-safe access.
    record RouteTable(Set<HttpRouteKey> localRoutes,
                      List<HttpRouteRegistry.RouteInfo> remoteRoutes) {
        static RouteTable empty() {
            return new RouteTable(Set.of(), List.of());
        }

        static RouteTable routeTable(Set<HttpRouteKey> localRoutes,
                                     List<HttpRouteRegistry.RouteInfo> remoteRoutes) {
            return new RouteTable(Set.copyOf(localRoutes), List.copyOf(remoteRoutes));
        }
    }
}
