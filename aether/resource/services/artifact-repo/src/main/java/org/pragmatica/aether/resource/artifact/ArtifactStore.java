// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.artifact;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactId;
import org.pragmatica.aether.artifact.GroupId;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.dht.DHTConfig.DhtRetryPolicy;
import org.pragmatica.dht.DHTError;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.StorageInstance;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;


public interface ArtifactStore {
    Promise<DeployResult> deploy(Artifact artifact, byte[] content);
    Promise<byte[]> resolve(Artifact artifact);
    Promise<ResolvedArtifact> resolveWithMetadata(Artifact artifact);

    record ResolvedArtifact(byte[] content, ArtifactMetadata metadata) {
        public ResolvedArtifact {
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ResolvedArtifact other
                   && Arrays.equals(content, other.content)
                   && metadata.equals(other.metadata);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(content) + metadata.hashCode();
        }
    }

    Promise<Boolean> exists(Artifact artifact);
    /// Fetch persisted metadata for an artifact WITHOUT reading or integrity-verifying
    /// the underlying block contents. Returns `Option.none()` when no metadata key is
    /// present in the DHT (artifact absent). Used by the idempotent PUT path where the
    /// caller only needs size/hashes for the response body — paying the full
    /// `resolveWithMetadata` cost (block fan-out + SHA1 verification) would defeat the
    /// purpose of returning early on a duplicate upload.
    Promise<Option<ArtifactMetadata>> metadata(Artifact artifact);
    Promise<List<Version>> versions(GroupId groupId, ArtifactId artifactId);
    Promise<Unit> delete(Artifact artifact);
    Metrics metrics();

    record Metrics(int artifactCount, int chunkCount, long memoryBytes) {
        static final int CHUNK_SIZE = 64 * 1024;

        static Metrics metrics(int artifactCount, int chunkCount) {
            return new Metrics(artifactCount, chunkCount, (long) chunkCount * CHUNK_SIZE);
        }

        static Metrics empty() {
            return new Metrics(0, 0, 0);
        }
    }

    record DeployResult(Artifact artifact, long size, String md5, String sha1) {}

    record ArtifactMetadata(long size,
                            int chunkCount,
                            String md5,
                            String sha1,
                            long deployedAt,
                            List<String> blockIds) {
        public byte[] toBytes() {
            var data = size
                     + ":" + chunkCount
                     + ":" + md5
                     + ":" + sha1
                     + ":" + deployedAt
                     + ":" + String.join(",", blockIds);

            return data.getBytes(StandardCharsets.UTF_8);
        }

        public static Option<ArtifactMetadata> fromBytes(byte[] bytes) {
            return parseMetadataBytes(bytes);
        }

        private static Option<ArtifactMetadata> parseMetadataBytes(byte[] bytes) {
            try {
                var parts = new String(bytes, StandardCharsets.UTF_8).split(":");

                if (parts.length != 6) {
                    return none();
                }

                var ids = List.of(parts[5].split(","));

                return some(new ArtifactMetadata(Long.parseLong(parts[0]),
                                                 Integer.parseInt(parts[1]),
                                                 parts[2],
                                                 parts[3],
                                                 Long.parseLong(parts[4]),
                                                 ids));
            } catch (Exception e) {
                return none();
            }
        }
    }

    sealed interface ArtifactStoreError extends Cause {
        record NotFound(Artifact artifact) implements ArtifactStoreError {
            @Override
            public String message() {
                return "Artifact not found: " + artifact.asString();
            }
        }

        record DeployFailed(Artifact artifact, String reason) implements ArtifactStoreError {
            @Override
            public String message() {
                return "Failed to deploy " + artifact.asString() + ": " + reason;
            }
        }

        record ResolveFailed(Artifact artifact, String reason) implements ArtifactStoreError {
            @Override
            public String message() {
                return "Failed to resolve " + artifact.asString() + ": " + reason;
            }
        }

