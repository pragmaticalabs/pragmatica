// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.TimeoutsConfig.ForwardingTimeouts;
import org.pragmatica.aether.config.UploadConfig;
import org.pragmatica.aether.http.adapter.RouteDecorator;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.aether.http.handler.ObjectHandle;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.http.upload.CapturingResponse;
import org.pragmatica.aether.http.upload.UploadObjects;
import org.pragmatica.aether.storage.SliceNamespace;
import org.pragmatica.aether.update.DeploymentManager;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.routing.SliceVersionRegistry;
import org.pragmatica.http.routing.VersioningMetricsSink;
import org.pragmatica.http.server.BodySink;
import org.pragmatica.http.server.Disposition;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.storage.ByteRange;
import org.pragmatica.storage.Compression;
import org.pragmatica.storage.ContentStore;
import org.pragmatica.storage.ContentStoreConfig;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.ObjectInfo;
import org.pragmatica.storage.StorageInstance;

import static org.assertj.core.api.Assertions.assertThat;

/// One unstarted app server whose upload routes are [IngestGateRoutes]' (all public here), with `[app-http.upload]` as a
/// test sets it and a real `content` storage instance bound, so a test can call `classify` on a request head, feed the
/// streamed sink itself, and read what reached storage. Nothing here replaces the code under test: the plan, the sink, the
/// dispatch and the conversion to the slice's request are the server's own.
final class StreamingIngestHarness {
    static final TimeSpan TIMEOUT = TimeSpan.timeSpan(30).seconds();
    static final Duration WAIT = Duration.ofSeconds(30);
    /// The artifact [IngestGateRoutes] serves every route from; its namespace is where the uploads go.
    static final String ARTIFACT = "com.example:svc:1.0.0";
    static final int MIB = 1024 * 1024;
    static final int MAX_CHUNK = 256 * 1024;

    final StorageInstance storage;
    final CapturingRouter router = new CapturingRouter();
    final AppHttpServerAdapter server;

    private StreamingIngestHarness(UploadConfig upload, StorageInstance storage, boolean bindStorage) {
        this.storage = storage;
        this.server = (AppHttpServerAdapter) AppHttpServer.appHttpServer(AppHttpConfig.insecureAppHttpConfig(0).withUpload(upload),
                                                                         ForwardingTimeouts.forwardingTimeouts(),
                                                                         NodeId.nodeId("ingest-node").unwrap(),
                                                                         HttpRouteRegistry.httpRouteRegistry(),
                                                                         Option.some(new IngestGateRoutes(router, SecurityPolicy.publicRoute())),
                                                                         Option.none(),
                                                                         Option.none(),
                                                                         Option.none(),
                                                                         Option.none(),
                                                                         Option.none(),
                                                                         Option.none(),
                                                                         Option.none(),
                                                                         Option.<DeploymentManager> none());
        if (bindStorage) {
            server.setUploadStorage(storage);
        }
    }

    /// `streaming_ingest = true`, everything else default, a 512 MiB in-memory `content` instance bound.
    static StreamingIngestHarness streaming() {
        return streaming(UploadConfig.DEFAULT.withStreamingIngest(true));
    }

    static StreamingIngestHarness streaming(UploadConfig upload) {
        return harness(upload, memoryContent(), true);
    }

    static StreamingIngestHarness harness(UploadConfig upload, StorageInstance storage, boolean bindStorage) {
        return new StreamingIngestHarness(upload, storage, bindStorage);
    }

    static StorageInstance memoryContent() {
        return StorageInstance.storageInstance("content", List.of(MemoryTier.memoryTier(512L * MIB)));
    }

    Disposition classify(HttpRequest head) {
        return server.classify(head);
    }

    /// The sink `head` is streamed into; the head must be classified `Stream`.
    BodySink stream(HttpRequest head) {
        var disposition = classify(head);

        assertThat(disposition).as("an upload route with streaming ingest on and storage bound streams").isInstanceOf(Disposition.Stream.class);

        return ((Disposition.Stream) disposition).sink();
    }

    /// What a `Reject` disposition writes.
    static CapturingResponse rejection(Disposition disposition) {
        assertThat(disposition).isInstanceOf(Disposition.Reject.class);
        var response = CapturingResponse.capturingResponse();

        ((Disposition.Reject) disposition).respond().accept(response);

        return response;
    }

    static boolean isStream(Disposition disposition) {
        return disposition instanceof Disposition.Stream;
    }

    /// The namespace [IngestGateRoutes]' artifact stores into.
    static String namespace() {
        return Artifact.artifact(ARTIFACT)
                       .map(ArtifactBase::artifactBase)
                       .flatMap(SliceNamespace::sliceNamespace)
                       .unwrap()
                       .value();
    }

