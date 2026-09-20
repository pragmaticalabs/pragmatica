// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
package org.pragmatica.aether.worker.metadata;

import java.util.HexFormat;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.function.Function;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.pragmatica.lang.utils.TimeSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.SliceCodec;


/// Shared core-side snapshot cache. KV monitor makes manifest capture atomic with committed batches.
public final class WorkerMetadataServer {
    private final NodeId self;
    private final TimeSource clock = TimeSource.system();
    private final KVStore<AetherKey, AetherValue> store;
    private final SliceCodec codec;
    private final BiConsumer<NodeId, ProtocolMessage> send;
    private final Predicate<NodeId> eligibleWorker;
    private final Supplier<Set<NodeId>> cores;
    private final Function<Set<NodeId>, List<NodeInfo>> directory;
    private final WorkerMetadataLimits limits;
    private final BiConsumer<NodeId, String> reportRejection;
    private final String incarnation = UUID.randomUUID().toString();
    private final WorkerMetadataIndex index = WorkerMetadataIndex.workerMetadataIndex();
    private final Map<String, byte[]> blobs = new LinkedHashMap<>();
    private final Map<String, CachedScope> cachedScopes = new HashMap<>();
    private final Map<NodeId, Lease> manifests = new HashMap<>();
    private boolean initialized;
    private long cachedBytes;
    private long budgetSecond;
    private long sentBytes;
    private long generation;

    private record CachedScope(long revision, WorkerMetadataMessage.ScopeContent content) {}

    private record Lease(WorkerMetadataMessage.Manifest manifest, long expiresAt) {}

    public WorkerMetadataServer(NodeId self,
                                KVStore<AetherKey, AetherValue> store,
                                SliceCodec codec,
                                BiConsumer<NodeId, ProtocolMessage> send,
                                Predicate<NodeId> eligibleWorker,
                                Supplier<Set<NodeId>> cores,
                                Function<Set<NodeId>, List<NodeInfo>> directory,
                                WorkerMetadataLimits limits,
                                BiConsumer<NodeId, String> reportRejection) {
        this.self = self;
        this.store = store;
        this.codec = codec;
        this.send = send;
        this.eligibleWorker = eligibleWorker;
        this.cores = cores;
        this.directory = directory;
        this.limits = limits;
        this.reportRejection = reportRejection;
    }

    public Unit put(StructuredKey key, Object value) {
        synchronized (store) {
            if (initialized) {
                index.put(key, value);
            }
        }

        return Unit.unit();
    }

    public Unit onStateRestored() {
        synchronized (store) {
            initialized = false;
        }

        return Unit.unit();
    }

    public Unit remove(StructuredKey key) {
        synchronized (store) {
            if (initialized) {
                index.remove(key);
            }
        }

        return Unit.unit();
    }

    public synchronized Unit onManifestRequest(WorkerMetadataMessage.ManifestRequest request) {
        var now = clock.nanoTime();

        expire(now);
        if (!eligibleWorker.test(request.sender())) {
            return Unit.unit();
        }

        if (store.committedRevision() < request.minimumRevision()) {
            failManifest(request, "core-behind-worker", now);

            return Unit.unit();
        }

        var previous = manifests.get(request.sender());

        if (previous != null && previous.manifest().requestId() == request.requestId()) {
            sendManifest(previous.manifest(), request.sender(), now);

            return Unit.unit();
        }

        if (previous == null && manifests.size() >= limits.manifests()) {
            failManifest(request, "manifest-capacity", now);

            return Unit.unit();
        }

        capture(request.sender()).flatMap(cut -> encodeCut(cut).map(contents -> new EncodedCut(cut.revision(),
                                                                                               contents)))
               .onSuccess(cut -> publish(request,
                                         cut.contents(),
                                         cut.revision(),
                                         now))
               .onFailure(_ -> failManifest(request, "projection-unavailable-or-oversize", now));

        return Unit.unit();
    }

    private record CapturedScope(String name,
                                 long revision,
                                 Map<StructuredKey, Object> entries,
                                 Option<WorkerMetadataMessage.ScopeContent> cached) {}

    private record CapturedCut(long revision,
                               List<CapturedScope> scopes,
                               List<NodeInfo> peers,
                               List<NodeInfo> endpoints) {}

    private record EncodedCut(long revision, List<WorkerMetadataMessage.ScopeContent> contents) {}

