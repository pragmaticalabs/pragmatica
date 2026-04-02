package org.pragmatica.aether.resource.artifact;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactId;
import org.pragmatica.aether.artifact.GroupId;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.StorageInstance;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;

/// Artifact storage backed by DHT for metadata and StorageInstance for chunk data.
/// Stores artifacts in chunks for efficient distribution.
///
///
/// Key format:
///
///   - Artifact metadata: `artifacts/{groupId}/{artifactId}/{version}/meta`
///   - Version list: `artifacts/{groupId}/{artifactId}/versions`
///
/// Chunk content is stored in the StorageInstance and referenced by BlockId in metadata.
///
public interface ArtifactStore {
    /// Deploy an artifact.
    Promise<DeployResult> deploy(Artifact artifact, byte[] content);

    /// Resolve an artifact.
    Promise<byte[]> resolve(Artifact artifact);

    /// Resolve an artifact with its metadata.
    /// Allows callers to access verification details (hash, size, etc.).
    Promise<ResolvedArtifact> resolveWithMetadata(Artifact artifact);

    /// Resolved artifact with content and metadata.
    record ResolvedArtifact(byte[] content, ArtifactMetadata metadata) {
        public ResolvedArtifact {
            content = content.clone();
        }

        @Override public byte[] content() {
            return content.clone();
        }

        @Override public boolean equals(Object o) {
            return o instanceof ResolvedArtifact other &&
            Arrays.equals(content, other.content) &&
            metadata.equals(other.metadata);
        }

        @Override public int hashCode() {
            return 31 * Arrays.hashCode(content) + metadata.hashCode();
        }
    }

    /// Check if an artifact exists.
    Promise<Boolean> exists(Artifact artifact);

    /// List all versions of an artifact.
    Promise<List<Version>> versions(GroupId groupId, ArtifactId artifactId);

    /// Delete an artifact.
    Promise<Unit> delete(Artifact artifact);

    /// Get storage metrics for this artifact store.
    Metrics metrics();

    /// Artifact storage metrics snapshot.
    record Metrics(int artifactCount, int chunkCount, long memoryBytes) {
        /// Chunk size in bytes (64KB).
        static final int CHUNK_SIZE = 64 * 1024;

        /// Create metrics from artifact and chunk counts.
        static Metrics metrics(int artifactCount, int chunkCount) {
            return new Metrics(artifactCount, chunkCount, (long) chunkCount * CHUNK_SIZE);
        }

        /// Empty metrics.
        static Metrics empty() {
            return new Metrics(0, 0, 0);
        }
    }

    /// Deployment result with checksums.
    record DeployResult(Artifact artifact,
                        long size,
                        String md5,
                        String sha1){}

    /// Artifact metadata stored in DHT. BlockIds reference chunks in StorageInstance.
    record ArtifactMetadata(long size,
                            int chunkCount,
                            String md5,
                            String sha1,
                            long deployedAt,
                            List<String> blockIds) {
        public byte[] toBytes() {
            var data = size + ":" + chunkCount + ":" + md5 + ":" + sha1 + ":" + deployedAt + ":" + String.join(",",
                                                                                                               blockIds);
            return data.getBytes(StandardCharsets.UTF_8);
        }

        public static Option<ArtifactMetadata> fromBytes(byte[] bytes) {
            return parseMetadataBytes(bytes);
        }

        private static Option<ArtifactMetadata> parseMetadataBytes(byte[] bytes) {
            try {
                var parts = new String(bytes, StandardCharsets.UTF_8).split(":");
                if ( parts.length != 6) {
                return none();}
                var ids = List.of(parts[5].split(","));
                return some(new ArtifactMetadata(Long.parseLong(parts[0]),
                                                 Integer.parseInt(parts[1]),
                                                 parts[2],
                                                 parts[3],
                                                 Long.parseLong(parts[4]),
                                                 ids));
            }




























            catch (Exception e) {
                return none();
            }
        }
    }

    /// Artifact store errors.
    sealed interface ArtifactStoreError extends Cause {
        record NotFound(Artifact artifact) implements ArtifactStoreError {
            @Override public String message() {
                return "Artifact not found: " + artifact.asString();
            }
        }

        record DeployFailed(Artifact artifact, String reason) implements ArtifactStoreError {
            @Override public String message() {
                return "Failed to deploy " + artifact.asString() + ": " + reason;
            }
        }

