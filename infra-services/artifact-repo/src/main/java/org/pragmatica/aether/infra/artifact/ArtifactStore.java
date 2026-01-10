package org.pragmatica.aether.infra.artifact;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.GroupId;
import org.pragmatica.aether.artifact.ArtifactId;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Artifact storage backed by DHT.
 * Stores artifacts in chunks for efficient distribution.
 *
 * <p>Key format:
 * <ul>
 *   <li>Artifact content: {@code artifacts/{groupId}/{artifactId}/{version}/content/{chunkIndex}}</li>
 *   <li>Artifact metadata: {@code artifacts/{groupId}/{artifactId}/{version}/meta}</li>
 *   <li>Version list: {@code artifacts/{groupId}/{artifactId}/versions}</li>
 * </ul>
 */
public interface ArtifactStore {
    /**
     * Deploy an artifact.
     */
    Promise<DeployResult> deploy(Artifact artifact, byte[] content);

    /**
     * Resolve an artifact.
     */
    Promise<byte[]> resolve(Artifact artifact);

    /**
     * Resolve an artifact with its metadata.
     * Allows callers to access verification details (hash, size, etc.).
     */
    Promise<ResolvedArtifact> resolveWithMetadata(Artifact artifact);

    /**
     * Resolved artifact with content and metadata.
     */
    record ResolvedArtifact(byte[] content, ArtifactMetadata metadata) {}

    /**
     * Check if an artifact exists.
     */
    Promise<Boolean> exists(Artifact artifact);

    /**
     * List all versions of an artifact.
     */
    Promise<List<Version>> versions(GroupId groupId, ArtifactId artifactId);

    /**
     * Delete an artifact.
     */
    Promise<Unit> delete(Artifact artifact);

    /**
     * Get storage metrics for this artifact store.
     */
    Metrics metrics();

    /**
     * Artifact storage metrics snapshot.
     */
    record Metrics(int artifactCount, int chunkCount, long memoryBytes) {
        /**
         * Chunk size in bytes (64KB).
         */
        static final int CHUNK_SIZE = 64 * 1024;

        /**
         * Create metrics from artifact and chunk counts.
         */
        static Metrics metrics(int artifactCount, int chunkCount) {
            return new Metrics(artifactCount, chunkCount, (long) chunkCount * CHUNK_SIZE);
        }

        /**
         * Empty metrics.
         */
        static Metrics empty() {
            return new Metrics(0, 0, 0);
        }
    }

    /**
     * Deployment result with checksums.
     */
    record DeployResult(Artifact artifact,
                        long size,
                        String md5,
                        String sha1) {}

    /**
     * Artifact metadata.
     */
    record ArtifactMetadata(long size,
                            int chunkCount,
                            String md5,
                            String sha1,
                            long deployedAt) {
        public byte[] toBytes() {
            var data = size + ":" + chunkCount + ":" + md5 + ":" + sha1 + ":" + deployedAt;
            return data.getBytes(StandardCharsets.UTF_8);
        }

        public static Option<ArtifactMetadata> fromBytes(byte[] bytes) {
            try{
                var parts = new String(bytes, StandardCharsets.UTF_8).split(":");
                if (parts.length != 5) return Option.none();
                return Option.some(new ArtifactMetadata(Long.parseLong(parts[0]),
                                                        Integer.parseInt(parts[1]),
                                                        parts[2],
                                                        parts[3],
                                                        Long.parseLong(parts[4])));
            } catch (Exception e) {
                return Option.none();
            }
        }
    }

    /**
     * Artifact store errors.
     */
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

    /**
     * Create an artifact store backed by DHT.
     */
    static ArtifactStore artifactStore(DHTClient dht) {
        return new ArtifactStoreImpl(dht);
    }
}

class ArtifactStoreImpl implements ArtifactStore {
    private static final Logger log = LoggerFactory.getLogger(ArtifactStoreImpl.class);
    private static final int CHUNK_SIZE = 64 * 1024;

    // 64KB chunks
    private final DHTClient dht;

    // Metrics tracking
    private final java.util.concurrent.atomic.AtomicInteger artifactCount = new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicInteger chunkCount = new java.util.concurrent.atomic.AtomicInteger(0);

    ArtifactStoreImpl(DHTClient dht) {
        this.dht = dht;
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
        // Store all chunks
        var chunkPromises = new ArrayList<Promise<Unit>>();
        for (int i = 0; i < chunks.size(); i++) {
            var chunkKey = chunkKey(artifact, i);
            chunkPromises.add(dht.put(chunkKey, chunks.get(i)));
        }
        // Store metadata after chunks
        var metadata = new ArtifactMetadata(content.length, chunks.size(), md5, sha1, System.currentTimeMillis());
        return Promise.allOf(chunkPromises)
                      .flatMap(_ -> dht.put(metaKey(artifact),
                                            metadata.toBytes()))
                      .flatMap(_ -> updateVersionsList(artifact))
                      .map(_ -> {
                               artifactCount.incrementAndGet();
                               chunkCount.addAndGet(chunks.size());
                               log.info("Deployed artifact: {} ({} chunks)",
                                        artifact.asString(),
                                        chunks.size());
                               return new DeployResult(artifact, content.length, md5, sha1);
                           });
    }

