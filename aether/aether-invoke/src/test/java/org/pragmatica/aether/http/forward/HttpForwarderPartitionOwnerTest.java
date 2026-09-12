// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.forward;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.HttpRouteRegistry;
import org.pragmatica.aether.http.forward.HttpForwarder.PartitionOwnerResolver;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.management.route.ManagementRouteError;
import org.pragmatica.aether.management.route.MatchedRoute;
import org.pragmatica.aether.slice.delegation.TaskAssignmentError;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// #1039: `ManagementRoute.STREAM_REPLICAS` forwards to the partition's computed HRW owner.
///
/// The defect this replaces was structural, not a race: `taskGroup(STREAMING)` dispatch lands the
/// request on an ARBITRARY STREAMING-capable node, and the `ReplicaRegistry` is authoritative only ON
/// the owner (only the owner receives every replica's ack). A non-owner therefore answered
/// `servedByOwner=false` with an empty ring — indistinguishable from a genuinely empty partition.
/// Measured on a live 5-node cluster, same node and instant: `replicas-local` reported
/// `servedByOwner=true, ownerHeadOffset=20`; the delegate-routed variant reported
/// `servedByOwner=false, ownerHeadOffset=0` from 5 of 5 ports, the owner's own included.
///
/// The destination is COMPUTED, so these tests stub the injected `PartitionOwnerResolver` — the node
/// supplies the real one, reading the same resolver `StreamReadRouter.replicaSnapshot` reads, so the
/// forwarder and the answering handler agree by construction rather than by coincidence.
class HttpForwarderPartitionOwnerTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId OWNER = nodeId("node-owner").unwrap();
    private static final NodeId OTHER = nodeId("node-other").unwrap();

    private static final String REPLICAS_PATH = ManagementRoute.STREAM_REPLICAS.assemble(List.of("myns",
                                                                                                 "mystream",
                                                                                                 "1.0.0",
                                                                                                 "7"))
                                                                               .unwrap();

    @Test
    void forwardManagement_forwardsToResolvedOwner_whenSelfIsNotOwner() {
        var network = new RecordingClusterNetwork(Set.of(OWNER, OTHER));
        var resolver = new RecordingResolver(Option.some(OWNER));
        var forwarder = forwarder(SELF, network, new NoopSerializer(), resolver);

        forwarder.forwardManagement(context(REPLICAS_PATH, Map.of()), "req-owner");

        assertThat(network.sendTargets())
                .as("STREAM_REPLICAS must reach the partition owner, not an arbitrary STREAMING node")
                .containsExactly(OWNER);
        assertThat(resolver.calls())
                .as("control: the answer must come from the injected owner resolver, not a fallback path")
                .hasSize(1);
    }

    @Test
    void forwardManagement_passesTheMatchedRouteAndPartitionIndexToTheResolver() {
        // Pins the "one derivation of stream identity" contract at this boundary (#1040): the
        // forwarder hands over the matched route intact and lets the node reduce it, instead of
        // computing an engine key here from a second copy of `StreamManager.engineKey`'s rules.
        var network = new RecordingClusterNetwork(Set.of(OWNER));
        var resolver = new RecordingResolver(Option.some(OWNER));

        forwarder(SELF, network, new NoopSerializer(), resolver).forwardManagement(context(REPLICAS_PATH, Map.of()),
                                                                                   "req-identity");

        assertThat(resolver.calls()).hasSize(1);

        var call = resolver.calls().getFirst();

        assertThat(call.matched().route()).isEqualTo(ManagementRoute.STREAM_REPLICAS);
        assertThat(call.matched().params()).containsExactlyInAnyOrderEntriesOf(Map.of("namespace",
                                                                                      "myns",
                                                                                      "stream",
                                                                                      "mystream",
                                                                                      "version",
                                                                                      "1.0.0",
                                                                                      "partition",
                                                                                      "7"));
        assertThat(call.partitionParamIndex())
                .as("the index must select `partition`, which is param 3 on this route")
                .isEqualTo(3);
    }

    @Test
    void forwardManagement_signalsLocalHandling_whenSelfIsTheOwner() {
        var network = new RecordingClusterNetwork(Set.of(OTHER));
        var forwarder = forwarder(SELF, network, new NoopSerializer(), new RecordingResolver(Option.some(SELF)));

        var result = forwarder.forwardManagement(context(REPLICAS_PATH, Map.of()), "req-self").await();

        result.onSuccess(_ -> fail("owner-is-self must signal local handling, not forward"))
              .onFailure(cause -> assertThat(cause).isInstanceOf(ManagementRouteError.NotLocalTarget.class));
        assertThat(network.sendTargets()).isEmpty();
    }

    @Test
    void forwardManagement_failsWithPartitionOwnerUnresolved_whenNoOwnerIsComputable() {
        // The honest outcome for an empty member view / pre-reconcile bootstrap window. Answering
        // locally instead would return servedByOwner=false with an empty ring, which is exactly the
        // "looks like an empty partition" ambiguity #1039 exists to remove.
        var network = new RecordingClusterNetwork(Set.of(OWNER, OTHER));
        var forwarder = forwarder(SELF, network, new NoopSerializer(), new RecordingResolver(Option.none()));

        var result = forwarder.forwardManagement(context(REPLICAS_PATH, Map.of()), "req-noowner").await();

        result.onSuccess(_ -> fail("an unresolvable owner must not produce an answer"))
              .onFailure(cause -> assertThat(cause).isInstanceOf(ManagementRouteError.PartitionOwnerUnresolved.class));
        assertThat(network.sendTargets()).isEmpty();
    }

    @Test
    void forwardManagement_failsWithTargetDisconnected_whenTheOwnerIsOffline() {
        var network = new RecordingClusterNetwork(Set.of(OTHER));
        var forwarder = forwarder(SELF, network, new NoopSerializer(), new RecordingResolver(Option.some(OWNER)));

        var result = forwarder.forwardManagement(context(REPLICAS_PATH, Map.of()), "req-offline").await();

        result.onSuccess(_ -> fail("a disconnected owner must not be reported as answered"))
              .onFailure(cause -> assertThat(cause).isInstanceOf(ManagementRouteError.TargetDisconnected.class));
        assertThat(network.sendTargets()).isEmpty();
    }

    @Test
    void forwardManagement_stampsTheForwardingNodeOntoTheForwardedRequest() {
        var network = new RecordingClusterNetwork(Set.of(OWNER));
        var serializer = new CapturingSerializer();

        forwarder(SELF, network, serializer, new RecordingResolver(Option.some(OWNER))).forwardManagement(context(REPLICAS_PATH,
                                                                                                                  Map.of()),
                                                                                                          "req-stamp");

        assertThat(serializer.captured()).hasSize(1);
        assertThat(serializer.captured().getFirst().headers())
                .as("the forwarded request must name its forwarding hop so a second owner-forward is detectable")
                .containsEntry(HttpForwarder.OWNER_FORWARDED_BY_HEADER, List.of(SELF.id()));
    }

    @Test
    void forwardManagement_failsWithOwnerForwardLoop_whenTwoNodesDisagreeOnTheOwner() {
        // The membership-skew cycle, driven end to end rather than hand-fed: A's OWN stamped output
        // is what B receives. A believes B owns the partition; B believes A does. Without the marker
        // this pair would bounce until the request budget ran out, reporting a deadline — a symptom
        // indistinguishable from a slow peer. With it, the second hop terminates on a named cause.
        var nodeA = SELF;
        var nodeB = OWNER;
        var networkA = new RecordingClusterNetwork(Set.of(nodeB));
        var serializerA = new CapturingSerializer();

        forwarder(nodeA, networkA, serializerA, new RecordingResolver(Option.some(nodeB))).forwardManagement(context(REPLICAS_PATH,
                                                                                                                     Map.of()),
                                                                                                             "req-skew");

        assertThat(networkA.sendTargets()).as("precondition: A forwards to the node it believes owns the partition")
                                          .containsExactly(nodeB);
        assertThat(serializerA.captured()).hasSize(1);

        var arrivedAtB = serializerA.captured().getFirst();
        var networkB = new RecordingClusterNetwork(Set.of(nodeA));
        var resolverB = new RecordingResolver(Option.some(nodeA));

        var result = forwarder(nodeB, networkB, new NoopSerializer(), resolverB).forwardManagement(arrivedAtB,
                                                                                                   "req-skew")
                                                                                .await();

        result.onSuccess(_ -> fail("a second owner-forward under skew must not proceed"))
              .onFailure(cause -> assertThat(cause).isInstanceOf(ManagementRouteError.OwnerForwardLoop.class));
        assertThat(networkB.sendTargets()).as("B must not bounce the request back to A").isEmpty();
        assertThat(resolverB.calls()).as("the loop guard must precede resolution — a skewed view cannot authorize a second hop")
                                     .isEmpty();
    }

    @Test
    void forwardManagement_forwardsNormally_whenAnUnrelatedHeaderIsPresent() {
        // Positive control for the loop guard: it must key on ITS OWN marker, not on any header at
        // all. Without this, a guard matching too broadly would pass every test above by refusing
        // everything, and look identical in a summary.
        var network = new RecordingClusterNetwork(Set.of(OWNER));
        var forwarder = forwarder(SELF, network, new NoopSerializer(), new RecordingResolver(Option.some(OWNER)));

        forwarder.forwardManagement(context(REPLICAS_PATH, Map.of("X-Aether-Served-By", List.of("node-x"))),
                                    "req-unrelated");

        assertThat(network.sendTargets()).containsExactly(OWNER);
    }

    private static HttpRequestContext context(String path, Map<String, List<String>> headers) {
        return HttpRequestContext.httpRequestContext(path, "GET", Map.of(), headers, "req");
    }

    private static HttpForwarder forwarder(NodeId self,
                                           ClusterNetwork network,
                                           Serializer serializer,
                                           PartitionOwnerResolver ownerResolver) {
        return HttpForwarder.httpForwarder(self,
                                           HttpRouteRegistry.httpRouteRegistry(),
                                           network,
                                           serializer,
                                           new NoopDeserializer(),
                                           timeSpan(1).seconds(),
                                           HttpForwarder.DEFAULT_RETRY_DELAY_MS,
                                           HttpForwarder.DEFAULT_MAX_FORWARD_RETRIES,
                                           () -> Set.of(SELF, OWNER, OTHER),
                                           group -> TaskAssignmentError.notAssigned(group).result(),
                                           Option::none,
                                           ownerResolver);
    }

    private record ResolverCall(MatchedRoute matched, int partitionParamIndex) {}

    private static final class RecordingResolver implements PartitionOwnerResolver {
        private final Option<NodeId> owner;
        private final List<ResolverCall> calls = new ArrayList<>();

        RecordingResolver(Option<NodeId> owner) {
            this.owner = owner;
        }

        List<ResolverCall> calls() {return List.copyOf(calls);}

        @Override
        public Option<NodeId> resolve(MatchedRoute matched, int partitionParamIndex) {
            calls.add(new ResolverCall(matched, partitionParamIndex));

            return owner;
        }
    }

    private static final class RecordingClusterNetwork implements ClusterNetwork {
        private final Set<NodeId> connected;
        private final List<NodeId> sendTargets = new ArrayList<>();

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

    /// Captures the `HttpRequestContext` handed to the wire so the forwarded request can be inspected
    /// — and, in the skew test, replayed into the receiving node's forwarder as its actual input.
    private static final class CapturingSerializer implements Serializer {
        private final List<HttpRequestContext> captured = new ArrayList<>();

        List<HttpRequestContext> captured() {return List.copyOf(captured);}

        @Override public <T> void write(io.netty.buffer.ByteBuf byteBuf, T object) {}

        @Override public <T> byte[] encode(T value) {
            if (value instanceof HttpRequestContext context) {
                captured.add(context);
            }

            return new byte[0];
        }
    }

    private static final class NoopDeserializer implements Deserializer {
        @Override public <T> T read(io.netty.buffer.ByteBuf byteBuf) {return null;}
    }
}
