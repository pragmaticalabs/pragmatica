// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.forward;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.pragmatica.aether.http.HttpRouteRegistry;
import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardRequest;
import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardResponse;
import org.pragmatica.aether.http.forward.HttpForwardMessage.Pipeline;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.management.route.ManagementRouteError;
import org.pragmatica.aether.management.route.MatchedRoute;
import org.pragmatica.aether.management.route.RouteTarget;
import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.TransportObservation;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.CoreError;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.Deadline;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.utility.IdGenerator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


@SuppressWarnings({"JBCT-RET-01", "JBCT-RET-03"})
public interface HttpForwarder {
    Promise<HttpResponseData> forward(HttpRequestContext requestContext,
                                      String httpMethod,
                                      String pathPrefix,
                                      String requestId);

    /// #1678: forward to the nodes serving exactly `route` -- a route already narrowed to the sibling shape the
    /// request is served by, so a request for one sibling never lands on a node serving only another that shares its
    /// base path. The default forwards by the route's base path, as a forwarder without shape knowledge does.
    default Promise<HttpResponseData> forward(HttpRequestContext requestContext,
                                              HttpRouteRegistry.RouteInfo route,
                                              String requestId) {
        return forward(requestContext, route.httpMethod(), route.pathPrefix(), requestId);
    }

    Promise<HttpResponseData> forwardToAnyNode(HttpRequestContext requestContext, String requestId);

    /// Entry for a CLIENT-originated management request: by definition there is no previous hop.
    default Promise<HttpResponseData> forwardManagement(HttpRequestContext requestContext, String requestId) {
        return forwardManagement(requestContext, requestId, Option.none());
    }

    /// Entry carrying the hop this request already took, for owner-forwarded routes (#1039).
    ///
    /// `previousHop` is deliberately a PARAMETER and not a header. The earlier design stamped
    /// `X-Aether-Owner-Forwarded-By` onto the forwarded request and read it back, which put the loop
    /// guard's only input inside data a client controls: nothing in this repo strips inbound headers
    /// (`headers().remove` / `removeHeader`: zero sites), so any caller could set it and change the
    /// dispatch decision on a first hop. Hop state now travels where a client cannot reach it.
    ///
    /// Supply `Option.none()` for anything arriving over HTTP.
    ///
    /// NO PRODUCTION CALLER SUPPLIES A PRESENT VALUE, and none is expected to: a forwarded management
    /// request is dispatched by `router.handle` on the receiving node and never re-enters this
    /// forwarder, so the loop guard below cannot fire in production. The reachable skew guard is
    /// `ManagementServerImpl.checkForwardedPartitionOwner`, which re-resolves the owner on the
    /// RECEIVING node and refuses there — strictly stronger, because it fires on a disagreement
    /// regardless of hop count. This parameter is retained pending a decision to retire it, not
    /// because anything depends on it; the only present value it ever sees is a test's.
    Promise<HttpResponseData> forwardManagement(HttpRequestContext requestContext,
                                                String requestId,
                                                Option<NodeId> previousHop);

    @MessageReceiver
    void onHttpForwardResponse(HttpForwardResponse response);

    /// The target refused the request before dispatching it (see [HttpForwardMessage.HttpForwardNotApplied]).
    @MessageReceiver
    void onHttpForwardNotApplied(HttpForwardMessage.HttpForwardNotApplied notApplied);

    @MessageReceiver
    void onNodeRemoved(MembershipDecision.NodeRemoved nodeRemoved);

    /// Role-neutral cleanup; does not mutate the core topology.
    org.pragmatica.lang.Unit onNodeDeparture(NodeId node);

    @MessageReceiver
    void onNodeDecommissioned(MembershipDecision.NodeDecommissioned nodeDecommissioned);

    // Self-shutdown cleanup hook: kept on TransportObservation stream because self-shutdown is not a cluster decision.
    @MessageReceiver
    void onSelfShutdown(TransportObservation.SelfShutdown selfShutdown);

    Fn1<Result<NodeId>, TaskGroup> UNASSIGNED_RESOLVER = group -> org.pragmatica.aether.slice.delegation.TaskAssignmentError.notAssigned(group)
                                                                                                                            .result();

    Supplier<Option<NodeId>> NO_LEADER_RESOLVER = Option::none;
    /// Response header stamped onto a LEADER-forwarded management response naming the node that
    /// actually produced the body. Lets a caller distinguish the leader's view from the receiving
    /// node's view on forwarded endpoints (e.g. /api/cluster/status) — the "hall of mirrors"
    /// diagnostic gap surfaced during the zero-leader wedge investigation.
    String SERVED_BY_HEADER = "X-Aether-Served-By";

    /// Resolves the HRW owner a [RouteTarget.PartitionOwner] route must be forwarded to (#1039).
    ///
    /// Injected rather than computed here: the destination is `hrw(engineKey, partition)`, and the
    /// engine key comes from `StreamManager.engineKey` (the full `ns:stream:version` address in every
    /// namespace, `system` included), which lives in module `node`. Re-implementing that
    /// reduction in this module would give one declaration two engine keys — the defect tracked by
    /// #1040 — so the node supplies the whole resolution, owner included, reading the SAME resolver
    /// the replica-snapshot handler reads.
    interface PartitionOwnerResolver {
        Option<NodeId> resolve(MatchedRoute matched, int partitionParamIndex);
    }

