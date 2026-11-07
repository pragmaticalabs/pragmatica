// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.TimeoutsConfig.ForwardingTimeouts;
import org.pragmatica.aether.config.UploadConfig;
import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardRequest;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.ObjectHandle;
import org.pragmatica.aether.http.handler.UploadHandle;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.http.upload.CapturingResponse;
import org.pragmatica.aether.http.upload.TestRequest;
import org.pragmatica.aether.node.NodeCodecs;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue.RouteEntry;
import org.pragmatica.aether.storage.DhtStorageTier;
import org.pragmatica.aether.update.DeploymentManager;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.dht.Partition;
import org.pragmatica.http.server.BodySink;
import org.pragmatica.http.server.Disposition;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.Server;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.storage.StorageInstance;

import static java.util.Arrays.copyOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.http.StreamingIngestHarness.MIB;
import static org.pragmatica.aether.http.StreamingIngestHarness.TIMEOUT;
import static org.pragmatica.aether.http.StreamingIngestHarness.WAIT;
import static org.pragmatica.aether.http.StreamingIngestHarness.sha256;
import static org.pragmatica.aether.http.StreamingIngestHarness.sha256Hex;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;

/// U2 forward by reference: an upload the ingress node streamed into storage crosses to the serving node as a handle, not
/// as its bytes, and the serving node's slice opens the same object from its own `content` instance.
///
/// Each node's `content` instance here is a storage instance named `content` over one `DhtStorageTier` with the key
/// prefix `content-blocks`, both over ONE in-memory DHT client: on a node, the content instance's shared tier is the
/// cluster DHT under `content-blocks` (`StorageFactory`'s shared tier), and that sharing is what lets a node open a block
/// another node wrote. Each instance keeps its own catalog, so the serving node reads by reference alone.
@Timeout(300)
class ForwardByReferenceTest {
    private static final NodeId INGRESS = NodeId.nodeId("ingress-a").unwrap();
    private static final NodeId HOST = NodeId.nodeId("host-b").unwrap();
    private static final Artifact ARTIFACT = Artifact.artifact(StreamingIngestHarness.ARTIFACT).unwrap();
    /// Exactly the route's prefix: the published remote route declares no path segments beyond it.
    private static final String PATH = "/api/upload/";
    private static final String TYPE = "application/x-forward-test";
    private static final int CHUNK = 64 * 1024;
    /// At least 64 MiB, and not a multiple of the chunk.
    private static final long SIZE = 64L * MIB + 4321;
    private static final int FRAME_BOUND = 64 * 1024;
    private static final SliceCodec CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
    private static final UploadConfig STREAMING = UploadConfig.DEFAULT.withStreamingIngest(true);

    private final InMemoryDHTClient dht = new InMemoryDHTClient();
    private final StorageInstance ingressStorage = contentInstance(dht);
    private final StorageInstance hostStorage = contentInstance(dht);
    private final RecordingClusterNetwork ingressNetwork = new RecordingClusterNetwork(HOST);
    private final RecordingClusterNetwork hostNetwork = new RecordingClusterNetwork(INGRESS);
    private final HttpRouteRegistry ingressRegistry = HttpRouteRegistry.httpRouteRegistry();
    private final StreamingIngestHarness.CapturingRouter hostRouter = new StreamingIngestHarness.CapturingRouter();
    private AppHttpServer ingress;

    @AfterEach
    void stop() {
        if (ingress != null) {
            ingress.stop().await(TIMEOUT);
        }
    }