        record CorruptedArtifact(Artifact artifact) implements ArtifactStoreError {
            @Override
            public String message() {
                return "Corrupted artifact: " + artifact.asString();
            }
        }
    }

    static ArtifactStore artifactStore(DHTClient dht, StorageInstance storage) {
        return new ArtifactStoreImpl(dht, storage);
    }

    /// Variant that overrides the bounded retry policy explicitly instead of inheriting it
    /// from `dht.config()`. Used by tests that drive transient-failure scenarios with a
    /// deterministic attempt count and short `TimeSpan` backoff.
    static ArtifactStore artifactStore(DHTClient dht, StorageInstance storage, DHTConfig.DhtRetryPolicy retryPolicy) {
        return new ArtifactStoreImpl(dht, storage, retryPolicy);
    }

    /// Variant that overrides the resolve fan-out aggregate timeout explicitly. Used by tests
    /// that drive the resolve-deadline firing with a short `TimeSpan` against a never-resolving
    /// block read, deterministically and without waiting the production budget.
    static ArtifactStore artifactStore(DHTClient dht,
                                       StorageInstance storage,
                                       TimeSpan resolveBase,
                                       TimeSpan resolvePerChunk,
                                       TimeSpan resolveCeiling) {
        return new ArtifactStoreImpl(dht, storage, resolveBase, resolvePerChunk, resolveCeiling);
    }
}

class ArtifactStoreImpl implements ArtifactStore {
    private static final Logger log = LoggerFactory.getLogger(ArtifactStoreImpl.class);
    private static final int CHUNK_SIZE = 64 * 1024;
    /// Upper bound on the number of chunk puts/gets kept in flight at once during the
    /// deploy and resolve fan-outs. 8 × 64KB `CHUNK_SIZE` = 512KB of in-flight payload on
    /// the per-peer dedicated DHT QUIC lane — comfortably under that lane's 1MB write-buffer
    /// high-watermark, so neither the outbound request writes (deploy puts) nor the inbound
    /// response writes (resolve gets) drive the peer channel to `isWritable=false`. Empirically,
    /// an unbounded fan-out of a ≥1MB artifact (16-80 chunks) bursts the lane past the
    /// watermark → backpressure → fast-fail/drop → cross-node resolve hangs to the 30s
    /// `operationTimeout`. Bounding the in-flight count keeps the buffer under the watermark so
    /// reads/writes complete. Intentionally a constant (not configurable): a derived 1MB/2 budget
    /// over a fixed 64KB chunk size; no speculative config surface.
    private static final int MAX_CONCURRENT_CHUNKS = 8;
    /// Aggregate timeout for `deploy()` chunk fan-out. `DistributedDHTClient.put` has its
    /// own 10s per-chunk timeout; this caps the worst-case dominator chunk of `Promise.allOf`
    /// plus the downstream metadata/versions writes so the HTTP request returns rather than
    /// stacking 30s+ of tail latency. Calibrated for ~16-chunk 1MB artifacts.
    private static final TimeSpan DEPLOY_TIMEOUT = timeSpan(30).seconds();
    /// Aggregate timeout for the FULL `resolveWithMetadata()` pipeline (metadata read +
    /// chunk fan-out + SHA1 verification). The symmetric counterpart to `DEPLOY_TIMEOUT`:
    /// `deploy()` was bounded but `resolve()` had NO aggregate timeout, so a single block
    /// `get` whose durable DHT tier never answers (the Hetzner 3h hang: a quorum read against
    /// an unwritable/unreachable peer where `writeIfWritable` silently dropped and no read
    /// response ever arrived) blocked the chain forever — the bounded read RETRY only fires on
    /// a delivered transient FAILURE, never on a never-resolving promise. Without this bound the
    /// HTTP handler's resolution promise (and its connection) leaks indefinitely.
    ///
    /// Chunk-count scaled, following the `DEPLOY_TIMEOUT` idiom but bounded both ways: a
    /// `resolveBase` floor covers the metadata read + verification of small artifacts, plus
    /// `resolvePerChunk` per block to absorb fan-out tail latency on large multi-chunk
    /// artifacts, capped at `resolveCeiling` so a corrupt/huge declared chunk count cannot
    /// produce an unbounded budget. For the unknown-chunk-count metadata-read leg the floor
    /// applies; once chunk count is known the scaled budget governs the block fan-out. Held as
    /// fields (defaulting to these constants) so tests can drive the deadline with a short span.
    private static final TimeSpan DEFAULT_RESOLVE_BASE = timeSpan(15).seconds();
    private static final TimeSpan DEFAULT_RESOLVE_PER_CHUNK = timeSpan(2).seconds();
    private static final TimeSpan DEFAULT_RESOLVE_CEILING = timeSpan(120).seconds();