    /// The catalog entries of the slice's namespace.
    List<ObjectInfo> stored() {
        return storeOf(storage, namespace()).list(Option.none(), Option.none(), 1000).await(TIMEOUT).unwrap().items();
    }

    Option<ObjectInfo> stat(ObjectHandle handle) {
        return storeOf(storage, handle.namespace()).stat(UploadObjects.objectRef(handle).unwrap().id()).await(TIMEOUT).unwrap();
    }

    /// A store over `instance` with the node's settings (default chunk size, window and read-ahead, no compression).
    static ContentStore storeOf(StorageInstance instance, String namespace) {
        var defaults = ContentStoreConfig.contentStoreConfig();

        return ContentStore.contentStore(instance,
                                         ContentStoreConfig.contentStoreConfig(defaults.chunkSizeBytes(),
                                                                               Compression.NONE,
                                                                               namespace,
                                                                               defaults.writeWindow(),
                                                                               defaults.readAhead()))
                           .unwrap();
    }

    /// Feeds `body` whole, in chunks of `sizes`; the first refusal, if any.
    static Option<Cause> feed(BodySink sink, byte[] body, IntSupplier sizes) {
        return feed(sink, body, 0, body.length, sizes);
    }

    /// Feeds `body[from, to)` as the server does: one chunk at a time, each after the previous one resolved. Every chunk
    /// goes through one reused buffer overwritten after its `accept` resolved, so a sink that kept the chunk (or a slice of
    /// it) past that point stores garbage. Stops at the first refusal and answers it.
    static Option<Cause> feed(BodySink sink, byte[] body, int from, int to, IntSupplier sizes) {
        var scratch = new byte[MAX_CHUNK];
        var position = from;

        while (position < to) {
            var size = Math.min(Math.min(sizes.getAsInt(), MAX_CHUNK), to - position);

            System.arraycopy(body, position, scratch, 0, size);
            var accepted = sink.accept(ByteBuffer.wrap(scratch, 0, size)).await(TIMEOUT);

            Arrays.fill(scratch, 0, size, (byte) '#');
            if (accepted.isFailure()) {
                return accepted.fold(Option::<Cause>some, _ -> Option.<Cause>none());
            }
            position += size;
        }

        return Option.none();
    }

    static IntSupplier oneByte() {
        return () -> 1;
    }

    static IntSupplier randomSizes(long seed) {
        var random = new Random(seed);

        return () -> 1 + random.nextInt(MAX_CHUNK);
    }

    static byte[] randomBytes(int size, long seed) {
        var bytes = new byte[size];

        new Random(seed).nextBytes(bytes);

        return bytes;
    }

    /// Every byte of the object `handle` names, read from `instance` through a chunk stream.
    static byte[] read(StorageInstance instance, ObjectHandle handle) {
        var out = new ByteArrayOutputStream();

        consume(instance, handle, buffer -> out.writeBytes(bytesOf(buffer)));

        return out.toByteArray();
    }

    /// The SHA-256 of the object `handle` names, read from `instance` without holding it.
    static String sha256Hex(StorageInstance instance, ObjectHandle handle) {
        var digest = sha256();

        consume(instance, handle, digest::update);

        return HexFormat.of().formatHex(digest.digest());
    }

    static String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }

    static MessageDigest sha256() {
        return Result.lift(() -> MessageDigest.getInstance("SHA-256")).unwrap();
    }

    private static void consume(StorageInstance instance, ObjectHandle handle, Consumer<ByteBuffer> consumer) {
        var stream = storeOf(instance, handle.namespace()).open(UploadObjects.objectRef(handle).unwrap(), ByteRange.whole())
                                                          .await(TIMEOUT)
                                                          .unwrap();

        for (var next = stream.next().await(TIMEOUT).unwrap(); next.isPresent(); next = stream.next().await(TIMEOUT).unwrap()) {
            next.onPresent(consumer::accept);
        }
        stream.close().await(TIMEOUT);
    }

    private static byte[] bytesOf(ByteBuffer buffer) {
        var bytes = new byte[buffer.remaining()];

        buffer.duplicate().get(bytes);

        return bytes;
    }

    /// The slice: keeps the first request it was handed and counts every call; answers 200.
    static final class CapturingRouter implements SliceRouter {
        private final Promise<HttpRequestContext> seen = Promise.promise();
        private final AtomicInteger calls = new AtomicInteger();

        HttpRequestContext seen() {
            var request = seen.await(TIMEOUT);

            assertThat(request.isSuccess()).as("the request reached the slice").isTrue();

            return request.unwrap();
        }

        int calls() {
            return calls.get();
        }

        @Override
        public Promise<HttpResponseData> handle(HttpRequestContext request) {
            calls.incrementAndGet();
            seen.succeed(request);

            return Promise.success(HttpResponseData.httpResponseData(200, "served"));
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
}
