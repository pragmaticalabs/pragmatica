// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.TimeoutsConfig.ForwardingTimeouts;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.config.UploadConfig;
import org.pragmatica.aether.http.upload.UploadIngest;
import org.pragmatica.aether.storage.SliceNamespace;
import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardRequest;
import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardResponse;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.http.upload.UploadObjects;
import org.pragmatica.aether.http.adapter.RouteDecorator;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue.RouteEntry;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.http.routing.SliceVersionRegistry;
import org.pragmatica.http.routing.UploadSpec;
import org.pragmatica.http.routing.VersioningMetricsSink;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.Server;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.storage.ByteRange;
import org.pragmatica.storage.ContentStore;
import org.pragmatica.storage.ContentStoreConfig;
import org.pragmatica.storage.Compression;
import org.pragmatica.storage.LocalDiskTier;
import org.pragmatica.storage.StorageInstance;

import static org.pragmatica.lang.Unit.unit;

/// U2 forward by reference across two nodes: node A (the ingress, `streaming_ingest` on) takes an upload for a route only
/// node B serves, stores it in content storage, and forwards the request with the upload's handle and an empty body over
/// the production codec; node B's slice opens the object through the handle and hashes it. Run in-process by
/// [AppHttpServerForwardByReferenceTest] and, through [#main], in a child JVM with a heap smaller than the upload.
///
/// The two nodes share one content [StorageInstance]: that stands in for the cluster's content storage, which every node
/// reads (A2). The cluster network is two in-memory links that deliver each node's forward request and response to the
/// other, and record the size of every forwarded request.
final class ForwardByReferenceScenario {
    static final NodeId NODE_A = NodeId.nodeId("u2-ingress-a").unwrap();
    static final NodeId NODE_B = NodeId.nodeId("u2-serving-b").unwrap();
    private static final Artifact ARTIFACT = Artifact.artifact("com.example:svc:1.0.0").unwrap();
    /// Sorts before [#ARTIFACT]: a second slice on node B that serves the same route (see [Fault#TWO_PUBLISHERS]).
    private static final Artifact EARLIER_ARTIFACT = Artifact.artifact("com.example:aaa:1.0.0").unwrap();
    private static final long GIB = 1024L * 1024 * 1024;
    private static final TimeSpan WAIT = TimeSpan.timeSpan(60).seconds();

    private ForwardByReferenceScenario() {}

    /// What one run observed.
    ///
    /// @param status         the status A answered the client
    /// @param sentSha        SHA-256 of the bytes the client sent
    /// @param servedSha      SHA-256 of the bytes B's slice read through the handle (also B's answer body)
    /// @param handleSize     the object size the handle names
    /// @param bodyOnB        the body length B's slice was handed
    /// @param forwardedBytes the largest encoded request A forwarded to B
    /// @param forwards       how many requests A forwarded
    /// @param text           the body of A's answer
    /// @param sliceCalls     how many requests reached B's slice
    /// @param catalogDelta   how many objects A's content storage gained from the request (kept orphans), net of deletions
    /// @param handleNamespace the namespace the handle B's slice received names
    record Outcome(int status, String sentSha, String servedSha, long handleSize, long bodyOnB, long forwardedBytes,
                   long forwards, String text, long sliceCalls, long catalogDelta, String handleNamespace) {}

    /// What happens to the stored object between A's store and B's read.
    enum Fault {
        /// Nothing: B reads the object through the content storage both nodes share.
        NONE,
        /// The object is deleted from the shared content storage after A forwarded the request and before B received it.
        DELETE_MID_FORWARD,
        /// B reads a content storage that does not hold A's object: the node that held it is gone.
        HOLDER_GONE,
        /// The transport refuses A's write to B: the forward never leaves A.
        PEER_UNREACHABLE,
        /// Nothing is lost, but two slices on B publish the route; the ingress stores in the namespace of the first of them
        /// in coordinate order.
        TWO_PUBLISHERS
    }

    public static void main(String[] args) throws Exception {
        var size = Long.parseLong(args[0]);
        var outcome = run(size);

        System.out.println("MAX_HEAP_MB=" + Runtime.getRuntime().maxMemory() / (1024 * 1024));
        System.out.println("STATUS=" + outcome.status());
        System.out.println("SENT_SHA=" + outcome.sentSha());
        System.out.println("SERVED_SHA=" + outcome.servedSha());
        System.out.println("HANDLE_SIZE=" + outcome.handleSize());
        System.out.println("BODY_ON_B=" + outcome.bodyOnB());
        System.out.println("FORWARDED_BYTES=" + outcome.forwardedBytes());
        System.out.println("FORWARDS=" + outcome.forwards());
        System.out.flush();
        System.exit(outcome.status() == 200 ? 0 : 1);
    }