        record ResolveFailed(Artifact artifact, String reason) implements ArtifactStoreError {
            @Override public String message() {
                return "Failed to resolve " + artifact.asString() + ": " + reason;
            }
        }

        record CorruptedArtifact(Artifact artifact) implements ArtifactStoreError {
            @Override public String message() {
                return "Corrupted artifact: " + artifact.asString();
            }
        }
    }

    /// Create an artifact store with DHT for metadata and StorageInstance for chunk storage.
    static ArtifactStore artifactStore(DHTClient dht, StorageInstance storage) {
        return new ArtifactStoreImpl(dht, storage);
    }
}

class ArtifactStoreImpl implements ArtifactStore {
    private static final Logger log = LoggerFactory.getLogger(ArtifactStoreImpl.class);
    private static final int CHUNK_SIZE = 64 * 1024;

    private final DHTClient dht;
    private final StorageInstance storage;

    // Metrics tracking
    private final AtomicInteger artifactCount = new AtomicInteger(0);
    private final AtomicInteger chunkCount = new AtomicInteger(0);

    ArtifactStoreImpl(DHTClient dht, StorageInstance storage) {
        this.dht = dht;
        this.storage = storage;
    }

    @Override public Metrics metrics() {
        return Metrics.metrics(artifactCount.get(), chunkCount.get());
    }

    @Override public Promise<DeployResult> deploy(Artifact artifact, byte[] content) {
        log.info("Deploying artifact: {} ({} bytes)", artifact.asString(), content.length);
        var md5 = computeHash(content, "MD5");
        var sha1 = computeHash(content, "SHA-1");
        var chunks = splitIntoChunks(content);
        var blockIdPromises = chunks.stream().map(storage::put)
                                           .toList();
        return Promise.allOf(blockIdPromises).map(results -> results.stream().map(Result::unwrap)
                                                                           .toList())
                            .flatMap(blockIds -> storeMetadataAndVersions(artifact,
                                                                          blockIds,
                                                                          chunks.size(),
                                                                          md5,
                                                                          sha1,
                                                                          content.length));
    }

    @Override public Promise<byte[]> resolve(Artifact artifact) {
        return resolveWithMetadata(artifact).map(ResolvedArtifact::content);
    }

    @Override public Promise<ResolvedArtifact> resolveWithMetadata(Artifact artifact) {
        log.debug("Resolving artifact: {}", artifact.asString());
        return dht.get(metaKey(artifact))
        .flatMap(metaOpt -> metaOpt.flatMap(ArtifactMetadata::fromBytes).async(new ArtifactStoreError.NotFound(artifact))
                                           .flatMap(meta -> resolveChunksFromStorage(artifact, meta)));
    }

    @Override public Promise<Boolean> exists(Artifact artifact) {
        return dht.exists(metaKey(artifact));
    }

    @Override public Promise<List<Version>> versions(GroupId groupId, ArtifactId artifactId) {
        var versionsKey = versionsKey(groupId, artifactId);
        return dht.get(versionsKey).map(opt -> opt.map(this::parseVersionsList).or(List.of()));
    }

    @Override public Promise<Unit> delete(Artifact artifact) {
        log.info("Deleting artifact: {}", artifact.asString());
        return dht.get(metaKey(artifact))
        .flatMap(metaOpt -> metaOpt.flatMap(ArtifactMetadata::fromBytes).map(meta -> deleteMetadata(artifact, meta))
                                           .or(Promise.unitPromise()));
    }

    // --- Deploy ---
    private Promise<DeployResult> storeMetadataAndVersions(Artifact artifact,
                                                           List<BlockId> blockIds,
                                                           int chunkCount,
                                                           String md5,
                                                           String sha1,
                                                           int contentLength) {
        var hexIds = blockIds.stream().map(BlockId::hexString)
                                    .toList();
        var metadata = new ArtifactMetadata(contentLength, chunkCount, md5, sha1, System.currentTimeMillis(), hexIds);
        return dht.put(metaKey(artifact),
                       metadata.toBytes()).flatMap(_ -> updateVersionsList(artifact))
                      .map(_ -> recordDeployMetrics(artifact, contentLength, chunkCount, md5, sha1));
    }