    /// Kills "put the body back in the forward": the forwarded frame, encoded with the production node codec, stays under
    /// 64 KiB for a body of 64 MiB, and the serving node's slice reads the body's exact bytes through the handle.
    @Test
    void streamedUpload_forwardedToTheServingNode_crossesAsAReferenceTheHostOpens() {
        ingress = ingressServer();
        assertThat(ingress.start().await(TIMEOUT).isSuccess()).as("control: the ingress listener starts").isTrue();
        ingress.onQuorumStateChange(ClusterStateNotification.active());
        ingressRegistry.onNodeRoutesPut(new ValuePut<>(new KVCommand.Put<>(NodeRoutesKey.nodeRoutesKey(HOST, ARTIFACT),
                                                                           NodeRoutesValue.nodeRoutesValue(List.of(remoteUploadRoute()))),
                                                       Option.none()));
        var head = TestRequest.post(PATH, TYPE, SIZE);
        var admitted = await().atMost(WAIT).until(() -> classifyAfterRepublish(head), StreamingIngestHarness::isStream);
        var sink = ((Disposition.Stream) admitted).sink();
        var bodyDigest = streamDeterministicBody(sink);

        sink.complete(head, CapturingResponse.capturingResponse());

        var forward = ingressNetwork.forwarded();

        assertThat(forward.requestData().length).as("the forwarded request carries no body").isLessThan(FRAME_BOUND);
        assertThat(CODEC.encode(forward).length).as("the whole forward frame, production codec").isLessThan(FRAME_BOUND);

        var host = hostServer();

        host.setUploadStorage(hostStorage);
        host.onHttpForwardRequest(forward);

        assertTheHostReadsTheBody(hostRouter.seen(), bodyDigest);
    }

    /// The conversion both the local dispatch and the forward use (`toHttpRequestContext`), measured where it does not
    /// depend on the remote route table: the context the ingress hands its slice, encoded with the production codec, is
    /// under 64 KiB, and decoded on the other side it opens the body from the other node's instance.
    @Test
    void dispatchedContext_encodedWithTheProductionCodec_isSmall_andOpensFromTheOtherInstance() {
        var harness = StreamingIngestHarness.harness(STREAMING, ingressStorage, true);
        var head = TestRequest.post(PATH, TYPE, SIZE);
        var sink = harness.stream(head);
        var bodyDigest = streamDeterministicBody(sink);
        var response = CapturingResponse.capturingResponse();

        sink.complete(head, response);
        assertThat(response.status()).isEqualTo(200);

        var encoded = CODEC.encode(harness.router.seen());

        assertThat(encoded.length).isLessThan(FRAME_BOUND);
        HttpRequestContext decoded = CODEC.decode(encoded);

        assertTheHostReadsTheBody(decoded, bodyDigest);
    }

    private void assertTheHostReadsTheBody(HttpRequestContext seen, byte[] bodyDigest) {
        assertThat(seen.body()).as("the slice gets an empty body").isEmpty();
        assertThat(seen.upload().isPresent()).as("and the handle of what the ingress stored").isTrue();
        UploadHandle upload = seen.upload().unwrap();

        assertThat(upload.files()).hasSize(1);
        ObjectHandle object = upload.files().getFirst().object();

        assertThat(object.size()).isEqualTo(SIZE);
        assertThat(object.digest()).isEqualTo(HexFormat.of().formatHex(bodyDigest));
        assertThat(sha256Hex(hostStorage, object)).as("the serving node's own instance reads the ingress's bytes")
                                                  .isEqualTo(HexFormat.of().formatHex(bodyDigest));
    }

    private Disposition classifyAfterRepublish(TestRequest head) {
        ingress.rebuildRouter();

        return ((AppHttpServerAdapter) ingress).classify(head);
    }

    /// Streams SIZE deterministic bytes in 64 KiB chunks through one reused buffer, never holding the body; its SHA-256.
    private static byte[] streamDeterministicBody(BodySink sink) {
        var digest = sha256();
        var random = new Random(64);
        var chunk = new byte[CHUNK];

        for (var sent = 0L; sent < SIZE; sent += CHUNK) {
            var size = (int) Math.min(CHUNK, SIZE - sent);

            random.nextBytes(chunk);
            digest.update(chunk, 0, size);
            assertThat(sink.accept(ByteBuffer.wrap(chunk, 0, size)).await(TIMEOUT).isSuccess()).isTrue();
        }

        return digest.digest();
    }

    private static RouteEntry remoteUploadRoute() {
        return RouteEntry.activeRoute("POST", PATH, "upload", "PUBLIC", "PUBLIC", 0, List.of(), List.of(), true, 256L * MIB, 0);
    }

    private AppHttpServer ingressServer() {
        var config = AppHttpConfig.insecureAppHttpConfig(0).withUpload(STREAMING);
        var server = AppHttpServer.appHttpServer(config,
                                                 ForwardingTimeouts.forwardingTimeouts(),
                                                 INGRESS,
                                                 ingressRegistry,
                                                 Option.<HttpRoutePublisher> none(),
                                                 Option.<ClusterNetwork> some(ingressNetwork),
                                                 Option.<Serializer> some(CODEC),
                                                 Option.<Deserializer> some(CODEC),
                                                 Option.none(),
                                                 Option.none(),
                                                 Option.none(),
                                                 Option.none(),
                                                 Option.<DeploymentManager> none());

        server.setUploadStorage(ingressStorage);

        return server;
    }

