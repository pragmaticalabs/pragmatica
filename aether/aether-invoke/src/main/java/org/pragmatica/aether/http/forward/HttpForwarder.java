package org.pragmatica.aether.http.forward;

import org.pragmatica.aether.http.HttpRouteRegistry;
import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardRequest;
import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardResponse;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.CoreError;
import org.pragmatica.lang.io.TimeSpan;
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
                                      String httpMethod,
                                      String pathPrefix,
                                      String requestId);

    /// Handle HTTP forward response from another node.
    @MessageReceiver void onHttpForwardResponse(HttpForwardResponse response);

    /// Handle node removal for immediate retry of pending forwards.
    @MessageReceiver void onNodeRemoved(TopologyChangeNotification.NodeRemoved nodeRemoved);

    /// Handle node down for immediate retry of pending forwards.
    @MessageReceiver void onNodeDown(TopologyChangeNotification.NodeDown nodeDown);

    /// Create an HttpForwarder instance with default retry settings.
    static HttpForwarder httpForwarder(NodeId selfNodeId,
                                       HttpRouteRegistry routeRegistry,
                                       ClusterNetwork clusterNetwork,
                                       Serializer serializer,
                                       Deserializer deserializer,
                                       TimeSpan forwardTimeout) {
        return httpForwarder(selfNodeId,
                             routeRegistry,
                             clusterNetwork,
                             serializer,
                             deserializer,
                             forwardTimeout,
                             DEFAULT_RETRY_DELAY_MS,
                             DEFAULT_MAX_FORWARD_RETRIES);
    }

    long DEFAULT_RETRY_DELAY_MS = 200;
    int DEFAULT_MAX_FORWARD_RETRIES = 3;

    /// Create an HttpForwarder instance with custom retry settings.
    static HttpForwarder httpForwarder(NodeId selfNodeId,
                                       HttpRouteRegistry routeRegistry,
                                       ClusterNetwork clusterNetwork,
                                       Serializer serializer,
                                       Deserializer deserializer,
                                       TimeSpan forwardTimeout,
                                       long retryDelayMs,
                                       int maxForwardRetries) {
        @SuppressWarnings({"JBCT-RET-01", "JBCT-RET-03"}) record httpForwarder( NodeId selfNodeId,
                                                                                HttpRouteRegistry routeRegistry,
                                                                                ClusterNetwork clusterNetwork,
                                                                                Serializer serializer,
                                                                                Deserializer deserializer,
                                                                                TimeSpan forwardTimeout,
                                                                                long retryDelayMs,
                                                                                int maxForwardRetries,
                                                                                Map<String, PendingForward> pendingForwards,
                                                                                Map<NodeId, Set<String>> pendingForwardsByNode,
                                                                                Map<String, AtomicInteger> roundRobinCounters) implements HttpForwarder {
            private static final Logger log = LoggerFactory.getLogger(HttpForwarder.class);
            private static final int MAX_PENDING_FORWARDS = 10_000;

            record PendingForward(Promise<HttpResponseData> promise,
                                  long createdAtMs,
                                  String requestId,
                                  NodeId targetNode,
                                  Runnable onFailure){}

            @Override public Promise<HttpResponseData> forward(HttpRequestContext requestContext,
                                                               String httpMethod,
                                                               String pathPrefix,
                                                               String requestId) {
                var resultPromise = Promise.<HttpResponseData>promise();
                var connectedNodes = filterConnectedNodes(routeRegistry.findRoute(httpMethod, pathPrefix).map(HttpRouteRegistry.RouteInfo::nodes)
                                                                                 .or(Set.of()));
                if ( connectedNodes.isEmpty()) {
                    log.warn("No connected nodes available for route {} {} [{}]", httpMethod, pathPrefix, requestId);
                    resultPromise.fail(Causes.cause("No available nodes for route"));
                    return resultPromise;
                }
                var routeIdentity = httpMethod + ":" + pathPrefix;
                forwardWithRetry(requestContext,
                                 resultPromise,
                                 connectedNodes,
                                 Set.of(),
                                 routeIdentity,
                                 requestId,
                                 Math.min(connectedNodes.size() - 1, maxForwardRetries));
                return resultPromise;
            }

            @Override public void onHttpForwardResponse(HttpForwardResponse response) {
                log.trace("Received HttpForwardResponse [{}] correlationId={} success={}",
                          response.requestId(),
                          response.correlationId(),
                          response.success());
                Option.option(pendingForwards.remove(response.correlationId())).onEmpty(() -> log.debug("[{}] Received forward response for unknown correlationId: {}",
                                                                                                        response.requestId(),
                                                                                                        response.correlationId()))
                             .onPresent(pending -> processForwardResponse(pending, response));
            }

            @Override public void onNodeRemoved(TopologyChangeNotification.NodeRemoved nodeRemoved) {
                handleNodeDeparture(nodeRemoved.nodeId());
            }

            @Override public void onNodeDown(TopologyChangeNotification.NodeDown nodeDown) {
                handleNodeDeparture(nodeDown.nodeId());
            }

            // ================== Forwarding Logic ==================
            private List<NodeId> filterConnectedNodes(Set<NodeId> nodes) {
                var connected = clusterNetwork.connectedPeers();
                return nodes.stream().filter(connected::contains)
                                   .toList();
            }

            private List<NodeId> freshCandidatesForRoute(String routeIdentity) {
                // Parse routeIdentity back to method:prefix
                var colonIdx = routeIdentity.indexOf(':');
                if ( colonIdx == - 1) {
                return List.of();}
                var method = routeIdentity.substring(0, colonIdx);
                var prefix = routeIdentity.substring(colonIdx + 1);
                return routeRegistry.findRoute(method, prefix).map(r -> filterConnectedNodes(r.nodes()))
                                              .or(List.of());
            }

            private NodeId selectNodeFromCandidates(String routeIdentity, List<NodeId> candidates) {
                var counter = roundRobinCounters.computeIfAbsent(routeIdentity, _ -> new AtomicInteger(0));
                var index = Math.abs(counter.getAndIncrement() % candidates.size());
                return candidates.get(index);
            }

            private void forwardWithRetry(HttpRequestContext requestContext,
                                          Promise<HttpResponseData> resultPromise,
                                          List<NodeId> availableNodes,
                                          Set<NodeId> triedNodes,
                                          String routeIdentity,
                                          String requestId,
                                          int retriesRemaining) {
                var candidates = availableNodes.stream().filter(n -> !triedNodes.contains(n))
                                                      .toList();
                if ( candidates.isEmpty()) {
                    handleNoCandidates(requestContext, resultPromise, routeIdentity, requestId, retriesRemaining);
                    return;
                }
                var targetNode = selectNodeFromCandidates(routeIdentity, candidates);
                var newTriedNodes = new HashSet<>(triedNodes);
                newTriedNodes.add(targetNode);
                forwardToNode(requestContext,
                              resultPromise,
                              targetNode,
                              requestId,
                              () -> handleRetryOrExhausted(requestContext,
                                                           resultPromise,
                                                           newTriedNodes,
                                                           routeIdentity,
                                                           requestId,
                                                           retriesRemaining));
            }

            private void handleNoCandidates(HttpRequestContext requestContext,
                                            Promise<HttpResponseData> resultPromise,
                                            String routeIdentity,
                                            String requestId,
                                            int retriesRemaining) {
                if ( retriesRemaining > 0) {
                    log.debug("No candidates for {} [{}], waiting {}ms before re-query ({} retries remaining)",
                              routeIdentity,
                              requestId,
                              retryDelayMs,
                              retriesRemaining);
                    Promise.<Unit>promise()
                           .timeout(timeSpan(retryDelayMs).millis())
                           .onResult(_ -> retryAfterDelay(requestContext,
                                                          resultPromise,
                                                          routeIdentity,
                                                          requestId,
                                                          retriesRemaining));
                    return;
                }
                log.error("No more nodes to try for {} [{}] after all retries exhausted", routeIdentity, requestId);
                resultPromise.fail(Causes.cause("All nodes failed or unavailable"));
            }

            private void retryAfterDelay(HttpRequestContext requestContext,
                                         Promise<HttpResponseData> resultPromise,
                                         String routeIdentity,
                                         String requestId,
                                         int retriesRemaining) {
                var freshNodes = freshCandidatesForRoute(routeIdentity);
                forwardWithRetry(requestContext,
                                 resultPromise,
                                 freshNodes,
                                 Set.of(),
                                 routeIdentity,
                                 requestId,
                                 retriesRemaining - 1);
            }

            private void handleRetryOrExhausted(HttpRequestContext requestContext,
                                                Promise<HttpResponseData> resultPromise,
                                                Set<NodeId> triedNodes,
                                                String routeIdentity,
                                                String requestId,
                                                int retriesRemaining) {
                if ( retriesRemaining > 0) {
                    log.debug("Retrying request [{}], {} retries remaining, re-querying route",
                              requestId,
                              retriesRemaining);
                    var freshNodes = freshCandidatesForRoute(routeIdentity);
                    forwardWithRetry(requestContext,
                                     resultPromise,
                                     freshNodes,
                                     triedNodes,
                                     routeIdentity,
                                     requestId,
                                     retriesRemaining - 1);
                } else

























                {
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
                if ( !clusterNetwork.connectedPeers().contains(targetNode)) {
                    log.debug("Target node {} already disconnected, immediate retry [{}]", targetNode, requestId);
                    onFailure.run();
                    return;
                }
                var correlationId = KSUID.ksuid().toString();
                // Serialize the request context
                byte[] requestData;
                try {
                    requestData = serializer.encode(requestContext);
                }

























                catch (Exception e) {
                    log.error("Failed to serialize request [{}]: {}", requestId, e.getMessage());
                    resultPromise.fail(Causes.cause("Request serialization failed"));
                    return;
                }
                if ( pendingForwards.size() >= MAX_PENDING_FORWARDS) {
                    log.warn("Pending forwards limit reached ({}), rejecting forward [{}]",
                             MAX_PENDING_FORWARDS,
                             requestId);
                    resultPromise.fail(Causes.cause("Too many pending forwards"));
                    return;
                }
                // Create pending forward entry with onFailure callback
                var internalPromise = Promise.<HttpResponseData>promise();
                var pending = new PendingForward(internalPromise,
                                                 System.currentTimeMillis(),
                                                 requestId,
                                                 targetNode,
                                                 onFailure);
                pendingForwards.put(correlationId, pending);
                pendingForwardsByNode.computeIfAbsent(targetNode, _ -> ConcurrentHashMap.newKeySet())
                .add(correlationId);
                // Set up timeout — will resolve internalPromise with CoreError.Timeout
                internalPromise.timeout(forwardTimeout);
                // Send forward request
                var forwardRequest = new HttpForwardRequest(selfNodeId, correlationId, requestId, requestData);
                clusterNetwork.send(targetNode, forwardRequest);
                log.trace("Forwarded request to {} [{}] correlationId={}", targetNode, requestId, correlationId);
                // Handle resolution — atomic removal ensures only one path processes the result
                internalPromise.onSuccess(resultPromise::succeed)
                .onFailure(cause -> handleInternalFailure(cause, correlationId, targetNode, requestId, onFailure));
            }

            private void handleInternalFailure(Cause cause,
                                               String correlationId,
                                               NodeId targetNode,
                                               String requestId,
                                               Runnable onFailure) {
                var removed = pendingForwards.remove(correlationId);
                if ( removed != null) {
                removeFromNodeIndex(correlationId, targetNode);}
                if ( cause instanceof CoreError.Timeout) {
                log.warn("Forward to {} timed out after {} [{}]", targetNode, forwardTimeout, requestId);}
                onFailure.run();
            }

            // ================== Response Processing ==================
            private void processForwardResponse(PendingForward pending, HttpForwardResponse response) {
                removeFromNodeIndex(response.correlationId(), pending.targetNode());
                if ( response.success()) {
                handleSuccessfulForwardResponse(pending, response);} else
                {
                handleFailedForwardResponse(pending, response);}
            }

            private void handleSuccessfulForwardResponse(PendingForward pending, HttpForwardResponse response) {
                try {
                    HttpResponseData responseData = deserializer.decode(response.payload());
                    pending.promise().succeed(responseData);
                    log.trace("Completed forward request [{}]", pending.requestId());
                }

























                catch (Exception e) {
                    log.error("Failed to deserialize forward response [{}]: {}", pending.requestId(), e.getMessage());
                    pending.promise().fail(Causes.cause("Response deserialization failed: " + e.getMessage()));
                }
            }

            private void handleFailedForwardResponse(PendingForward pending, HttpForwardResponse response) {
                var errorMessage = new String(response.payload(), StandardCharsets.UTF_8);
                log.warn("Failed to forward request [{}]: {}", pending.requestId(), errorMessage);
                pending.promise().fail(Causes.cause("Remote processing failed: " + errorMessage));
            }

            // ================== Node Departure ==================
            private void handleNodeDeparture(NodeId departedNode) {
                Option.option(pendingForwardsByNode.remove(departedNode)).filter(ids -> !ids.isEmpty())
                             .onPresent(correlationIds -> retryPendingForwards(departedNode, correlationIds));
            }

            private void retryPendingForwards(NodeId departedNode, Set<String> correlationIds) {
                var affectedRequestIds = correlationIds.stream().map(pendingForwards::get)
                                                              .map(Option::option)
                                                              .flatMap(Option::stream)
                                                              .map(PendingForward::requestId)
                                                              .limit(5)
                                                              .toList();
                log.debug("Node {} departed, triggering immediate retry for {} pending forwards, requestIds={}",
                          departedNode,
                          correlationIds.size(),
                          affectedRequestIds);
                for ( var correlationId : correlationIds) {
                Option.option(pendingForwards.remove(correlationId))
                .onPresent(pending -> failPendingForwardOnDeparture(pending, departedNode));}
            }

            private void failPendingForwardOnDeparture(PendingForward pending, NodeId departedNode) {
                log.debug("Triggering retry for request [{}] due to node {} departure",
                          pending.requestId(),
                          departedNode);
                pending.promise().fail(Causes.cause("Target node " + departedNode + " departed"));
            }

            // ================== Cleanup ==================
            private void removeFromNodeIndex(String correlationId, NodeId targetNode) {
                Option.option(pendingForwardsByNode.get(targetNode))
                .onPresent(nodeCorrelations -> cleanupNodeCorrelation(nodeCorrelations, correlationId, targetNode));
            }

            private void cleanupNodeCorrelation(Set<String> nodeCorrelations, String correlationId, NodeId targetNode) {
                nodeCorrelations.remove(correlationId);
                if ( nodeCorrelations.isEmpty()) {
                pendingForwardsByNode.remove(targetNode, nodeCorrelations);}
            }
        }
        return new httpForwarder(selfNodeId,
                                 routeRegistry,
                                 clusterNetwork,
                                 serializer,
                                 deserializer,
                                 forwardTimeout,
                                 retryDelayMs,
                                 maxForwardRetries,
                                 new ConcurrentHashMap<>(),
                                 new ConcurrentHashMap<>(),
                                 new ConcurrentHashMap<>());
    }
}
