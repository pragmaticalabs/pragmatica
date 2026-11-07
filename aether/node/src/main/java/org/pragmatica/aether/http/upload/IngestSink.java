// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
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
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.storage.ContentStore;
import org.pragmatica.storage.ObjectId;
import org.pragmatica.storage.ObjectInfo;
import org.pragmatica.storage.ObjectSpec;
import org.pragmatica.storage.ObjectWriter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;


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
/// step at a time; each step happens after the resolution of the one before.
public final class IngestSink implements BodySink {
    private static final Logger log = LoggerFactory.getLogger(IngestSink.class);
    private static final Cause NO_OPEN_WRITER = Causes.cause("Multipart file content arrived outside a file part");

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
    private final AtomicBoolean released = new AtomicBoolean();
    private final List<FileHandle> files = new ArrayList<>();
    private final List<ObjectId> committed = new ArrayList<>();
    private final Map<String, List<String>> fields = new LinkedHashMap<>();
    private Option<OpenFile> open = Option.none();

    private IngestSink(ContentStore store, Body body, Runnable permit, Dispatch dispatch, Refuse refuse) {
        this.store = store;
        this.body = body;
        this.permit = permit;
        this.dispatch = dispatch;
        this.refuse = refuse;
    }

    public static IngestSink ingestSink(ContentStore store,
                                        Body body,
                                        Runnable permit,
                                        Dispatch dispatch,
                                        Refuse refuse) {
        return new IngestSink(store, body, permit, dispatch, refuse);
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
        return open.fold(() -> store.create(binary.spec())
                                    .map(writer -> opened(writer,
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
                                                                             Map.of())).map(writer -> opened(writer,
                                                                                                             start)).mapToUnit();
            case Event.FileData data -> open.async(NO_OPEN_WRITER).flatMap(file -> file.writer()
                                                                                       .write(data.bytes()));
            case Event.FileEnd _ -> open.async(NO_OPEN_WRITER).flatMap(this::commitOpen);
            case Event.Field field -> Promise.success(addField(field));
        };
    }

    private Unit addField(Event.Field field) {
        fields.computeIfAbsent(field.name(), _ -> new ArrayList<>()).add(field.value());

        return unit();
    }

    // ---- writers ----
    private OpenFile opened(ObjectWriter writer, Event.FileStart part) {
        var file = new OpenFile(writer, part);

        open = Option.some(file);

        return file;
    }

    /// Commits the open object and records it as one of the request's files. The writer is no longer open whether the
    /// commit succeeded or not: a failed commit gives back what the writer stored.
    private Promise<Unit> commitOpen(OpenFile file) {
        open = Option.none();

        return file.writer()
                   .commit(Option.none())
                   .map(info -> recordFile(info,
                                           file.part()));
    }

    private Unit recordFile(ObjectInfo info, Event.FileStart part) {
        committed.add(info.ref().id());
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
        var file = open;

        open = Option.none();

        return file.fold(Promise::unitPromise,
                         openFile -> openFile.writer()
                                             .abort())
                   .onFailure(cause -> log.warn("Aborting a streamed upload's writer failed: {}",
                                                cause.message()))
                   .fold(_ -> deleteStored());
    }

    private Promise<Unit> deleteStored() {
        var ids = List.copyOf(committed);

        committed.clear();

        return Promise.allOf(ids.stream().map(this::deleteOne).toList()).mapToUnit();
    }

    private Promise<Unit> deleteOne(ObjectId id) {
        return store.delete(id)
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