    static Outcome run(long size) throws Exception {
        return run(size, Fault.NONE);
    }

    static Outcome run(long size, Fault fault) throws Exception {
        var dir = Files.createTempDirectory("u2-forward");

        try {
            return run(size, dir, fault);
        } finally {
            try (var paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    private static Outcome run(long size, Path dir, Fault fault) throws Exception {
        var storage = StorageInstance.storageInstance("content", List.of(LocalDiskTier.localDiskTier(dir, 8 * GIB).unwrap()));
        var codec = org.pragmatica.aether.node.NodeCodecs.nodeCodecs(org.pragmatica.serialization.FrameworkCodecs.frameworkCodecs());
        var toB = new Link(NODE_B);
        var toA = new Link(NODE_A);
        var slice = new HashingSlice(storage);
        var upload = UploadConfig.DEFAULT.withStreamingIngest(true).withMaxSizeCeiling(8 * GIB);
        var registryA = HttpRouteRegistry.httpRouteRegistry();
        var a = server(NODE_A, registryA, toB, codec, upload,
                       new IngestGateRoutes(slice, SecurityPolicy.publicRoute()));
        var b = server(NODE_B, HttpRouteRegistry.httpRouteRegistry(), toA, codec, upload,
                       new IngestGateRoutes(slice,
                                            SecurityPolicy.publicRoute(),
                                            Map.of("/remote/", UploadSpec.uploadSpec(true, 4 * GIB, 0))));

        toB.peer.set(b);
        toA.peer.set(a);
        a.setUploadStorage(storage);
        b.setUploadStorage(storage);
        try {
            TlsProbe.requireStarted(a.start().await(), "node A");
            TlsProbe.requireStarted(b.start().await(), "node B");
            a.onQuorumStateChange(ClusterStateNotification.active());
            b.onQuorumStateChange(ClusterStateNotification.active());

            var entry = RouteEntry.activeRoute("POST", "/remote/", "upload", "UNSPECIFIED", "UNSPECIFIED", 0, List.of(), List.of(),
                                               true, 4 * GIB, 0);

            registryA.onNodeRoutesPut(new ValuePut<>(new KVCommand.Put<>(NodeRoutesKey.nodeRoutesKey(NODE_B, ARTIFACT),
                                                                         NodeRoutesValue.nodeRoutesValue(List.of(entry))),
                                                     Option.none()));
            if (fault == Fault.TWO_PUBLISHERS) {
                registryA.onNodeRoutesPut(new ValuePut<>(new KVCommand.Put<>(NodeRoutesKey.nodeRoutesKey(NODE_B, EARLIER_ARTIFACT),
                                                                             NodeRoutesValue.nodeRoutesValue(List.of(entry))),
                                                         Option.none()));
            }

            var port = TlsProbe.requireBoundPort(a.boundPort(), "node A");

            awaitRouteServed(a, port);
            toB.reset();
            slice.calls.set(0);
            injectFault(fault, storage, b, dir, toB);

            var catalogBefore = catalogSize(storage);
            var answer = post(port, size);

            return new Outcome(answer.status(),
                               answer.sentSha(),
                               slice.servedSha.get(),
                               slice.handleSize.get(),
                               slice.bodyLength.get(),
                               toB.maxRequestBytes.get(),
                               toB.requests.get(),
                               answer.text(),
                               slice.calls.get(),
                               catalogSize(storage) - catalogBefore,
                               slice.handleNamespace.get());
        } finally {
            a.stop().await(WAIT);
            b.stop().await(WAIT);
        }
    }

    private static void injectFault(Fault fault, StorageInstance storage, AppHttpServer b, Path dir, Link toB) throws Exception {
        switch (fault) {
            case NONE, TWO_PUBLISHERS -> {}
            case DELETE_MID_FORWARD -> toB.beforeDelivery.set(() -> deleteEverything(storage));
            case PEER_UNREACHABLE -> toB.unreachable.set(true);
            case HOLDER_GONE -> b.setUploadStorage(StorageInstance.storageInstance("content-b",
                                                                                   List.of(LocalDiskTier.localDiskTier(Files.createDirectory(dir.resolve("b")),
                                                                                                                       GIB).unwrap())));
        }
    }

    private static ContentStore sliceStore(StorageInstance storage) {
        var namespace = SliceNamespace.sliceNamespace(ArtifactBase.artifactBase(ARTIFACT)).unwrap();

        return UploadIngest.uploadIngest(storage, TimeSpan.timeSpan(1).hours()).store(namespace).unwrap();
    }

    private static long catalogSize(StorageInstance storage) {
        return sliceStore(storage).list(Option.none(), Option.none(), 1000).await(WAIT).unwrap().items().size();
    }

    private static void deleteEverything(StorageInstance storage) {
        var store = sliceStore(storage);

        store.list(Option.none(), Option.none(), 1000)
             .await(WAIT)
             .unwrap()
             .items()
             .forEach(info -> store.delete(info.ref().id()).await(WAIT));
    }

    private static AppHttpServer server(NodeId self,
                                        HttpRouteRegistry registry,
                                        ClusterNetwork network,
                                        SliceCodec codec,
                                        UploadConfig upload,
                                        IngestGateRoutes routes) {
        return AppHttpServer.appHttpServer(AppHttpConfig.insecureAppHttpConfig(0).withUpload(upload),
                                           ForwardingTimeouts.forwardingTimeouts(),
                                           self,
                                           registry,
                                           Option.some(routes),
                                           Option.some(network),
                                           Option.some(codec),
                                           Option.some(codec),
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           Option.<org.pragmatica.aether.update.DeploymentManager> none());
    }

    /// The route table published when A's listener bound can be read before the remote route arrives; the server says so
    /// (503 "Route table propagating") and a rebuild repairs it. Small uploads are sent until one is served.
    private static void awaitRouteServed(AppHttpServer server, int port) throws Exception {
        var status = 0;

        for (var attempt = 0; attempt < 100 && status != 200; attempt++) {
            server.rebuildRouter();
            status = post(port, 16).status();
            if (status != 200) {
                Thread.sleep(50);
            }
        }
        if (status != 200) {
            throw new IllegalStateException("the remote route was never served (last status " + status + ")");
        }
    }

    record Answer(int status, String sentSha, String text) {}

    /// One HTTP/1.1 POST of `size` seeded random bytes, generated and hashed while they are sent, so the client holds one
    /// buffer, never the body.
    private static Answer post(int port, long size) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        var random = new Random(size);
        var buffer = new byte[256 * 1024];

        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(600_000);
            var out = socket.getOutputStream();

            out.write(("POST /remote/file HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/octet-stream\r\n"
                       + "Content-Length: " + size + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            for (var sent = 0L; sent < size; ) {
                var length = (int) Math.min(buffer.length, size - sent);

                random.nextBytes(buffer);
                digest.update(buffer, 0, length);
                out.write(buffer, 0, length);
                sent += length;
            }
            out.flush();

            var response = readAll(socket.getInputStream());

            return new Answer(Integer.parseInt(response.substring(9, 12)),
                              HexFormat.of().formatHex(digest.digest()),
                              response.substring(response.indexOf("\r\n\r\n") + 4));
        }
    }

    private static String readAll(InputStream in) throws IOException {
        var out = new ByteArrayOutputStream();

        in.transferTo(out);

        return out.toString(StandardCharsets.ISO_8859_1);
    }

    /// The slice on B: opens the upload through its handle and hashes what it reads, chunk by chunk.
    private static final class HashingSlice implements SliceRouter {
        private final StorageInstance storage;
        final AtomicReference<String> servedSha = new AtomicReference<>("<none>");
        final AtomicLong handleSize = new AtomicLong(-1);
        final AtomicReference<String> handleNamespace = new AtomicReference<>("<none>");
        final AtomicLong bodyLength = new AtomicLong(-1);
        final AtomicLong calls = new AtomicLong();

        HashingSlice(StorageInstance storage) {
            this.storage = storage;
        }

        @Override
        public Promise<HttpResponseData> handle(HttpRequestContext request) {
            calls.incrementAndGet();
            bodyLength.set(request.body().length);

            return request.upload()
                          .fold(() -> Promise.success(HttpResponseData.httpResponseData(500, "no upload handle")),
                                upload -> Promise.success(answer(upload.files().getFirst().object())));
        }

        private HttpResponseData answer(org.pragmatica.aether.http.handler.ObjectHandle object) {
            try {
                return hash(object);
            } catch (Exception e) {
                return HttpResponseData.httpResponseData(500, "reading the upload failed: " + e);
            }
        }

        private HttpResponseData hash(org.pragmatica.aether.http.handler.ObjectHandle object) throws Exception {
            var defaults = ContentStoreConfig.contentStoreConfig();
            var store = ContentStore.contentStore(storage,
                                                  ContentStoreConfig.contentStoreConfig(defaults.chunkSizeBytes(),
                                                                                        Compression.NONE,
                                                                                        object.namespace(),
                                                                                        defaults.writeWindow(),
                                                                                        defaults.readAhead()))
                                    .unwrap();
            var stream = store.open(UploadObjects.objectRef(object).unwrap(), ByteRange.whole()).await(WAIT).unwrap();
            var digest = MessageDigest.getInstance("SHA-256");

            for (var next = stream.next().await(WAIT).unwrap(); next.isPresent(); next = stream.next().await(WAIT).unwrap()) {
                next.onPresent(digest::update);
            }
            stream.close().await(WAIT);

            var sha = HexFormat.of().formatHex(digest.digest());

            handleSize.set(object.size());
            handleNamespace.set(object.namespace());
            servedSha.set(sha);

            return HttpResponseData.httpResponseData(200, sha);
        }

        @Override
        public SliceVersionRegistry versionRegistry() {
            return SliceVersionRegistry.UNVERSIONED;
        }

        @Override
        public SliceRouter withObservability(String sliceName, VersioningMetricsSink sink) {
            return this;
        }

        @Override
        public SliceRouter withInvocationCells(RouteDecorator decorator) {
            return this;
        }
    }

    /// One direction of the cluster network: delivers forward requests and responses to `peer`, as the transport would,
    /// on another thread; records the size of every forwarded request.
    private static final class Link implements ClusterNetwork {
        private final NodeId target;
        final AtomicReference<AppHttpServer> peer = new AtomicReference<>();
        final AtomicLong maxRequestBytes = new AtomicLong();
        final AtomicLong requests = new AtomicLong();
        final java.util.concurrent.atomic.AtomicBoolean unreachable = new java.util.concurrent.atomic.AtomicBoolean();
        final AtomicReference<Runnable> beforeDelivery = new AtomicReference<>(() -> {});

        Link(NodeId target) {
            this.target = target;
        }

        void reset() {
            maxRequestBytes.set(0);
            requests.set(0);
        }

        @Override
        public <M extends ProtocolMessage> Promise<WriteOutcome> send(NodeId nodeId, M message) {
            switch (message) {
                case HttpForwardRequest request when unreachable.get() -> {
                    requests.incrementAndGet();

                    return Promise.success(new WriteOutcome.NoPeerState(nodeId));
                }
                case HttpForwardRequest request -> {
                    requests.incrementAndGet();
                    maxRequestBytes.accumulateAndGet(request.requestData().length, Math::max);
                    beforeDelivery.get().run();
                    Thread.ofVirtual().start(() -> peer.get().onHttpForwardRequest(request));
                }
                case HttpForwardResponse response -> Thread.ofVirtual().start(() -> peer.get().onHttpForwardResponse(response));
                default -> {}
            }

            return Promise.success(new WriteOutcome.Sent(nodeId));
        }

        @Override
        public <M extends ProtocolMessage> Promise<WriteOutcome> send(NodeId nodeId, M message, TimeSpan offlineTtl) {
            return send(nodeId, message);
        }

        @Override
        public <M extends ProtocolMessage> Unit broadcast(M message) {
            return unit();
        }

        @Override
        public void connect(NetworkServiceMessage.ConnectNode connectNode) {}

        @Override
        public void disconnect(NetworkServiceMessage.DisconnectNode disconnectNode) {}

        @Override
        public void listNodes(NetworkServiceMessage.ListConnectedNodes listConnectedNodes) {}

        @Override
        public void handleSend(NetworkServiceMessage.Send send) {}

        @Override
        public void handleBroadcast(NetworkServiceMessage.Broadcast broadcast) {}

        @Override
        public Promise<Unit> start() {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.unitPromise();
        }

        @Override
        public int connectedNodeCount() {
            return 1;
        }

        @Override
        public Set<NodeId> connectedPeers() {
            return Set.of(target);
        }

        @Override
        public Option<Server> server() {
            return Option.none();
        }
    }
}
