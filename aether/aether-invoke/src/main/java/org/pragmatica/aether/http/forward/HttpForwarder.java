// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.forward;

import org.pragmatica.aether.http.HttpRouteRegistry;
import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardRequest;
import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardResponse;
import org.pragmatica.aether.http.forward.HttpForwardMessage.Pipeline;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.management.route.ManagementRouteError;
import org.pragmatica.aether.management.route.RouteTarget;
import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.TransportObservation;
import org.pragmatica.http.routing.HttpMethod;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
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
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


@SuppressWarnings({"JBCT-RET-01", "JBCT-RET-03"}) public interface HttpForwarder {
    Promise<HttpResponseData> forward(HttpRequestContext requestContext,
                                      String httpMethod,
                                      String pathPrefix,
                                      String requestId);
    Promise<HttpResponseData> forwardToAnyNode(HttpRequestContext requestContext, String requestId);
    Promise<HttpResponseData> forwardManagement(HttpRequestContext requestContext, String requestId);
    @MessageReceiver void onHttpForwardResponse(HttpForwardResponse response);
    @MessageReceiver void onNodeRemoved(MembershipDecision.NodeRemoved nodeRemoved);
    @MessageReceiver void onNodeDecommissioned(MembershipDecision.NodeDecommissioned nodeDecommissioned);
    // Self-shutdown cleanup hook: kept on TransportObservation stream because self-shutdown is not a cluster decision.
    @MessageReceiver void onSelfShutdown(TransportObservation.SelfShutdown selfShutdown);

    Fn1<Result<NodeId>, TaskGroup> UNASSIGNED_RESOLVER = group -> org.pragmatica.aether.slice.delegation.TaskAssignmentError.notAssigned(group)
                                                                                                                                        .result();