    @Override
    public Promise<byte[]> resolve(Artifact artifact) {
        return resolveWithMetadata(artifact)
                                  .map(ResolvedArtifact::content);
    }

    @Override
    public Promise<ResolvedArtifact> resolveWithMetadata(Artifact artifact) {
        log.debug("Resolving artifact: {}", artifact.asString());
        return dht.get(metaKey(artifact))
                  .flatMap(metaOpt -> metaOpt.flatMap(ArtifactMetadata::fromBytes)
                                             .async(new ArtifactStoreError.NotFound(artifact))
                                             .flatMap(meta -> resolveAndVerifyChunks(artifact, meta)));
    }

    private Promise<ResolvedArtifact> resolveAndVerifyChunks(Artifact artifact, ArtifactMetadata meta) {
        var corruptedError = new ArtifactStoreError.CorruptedArtifact(artifact);
        var chunkPromises = new ArrayList<Promise<byte[]>>();
        for (int i = 0; i < meta.chunkCount(); i++) {
            chunkPromises.add(dht.get(chunkKey(artifact, i))
                                 .flatMap(opt -> opt.async(corruptedError)));
        }
        return Promise.allOf(chunkPromises)
                      .map(results -> reassembleChunks(results.stream()
                                                              .map(Result::unwrap)
                                                              .toList(),
                                                       (int) meta.size()))
                      .flatMap(content -> verifyIntegrity(artifact, content, meta));
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

    @Override
    public Promise<Boolean> exists(Artifact artifact) {
        return dht.exists(metaKey(artifact));
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
                                             .map(meta -> deleteChunksAndMeta(artifact, meta))
                                             .or(Promise.unitPromise()));
    }

    private Promise<Unit> deleteChunksAndMeta(Artifact artifact, ArtifactMetadata meta) {
        var deletePromises = new ArrayList<Promise<Boolean>>();
        for (int i = 0; i < meta.chunkCount(); i++) {
            deletePromises.add(dht.remove(chunkKey(artifact, i)));
        }
        deletePromises.add(dht.remove(metaKey(artifact)));
        return Promise.allOf(deletePromises)
                      .map(_ -> {
                               artifactCount.decrementAndGet();
                               chunkCount.addAndGet(- meta.chunkCount());
                               return org.pragmatica.lang.Unit.unit();
                           });
    }

    private Promise<Unit> updateVersionsList(Artifact artifact) {
        var versionsKey = versionsKey(artifact.groupId(), artifact.artifactId());
        return dht.get(versionsKey)
                  .map(opt -> addVersionIfAbsent(opt,
                                                 artifact.version()))
                  .flatMap(versions -> dht.put(versionsKey,
                                               serializeVersionsList(versions)));
    }

    private List<Version> addVersionIfAbsent(Option<byte[]> existingData, Version version) {
        var versions = new ArrayList<>(existingData.map(this::parseVersionsList)
                                                   .or(List.of()));
        if (!versions.contains(version)) {
            versions.add(version);
        }
        return versions;
    }

    private List<Version> parseVersionsList(byte[] data) {
        var str = new String(data, StandardCharsets.UTF_8);
        if (str.isEmpty()) return new ArrayList<>();
        var versions = new ArrayList<Version>();
        for (var v : str.split(",")) {
            Version.version(v)
                   .onSuccess(versions::add);
        }
        return versions;
    }

    private byte[] serializeVersionsList(List<Version> versions) {
        var str = String.join(",",
                              versions.stream()
                                      .map(Version::withQualifier)
                                      .toList());
        return str.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] metaKey(Artifact artifact) {
        var key = "artifacts/" + artifact.groupId()
                                         .id() + "/" + artifact.artifactId()
                                                               .id() + "/" + artifact.version()
                                                                                     .withQualifier() + "/meta";
        return key.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] chunkKey(Artifact artifact, int index) {
        var key = "artifacts/" + artifact.groupId()
                                         .id() + "/" + artifact.artifactId()
                                                               .id() + "/" + artifact.version()
                                                                                     .withQualifier() + "/content/" + index;
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
        try{
            var md = MessageDigest.getInstance(algorithm);
            var hash = md.digest(content);
            return HexFormat.of()
                            .formatHex(hash);
        } catch (Exception e) {
            return "";
        }
    }
}
