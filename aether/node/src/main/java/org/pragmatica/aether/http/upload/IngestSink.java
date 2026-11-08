// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.upload;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.pragmatica.aether.http.handler.FileHandle;
import org.pragmatica.aether.http.handler.UploadHandle;
import org.pragmatica.aether.http.upload.MultipartDecoder.Event;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.server.BodySink;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.storage.ContentStore;
import org.pragmatica.storage.ObjectId;
import org.pragmatica.storage.ObjectInfo;
import org.pragmatica.storage.ObjectSpec;
import org.pragmatica.storage.ObjectWriter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Takes an upload body as it arrives and writes it to content storage (U2, spec §B2): a body that is not
/// `multipart/form-data` becomes one object; a multipart body becomes one object per file part, written in order with one
/// writer open at a time, and its text fields are kept for the handle.
///
/// - **Backpressure.** An `accept` resolves only when every write of the chunk resolved, so the server reads no further
///   while storage is behind. The chunk is not kept past that point: file content is written from slices of it, which
///   the writer copies into its own block buffer, and field values are copied.
/// - **Complete.** The open writer is committed (or the multipart decoder confirms the closing delimiter), the
///   [UploadHandle] is built, and the request is handed to `dispatch` with an empty body and the handle attached.
/// - **Failure.** On a refused or failed chunk, a body the server gave up on, or a commit that failed, the open writer is
///   aborted and every object already committed for the request is deleted, so nothing it stored stays in the catalog.
///   The client is answered the refusal's status ([UploadIngestError#status]); after an abort the server answers.
/// - **Permit.** `permit` (the upload's admission) is run exactly once, on whichever terminal path ends the request:
///   after the request was handed to `dispatch`, after a refusal was answered and its clean-up settled, or after an
///   abort's clean-up settled [G: release after the hand-off, not after the slice answers].
///
/// The server makes one call at a time, each after the previous `accept` resolved, so the state below is touched by one
/// step at a time; each step happens after the resolution of the one before. One exception: a server that abandoned a
/// hung `accept` (the abort deadline) aborts the sink while that `accept`'s storage chain is still running, so the
/// clean-up and the chain's late steps can meet. The state they share (`open`, `committed`, `aborted`) is therefore
/// guarded by the sink's monitor, and `aborted` (set by the first clean-up) makes a late step undo itself: a writer that
/// opens after the clean-up is aborted, an object that commits after it is deleted, and the chain stops there.
public final class IngestSink implements BodySink {
    private static final Logger log = LoggerFactory.getLogger(IngestSink.class);
    private static final Cause NO_OPEN_WRITER = Causes.cause("Multipart file content arrived outside a file part");
    /// How long one object delete may take before it is given up on (logged as a failure, the object left for A4).
    private static final TimeSpan DELETE_BOUND = timeSpan(30).seconds();
    private static final Cause ABANDONED = Causes.cause("The upload ended before this step completed");

    /// Hands a stored upload's request to the normal dispatch path.
    @FunctionalInterface
    public interface Dispatch {
        @Contract
        void dispatch(HttpRequest request, ResponseWriter response);
    }

    /// Answers a refused upload with its status and message.
    @FunctionalInterface
    public interface Refuse {
        @Contract
        void refuse(ResponseWriter response, UploadIngestError cause);
    }

    /// What the body is: one object of `spec`, or a multipart body read by `decoder`, whose file objects expire at
    /// `expiresAt`.
    public sealed interface Body {
        record Binary(ObjectSpec spec) implements Body {}

        record Multipart(MultipartDecoder decoder, Option<Instant> expiresAt) implements Body {}
    }

    /// The writer of the object being written, and the part it is for.
    private record OpenFile(ObjectWriter writer, Event.FileStart part) {}

    private final ContentStore store;
    private final Body body;
    private final Runnable permit;
    private final Dispatch dispatch;
    private final Refuse refuse;
    private final TimeSpan deleteBound;
    private final AtomicBoolean released = new AtomicBoolean();
    private final List<FileHandle> files = new ArrayList<>();
    private final List<ObjectId> committed = new ArrayList<>();
    private final Map<String, List<String>> fields = new LinkedHashMap<>();
    // Guarded by `this`: shared by the clean-up and the storage chain of an abandoned accept.
    private Option<OpenFile> open = Option.none();
    private boolean aborted;

