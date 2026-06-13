// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.forward;

import org.pragmatica.aether.http.HttpRouteRegistry;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.management.route.ManagementRouteError;
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


/// Bug A coverage: leader-target management forwarding.
///
/// LEADER-bound routes (e.g. `ManagementRoute.NODE_PROMOTE`) must route to the
/// cluster leader, not the task-group owner. When this node is the leader the
/// forwarder must signal `NotLeader` so the local handler runs; when no leader is
/// elected, surface `NoLeaderElected`; when the leader is offline, surface
/// `LeaderDisconnected`.
class HttpForwarderLeaderTargetTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId LEADER = nodeId("node-leader").unwrap();
    private static final NodeId OTHER = nodeId("node-other").unwrap();

    private static final String REASSIGN_PATH = "/api/nodes/promote/node-other";

    private static final String SCALE_PATH = "/api/cluster/scale";

    @Test
    void clusterScale_followerReceiver_returnsConflict() {
        // Defense-in-depth: when a follower receives /api/cluster/scale (which now routes
        // to LEADER), the forwarder must signal NotLeader so the local handler is bypassed.
        // The local handler running on a follower would observe `CTM.active=false` and
        // silently drop the request — the original bug. Surfacing NotLeader (mapped to 409
        // upstream) lets clients retry against the actual leader.
        var network = new RecordingClusterNetwork(Set.of(OTHER));
        var routeRegistry = HttpRouteRegistry.httpRouteRegistry();
        var forwarder = HttpForwarder.httpForwarder(SELF,
                                                    routeRegistry,
                                                    network,
                                                    new NoopSerializer(),
                                                    new NoopDeserializer(),
                                                    timeSpan(1).seconds(),
                                                    () -> Set.of(SELF, OTHER),
                                                    group -> org.pragmatica.aether.slice.delegation.TaskAssignmentError.notAssigned(group).result(),
                                                    () -> Option.some(SELF));

        var ctx = HttpRequestContext.httpRequestContext(SCALE_PATH, "POST", Map.of(), Map.of(), "req-scale-self");
        var result = forwarder.forwardManagement(ctx, "req-scale-self").await();

        assertThat(result.isFailure())
                .as("CLUSTER_SCALE on the leader must signal local handling via NotLeader")
                .isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(ManagementRouteError.NotLeader.class));
        assertThat(network.sendTargets()).isEmpty();
    }

    @Test
    void clusterScale_routesToLeaderNode_whenSelfIsNotLeader() {
        var network = new RecordingClusterNetwork(Set.of(LEADER, OTHER));
        var routeRegistry = HttpRouteRegistry.httpRouteRegistry();
        var forwarder = HttpForwarder.httpForwarder(SELF,
                                                    routeRegistry,
                                                    network,
                                                    new NoopSerializer(),
                                                    new NoopDeserializer(),
                                                    timeSpan(1).seconds(),
                                                    () -> Set.of(SELF, LEADER, OTHER),
                                                    group -> org.pragmatica.aether.slice.delegation.TaskAssignmentError.notAssigned(group).result(),
                                                    () -> Option.some(LEADER));

        var ctx = HttpRequestContext.httpRequestContext(SCALE_PATH, "POST", Map.of(), Map.of(), "req-scale-fwd");
        forwarder.forwardManagement(ctx, "req-scale-fwd");

        assertThat(network.sendTargets())
                .as("CLUSTER_SCALE must dispatch to leader, not the SCALING task-group owner")
                .containsExactly(LEADER);
    }

    @Test
    void forwardLeaderTarget_routesToLeaderNode_whenSelfIsNotLeader() {
        var network = new RecordingClusterNetwork(Set.of(LEADER, OTHER));
        var routeRegistry = HttpRouteRegistry.httpRouteRegistry();
        var forwarder = HttpForwarder.httpForwarder(SELF,
                                                    routeRegistry,
                                                    network,
                                                    new NoopSerializer(),
                                                    new NoopDeserializer(),
                                                    timeSpan(1).seconds(),
                                                    () -> Set.of(SELF, LEADER, OTHER),
                                                    group -> org.pragmatica.aether.slice.delegation.TaskAssignmentError.notAssigned(group).result(),
                                                    () -> Option.some(LEADER));

        var ctx = HttpRequestContext.httpRequestContext(REASSIGN_PATH, "POST", Map.of(), Map.of(), "req-1");
        forwarder.forwardManagement(ctx, "req-1");

        assertThat(network.sendTargets())
            .as("Leader-bound route must dispatch to leader, not task-group owner")
            .containsExactly(LEADER);
    }

    @Test
    void forwardLeaderTarget_returnsConflict_whenSelfIsLeader() {
        var network = new RecordingClusterNetwork(Set.of(OTHER));
        var routeRegistry = HttpRouteRegistry.httpRouteRegistry();
        var forwarder = HttpForwarder.httpForwarder(SELF,
                                                    routeRegistry,
                                                    network,
                                                    new NoopSerializer(),
                                                    new NoopDeserializer(),
                                                    timeSpan(1).seconds(),
                                                    () -> Set.of(SELF, OTHER),
                                                    group -> org.pragmatica.aether.slice.delegation.TaskAssignmentError.notAssigned(group).result(),
                                                    () -> Option.some(SELF));

        var ctx = HttpRequestContext.httpRequestContext(REASSIGN_PATH, "POST", Map.of(), Map.of(), "req-2");
        var promise = forwarder.forwardManagement(ctx, "req-2");
        var result = promise.await();

        assertThat(result.isFailure())
            .as("When local node is leader, forwarder must signal local handling via NotLeader")
            .isTrue();
        result.onSuccess(success -> {throw new AssertionError("expected failure");});
        result.onFailure(cause -> assertThat(cause).isInstanceOf(ManagementRouteError.NotLeader.class));
        assertThat(network.sendTargets()).isEmpty();
    }

    @Test
    void forwardLeaderTarget_returnsNoLeaderElected_whenLeaderUnknown() {
        var network = new RecordingClusterNetwork(Set.of(OTHER));
        var routeRegistry = HttpRouteRegistry.httpRouteRegistry();
        var forwarder = HttpForwarder.httpForwarder(SELF,
                                                    routeRegistry,
                                                    network,
                                                    new NoopSerializer(),
                                                    new NoopDeserializer(),
                                                    timeSpan(1).seconds(),
                                                    () -> Set.of(SELF, OTHER),
                                                    group -> org.pragmatica.aether.slice.delegation.TaskAssignmentError.notAssigned(group).result(),
                                                    Option::none);

        var ctx = HttpRequestContext.httpRequestContext(REASSIGN_PATH, "POST", Map.of(), Map.of(), "req-3");
        var result = forwarder.forwardManagement(ctx, "req-3").await();

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(ManagementRouteError.NoLeaderElected.class));
        assertThat(network.sendTargets()).isEmpty();
    }

    @Test
    void forwardLeaderTarget_returnsLeaderDisconnected_whenLeaderOffline() {
        var network = new RecordingClusterNetwork(Set.of(OTHER));
        var routeRegistry = HttpRouteRegistry.httpRouteRegistry();
        var forwarder = HttpForwarder.httpForwarder(SELF,
                                                    routeRegistry,
                                                    network,
                                                    new NoopSerializer(),
                                                    new NoopDeserializer(),
                                                    timeSpan(1).seconds(),
                                                    () -> Set.of(SELF, LEADER, OTHER),
                                                    group -> org.pragmatica.aether.slice.delegation.TaskAssignmentError.notAssigned(group).result(),
                                                    () -> Option.some(LEADER));

        var ctx = HttpRequestContext.httpRequestContext(REASSIGN_PATH, "POST", Map.of(), Map.of(), "req-4");
        var result = forwarder.forwardManagement(ctx, "req-4").await();

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(ManagementRouteError.LeaderDisconnected.class));
    }

    private static final class RecordingClusterNetwork implements ClusterNetwork {
        private final Set<NodeId> connected;
        private final List<NodeId> sendTargets = new java.util.ArrayList<>();

        RecordingClusterNetwork(Set<NodeId> connected) {
            this.connected = new HashSet<>(connected);
        }

        List<NodeId> sendTargets() {return List.copyOf(sendTargets);}

        @Override public <M extends ProtocolMessage> Unit broadcast(M message) {return unit();}

        @Override public void connect(NetworkServiceMessage.ConnectNode connectNode) {}
        @Override public void disconnect(NetworkServiceMessage.DisconnectNode disconnectNode) {}
        @Override public void listNodes(NetworkServiceMessage.ListConnectedNodes listConnectedNodes) {}
        @Override public void handleSend(NetworkServiceMessage.Send send) {}
        @Override public void handleBroadcast(NetworkServiceMessage.Broadcast broadcast) {}

        @Override public <M extends ProtocolMessage> Unit send(NodeId nodeId, M message) {
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
