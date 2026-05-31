// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.forward;

import org.pragmatica.aether.http.HttpRouteRegistry;
import org.pragmatica.aether.http.HttpRouteRegistry.RouteInfo;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.net.tcp.Server;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Silent-death fix coverage: the data-path forwarder must narrow candidates by the injected
/// [`AccessibilityFilter`] (NTT-backed in production) in addition to the QUIC `connectedPeers`
/// set. A hard-killed peer stays QUIC-CONNECTED until eviction, so without this gate the
/// forwarder keeps selecting it and burning the forward timeout. The filter drops it from both
/// initial selection and the retry re-query, and the retry budget is computed off the filtered
/// candidate count.
class HttpForwarderAccessibilityTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId A = nodeId("node-a").unwrap();
    private static final NodeId B = nodeId("node-b").unwrap();
    private static final NodeId C = nodeId("node-c").unwrap();

    private static final String METHOD = "GET";
    private static final String PREFIX = "/api/widgets";
    private static final String PATH = "/api/widgets/42";

    private HttpForwarder forwarder(ClusterNetwork network, AccessibilityFilter filter) {
        var routeRegistry = fixedRouteRegistry(Set.of(A, B, C));
        return HttpForwarder.httpForwarder(SELF,
                                           routeRegistry,
                                           network,
                                           new NoopSerializer(),
                                           new NoopDeserializer(),
                                           timeSpan(50).millis(),
                                           50L,
                                           5,
                                           () -> Set.of(SELF, A, B, C),
                                           group -> org.pragmatica.aether.slice.delegation.TaskAssignmentError.notAssigned(group).result(),
                                           HttpForwarder.NO_LEADER_RESOLVER,
                                           filter);
    }

    @Test
    void forward_excludesInaccessibleNode_neverSelectsIt() {
        var network = new RecordingClusterNetwork(Set.of(A, B, C));
        // B is QUIC-connected but NTT marks it inaccessible (hard-killed silent death).
        AccessibilityFilter excludeB = candidates -> candidates.stream().filter(n -> !n.equals(B)).toList();
        var forwarder = forwarder(network, excludeB);

        var ctx = HttpRequestContext.httpRequestContext(PATH, METHOD, Map.of(), Map.of(), "req-excl");
        forwarder.forward(ctx, METHOD, PREFIX, "req-excl").await();

        assertThat(network.sendTargets())
            .as("inaccessible B must never be selected, even across retries")
            .doesNotContain(B);
        assertThat(network.sendTargets()).isSubsetOf(A, C);
    }

    @Test
    void forward_retryBudgetReflectsFilteredCount() {
        var network = new RecordingClusterNetwork(Set.of(A, B, C));
        // Only A is accessible -> filtered candidate count = 1 -> retry budget = min(1-1, 5) = 0.
        AccessibilityFilter onlyA = candidates -> candidates.stream().filter(n -> n.equals(A)).toList();
        var forwarder = forwarder(network, onlyA);

        var ctx = HttpRequestContext.httpRequestContext(PATH, METHOD, Map.of(), Map.of(), "req-budget");
        forwarder.forward(ctx, METHOD, PREFIX, "req-budget").await();

        assertThat(network.distinctSendTargets())
            .as("zero retry budget off filtered count = 1 -> exactly one node attempted (A)")
            .containsExactly(A);
    }

    @Test
    void forward_identityFilter_preservesLegacyBehavior() {
        var network = new RecordingClusterNetwork(Set.of(A, B, C));
        var forwarder = forwarder(network, AccessibilityFilter.IDENTITY);

        var ctx = HttpRequestContext.httpRequestContext(PATH, METHOD, Map.of(), Map.of(), "req-legacy");
        forwarder.forward(ctx, METHOD, PREFIX, "req-legacy").await();

        assertThat(network.sendTargets())
            .as("IDENTITY filter selects from the full connected route-owner set")
            .isSubsetOf(A, B, C)
            .isNotEmpty();
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
        private final List<NodeId> sendTargets = new java.util.ArrayList<>();

        RecordingClusterNetwork(Set<NodeId> connected) {
            this.connected = new HashSet<>(connected);
        }

        synchronized List<NodeId> sendTargets() {return List.copyOf(sendTargets);}

        synchronized Set<NodeId> distinctSendTargets() {return Set.copyOf(sendTargets);}

        @Override public <M extends ProtocolMessage> Unit broadcast(M message) {return unit();}

        @Override public void connect(NetworkServiceMessage.ConnectNode connectNode) {}
        @Override public void disconnect(NetworkServiceMessage.DisconnectNode disconnectNode) {}
        @Override public void listNodes(NetworkServiceMessage.ListConnectedNodes listConnectedNodes) {}
        @Override public void handleSend(NetworkServiceMessage.Send send) {}
        @Override public void handleBroadcast(NetworkServiceMessage.Broadcast broadcast) {}

        @Override public synchronized <M extends ProtocolMessage> Unit send(NodeId nodeId, M message) {
            sendTargets.add(nodeId);
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
