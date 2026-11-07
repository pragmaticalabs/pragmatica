// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.upload;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.UploadHandle;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.QueryParams;
import org.pragmatica.http.routing.UploadSpec;
import org.pragmatica.http.server.BodySink;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.storage.ByteRange;
import org.pragmatica.storage.ContentStore;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.ObjectRef;
import org.pragmatica.storage.ObjectSpec;
import org.pragmatica.storage.StorageInstance;

import static org.assertj.core.api.Assertions.assertThat;

/// The ingest sink (U2, spec §B2) over a real [ContentStore]: a stored body reaches dispatch by reference with an empty
/// body, its bytes readable back and its digest the sender's; every failure path aborts the open writer, deletes what was
/// committed (no catalog entry) and returns the permit exactly once.
class IngestSinkTest {
    private static final int MIB = 1024 * 1024;
    private static final String BOUNDARY = "xYz";
    private static final TimeSpan WAIT = TimeSpan.timeSpan(30).seconds();

    private final AtomicInteger permits = new AtomicInteger();
    private final AtomicReference<HttpRequest> dispatched = new AtomicReference<>();
    private final AtomicReference<UploadIngestError> refused = new AtomicReference<>();
    private final List<HttpStatus> written = new ArrayList<>();

    private static StorageInstance storage(long bytes) {
        return StorageInstance.storageInstance("ingest-sink-test", List.of(MemoryTier.memoryTier(bytes)));
    }

    private static ContentStore store(StorageInstance storage) {
        return UploadIngest.uploadIngest(storage, TimeSpan.timeSpan(1).hours())
                           .store(org.pragmatica.storage.Namespace.namespace("com.example.svc").unwrap())
                           .unwrap();
    }

    private IngestSink sink(ContentStore store, IngestSink.Body body) {
        return IngestSink.ingestSink(store,
                                     body,
                                     permits::incrementAndGet,
                                     (request, _) -> dispatched.set(request),
                                     (_, cause) -> refused.set(cause));
    }

    private static IngestSink.Body binary() {
        return new IngestSink.Body.Binary(ObjectSpec.objectSpec("application/octet-stream"));
    }

    private static IngestSink.Body multipart(UploadSpec spec) {
        return new IngestSink.Body.Multipart(MultipartDecoder.multipartDecoder("multipart/form-data; boundary=" + BOUNDARY,
                                                                               spec)
                                                             .unwrap(),
                                             Option.none());
    }