    /// Bounded retry for transient DHT-resilience failures inside `deploy` AND `resolve`.
    /// The DHT-resilience layer (`dht-resilience-spec.md`) converts a transient QUIC
    /// backpressure refusal — or a read that targets a still-JOINING peer — into a
    /// synchronous `QuorumCollector.onFailure`, which is correct for Rabia/consensus
    /// (built-in retransmit) but wrong for one-shot `ArtifactStore` operations, which have
    /// no retransmit cycle of their own. Without this retry, a single backpressured or
    /// unreachable replica on ANY chunk, on the metadata key, or on the resolve read
    /// surfaces as HTTP 500 (write) or a 30s `operationTimeout` hang (read) on the client's
    /// request. The probability of hitting this scales with chunk count, so multi-chunk
    /// artifacts (≥1MB) are especially vulnerable.
    ///
    /// Applied to writes (`dhtPutWithRetry`, `storagePutWithRetry`) and reads
    /// (`dhtGetWithRetry`, `storageGetWithRetry`). Attempts and `TimeSpan` backoff come
    /// from `DHTConfig.DhtRetryPolicy` (default 3 attempts, 100/250/500ms) — no hardcoded
    /// millis literals here.
    ///
    /// Selective retry: only retries on `DHTError.PeerUnreachable` and
    /// `DHTError.QuorumNotReached`. Other causes (NO_AVAILABLE_NODES, corruption)
    /// propagate immediately so genuine failures aren't masked. On the read path an
    /// `Option.empty()` (legitimate "not found") is NEVER retried — only a transient
    /// FAILURE triggers a retry.
    private final DhtRetryPolicy retryPolicy;
    private final DHTClient dht;
    private final StorageInstance storage;
    private final TimeSpan resolveBase;
    private final TimeSpan resolvePerChunk;
    private final TimeSpan resolveCeiling;
    private final AtomicInteger artifactCount = new AtomicInteger(0);
    private final AtomicInteger chunkCount = new AtomicInteger(0);

    ArtifactStoreImpl(DHTClient dht, StorageInstance storage) {
        this(dht,
             storage,
             dht.config().retryPolicy());
    }

    ArtifactStoreImpl(DHTClient dht, StorageInstance storage, DhtRetryPolicy retryPolicy) {
        this(dht, storage, retryPolicy, DEFAULT_RESOLVE_BASE, DEFAULT_RESOLVE_PER_CHUNK, DEFAULT_RESOLVE_CEILING);
    }

    ArtifactStoreImpl(DHTClient dht,
                      StorageInstance storage,
                      TimeSpan resolveBase,
                      TimeSpan resolvePerChunk,
                      TimeSpan resolveCeiling) {
        this(dht,
             storage,
             dht.config().retryPolicy(),
             resolveBase,
             resolvePerChunk,
             resolveCeiling);
    }

