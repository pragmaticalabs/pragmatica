// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.forward;

import org.pragmatica.aether.http.HttpRouteRegistry;
import org.pragmatica.aether.http.HttpRouteRegistry.RouteInfo;
import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardRequest;
import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardResponse;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.http.HttpError;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.Server;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// A1: a forwarded request is re-sent to another node only when that cannot run it twice. A non-idempotent
/// request (RFC 9110 §9.2.2: POST, PATCH, CONNECT) whose attempt may have been delivered — the hop timed out, or
/// the target answered with a failure — is NOT re-sent. A refusal before write proves nothing left, so even a POST is
/// retried then. Idempotent methods keep retrying as before. The client statuses follow the 2026-10-07 ruling
/// (know 14a3606152): 503 "outcome unknown, may have been applied; retry only an idempotent operation" when an attempt
/// may have been delivered, 503 "nothing was applied; safe to retry" when none was.
class HttpForwarderIdempotencyTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId A = nodeId("node-a").unwrap();
    private static final NodeId B = nodeId("node-b").unwrap();
    private static final String PREFIX = "/api/uploads";
    private static final String PATH = "/api/uploads/1";

    @Test
    void forward_postWhoseFirstHopTimesOut_isSentExactlyOnce_andFailsNotRetried503() {
        var network = new CountingClusterNetwork(Set.of(A, B), Set.of());
        var forwarder = forwarder(network, "POST");

        var result = forwarder.forward(context("POST", "post-timeout"), "POST", PREFIX, "post-timeout")
                              .await(timeSpan(5).seconds());

        assertThat(network.totalSends()).as("a POST that may have been delivered must not be re-sent").isEqualTo(1);
        var failure = failureOf(result);
        assertThat(failure).isInstanceOf(ForwardError.NotRetried.class);
        assertThat(((ForwardError) failure).status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(HttpError.clientMessage((Cause) failure)).contains("POST request was NOT retried")
                                                            .contains("outcome unknown, may have been applied; retry only an idempotent operation");
    }

    @Test
    void forward_getWhoseFirstHopTimesOut_isRetriedOnAnotherNode() {
        var network = new CountingClusterNetwork(Set.of(A, B), Set.of());
        var forwarder = forwarder(network, "GET");

        var result = forwarder.forward(context("GET", "get-timeout"), "GET", PREFIX, "get-timeout")
                              .await(timeSpan(5).seconds());

        assertThat(network.totalSends()).as("an idempotent GET keeps today's retry").isEqualTo(2);
        assertThat(network.sendsTo(A)).isEqualTo(1);
        assertThat(network.sendsTo(B)).isEqualTo(1);
        var failure = failureOf(result);
        assertThat(failure).as("retried, then exhausted after attempts that may have been delivered")
                           .isInstanceOf(ForwardError.OutcomeUnknown.class);
        assertThat(((ForwardError) failure).status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(HttpError.clientMessage((Cause) failure)).contains("outcome unknown, may have been applied");
    }

    @Test
    void forward_patchAnsweredWithRemoteFailure_isNotResent() {
        var network = new CountingClusterNetwork(Set.of(A, B), Set.of());
        var forwarder = forwarder(network, "PATCH");
        var pending = forwarder.forward(context("PATCH", "patch-remote"), "PATCH", PREFIX, "patch-remote");

        network.firstSend.await(timeSpan(2).seconds()).unwrap();
        var request = network.requests().getFirst();
        forwarder.onHttpForwardResponse(new HttpForwardResponse(A,
                                                                request.correlationId(),
                                                                request.requestId(),
                                                                false,
                                                                "boom".getBytes(StandardCharsets.UTF_8),
                                                                request.pipeline()));
        var result = pending.await(timeSpan(5).seconds());

        assertThat(network.totalSends()).isEqualTo(1);
        assertThat(failureOf(result)).isInstanceOf(ForwardError.NotRetried.class);
    }

    @Test
    void forward_postRefusedBeforeWrite_isRetriedOnAnotherNode() {
        var network = new CountingClusterNetwork(Set.of(A, B), Set.of(A, B));
        var forwarder = forwarder(network, "POST");

        var result = forwarder.forward(context("POST", "post-refused"), "POST", PREFIX, "post-refused")
                              .await(timeSpan(5).seconds());

        assertThat(network.totalSends()).as("a refusal proves nothing left, so even a POST is retried").isEqualTo(2);
        var failure = failureOf(result);
        assertThat(failure).as("every attempt refused before write: nothing was applied")
                           .isInstanceOf(ForwardError.NotSent.class);
        assertThat(((ForwardError) failure).status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(HttpError.clientMessage((Cause) failure)).contains("nothing was applied; safe to retry");
    }

    /// One attempt may have been delivered (it timed out) and the other was refused before write: the outcome is
    /// unknown, whichever came last. Both orders are run, because the hunt's node order is not fixed by the test.
    @Test
    void forward_getWithOneTimeoutAndOneRefusal_isOutcomeUnknown_inEitherOrder() {
        for (var refused : List.of(A, B)) {
            var network = new CountingClusterNetwork(Set.of(A, B), Set.of(refused));
            var result = forwarder(network, "GET").forward(context("GET", "mixed-" + refused.id()), "GET", PREFIX, "mixed")
                                                  .await(timeSpan(5).seconds());

            assertThat(network.totalSends()).isEqualTo(2);
            assertThat(failureOf(result)).as("refused node " + refused.id())
                                         .isInstanceOf(ForwardError.OutcomeUnknown.class);
        }
    }

    private static HttpForwarder forwarder(ClusterNetwork network, String method) {
        return HttpForwarder.httpForwarder(SELF,
                                           fixedRouteRegistry(method, Set.of(A, B)),
                                           network,
                                           new NoopSerializer(),
                                           new NoopDeserializer(),
                                           timeSpan(150).millis(),
                                           10L,
                                           3,
                                           () -> Set.of(SELF, A, B),
                                           HttpForwarder.UNASSIGNED_RESOLVER,
                                           HttpForwarder.NO_LEADER_RESOLVER,
                                           AccessibilityFilter.IDENTITY);
    }

    private static Object failureOf(Result<?> result) {
        return result.fold(cause -> cause, value -> "succeeded: " + value);
    }

    private static HttpRequestContext context(String method, String requestId) {
        return HttpRequestContext.httpRequestContext(PATH, method, Map.of(), Map.of(), requestId);
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

    /// Never answers. Counts every forward handed to it per target; a target in `refusing` reports
    /// BackpressureRefused, the refusal-before-write outcome.
    private static final class CountingClusterNetwork implements ClusterNetwork {
        private final Set<NodeId> connected;
        private final Set<NodeId> refusing;
        private final Map<NodeId, AtomicInteger> sends = new ConcurrentHashMap<>();
        private final List<HttpForwardRequest> requests = new CopyOnWriteArrayList<>();
        private final Promise<Unit> firstSend = Promise.promise();

        CountingClusterNetwork(Set<NodeId> connected, Set<NodeId> refusing) {
            this.connected = Set.copyOf(connected);
            this.refusing = Set.copyOf(refusing);
        }

        int totalSends() {return sends.values().stream().mapToInt(AtomicInteger::get).sum();}

        int sendsTo(NodeId node) {return Option.option(sends.get(node)).map(AtomicInteger::get).or(0);}

        List<HttpForwardRequest> requests() {return List.copyOf(requests);}

        @Override public <M extends ProtocolMessage> Promise<WriteOutcome> sendOutcome(NodeId nodeId, M message, TimeSpan offlineTtl) {
            sends.computeIfAbsent(nodeId, _ -> new AtomicInteger()).incrementAndGet();
            requests.add((HttpForwardRequest) message);
            firstSend.succeed(unit());
            return Promise.success(refusing.contains(nodeId)
                                   ? new WriteOutcome.BackpressureRefused(nodeId)
                                   : new WriteOutcome.Sent(nodeId));
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
        @Override public Set<NodeId> connectedPeers() {return connected;}
        @Override public Option<Server> server() {return Option.none();}
    }

    private static final class NoopSerializer implements Serializer {
        @Override public <T> void write(io.netty.buffer.ByteBuf byteBuf, T object) {}
        @Override public <T> byte[] encode(T value) {return new byte[0];}
    }

    private static final class NoopDeserializer implements Deserializer {
        @Override public <T> T read(io.netty.buffer.ByteBuf byteBuf) {return null;}
    }
}
