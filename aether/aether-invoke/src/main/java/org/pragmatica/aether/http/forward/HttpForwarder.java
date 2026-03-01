package org.pragmatica.aether.http.forward;

import org.pragmatica.aether.http.HttpRouteRegistry;
import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardRequest;
import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardResponse;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.aether.slice.kvstore.AetherKey.HttpRouteKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.utility.KSUID;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Reusable HTTP request forwarder for cluster-internal request forwarding.
///
/// Used by both active nodes (AppHttpServer) and passive nodes (Passive LB)
/// to forward HTTP requests to the correct node via the cluster network.
@SuppressWarnings({"JBCT-RET-01", "JBCT-RET-03"})
public interface HttpForwarder {
    /// Forward an HTTP request context to a remote node for the given route.
    /// Returns a Promise that resolves with the HttpResponseData from the remote node.
    /// Handles round-robin selection, retry with backoff, and node departure failover.
    Promise<HttpResponseData> forward(HttpRequestContext requestContext,
                                      HttpRouteKey routeKey,
                                      String requestId,
                                      int maxRetries);

    /// Handle HTTP forward response from another node.
    @MessageReceiver
    void onHttpForwardResponse(HttpForwardResponse response);

    /// Handle node removal for immediate retry of pending forwards.
    @MessageReceiver
    void onNodeRemoved(TopologyChangeNotification.NodeRemoved nodeRemoved);

    /// Handle node down for immediate retry of pending forwards.
    @MessageReceiver
    void onNodeDown(TopologyChangeNotification.NodeDown nodeDown);

    /// Create an HttpForwarder instance.
    static HttpForwarder httpForwarder(NodeId selfNodeId,
                                       HttpRouteRegistry routeRegistry,
                                       ClusterNetwork clusterNetwork,
                                       Serializer serializer,
                                       Deserializer deserializer,
                                       long forwardTimeoutMs) {
        return new HttpForwarderImpl(selfNodeId,
                                     routeRegistry,
                                     clusterNetwork,
                                     serializer,
                                     deserializer,
                                     forwardTimeoutMs);
    }
}

@SuppressWarnings({"JBCT-RET-01", "JBCT-RET-03"})
class HttpForwarderImpl implements HttpForwarder {
    private static final Logger log = LoggerFactory.getLogger(HttpForwarderImpl.class);
    private static final long RETRY_DELAY_MS = 200;

    private final NodeId selfNodeId;
    private final HttpRouteRegistry routeRegistry;
    private final ClusterNetwork clusterNetwork;
    private final Serializer serializer;
    private final Deserializer deserializer;
    private final long forwardTimeoutMs;

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

    HttpForwarderImpl(NodeId selfNodeId,
                      HttpRouteRegistry routeRegistry,
                      ClusterNetwork clusterNetwork,
                      Serializer serializer,
                      Deserializer deserializer,
                      long forwardTimeoutMs) {
        this.selfNodeId = selfNodeId;
        this.routeRegistry = routeRegistry;
        this.clusterNetwork = clusterNetwork;
        this.serializer = serializer;
        this.deserializer = deserializer;
        this.forwardTimeoutMs = forwardTimeoutMs;
    }

