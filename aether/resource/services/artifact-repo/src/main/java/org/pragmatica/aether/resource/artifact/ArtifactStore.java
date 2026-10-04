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
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactId;
import org.pragmatica.aether.artifact.GroupId;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactContentValue;
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
import org.pragmatica.lang.io.CoreError;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;


public interface ArtifactStore {
    /// Every artifact-store DHT key (metadata, file list, version list) starts with this.
    String KEY_PREFIX = "artifacts/";
    /// Only a file's metadata key ends with this. The file-list and version-list keys share [#KEY_PREFIX] but are
    /// routinely absent (a first deploy reads them before writing), so they must not WARN.
    String METADATA_KEY_SUFFIX = "/meta";

    /// Whether a hex-encoded DHT key is an artifact's METADATA key: the one key whose absence means "this artifact
    /// is not deployed". Total on any string: it compares in hex space, never decoding.
    static boolean isArtifactMetadataKeyHex(String keyHex) {
        var hex = HexFormat.of();

        return keyHex.startsWith(hex.formatHex(KEY_PREFIX.getBytes(StandardCharsets.UTF_8))) && keyHex.endsWith(hex.formatHex(METADATA_KEY_SUFFIX.getBytes(StandardCharsets.UTF_8)));
    }

    /// The store is keyed per FILE (#281): a coordinate's jar, pom and classified files are
    /// distinct entries. The `Artifact`-typed operations address the coordinate's PRIMARY file
    /// (`<artifactId>-<version>.jar`), which is what slice resolution means by "the artifact".
    Promise<DeployResult> deploy(ArtifactFile file, byte[] content);
    Promise<byte[]> resolve(ArtifactFile file);
    Promise<ResolvedArtifact> resolveWithMetadata(ArtifactFile file);
    Promise<Boolean> exists(ArtifactFile file);
    /// Archives the whole version `artifact` belongs to (#1778): its files stop resolving and the version
    /// leaves the versions list, but every key is KEPT, so "absent" still means "never written" and no
    /// replica can resurrect the version. Allowed once the version has been stored for at least the
    /// retention period; archiving an archived version is a no-op.
    Promise<Unit> archive(Artifact artifact);

    default Promise<DeployResult> deploy(Artifact artifact, byte[] content) {
        return deploy(ArtifactFile.primary(artifact), content);
    }

    default Promise<byte[]> resolve(Artifact artifact) {
        return resolve(ArtifactFile.primary(artifact));
    }

    default Promise<ResolvedArtifact> resolveWithMetadata(Artifact artifact) {
        return resolveWithMetadata(ArtifactFile.primary(artifact));
    }

    default Promise<Boolean> exists(Artifact artifact) {
        return exists(ArtifactFile.primary(artifact));
    }

    default Promise<Option<ArtifactMetadata>> metadata(Artifact artifact) {
        return metadata(ArtifactFile.primary(artifact));
    }

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

    /// Fetch persisted metadata for an artifact WITHOUT reading or integrity-verifying
    /// the underlying block contents. Returns `Option.none()` when no metadata key is
    /// present in the DHT (artifact absent). It does NOT consult the archive marker: an archived
    /// file's metadata is still returned, because archiving keeps the key.
    Promise<Option<ArtifactMetadata>> metadata(ArtifactFile file);
    Promise<List<Version>> versions(GroupId groupId, ArtifactId artifactId);
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

    /// `alreadyPresent` is true when the coordinate already held identical content (an idempotent re-put):
    /// nothing was uploaded.
    record DeployResult(Artifact artifact, long size, String md5, String sha1, boolean alreadyPresent) {
        public DeployResult(Artifact artifact, long size, String md5, String sha1) {
            this(artifact, size, md5, sha1, false);
        }
    }

    /// Minimum time a version must have been stored before it may be archived. Seven days by owner ruling
    /// (#1778, 2026-10-02); configurable per cluster with `[slice] artifact_archive_retention`. Nothing frees the
    /// archived bytes yet, so a longer period costs nothing today.
    record ArchivePolicy(TimeSpan minimumRetention) {
        public static final ArchivePolicy DEFAULT = new ArchivePolicy(timeSpan(7).days());

        public static ArchivePolicy archivePolicy(TimeSpan minimumRetention) {
            return new ArchivePolicy(minimumRetention);
        }
    }