    private IngestSink(ContentStore store,
                       Body body,
                       Runnable permit,
                       Dispatch dispatch,
                       Refuse refuse,
                       TimeSpan deleteBound) {
        this.store = store;
        this.body = body;
        this.permit = permit;
        this.dispatch = dispatch;
        this.refuse = refuse;
        this.deleteBound = deleteBound;
    }

    public static IngestSink ingestSink(ContentStore store,
                                        Body body,
                                        Runnable permit,
                                        Dispatch dispatch,
                                        Refuse refuse) {
        return ingestSink(store, body, permit, dispatch, refuse, DELETE_BOUND);
    }

    static IngestSink ingestSink(ContentStore store,
                                 Body body,
                                 Runnable permit,
                                 Dispatch dispatch,
                                 Refuse refuse,
                                 TimeSpan deleteBound) {
        return new IngestSink(store, body, permit, dispatch, refuse, deleteBound);
    }

    @Override
    public Promise<Unit> accept(ByteBuffer chunk) {
        return switch (body) {
            case Body.Binary binary -> binaryFile(binary).flatMap(file -> file.writer()
                                                                              .write(chunk));
            case Body.Multipart multipart -> multipart.decoder().push(chunk).async().flatMap(events -> handleAll(events,
                                                                                                                 multipart));
        };
    }

    @Override
    @Contract
    public void complete(HttpRequest request, ResponseWriter response) {
        finishBody().map(_ -> uploadHandle())
                  .onSuccess(handle -> handOff(request, handle, response))
                  .onFailure(cause -> refuseAndClean(cause, response));
    }

    @Override
    @Contract
    public void fail(Cause cause, ResponseWriter response) {
        refuseAndClean(cause, response);
    }

    @Override
    @Contract
    public void abort(Cause cause) {
        log.debug("Streamed upload ended by the server: {}", cause.message());
        cleanUp().onResultRun(this::release);
    }

    /// Test seam: whether the permit was returned.
    boolean released() {
        return released.get();
    }

    // ---- binary body ----
    /// The body's one object, created on the first chunk (or at completion, for an empty body).
    private Promise<OpenFile> binaryFile(Body.Binary binary) {
        return currentOpen().fold(() -> store.create(binary.spec())
                                             .flatMap(writer -> opened(writer,
                                                                       new Event.FileStart("",
                                                                                           Option.none(),
                                                                                           binary.spec().contentType()))),
                                  Promise::success);
    }

    private Promise<Unit> finishBody() {
        return switch (body) {
            case Body.Binary binary -> binaryFile(binary).flatMap(this::commitOpen);
            case Body.Multipart multipart -> multipart.decoder().finish().async();
        };
    }

    // ---- multipart body ----
    /// The events of one chunk, strictly in order: a write starts only when the one before it resolved.
    private Promise<Unit> handleAll(List<Event> events, Body.Multipart multipart) {
        var chain = Promise.unitPromise();

        for (var event : events) {
            chain = chain.flatMap(_ -> handle(event, multipart));
        }

        return chain;
    }

    private Promise<Unit> handle(Event event, Body.Multipart multipart) {
        return switch (event) {
            case Event.FileStart start -> store.create(ObjectSpec.objectSpec(start.fileName(),
                                                                             start.contentType(),
                                                                             Option.none(),
                                                                             multipart.expiresAt(),
                                                                             Map.of())).flatMap(writer -> opened(writer,
                                                                                                                 start)).mapToUnit();
            case Event.FileData data -> currentOpen().async(NO_OPEN_WRITER).flatMap(file -> file.writer()
                                                                                                .write(data.bytes()));
            case Event.FileEnd _ -> currentOpen().async(NO_OPEN_WRITER).flatMap(this::commitOpen);
            case Event.Field field -> Promise.success(addField(field));
        };
    }

    private Unit addField(Event.Field field) {
        fields.computeIfAbsent(field.name(), _ -> new ArrayList<>()).add(field.value());

        return unit();
    }

    // ---- writers ----
    /// The created writer becomes the open one, unless the upload ended while it was being created: then it is aborted
    /// at once and the chain fails.
    private Promise<OpenFile> opened(ObjectWriter writer, Event.FileStart part) {
        var file = new OpenFile(writer, part);

        return register(file)
               ? Promise.success(file)
               : abandonWriter(writer);
    }