    private AppHttpServer hostServer() {
        return AppHttpServer.appHttpServer(AppHttpConfig.insecureAppHttpConfig(0).withUpload(STREAMING),
                                           ForwardingTimeouts.forwardingTimeouts(),
                                           HOST,
                                           HttpRouteRegistry.httpRouteRegistry(),
                                           Option.<HttpRoutePublisher> some(new IngestGateRoutes(hostRouter, SecurityPolicy.publicRoute())),
                                           Option.<ClusterNetwork> some(hostNetwork),
                                           Option.<Serializer> some(CODEC),
                                           Option.<Deserializer> some(CODEC),
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           Option.<DeploymentManager> none());
    }

    private static StorageInstance contentInstance(DHTClient dht) {
        return StorageInstance.storageInstance("content", List.of(DhtStorageTier.dhtStorageTier(dht, "content-blocks")));
    }

    /// Records what a node sends; the first forwarded request is kept for the test to deliver.
    private static final class RecordingClusterNetwork implements ClusterNetwork {
        private final NodeId peer;
        private final Promise<HttpForwardRequest> forwarded = Promise.promise();

        RecordingClusterNetwork(NodeId peer) {
            this.peer = peer;
        }

        HttpForwardRequest forwarded() {
            var sent = forwarded.await(TIMEOUT);

            assertThat(sent.isSuccess()).as("the ingress forwarded the request").isTrue();

            return sent.unwrap();
        }

        @Override public <M extends ProtocolMessage> Unit broadcast(M message) {return unit();}

        @Override public void connect(NetworkServiceMessage.ConnectNode connectNode) {}
        @Override public void disconnect(NetworkServiceMessage.DisconnectNode disconnectNode) {}
        @Override public void listNodes(NetworkServiceMessage.ListConnectedNodes listConnectedNodes) {}
        @Override public void handleSend(NetworkServiceMessage.Send send) {}
        @Override public void handleBroadcast(NetworkServiceMessage.Broadcast broadcast) {}

        @Override
        public <M extends ProtocolMessage> Promise<WriteOutcome> send(NodeId nodeId, M message) {
            if (message instanceof HttpForwardRequest request) {
                forwarded.succeed(request);
            }

            return Promise.success(new WriteOutcome.Sent(nodeId));
        }

        @Override
        public <M extends ProtocolMessage> Promise<WriteOutcome> send(NodeId nodeId, M message, TimeSpan offlineTtl) {
            return send(nodeId, message);
        }

        @Override public Promise<Unit> start() {return Promise.unitPromise();}
        @Override public Promise<Unit> stop() {return Promise.unitPromise();}
        @Override public int connectedNodeCount() {return 1;}
        @Override public Set<NodeId> connectedPeers() {return Set.of(peer);}
        @Override public Option<Server> server() {return Option.none();}
    }

    /// The in-memory DHT of `StorageFactoryPrefetchWiringTest`, shared by both nodes' `content-blocks` tiers.
    private static final class InMemoryDHTClient implements DHTClient {
        private final ConcurrentHashMap<String, byte[]> store = new ConcurrentHashMap<>();

        @Override
        public Promise<Option<byte[]>> get(byte[] key) {
            return Promise.success(option(store.get(keyString(key))).map(v -> copyOf(v, v.length)));
        }

        @Override
        public Promise<Unit> put(byte[] key, byte[] value) {
            store.put(keyString(key), copyOf(value, value.length));

            return Promise.success(unit());
        }

        @Override
        public Promise<Boolean> remove(byte[] key) {
            return Promise.success(store.remove(keyString(key)) != null);
        }

        @Override
        public Promise<Boolean> exists(byte[] key) {
            return Promise.success(store.containsKey(keyString(key)));
        }

        @Override
        public Partition partitionFor(byte[] key) {
            return null;
        }

        private static String keyString(byte[] key) {
            return new String(key, StandardCharsets.UTF_8);
        }
    }
}
