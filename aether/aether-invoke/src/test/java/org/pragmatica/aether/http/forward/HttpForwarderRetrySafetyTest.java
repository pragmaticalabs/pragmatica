// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.forward;

import org.pragmatica.aether.http.HttpRouteRegistry;
import org.pragmatica.aether.http.HttpRouteRegistry.RouteInfo;
import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardNotApplied;
import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardRequest;
import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardResponse;
import org.pragmatica.aether.http.forward.HttpForwardMessage.NotAppliedReason;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.http.HttpError;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Deadline;
import org.pragmatica.net.tcp.Server;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// What a forward may safely re-send, beyond [HttpForwarderIdempotencyTest]:
///
///   - F1: a target's [HttpForwardNotApplied] proves the request was not applied there, so even a POST is re-sent,
///     and a hunt that ends on such answers says "nothing was applied; safe to retry";
///   - F3: `ConnectionDead` is NOT proof (the frame may sit in the offline buffer, know 14a3606152), PUT and DELETE
///     are idempotent (RFC 9110 §9.2.2) and keep retrying, and the task-group outer loop stops on a terminal failure;
///   - F8: the task-group loop answers its budget exhaustion with the ruling's 503, and remembers an earlier attempt
///     that may have been applied.
class HttpForwarderRetrySafetyTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId A = nodeId("node-a").unwrap();
    private static final NodeId B = nodeId("node-b").unwrap();
    private static final String PREFIX = "/api/uploads";
    private static final String PATH = "/api/uploads/1";
    private static final HttpResponseData OK = HttpResponseData.httpResponseData(200, "stored");

    // --- F1 ---

    @Test
    void forward_postWhoseFirstTargetAnswersRouteNotFound_isRetried_andSucceedsOnTheSecond() {
        var network = new ScriptedClusterNetwork(Set.of(A, B), _ -> WriteOutcome.Sent::new);
        var forwarder = appForwarder(network, "POST");

        network.onSend = sent -> answer(forwarder, network, sent);
        var result = forwarder.forward(context("POST", PATH), "POST", PREFIX, "post-not-applied")
                              .await(timeSpan(5).seconds());

        assertThat(network.requests()).as("re-sent once after the not-applied answer").hasSize(2);
        assertThat(result.isSuccess()).as("the second target served it: " + failureOf(result)).isTrue();
    }

    @Test
    void forward_postAnsweredNotAppliedByEveryTarget_failsNothingWasApplied() {
        var network = new ScriptedClusterNetwork(Set.of(A, B), _ -> WriteOutcome.Sent::new);
        var forwarder = appForwarder(network, "POST");

        network.onSend = sent -> forwarder.onHttpForwardNotApplied(notApplied(sent, NotAppliedReason.ROUTE_NOT_FOUND));
        var result = forwarder.forward(context("POST", PATH), "POST", PREFIX, "post-all-not-applied")
                              .await(timeSpan(5).seconds());

        assertThat(network.requests()).hasSize(2);
        var failure = failureOf(result);
        assertThat(failure).isInstanceOf(ForwardError.NotSent.class);
        assertThat(((ForwardError) failure).status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(HttpError.clientMessage((Cause) failure)).contains("nothing was applied; safe to retry")
                                                            .doesNotContain("may have been applied");
    }

    // --- F3 ---

    @Test
    void forward_postWhoseWriteReportsConnectionDead_isNotResent() {
        var network = new ScriptedClusterNetwork(Set.of(A, B), _ -> WriteOutcome.ConnectionDead::new);
        var result = appForwarder(network, "POST").forward(context("POST", PATH), "POST", PREFIX, "post-dead")
                                                  .await(timeSpan(5).seconds());

        assertThat(network.requests()).as("a ConnectionDead frame may still be delivered from the offline buffer")
                                      .hasSize(1);
        assertThat(failureOf(result)).isInstanceOf(ForwardError.NotRetried.class);
    }

    @Test
    void forward_putWhoseFirstHopTimesOut_isRetried() {
        var network = new ScriptedClusterNetwork(Set.of(A, B), _ -> WriteOutcome.Sent::new);

        appForwarder(network, "PUT").forward(context("PUT", PATH), "PUT", PREFIX, "put").await(timeSpan(5).seconds());

        assertThat(network.requests()).as("PUT is idempotent (RFC 9110 §9.2.2)").hasSize(2);
    }

    @Test
    void forward_deleteWhoseFirstHopTimesOut_isRetried() {
        var network = new ScriptedClusterNetwork(Set.of(A, B), _ -> WriteOutcome.Sent::new);

        appForwarder(network, "DELETE").forward(context("DELETE", PATH), "DELETE", PREFIX, "delete")
                                       .await(timeSpan(5).seconds());

        assertThat(network.requests()).as("DELETE is idempotent (RFC 9110 §9.2.2)").hasSize(2);
    }

    @Test
    void taskGroupForward_postNotRetried_stopsTheOuterLoop() {
        var network = new ScriptedClusterNetwork(Set.of(A), _ -> WriteOutcome.Sent::new);
        var result = taskGroupForwarder(network, timeSpan(100).millis(), 3)
            .forwardManagement(context("POST", taskGroupPath()), "tg-post")
            .await(timeSpan(10).seconds());

        assertThat(failureOf(result)).isInstanceOf(ForwardError.NotRetried.class);
        assertThat(network.requests()).as("a terminal NotRetried must not be re-sent by the outer loop").hasSize(1);
    }

    // --- F8 ---

    @Test
    void taskGroupForward_budgetExhausted_answersTheRuling503() {
        var network = new ScriptedClusterNetwork(Set.of(A), _ -> WriteOutcome.Sent::new);
        var result = Deadline.runWith(Deadline.fromWireMillis(600),
                                      () -> taskGroupForwarder(network, timeSpan(100).millis(), 50)
                                          .forwardManagement(context("GET", taskGroupGetPath()), "tg-budget"))
                             .await(timeSpan(10).seconds());

        var failure = failureOf(result);
        assertThat(failure).as("never a bare budget cause, which maps to 504").isInstanceOf(ForwardError.OutcomeUnknown.class);
        assertThat(((ForwardError) failure).status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void taskGroupForward_earlierAttemptMayHaveApplied_exhaustsOutcomeUnknown() {
        var network = new ScriptedClusterNetwork(Set.of(A), _ -> WriteOutcome.Sent::new);

        network.onSend = _ -> network.disconnect(A);
        var result = taskGroupForwarder(network, timeSpan(100).millis(), 3)
            .forwardManagement(context("GET", taskGroupGetPath()), "tg-mixed")
            .await(timeSpan(10).seconds());

        assertThat(network.requests()).as("one attempt reached the owner, the later ones found it disconnected").hasSize(1);
        assertThat(failureOf(result)).isInstanceOf(ForwardError.OutcomeUnknown.class);
    }

    // --- helpers ---

    private static void answer(HttpForwarder forwarder, ScriptedClusterNetwork network, HttpForwardRequest sent) {
        if (network.requests().size() == 1) {
            forwarder.onHttpForwardNotApplied(notApplied(sent, NotAppliedReason.ROUTE_NOT_FOUND));
        } else {
            forwarder.onHttpForwardResponse(new HttpForwardResponse(B,
                                                                    sent.correlationId(),
                                                                    sent.requestId(),
                                                                    true,
                                                                    new byte[0],
                                                                    sent.pipeline()));
        }
    }

    private static HttpForwardNotApplied notApplied(HttpForwardRequest sent, NotAppliedReason reason) {
        return new HttpForwardNotApplied(A, sent.correlationId(), sent.requestId(), reason, sent.pipeline());
    }

    private static String taskGroupPath() {
        return ManagementRoute.CLUSTER_CONFIG_APPLY.assemble(List.of()).unwrap();
    }

    private static String taskGroupGetPath() {
        return ManagementRoute.CLUSTER_CONFIG_GET.assemble(List.of()).unwrap();
    }

    private static HttpForwarder appForwarder(ClusterNetwork network, String method) {
        return HttpForwarder.httpForwarder(SELF,
                                           fixedRouteRegistry(method, Set.of(A, B)),
                                           network,
                                           new NoopSerializer(),
                                           new FixedDeserializer(),
                                           timeSpan(150).millis(),
                                           10L,
                                           3,
                                           () -> Set.of(SELF, A, B),
                                           HttpForwarder.UNASSIGNED_RESOLVER,
                                           HttpForwarder.NO_LEADER_RESOLVER,
                                           AccessibilityFilter.IDENTITY);
    }

    private static HttpForwarder taskGroupForwarder(ClusterNetwork network, TimeSpan hopTimeout, int retries) {
        return HttpForwarder.httpForwarder(SELF,
                                           fixedRouteRegistry("GET", Set.of(A)),
                                           network,
                                           new NoopSerializer(),
                                           new FixedDeserializer(),
                                           hopTimeout,
                                           10L,
                                           retries,
                                           () -> Set.of(SELF, A),
                                           _ -> Result.success(A),
                                           HttpForwarder.NO_LEADER_RESOLVER,
                                           AccessibilityFilter.IDENTITY);
    }

    private static Object failureOf(Result<?> result) {
        return result.fold(cause -> cause, value -> "succeeded: " + value);
    }

    private static HttpRequestContext context(String method, String path) {
        return HttpRequestContext.httpRequestContext(path, method, Map.of(), Map.of(), "retry-safety");
    }

    private static HttpRouteRegistry fixedRouteRegistry(String method, Set<NodeId> owners) {
        return new HttpRouteRegistry() {
            @Override public Option<RouteInfo> findRoute(String httpMethod, String path) {
                return Option.some(RouteInfo.routeInfo(method, PREFIX, owners));
            }
            @Override public List<RouteInfo> allRoutes() {return List.of(RouteInfo.routeInfo(method, PREFIX, owners));}
            @Override public void onNodeRoutesPut(org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut<org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey, org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue> valuePut) {}
            @Override public void onNodeRoutesRemove(org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove<org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey, org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue> valueRemove) {}
            @Override public void evictNode(NodeId nodeId) {}
            @Override public long staleFenceObservationCount() {return 0L;}
        };
    }

    /// Records every forward; the per-node outcome and an optional reaction to each send are scripted.
    private static final class ScriptedClusterNetwork implements ClusterNetwork {
        private final Set<NodeId> connected = ConcurrentHashMap.newKeySet();
        private final Fn1<Fn1<WriteOutcome, NodeId>, NodeId> outcomes;
        private final List<HttpForwardRequest> requests = new CopyOnWriteArrayList<>();
        private volatile Consumer<HttpForwardRequest> onSend = _ -> {};

        ScriptedClusterNetwork(Set<NodeId> connected, Fn1<Fn1<WriteOutcome, NodeId>, NodeId> outcomes) {
            this.connected.addAll(connected);
            this.outcomes = outcomes;
        }

        List<HttpForwardRequest> requests() {return List.copyOf(requests);}

        void disconnect(NodeId node) {connected.remove(node);}

        @Override public <M extends ProtocolMessage> Promise<WriteOutcome> sendOutcome(NodeId nodeId, M message, TimeSpan offlineTtl) {
            var request = (HttpForwardRequest) message;

            requests.add(request);
            var outcome = outcomes.apply(nodeId).apply(nodeId);
            onSend.accept(request);
            return Promise.success(outcome);
        }

        @Override public <M extends ProtocolMessage> Unit send(NodeId nodeId, M message) {
            throw new AssertionError("a forward must go through sendOutcome");
        }

        @Override public <M extends ProtocolMessage> Unit broadcast(M message) {return unit();}
        @Override public void connect(NetworkServiceMessage.ConnectNode connectNode) {}
        @Override public void disconnect(NetworkServiceMessage.DisconnectNode disconnectNode) {}
        @Override public void listNodes(NetworkServiceMessage.ListConnectedNodes listConnectedNodes) {}
        @Override public void handleSend(NetworkServiceMessage.Send send) {}
        @Override public void handleBroadcast(NetworkServiceMessage.Broadcast broadcast) {}
        @Override public Promise<Unit> start() {return Promise.unitPromise();}
        @Override public Promise<Unit> stop() {return Promise.unitPromise();}
        @Override public int connectedNodeCount() {return connected.size();}
        @Override public Set<NodeId> connectedPeers() {return Set.copyOf(connected);}
        @Override public Option<Server> server() {return Option.none();}
    }

    private static final class NoopSerializer implements Serializer {
        @Override public <T> void write(io.netty.buffer.ByteBuf byteBuf, T object) {}
        @Override public <T> byte[] encode(T value) {return new byte[0];}
    }

    /// Every success payload decodes to the same 200 response.
    private static final class FixedDeserializer implements Deserializer {
        @Override public <T> T read(io.netty.buffer.ByteBuf byteBuf) {return null;}

        @Override
        @SuppressWarnings("unchecked")
        public <T> T decode(byte[] bytes) {
            return (T) OK;
        }
    }
}