    /// Default for forwarders built without a node to ask (tests, app-pipeline-only wiring): no owner
    /// is resolvable, so an owner-targeted route fails with `PartitionOwnerUnresolved` rather than
    /// guessing a destination.
    PartitionOwnerResolver NO_PARTITION_OWNER_RESOLVER = (_, _) -> Option.none();

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
                             leaderResolver,
                             AccessibilityFilter.IDENTITY);
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
                                       Supplier<Option<NodeId>> leaderResolver,
                                       AccessibilityFilter accessibilityFilter) {
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
                             leaderResolver,
                             accessibilityFilter,
                             NO_PARTITION_OWNER_RESOLVER);
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
                                       Supplier<Option<NodeId>> leaderResolver,
                                       PartitionOwnerResolver partitionOwnerResolver) {
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
                             leaderResolver,
                             AccessibilityFilter.IDENTITY,
                             partitionOwnerResolver);
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
                                       Supplier<Option<NodeId>> leaderResolver,
                                       AccessibilityFilter accessibilityFilter,
                                       PartitionOwnerResolver partitionOwnerResolver) {
        @SuppressWarnings({"JBCT-RET-01", "JBCT-RET-03"})
        record httpForwarder(NodeId selfNodeId,
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
                             Supplier<Option<NodeId>> leaderResolver,
                             AccessibilityFilter accessibilityFilter,
                             PartitionOwnerResolver partitionOwnerResolver) implements HttpForwarder {
            private static final Logger log = LoggerFactory.getLogger(HttpForwarder.class);
            private static final int MAX_PENDING_FORWARDS = 10_000;
            /// Below this much remaining budget the hunt stops: another hop cannot answer before the
            /// client (or the forwarding sender) is gone, so starting one only feeds zombie work.
            private static final TimeSpan BUDGET_FLOOR = timeSpan(200).millis();
            private static final Cause BUDGET_EXHAUSTED = Causes.cause("Request budget exhausted before a node answered");

            /// RFC 9110 §9.2.2. Every other method, and any method this node cannot name, is treated as
            /// non-idempotent: a forwarded attempt that may have been delivered is never re-sent for it.
            private static final Set<HttpMethod> IDEMPOTENT_METHODS = EnumSet.of(HttpMethod.GET,
                                                                                 HttpMethod.HEAD,
                                                                                 HttpMethod.OPTIONS,
                                                                                 HttpMethod.TRACE,
                                                                                 HttpMethod.PUT,
                                                                                 HttpMethod.DELETE);

            /// A hop failure that PROVES the request was not applied on the target: the frame never left this node,
            /// or the target refused it before dispatch. Re-sending it elsewhere cannot run it twice, whatever its
            /// method. Every other hop failure (timeout, a failure answer from the slice, target departure,
            /// undecodable response) may follow an application.
            enum Undelivered implements Cause {
                TARGET_NOT_CONNECTED("Target node not connected; nothing was sent"),
                SEND_REFUSED("Transport refused the forward before writing it"),
                /// The target answered [HttpForwardMessage.HttpForwardNotApplied]: it received the request but refused
                /// it before any router or slice code ran (route not found locally, wire budget exhausted on arrival,
                /// request undecodable). Delivered, but provably not applied, which is what re-sending needs.
                TARGET_DID_NOT_APPLY("Target refused the forward before dispatching it");
                private final String message;
                Undelivered(String message) {
                    this.message = message;
                }
                @Override
                public String message() {
                    return message;
                }
            }

            record PendingForward(Promise<HttpResponseData> promise,
                                  long createdAtMs,
                                  String requestId,
                                  NodeId targetNode,
                                  Consumer<Cause> onFailure) {}

            @Override
            public Promise<HttpResponseData> forward(HttpRequestContext requestContext,
                                                     String httpMethod,
                                                     String pathPrefix,
                                                     String requestId) {
                return forwardAmong(requestContext,
                                    httpMethod,
                                    pathPrefix,
                                    routeRegistry.findRoute(httpMethod, pathPrefix)
                                                 .map(HttpRouteRegistry.RouteInfo::nodes)
                                                 .or(Set.of()),
                                    "",
                                    requestId);
            }

            @Override
            public Promise<HttpResponseData> forward(HttpRequestContext requestContext,
                                                     HttpRouteRegistry.RouteInfo route,
                                                     String requestId) {
                return forwardAmong(requestContext,
                                    route.httpMethod(),
                                    route.pathPrefix(),
                                    route.nodes(),
                                    route.shapeKey(),
                                    requestId);
            }

            private Promise<HttpResponseData> forwardAmong(HttpRequestContext requestContext,
                                                           String httpMethod,
                                                           String pathPrefix,
                                                           Set<NodeId> serving,
                                                           String shapeKey,
                                                           String requestId) {
                var resultPromise = Promise.<HttpResponseData> promise();
                var connectedNodes = filterConnectedNodes(serving);

                if (connectedNodes.isEmpty()) {
                    log.warn("No connected nodes available for route {} {} [{}]", httpMethod, pathPrefix, requestId);
                    resultPromise.fail(ForwardError.NotSent.FACTORY.apply(Causes.cause("No available nodes for route")));

                    return resultPromise;
                }
                // #1678: the identity names the sibling SHAPE too, so a retry re-reads candidates for that sibling only.
                var routeIdentity = httpMethod + ":" + pathPrefix + shapeKey;

                forwardWithRetry(requestContext,
                                 resultPromise,
                                 connectedNodes,
                                 Set.of(),
                                 routeIdentity,
                                 requestId,
                                 Math.min(connectedNodes.size() - 1, maxForwardRetries),
                                 Pipeline.APP,
                                 Deadline.current(),
                                 1,
                                 false);

                return resultPromise;
            }

            @Override
            public Promise<HttpResponseData> forwardToAnyNode(HttpRequestContext requestContext, String requestId) {
                var resultPromise = Promise.<HttpResponseData> promise();
                var connectedNodes = accessibilityFilter.keepOnlyAccessible(List.copyOf(clusterNetwork.connectedPeers()));

                if (connectedNodes.isEmpty()) {
                    log.warn("No connected nodes available for fallback forward [{}]", requestId);
                    resultPromise.fail(ForwardError.NotSent.FACTORY.apply(Causes.cause("No connected nodes available")));

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
                                 Pipeline.APP,
                                 Deadline.current(),
                                 1,
                                 false);

                return resultPromise;
            }

            @Override
            public Promise<HttpResponseData> forwardManagement(HttpRequestContext requestContext,
                                                               String requestId,
                                                               Option<NodeId> previousHop) {
                var methodOpt = parseHttpMethod(requestContext.method());

                if (methodOpt.isEmpty()) {
                    log.warn("Unsupported HTTP method {} for management forward [{}]",
                             requestContext.method(),
                             requestId);

                    return Causes.cause("Unsupported HTTP method: " + requestContext.method()).promise();
                }
                // Captured ONCE at the public entry: every path below hops through schedulers and
                // response callbacks where the ScopedValue binding is gone, so the deadline threads
                // through as an explicit parameter from here on.
                var deadline = Deadline.current();

                return ManagementRoute.match(methodOpt.unwrap(),
                                             requestContext.path())
                                      .fold(_ -> {
                                                log.debug("No ManagementRoute match for {} {} [{}] — falling back to any-core forward",
                                                          requestContext.method(),
                                                          requestContext.path(),
                                                          requestId);

                                                return forwardToAnyCoreNode(requestContext, requestId, deadline);
                                            },
                                            matched -> dispatchByTarget(previousHop,
                                                                        matched.route(),
                                                                        requestContext,
                                                                        requestId,
                                                                        deadline));
            }

            private Promise<HttpResponseData> dispatchByTarget(Option<NodeId> previousHop,
                                                               ManagementRoute route,
                                                               HttpRequestContext requestContext,
                                                               String requestId,
                                                               Deadline deadline) {
                return switch (route.target()) {
                    case RouteTarget.LocalNode __ -> ManagementRouteError.localNotForwardable(route.name()).<HttpResponseData> promise();
                    case RouteTarget.AnyCoreNode __ -> forwardToAnyCoreNode(requestContext, requestId, deadline);
                    case RouteTarget.TaskGroupTarget(var group) -> forwardToTaskGroupOwner(group,
                                                                                           requestContext,
                                                                                           requestId,
                                                                                           deadline);
                    case RouteTarget.LeaderNode __ -> forwardToLeader(requestContext, requestId, deadline);
                    case RouteTarget.NodeIdParam(var paramIndex) -> forwardToTargetNode(route,
                                                                                        requestContext,
                                                                                        paramIndex,
                                                                                        requestId,
                                                                                        deadline);
                    case RouteTarget.PartitionOwner(var partitionParamIndex) -> forwardToPartitionOwner(previousHop,
                                                                                                        route,
                                                                                                        requestContext,
                                                                                                        partitionParamIndex,
                                                                                                        requestId,
                                                                                                        deadline);
                };
            }

            private Promise<HttpResponseData> forwardToLeader(HttpRequestContext requestContext,
                                                              String requestId,
                                                              Deadline deadline) {
                var leaderOpt = leaderResolver.get();

                if (leaderOpt.isEmpty()) {
                    log.warn("No leader elected for management forward [{}]", requestId);

                    return ManagementRouteError.noLeaderElected().<HttpResponseData> promise();
                }

                var leader = leaderOpt.unwrap();

                if (leader.equals(selfNodeId)) {
                    log.debug("Local node {} is leader; signalling local handling [{}]", selfNodeId, requestId);

                    return ManagementRouteError.notLeader().<HttpResponseData> promise();
                }

                if (!clusterNetwork.connectedPeers().contains(leader)) {
                    log.warn("Leader {} is not connected for management forward [{}]", leader, requestId);

                    return ManagementRouteError.leaderDisconnected(leader.id()).<HttpResponseData> promise();
                }
                // Stamp the answering node into the forwarded response so a caller can tell WHICH
                // node's view a LEADER-targeted response describes. Without this, a forwarded
                // /api/cluster/status reads as if it came from the node that received the request,
                // not the leader that produced it (the "hall of mirrors" that nearly defeated
                // tonight's zero-leader diagnosis).
                return forwardToSpecificNode(requestContext, leader, requestId, deadline, 1).map(response -> withServedBy(response,
                                                                                                                          leader));
            }

            /// Returns a copy of `response` with the `X-Aether-Served-By` header set to the node
            /// that actually produced the body. Existing headers are preserved; the served-by entry
            /// overrides any prior value of the same key.
            private HttpResponseData withServedBy(HttpResponseData response, NodeId servedBy) {
                var headers = new LinkedHashMap<>(response.headers());

                headers.put(SERVED_BY_HEADER, servedBy.id());

                return new HttpResponseData(response.statusCode(), headers, response.body());
            }

            private Promise<HttpResponseData> forwardToAnyCoreNode(HttpRequestContext requestContext,
                                                                   String requestId,
                                                                   Deadline deadline) {
                var resultPromise = Promise.<HttpResponseData> promise();
                var connectedCoreNodes = connectedCoreNodes();

                if (connectedCoreNodes.isEmpty()) {
                    log.warn("No connected core nodes available for management forward [{}]", requestId);
                    resultPromise.fail(ForwardError.NotSent.FACTORY.apply(Causes.cause("No core nodes available for management API")));

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
                                 Pipeline.MANAGEMENT,
                                 deadline,
                                 1,
                                 false);

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
                                                                      String requestId,
                                                                      Deadline deadline) {
                var resultPromise = Promise.<HttpResponseData> promise();

                attemptTaskGroupForward(group, requestContext, requestId, resultPromise, maxForwardRetries, deadline, false);

                return resultPromise;
            }

            private void attemptTaskGroupForward(TaskGroup group,
                                                 HttpRequestContext requestContext,
                                                 String requestId,
                                                 Promise<HttpResponseData> resultPromise,
                                                 int retriesRemaining,
                                                 Deadline deadline,
                                                 boolean delivered) {
                var ownerResult = taskGroupOwnerResolver.apply(group);

                if (ownerResult.isFailure()) {
                    log.debug("Task group {} has no owner (retries={}) [{}]", group, retriesRemaining, requestId);
                    retryTaskGroupOrFail(group,
                                         requestContext,
                                         requestId,
                                         resultPromise,
                                         retriesRemaining,
                                         deadline,
                                         delivered,
                                         () -> Causes.cause("Task group " + group + " has no owner after retries"));

                    return;
                }

                var owner = ownerResult.unwrap();

                if (!clusterNetwork.connectedPeers().contains(owner)) {
                    log.debug("Task group {} owner {} disconnected (retries={}) [{}]",
                              group,
                              owner,
                              retriesRemaining,
                              requestId);
                    retryTaskGroupOrFail(group,
                                         requestContext,
                                         requestId,
                                         resultPromise,
                                         retriesRemaining,
                                         deadline,
                                         delivered,
                                         () -> ManagementRouteError.ownerDisconnected(group, owner.id()));

                    return;
                }

                forwardToSpecificNode(requestContext, owner, requestId, deadline, retriesRemaining + 1).onSuccess(resultPromise::succeed)
                                     .onFailure(cause -> onTaskGroupForwardFailure(group,
                                                                                   owner,
                                                                                   requestContext,
                                                                                   requestId,
                                                                                   resultPromise,
                                                                                   retriesRemaining,
                                                                                   deadline,
                                                                                   delivered,
                                                                                   cause));
            }

            /// A terminal [ForwardError] is final (A1: a non-idempotent attempt that may have been delivered, or a
            /// request no frame can carry), so this outer loop must not re-send it to the same or a re-resolved owner.
            private void onTaskGroupForwardFailure(TaskGroup group,
                                                   NodeId owner,
                                                   HttpRequestContext requestContext,
                                                   String requestId,
                                                   Promise<HttpResponseData> resultPromise,
                                                   int retriesRemaining,
                                                   Deadline deadline,
                                                   boolean delivered,
                                                   Cause cause) {
                log.debug("Forward to owner {} failed: {} (retries={}) [{}]",
                          owner,
                          cause.message(),
                          retriesRemaining,
                          requestId);
                if (cause instanceof ForwardError && cause.isTerminal()) {
                    resultPromise.fail(cause);

                    return;
                }

                retryTaskGroupOrFail(group,
                                     requestContext,
                                     requestId,
                                     resultPromise,
                                     retriesRemaining,
                                     deadline,
                                     delivered || cause instanceof ForwardError.OutcomeUnknown,
                                     () -> cause);
            }

            private void retryTaskGroupOrFail(TaskGroup group,
                                              HttpRequestContext requestContext,
                                              String requestId,
                                              Promise<HttpResponseData> resultPromise,
                                              int retriesRemaining,
                                              Deadline deadline,
                                              boolean delivered,
                                              Supplier<Cause> exhaustionCause) {
                if (retriesRemaining <= 0) {
                    resultPromise.fail(taskGroupExhausted(delivered, exhaustionCause.get()));

                    return;
                }

                if (deadline.expired(BUDGET_FLOOR)) {
                    resultPromise.fail(exhausted(delivered, BUDGET_EXHAUSTED));

                    return;
                }

                Promise.<Unit> promise()
                       .timeout(timeSpan(retryDelayMs).millis())
                       .onResult(_ -> attemptTaskGroupForward(group,
                                                              requestContext,
                                                              requestId,
                                                              resultPromise,
                                                              retriesRemaining - 1,
                                                              deadline,
                                                              delivered));
            }

            /// F8: the outer task-group loop remembers whether ANY attempt may have reached an owner. If one did, the
            /// result is "outcome unknown" (503, know 14a3606152) even when the last attempt never left this node; if
            /// none did, the last cause stands (an owner that could not be resolved or reached keeps its own error).
            private static Cause taskGroupExhausted(boolean delivered, Cause last) {
                return delivered && !(last instanceof ForwardError.OutcomeUnknown)
                       ? ForwardError.OutcomeUnknown.FACTORY.apply(last)
                       : last;
            }

            /// `budgetShares` counts the attempts an OUTER retry loop still has ahead of it (the
            /// task-group path passes its retries-remaining + 1) so the hop share divides across
            /// them; single-shot callers pass 1.
            private Promise<HttpResponseData> forwardToSpecificNode(HttpRequestContext requestContext,
                                                                    NodeId targetNode,
                                                                    String requestId,
                                                                    Deadline deadline,
                                                                    int budgetShares) {
                var resultPromise = Promise.<HttpResponseData> promise();
                var routeIdentity = "MANAGEMENT:" + targetNode.id();

                forwardWithRetry(requestContext,
                                 resultPromise,
                                 List.of(targetNode),
                                 Set.of(),
                                 routeIdentity,
                                 requestId,
                                 0,
                                 Pipeline.MANAGEMENT,
                                 deadline,
                                 budgetShares,
                                 false);

                return resultPromise;
            }

            /// Forward to a peer named by a path param. The param at `paramIndex` in the
            /// matched route is interpreted as a NodeId. If the target equals the local node,
            /// returns `notLocalTarget` to signal "handle locally" (parallel to `notLeader`).
            /// If the target peer is not in the connected set, returns `targetDisconnected`.
            private Promise<HttpResponseData> forwardToTargetNode(ManagementRoute route,
                                                                  HttpRequestContext requestContext,
                                                                  int paramIndex,
                                                                  String requestId,
                                                                  Deadline deadline) {
                var methodOpt = parseHttpMethod(requestContext.method());

                if (methodOpt.isEmpty()) {
                    return Causes.cause("Unparseable HTTP method on per-node forward: " + requestContext.method()).<HttpResponseData> promise();
                }

                var matchResult = ManagementRoute.match(methodOpt.unwrap(), requestContext.path());

                if (matchResult.isFailure()) {
                    return matchResult.fold(c -> c.<HttpResponseData> promise(),
                                            __ -> Causes.cause("unreachable").<HttpResponseData> promise());
                }

                var matched = matchResult.unwrap();
                var paramNames = matched.route().paramNames();

                if (paramIndex < 0 || paramIndex >= paramNames.size()) {
                    return Causes.cause("Per-node forward configured with out-of-range paramIndex=" + paramIndex
                                       + " on route " + route.name()).<HttpResponseData> promise();
                }

                var targetId = matched.params().get(paramNames.get(paramIndex));

                if (targetId == null) {
                    return Causes.cause("Per-node forward param missing on route " + route.name()
                                       + " paramIndex=" + paramIndex).<HttpResponseData> promise();
                }

                var targetResult = NodeId.nodeId(targetId);

                if (targetResult.isFailure()) {
                    return Causes.cause("Invalid node id in per-node forward: " + targetId).<HttpResponseData> promise();
                }

                var target = targetResult.unwrap();

                if (target.equals(selfNodeId)) {
                    log.debug("Per-node forward target {} is local; signalling local handling [{}]", target, requestId);

                    return ManagementRouteError.notLocalTarget(target.id()).<HttpResponseData> promise();
                }

                if (!clusterNetwork.connectedPeers().contains(target)) {
                    log.warn("Per-node forward target {} not connected for [{}]", target, requestId);

                    return ManagementRouteError.targetDisconnected(target.id()).<HttpResponseData> promise();
                }

                return forwardToSpecificNode(requestContext, target, requestId, deadline, 1);
            }

            /// Forward to the deterministic HRW owner of the stream partition this request names
            /// (#1039). Parallel to [#forwardToTargetNode] in every signal it returns — `notLocalTarget`
            /// means "handle locally", `targetDisconnected` means the owner is unreachable — and differs
            /// in only one way: the destination is COMPUTED by `partitionOwnerResolver` rather than read
            /// out of a path param, because no param carries it.
            ///
            /// The loop guard runs FIRST, before any resolution: a request already marked as
            /// owner-forwarded must not be forwarded again whatever this node's membership view says,
            /// since disagreeing views are exactly what produces the cycle.
            private Promise<HttpResponseData> forwardToPartitionOwner(Option<NodeId> previousHop,
                                                                      ManagementRoute route,
                                                                      HttpRequestContext requestContext,
                                                                      int partitionParamIndex,
                                                                      String requestId,
                                                                      Deadline deadline) {
                if (previousHop.isPresent()) {
                    var hopId = previousHop.map(NodeId::id).or("");

                    log.warn("Owner-forward loop on {} [{}]: already forwarded by {}", route.name(), requestId, hopId);

                    return ManagementRouteError.ownerForwardLoop(route.name(),
                                                                 hopId)
                                               .<HttpResponseData> promise();
                }

                return rematchManagementRoute(requestContext).fold(cause -> cause.<HttpResponseData> promise(),
                                                                   matched -> forwardToResolvedOwner(route,
                                                                                                     requestContext,
                                                                                                     matched,
                                                                                                     partitionParamIndex,
                                                                                                     requestId,
                                                                                                     deadline));
            }

            private Promise<HttpResponseData> forwardToResolvedOwner(ManagementRoute route,
                                                                     HttpRequestContext requestContext,
                                                                     MatchedRoute matched,
                                                                     int partitionParamIndex,
                                                                     String requestId,
                                                                     Deadline deadline) {
                var ownerOpt = partitionOwnerResolver.resolve(matched, partitionParamIndex);

                if (ownerOpt.isEmpty()) {
                    log.warn("No partition owner resolvable for {} {} [{}]",
                             route.name(),
                             requestContext.path(),
                             requestId);

                    return ManagementRouteError.partitionOwnerUnresolved(route.name(),
                                                                         requestContext.path())
                                               .<HttpResponseData> promise();
                }

                var owner = ownerOpt.unwrap();

                if (owner.equals(selfNodeId)) {
                    log.debug("Partition owner {} is local; signalling local handling [{}]", owner, requestId);

                    return ManagementRouteError.notLocalTarget(owner.id()).<HttpResponseData> promise();
                }

                if (!clusterNetwork.connectedPeers().contains(owner)) {
                    log.warn("Partition owner {} not connected for [{}]", owner, requestId);

                    return ManagementRouteError.targetDisconnected(owner.id()).<HttpResponseData> promise();
                }
                // Stamped for the same reason the leader path stamps it: the body describes the OWNER's
                // registry, and without the header a caller cannot tell that view from the receiving
                // node's own — the distinction #1039 exists to restore.
                return forwardToSpecificNode(requestContext, owner, requestId, deadline, 1).map(response -> withServedBy(response,
                                                                                                                         owner));
            }

            private static Result<MatchedRoute> rematchManagementRoute(HttpRequestContext requestContext) {
                return parseHttpMethod(requestContext.method()).toResult(Causes.cause("Unparseable HTTP method on owner forward: " + requestContext.method()))
                                      .flatMap(method -> ManagementRoute.match(method,
                                                                               requestContext.path()));
            }

            private static Option<HttpMethod> parseHttpMethod(String raw) {
                return Result.lift(Causes::fromThrowable,
                                   () -> HttpMethod.valueOf(raw.toUpperCase()))
                             .option();
            }

            private List<NodeId> connectedCoreNodes() {
                var connected = clusterNetwork.connectedPeers();
                var connectedCore = coreNodeSupplier.get().stream().filter(connected::contains).toList();

                return accessibilityFilter.keepOnlyAccessible(connectedCore);
            }

            @Override
            public void onHttpForwardResponse(HttpForwardResponse response) {
                log.trace("Received HttpForwardResponse [{}] correlationId={} success={}",
                          response.requestId(),
                          response.correlationId(),
                          response.success());
                Option.option(pendingForwards.remove(response.correlationId()))
                      .onEmpty(() -> log.debug("[{}] Received forward response for unknown correlationId: {}",
                                               response.requestId(),
                                               response.correlationId()))
                      .onPresent(pending -> processForwardResponse(pending, response));
            }

            @Override
            public void onHttpForwardNotApplied(HttpForwardMessage.HttpForwardNotApplied notApplied) {
                log.debug("[{}] Target {} did not apply forward correlationId={}: {}",
                          notApplied.requestId(),
                          notApplied.sender(),
                          notApplied.correlationId(),
                          notApplied.reason());
                Option.option(pendingForwards.remove(notApplied.correlationId()))
                      .onPresent(pending -> failNotApplied(pending, notApplied));
            }

            private void failNotApplied(PendingForward pending, HttpForwardMessage.HttpForwardNotApplied notApplied) {
                removeFromNodeIndex(notApplied.correlationId(), pending.targetNode());
                pending.promise()
                       .fail(Undelivered.TARGET_DID_NOT_APPLY);
            }

            @Override
            public void onNodeRemoved(MembershipDecision.NodeRemoved nodeRemoved) {
                onNodeDeparture(nodeRemoved.nodeId());
            }

            @Override
            public void onNodeDecommissioned(MembershipDecision.NodeDecommissioned nodeDecommissioned) {
                onNodeDeparture(nodeDecommissioned.nodeId());
            }

            // Self-shutdown cleanup hook: kept on TransportObservation stream because self-shutdown is not a cluster decision.
            @Override
            public void onSelfShutdown(TransportObservation.SelfShutdown selfShutdown) {
                onNodeDeparture(selfShutdown.nodeId());
            }

            private List<NodeId> filterConnectedNodes(Set<NodeId> nodes) {
                var connected = clusterNetwork.connectedPeers();
                var connectedNodes = nodes.stream().filter(connected::contains).toList();

                return accessibilityFilter.keepOnlyAccessible(connectedNodes);
            }

            private List<NodeId> freshCandidatesForRoute(String routeIdentity, Pipeline pipeline) {
                if (pipeline == Pipeline.MANAGEMENT) {
                    return connectedCoreNodes();
                }
                // #964: an unreadable pipeline gets NO candidates, so the forward fails rather than
                // being attempted against the app route table it was never addressed to.
                if (pipeline == Pipeline.UNKNOWN) {
                    return List.of();
                }

                var colonIdx = routeIdentity.indexOf(':');

                if (colonIdx == -1) {
                    return List.of();
                }

                var method = routeIdentity.substring(0, colonIdx);
                var shapeIdx = routeIdentity.indexOf(HttpRouteRegistry.RouteInfo.SHAPE_MARK, colonIdx);
                var prefix = shapeIdx == -1
                             ? routeIdentity.substring(colonIdx + 1)
                             : routeIdentity.substring(colonIdx + 1, shapeIdx);
                var shapeKey = shapeIdx == -1
                               ? ""
                               : routeIdentity.substring(shapeIdx);

                return routeRegistry.findRoute(method, prefix)
                                    .map(r -> r.withShapeKey(shapeKey))
                                    .map(r -> filterConnectedNodes(r.nodes()))
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
                                          Pipeline pipeline,
                                          Deadline deadline,
                                          int budgetShares,
                                          boolean delivered) {
                if (deadline.expired(BUDGET_FLOOR)) {
                    log.warn("Request budget exhausted for {} [{}], not trying further nodes", routeIdentity, requestId);
                    resultPromise.fail(exhausted(delivered, BUDGET_EXHAUSTED));

                    return;
                }

                var candidates = availableNodes.stream().filter(n -> !triedNodes.contains(n)).toList();

                if (candidates.isEmpty()) {
                    handleNoCandidates(requestContext,
                                       resultPromise,
                                       routeIdentity,
                                       requestId,
                                       retriesRemaining,
                                       pipeline,
                                       deadline,
                                       budgetShares,
                                       delivered);

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
                              hopTimeout(Math.max(budgetShares, retriesRemaining + 1), deadline),
                              deadline,
                              failure -> handleRetryOrExhausted(requestContext,
                                                                resultPromise,
                                                                newTriedNodes,
                                                                routeIdentity,
                                                                requestId,
                                                                retriesRemaining,
                                                                pipeline,
                                                                deadline,
                                                                budgetShares,
                                                                delivered,
                                                                failure));
            }

            /// A hop's slice of the remaining budget: the configured per-hop timeout, capped so the
            /// attempts still ahead of us each keep a share of what is left. `shareParts` counts ALL
            /// attempts still ahead — the inner hunt's own retries or an outer retry loop's (the
            /// task-group path re-enters with a single-node hunt per attempt, and dividing by 1 there
            /// would hand the first attempt the whole budget and starve the attempts behind it).
            /// Unbounded deadlines keep the configured timeout unchanged.
            private TimeSpan hopTimeout(int shareParts, Deadline deadline) {
                var share = deadline.remainingShare(shareParts);

                return share.compareTo(forwardTimeout) < 0
                       ? share
                       : forwardTimeout;
            }

            private void handleNoCandidates(HttpRequestContext requestContext,
                                            Promise<HttpResponseData> resultPromise,
                                            String routeIdentity,
                                            String requestId,
                                            int retriesRemaining,
                                            Pipeline pipeline,
                                            Deadline deadline,
                                            int budgetShares,
                                            boolean delivered) {
                if (retriesRemaining > 0) {
                    if (deadline.expired(BUDGET_FLOOR)) {
                        log.warn("Request budget exhausted for {} [{}], not waiting for candidates",
                                 routeIdentity,
                                 requestId);
                        resultPromise.fail(exhausted(delivered, BUDGET_EXHAUSTED));

                        return;
                    }

                    log.debug("No candidates for {} [{}], waiting {}ms before re-query ({} retries remaining)",
                              routeIdentity,
                              requestId,
                              retryDelayMs,
                              retriesRemaining);
                    Promise.<Unit> promise()
                           .timeout(timeSpan(retryDelayMs).millis())
                           .onResult(_ -> retryAfterDelay(requestContext,
                                                          resultPromise,
                                                          routeIdentity,
                                                          requestId,
                                                          retriesRemaining,
                                                          pipeline,
                                                          deadline,
                                                          budgetShares,
                                                          delivered));

                    return;
                }

                log.error("No more nodes to try for {} [{}] after all retries exhausted", routeIdentity, requestId);
                resultPromise.fail(exhausted(delivered, Causes.cause("All nodes failed or unavailable")));
            }

            private void retryAfterDelay(HttpRequestContext requestContext,
                                         Promise<HttpResponseData> resultPromise,
                                         String routeIdentity,
                                         String requestId,
                                         int retriesRemaining,
                                         Pipeline pipeline,
                                         Deadline deadline,
                                         int budgetShares,
                                         boolean delivered) {
                var freshNodes = freshCandidatesForRoute(routeIdentity, pipeline);

                forwardWithRetry(requestContext,
                                 resultPromise,
                                 freshNodes,
                                 Set.of(),
                                 routeIdentity,
                                 requestId,
                                 retriesRemaining - 1,
                                 pipeline,
                                 deadline,
                                 budgetShares,
                                 delivered);
            }

            private void handleRetryOrExhausted(HttpRequestContext requestContext,
                                                Promise<HttpResponseData> resultPromise,
                                                Set<NodeId> triedNodes,
                                                String routeIdentity,
                                                String requestId,
                                                int retriesRemaining,
                                                Pipeline pipeline,
                                                Deadline deadline,
                                                int budgetShares,
                                                boolean delivered,
                                                Cause failure) {
                var deliveredSoFar = delivered || mayHaveBeenDelivered(failure);

                if (mustNotResend(requestContext.method(), failure)) {
                    log.warn("Forward of non-idempotent {} [{}] failed after it may have been delivered, not retrying: {}",
                             requestContext.method(),
                             requestId,
                             failure.message());
                    resultPromise.fail(ForwardError.NotRetried.FACTORY.apply(requestContext.method(), failure));

                    return;
                }

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
                                     pipeline,
                                     deadline,
                                     budgetShares,
                                     deliveredSoFar);
                } else {
                    log.error("All retries exhausted for [{}]", requestId);
                    resultPromise.fail(exhausted(deliveredSoFar, failure));
                }
            }

            /// The hunt is over without an answer. Per the 2026-10-07 ruling (know 14a3606152) the client learns
            /// which of two things happened: some attempt may have reached a node (outcome unknown), or none
            /// left this node (nothing applied, safe to retry). Both answer 503; the origin stays in the log.
            private static ForwardError exhausted(boolean delivered, Cause origin) {
                return delivered
                       ? ForwardError.OutcomeUnknown.FACTORY.apply(origin)
                       : ForwardError.NotSent.FACTORY.apply(origin);
            }

            private static boolean mayHaveBeenDelivered(Cause failure) {
                return ! (failure instanceof Undelivered);
            }

            /// A1: re-sending is safe when the method is idempotent, or when the failure proves the frame never
            /// left. A non-idempotent request whose attempt may have reached the target is not re-sent, because a
            /// second node would process it a second time while the first may still be processing it.
            private static boolean mustNotResend(String method, Cause failure) {
                return ! isIdempotent(method) && mayHaveBeenDelivered(failure);
            }

            private static boolean isIdempotent(String method) {
                return HttpMethod.fromString(method)
                                 .filter(IDEMPOTENT_METHODS::contains)
                                 .isPresent();
            }

            private void forwardToNode(HttpRequestContext requestContext,
                                       Promise<HttpResponseData> resultPromise,
                                       NodeId targetNode,
                                       String requestId,
                                       Pipeline pipeline,
                                       TimeSpan hopTimeout,
                                       Deadline deadline,
                                       Consumer<Cause> onFailure) {
                if (!clusterNetwork.connectedPeers().contains(targetNode)) {
                    log.debug("Target node {} already disconnected, immediate retry [{}]", targetNode, requestId);
                    onFailure.accept(Undelivered.TARGET_NOT_CONNECTED);

                    return;
                }

                var correlationId = IdGenerator.generate();
                byte[] requestData;

                try {
                    requestData = serializer.encode(requestContext);
                } catch (Exception e) {
                    log.error("Failed to serialize request [{}]: {}", requestId, e.getMessage());
                    resultPromise.fail(ForwardError.NotSent.FACTORY.apply(Causes.cause("Request serialization failed")));

                    return;
                }

                var forwardRequest = new HttpForwardRequest(selfNodeId,
                                                            correlationId,
                                                            requestId,
                                                            requestData,
                                                            pipeline,
                                                            deadline.toWireMillis());
                // A3: a frame over the cluster frame limit is never sent. The receiver's frame decoder would reject
                // it and close the whole FORWARD lane to this peer, failing every other forward in flight on it
                // (pinned by OversizedLaneFrameTest in the consensus module); and no other node can take it either,
                // so it fails terminally with 413, not as a retryable hop failure.
                var bounded = clusterNetwork.validateOutboundMessage(forwardRequest);

                if (bounded.isFailure()) {
                    log.warn("Request [{}] encodes to {} bytes, over the cluster frame limit; not forwarding it",
                             requestId,
                             requestData.length);
                    resultPromise.fail(ForwardError.TooLargeToForward.FACTORY.apply(requestData.length));

                    return;
                }

                if (pendingForwards.size() >= MAX_PENDING_FORWARDS) {
                    log.warn("Pending forwards limit reached ({}), rejecting forward [{}]",
                             MAX_PENDING_FORWARDS,
                             requestId);
                    resultPromise.fail(ForwardError.NotSent.FACTORY.apply(Causes.cause("Too many pending forwards")));

                    return;
                }

                var internalPromise = Promise.<HttpResponseData> promise();
                var pending = new PendingForward(internalPromise,
                                                 System.currentTimeMillis(),
                                                 requestId,
                                                 targetNode,
                                                 onFailure);

                pendingForwards.put(correlationId, pending);
                pendingForwardsByNode.computeIfAbsent(targetNode, _ -> ConcurrentHashMap.newKeySet()).add(correlationId);
                // #838 review round 1: return value discarded on purpose -- the scheduled fail targets
                // `internalPromise` itself (see Promise#timeout javadoc), so this still arms the timeout
                // even though the derived promise it returns here is never used.
                internalPromise.timeout(hopTimeout);
                // hopTimeout is the wait that just started and the moment the caller retries elsewhere, so the frame must
                // not outlive it in an offline buffer (#1996). Always bounded here: `remainingMillis` may be NO_BUDGET,
                // but the hop wait never is.
                // The outcome tells a refusal before write (provably not sent: retry is safe for every method) from a
                // frame that may be delivered. A refusal fails the hop now instead of waiting out the hop timeout.
                clusterNetwork.sendOutcome(targetNode, forwardRequest, hopTimeout)
                              .onSuccess(outcome -> failHopIfRefused(outcome, internalPromise, requestId));
                log.trace("Forwarded request to {} [{}] correlationId={}", targetNode, requestId, correlationId);
                internalPromise.onSuccess(resultPromise::succeed)
                               .onFailure(cause -> handleInternalFailure(cause,
                                                                         correlationId,
                                                                         targetNode,
                                                                         requestId,
                                                                         hopTimeout,
                                                                         onFailure));
            }

            private void handleInternalFailure(Cause cause,
                                               String correlationId,
                                               NodeId targetNode,
                                               String requestId,
                                               TimeSpan hopTimeout,
                                               Consumer<Cause> onFailure) {
                var removed = pendingForwards.remove(correlationId);

                if (removed != null) {
                    removeFromNodeIndex(correlationId, targetNode);
                }

                if (cause instanceof CoreError.Timeout) {
                    log.warn("Forward to {} timed out after {} [{}]", targetNode, hopTimeout, requestId);
                }

                onFailure.accept(cause);
            }

            /// `Sent` and `ConnectionDead` leave the hop waiting: a frame reported under either may still be delivered
            /// from the offline buffer (see [ClusterNetwork#sendOutcome(NodeId, org.pragmatica.consensus.ProtocolMessage, TimeSpan)]).
            private static void failHopIfRefused(WriteOutcome outcome,
                                                 Promise<HttpResponseData> hop,
                                                 String requestId) {
                switch (outcome) {
                    case WriteOutcome.BackpressureRefused _, WriteOutcome.NoPeerState _, WriteOutcome.EncodeFailed _ -> {
                        log.debug("Forward [{}] refused before write: {}", requestId, outcome);
                        hop.fail(Undelivered.SEND_REFUSED);
                    }
                    case WriteOutcome.Sent _, WriteOutcome.ConnectionDead _ -> {}
                }
            }

            private void processForwardResponse(PendingForward pending, HttpForwardResponse response) {
                removeFromNodeIndex(response.correlationId(), pending.targetNode());
                if (response.success()) {
                    handleSuccessfulForwardResponse(pending, response);
                } else {
                    handleFailedForwardResponse(pending, response);
                }
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

            public org.pragmatica.lang.Unit onNodeDeparture(NodeId departedNode) {
                Option.option(pendingForwardsByNode.remove(departedNode))
                      .filter(ids -> !ids.isEmpty())
                      .onPresent(correlationIds -> retryPendingForwards(departedNode, correlationIds));

                return org.pragmatica.lang.Unit.unit();
            }

            private void retryPendingForwards(NodeId departedNode, Set<String> correlationIds) {
                var affectedRequestIds = correlationIds.stream()
                                                       .map(pendingForwards::get)
                                                       .map(Option::option)
                                                       .flatMap(Option::stream)
                                                       .map(PendingForward::requestId)
                                                       .limit(5)
                                                       .toList();

                log.debug("Node {} departed, triggering immediate retry for {} pending forwards, requestIds={}",
                          departedNode,
                          correlationIds.size(),
                          affectedRequestIds);
                for (var correlationId : correlationIds) {
                    Option.option(pendingForwards.remove(correlationId)).onPresent(pending -> failPendingForwardOnDeparture(pending,
                                                                                                                            departedNode));
                }
            }

            private void failPendingForwardOnDeparture(PendingForward pending, NodeId departedNode) {
                log.debug("Triggering retry for request [{}] due to node {} departure",
                          pending.requestId(),
                          departedNode);
                pending.promise().fail(Causes.cause("Target node " + departedNode + " departed"));
            }

            private void removeFromNodeIndex(String correlationId, NodeId targetNode) {
                Option.option(pendingForwardsByNode.get(targetNode)).onPresent(nodeCorrelations -> cleanupNodeCorrelation(nodeCorrelations,
                                                                                                                          correlationId,
                                                                                                                          targetNode));
            }

            private void cleanupNodeCorrelation(Set<String> nodeCorrelations, String correlationId, NodeId targetNode) {
                nodeCorrelations.remove(correlationId);
                if (nodeCorrelations.isEmpty()) {
                    pendingForwardsByNode.remove(targetNode, nodeCorrelations);
                }
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
                                 leaderResolver,
                                 accessibilityFilter,
                                 partitionOwnerResolver);
    }
}
