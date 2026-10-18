package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


/// Metadata snapshot -- a point-in-time capture of all block lifecycle entries and named references.
/// Used for crash recovery and node restart.
///
/// @param epoch monotonically increasing snapshot sequence number
/// @param timestamp when the snapshot was taken
/// @param nodeId the node that created this snapshot
/// @param lifecycles all block lifecycle entries at snapshot time
/// @param refs all named reference mappings at snapshot time
/// @param contentHash SHA-256 integrity hash of the canonical body: the epoch, every lifecycle line and every
/// ref line, exactly as [DefaultSnapshotManager] writes them and exactly what a restore installs (#2257)
public record MetadataSnapshot(long epoch,
                               long timestamp,
                               String nodeId,
                               List<BlockLifecycle> lifecycles,
                               Map<String, BlockId> refs,
                               byte[] contentHash) {
    /// Defensive copies.
    public MetadataSnapshot {
        lifecycles = List.copyOf(lifecycles);
        refs = Map.copyOf(refs);
        contentHash = contentHash.clone();
    }

    @Override
    public byte[] contentHash() {
        return contentHash.clone();
    }

    public static MetadataSnapshot metadataSnapshot(long epoch,
                                                    String nodeId,
                                                    List<BlockLifecycle> lifecycles,
                                                    Map<String, BlockId> refs) {
        var hash = computeHash(epoch, lifecycles, refs);

        return new MetadataSnapshot(epoch, System.currentTimeMillis(), nodeId, lifecycles, refs, hash);
    }

    /// Verify snapshot integrity.
    public boolean isValid() {
        return Arrays.equals(contentHash, computeHash(epoch, lifecycles, refs));
    }

    /// The same snapshot carrying the current hash scheme. A snapshot read from a pre-#2257 file was
    /// verified against [#legacyContentHash] at parse time and is re-sealed here, so every in-memory
    /// snapshot answers [#isValid] under one scheme.
    MetadataSnapshot resealed() {
        return new MetadataSnapshot(epoch, timestamp, nodeId, lifecycles, refs, computeHash(epoch, lifecycles, refs));
    }

    /// One lifecycle line of the snapshot body. Written to disk and hashed from this one method, so the
    /// bytes that are verified are the bytes that were written.
    static String lifecycleLine(BlockLifecycle lifecycle) {
        var tiers = lifecycle.presentIn().stream().map(TierLevel::name).collect(Collectors.joining(","));

        return String.join("|",
                           lifecycle.blockId().hexString(),
                           tiers,
                           String.valueOf(lifecycle.refCount()),
                           String.valueOf(lifecycle.lastAccessedAt()),
                           String.valueOf(lifecycle.createdAt()),
                           String.valueOf(lifecycle.accessCount()),
                           String.valueOf(lifecycle.orphanedAt()));
    }

    /// One ref line of the snapshot body: the percent-encoded name, `|`, the block id in hex.
    static String refLine(String name, BlockId id) {
        return SnapshotNameCodec.encode(name) + "|" + id.hexString();
    }

    @SuppressWarnings("JBCT-EX-01")  // SHA-256 is mandated by the Java platform spec; absence is a JVM defect, not a business failure
    private static MessageDigest createSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /// Hash of the canonical body. Lifecycles and refs are sorted so the digest does not depend on
    /// iteration order. Every field a restore installs is inside it (epoch, all lifecycle fields, each
    /// ref name AND target), and the lines are delimited, so `{"ab","c"}` and `{"a","bc"}` differ.
    private static byte[] computeHash(long epoch, List<BlockLifecycle> lifecycles, Map<String, BlockId> refs) {
        var body = "epoch=" + epoch + "\n" + canonicalLifecycles(lifecycles) + "\n#\n" + canonicalRefs(refs);

        return createSha256().digest(body.getBytes(StandardCharsets.UTF_8));
    }

    private static String canonicalLifecycles(List<BlockLifecycle> lifecycles) {
        return lifecycles.stream()
                         .map(MetadataSnapshot::lifecycleLine)
                         .sorted()
                         .collect(Collectors.joining("\n"));
    }

    private static String canonicalRefs(Map<String, BlockId> refs) {
        return refs.entrySet()
                   .stream()
                   .map(entry -> refLine(entry.getKey(),
                                         entry.getValue()))
                   .sorted()
                   .collect(Collectors.joining("\n"));
    }

    /// The hash scheme of snapshot files written before #2257 (no `format=` header): block ids and ref
    /// names only, concatenated without delimiters. Kept so those files still validate on restore.
    static byte[] legacyContentHash(List<BlockLifecycle> lifecycles, Map<String, BlockId> refs) {
        var digest = createSha256();

        lifecycles.stream()
                  .map(BlockLifecycle::blockId)
                  .map(BlockId::hexString)
                  .sorted()
                  .forEach(hex -> digest.update(hex.getBytes(StandardCharsets.UTF_8)));
        refs.keySet().stream().sorted().forEach(key -> digest.update(key.getBytes(StandardCharsets.UTF_8)));

        return digest.digest();
    }
}