    private static byte[] random(int size, long seed) {
        var bytes = new byte[size];

        new Random(seed).nextBytes(bytes);

        return bytes;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /// Pushes `body` in `chunk`-sized slices, each after the previous `accept` resolved, as the server does; answers the
    /// first refusal, if any.
    private static Optional<org.pragmatica.lang.Cause> push(BodySink sink, byte[] body, int chunk) {
        for (var offset = 0; offset < body.length; offset += chunk) {
            var slice = ByteBuffer.wrap(body, offset, Math.min(chunk, body.length - offset)).slice();
            var outcome = sink.accept(slice).await(WAIT);

            if (outcome.isFailure()) {
                return outcome.fold(Option::some, _ -> Option.<org.pragmatica.lang.Cause>none()).toOptional();
            }
        }

        return Optional.empty();
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();

        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime() < deadline).as("timed out waiting until " + what).isTrue();
            Thread.sleep(10);
        }
    }

    private static List<ObjectRef> catalog(ContentStore store) {
        return store.list(Option.none(), Option.none(), 1000)
                    .await(WAIT)
                    .unwrap()
                    .items()
                    .stream()
                    .map(info -> info.ref())
                    .toList();
    }

    private static byte[] read(ContentStore store, ObjectRef ref) {
        var stream = store.open(ref, ByteRange.whole()).await(WAIT).unwrap();
        var out = new ByteArrayOutputStream();

        for (var next = stream.next().await(WAIT).unwrap(); next.isPresent(); next = stream.next().await(WAIT).unwrap()) {
            next.onPresent(buffer -> out.writeBytes(copy(buffer)));
        }
        stream.close().await(WAIT);

        return out.toByteArray();
    }

    private static byte[] copy(ByteBuffer buffer) {
        var bytes = new byte[buffer.remaining()];

        buffer.duplicate().get(bytes);

        return bytes;
    }

    private static UploadHandle handleOf(HttpRequest request) {
        return IngestedRequest.uploadOf(request).unwrap();
    }

    private static ObjectRef refOf(UploadHandle handle, int file) {
        return UploadObjects.objectRef(handle.files().get(file).object()).unwrap();
    }

    @Test
    void complete_binaryBody_reachesDispatchByReference_withAnEmptyBody_andItsBytesReadBack() throws Exception {
        var storage = storage(64L * MIB);
        var store = store(storage);
        var sink = sink(store, binary());
        var body = random(9 * MIB + 17, 1);

        assertThat(push(sink, body, 3 * MIB)).isEmpty();
        sink.complete(head("application/octet-stream"), writer());
        await(() -> dispatched.get() != null, "the request is dispatched");

        var handle = handleOf(dispatched.get());

        assertThat(dispatched.get().body()).as("the body travels by reference, never inline").isEmpty();
        assertThat(handle.files()).hasSize(1);
        assertThat(handle.files().getFirst().object().size()).isEqualTo(body.length);
        assertThat(handle.files().getFirst().object().digest()).isEqualTo(sha256(body));
        assertThat(read(store, refOf(handle, 0))).isEqualTo(body);
        assertThat(permits.get()).as("the permit is returned once, after the hand-off").isEqualTo(1);
        assertThat(refused.get()).isNull();
    }

    @Test
    void complete_multipartBody_storesOneObjectPerFile_andKeepsTheFields() throws Exception {
        var store = store(storage(64L * MIB));
        var sink = sink(store, multipart(UploadSpec.uploadSpec(true, 0, 0)));
        var first = random(5 * MIB + 3, 2);
        var second = random(1000, 3);

        assertThat(push(sink, multipartBody(first, second), 777_777)).isEmpty();
        sink.complete(head("multipart/form-data; boundary=" + BOUNDARY), writer());
        await(() -> dispatched.get() != null, "the request is dispatched");

        var handle = handleOf(dispatched.get());

        assertThat(handle.fields()).containsEntry("comment", List.of("hi"));
        assertThat(handle.files()).extracting(file -> file.field()).containsExactly("a", "b");
        assertThat(handle.files().getFirst().fileName().toOptional()).contains("a.bin");
        assertThat(read(store, refOf(handle, 0))).isEqualTo(first);
        assertThat(read(store, refOf(handle, 1))).isEqualTo(second);
        assertThat(catalog(store)).hasSize(2);
        assertThat(permits.get()).isEqualTo(1);
    }

    /// Spec §4 U2: malformed multipart answers 400, the open writer is aborted and nothing stays in the catalog, including
    /// the file committed before the malformed part.
    @Test
    void fail_malformedMultipartAfterACommittedFile_answers400_andLeavesNoCatalogEntry() throws Exception {
        var store = store(storage(64L * MIB));
        var sink = sink(store, multipart(UploadSpec.uploadSpec(true, 0, 0)));
        var valid = multipartBody(random(2 * MIB, 4), random(MIB, 5));
        var cut = new String(valid, StandardCharsets.ISO_8859_1).lastIndexOf("\r\n--" + BOUNDARY + "--");
        var body = new ByteArrayOutputStream();

        body.write(valid, 0, cut);
        body.writeBytes(("\r\n--" + BOUNDARY + "!!garbage").getBytes(StandardCharsets.ISO_8859_1));

        var refusal = push(sink, body.toByteArray(), 512 * 1024);

        assertThat(refusal).contains(UploadIngestError.Malformed.BAD_DELIMITER);
        sink.fail(refusal.get(), writer());
        await(() -> permits.get() == 1, "the clean-up settled and the permit was returned");

        assertThat(refused.get()).isEqualTo(UploadIngestError.Malformed.BAD_DELIMITER);
        assertThat(refused.get().status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(catalog(store)).as("no catalog entry is left for a refused upload").isEmpty();
        assertThat(dispatched.get()).isNull();
    }

    @Test
    void complete_bodyEndingBeforeItsClosingDelimiter_answers400NotClosed_andLeavesNoCatalogEntry() throws Exception {
        var store = store(storage(64L * MIB));
        var sink = sink(store, multipart(UploadSpec.uploadSpec(true, 0, 0)));
        var valid = multipartBody(random(MIB, 6), random(100, 7));

        assertThat(push(sink, java.util.Arrays.copyOf(valid, valid.length - 10), 300_000)).isEmpty();
        sink.complete(head("multipart/form-data; boundary=" + BOUNDARY), writer());
        await(() -> permits.get() == 1, "the clean-up settled and the permit was returned");

        assertThat(refused.get()).isEqualTo(UploadIngestError.Malformed.NOT_CLOSED);
        assertThat(catalog(store)).isEmpty();
        assertThat(dispatched.get()).isNull();
    }

    @Test
    void abort_midFile_deletesTheCommittedFiles_abortsTheOpenWriter_andReturnsThePermitOnce() throws Exception {
        var tier = MemoryTier.memoryTier(64L * MIB);
        var store = store(StorageInstance.storageInstance("ingest-sink-test", List.of(tier)));
        var sink = sink(store, multipart(UploadSpec.uploadSpec(true, 0, 0)));
        var valid = multipartBody(random(MIB, 8), random(6 * MIB, 9));

        assertThat(push(sink, java.util.Arrays.copyOf(valid, valid.length - 3 * MIB), 400_000)).isEmpty();
        assertThat(catalog(store)).as("control: the first file was committed before the abort").hasSize(1);

        sink.abort(BodySink.Ended.DISCONNECTED);
        await(() -> permits.get() == 1, "the clean-up settled and the permit was returned");
        Thread.sleep(200);

        assertThat(permits.get()).as("the permit is returned exactly once").isEqualTo(1);
        assertThat(catalog(store)).isEmpty();
        // A deleted object's blocks stay in the tier until cluster GC (A4): what must go is the open writer's partial blocks.
        assertThat(tier.usedBytes()).as("nothing beyond the deleted first file's blocks remains")
                                    .isLessThan(MIB + 4096);
        assertThat(refused.get()).as("after an abort the server answers, not the sink").isNull();
        assertThat(written).isEmpty();
    }

    /// A full content tier answers 507 `storage-full` (not 503), and the partial object leaves no catalog entry.
    /// The permit is returned once however many terminal calls reach the sink: a server abort after a refusal (a
    /// disconnect racing the answer) returns no second permit.
    @Test
    void abort_afterARefusal_returnsNoSecondPermit() throws Exception {
        var store = store(storage(64L * MIB));
        var sink = sink(store, binary());

        assertThat(push(sink, random(MIB, 10), 400_000)).isEmpty();
        sink.fail(BodySink.Ended.TOO_LARGE, writer());
        await(() -> permits.get() == 1, "the refusal returned the permit");

        sink.abort(BodySink.Ended.DISCONNECTED);
        Thread.sleep(200);

        assertThat(permits.get()).as("the permit is returned exactly once").isEqualTo(1);
        assertThat(catalog(store)).isEmpty();
    }

    @Test
    void fullTier_answers507_andLeavesNoCatalogEntry() throws Exception {
        var store = store(storage(2L * MIB));
        var sink = sink(store, binary());
        var refusal = push(sink, random(9 * MIB, 10), MIB);

        if (refusal.isPresent()) {
            sink.fail(refusal.get(), writer());
        } else {
            sink.complete(head("application/octet-stream"), writer());
        }
        await(() -> permits.get() == 1, "the clean-up settled and the permit was returned");

        assertThat(refused.get()).isNotNull();
        assertThat(refused.get().status()).isEqualTo(HttpStatus.INSUFFICIENT_STORAGE);
        assertThat(refused.get().message()).isEqualTo("storage-full");
        assertThat(catalog(store)).isEmpty();
    }

    @Test
    void discardUpload_ofADispatchedRequest_deletesItsObjects() throws Exception {
        var store = store(storage(64L * MIB));
        var sink = sink(store, binary());

        assertThat(push(sink, random(MIB, 11), MIB)).isEmpty();
        sink.complete(head("application/octet-stream"), writer());
        await(() -> dispatched.get() != null, "the request is dispatched");
        assertThat(catalog(store)).hasSize(1);

        IngestedRequest.discardUpload(dispatched.get());
        await(() -> catalog(store).isEmpty(), "the discarded upload's objects are deleted");
    }

    private static byte[] multipartBody(byte[] first, byte[] second) {
        var out = new ByteArrayOutputStream();

        out.writeBytes(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"comment\"\r\n\r\nhi\r\n--" + BOUNDARY
                        + "\r\nContent-Disposition: form-data; name=\"a\"; filename=\"a.bin\"\r\n"
                        + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(first);
        out.writeBytes(("\r\n--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"b\"; filename=\"b.bin\"\r\n"
                        + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(second);
        out.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.ISO_8859_1));

        return out.toByteArray();
    }

    private static HttpRequest head(String contentType) {
        return new HttpRequest() {
            @Override
            public String requestId() {
                return "req-ingest-sink";
            }

            @Override
            public HttpMethod method() {
                return HttpMethod.POST;
            }

            @Override
            public String path() {
                return "/api/upload/";
            }

            @Override
            public Headers headers() {
                return Headers.headers(Map.of("content-type", List.of(contentType)));
            }

            @Override
            public QueryParams queryParams() {
                return QueryParams.empty();
            }

            @Override
            public byte[] body() {
                return new byte[0];
            }
        };
    }

    private ResponseWriter writer() {
        return new ResponseWriter() {
            @Override
            public void write(HttpStatus status, byte[] body, ContentType contentType) {
                synchronized (written) {
                    written.add(status);
                }
            }

            @Override
            public ResponseWriter header(String name, String value) {
                return this;
            }
        };
    }
}
