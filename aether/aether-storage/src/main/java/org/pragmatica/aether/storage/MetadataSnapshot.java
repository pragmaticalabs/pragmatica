package org.pragmatica.aether.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/// Metadata snapshot -- a point-in-time capture of all block lifecycle entries and named references.
/// Used for crash recovery and node restart.
///
/// @param epoch monotonically increasing snapshot sequence number
/// @param timestamp when the snapshot was taken
/// @param nodeId the node that created this snapshot
/// @param lifecycles all block lifecycle entries at snapshot time
/// @param refs all named reference mappings at snapshot time
/// @param contentHash SHA-256 integrity hash of the serialized entries
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

    public static MetadataSnapshot metadataSnapshot(long epoch, String nodeId,
                                                    List<BlockLifecycle> lifecycles,
                                                    Map<String, BlockId> refs) {
        var hash = computeHash(lifecycles, refs);
        return new MetadataSnapshot(epoch, System.currentTimeMillis(), nodeId, lifecycles, refs, hash);
    }

    /// Verify snapshot integrity.
    public boolean isValid() {
        return Arrays.equals(contentHash, computeHash(lifecycles, refs));
    }

    @SuppressWarnings("JBCT-EXC-01") // Static initializer — SHA-256 is JVM-guaranteed
    private static MessageDigest createSha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new ExceptionInInitializerError(e); }
    }

    private static byte[] computeHash(List<BlockLifecycle> lifecycles, Map<String, BlockId> refs) {
        var digest = createSha256();
        lifecycles.stream()
                  .map(BlockLifecycle::blockId)
                  .map(BlockId::hexString)
                  .sorted()
                  .forEach(hex -> digest.update(hex.getBytes(StandardCharsets.UTF_8)));
        refs.keySet().stream()
            .sorted()
            .forEach(key -> digest.update(key.getBytes(StandardCharsets.UTF_8)));
        return digest.digest();
    }
}