    ArtifactStoreImpl(DHTClient dht,
                      StorageInstance storage,
                      DhtRetryPolicy retryPolicy,
                      TimeSpan resolveBase,
                      TimeSpan resolvePerChunk,
                      TimeSpan resolveCeiling) {
        this.dht = dht;
        this.storage = storage;
        this.retryPolicy = retryPolicy;
        this.resolveBase = resolveBase;
        this.resolvePerChunk = resolvePerChunk;
        this.resolveCeiling = resolveCeiling;
    }

    @Override
    public Metrics metrics() {
        return Metrics.metrics(artifactCount.get(), chunkCount.get());
    }

    @Override
    public Promise<DeployResult> deploy(Artifact artifact, byte[] content) {
        log.info("Deploying artifact: {} ({} bytes)", artifact.asString(), content.length);
        var md5 = computeHash(content, "MD5");
        var sha1 = computeHash(content, "SHA-1");
        var chunks = splitIntoChunks(content);
        // Aggregate timeout on the FULL deploy pipeline (chunk fan-out + metadata + versions
        // list writes — the latter two each issue their own DHT puts). The previous timeout
        // placement only bound the local `storage::put` fan-out, which is fast and never
        // stalls; the actual DHT operations happen inside `storeMetadataAndVersions`. A
        // single failing-to-quorum DHT write (e.g. when a peer's QUIC channel is unwritable
        // due to backpressure and `writeIfWritable` silently drops) blocks the chain
        // indefinitely. The 30s bound at the outer level guarantees the HTTP handler
        // resolves with success or failure before the test harness's curl times out.
        // CORRECTNESS: boundedFanOut preserves chunk order — blockIds are recorded into
        // metadata in chunk order and reassembled in that order on resolve; reordering
        // corrupts the artifact.
        return boundedFanOut(chunks, MAX_CONCURRENT_CHUNKS, this::storagePutWithRetry).flatMap(blockIds -> storeMetadataAndVersions(artifact,
                                                                                                                                    blockIds,
                                                                                                                                    chunks.size(),
                                                                                                                                    md5,
                                                                                                                                    sha1,
                                                                                                                                    content.length))
                            .timeout(DEPLOY_TIMEOUT);
    }

    @Override
    public Promise<byte[]> resolve(Artifact artifact) {
        return resolveWithMetadata(artifact).map(ResolvedArtifact::content);
    }

    @Override
    public Promise<ResolvedArtifact> resolveWithMetadata(Artifact artifact) {
        log.debug("Resolving artifact: {}", artifact.asString());
        // Aggregate timeout on the metadata-read leg (chunk count not yet known here, so the
        // resolveBase floor applies). The block fan-out leg is bounded separately in
        // resolveChunksFromStorage with a chunk-count-scaled budget. Placed early per
        // Promise.timeout's contract so a never-resolving dht.get is cancelled rather than a
        // downstream transformation.
        return dhtGetWithRetry(metaKey(artifact)).timeout(resolveBase)
                              .flatMap(metaOpt -> metaOpt.flatMap(ArtifactMetadata::fromBytes)
                                                         .async(new ArtifactStoreError.NotFound(artifact))
                                                         .flatMap(meta -> resolveChunksFromStorage(artifact, meta)));
    }

    @Override
    public Promise<Boolean> exists(Artifact artifact) {
        return dht.exists(metaKey(artifact));
    }

    @Override
    public Promise<Option<ArtifactMetadata>> metadata(Artifact artifact) {
        return dht.get(metaKey(artifact))
                  .map(opt -> opt.flatMap(ArtifactMetadata::fromBytes));
    }

    @Override
    public Promise<List<Version>> versions(GroupId groupId, ArtifactId artifactId) {
        var versionsKey = versionsKey(groupId, artifactId);

        return dht.get(versionsKey)
                  .map(opt -> opt.map(this::parseVersionsList)
                                 .or(List.of()));
    }