    // --- Resolve ---
    private Promise<ResolvedArtifact> resolveChunksFromStorage(Artifact artifact, ArtifactMetadata meta) {
        var corruptedError = new ArtifactStoreError.CorruptedArtifact(artifact);
        var fetchPromises = meta.blockIds().stream()
                                         .map(hex -> fetchSingleBlock(hex, corruptedError))
                                         .toList();
        return Promise.allOf(fetchPromises).map(results -> reassembleChunks(results.stream().map(Result::unwrap)
                                                                                          .toList(),
                                                                            (int) meta.size()))
                            .flatMap(content -> verifyIntegrity(artifact, content, meta));
    }

    private Promise<byte[]> fetchSingleBlock(String hex, ArtifactStoreError.CorruptedArtifact error) {
        return BlockId.fromHex(hex).async()
                              .flatMap(storage::get)
                              .flatMap(opt -> opt.async(error));
    }

    // --- Verify ---
    private Promise<ResolvedArtifact> verifyIntegrity(Artifact artifact, byte[] content, ArtifactMetadata meta) {
        var computedSha1 = computeHash(content, "SHA-1");
        if ( !computedSha1.equals(meta.sha1())) {
            log.error("Integrity verification failed for {}: expected SHA1={}, computed={}",
                      artifact.asString(),
                      meta.sha1(),
                      computedSha1);
            return new ArtifactStoreError.CorruptedArtifact(artifact).promise();
        }
        log.debug("Integrity verified for {}: SHA1={}", artifact.asString(), computedSha1);
        return Promise.success(new ResolvedArtifact(content, meta));
    }

    // --- Delete ---
    private Promise<Unit> deleteMetadata(Artifact artifact, ArtifactMetadata meta) {
        return dht.remove(metaKey(artifact)).map(_ -> recordDeleteMetrics(meta));
    }

    // --- Versions ---
    private Promise<Unit> updateVersionsList(Artifact artifact) {
        var versionsKey = versionsKey(artifact.groupId(), artifact.artifactId());
        return dht.get(versionsKey).map(opt -> addVersionIfAbsent(opt,
                                                                  artifact.version()))
                      .flatMap(versions -> dht.put(versionsKey,
                                                   serializeVersionsList(versions)));
    }

    private List<Version> addVersionIfAbsent(Option<byte[]> existingData, Version version) {
        var versions = new ArrayList<>(existingData.map(this::parseVersionsList).or(List.of()));
        if ( !versions.contains(version)) {
        versions.add(version);}
        return versions;
    }

    private List<Version> parseVersionsList(byte[] data) {
        var str = new String(data, StandardCharsets.UTF_8);
        if ( str.isEmpty()) {
        return new ArrayList<>();}
        var versions = new ArrayList<Version>();
        for ( var v : str.split(",")) {
        Version.version(v).onSuccess(versions::add);}
        return versions;
    }

    private byte[] serializeVersionsList(List<Version> versions) {
        var str = versions.stream().map(Version::withQualifier)
                                 .collect(Collectors.joining(","));
        return str.getBytes(StandardCharsets.UTF_8);
    }

    // --- Metrics ---
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
        chunkCount.addAndGet(- meta.chunkCount());
        return unit();
    }

    // --- Key construction ---
    private byte[] metaKey(Artifact artifact) {
        var key = "artifacts/" + artifact.groupId().id() + "/" + artifact.artifactId().id() + "/" + artifact.version().withQualifier() + "/meta";
        return key.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] versionsKey(GroupId groupId, ArtifactId artifactId) {
        var key = "artifacts/" + groupId.id() + "/" + artifactId.id() + "/versions";
        return key.getBytes(StandardCharsets.UTF_8);
    }

    // --- Chunking ---
    private List<byte[]> splitIntoChunks(byte[] content) {
        var chunks = new ArrayList<byte[]>();
        int offset = 0;
        while ( offset < content.length) {
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
        for ( var chunk : chunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.length);
            offset += chunk.length;
        }
        return result;
    }

    // --- Hashing ---
    private String computeHash(byte[] content, String algorithm) {
        try {
            var md = MessageDigest.getInstance(algorithm);
            var hash = md.digest(content);
            return HexFormat.of().formatHex(hash);
        }




























        catch (Exception e) {
            return "";
        }
    }
}
