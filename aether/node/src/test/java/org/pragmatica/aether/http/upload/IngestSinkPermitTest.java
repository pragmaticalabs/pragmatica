// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.upload;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.UploadHandle;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.routing.UploadSpec;
import org.pragmatica.http.server.BodySink;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.storage.ContentStore;
import org.pragmatica.storage.ContentStoreConfig;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.ObjectId;
import org.pragmatica.storage.ObjectInfo;
import org.pragmatica.storage.ObjectSpec;
import org.pragmatica.storage.StorageInstance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// The sink's terminal paths, driven directly: the admission permit runs exactly once on each (after the hand-off on
/// `complete`, after the clean-up settled on `fail` and `abort`), a refusal leaves nothing in the catalog, and the
/// handed-off request's `discard` deletes what was stored.
class IngestSinkPermitTest {
    private static final TimeSpan TIMEOUT = TimeSpan.timeSpan(30).seconds();
    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final Duration HOLD = Duration.ofMillis(300);
    private static final String TYPE = "application/x-permit-test";
    private static final String BOUNDARY = "permit-boundary";
    private static final byte[] BODY = "the whole body of a streamed upload".getBytes(StandardCharsets.UTF_8);

    private final StorageInstance storage = StorageInstance.storageInstance("content", List.of(MemoryTier.memoryTier(64L * 1024 * 1024)));
    private final ContentStore store = storeOver(storage);
    private final AtomicInteger permits = new AtomicInteger();
    private final AtomicInteger dispatchesSeenByPermit = new AtomicInteger(-1);
    private final List<HttpRequest> dispatched = new CopyOnWriteArrayList<>();
    private final List<UploadIngestError> refusals = new CopyOnWriteArrayList<>();
    private final CapturingResponse response = CapturingResponse.capturingResponse();

    /// Kills: the release removed from the hand-off (stays 0), the release run before the hand-off (sees no dispatch),
    /// and the guard removed (a stray second terminal call releases again).
    @Test
    void complete_runsThePermitOnce_afterTheHandOff() {
        var sink = binarySink(store);

        assertThat(accept(sink, BODY).isSuccess()).isTrue();
        sink.complete(TestRequest.post("/api/upload/x", TYPE, BODY.length), response);

        await().atMost(WAIT).until(() -> permits.get() == 1);
        assertThat(dispatchesSeenByPermit.get()).as("the permit is returned after the hand-off").isEqualTo(1);
        assertThat(refusals).isEmpty();
        assertThat(IngestedRequest.uploadOf(dispatched.getFirst()).map(handle -> handle.files().size())).isEqualTo(Option.some(1));

        sink.abort(BodySink.Ended.DISCONNECTED);
        await().during(HOLD).atMost(WAIT).until(() -> permits.get() == 1);
    }

    /// Kills: the release removed from the refusal path, and the guard removed.
    @Test
    void fail_runsThePermitOnce_andAnswersTheRefusal() {
        var sink = binarySink(store);
        var limit = UploadIngestError.LimitExceeded.limitExceeded("max_file_size", 1);

        assertThat(accept(sink, BODY).isSuccess()).isTrue();
        sink.fail(limit, response);

        await().atMost(WAIT).until(() -> permits.get() == 1);
        assertThat(refusals).containsExactly(limit);
        assertThat(dispatched).isEmpty();
        assertThat(stored()).as("the aborted writer left nothing in the catalog").isEmpty();

        sink.abort(BodySink.Ended.DISCONNECTED);
        await().during(HOLD).atMost(WAIT).until(() -> permits.get() == 1);
    }

    /// Kills: the release removed from the abort path, and the guard removed.
    @Test
    void abort_runsThePermitOnce_andAnswersNothing() {
        var sink = binarySink(store);

        assertThat(accept(sink, BODY).isSuccess()).isTrue();
        sink.abort(BodySink.Ended.TIMED_OUT);

        await().atMost(WAIT).until(() -> permits.get() == 1);
        assertThat(refusals).isEmpty();
        assertThat(dispatched).isEmpty();
        assertThat(response.answered()).as("after an abort the server answers, not the sink").isFalse();

        sink.abort(BodySink.Ended.DISCONNECTED);
        await().during(HOLD).atMost(WAIT).until(() -> permits.get() == 1);
    }

    /// Kills "EOF treated as success" at the sink: a multipart body without its closing delimiter is refused 400 on
    /// `complete`, never handed off, and the file it had committed is deleted.
    @Test
    void complete_ofUnclosedMultipart_refusesAndDeletesTheCommittedFile() {
        var body = MultipartBody.multipartBody(BOUNDARY).file("doc", "a.txt", "text/plain", BODY);
        var bytes = body.raw("--" + BOUNDARY).bytes();
        var decoder = MultipartDecoder.multipartDecoder(body.contentType(), UploadSpec.uploadSpec(true, 1024 * 1024, 0)).unwrap();
        var sink = IngestSink.ingestSink(store, new IngestSink.Body.Multipart(decoder, Option.none()), this::onPermit, this::dispatch, this::refuse);

        assertThat(accept(sink, bytes).isSuccess()).isTrue();
        assertThat(stored()).as("control: the file part was committed before the body ended").hasSize(1);
        sink.complete(TestRequest.post("/api/upload/x", body.contentType(), bytes.length), response);

        await().atMost(WAIT).until(() -> permits.get() == 1);
        assertThat(refusals).containsExactly(UploadIngestError.Malformed.NOT_CLOSED);
        assertThat(UploadIngestError.Malformed.NOT_CLOSED.status().code()).isEqualTo(400);
        assertThat(dispatched).isEmpty();
        await().atMost(WAIT).until(() -> stored().isEmpty());
    }