    @Override
    public Promise<Unit> delete(Artifact artifact) {
        log.info("Deleting artifact: {}", artifact.asString());

        return dht.get(metaKey(artifact))
                  .flatMap(metaOpt -> metaOpt.flatMap(ArtifactMetadata::fromBytes)
                                             .map(meta -> deleteMetadata(artifact, meta))
                                             .or(Promise.unitPromise()));
    }

    private Promise<DeployResult> storeMetadataAndVersions(Artifact artifact,
                                                           List<BlockId> blockIds,
                                                           int chunkCount,
                                                           String md5,
                                                           String sha1,
                                                           int contentLength) {
        var hexIds = blockIds.stream().map(BlockId::hexString).toList();
        var metadata = new ArtifactMetadata(contentLength, chunkCount, md5, sha1, System.currentTimeMillis(), hexIds);

        return dhtPutWithRetry(metaKey(artifact),
                               metadata.toBytes()).flatMap(_ -> updateVersionsList(artifact))
                              .map(_ -> recordDeployMetrics(artifact, contentLength, chunkCount, md5, sha1));
    }

    private Promise<ResolvedArtifact> resolveChunksFromStorage(Artifact artifact, ArtifactMetadata meta) {
        var corruptedError = new ArtifactStoreError.CorruptedArtifact(artifact);
        // CORRECTNESS: boundedFanOut preserves blockId order — chunks are reassembled in
        // metadata order; reordering corrupts the artifact.
        // Chunk-count-scaled aggregate timeout on the block fan-out leg (placed early per
        // Promise.timeout's contract). A single never-resolving block get caps here rather
        // than hanging the whole resolve forever.
        return boundedFanOut(meta.blockIds(),
                             MAX_CONCURRENT_CHUNKS,
                             hex -> fetchSingleBlock(hex, corruptedError)).timeout(resolveTimeoutFor(meta.blockIds()
                                                                                                         .size()))
                            .map(blocks -> reassembleChunks(blocks,
                                                            (int) meta.size()))
                            .flatMap(content -> verifyIntegrity(artifact, content, meta));
    }

    /// Chunk-count-scaled resolve budget: `resolveBase` floor + `resolvePerChunk` per
    /// block, capped at `resolveCeiling`. Bounds the block fan-out tail latency without an
    /// unbounded budget for a corrupt/huge declared chunk count.
    private TimeSpan resolveTimeoutFor(int chunkCount) {
        var scaledMillis = resolveBase.millis() + (long) chunkCount * resolvePerChunk.millis();

        return timeSpan(Math.min(scaledMillis, resolveCeiling.millis())).millis();
    }

    /// Order-preserving, bounded, first-failure fan-out. Processes `items` through the async
    /// `op` with at most `maxInFlight` operations in flight at once by running consecutive
    /// sublists (batches) of size `maxInFlight` one after another: each batch fans out
    /// concurrently via `Promise.allOf` + `Result.firstFailureOf` (mirroring the original
    /// per-batch aggregation), and the next batch starts only after the current one resolves.
    ///
    /// CORRECTNESS: results are returned in input order — batches are consecutive sublists and
    /// their per-batch results (themselves in input order via `firstFailureOf`) are concatenated
    /// in batch order. Callers depend on this: deploy records blockIds in chunk order into
    /// metadata, and resolve reassembles chunks in metadata order; reordering corrupts the
    /// artifact.
    ///
    /// First-failure: on any batch failure the aggregate fails with that cause and no further
    /// batch is started (the recursion short-circuits through `flatMap`).
    private <I, R> Promise<List<R>> boundedFanOut(List<I> items, int maxInFlight, Function<I, Promise<R>> op) {
        return runBatchFrom(items, maxInFlight, op, 0, new ArrayList<>());
    }