    record ArtifactMetadata(long size,
                            int chunkCount,
                            String md5,
                            String sha1,
                            String sha256,
                            long deployedAt,
                            List<String> blockIds) {
        public byte[] toBytes() {
            var data = size
                     + ":" + chunkCount
                     + ":" + md5
                     + ":" + sha1
                     + ":" + sha256
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

                if (parts.length != 7) {
                    return none();
                }

                var ids = List.of(parts[6].split(","));

                return some(new ArtifactMetadata(Long.parseLong(parts[0]),
                                                 Integer.parseInt(parts[1]),
                                                 parts[2],
                                                 parts[3],
                                                 parts[4],
                                                 Long.parseLong(parts[5]),
                                                 ids));
            } catch (Exception e) {
                return none();
            }
        }
    }

    sealed interface ArtifactStoreError extends Cause {
        /// The DHT answered "no metadata" for the key. `keyHex` joins this line to the DHT client's
        /// all-miss report for the same key, which says whether the key is lost or merely unreachable.
        record NotFound(ArtifactFile file, String keyHex, long elapsedMillis) implements ArtifactStoreError {
            @Override
            public String message() {
                return "Artifact not found: " + file.asString()
                     + " (dht key " + keyHex
                     + ", outcome=dht-returned-empty, elapsedMs=" + elapsedMillis
                     + ")";
            }
        }

        /// The DHT returned bytes for the metadata key but they do not parse. The key IS present, so this
        /// is deliberately not [NotFound]: corruption must not read as absence.
        record MetadataUnparseable(ArtifactFile file, String keyHex) implements ArtifactStoreError {
            @Override
            public String message() {
                return "Artifact metadata unparseable: " + file.asString() + " (dht key " + keyHex + ")";
            }
        }

        record DeployFailed(ArtifactFile file, String reason) implements ArtifactStoreError {
            @Override
            public String message() {
                return "Failed to deploy " + file.asString() + ": " + reason;
            }
        }

        record ResolveFailed(ArtifactFile file, String reason) implements ArtifactStoreError {
            @Override
            public String message() {
                return "Failed to resolve " + file.asString() + ": " + reason;
            }
        }

        record CorruptedArtifact(ArtifactFile file) implements ArtifactStoreError {
            @Override
            public String message() {
                return "Corrupted artifact: " + file.asString();
            }
        }

        /// A coordinate is written once (#1778): the stored content and the offered content differ. Names
        /// both SHA-1 and SHA-256 digests so the operator can tell which of the two is the one they meant.
        record ContentConflict(ArtifactFile file, ArtifactContentValue stored, ArtifactContentValue offered) implements ArtifactStoreError {
            @Override
            public String message() {
                return "Artifact " + file.asString()
                     + " is already stored with different content (" + differences()
                     + "); a coordinate is written once, publish the new content under a new version";
            }

            /// Only what actually differs, stored first: a conflict that names the same digest twice says nothing.
            private String differences() {
                var parts = new ArrayList<String>();

                addIfDifferent(parts,
                               "size",
                               String.valueOf(stored.size()),
                               String.valueOf(offered.size()));
                addIfDifferent(parts, "md5", stored.md5(), offered.md5());
                addIfDifferent(parts, "sha1", stored.sha1(), offered.sha1());
                addIfDifferent(parts, "sha256", stored.sha256(), offered.sha256());

                return String.join(", ", parts);
            }

            private static void addIfDifferent(List<String> parts,
                                               String name,
                                               String storedValue,
                                               String offeredValue) {
                if (!storedValue.equals(offeredValue)) {
                    parts.add(name + ": stored=" + storedValue + ", offered=" + offeredValue);
                }
            }
        }

        /// SNAPSHOT versions are mutable by definition, so the built-in store, whose every coordinate is
        /// immutable, does not take them (#1778). The Local repository still serves them in development.
        record SnapshotRefused(ArtifactFile file) implements ArtifactStoreError {
            @Override
            public String message() {
                return "SNAPSHOT versions are not accepted by the built-in artifact store: " + file.asString()
                     + "; publish a release version (SNAPSHOTs stay available through the Local repository)";
            }
        }

        /// The version was archived: it neither resolves nor takes new files, and its coordinates are never
        /// reused (#1778).
        record Archived(ArtifactFile file) implements ArtifactStoreError {
            @Override
            public String message() {
                return "Artifact " + file.asString() + " is archived and unavailable";
            }
        }

        /// Nothing was ever stored for this version, so there is nothing to archive.
        record VersionNotFound(Artifact artifact) implements ArtifactStoreError {
            @Override
            public String message() {
                return "No stored files for " + artifact.asString() + "; nothing to archive";
            }
        }

        /// The artifact already holds its maximum number of present (not archived) versions, so a NEW version is
        /// refused (#1778). Existing versions are untouched; archiving old ones (allowed after the retention period)
        /// frees room, or the bound `[slice] artifact_max_versions` can be raised.
        record VersionLimitReached(Artifact artifact, int limit) implements ArtifactStoreError {
            @Override
            public String message() {
                return "Cannot publish " + artifact.asString()
                     + ": " + artifact.groupId()
                                      .id()
                     + ":" + artifact.artifactId()
                                     .id()
                     + " already has " + limit
                     + " present versions (the limit); archive old versions"
                     + " (allowed once they are past the retention period) or raise [slice] artifact_max_versions";
            }
        }

        /// The version is younger than the archive policy's minimum retention.
        record RetentionNotElapsed(Artifact artifact, long ageMillis, long retentionMillis) implements ArtifactStoreError {
            @Override
            public String message() {
                return "Cannot archive " + artifact.asString()
                     + " yet: stored for " + ageMillis
                     + " ms, minimum retention is " + retentionMillis
                     + " ms";
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

    /// Variant that overrides the archive policy (default [ArchivePolicy#DEFAULT]).
    static ArtifactStore artifactStore(DHTClient dht, StorageInstance storage, ArchivePolicy archivePolicy) {
        return new ArtifactStoreImpl(dht, storage, archivePolicy, System::currentTimeMillis);
    }

    /// The cluster variant: the versions of each artifact live in `versionIndex`, which a node backs with the
    /// consensus KV plane so concurrent publishes are serialized (#1778). The artifact bytes and per-version
    /// metadata stay in the DHT.
    static ArtifactStore artifactStore(DHTClient dht,
                                       StorageInstance storage,
                                       ArchivePolicy archivePolicy,
                                       ArtifactVersionIndex versionIndex) {
        return new ArtifactStoreImpl(dht, storage, archivePolicy, System::currentTimeMillis, versionIndex);
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
    static final TimeSpan DEFAULT_RESOLVE_BASE = timeSpan(15).seconds();
    static final TimeSpan DEFAULT_RESOLVE_PER_CHUNK = timeSpan(2).seconds();
    static final TimeSpan DEFAULT_RESOLVE_CEILING = timeSpan(120).seconds();
    /// Deploy time reported for a file that is absent or unreadable: larger than any real time, so it never
    /// wins the minimum and a version with only such files reads as "nothing stored".
    private static final long NOT_STORED = Long.MAX_VALUE;

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
    private final Consumer<String> readFailureSink;
    private final ArchivePolicy archivePolicy;
    private final LongSupplier clock;
    private final ArtifactVersionIndex versionIndex;
    /// Serializes this node's read-merge-write rewrites of the per-version file list in the DHT.
    private final KeyedSequencer sequencer = new KeyedSequencer();
    private final AtomicInteger artifactCount = new AtomicInteger(0);
    private final AtomicInteger chunkCount = new AtomicInteger(0);

    ArtifactStoreImpl(DHTClient dht, StorageInstance storage, ArchivePolicy archivePolicy, LongSupplier clock) {
        this(dht, storage, archivePolicy, clock, ArtifactVersionIndex.inMemory());
    }

    ArtifactStoreImpl(DHTClient dht,
                      StorageInstance storage,
                      ArchivePolicy archivePolicy,
                      LongSupplier clock,
                      ArtifactVersionIndex versionIndex) {
        this(dht,
             storage,
             dht.config().retryPolicy(),
             DEFAULT_RESOLVE_BASE,
             DEFAULT_RESOLVE_PER_CHUNK,
             DEFAULT_RESOLVE_CEILING,
             log::warn,
             archivePolicy,
             clock,
             versionIndex);
    }

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
        this(dht, storage, retryPolicy, resolveBase, resolvePerChunk, resolveCeiling, log::warn);
    }

    /// `readFailureSink` receives the line for a metadata read that did not complete; production logs it at WARN.
    ArtifactStoreImpl(DHTClient dht,
                      StorageInstance storage,
                      DhtRetryPolicy retryPolicy,
                      TimeSpan resolveBase,
                      TimeSpan resolvePerChunk,
                      TimeSpan resolveCeiling,
                      Consumer<String> readFailureSink) {
        this(dht,
             storage,
             retryPolicy,
             resolveBase,
             resolvePerChunk,
             resolveCeiling,
             readFailureSink,
             ArchivePolicy.DEFAULT,
             System::currentTimeMillis,
             ArtifactVersionIndex.inMemory());
    }

    ArtifactStoreImpl(DHTClient dht,
                      StorageInstance storage,
                      DhtRetryPolicy retryPolicy,
                      TimeSpan resolveBase,
                      TimeSpan resolvePerChunk,
                      TimeSpan resolveCeiling,
                      Consumer<String> readFailureSink,
                      ArchivePolicy archivePolicy,
                      LongSupplier clock,
                      ArtifactVersionIndex versionIndex) {
        this.archivePolicy = archivePolicy;
        this.clock = clock;
        this.versionIndex = versionIndex;
        this.readFailureSink = readFailureSink;
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

    /// Write-once (#1778). The refusals run before any write: a SNAPSHOT version, an archived version, and a
    /// coordinate whose stored content differs from `content` (compared by size, MD5 and SHA-1). An identical
    /// re-put uploads nothing and answers `alreadyPresent`; it re-asserts the file and version registrations,
    /// so a first deploy that died between the metadata write and the list writes converges on retry.
    ///
    /// The existence check is a read, and a read that FAILS is not "absent": it fails the deploy, so DHT churn
    /// can never turn into an overwrite (#1795). The read is only a fast path for content that is already stored:
    /// the binding of the coordinate to its content is decided by the index (consensus in a cluster), which keeps
    /// the FIRST digest proposed, so two concurrent first writes of different content cannot both succeed.
    @Override
    public Promise<DeployResult> deploy(ArtifactFile file, byte[] content) {
        log.info("Deploying artifact: {} ({} bytes)", file.asString(), content.length);
        var md5 = computeHash(content, "MD5");
        var sha1 = computeHash(content, "SHA-1");
        var digest = new ArtifactContentValue(content.length,
                                              md5,
                                              sha1,
                                              computeHash(content, "SHA-256"),
                                              clock.getAsLong());
        // Aggregate timeout on the FULL deploy pipeline (checks + chunk fan-out + metadata + list writes —
        // each issues its own DHT operations). A single failing-to-quorum DHT write (e.g. when a peer's QUIC
        // channel is unwritable due to backpressure and `writeIfWritable` silently drops) blocks the chain
        // indefinitely; the 30s bound at the outer level guarantees the HTTP handler resolves with success or
        // failure before the test harness's curl times out.
        return rejectSnapshot(file).async()
                             .flatMap(_ -> rejectIfArchived(file))
                             .flatMap(_ -> readStoredMetadata(file))
                             .flatMap(stored -> stored.map(bytes -> acceptIdentical(file, bytes, digest))
                                                      .or(() -> bindThenWrite(file, content, digest)))
                             .timeout(DEPLOY_TIMEOUT);
    }

    private Result<ArtifactFile> rejectSnapshot(ArtifactFile file) {
        return VersionOrder.isSnapshot(file.artifact().version())
               ? new ArtifactStoreError.SnapshotRefused(file).result()
               : Result.success(file);
    }

    private Promise<Unit> rejectIfArchived(ArtifactFile file) {
        return isArchived(file.artifact()).flatMap(archived -> failIfArchived(file, archived));
    }

    private static Promise<Unit> failIfArchived(ArtifactFile file, boolean archived) {
        return archived
               ? new ArtifactStoreError.Archived(file).promise()
               : Promise.unitPromise();
    }

    /// Archived when EITHER the consensus-committed flag or the DHT marker says so. The flag is the authority: the
    /// marker is a DHT key on its own replica set, so a churn that loses it on every replica that answers must not
    /// resurrect the version. The marker still covers a node whose KV has not yet applied the flag. The flag is
    /// read first (a local read): a flagged version needs no DHT read, and a failed marker read surfaces with its
    /// own cause (a transient `NotCaughtUp` stays transient, so it answers 503, not 500).
    private Promise<Boolean> isArchived(Artifact artifact) {
        return versionIndex.isArchived(artifact)
                           .flatMap(flagged -> flagged
                                               ? Promise.success(true)
                                               : markerPresent(artifact));
    }

    private Promise<Boolean> markerPresent(Artifact artifact) {
        return dhtGetWithRetry(archivedKey(artifact)).map(Option::isPresent);
    }

    private Promise<Option<byte[]>> readStoredMetadata(ArtifactFile file) {
        return dhtGetWithRetry(metaKey(file));
    }

    /// Identical content is idempotent; different content is [ArtifactStoreError.ContentConflict]. Bytes that
    /// are present but do not parse are corruption and are never overwritten.
    private Promise<DeployResult> acceptIdentical(ArtifactFile file, byte[] storedBytes, ArtifactContentValue offered) {
        return ArtifactMetadata.fromBytes(storedBytes)
                               .async(new ArtifactStoreError.MetadataUnparseable(file,
                                                                                 keyHex(file)))
                               .flatMap(stored -> acceptIfSame(file, stored, offered));
    }

    private Promise<DeployResult> acceptIfSame(ArtifactFile file,
                                               ArtifactMetadata stored,
                                               ArtifactContentValue offered) {
        return sameContent(stored, offered)
               ? reassertRegistration(file, stored)
               : new ArtifactStoreError.ContentConflict(file,
                                                        new ArtifactContentValue(stored.size(),
                                                                                 stored.md5(),
                                                                                 stored.sha1(),
                                                                                 stored.sha256(),
                                                                                 stored.deployedAt()),
                                                        offered).promise();
    }

    /// Size, MD5, SHA-1 AND SHA-256 must all match: a collision of the two old digests is not enough to pass.
    private static boolean sameContent(ArtifactMetadata stored, ArtifactContentValue offered) {
        return stored.size() == offered.size()
               && stored.md5()
                        .equals(offered.md5())
               && stored.sha1()
                        .equals(offered.sha1())
               && stored.sha256()
                        .equals(offered.sha256());
    }

    private Promise<DeployResult> reassertRegistration(ArtifactFile file, ArtifactMetadata stored) {
        return registerFile(file).flatMap(_ -> publishVersion(file.artifact()))
                           .map(_ -> new DeployResult(file.artifact(),
                                                      stored.size(),
                                                      stored.md5(),
                                                      stored.sha1(),
                                                      true));
    }

    /// The coordinate-to-content binding is decided ONCE, by the index (consensus in a cluster): the first digest
    /// proposed wins. Only the winner writes chunks and metadata, so a reader can never resolve a loser's bytes, and
    /// a loser is refused with both digests before anything of it is uploaded. A winner that died after binding and
    /// before writing leaves a bound coordinate with no metadata; an identical re-put finds its own digest bound and
    /// completes the write. The version bound is checked BEFORE binding, so a version refused for want of room does
    /// not leave its coordinate bound to content that was never stored.
    private Promise<DeployResult> bindThenWrite(ArtifactFile file, byte[] content, ArtifactContentValue offered) {
        return versionIndex.requireCapacity(file.artifact())
                           .flatMap(_ -> versionIndex.bindContent(file, offered))
                           .flatMap(bound -> writeIfBound(file, content, offered, bound));
    }

    private Promise<DeployResult> writeIfBound(ArtifactFile file,
                                               byte[] content,
                                               ArtifactContentValue offered,
                                               ArtifactContentValue bound) {
        return bound.sameDigest(offered)
               ? writeNew(file, content, bound)
               : new ArtifactStoreError.ContentConflict(file, bound, offered).promise();
    }

    private Promise<DeployResult> writeNew(ArtifactFile file, byte[] content, ArtifactContentValue digest) {
        var chunks = splitIntoChunks(content);
        // CORRECTNESS: boundedFanOut preserves chunk order — blockIds are recorded into
        // metadata in chunk order and reassembled in that order on resolve; reordering
        // corrupts the artifact.
        return boundedFanOut(chunks, MAX_CONCURRENT_CHUNKS, this::storagePutWithRetry).flatMap(blockIds -> storeMetadataAndVersions(file,
                                                                                                                                    blockIds,
                                                                                                                                    chunks.size(),
                                                                                                                                    digest));
    }

    @Override
    public Promise<byte[]> resolve(ArtifactFile file) {
        return resolveWithMetadata(file).map(ResolvedArtifact::content);
    }

    /// A version that is archived ([#isArchived]: the consensus flag or the DHT marker) never resolves, whatever
    /// the metadata key says. The marker is a key of its own, written once and never rewritten or removed, so any
    /// replica that holds it wins the read ("present beats absent"); the consensus flag covers a marker lost on
    /// every replica that answers. The check starts together with the metadata read, so a live artifact pays no
    /// extra round trip, and it is consulted before any chunk is fetched.
    @Override
    public Promise<ResolvedArtifact> resolveWithMetadata(ArtifactFile file) {
        log.debug("Resolving artifact: {}", file.asString());
        var archived = isArchived(file.artifact()).timeout(resolveBase);
        // Aggregate timeout on the metadata-read leg (chunk count not yet known here, so the
        // resolveBase floor applies). The block fan-out leg is bounded separately in
        // resolveChunksFromStorage with a chunk-count-scaled budget. Placed early per
        // Promise.timeout's contract so a never-resolving dht.get is cancelled rather than a
        // downstream transformation.
        var startNanos = System.nanoTime();

        return dhtGetWithRetry(metaKey(file)).timeout(resolveBase)
                              .withResult(read -> reportReadFailure(file, read, startNanos))
                              .flatMap(metaOpt -> metadataOf(file,
                                                             metaOpt,
                                                             elapsedMillisSince(startNanos)))
                              .flatMap(meta -> archived.flatMap(retired -> resolveIfLive(file, meta, retired)));
    }

    private Promise<ResolvedArtifact> resolveIfLive(ArtifactFile file, ArtifactMetadata meta, boolean archived) {
        return archived
               ? new ArtifactStoreError.Archived(file).promise()
               : resolveChunksFromStorage(file, meta);
    }

    /// Runs as a dependent step (`withResult`), so the line is in the sink before the resolve chain settles: an
    /// `onFailure` callback is an independent event and a caller awaiting the chain could observe an empty sink.
    @Contract
    private void reportReadFailure(ArtifactFile file, Result<Option<byte[]>> read, long startNanos) {
        read.onFailure(cause -> readFailureSink.accept(readFailureLine(keyHex(file),
                                                                       cause,
                                                                       elapsedMillisSince(startNanos))));
    }

    private String keyHex(ArtifactFile file) {
        return HexFormat.of().formatHex(metaKey(file));
    }

    private static long elapsedMillisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /// The line for a metadata read that did not complete. `outcome=timed-out` is a read the aggregate timeout cut
    /// (a target that departed mid-read never answers, so nothing reports an all-miss for it); any other failure is
    /// `read-failed`. Joins by key hex to the DHT client's all-miss line, which covers the reads that DID complete.
    static String readFailureLine(String keyHex, Cause cause, long elapsedMillis) {
        return "DHT metadata read did not complete key=" + keyHex
             + " outcome=" + (cause instanceof CoreError.Timeout
                              ? "timed-out"
                              : "read-failed")
             + " elapsedMs=" + elapsedMillis
             + " cause=" + cause.message();
    }

    /// Absent bytes are [ArtifactStoreError.NotFound]; present bytes that do not parse are
    /// [ArtifactStoreError.MetadataUnparseable] — the two are different facts and stay different causes.
    private Promise<ArtifactMetadata> metadataOf(ArtifactFile file, Option<byte[]> metaOpt, long elapsedMillis) {
        var keyHex = keyHex(file);

        return metaOpt.async(new ArtifactStoreError.NotFound(file, keyHex, elapsedMillis))
                      .flatMap(bytes -> ArtifactMetadata.fromBytes(bytes).async(new ArtifactStoreError.MetadataUnparseable(file,
                                                                                                                           keyHex)));
    }

    @Override
    public Promise<Boolean> exists(ArtifactFile file) {
        return Promise.all(dht.exists(metaKey(file)),
                           isArchived(file.artifact()))
                      .map(ArtifactStoreImpl::storedAndLive);
    }

    private static boolean storedAndLive(boolean stored, boolean archived) {
        return stored && !archived;
    }

    @Override
    public Promise<Option<ArtifactMetadata>> metadata(ArtifactFile file) {
        return dht.get(metaKey(file))
                  .map(opt -> opt.flatMap(ArtifactMetadata::fromBytes));
    }

    /// The versions that are stored and not archived.
    @Override
    public Promise<List<Version>> versions(GroupId groupId, ArtifactId artifactId) {
        return versionIndex.versions(groupId, artifactId);
    }

    /// Archive is a one-way, version-wide state change (#1778). Order matters: the retention check reads the
    /// stored files; the MARKER is written first because it is what reads obey; the versions-list flag is
    /// written second because it only drives listing — a failure between the two leaves a version that does
    /// not resolve but is still listed, and re-running the archive (idempotent) completes it.
    @Override
    public Promise<Unit> archive(Artifact artifact) {
        log.info("Archiving artifact: {}", artifact.asString());

        return oldestDeployTime(artifact).flatMap(oldest -> checkRetention(artifact, oldest))
                               .flatMap(_ -> writeArchiveMarker(artifact))
                               .flatMap(_ -> flagArchived(artifact));
    }

    private Promise<Long> oldestDeployTime(Artifact artifact) {
        return dhtGetWithRetry(filesKey(artifact)).map(ArtifactStoreImpl::fileNames)
                              .flatMap(names -> deployTimes(artifact, names))
                              .map(ArtifactStoreImpl::oldest);
    }

    private static List<String> fileNames(Option<byte[]> stored) {
        return setOf(stored).live();
    }

    private static GrowOnlySet setOf(Option<byte[]> stored) {
        return stored.map(GrowOnlySet::growOnlySet)
                     .or(GrowOnlySet.empty());
    }

    private Promise<List<Long>> deployTimes(Artifact artifact, List<String> fileNames) {
        return boundedFanOut(fileNames, MAX_CONCURRENT_CHUNKS, name -> deployTimeOf(artifact, name));
    }

    /// An absent or unparseable metadata key contributes nothing to the oldest time.
    private Promise<Long> deployTimeOf(Artifact artifact, String fileName) {
        return dhtGetWithRetry(metaKey(artifact, fileName)).map(ArtifactStoreImpl::deployedAt);
    }

    private static long deployedAt(Option<byte[]> stored) {
        return stored.flatMap(ArtifactMetadata::fromBytes)
                     .map(ArtifactMetadata::deployedAt)
                     .or(NOT_STORED);
    }

    private static long oldest(List<Long> deployTimes) {
        return deployTimes.stream()
                          .mapToLong(Long::longValue)
                          .min()
                          .orElse(NOT_STORED);
    }

    private Promise<Unit> checkRetention(Artifact artifact, long oldestDeployTime) {
        if (oldestDeployTime == NOT_STORED) {
            return new ArtifactStoreError.VersionNotFound(artifact).promise();
        }

        var age = clock.getAsLong() - oldestDeployTime;
        var retention = archivePolicy.minimumRetention().millis();

        return age >= retention
               ? Promise.unitPromise()
               : new ArtifactStoreError.RetentionNotElapsed(artifact, age, retention).promise();
    }

    /// Written once and never rewritten: an existing marker is left exactly as it is.
    private Promise<Unit> writeArchiveMarker(Artifact artifact) {
        return markerPresent(artifact).flatMap(archived -> putMarkerUnlessArchived(artifact, archived));
    }

    private Promise<Unit> putMarkerUnlessArchived(Artifact artifact, boolean archived) {
        return archived
               ? Promise.unitPromise()
               : dhtPutWithRetry(archivedKey(artifact),
                                 Long.toString(clock.getAsLong()).getBytes(StandardCharsets.UTF_8));
    }

    private Promise<Unit> flagArchived(Artifact artifact) {
        return versionIndex.archive(artifact);
    }

    private Promise<DeployResult> storeMetadataAndVersions(ArtifactFile file,
                                                           List<BlockId> blockIds,
                                                           int chunkCount,
                                                           ArtifactContentValue digest) {
        var hexIds = blockIds.stream().map(BlockId::hexString).toList();
        var metadata = new ArtifactMetadata(digest.size(),
                                            chunkCount,
                                            digest.md5(),
                                            digest.sha1(),
                                            digest.sha256(),
                                            digest.deployedAt(),
                                            hexIds);

        return dhtPutWithRetry(metaKey(file),
                               metadata.toBytes()).flatMap(_ -> publishVersion(file.artifact()))
                              .flatMap(_ -> registerFile(file))
                              .map(_ -> recordDeployMetrics(file,
                                                            (int) digest.size(),
                                                            chunkCount,
                                                            digest.md5(),
                                                            digest.sha1()));
    }

    private Promise<ResolvedArtifact> resolveChunksFromStorage(ArtifactFile file, ArtifactMetadata meta) {
        var corruptedError = new ArtifactStoreError.CorruptedArtifact(file);
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
                            .flatMap(content -> verifyIntegrity(file, content, meta));
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

    private Promise<ResolvedArtifact> verifyIntegrity(ArtifactFile file, byte[] content, ArtifactMetadata meta) {
        var computedSha1 = computeHash(content, "SHA-1");

        if (!computedSha1.equals(meta.sha1())) {
            log.error("Integrity verification failed for {}: expected SHA1={}, computed={}",
                      file.asString(),
                      meta.sha1(),
                      computedSha1);

            return new ArtifactStoreError.CorruptedArtifact(file).promise();
        }

        log.debug("Integrity verified for {}: SHA1={}", file.asString(), computedSha1);

        return Promise.success(new ResolvedArtifact(content, meta));
    }

    private Promise<Unit> publishVersion(Artifact artifact) {
        return versionIndex.publish(artifact);
    }

    private Promise<Unit> registerFile(ArtifactFile file) {
        return rewriteSet(filesKey(file.artifact()),
                          files -> files.add(file.fileName()));
    }

    /// Read-merge-write on the per-version FILE list, a grow-only set in the DHT: `change` can only add an entry, and
    /// the result is merged with whatever was read, so the write never removes one. Rewrites of one key on THIS node
    /// run one at a time ([KeyedSequencer]). Across nodes the DHT has no conditional put, so two nodes adding files
    /// to one version at once can overwrite each other's write; the list only dates the version for the archive
    /// retention check, and a missing entry can only make that check stricter. The versions of an artifact are NOT
    /// kept here: they live in the [ArtifactVersionIndex], which a node backs with consensus.
    private Promise<Unit> rewriteSet(byte[] key, UnaryOperator<GrowOnlySet> change) {
        return sequencer.sequence(new String(key, StandardCharsets.UTF_8), () -> mergeAndWrite(key, change));
    }

    private Promise<Unit> mergeAndWrite(byte[] key, UnaryOperator<GrowOnlySet> change) {
        return dhtGetWithRetry(key).map(ArtifactStoreImpl::setOf)
                              .map(change::apply)
                              .flatMap(set -> dhtPutWithRetry(key,
                                                              set.toBytes()));
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

    /// `NotCaughtUp` (#1777 track 2) is a read that met replicas still filling after a ring change: retried like
    /// an unreachable quorum, and never mistaken for an answer about the artifact. `WriteIndeterminate` (#1818,
    /// the owner's fence ruling) is a put that lost its quorum to owner-epoch fences and may have been applied:
    /// retried like an unreachable quorum, stamped with the owner epoch current by then. `ReplicationUnresolved`
    /// (#1777 track 1) is a node that has not yet read the committed replication factors: retried the same way.
    private static boolean isTransientDhtFailure(Cause cause) {
        return cause instanceof DHTError.PeerUnreachable || cause instanceof DHTError.QuorumNotReached || cause instanceof DHTError.NotCaughtUp || cause instanceof DHTError.WriteIndeterminate || cause instanceof DHTError.ReplicationUnresolved;
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

    private DeployResult recordDeployMetrics(ArtifactFile file,
                                             int contentLength,
                                             int chunks,
                                             String md5,
                                             String sha1) {
        artifactCount.incrementAndGet();
        chunkCount.addAndGet(chunks);
        log.info("Deployed artifact: {} ({} chunks)", file.asString(), chunks);

        return new DeployResult(file.artifact(), contentLength, md5, sha1);
    }

    /// Storage format, pinned by `ArtifactStoreTest.KeyShapeTests`: one metadata key per FILE —
    /// `artifacts/<group>/<artifact>/<version>/<file name>/meta` with the EXACT file name, the primary jar
    /// included (`.../lib-1.0.0.jar/meta`). The pre-#281 GAV-only key (`.../<version>/meta`) is NOT read:
    /// artifact-store contents are cluster DHT state with no pre-GA compatibility promise.
    private byte[] metaKey(ArtifactFile file) {
        return metaKey(file.artifact(), file.fileName());
    }

    private byte[] metaKey(Artifact artifact, String fileName) {
        var key = KEY_PREFIX + artifact.groupId().id()
                + "/" + artifact.artifactId().id()
                + "/" + artifact.version().withQualifier()
                + "/" + fileName + METADATA_KEY_SUFFIX;

        return key.getBytes(StandardCharsets.UTF_8);
    }

    /// The version's archive marker — `artifacts/<group>/<artifact>/<version>/archived`. Its mere presence
    /// is the state `archived`; the value is the archive time in epoch millis. It is not a metadata key
    /// (no `/meta` suffix), so its routine absence is not reported as a missing artifact.
    private byte[] archivedKey(Artifact artifact) {
        var key = KEY_PREFIX + artifact.groupId().id()
                + "/" + artifact.artifactId().id()
                + "/" + artifact.version().withQualifier()
                + "/archived";

        return key.getBytes(StandardCharsets.UTF_8);
    }

    /// The files deployed for one version — `artifacts/<group>/<artifact>/<version>/files` — a grow-only
    /// set, so an archive can find every file of the version to date its retention.
    private byte[] filesKey(Artifact artifact) {
        var key = KEY_PREFIX + artifact.groupId().id()
                + "/" + artifact.artifactId().id()
                + "/" + artifact.version().withQualifier()
                + "/files";

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