    private Result<CapturedCut> capture(NodeId worker) {
        synchronized (store) {
            if (store.hasPendingNotifications()) {
                return Causes.cause("Committed metadata notifications are still being delivered").result();
            }

            if (!initialized) {
                index.initialize(store.snapshot());
                initialized = true;
            }

            cachedScopes.keySet().removeIf(scope -> !index.hasScope(scope));
            var selected = index.scopesForWorker(worker);

            if (selected.size() + 2 > limits.scopesPerWorker()) {
                return Causes.cause("Worker metadata scope count exceeds configured limit").result();
            }

            return Result.success(new CapturedCut(store.committedRevision(),
                                                  selected.stream().map(this::captureScope).toList(),
                                                  directory.apply(index.peersForWorker(worker, cores.get())),
                                                  directory.apply(index.endpointPeersForWorker(worker))));
        }
    }

    private CapturedScope captureScope(String name) {
        var revision = index.revision(name);
        var cached = Option.option(cachedScopes.get(name))
                           .filter(value -> value.revision() == revision && blobs.containsKey(value.content().hash()))
                           .map(CachedScope::content);

        return new CapturedScope(name,
                                 revision,
                                 cached.isPresent()
                                 ? Map.of()
                                 : index.snapshot(name),
                                 cached);
    }

    private Result<List<WorkerMetadataMessage.ScopeContent>> encodeCut(CapturedCut cut) {
        return Result.allOf(cut.scopes().stream().map(this::scopeContent))
                     .flatMap(contents -> encode(WorkerMetadataIndex.DIRECTORY,
                                                 cut.peers()).map(peers -> append(contents, peers)))
                     .flatMap(contents -> encode(WorkerMetadataIndex.ENDPOINT_DIRECTORY,
                                                 cut.endpoints()).map(peers -> append(contents, peers)))
                     .flatMap(this::checkManifestSize);
    }

    private Result<List<WorkerMetadataMessage.ScopeContent>> checkManifestSize(List<WorkerMetadataMessage.ScopeContent> contents) {
        return contents.stream()
                       .mapToLong(WorkerMetadataMessage.ScopeContent::length)
                       .sum() > limits.cacheBytes()
               ? Causes.cause("Worker metadata projection exceeds configured byte limit").result()
               : Result.success(contents);
    }

    private static List<WorkerMetadataMessage.ScopeContent> append(List<WorkerMetadataMessage.ScopeContent> scopes,
                                                                   WorkerMetadataMessage.ScopeContent directory) {
        var result = new ArrayList<>(scopes);

        result.add(directory);

        return List.copyOf(result);
    }

    private Result<WorkerMetadataMessage.ScopeContent> scopeContent(CapturedScope scope) {
        return scope.cached()
                    .map(Result::success)
                    .or(() -> encode(scope.name(),
                                     scope.entries()).onSuccess(content -> cachedScopes.put(scope.name(),
                                                                                            new CachedScope(scope.revision(),
                                                                                                            content))));
    }

    private Result<WorkerMetadataMessage.ScopeContent> encode(String scope, Object value) {
        return Result.lift(Causes::fromThrowable,
                           () -> codec.canonical()
                                      .encode(value))
                     .flatMap(bytes -> cache(scope, bytes));
    }

    private Result<WorkerMetadataMessage.ScopeContent> cache(String scope, byte[] bytes) {
        if (bytes.length > limits.scopeBytes() || bytes.length > limits.cacheBytes()) {
            return Causes.cause("Worker metadata scope exceeds configured byte limit").result();
        }

        return hash(bytes).map(hash -> storeBlob(scope, hash, bytes));
    }

    static Result<String> hash(byte[] bytes) {
        return Result.lift(() -> MessageDigest.getInstance("SHA-256")).map(digest -> HexFormat.of().formatHex(digest.digest(bytes)));
    }

    private WorkerMetadataMessage.ScopeContent storeBlob(String scope, String hash, byte[] bytes) {
        if (!blobs.containsKey(hash)) {
            while (cachedBytes + bytes.length > limits.cacheBytes() && !blobs.isEmpty()) {
                var iterator = blobs.entrySet().iterator();
                var oldest = iterator.next();

                cachedBytes -= oldest.getValue().length;
                iterator.remove();
            }

            blobs.put(hash, bytes);
            cachedBytes += bytes.length;
        }

        return new WorkerMetadataMessage.ScopeContent(scope, hash, bytes.length);
    }