    private <I, R> Promise<List<R>> runBatchFrom(List<I> items,
                                                 int maxInFlight,
                                                 Function<I, Promise<R>> op,
                                                 int start,
                                                 List<R> accumulated) {
        if (start >= items.size()) {
            return Promise.success(List.copyOf(accumulated));
        }

        var end = Math.min(start + maxInFlight, items.size());
        var batchPromises = items.subList(start, end).stream().map(op).toList();

        return Promise.allOf(batchPromises)
                      .flatMap(results -> Result.firstFailureOf(results).async())
                      .flatMap(batchResults -> runBatchFrom(items,
                                                            maxInFlight,
                                                            op,
                                                            end,
                                                            concat(accumulated, batchResults)));
    }

    private static <R> List<R> concat(List<R> head, List<R> tail) {
        var combined = new ArrayList<R>(head.size() + tail.size());

        combined.addAll(head);
        combined.addAll(tail);

        return combined;
    }

    private Promise<byte[]> fetchSingleBlock(String hex, ArtifactStoreError.CorruptedArtifact error) {
        return BlockId.fromHex(hex)
                      .async()
                      .flatMap(this::storageGetWithRetry)
                      .flatMap(opt -> opt.async(error));
    }

    private Promise<ResolvedArtifact> verifyIntegrity(Artifact artifact, byte[] content, ArtifactMetadata meta) {
        var computedSha1 = computeHash(content, "SHA-1");

        if (!computedSha1.equals(meta.sha1())) {
            log.error("Integrity verification failed for {}: expected SHA1={}, computed={}",
                      artifact.asString(),
                      meta.sha1(),
                      computedSha1);

            return new ArtifactStoreError.CorruptedArtifact(artifact).promise();
        }

        log.debug("Integrity verified for {}: SHA1={}", artifact.asString(), computedSha1);

        return Promise.success(new ResolvedArtifact(content, meta));
    }

    private Promise<Unit> deleteMetadata(Artifact artifact, ArtifactMetadata meta) {
        return dht.remove(metaKey(artifact))
                  .map(_ -> recordDeleteMetrics(meta));
    }

    private Promise<Unit> updateVersionsList(Artifact artifact) {
        var versionsKey = versionsKey(artifact.groupId(), artifact.artifactId());

        return dht.get(versionsKey)
                  .map(opt -> addVersionIfAbsent(opt,
                                                 artifact.version()))
                  .flatMap(versions -> dhtPutWithRetry(versionsKey,
                                                       serializeVersionsList(versions)));
    }

    private Promise<Unit> dhtPutWithRetry(byte[] key, byte[] value) {
        return dhtPutWithRetry(key, value, 0);
    }

    private Promise<Unit> dhtPutWithRetry(byte[] key, byte[] value, int attempt) {
        var result = Promise.<Unit> promise();

        dht.put(key, value)
           .onResult(r -> r.onSuccess(_ -> result.resolve(r))
                           .onFailure(cause -> handlePutFailure(key, value, attempt, cause, result)));

        return result;
    }

    private void handlePutFailure(byte[] key, byte[] value, int attempt, Cause cause, Promise<Unit> result) {
        var nextAttempt = attempt + 1;

        if (!isTransientDhtFailure(cause) || nextAttempt >= retryPolicy.maxAttempts()) {
            result.fail(cause);

            return;
        }

        var backoff = retryPolicy.backoffFor(attempt);

        log.warn("DHT put attempt {} of {} failed (transient): {}; retrying after {}ms",
                 nextAttempt,
                 retryPolicy.maxAttempts(),
                 cause.message(),
                 backoff.millis());
        SharedScheduler.schedule(() -> dhtPutWithRetry(key, value, nextAttempt).onResult(result::resolve), backoff);
    }

    private static boolean isTransientDhtFailure(Cause cause) {
        return cause instanceof DHTError.PeerUnreachable || cause instanceof DHTError.QuorumNotReached;
    }

