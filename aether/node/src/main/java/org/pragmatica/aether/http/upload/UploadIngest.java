// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.upload;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.http.handler.ObjectHandle;
import org.pragmatica.aether.http.handler.UploadHandle;
import org.pragmatica.aether.storage.SliceNamespace;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.routing.UploadSpec;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.storage.Compression;
import org.pragmatica.storage.ContentStore;
import org.pragmatica.storage.ContentStoreConfig;
import org.pragmatica.storage.Namespace;
import org.pragmatica.storage.ObjectInfo;
import org.pragmatica.storage.ObjectSpec;
import org.pragmatica.storage.StorageInstance;


/// The node's streaming upload ingest (U2): one [ContentStore] per slice namespace over the node's `content` storage
/// instance, created on first use and kept, and the plan of each streamed upload.
///
/// Every store uses the default chunk size, write window and read-ahead of [ContentStoreConfig], without compression
/// [G: the node's settings are the defaults].
public final class UploadIngest {
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final StorageInstance storage;
    private final TimeSpan unclaimedTtl;
    private final long fieldsCap;
    private final ContentStoreConfig settings = ContentStoreConfig.contentStoreConfig();
    private final Map<String, Result<ContentStore>> stores = new ConcurrentHashMap<>();

    private UploadIngest(StorageInstance storage, TimeSpan unclaimedTtl, long fieldsCap) {
        this.storage = storage;
        this.unclaimedTtl = unclaimedTtl;
        this.fieldsCap = fieldsCap;
    }

    /// Ingest into `storage`, whose stored uploads expire `unclaimedTtl` after ingest; a multipart body's fields are
    /// bounded by its route alone.
    public static UploadIngest uploadIngest(StorageInstance storage, TimeSpan unclaimedTtl) {
        return uploadIngest(storage, unclaimedTtl, Long.MAX_VALUE);
    }

    /// [#uploadIngest(StorageInstance, TimeSpan)], with a multipart body's field values capped at `fieldsCap` bytes in all,
    /// whatever the route declares: the node's byte budget, so no one request's fields can hold more memory than every
    /// upload in flight together may reserve.
    public static UploadIngest uploadIngest(StorageInstance storage, TimeSpan unclaimedTtl, long fieldsCap) {
        return new UploadIngest(storage, unclaimedTtl, fieldsCap);
    }

    /// The bytes one streamed upload holds in memory at most while its writes are in flight: the write window's blocks plus
    /// the one being filled [G: this is the `write_buffer_budget` share of one upload]. A multipart body adds its fields
    /// budget ([Plan#memoryBytes]).
    public long writeBufferBytes() {
        return (long)(settings.writeWindow() + 1) * settings.chunkSizeBytes();
    }

    /// The plan for streaming `head`'s body into the namespace of the slice `artifactCoordinate` (`groupId:artifactId:version`)
    /// under the part limits of `spec`: refused when the namespace cannot be derived or bound, or when a multipart body
    /// declares no usable boundary.
    public Result<Plan> plan(HttpRequest head, UploadSpec spec, String artifactCoordinate) {
        var contentType = head.headers().get("content-type");

        return Artifact.artifact(artifactCoordinate)
                       .map(ArtifactBase::artifactBase)
                       .flatMap(SliceNamespace::sliceNamespace)
                       .flatMap(this::store)
                       .flatMap(store -> body(head, spec, contentType).map(body -> new Plan(store,
                                                                                            body,
                                                                                            memoryBytes(body))));
    }

    /// The store of `namespace`, bound once.
    public Result<ContentStore> store(Namespace namespace) {
        return stores.computeIfAbsent(namespace.value(), this::bind);
    }

    /// Resolves when every object `upload` names is in this node's content storage; fails [UploadIngestError.UploadUnavailable]
    /// for the first that is not (a handle that names no object storage could have printed counts as not there), or with
    /// storage's own cause when it cannot tell.
    public Promise<Unit> available(UploadHandle upload) {
        return Promise.allOf(upload.files().stream().map(file -> available(file.object())).toList())
                      .flatMap(results -> Result.allOf(results).async())
                      .mapToUnit();
    }

    private Promise<Unit> available(ObjectHandle object) {
        var missing = UploadIngestError.UploadUnavailable.uploadUnavailable(object.id());

        return Result.all(Namespace.namespace(object.namespace()).flatMap(this::store),
                          UploadObjects.objectRef(object))
                     .map((store, ref) -> store.stat(ref.id()))
                     .fold(_ -> missing.<Option<ObjectInfo>> promise(),
                           stat -> stat)
                     .flatMap(info -> info.async(missing))
                     .mapToUnit();
    }

    private Result<ContentStore> bind(String namespace) {
        return ContentStore.contentStore(storage,
                                         ContentStoreConfig.contentStoreConfig(settings.chunkSizeBytes(),
                                                                               Compression.NONE,
                                                                               namespace,
                                                                               settings.writeWindow(),
                                                                               settings.readAhead()));
    }

    /// What one upload holds in memory at most: its writes in flight ([#writeBufferBytes]) and, for a multipart body, its
    /// field values, which the decoder refuses past their budget before buffering them. Saturates instead of overflowing.
    private long memoryBytes(IngestSink.Body body) {
        return switch (body) {
            case IngestSink.Body.Binary _ -> writeBufferBytes();
            case IngestSink.Body.Multipart multipart -> saturatingSum(writeBufferBytes(),
                                                                      multipart.decoder().fieldsBudget());
        };
    }

    private static long saturatingSum(long a, long b) {
        return b > Long.MAX_VALUE - a
               ? Long.MAX_VALUE
               : a + b;
    }

    private Result<IngestSink.Body> body(HttpRequest head, UploadSpec spec, Option<String> contentType) {
        var expiresAt = Option.some(Instant.now().plus(unclaimedTtl.duration()));

        return contentType.filter(MultipartDecoder::isFormData)
                          .fold(() -> Result.success(binary(head, contentType, expiresAt)),
                                type -> MultipartDecoder.multipartDecoder(type, spec, fieldsCap).map(decoder -> new IngestSink.Body.Multipart(decoder,
                                                                                                                                              expiresAt)));
    }

    private static IngestSink.Body binary(HttpRequest head, Option<String> contentType, Option<Instant> expiresAt) {
        return new IngestSink.Body.Binary(ObjectSpec.objectSpec(Option.none(),
                                                                contentType.or(DEFAULT_CONTENT_TYPE),
                                                                declaredLength(head),
                                                                expiresAt,
                                                                Map.of()));
    }

    private static Option<Long> declaredLength(HttpRequest head) {
        return head.headers()
                   .get("content-length")
                   .flatMap(value -> Number.parseLong(value.strip()).option())
                   .filter(length -> length >= 0);
    }

    /// Where one streamed upload goes, how its body is read, and the most it holds in memory (`memoryBytes`, what it reserves
    /// from the node's byte budget when it declares no smaller length); [#sink] gives it its admission permit and its way
    /// out.
    public record Plan(ContentStore store, IngestSink.Body body, long memoryBytes) {
        public IngestSink sink(Runnable permit, IngestSink.Dispatch dispatch, IngestSink.Refuse refuse) {
            return IngestSink.ingestSink(store, body, permit, dispatch, refuse);
        }
    }
}
