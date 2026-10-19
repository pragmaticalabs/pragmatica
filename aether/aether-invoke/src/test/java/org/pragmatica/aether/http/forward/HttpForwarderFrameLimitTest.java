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
import org.pragmatica.consensus.net.OutboundMessageLimit;
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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// A3/I12: a request whose encoded forward exceeds the cluster frame limit is never sent (the receiver would close
/// the FORWARD lane to that peer, see `OversizedLaneFrameTest` in the consensus module) and fails terminally with a
/// 413 saying it is too large to forward; it is not retried on another node. A request within the limit is sent.
/// The stub network applies the transport's own rule ([OutboundMessageLimit#validate]) to a frame [#FRAME_OVERHEAD]
/// bytes larger than the request payload it carries, as a real frame is.
class HttpForwarderFrameLimitTest {
    private static final int FRAME_OVERHEAD = 77;

    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId A = nodeId("node-a").unwrap();
    private static final NodeId B = nodeId("node-b").unwrap();
    private static final String PREFIX = "/api/uploads";

    @Test
    void forward_overTheFrameLimit_isNotSent_andFails413() {
        var network = new LimitedClusterNetwork();
        var result = forwarder(network, OutboundMessageLimit.MAX_TRANSFER_BYTES + 1)
            .forward(context(), "PUT", PREFIX, "too-large")
            .await(timeSpan(5).seconds());

        assertThat(network.sends.get()).as("an oversized frame must never reach the transport").isZero();
        var failure = result.fold(cause -> cause, value -> (Cause) null);
        assertThat(failure).isInstanceOf(ForwardError.TooLargeToForward.class);
        assertThat(((ForwardError) failure).status()).isEqualTo(HttpStatus.REQUEST_ENTITY_TOO_LARGE);
        assertThat(HttpError.clientMessage(failure)).contains("too large to forward");
    }

    /// F8(c): the 413 reports the size of the refused frame, the quantity the limit bounds, not the payload's.
    @Test
    void forward_overTheFrameLimit_reportsTheFrameSize() {
        var payloadBytes = OutboundMessageLimit.MAX_TRANSFER_BYTES + 1;
        var frameBytes = payloadBytes + FRAME_OVERHEAD;
        var result = forwarder(new LimitedClusterNetwork(), payloadBytes)
            .forward(context(), "PUT", PREFIX, "too-large-size")
            .await(timeSpan(5).seconds());

        var failure = result.fold(cause -> cause, value -> (Cause) null);
        assertThat(((ForwardError.TooLargeToForward) failure).frameBytes()).isEqualTo(frameBytes);
        assertThat(HttpError.clientMessage(failure)).contains(frameBytes + "-byte frame")
                                                    .doesNotContain(String.valueOf(payloadBytes));
    }