    /// Kills a `discard` that does not reach the committed objects (the dispatch path's clean-up for a request no slice
    /// receives); running it twice is harmless.
    @Test
    void discardUpload_deletesTheStoredObject_andIsSafeToRunTwice() {
        var sink = binarySink(store);

        assertThat(accept(sink, BODY).isSuccess()).isTrue();
        sink.complete(TestRequest.post("/api/upload/x", TYPE, BODY.length), response);
        await().atMost(WAIT).until(() -> dispatched.size() == 1);

        var request = dispatched.getFirst();
        var id = idOf(IngestedRequest.uploadOf(request).unwrap());

        assertThat(store.stat(id).await(TIMEOUT).unwrap().isPresent()).as("control: stored before the discard").isTrue();
        IngestedRequest.discardUpload(request);
        IngestedRequest.discardUpload(request);
        await().atMost(WAIT).until(() -> store.stat(id).await(TIMEOUT).unwrap().isEmpty());
        assertThat(stored()).isEmpty();
    }

    /// A request the ingress did not store carries no upload, and discarding it does nothing.
    @Test
    void uploadOf_plainRequest_isEmpty_andDiscardIsANoOp() {
        var plain = TestRequest.post("/api/upload/x", TYPE, 0);

        assertThat(IngestedRequest.uploadOf(plain).isEmpty()).isTrue();
        IngestedRequest.discardUpload(plain);
        assertThat(stored()).isEmpty();
    }

    /// Kills a storage failure handed off as a success: a full tier is refused with a storage status, nothing is
    /// dispatched, and the permit returns. [Guess: the instance reports the tier's `TierFull`, possibly wrapped, so 507;
    /// 503 is accepted in case the instance reports it otherwise.]
    @Test
    void complete_whenTheTierIsFull_isRefusedWithAStorageStatus() {
        var tiny = StorageInstance.storageInstance("content", List.of(MemoryTier.memoryTier(1024)));
        var sink = binarySink(storeOver(tiny));
        var large = new byte[8 * 1024];

        Arrays.fill(large, (byte) 7);
        accept(sink, large).onSuccess(_ -> sink.complete(TestRequest.post("/api/upload/x", TYPE, large.length), response))
                           .onFailure(cause -> sink.fail(cause, response));

        await().atMost(WAIT).until(() -> permits.get() == 1);
        assertThat(dispatched).isEmpty();
        assertThat(refusals).hasSize(1);
        assertThat(refusals.getFirst()).isInstanceOf(UploadIngestError.StorageFailed.class);
        assertThat(refusals.getFirst().status().code()).isIn(503, 507);
    }

    /// Kills a mapping that lets a storage cause through as itself, or rewraps one of the ingest's own refusals.
    @Test
    void ingestError_mapsForeignCausesToStorageUnavailable_andKeepsItsOwn() {
        var foreign = UploadIngestError.ingestError(Causes.cause("disk on fire"));

        assertThat(foreign.status().code()).isEqualTo(503);
        assertThat(foreign.message()).isEqualTo("storage-unavailable");
        assertThat(UploadIngestError.ingestError(UploadIngestError.Malformed.BAD_HEADERS)).isSameAs(UploadIngestError.Malformed.BAD_HEADERS);
    }

    private IngestSink binarySink(ContentStore target) {
        return IngestSink.ingestSink(target,
                                     new IngestSink.Body.Binary(ObjectSpec.objectSpec(TYPE)),
                                     this::onPermit,
                                     this::dispatch,
                                     this::refuse);
    }

    private void onPermit() {
        permits.incrementAndGet();
        dispatchesSeenByPermit.set(dispatched.size());
    }

    private void dispatch(HttpRequest request, ResponseWriter writer) {
        dispatched.add(request);
    }

    private void refuse(ResponseWriter writer, UploadIngestError cause) {
        refusals.add(cause);
    }

    private static Result<Unit> accept(BodySink sink, byte[] bytes) {
        return sink.accept(ByteBuffer.wrap(bytes.clone())).await(TIMEOUT);
    }

    private List<ObjectInfo> stored() {
        return store.list(Option.none(), Option.none(), 100).await(TIMEOUT).unwrap().items();
    }

    private static ObjectId idOf(UploadHandle handle) {
        return UploadObjects.objectRef(handle.files().getFirst().object()).unwrap().id();
    }

    private static ContentStore storeOver(StorageInstance instance) {
        return ContentStore.contentStore(instance, ContentStoreConfig.contentStoreConfig()).unwrap();
    }
}