    @Override
    public Promise<HttpResponseData> forward(HttpRequestContext requestContext,
                                             HttpRouteKey routeKey,
                                             String requestId,
                                             int maxRetries) {
        var resultPromise = Promise.<HttpResponseData>promise();
        var connectedNodes = filterConnectedNodes(routeRegistry.findRoute(routeKey.httpMethod(),
                                                                          routeKey.pathPrefix())
                                                               .map(HttpRouteRegistry.RouteInfo::nodes)
                                                               .or(Set.of()));
        if (connectedNodes.isEmpty()) {
            log.warn("No connected nodes available for route {} {} [{}]",
                     routeKey.httpMethod(),
                     routeKey.pathPrefix(),
                     requestId);
            resultPromise.fail(Causes.cause("No available nodes for route"));
            return resultPromise;
        }
        forwardWithRetry(requestContext, resultPromise, connectedNodes, Set.of(), routeKey, requestId, maxRetries);
        return resultPromise;
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

    @Override
    public void onNodeRemoved(TopologyChangeNotification.NodeRemoved nodeRemoved) {
        handleNodeDeparture(nodeRemoved.nodeId());
    }

    @Override
    public void onNodeDown(TopologyChangeNotification.NodeDown nodeDown) {
        handleNodeDeparture(nodeDown.nodeId());
    }

    // ================== Forwarding Logic ==================
    private List<NodeId> filterConnectedNodes(Set<NodeId> nodes) {
        var connected = clusterNetwork.connectedPeers();
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

    private NodeId selectNodeFromCandidates(HttpRouteKey routeKey, List<NodeId> candidates) {
        var counter = roundRobinCounters.computeIfAbsent(routeKey, _ -> new AtomicInteger(0));
        var index = Math.abs(counter.getAndIncrement() % candidates.size());
        return candidates.get(index);
    }

    private void forwardWithRetry(HttpRequestContext requestContext,
                                  Promise<HttpResponseData> resultPromise,
                                  List<NodeId> availableNodes,
                                  Set<NodeId> triedNodes,
                                  HttpRouteKey routeKey,
                                  String requestId,
                                  int retriesRemaining) {
        var candidates = availableNodes.stream()
                                       .filter(n -> !triedNodes.contains(n))
                                       .toList();
        if (candidates.isEmpty()) {
            handleNoCandidates(requestContext, resultPromise, routeKey, requestId, retriesRemaining);
            return;
        }
        var targetNode = selectNodeFromCandidates(routeKey, candidates);
        var newTriedNodes = new HashSet<>(triedNodes);
        newTriedNodes.add(targetNode);
        forwardToNode(requestContext,
                      resultPromise,
                      targetNode,
                      requestId,
                      () -> handleRetryOrExhausted(requestContext,
                                                   resultPromise,
                                                   newTriedNodes,
                                                   routeKey,
                                                   requestId,
                                                   retriesRemaining));
    }

    private void handleNoCandidates(HttpRequestContext requestContext,
                                    Promise<HttpResponseData> resultPromise,
                                    HttpRouteKey routeKey,
                                    String requestId,
                                    int retriesRemaining) {
        if (retriesRemaining > 0) {
            log.debug("No candidates for {} {} [{}], waiting {}ms before re-query ({} retries remaining)",
                      routeKey.httpMethod(),
                      routeKey.pathPrefix(),
                      requestId,
                      RETRY_DELAY_MS,
                      retriesRemaining);
            Promise.<Unit> promise()
                   .timeout(timeSpan(RETRY_DELAY_MS).millis())
                   .onResult(_ -> retryAfterDelay(requestContext, resultPromise, routeKey, requestId, retriesRemaining));
            return;
        }
        log.error("No more nodes to try for {} {} [{}] after all retries exhausted",
                  routeKey.httpMethod(),
                  routeKey.pathPrefix(),
                  requestId);
        resultPromise.fail(Causes.cause("All nodes failed or unavailable"));
    }

    private void retryAfterDelay(HttpRequestContext requestContext,
                                 Promise<HttpResponseData> resultPromise,
                                 HttpRouteKey routeKey,
                                 String requestId,
                                 int retriesRemaining) {
        var freshNodes = freshCandidatesForRoute(routeKey);
        forwardWithRetry(requestContext, resultPromise, freshNodes, Set.of(), routeKey, requestId, retriesRemaining - 1);
    }

    private void handleRetryOrExhausted(HttpRequestContext requestContext,
                                        Promise<HttpResponseData> resultPromise,
                                        Set<NodeId> triedNodes,
                                        HttpRouteKey routeKey,
                                        String requestId,
                                        int retriesRemaining) {
        if (retriesRemaining > 0) {
            log.debug("Retrying request [{}], {} retries remaining, re-querying route", requestId, retriesRemaining);
            var freshNodes = freshCandidatesForRoute(routeKey);
            forwardWithRetry(requestContext,
                             resultPromise,
                             freshNodes,
                             triedNodes,
                             routeKey,
                             requestId,
                             retriesRemaining - 1);
        } else {
            log.error("All retries exhausted for [{}]", requestId);
            resultPromise.fail(Causes.cause("Request failed after all retries"));
        }
    }

    private void forwardToNode(HttpRequestContext requestContext,
                               Promise<HttpResponseData> resultPromise,
                               NodeId targetNode,
                               String requestId,
                               Runnable onFailure) {
        // Fast path: if the target is already known to be disconnected, fail immediately
        if (!clusterNetwork.connectedPeers()
                           .contains(targetNode)) {
            log.debug("Target node {} already disconnected, immediate retry [{}]", targetNode, requestId);
            onFailure.run();
            return;
        }
        var correlationId = KSUID.ksuid()
                                 .toString();
        // Serialize the request context
        byte[] requestData;
        try{
            requestData = serializer.encode(requestContext);
        } catch (Exception e) {
            log.error("Failed to serialize request [{}]: {}", requestId, e.getMessage());
            resultPromise.fail(Causes.cause("Request serialization failed"));
            return;
        }
        // Create pending forward entry with onFailure callback
        var internalPromise = Promise.<HttpResponseData>promise();
        var pending = new PendingForward(internalPromise, System.currentTimeMillis(), requestId, targetNode, onFailure);
        pendingForwards.put(correlationId, pending);
        pendingForwardsByNode.computeIfAbsent(targetNode,
                                              _ -> ConcurrentHashMap.newKeySet())
                             .add(correlationId);
        // Set up timeout
        internalPromise.timeout(timeSpan(forwardTimeoutMs).millis())
                       .onResult(_ -> removePendingForward(correlationId, targetNode));
        // Send forward request
        var forwardRequest = new HttpForwardRequest(selfNodeId, correlationId, requestId, requestData);
        clusterNetwork.send(targetNode, forwardRequest);
        log.trace("Forwarded request to {} [{}] correlationId={}", targetNode, requestId, correlationId);
        // Handle response
        internalPromise.onSuccess(resultPromise::succeed)
                       .onFailure(cause -> handleForwardFailure(requestId, targetNode, onFailure));
    }

    private void handleForwardFailure(String requestId, NodeId targetNode, Runnable onFailure) {
        log.warn("Failed to forward request [{}] to {}, attempting retry", requestId, targetNode);
        onFailure.run();
    }

    // ================== Response Processing ==================
    private void processForwardResponse(PendingForward pending, HttpForwardResponse response) {
        removeFromNodeIndex(response.correlationId(), pending.targetNode());
        if (response.success()) {
            handleSuccessfulForwardResponse(pending, response);
        } else {
            handleFailedForwardResponse(pending, response);
        }
    }

    private void handleSuccessfulForwardResponse(PendingForward pending, HttpForwardResponse response) {
        try{
            HttpResponseData responseData = deserializer.decode(response.payload());
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

    // ================== Node Departure ==================
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
        log.debug("Triggering retry for request [{}] due to node {} departure", pending.requestId(), departedNode);
        pending.promise()
               .fail(Causes.cause("Target node " + departedNode + " departed"));
    }

    // ================== Cleanup ==================
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
}