    private Promise<OpenFile> abandonWriter(ObjectWriter writer) {
        return writer.abort()
                     .onFailure(cause -> log.warn("Aborting a late upload writer failed: {}",
                                                  cause.message()))
                     .fold(_ -> ABANDONED.promise());
    }

    private synchronized boolean register(OpenFile file) {
        if (aborted) {
            return false;
        }

        open = Option.some(file);

        return true;
    }

    private synchronized Option<OpenFile> currentOpen() {
        return open;
    }

    private synchronized void closeOpen() {
        open = Option.none();
    }

    /// Commits the open object and records it as one of the request's files. The writer is no longer open whether the
    /// commit succeeded or not: a failed commit gives back what the writer stored.
    private Promise<Unit> commitOpen(OpenFile file) {
        closeOpen();

        return file.writer()
                   .commit(Option.none())
                   .flatMap(info -> recordFile(info,
                                               file.part()));
    }

    /// Records the committed object as one of the request's files; if the upload ended while it was committing, it is
    /// deleted instead (no clean-up will see it) and the chain fails.
    private Promise<Unit> recordFile(ObjectInfo info, Event.FileStart part) {
        var id = info.ref().id();

        return keep(id)
               ? Promise.success(addFile(info, part))
               : deleteOne(id).fold(_ -> ABANDONED.promise());
    }

    private synchronized boolean keep(ObjectId id) {
        if (aborted) {
            return false;
        }

        committed.add(id);

        return true;
    }

    private Unit addFile(ObjectInfo info, Event.FileStart part) {
        files.add(FileHandle.fileHandle(part.field(),
                                        part.fileName(),
                                        part.contentType(),
                                        UploadObjects.objectHandle(info.ref())));

        return unit();
    }

    private UploadHandle uploadHandle() {
        return UploadHandle.uploadHandle(files, fields);
    }

    // ---- terminal paths ----
    private void handOff(HttpRequest request, UploadHandle handle, ResponseWriter response) {
        dispatch.dispatch(IngestedRequest.ingestedRequest(request, handle, this::discardStored), response);
        release();
    }

    private void refuseAndClean(Cause cause, ResponseWriter response) {
        var refusal = UploadIngestError.ingestError(cause);

        log.debug("Streamed upload refused ({}): {}", refusal.status(), cause.message());
        refuse.refuse(response, refusal);
        cleanUp().onResultRun(this::release);
    }

    /// Aborts the open writer, then deletes every object already committed for this request; resolves when both settled.
    /// A failed step is logged and absorbed (FER: none exists before cluster GC, A4): an object whose delete failed stays
    /// in storage, in the unclaimed-content window the docs name, until A4 reclaims it.
    private Promise<Unit> cleanUp() {
        var file = seal();

        return file.fold(Promise::unitPromise,
                         openFile -> openFile.writer()
                                             .abort())
                   .onFailure(cause -> log.warn("Aborting a streamed upload's writer failed: {}",
                                                cause.message()))
                   .fold(_ -> deleteStored());
    }

    /// Marks the upload ended and takes the open writer, so no late step can open or keep anything after this.
    private synchronized Option<OpenFile> seal() {
        var file = open;

        aborted = true;
        open = Option.none();

        return file;
    }

    private synchronized List<ObjectId> takeCommitted() {
        var ids = List.copyOf(committed);

        committed.clear();

        return ids;
    }

    private Promise<Unit> deleteStored() {
        var ids = takeCommitted();

        return Promise.allOf(ids.stream().map(this::deleteOne).toList()).mapToUnit();
    }

    /// A delete that does not resolve within the bound fails with a timeout, so a hung store holds neither the permit nor
    /// the abandoned chain; the timeout is logged like any other failed delete.
    private Promise<Unit> deleteOne(ObjectId id) {
        return store.delete(id)
                    .timeout(deleteBound)
                    .onFailure(cause -> log.warn("Deleting stored upload object {} failed: {}",
                                                 id.asString(),
                                                 cause.message()));
    }

    /// What the dispatch path runs when the request reaches no slice: the committed objects are deleted, fire and forget
    /// (each failure is logged by [#deleteOne]).
    @Contract
    private void discardStored() {
        deleteStored().onResultRun(() -> log.debug("Discarded the stored upload of a request no slice received"));
    }

    private void release() {
        if (released.compareAndSet(false, true)) {
            permit.run();
        }
    }
}
