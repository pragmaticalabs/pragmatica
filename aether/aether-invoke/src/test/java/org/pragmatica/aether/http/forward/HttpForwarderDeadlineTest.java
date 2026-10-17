// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.forward;

import org.pragmatica.aether.http.HttpRouteRegistry;
import org.pragmatica.aether.http.HttpRouteRegistry.RouteInfo;
import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardRequest;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Deadline;
import org.pragmatica.net.tcp.Server;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Deadline-budget propagation at the forwarder (the invoke→forward seam): the ambient budget
/// captured at `forward()` entry caps the whole hunt — an exhausted budget refuses before any
/// send, a bounded budget is stamped onto the wire for the receiver's stage-2 drop, and each
/// hop's wait is the configured timeout capped by the remaining budget's share. Without this,
/// 02w measured layered constants stacking past the client's 30s (5s hops under 30s entity
/// forwards under harness sweeps).
class HttpForwarderDeadlineTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId A = nodeId("node-a").unwrap();
    private static final NodeId B = nodeId("node-b").unwrap();

    private static final String METHOD = "GET";
    private static final String PREFIX = "/api/widgets";
    private static final String PATH = "/api/widgets/42";

    private HttpForwarder forwarder(ClusterNetwork network, TimeSpan forwardTimeout, Set<NodeId> owners) {
        return HttpForwarder.httpForwarder(SELF,
                                           fixedRouteRegistry(owners),
                                           network,
                                           new NoopSerializer(),
                                           new NoopDeserializer(),
                                           forwardTimeout,
                                           50L,
                                           5,
                                           () -> Set.of(SELF, A, B),
                                           group -> org.pragmatica.aether.slice.delegation.TaskAssignmentError.notAssigned(group).result(),
                                           HttpForwarder.NO_LEADER_RESOLVER,
                                           AccessibilityFilter.IDENTITY);
    }

    @Test
    void workerDeparture_resolvesPendingForwardWithoutWaitingForHopTimeout() {
        var network = new RecordingClusterNetwork(Set.of(A));
        var forwarder = forwarder(network, timeSpan(20).seconds(), Set.of(A));
        var context = HttpRequestContext.httpRequestContext(PATH, METHOD, Map.of(), Map.of(), "worker-departure");
        var result = forwarder.forward(context, METHOD, PREFIX, "worker-departure");
        network.firstSend.await(timeSpan(1).seconds()).unwrap();
        forwarder.onNodeDeparture(A);
        result.await(timeSpan(1).seconds());
        assertThat(result.isResolved()).isTrue();
        assertThat(result.await().isFailure()).isTrue();
    }

    @Test
    void forward_underExhaustedBudget_failsTyped_withoutAnySend() {
        var network = new RecordingClusterNetwork(Set.of(A, B));
        var forwarder = forwarder(network, timeSpan(50).millis(), Set.of(A, B));
        var ctx = HttpRequestContext.httpRequestContext(PATH, METHOD, Map.of(), Map.of(), "req-exhausted");

        var result = Deadline.runWith(Deadline.fromWireMillis(0),
                                      () -> forwarder.forward(ctx, METHOD, PREFIX, "req-exhausted"))
                             .await();

        assertThat(result.isFailure()).isTrue();

        String cause = result.fold(Cause::message, _ -> "unexpectedly succeeded");

        assertThat(cause).contains("budget exhausted");
        assertThat(network.sentMessages()).as("an exhausted budget must not start a hop").isEmpty();
    }

    @Test
    void forward_underBoundedBudget_stampsRemainingOnTheWire() {
        var network = new RecordingClusterNetwork(Set.of(A, B));
        var forwarder = forwarder(network, timeSpan(50).millis(), Set.of(A, B));
        var ctx = HttpRequestContext.httpRequestContext(PATH, METHOD, Map.of(), Map.of(), "req-stamped");

        Deadline.runWith(Deadline.fromWireMillis(5_000),
                         () -> forwarder.forward(ctx, METHOD, PREFIX, "req-stamped"))
                .await();

        var first = (HttpForwardRequest) network.sentMessages().getFirst();

        assertThat(first.remainingMillis())
            .as("the receiver must learn how much budget the sender still has")
            .isGreaterThan(0)
            .isLessThanOrEqualTo(5_000);
    }

    @Test
    void forward_withoutAmbientBudget_stampsNoBudgetSentinel() {
        var network = new RecordingClusterNetwork(Set.of(A, B));
        var forwarder = forwarder(network, timeSpan(50).millis(), Set.of(A, B));
        var ctx = HttpRequestContext.httpRequestContext(PATH, METHOD, Map.of(), Map.of(), "req-nobudget");

        forwarder.forward(ctx, METHOD, PREFIX, "req-nobudget").await();

        var first = (HttpForwardRequest) network.sentMessages().getFirst();

        assertThat(first.remainingMillis())
            .as("no ambient budget -> the receiver applies its own defaults")
            .isEqualTo(Deadline.NO_BUDGET);
    }

    /// The hop-timeout cap. A single-owner route (1 attempt) under a ~300ms budget, against a
    /// forwarder configured with a 10s per-hop timeout and a peer that never answers: the hunt
    /// must give up at the budget's share, not at the configured hop timeout. Pre-fix (hop waits
    /// the configured 10s) this red-lines on the elapsed assertion.
    @Test
    void forward_underSmallBudget_hopWaitsTheBudgetShareNotTheConfiguredTimeout() {
        var network = new RecordingClusterNetwork(Set.of(A));
        var forwarder = forwarder(network, timeSpan(10).seconds(), Set.of(A));
        var ctx = HttpRequestContext.httpRequestContext(PATH, METHOD, Map.of(), Map.of(), "req-hopcap");
        var startedAt = System.nanoTime();

        var result = Deadline.runWith(Deadline.fromWireMillis(300),
                                      () -> forwarder.forward(ctx, METHOD, PREFIX, "req-hopcap"))
                             .await();
        var elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(result.isFailure()).isTrue();
        assertThat(network.sentMessages()).as("within budget, the hop IS attempted").isNotEmpty();
        assertThat(elapsedMillis)
            .as("the hop wait is the budget share, not the configured 10s")
            .isLessThan(5_000);
    }

    /// #1996: the frame must not outlive the hop wait that just started — after it the caller has moved on to another
    /// owner, so a late delivery would run the request a second time. NO_BUDGET on the wire does not mean no wait: the
    /// hop is always bounded by the configured timeout, so that is the lifetime handed to the transport.
    @Test
    void forward_withoutAmbientBudget_stillBoundsTheFrameLifetimeByTheHopTimeout() {
        var network = new RecordingClusterNetwork(Set.of(A));
        var forwarder = forwarder(network, timeSpan(50).millis(), Set.of(A));
        var ctx = HttpRequestContext.httpRequestContext(PATH, METHOD, Map.of(), Map.of(), "req-ttl-nobudget");

        forwarder.forward(ctx, METHOD, PREFIX, "req-ttl-nobudget").await();

        assertThat(network.plainSends()).as("an HTTP forward is never sent without a frame lifetime").isZero();
        assertThat(network.lifetimes()).isNotEmpty();
        assertThat(network.lifetimes().getFirst().millis()).isPositive().isLessThanOrEqualTo(50L);
    }

    @Test
    void forward_underBoundedBudget_frameLifetimeIsCappedByTheBudgetShare() {
        var network = new RecordingClusterNetwork(Set.of(A));
        var forwarder = forwarder(network, timeSpan(10).seconds(), Set.of(A));
        var ctx = HttpRequestContext.httpRequestContext(PATH, METHOD, Map.of(), Map.of(), "req-ttl-budget");

        Deadline.runWith(Deadline.fromWireMillis(300), () -> forwarder.forward(ctx, METHOD, PREFIX, "req-ttl-budget")).await();

        assertThat(network.lifetimes()).isNotEmpty();
        assertThat(network.lifetimes().getFirst().millis())
            .as("the budget share (<= 300ms), not the configured 10s hop timeout")
            .isPositive()
            .isLessThanOrEqualTo(300L);
    }

    private static HttpRouteRegistry fixedRouteRegistry(Set<NodeId> owners) {
        return new HttpRouteRegistry() {
            @Override public Option<RouteInfo> findRoute(String httpMethod, String path) {
                return Option.some(RouteInfo.routeInfo(METHOD, PREFIX, owners));
            }
            @Override public List<RouteInfo> allRoutes() {return List.of(RouteInfo.routeInfo(METHOD, PREFIX, owners));}
            @Override public void onNodeRoutesPut(org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut<org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey, org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue> valuePut) {}
            @Override public void onNodeRoutesRemove(org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove<org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey, org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue> valueRemove) {}
            @Override public void evictNode(NodeId nodeId) {}
            @Override public long staleFenceObservationCount() {return 0L;}
        };
    }

    private static final class RecordingClusterNetwork implements ClusterNetwork {
        private final Set<NodeId> connected;
        private final List<ProtocolMessage> sentMessages = new ArrayList<>();
        private final Promise<Unit> firstSend = Promise.promise();
        private final List<TimeSpan> lifetimes = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final java.util.concurrent.atomic.AtomicInteger plainSends = new java.util.concurrent.atomic.AtomicInteger();

        RecordingClusterNetwork(Set<NodeId> connected) {
            this.connected = new HashSet<>(connected);
        }

        synchronized List<ProtocolMessage> sentMessages() {return List.copyOf(sentMessages);}

        List<TimeSpan> lifetimes() {return lifetimes;}

        int plainSends() {return plainSends.get();}

        @Override public <M extends ProtocolMessage> Unit send(NodeId nodeId, M message, TimeSpan offlineTtl) {
            lifetimes.add(offlineTtl);
            return recordSend(message);
        }

        @Override public <M extends ProtocolMessage> Unit broadcast(M message) {return unit();}

        @Override public void connect(NetworkServiceMessage.ConnectNode connectNode) {}
        @Override public void disconnect(NetworkServiceMessage.DisconnectNode disconnectNode) {}
        @Override public void listNodes(NetworkServiceMessage.ListConnectedNodes listConnectedNodes) {}
        @Override public void handleSend(NetworkServiceMessage.Send send) {}
        @Override public void handleBroadcast(NetworkServiceMessage.Broadcast broadcast) {}

        @Override public <M extends ProtocolMessage> Unit send(NodeId nodeId, M message) {
            plainSends.incrementAndGet();
            return recordSend(message);
        }

        private synchronized Unit recordSend(ProtocolMessage message) {
            sentMessages.add(message);
            firstSend.succeed(Unit.unit());
            return unit();
        }

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

    private static final class NoopDeserializer implements Deserializer {
        @Override public <T> T read(io.netty.buffer.ByteBuf byteBuf) {return null;}
    }
}