    private void publish(WorkerMetadataMessage.ManifestRequest request,
                         List<WorkerMetadataMessage.ScopeContent> contents,
                         long revision,
                         long now) {
        var manifest = new WorkerMetadataMessage.Manifest(self,
                                                          request.requestId(),
                                                          incarnation,
                                                          ++generation,
                                                          revision,
                                                          contents,
                                                          "");

        manifests.put(request.sender(),
                      new Lease(manifest,
                                now + limits.manifestTtl().nanos()));
        sendManifest(manifest, request.sender(), now);
    }

    private void sendManifest(WorkerMetadataMessage.Manifest manifest, NodeId destination, long now) {
        if (allowBytes(256L + manifest.scopes().size() * 256L,
                       now)) {
            send.accept(destination, manifest);
        }
    }

    private void failManifest(WorkerMetadataMessage.ManifestRequest request, String error, long now) {
        reportRejection.accept(request.sender(), error);
        sendManifest(new WorkerMetadataMessage.Manifest(self,
                                                        request.requestId(),
                                                        incarnation,
                                                        0,
                                                        store.committedRevision(),
                                                        List.of(),
                                                        error),
                     request.sender(),
                     now);
    }

    public synchronized Unit onChunkRequest(WorkerMetadataMessage.ChunkRequest request) {
        synchronized (this) {
            var now = clock.nanoTime();

            expire(now);
            if (!eligibleWorker.test(request.sender())) {
                return Unit.unit();
            }

            var lease = manifests.get(request.sender());

            if (!matches(Option.option(lease), request)) {
                failChunk(request, "manifest-expired", now);

                return Unit.unit();
            }

            var content = lease.manifest()
                               .scopes()
                               .stream()
                               .filter(scope -> scope.scope()
                                                     .equals(request.scope()) && scope.hash()
                                                                                      .equals(request.hash()))
                               .findFirst();
            var bytes = blobs.get(request.hash());

            if (content.isEmpty() || bytes == null || request.offset() < 0 || request.offset() >= bytes.length) {
                failChunk(request, "content-evicted-or-invalid-offset", now);

                return Unit.unit();
            }

            var end = (int) Math.min((long) bytes.length,
                                     (long) request.offset() + limits.chunkBytes());

            if (!allowBytes(end - request.offset() + 256L, now)) {
                return Unit.unit();
            }

            send.accept(request.sender(),
                        new WorkerMetadataMessage.Chunk(self,
                                                        request.requestId(),
                                                        incarnation,
                                                        request.generation(),
                                                        request.scope(),
                                                        request.hash(),
                                                        request.offset(),
                                                        Arrays.copyOfRange(bytes, request.offset(), end),
                                                        ""));
        }

        return Unit.unit();
    }

    private static boolean matches(Option<Lease> lease, WorkerMetadataMessage.ChunkRequest request) {
        return lease.filter(value -> value.manifest()
                                          .requestId() == request.requestId()
                                     && value.manifest()
                                             .generation() == request.generation()
                                     && value.manifest()
                                             .incarnation()
                                             .equals(request.incarnation()))
                    .isPresent();
    }

    private void failChunk(WorkerMetadataMessage.ChunkRequest request, String error, long now) {
        reportRejection.accept(request.sender(), error);
        if (allowBytes(256, now)) {
            send.accept(request.sender(),
                        new WorkerMetadataMessage.Chunk(self,
                                                        request.requestId(),
                                                        incarnation,
                                                        request.generation(),
                                                        request.scope(),
                                                        request.hash(),
                                                        request.offset(),
                                                        new byte[0],
                                                        error));
        }
    }

    private void expire(long now) {
        manifests.values().removeIf(lease -> lease.expiresAt() < now);
    }

    private boolean allowBytes(long bytes, long now) {
        var second = java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(now);

        if (second != budgetSecond) {
            budgetSecond = second;
            sentBytes = 0;
        }

        if (bytes > limits.bytesPerSecond() - sentBytes) {
            return false;
        }

        sentBytes += bytes;

        return true;
    }

    long cachedBytes() {
        synchronized (this) {
            return cachedBytes;
        }
    }

    int manifestCount() {
        synchronized (this) {
            return manifests.size();
        }
    }
}
