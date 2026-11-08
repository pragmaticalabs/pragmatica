// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.upload;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
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
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.storage.ByteRange;
import org.pragmatica.storage.ContentStore;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.ObjectId;
import org.pragmatica.storage.ObjectRef;
import org.pragmatica.storage.ObjectSpec;
import org.pragmatica.storage.ObjectWriter;
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
        return sink(store, body, TimeSpan.timeSpan(30).seconds());
    }

    private IngestSink sink(ContentStore store, IngestSink.Body body, TimeSpan deleteBound) {
        return IngestSink.ingestSink(store,
                                     body,
                                     permits::incrementAndGet,
                                     (request, _) -> dispatched.set(request),
                                     (_, cause) -> refused.set(cause),
                                     deleteBound);
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

    // ---- observing the store the sink writes to ----
    /// What a [#spy] records and controls: writer aborts and object deletes seen, a gate every delete waits on, and whether
    /// commits fail.
    private static final class Spy {
        final AtomicInteger aborts = new AtomicInteger();
        final AtomicInteger deletes = new AtomicInteger();
        final AtomicInteger creates = new AtomicInteger();
        final Promise<Unit> deleteGate = Promise.promise();
        /// Every create / commit waits on these; resolved from the start, so a test holds one by replacing it.
        volatile Promise<Unit> createGate = Promise.success(Unit.unit());
        volatile Promise<Unit> commitGate = Promise.success(Unit.unit());
        final boolean failCommit;

        Spy(boolean failCommit) {
            this.failCommit = failCommit;
        }
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @SuppressWarnings("unchecked")
    private static ContentStore spy(ContentStore real, Spy spy) {
        return (ContentStore) Proxy.newProxyInstance(ContentStore.class.getClassLoader(),
                                                     new Class<?>[]{ContentStore.class},
                                                     (_, method, args) -> switch (method.getName()) {
                                                         case "create" -> {
                                                             spy.creates.incrementAndGet();
                                                             yield spy.createGate.flatMap(_ -> {
                                                                 try {
                                                                     return ((Promise<ObjectWriter>) invoke(real, method, args))
                                                                         .map(writer -> spyWriter(writer, spy));
                                                                 } catch (Throwable t) {
                                                                     return Causes.fromThrowable(t).promise();
                                                                 }
                                                             });
                                                         }
                                                         case "delete" -> {
                                                             spy.deletes.incrementAndGet();
                                                             yield spy.deleteGate.flatMap(_ -> real.delete((ObjectId) args[0]));
                                                         }
                                                         default -> invoke(real, method, args);
                                                     });
    }

    private static ObjectWriter spyWriter(ObjectWriter real, Spy spy) {
        return (ObjectWriter) Proxy.newProxyInstance(ObjectWriter.class.getClassLoader(),
                                                     new Class<?>[]{ObjectWriter.class},
                                                     (_, method, args) -> switch (method.getName()) {
                                                         case "abort" -> {
                                                             spy.aborts.incrementAndGet();
                                                             yield invoke(real, method, args);
                                                         }
                                                         case "commit" -> spy.failCommit
                                                                          ? Causes.cause("commit failed").promise()
                                                                          : spy.commitGate.flatMap(_ -> {
                                                                              try {
                                                                                  return (Promise<?>) invoke(real, method, args);
                                                                              } catch (Throwable t) {
                                                                                  return Causes.fromThrowable(t).promise();
                                                                              }
                                                                          });
                                                         default -> invoke(real, method, args);
                                                     });
    }

    /// A multipart body of one committed file and a second, still open one: ends inside the second file's content.
    private static byte[] firstFileCommittedSecondOpen() {
        var valid = multipartBody(random(MIB, 8), random(6 * MIB, 9));

        return java.util.Arrays.copyOf(valid, valid.length - 3 * MIB);
    }

    @Test
    void abort_midFile_callsAbortOnTheOpenWriter_exactlyOnce() throws Exception {
        var spy = new Spy(false);
        var store = spy(store(storage(64L * MIB)), spy);
        var sink = sink(store, multipart(UploadSpec.uploadSpec(true, 0, 0)));

        assertThat(push(sink, firstFileCommittedSecondOpen(), 400_000)).isEmpty();
        spy.deleteGate.succeed(Unit.unit());
        sink.abort(BodySink.Ended.DISCONNECTED);
        await(() -> permits.get() == 1, "the clean-up settled and the permit was returned");

        assertThat(spy.aborts.get()).as("the open (second) file's writer was aborted, the committed first was not").isEqualTo(1);
    }

    @Test
    void fail_returnsThePermitOnlyAfterTheCleanUpSettled() throws Exception {
        var spy = new Spy(false);
        var sink = sink(spy(store(storage(64L * MIB)), spy), multipart(UploadSpec.uploadSpec(true, 0, 0)));

        assertThat(push(sink, firstFileCommittedSecondOpen(), 400_000)).isEmpty();
        sink.fail(BodySink.Ended.TOO_LARGE, writer());
        await(() -> spy.deletes.get() == 1, "the committed file's delete started");
        Thread.sleep(200);

        assertThat(permits.get()).as("the delete has not settled: the permit is still held").isZero();
        spy.deleteGate.succeed(Unit.unit());
        await(() -> permits.get() == 1, "the clean-up settled and the permit was returned");
    }

    @Test
    void abort_returnsThePermitOnlyAfterTheCleanUpSettled() throws Exception {
        var spy = new Spy(false);
        var sink = sink(spy(store(storage(64L * MIB)), spy), multipart(UploadSpec.uploadSpec(true, 0, 0)));

        assertThat(push(sink, firstFileCommittedSecondOpen(), 400_000)).isEmpty();
        sink.abort(BodySink.Ended.DISCONNECTED);
        await(() -> spy.deletes.get() == 1, "the committed file's delete started");
        Thread.sleep(200);

        assertThat(permits.get()).as("the delete has not settled: the permit is still held").isZero();
        spy.deleteGate.succeed(Unit.unit());
        await(() -> permits.get() == 1, "the clean-up settled and the permit was returned");
    }

    @Test
    void failThenAbort_deletesEachCommittedObjectOnce() throws Exception {
        var spy = new Spy(false);
        var sink = sink(spy(store(storage(64L * MIB)), spy), multipart(UploadSpec.uploadSpec(true, 0, 0)));

        assertThat(push(sink, firstFileCommittedSecondOpen(), 400_000)).isEmpty();
        spy.deleteGate.succeed(Unit.unit());
        sink.fail(BodySink.Ended.TOO_LARGE, writer());
        await(() -> permits.get() == 1, "the refusal's clean-up settled");
        sink.abort(BodySink.Ended.DISCONNECTED);
        Thread.sleep(300);

        assertThat(spy.deletes.get()).as("the second clean-up finds nothing left to delete").isEqualTo(1);
    }

    @Test
    void complete_whenTheCommitFails_answers503_andDoesNotAbortTheWriterThatAlreadyFailedCommitting() throws Exception {
        var spy = new Spy(true);
        var sink = sink(spy(store(storage(64L * MIB)), spy), binary());

        assertThat(push(sink, random(MIB, 12), 400_000)).isEmpty();
        sink.complete(head("application/octet-stream"), writer());
        await(() -> permits.get() == 1, "the clean-up settled and the permit was returned");

        assertThat(refused.get().status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(spy.aborts.get()).as("a failed commit gave back what the writer stored; it is no longer open").isZero();
    }

    private static byte[] typedFileBody(String typeA, String fileA) {
        return ("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"tag\"\r\n\r\nx\r\n--" + BOUNDARY
                + "\r\nContent-Disposition: form-data; name=\"tag\"\r\n\r\ny\r\n--" + BOUNDARY
                + "\r\nContent-Disposition: form-data; name=\"p\"; filename=\"" + fileA + "\"\r\nContent-Type: " + typeA
                + "\r\n\r\nPNGDATA\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void complete_multipartFile_isStoredWithItsDeclaredContentTypeAndFileName() throws Exception {
        var store = store(storage(64L * MIB));
        var sink = sink(store, multipart(UploadSpec.uploadSpec(true, 0, 0)));

        assertThat(push(sink, typedFileBody("image/png", "photo.png"), 1000)).isEmpty();
        sink.complete(head("multipart/form-data; boundary=" + BOUNDARY), writer());
        await(() -> dispatched.get() != null, "the request is dispatched");

        var handle = handleOf(dispatched.get());
        var stored = store.stat(refOf(handle, 0).id()).await(WAIT).unwrap().unwrap().meta();

        assertThat(stored.contentType()).isEqualTo("image/png");
        assertThat(stored.displayName().toOptional()).contains("photo.png");
        assertThat(handle.files().getFirst().declaredType()).isEqualTo("image/png");
        assertThat(handle.files().getFirst().fileName().toOptional()).contains("photo.png");
    }

    @Test
    void complete_repeatedFieldName_keepsEveryValueInOrder() throws Exception {
        var sink = sink(store(storage(64L * MIB)), multipart(UploadSpec.uploadSpec(true, 0, 0)));

        assertThat(push(sink, typedFileBody("image/png", "photo.png"), 1000)).isEmpty();
        sink.complete(head("multipart/form-data; boundary=" + BOUNDARY), writer());
        await(() -> dispatched.get() != null, "the request is dispatched");

        assertThat(handleOf(dispatched.get()).fields()).containsEntry("tag", List.of("x", "y"));
    }

    @Test
    void complete_binaryBody_isStoredAndHandedOverWithItsDeclaredContentType() throws Exception {
        var store = store(storage(64L * MIB));
        var sink = sink(store, new IngestSink.Body.Binary(ObjectSpec.objectSpec("image/png")));

        assertThat(push(sink, random(1000, 13), 400)).isEmpty();
        sink.complete(head("image/png"), writer());
        await(() -> dispatched.get() != null, "the request is dispatched");

        var handle = handleOf(dispatched.get());
        var ref = refOf(handle, 0);

        assertThat(handle.files().getFirst().declaredType()).isEqualTo("image/png");
        assertThat(store.stat(ref.id()).await(WAIT).unwrap().unwrap().meta().contentType()).isEqualTo("image/png");
        assertThat(ref.namespace()).as("the handle names the namespace the object lives in").isEqualTo("com.example.svc");
        assertThat(ref.size()).isEqualTo(1000);
        assertThat(handle.files().getFirst().object()).as("the wire handle is the reference's text form")
                                                      .isEqualTo(UploadObjects.objectHandle(ref));
    }

    // ---- #2876: a late completion of an abandoned accept ----
    /// The server abandoned an accept whose commit hangs; the commit resolves after the abort. What it stored is deleted.
    @Test
    void abort_thenTheHungCommitResolves_leavesTheStoreEmpty() throws Exception {
        var spy = new Spy(false);
        var store = store(storage(64L * MIB));
        var sink = sink(spy(store, spy), multipart(UploadSpec.uploadSpec(true, 0, 0)));
        var hung = Promise.<Unit>promise();

        spy.commitGate = hung;
        sink.accept(ByteBuffer.wrap(multipartBody(random(100, 1), random(100, 2))));
        sink.abort(BodySink.Ended.STALLED);
        spy.deleteGate.succeed(Unit.unit());
        await(() -> permits.get() == 1, "the clean-up settled and the permit was returned");
        hung.succeed(Unit.unit());
        await(() -> catalog(store).isEmpty() && spy.deletes.get() >= 1, "the late commit's object was deleted");

        assertThat(catalog(store)).as("nothing the abandoned chain committed survives").isEmpty();
    }

    /// The same chain must also not go on: after the abort, the next file part's writer is never created.
    @Test
    void abort_thenTheHungCommitResolves_opensNoFurtherWriter() throws Exception {
        var spy = new Spy(false);
        var store = store(storage(64L * MIB));
        var sink = sink(spy(store, spy), multipart(UploadSpec.uploadSpec(true, 0, 0)));
        var hung = Promise.<Unit>promise();

        spy.commitGate = hung;
        spy.deleteGate.succeed(Unit.unit());
        sink.accept(ByteBuffer.wrap(multipartBody(random(100, 1), random(100, 2))));
        sink.abort(BodySink.Ended.STALLED);
        await(() -> permits.get() == 1, "the clean-up settled and the permit was returned");
        hung.succeed(Unit.unit());
        await(() -> spy.deletes.get() >= 1, "the late commit's object was deleted");
        Thread.sleep(200);

        assertThat(spy.creates.get()).as("only the first file's writer was ever created").isEqualTo(1);
        assertThat(spy.aborts.get()).isZero();
    }

    /// A file start whose create hangs and resolves after the abort: the writer it yields is aborted at once.
    @Test
    void abort_thenTheHungCreateResolves_abortsTheLateWriter_andStoresNothing() throws Exception {
        var spy = new Spy(false);
        var store = store(storage(64L * MIB));
        var sink = sink(spy(store, spy), multipart(UploadSpec.uploadSpec(true, 0, 0)));
        var hung = Promise.<Unit>promise();

        spy.createGate = hung;
        sink.accept(ByteBuffer.wrap(multipartBody(random(100, 1), random(100, 2))));
        sink.abort(BodySink.Ended.STALLED);
        await(() -> permits.get() == 1, "the clean-up settled and the permit was returned");
        hung.succeed(Unit.unit());
        await(() -> spy.aborts.get() == 1, "the late writer was aborted");
        Thread.sleep(200);

        assertThat(spy.aborts.get()).isEqualTo(1);
        assertThat(spy.creates.get()).as("the chain stopped at the refused writer").isEqualTo(1);
        assertThat(catalog(store)).isEmpty();
    }

    /// #2876: a delete that never resolves must not hold the request's admission: the clean-up gives up on it at the bound.
    @Test
    void abort_withAHungDelete_returnsThePermitAtTheDeleteBound() throws Exception {
        var spy = new Spy(false);
        var sink = sink(spy(store(storage(64L * MIB)), spy),
                        multipart(UploadSpec.uploadSpec(true, 0, 0)),
                        TimeSpan.timeSpan(300).millis());

        assertThat(push(sink, firstFileCommittedSecondOpen(), 400_000)).isEmpty();
        sink.abort(BodySink.Ended.DISCONNECTED);
        await(() -> spy.deletes.get() == 1, "the committed file's delete started");

        await(() -> permits.get() == 1, "the delete was given up on and the permit was returned");
        assertThat(spy.deleteGate.isResolved()).as("the delete itself never resolved").isFalse();
    }

    /// #2876: the late commit's delete hangs too: the abandoned chain still ends (failed), it does not hang.
    @Test
    void abort_thenTheHungCommitResolves_withAHungDelete_theAbandonedChainEndsAtTheDeleteBound() throws Exception {
        var spy = new Spy(false);
        var sink = sink(spy(store(storage(64L * MIB)), spy),
                        multipart(UploadSpec.uploadSpec(true, 0, 0)),
                        TimeSpan.timeSpan(300).millis());
        var hung = Promise.<Unit>promise();

        spy.commitGate = hung;
        var chain = sink.accept(ByteBuffer.wrap(multipartBody(random(100, 1), random(100, 2))));

        sink.abort(BodySink.Ended.STALLED);
        await(() -> permits.get() == 1, "the clean-up settled and the permit was returned");
        hung.succeed(Unit.unit());
        await(() -> spy.deletes.get() == 1, "the late commit's delete started");

        await(chain::isResolved, "the abandoned chain ended though its delete never resolved");
        assertThat(chain.await(WAIT).isFailure()).as("the chain failed as abandoned").isTrue();
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
