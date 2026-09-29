// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.forward;

import org.pragmatica.aether.http.HttpRouteRegistry;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue.RouteEntry;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
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


/// #1678: a forward for one sibling route goes ONLY to nodes serving that sibling -- initial selection and every
/// retry. Two slices share the base `/orders/`: A serves `GET /orders/{id}`, B serves `GET /orders/{id}/admin`.
/// B and D serve the admin sibling. Every attempt times out, so the forwarder retries through its re-queried
/// candidates; each must still be B or D.
class HttpForwarderSiblingShapeTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId A = nodeId("node-a").unwrap();
    private static final NodeId B = nodeId("node-b").unwrap();
    private static final NodeId C = nodeId("node-c").unwrap();
    private static final NodeId D = nodeId("node-d").unwrap();

    @Test
    void forwardForTheAdminSibling_reachesOnlyTheNodesServingIt_acrossRetries() {
        var registry = HttpRouteRegistry.httpRouteRegistry();
        registry.onNodeRoutesPut(put(A, "org.example:orders-public:1.0.0", 1, List.of()));
        registry.onNodeRoutesPut(put(C, "org.example:orders-public:1.0.0", 1, List.of()));
        registry.onNodeRoutesPut(put(B, "org.example:orders-admin:1.0.0", 2, List.of("admin")));
        registry.onNodeRoutesPut(put(D, "org.example:orders-admin:1.0.0", 2, List.of("admin")));
        var network = new RecordingClusterNetwork(Set.of(A, B, C, D));
        var forwarder = HttpForwarder.httpForwarder(SELF,
                                                    registry,
                                                    network,
                                                    new NoopSerializer(),
                                                    new NoopDeserializer(),
                                                    timeSpan(50).millis(),
                                                    50L,
                                                    5,
                                                    () -> Set.of(SELF, A, B, C, D),
                                                    group -> org.pragmatica.aether.slice.delegation.TaskAssignmentError.notAssigned(group).result(),
                                                    HttpForwarder.NO_LEADER_RESOLVER,
                                                    AccessibilityFilter.IDENTITY);
        var admin = registry.allRoutes()
                            .getFirst()
                            .servingShape("/orders/5/admin");
        var ctx = HttpRequestContext.httpRequestContext("/orders/5/admin", "GET", Map.of(), Map.of(), "req-sibling");

        forwarder.forward(ctx, admin, "req-sibling").await();

        assertThat(network.sendTargets()).as("CONTROL: the forward was retried at least once").hasSizeGreaterThan(1);
        assertThat(network.distinctSendTargets()).as("only the nodes serving the admin sibling, retries included")
                                                 .isSubsetOf(B, D);
    }

    private static ValuePut<NodeRoutesKey, NodeRoutesValue> put(NodeId node, String artifact, int arity, List<String> spacers) {
        var route = RouteEntry.activeRoute("GET", "/orders/", "handle", "PUBLIC", "PUBLIC", arity, spacers);
        var value = NodeRoutesValue.nodeRoutesValue(List.of(route), Epoch.ZERO);

        return new ValuePut<>(new KVCommand.Put<>(NodeRoutesKey.nodeRoutesKey(node, Artifact.artifact(artifact).unwrap()), value),
                              Option.none());
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