    /// A forward frame that cannot be encoded was never sent: it answers as unsent (503), never as too large.
    @Test
    void forward_whoseFrameCannotBeEncoded_isNotSent_andNotReportedTooLarge() {
        var network = new LimitedClusterNetwork(true);
        var result = forwarder(network, 1024)
            .forward(context(), "PUT", PREFIX, "unencodable")
            .await(timeSpan(5).seconds());

        assertThat(network.sends.get()).isZero();
        var failure = result.fold(cause -> cause, value -> (Cause) null);
        assertThat(failure).isInstanceOf(ForwardError.NotSent.class);
        assertThat(((ForwardError) failure).status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void forward_withinTheFrameLimit_isSent() {
        var network = new LimitedClusterNetwork();

        forwarder(network, 1024).forward(context(), "PUT", PREFIX, "fits").await(timeSpan(5).seconds());

        assertThat(network.sends.get()).isPositive();
    }

    private static HttpForwarder forwarder(ClusterNetwork network, int encodedSize) {
        return HttpForwarder.httpForwarder(SELF,
                                           fixedRouteRegistry(),
                                           network,
                                           new SizedSerializer(encodedSize),
                                           new NoopDeserializer(),
                                           timeSpan(100).millis(),
                                           10L,
                                           3,
                                           () -> Set.of(SELF, A, B),
                                           HttpForwarder.UNASSIGNED_RESOLVER,
                                           HttpForwarder.NO_LEADER_RESOLVER,
                                           AccessibilityFilter.IDENTITY);
    }

    private static HttpRequestContext context() {
        return HttpRequestContext.httpRequestContext(PREFIX + "/1", "PUT", Map.of(), Map.of(), "frame-limit");
    }

    private static HttpRouteRegistry fixedRouteRegistry() {
        return new HttpRouteRegistry() {
            @Override public Option<RouteInfo> findRoute(String httpMethod, String path) {
                return Option.some(RouteInfo.routeInfo("PUT", PREFIX, Set.of(A, B)));
            }
            @Override public List<RouteInfo> allRoutes() {return List.of(RouteInfo.routeInfo("PUT", PREFIX, Set.of(A, B)));}
            @Override public void onNodeRoutesPut(org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut<org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey, org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue> valuePut) {}
            @Override public void onNodeRoutesRemove(org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove<org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey, org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue> valueRemove) {}
            @Override public void evictNode(NodeId nodeId) {}
            @Override public long staleFenceObservationCount() {return 0L;}
        };
    }

    private static final class LimitedClusterNetwork implements ClusterNetwork {
        private final AtomicInteger sends = new AtomicInteger();
        private final boolean frameUnencodable;

        LimitedClusterNetwork() {
            this(false);
        }

        LimitedClusterNetwork(boolean frameUnencodable) {
            this.frameUnencodable = frameUnencodable;
        }

        @Override public Result<Unit> validateOutboundMessage(ProtocolMessage message) {
            var frameBytes = ((HttpForwardRequest) message).requestData().length + FRAME_OVERHEAD;

            return OutboundMessageLimit.validate(new FrameSerializer(frameBytes, frameUnencodable), message);
        }

        @Override public <M extends ProtocolMessage> Promise<WriteOutcome> sendOutcome(NodeId nodeId, M message, TimeSpan offlineTtl) {
            sends.incrementAndGet();
            return Promise.success(new WriteOutcome.Sent(nodeId));
        }

        @Override public <M extends ProtocolMessage> Unit send(NodeId nodeId, M message) {return unit();}
        @Override public <M extends ProtocolMessage> Unit broadcast(M message) {return unit();}
        @Override public void connect(NetworkServiceMessage.ConnectNode connectNode) {}
        @Override public void disconnect(NetworkServiceMessage.DisconnectNode disconnectNode) {}
        @Override public void listNodes(NetworkServiceMessage.ListConnectedNodes listConnectedNodes) {}
        @Override public void handleSend(NetworkServiceMessage.Send send) {}
        @Override public void handleBroadcast(NetworkServiceMessage.Broadcast broadcast) {}
        @Override public Promise<Unit> start() {return Promise.unitPromise();}
        @Override public Promise<Unit> stop() {return Promise.unitPromise();}
        @Override public int connectedNodeCount() {return 2;}
        @Override public Set<NodeId> connectedPeers() {return Set.of(A, B);}
        @Override public Option<Server> server() {return Option.none();}
    }

    /// Encodes a frame of the given size, or throws as an encoder failing on the frame would.
    private record FrameSerializer(int size, boolean fails) implements Serializer {
        @Override public <T> void write(io.netty.buffer.ByteBuf byteBuf, T object) {}
        @Override public <T> byte[] encode(T value) {
            if (fails) {
                throw new IllegalStateException("frame cannot be encoded");
            }
            return new byte[size];
        }
    }

    private record SizedSerializer(int size) implements Serializer {
        @Override public <T> void write(io.netty.buffer.ByteBuf byteBuf, T object) {}
        @Override public <T> byte[] encode(T value) {return new byte[size];}
    }

    private static final class NoopDeserializer implements Deserializer {
        @Override public <T> T read(io.netty.buffer.ByteBuf byteBuf) {return null;}
    }
}