    /// Bounded retry for the DHT READ/resolve path. Mirrors `dhtPutWithRetry` but guards
    /// the quorum-read that `DistributedDHTClient.get` performs: a read targeting a
    /// still-JOINING peer surfaces as a transient `QuorumNotReached`/`PeerUnreachable`
    /// that otherwise hangs until the 30s `operationTimeout`, manifesting as an HTTP 500
    /// on `/api/blueprints/deploy`. CRITICAL: a successful `Option.empty()` is a legitimate
    /// "not found" and is NEVER retried — only a transient FAILURE triggers a retry.
    private Promise<Option<byte[]>> dhtGetWithRetry(byte[] key) {
        return dhtGetWithRetry(key, 0);
    }

    private Promise<Option<byte[]>> dhtGetWithRetry(byte[] key, int attempt) {
        var result = Promise.<Option<byte[]>> promise();

        dht.get(key)
           .onResult(r -> r.onSuccess(_ -> result.resolve(r))
                           .onFailure(cause -> handleGetFailure(key, attempt, cause, result)));

        return result;
    }

    private void handleGetFailure(byte[] key, int attempt, Cause cause, Promise<Option<byte[]>> result) {
        var nextAttempt = attempt + 1;

        if (!isTransientDhtFailure(cause) || nextAttempt >= retryPolicy.maxAttempts()) {
            result.fail(cause);

            return;
        }

        var backoff = retryPolicy.backoffFor(attempt);

        log.warn("DHT get attempt {} of {} failed (transient): {}; retrying after {}ms",
                 nextAttempt,
                 retryPolicy.maxAttempts(),
                 cause.message(),
                 backoff.millis());
        SharedScheduler.schedule(() -> dhtGetWithRetry(key, nextAttempt).onResult(result::resolve), backoff);
    }

    /// Per-chunk read variant of `dhtGetWithRetry` for the resolve fan-out. The artifact
    /// `storage` instance's durable tier is the DHT, so `storage.get` propagates the same
    /// transient `PeerUnreachable`/`QuorumNotReached` failures as direct `dht.get`. A
    /// successful `Option.empty()` (chunk genuinely absent) is treated by the caller as a
    /// corruption signal and is NEVER retried here — only transient FAILURE retries.
    private Promise<Option<byte[]>> storageGetWithRetry(BlockId id) {
        return storageGetWithRetry(id, 0);
    }

    private Promise<Option<byte[]>> storageGetWithRetry(BlockId id, int attempt) {
        var result = Promise.<Option<byte[]>> promise();

        storage.get(id)
               .onResult(r -> r.onSuccess(_ -> result.resolve(r))
                               .onFailure(cause -> handleStorageGetFailure(id, attempt, cause, result)));

        return result;
    }

    private void handleStorageGetFailure(BlockId id, int attempt, Cause cause, Promise<Option<byte[]>> result) {
        var nextAttempt = attempt + 1;

        if (!isTransientDhtFailure(cause) || nextAttempt >= retryPolicy.maxAttempts()) {
            result.fail(cause);

            return;
        }

        var backoff = retryPolicy.backoffFor(attempt);

        log.warn("Storage chunk get attempt {} of {} failed (transient): {}; retrying after {}ms",
                 nextAttempt,
                 retryPolicy.maxAttempts(),
                 cause.message(),
                 backoff.millis());
        SharedScheduler.schedule(() -> storageGetWithRetry(id, nextAttempt).onResult(result::resolve), backoff);
    }

    /// Per-chunk variant of `dhtPutWithRetry` for the deploy fan-out. The artifact
    /// `storage` instance's durable tier is the DHT (see `StorageFactory.createAll`'s synthesized
    /// default `artifacts` instance), so `storage.put` propagates the same transient
    /// `PeerUnreachable`/`QuorumNotReached` failures as direct `dht.put`. Chunk writes are
    /// content-addressed (BlockId is the chunk's hash), so retrying is idempotent at the storage
    /// layer.
    private Promise<BlockId> storagePutWithRetry(byte[] chunk) {
        return storagePutWithRetry(chunk, 0);
    }