    Supplier<Option<NodeId>> NO_LEADER_RESOLVER = Option::none;

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
                             DEFAULT_MAX_FORWARD_RETRIES,
                             Set::of,
                             UNASSIGNED_RESOLVER,
                             NO_LEADER_RESOLVER);
    }

    static HttpForwarder httpForwarder(NodeId selfNodeId,
                                       HttpRouteRegistry routeRegistry,
                                       ClusterNetwork clusterNetwork,
                                       Serializer serializer,
                                       Deserializer deserializer,
                                       TimeSpan forwardTimeout,
                                       Supplier<Set<NodeId>> coreNodeSupplier) {
        return httpForwarder(selfNodeId,
                             routeRegistry,
                             clusterNetwork,
                             serializer,
                             deserializer,
                             forwardTimeout,
                             DEFAULT_RETRY_DELAY_MS,
                             DEFAULT_MAX_FORWARD_RETRIES,
                             coreNodeSupplier,
                             UNASSIGNED_RESOLVER,
                             NO_LEADER_RESOLVER);
    }

    static HttpForwarder httpForwarder(NodeId selfNodeId,
                                       HttpRouteRegistry routeRegistry,
                                       ClusterNetwork clusterNetwork,
                                       Serializer serializer,
                                       Deserializer deserializer,
                                       TimeSpan forwardTimeout,
                                       Supplier<Set<NodeId>> coreNodeSupplier,
                                       Fn1<Result<NodeId>, TaskGroup> taskGroupOwnerResolver) {
        return httpForwarder(selfNodeId,
                             routeRegistry,
                             clusterNetwork,
                             serializer,
                             deserializer,
                             forwardTimeout,
                             DEFAULT_RETRY_DELAY_MS,
                             DEFAULT_MAX_FORWARD_RETRIES,
                             coreNodeSupplier,
                             taskGroupOwnerResolver,
                             NO_LEADER_RESOLVER);
    }

    static HttpForwarder httpForwarder(NodeId selfNodeId,
                                       HttpRouteRegistry routeRegistry,
                                       ClusterNetwork clusterNetwork,
                                       Serializer serializer,
                                       Deserializer deserializer,
                                       TimeSpan forwardTimeout,
                                       Supplier<Set<NodeId>> coreNodeSupplier,
                                       Fn1<Result<NodeId>, TaskGroup> taskGroupOwnerResolver,
                                       Supplier<Option<NodeId>> leaderResolver) {
        return httpForwarder(selfNodeId,
                             routeRegistry,
                             clusterNetwork,
                             serializer,
                             deserializer,
                             forwardTimeout,
                             DEFAULT_RETRY_DELAY_MS,
                             DEFAULT_MAX_FORWARD_RETRIES,
                             coreNodeSupplier,
                             taskGroupOwnerResolver,
                             leaderResolver);
    }

    long DEFAULT_RETRY_DELAY_MS = 200;

    int DEFAULT_MAX_FORWARD_RETRIES = 3;

    static HttpForwarder httpForwarder(NodeId selfNodeId,
                                       HttpRouteRegistry routeRegistry,
                                       ClusterNetwork clusterNetwork,
                                       Serializer serializer,
                                       Deserializer deserializer,
                                       TimeSpan forwardTimeout,
                                       long retryDelayMs,
                                       int maxForwardRetries,
                                       Supplier<Set<NodeId>> coreNodeSupplier,
                                       Fn1<Result<NodeId>, TaskGroup> taskGroupOwnerResolver) {
        return httpForwarder(selfNodeId,
                             routeRegistry,
                             clusterNetwork,
                             serializer,
                             deserializer,
                             forwardTimeout,
                             retryDelayMs,
                             maxForwardRetries,
                             coreNodeSupplier,
                             taskGroupOwnerResolver,
                             NO_LEADER_RESOLVER);
    }

    static HttpForwarder httpForwarder(NodeId selfNodeId,
                                       HttpRouteRegistry routeRegistry,
                                       ClusterNetwork clusterNetwork,
                                       Serializer serializer,
                                       Deserializer deserializer,
                                       TimeSpan forwardTimeout,
                                       long retryDelayMs,
                                       int maxForwardRetries,
                                       Supplier<Set<NodeId>> coreNodeSupplier,
                                       Fn1<Result<NodeId>, TaskGroup> taskGroupOwnerResolver,
                                       Supplier<Option<NodeId>> leaderResolver) {
        @SuppressWarnings({"JBCT-RET-01", "JBCT-RET-03"}) record httpForwarder(NodeId selfNodeId,
                                                                               HttpRouteRegistry routeRegistry,
                                                                               ClusterNetwork clusterNetwork,
                                                                               Serializer serializer,
                                                                               Deserializer deserializer,
                                                                               TimeSpan forwardTimeout,
                                                                               long retryDelayMs,
                                                                               int maxForwardRetries,
                                                                               Map<String, PendingForward> pendingForwards,
                                                                               Map<NodeId, Set<String>> pendingForwardsByNode,
                                                                               Map<String, AtomicInteger> roundRobinCounters,
                                                                               Supplier<Set<NodeId>> coreNodeSupplier,
                                                                               Fn1<Result<NodeId>, TaskGroup> taskGroupOwnerResolver,
                                                                               Supplier<Option<NodeId>> leaderResolver) implements HttpForwarder {
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
                if (connectedNodes.isEmpty()) {
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
                                 Math.min(connectedNodes.size() - 1, maxForwardRetries),
                                 Pipeline.APP);
                return resultPromise;
            }

            @Override public Promise<HttpResponseData> forwardToAnyNode(HttpRequestContext requestContext,
                                                                        String requestId) {
                var resultPromise = Promise.<HttpResponseData>promise();
                var connectedNodes = List.copyOf(clusterNetwork.connectedPeers());
                if (connectedNodes.isEmpty()) {
                    log.warn("No connected nodes available for fallback forward [{}]", requestId);
                    resultPromise.fail(Causes.cause("No connected nodes available"));
                    return resultPromise;
                }
                var routeIdentity = "FALLBACK:*";
                forwardWithRetry(requestContext,
                                 resultPromise,
                                 connectedNodes,
                                 Set.of(),
                                 routeIdentity,
                                 requestId,
                                 Math.min(connectedNodes.size() - 1, maxForwardRetries),
                                 Pipeline.APP);
                return resultPromise;
            }

            @Override public Promise<HttpResponseData> forwardManagement(HttpRequestContext requestContext,
                                                                         String requestId) {
                var methodOpt = parseHttpMethod(requestContext.method());
                if (methodOpt.isEmpty()) {
                    log.warn("Unsupported HTTP method {} for management forward [{}]",
                             requestContext.method(),
                             requestId);
                    return Causes.cause("Unsupported HTTP method: " + requestContext.method()).promise();
                }
                return ManagementRoute.match(methodOpt.unwrap(),
                                             requestContext.path())
                .fold(_ -> {
                          log.debug("No ManagementRoute match for {} {} [{}] — falling back to any-core forward",
                                    requestContext.method(),
                                    requestContext.path(),
                                    requestId);
                          return forwardToAnyCoreNode(requestContext, requestId);
                      },
                      matched -> dispatchByTarget(matched.route(),
                                                  requestContext,
                                                  requestId));
            }

            private Promise<HttpResponseData> dispatchByTarget(ManagementRoute route,
                                                               HttpRequestContext requestContext,
                                                               String requestId) {
                return switch (route.target()){
                    case RouteTarget.LocalNode __ -> ManagementRouteError.localNotForwardable(route.name())
                                                                                             .<HttpResponseData>promise();
                    case RouteTarget.AnyCoreNode __ -> forwardToAnyCoreNode(requestContext, requestId);
                    case RouteTarget.TaskGroupTarget(var group) -> forwardToTaskGroupOwner(group,
                                                                                           requestContext,
                                                                                           requestId);
                    case RouteTarget.LeaderNode __ -> forwardToLeader(requestContext, requestId);
                    case RouteTarget.NodeIdParam(var paramIndex) -> forwardToTargetNode(route,
                                                                                        requestContext,
                                                                                        paramIndex,
                                                                                        requestId);
                };
            }

            private Promise<HttpResponseData> forwardToLeader(HttpRequestContext requestContext, String requestId) {
                var leaderOpt = leaderResolver.get();
                if (leaderOpt.isEmpty()) {
                    log.warn("No leader elected for management forward [{}]", requestId);
                    return ManagementRouteError.noLeaderElected().<HttpResponseData>promise();
                }
                var leader = leaderOpt.unwrap();
                if (leader.equals(selfNodeId)) {
                    log.debug("Local node {} is leader; signalling local handling [{}]", selfNodeId, requestId);
                    return ManagementRouteError.notLeader().<HttpResponseData>promise();
                }
                if (!clusterNetwork.connectedPeers().contains(leader)) {
                    log.warn("Leader {} is not connected for management forward [{}]", leader, requestId);
                    return ManagementRouteError.leaderDisconnected(leader.id()).<HttpResponseData>promise();
                }
                return forwardToSpecificNode(requestContext, leader, requestId);
            }

            private Promise<HttpResponseData> forwardToAnyCoreNode(HttpRequestContext requestContext,
                                                                   String requestId) {
                var resultPromise = Promise.<HttpResponseData>promise();
                var connectedCoreNodes = connectedCoreNodes();
                if (connectedCoreNodes.isEmpty()) {
                    log.warn("No connected core nodes available for management forward [{}]", requestId);
                    resultPromise.fail(Causes.cause("No core nodes available for management API"));
                    return resultPromise;
                }
                var routeIdentity = "MANAGEMENT:*";
                forwardWithRetry(requestContext,
                                 resultPromise,
                                 connectedCoreNodes,
                                 Set.of(),
                                 routeIdentity,
                                 requestId,
                                 Math.min(connectedCoreNodes.size() - 1, maxForwardRetries),
                                 Pipeline.MANAGEMENT);
                return resultPromise;
            }

            // RC1 fix (06-deployment regression): re-resolve task-group ownership on each retry
            // attempt and re-check QUIC connectedness. The prior fast-fail behavior burned the
            // entire retry budget against a single stale snapshot of (owner, connectedPeers) —
            // every retry hit the same wrong or disconnected node and returned 503. Two
            // independent failure modes both produced "Request failed after all retries":
            // (1) owner-disconnected race: a leader change reached the forwarder
            //     before the QUIC `connectedPeers()` set caught up;
            // (2) stale ownership after blue-green promote: leadership change via consensus
            //     reaches each node independently, so the forwarder may point at the old owner
            //     while the new owner has not yet activate()d.
            // Both classes are addressed by re-querying the resolver and connectedness on each
            // retry, with a small delay between attempts to let propagation converge.
            private Promise<HttpResponseData> forwardToTaskGroupOwner(TaskGroup group,
                                                                      HttpRequestContext requestContext,
                                                                      String requestId) {
                var resultPromise = Promise.<HttpResponseData>promise();
                attemptTaskGroupForward(group, requestContext, requestId, resultPromise, maxForwardRetries);
                return resultPromise;
            }

            private void attemptTaskGroupForward(TaskGroup group,
                                                 HttpRequestContext requestContext,
                                                 String requestId,
                                                 Promise<HttpResponseData> resultPromise,
                                                 int retriesRemaining) {
                var ownerResult = taskGroupOwnerResolver.apply(group);
                if (ownerResult.isFailure()) {
                    log.debug("Task group {} has no owner (retries={}) [{}]",
                              group, retriesRemaining, requestId);
                    retryTaskGroupOrFail(group, requestContext, requestId, resultPromise, retriesRemaining,
                                         () -> Causes.cause("Task group " + group + " has no owner after retries"));
                    return;
                }
                var owner = ownerResult.unwrap();
                if (!clusterNetwork.connectedPeers().contains(owner)) {
                    log.debug("Task group {} owner {} disconnected (retries={}) [{}]",
                              group, owner, retriesRemaining, requestId);
                    retryTaskGroupOrFail(group, requestContext, requestId, resultPromise, retriesRemaining,
                                         () -> ManagementRouteError.ownerDisconnected(group, owner.id()));
                    return;
                }
                forwardToSpecificNode(requestContext, owner, requestId)
                        .onSuccess(resultPromise::succeed)
                        .onFailure(cause -> {
                            log.debug("Forward to owner {} failed: {} (retries={}) [{}]",
                                      owner, cause.message(), retriesRemaining, requestId);
                            retryTaskGroupOrFail(group, requestContext, requestId, resultPromise, retriesRemaining, () -> cause);
                        });
            }

            private void retryTaskGroupOrFail(TaskGroup group,
                                              HttpRequestContext requestContext,
                                              String requestId,
                                              Promise<HttpResponseData> resultPromise,
                                              int retriesRemaining,
                                              Supplier<Cause> exhaustionCause) {
                if (retriesRemaining <= 0) {
                    resultPromise.fail(exhaustionCause.get());
                    return;
                }
                Promise.<Unit>promise()
                       .timeout(timeSpan(retryDelayMs).millis())
                       .onResult(_ -> attemptTaskGroupForward(group,
                                                              requestContext,
                                                              requestId,
                                                              resultPromise,
                                                              retriesRemaining - 1));
            }

            private Promise<HttpResponseData> forwardToSpecificNode(HttpRequestContext requestContext,
                                                                    NodeId targetNode,
                                                                    String requestId) {
                var resultPromise = Promise.<HttpResponseData>promise();
                var routeIdentity = "MANAGEMENT:" + targetNode.id();
                forwardWithRetry(requestContext,
                                 resultPromise,
                                 List.of(targetNode),
                                 Set.of(),
                                 routeIdentity,
                                 requestId,
                                 0,
                                 Pipeline.MANAGEMENT);
                return resultPromise;
            }

            /// Forward to a peer named by a path param. The param at `paramIndex` in the
            /// matched route is interpreted as a NodeId. If the target equals the local node,
            /// returns `notLocalTarget` to signal "handle locally" (parallel to `notLeader`).
            /// If the target peer is not in the connected set, returns `targetDisconnected`.
            private Promise<HttpResponseData> forwardToTargetNode(ManagementRoute route,
                                                                  HttpRequestContext requestContext,
                                                                  int paramIndex,
                                                                  String requestId) {
                var methodOpt = parseHttpMethod(requestContext.method());
                if (methodOpt.isEmpty()) {
                    return Causes.cause("Unparseable HTTP method on per-node forward: " + requestContext.method())
                                 .<HttpResponseData>promise();
                }
                var matchResult = ManagementRoute.match(methodOpt.unwrap(), requestContext.path());
                if (matchResult.isFailure()) {
                    return matchResult.fold(c -> c.<HttpResponseData>promise(),
                                            __ -> Causes.cause("unreachable")
                                                        .<HttpResponseData>promise());
                }
                var matched = matchResult.unwrap();
                var paramNames = matched.route().paramNames();
                if (paramIndex < 0 || paramIndex >= paramNames.size()) {
                    return Causes.cause("Per-node forward configured with out-of-range paramIndex=" + paramIndex + " on route " + route.name())
                                 .<HttpResponseData>promise();
                }
                var targetId = matched.params().get(paramNames.get(paramIndex));
                if (targetId == null) {
                    return Causes.cause("Per-node forward param missing on route " + route.name() + " paramIndex=" + paramIndex)
                                 .<HttpResponseData>promise();
                }
                var targetResult = NodeId.nodeId(targetId);
                if (targetResult.isFailure()) {
                    return Causes.cause("Invalid node id in per-node forward: " + targetId)
                                 .<HttpResponseData>promise();
                }
                var target = targetResult.unwrap();
                if (target.equals(selfNodeId)) {
                    log.debug("Per-node forward target {} is local; signalling local handling [{}]", target, requestId);
                    return ManagementRouteError.notLocalTarget(target.id())
                                                                .<HttpResponseData>promise();
                }
                if (!clusterNetwork.connectedPeers().contains(target)) {
                    log.warn("Per-node forward target {} not connected for [{}]", target, requestId);
                    return ManagementRouteError.targetDisconnected(target.id())
                                                                    .<HttpResponseData>promise();
                }
                return forwardToSpecificNode(requestContext, target, requestId);
            }

            private static Option<HttpMethod> parseHttpMethod(String raw) {
                return Result.lift(Causes::fromThrowable,
                                   () -> HttpMethod.valueOf(raw.toUpperCase())).option();
            }

            private List<NodeId> connectedCoreNodes() {
                var connected = clusterNetwork.connectedPeers();
                return coreNodeSupplier.get().stream()
                                           .filter(connected::contains)
                                           .toList();
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

            @Override public void onNodeRemoved(MembershipDecision.NodeRemoved nodeRemoved) {
                handleNodeDeparture(nodeRemoved.nodeId());
            }

            @Override public void onNodeDecommissioned(MembershipDecision.NodeDecommissioned nodeDecommissioned) {
                handleNodeDeparture(nodeDecommissioned.nodeId());
            }

            // Self-shutdown cleanup hook: kept on TransportObservation stream because self-shutdown is not a cluster decision.
            @Override public void onSelfShutdown(TransportObservation.SelfShutdown selfShutdown) {
                handleNodeDeparture(selfShutdown.nodeId());
            }

            private List<NodeId> filterConnectedNodes(Set<NodeId> nodes) {
                var connected = clusterNetwork.connectedPeers();
                return nodes.stream().filter(connected::contains)
                                   .toList();
            }

            private List<NodeId> freshCandidatesForRoute(String routeIdentity, Pipeline pipeline) {
                if (pipeline == Pipeline.MANAGEMENT) {return connectedCoreNodes();}
                var colonIdx = routeIdentity.indexOf(':');
                if (colonIdx == - 1) {return List.of();}
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
                                          int retriesRemaining,
                                          Pipeline pipeline) {
                var candidates = availableNodes.stream().filter(n -> !triedNodes.contains(n))
                                                      .toList();
                if (candidates.isEmpty()) {
                    handleNoCandidates(requestContext,
                                       resultPromise,
                                       routeIdentity,
                                       requestId,
                                       retriesRemaining,
                                       pipeline);
                    return;
                }
                var targetNode = selectNodeFromCandidates(routeIdentity, candidates);
                var newTriedNodes = new HashSet<>(triedNodes);
                newTriedNodes.add(targetNode);
                forwardToNode(requestContext,
                              resultPromise,
                              targetNode,
                              requestId,
                              pipeline,
                              () -> handleRetryOrExhausted(requestContext,
                                                           resultPromise,
                                                           newTriedNodes,
                                                           routeIdentity,
                                                           requestId,
                                                           retriesRemaining,
                                                           pipeline));
            }

            private void handleNoCandidates(HttpRequestContext requestContext,
                                            Promise<HttpResponseData> resultPromise,
                                            String routeIdentity,
                                            String requestId,
                                            int retriesRemaining,
                                            Pipeline pipeline) {
                if (retriesRemaining > 0) {
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
                                                          retriesRemaining,
                                                          pipeline));
                    return;
                }
                log.error("No more nodes to try for {} [{}] after all retries exhausted", routeIdentity, requestId);
                resultPromise.fail(Causes.cause("All nodes failed or unavailable"));
            }

            private void retryAfterDelay(HttpRequestContext requestContext,
                                         Promise<HttpResponseData> resultPromise,
                                         String routeIdentity,
                                         String requestId,
                                         int retriesRemaining,
                                         Pipeline pipeline) {
                var freshNodes = freshCandidatesForRoute(routeIdentity, pipeline);
                forwardWithRetry(requestContext,
                                 resultPromise,
                                 freshNodes,
                                 Set.of(),
                                 routeIdentity,
                                 requestId,
                                 retriesRemaining - 1,
                                 pipeline);
            }

            private void handleRetryOrExhausted(HttpRequestContext requestContext,
                                                Promise<HttpResponseData> resultPromise,
                                                Set<NodeId> triedNodes,
                                                String routeIdentity,
                                                String requestId,
                                                int retriesRemaining,
                                                Pipeline pipeline) {
                if (retriesRemaining > 0) {
                    log.debug("Retrying request [{}], {} retries remaining, re-querying route",
                              requestId,
                              retriesRemaining);
                    var freshNodes = freshCandidatesForRoute(routeIdentity, pipeline);
                    forwardWithRetry(requestContext,
                                     resultPromise,
                                     freshNodes,
                                     triedNodes,
                                     routeIdentity,
                                     requestId,
                                     retriesRemaining - 1,
                                     pipeline);
                } else {
                    log.error("All retries exhausted for [{}]", requestId);
                    resultPromise.fail(Causes.cause("Request failed after all retries"));
                }
            }

            private void forwardToNode(HttpRequestContext requestContext,
                                       Promise<HttpResponseData> resultPromise,
                                       NodeId targetNode,
                                       String requestId,
                                       Pipeline pipeline,
                                       Runnable onFailure) {
                if (!clusterNetwork.connectedPeers().contains(targetNode)) {
                    log.debug("Target node {} already disconnected, immediate retry [{}]", targetNode, requestId);
                    onFailure.run();
                    return;
                }
                var correlationId = KSUID.ksuid().toString();
                byte[] requestData;
                try {
                    requestData = serializer.encode(requestContext);
                } catch (Exception e) {
                    log.error("Failed to serialize request [{}]: {}", requestId, e.getMessage());
                    resultPromise.fail(Causes.cause("Request serialization failed"));
                    return;
                }
                if (pendingForwards.size() >= MAX_PENDING_FORWARDS) {
                    log.warn("Pending forwards limit reached ({}), rejecting forward [{}]",
                             MAX_PENDING_FORWARDS,
                             requestId);
                    resultPromise.fail(Causes.cause("Too many pending forwards"));
                    return;
                }
                var internalPromise = Promise.<HttpResponseData>promise();
                var pending = new PendingForward(internalPromise,
                                                 System.currentTimeMillis(),
                                                 requestId,
                                                 targetNode,
                                                 onFailure);
                pendingForwards.put(correlationId, pending);
                pendingForwardsByNode.computeIfAbsent(targetNode, _ -> ConcurrentHashMap.newKeySet()).add(correlationId);
                internalPromise.timeout(forwardTimeout);
                var forwardRequest = new HttpForwardRequest(selfNodeId, correlationId, requestId, requestData, pipeline);
                clusterNetwork.send(targetNode, forwardRequest);
                log.trace("Forwarded request to {} [{}] correlationId={}", targetNode, requestId, correlationId);
                internalPromise.onSuccess(resultPromise::succeed)
                                         .onFailure(cause -> handleInternalFailure(cause,
                                                                                   correlationId,
                                                                                   targetNode,
                                                                                   requestId,
                                                                                   onFailure));
            }

            private void handleInternalFailure(Cause cause,
                                               String correlationId,
                                               NodeId targetNode,
                                               String requestId,
                                               Runnable onFailure) {
                var removed = pendingForwards.remove(correlationId);
                if (removed != null) {removeFromNodeIndex(correlationId, targetNode);}
                if (cause instanceof CoreError.Timeout) {log.warn("Forward to {} timed out after {} [{}]",
                                                                  targetNode,
                                                                  forwardTimeout,
                                                                  requestId);}
                onFailure.run();
            }

            private void processForwardResponse(PendingForward pending, HttpForwardResponse response) {
                removeFromNodeIndex(response.correlationId(), pending.targetNode());
                if (response.success()) {handleSuccessfulForwardResponse(pending, response);} else {handleFailedForwardResponse(pending,
                                                                                                                                response);}
            }

            private void handleSuccessfulForwardResponse(PendingForward pending, HttpForwardResponse response) {
                try {
                    HttpResponseData responseData = deserializer.decode(response.payload());
                    pending.promise().succeed(responseData);
                    log.trace("Completed forward request [{}]", pending.requestId());
                } catch (Exception e) {
                    log.error("Failed to deserialize forward response [{}]: {}", pending.requestId(), e.getMessage());
                    pending.promise().fail(Causes.cause("Response deserialization failed: " + e.getMessage()));
                }
            }

            private void handleFailedForwardResponse(PendingForward pending, HttpForwardResponse response) {
                var errorMessage = new String(response.payload(), StandardCharsets.UTF_8);
                log.warn("Failed to forward request [{}]: {}", pending.requestId(), errorMessage);
                pending.promise().fail(Causes.cause("Remote processing failed: " + errorMessage));
            }

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
                for (var correlationId : correlationIds) {Option.option(pendingForwards.remove(correlationId))
                                                                       .onPresent(pending -> failPendingForwardOnDeparture(pending,
                                                                                                                           departedNode));}
            }

            private void failPendingForwardOnDeparture(PendingForward pending, NodeId departedNode) {
                log.debug("Triggering retry for request [{}] due to node {} departure",
                          pending.requestId(),
                          departedNode);
                pending.promise().fail(Causes.cause("Target node " + departedNode + " departed"));
            }

            private void removeFromNodeIndex(String correlationId, NodeId targetNode) {
                Option.option(pendingForwardsByNode.get(targetNode))
                             .onPresent(nodeCorrelations -> cleanupNodeCorrelation(nodeCorrelations,
                                                                                   correlationId,
                                                                                   targetNode));
            }

            private void cleanupNodeCorrelation(Set<String> nodeCorrelations, String correlationId, NodeId targetNode) {
                nodeCorrelations.remove(correlationId);
                if (nodeCorrelations.isEmpty()) {pendingForwardsByNode.remove(targetNode, nodeCorrelations);}
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
                                 new ConcurrentHashMap<>(),
                                 coreNodeSupplier,
                                 taskGroupOwnerResolver,
                                 leaderResolver);
    }
}