    private Promise<BlockId> storagePutWithRetry(byte[] chunk, int attempt) {
        var result = Promise.<BlockId> promise();

        storage.put(chunk)
               .onResult(r -> r.onSuccess(_ -> result.resolve(r))
                               .onFailure(cause -> handleStoragePutFailure(chunk, attempt, cause, result)));

        return result;
    }

    private void handleStoragePutFailure(byte[] chunk, int attempt, Cause cause, Promise<BlockId> result) {
        var nextAttempt = attempt + 1;

        if (!isTransientDhtFailure(cause) || nextAttempt >= retryPolicy.maxAttempts()) {
            result.fail(cause);

            return;
        }

        var backoff = retryPolicy.backoffFor(attempt);

        log.warn("Storage chunk put attempt {} of {} failed (transient): {}; retrying after {}ms",
                 nextAttempt,
                 retryPolicy.maxAttempts(),
                 cause.message(),
                 backoff.millis());
        SharedScheduler.schedule(() -> storagePutWithRetry(chunk, nextAttempt).onResult(result::resolve), backoff);
    }

    private List<Version> addVersionIfAbsent(Option<byte[]> existingData, Version version) {
        var versions = new ArrayList<>(existingData.map(this::parseVersionsList).or(List.of()));

        if (!versions.contains(version)) {
            versions.add(version);
        }

        return versions;
    }

    @Contract
    private List<Version> parseVersionsList(byte[] data) {
        var str = new String(data, StandardCharsets.UTF_8);

        if (str.isEmpty()) {
            return new ArrayList<>();
        }

        var versions = new ArrayList<Version>();

        for (var v : str.split(",")) {
            Version.version(v).onSuccess(versions::add);
        }

        return versions;
    }

    private byte[] serializeVersionsList(List<Version> versions) {
        var str = versions.stream().map(Version::withQualifier).collect(Collectors.joining(","));

        return str.getBytes(StandardCharsets.UTF_8);
    }

    private DeployResult recordDeployMetrics(Artifact artifact,
                                             int contentLength,
                                             int chunks,
                                             String md5,
                                             String sha1) {
        artifactCount.incrementAndGet();
        chunkCount.addAndGet(chunks);
        log.info("Deployed artifact: {} ({} chunks)", artifact.asString(), chunks);

        return new DeployResult(artifact, contentLength, md5, sha1);
    }

    private Unit recordDeleteMetrics(ArtifactMetadata meta) {
        artifactCount.decrementAndGet();
        chunkCount.addAndGet(-meta.chunkCount());

        return unit();
    }

    private byte[] metaKey(Artifact artifact) {
        var key = "artifacts/" + artifact.groupId().id()
                + "/" + artifact.artifactId().id()
                + "/" + artifact.version().withQualifier()
                + "/meta";

        return key.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] versionsKey(GroupId groupId, ArtifactId artifactId) {
        var key = "artifacts/" + groupId.id() + "/" + artifactId.id() + "/versions";

        return key.getBytes(StandardCharsets.UTF_8);
    }

    private List<byte[]> splitIntoChunks(byte[] content) {
        var chunks = new ArrayList<byte[]>();
        int offset = 0;

        while (offset < content.length) {
            int length = Math.min(CHUNK_SIZE, content.length - offset);
            var chunk = new byte[length];

            System.arraycopy(content, offset, chunk, 0, length);
            chunks.add(chunk);
            offset += length;
        }

        return chunks;
    }

    private byte[] reassembleChunks(List<byte[]> chunks, int totalSize) {
        var result = new byte[totalSize];
        int offset = 0;

        for (var chunk : chunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.length);
            offset += chunk.length;
        }

        return result;
    }

    private String computeHash(byte[] content, String algorithm) {
        try {
            var md = MessageDigest.getInstance(algorithm);
            var hash = md.digest(content);

            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return "";
        }
    }
}
