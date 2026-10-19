package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/// Metadata snapshot -- a point-in-time capture of all block lifecycle entries and named references.
/// Used for crash recovery and node restart.
///
/// @param epoch monotonically increasing snapshot sequence number
/// @param timestamp when the snapshot was taken
/// @param nodeId the node that created this snapshot
/// @param lifecycles all block lifecycle entries at snapshot time, in file order
/// @param refs all named reference mappings at snapshot time, in file order
/// @param contentHash SHA-256 integrity hash of the body as written: the epoch, then every lifecycle line and
/// every ref line in file order, exactly what a restore installs (#2257)
public record MetadataSnapshot(long epoch,
                               long timestamp,
                               String nodeId,
                               List<BlockLifecycle> lifecycles,
                               Map<String, BlockId> refs,
                               byte[] contentHash) {
    private static final HexFormat HEX = HexFormat.of();
    private static final int CHUNK = 16 * 1024;

    /// Defensive copies. The refs copy keeps iteration order: the file order IS the hashed order, so a
    /// snapshot read back from a file hashes the same lines in the same order the writer did.
    public MetadataSnapshot {
        lifecycles = List.copyOf(lifecycles);
        refs = Collections.unmodifiableMap(new LinkedHashMap<>(refs));
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
        var hash = render(epoch, lifecycles, refs).hash();

        return new MetadataSnapshot(epoch, System.currentTimeMillis(), nodeId, lifecycles, refs, hash);
    }

    /// Verify snapshot integrity.
    public boolean isValid() {
        return Arrays.equals(contentHash, render(epoch, lifecycles, refs).hash());
    }

    /// The same snapshot carrying the current hash scheme. A snapshot read from a pre-#2257 file was
    /// verified against [#legacyContentHash] at parse time and is re-sealed here, so every in-memory
    /// snapshot answers [#isValid] under one scheme.
    MetadataSnapshot resealed() {
        return new MetadataSnapshot(epoch, timestamp, nodeId, lifecycles, refs, render(epoch, lifecycles, refs).hash());
    }

    /// One lifecycle line of the snapshot body.
    static String lifecycleLine(BlockLifecycle lifecycle) {
        return appendLifecycleLine(new StringBuilder(112), lifecycle).toString();
    }

    /// One ref line of the snapshot body: the percent-encoded name, `|`, the block id in hex.
    static String refLine(String name, BlockId id) {
        return appendRefLine(new StringBuilder(112), name, id).toString();
    }

    private static StringBuilder appendLifecycleLine(StringBuilder sb, BlockLifecycle lifecycle) {
        var separator = "";

        HEX.formatHex(sb,
                      lifecycle.blockId().hash());
        sb.append('|');
        for (var tier : lifecycle.presentIn()) {
            sb.append(separator).append(tier.name());
            separator = ",";
        }

        return sb.append('|')
                 .append(lifecycle.refCount())
                 .append('|')
                 .append(lifecycle.lastAccessedAt())
                 .append('|')
                 .append(lifecycle.createdAt())
                 .append('|')
                 .append(lifecycle.accessCount())
                 .append('|')
                 .append(lifecycle.orphanedAt());
    }

    private static StringBuilder appendRefLine(StringBuilder sb, String name, BlockId id) {
        SnapshotNameCodec.appendEncoded(sb, name);
        sb.append('|');

        return HEX.formatHex(sb, id.hash());
    }

    /// The snapshot body rendered ONCE, in the order given: `text` is `# Lifecycles`, the lifecycle lines,
    /// a blank line, `# Refs`, the ref lines (each line newline-terminated) -- the part of the file after
    /// the headers. `hash` is SHA-256 over `epoch=<epoch>\n`, the lifecycle lines each followed by `\n`,
    /// `#\n`, the ref lines each followed by `\n`, streamed from `text` in chunks while it is written, so
    /// no second rendering, no sort and no joined copy exist. Every field a restore installs is inside it,
    /// and the lines are delimited, so `{"ab","c"}` and `{"a","bc"}` differ.
    record Body(String text, byte[] hash) {}

    static Body render(long epoch, List<BlockLifecycle> lifecycles, Map<String, BlockId> refs) {
        var sb = new StringBuilder(64 + (lifecycles.size() + refs.size()) * 100);

        sb.append("# Lifecycles\n");
        var lifecyclesFrom = sb.length();

        for (var lifecycle : lifecycles) {
            appendLifecycleLine(sb, lifecycle).append('\n');
        }

        var lifecyclesTo = sb.length();

        sb.append("\n# Refs\n");
        var refsFrom = sb.length();

        for (var entry : refs.entrySet()) {
            appendRefLine(sb, entry.getKey(), entry.getValue()).append('\n');
        }

        var digest = createSha256();

        digest.update(("epoch=" + epoch + "\n").getBytes(StandardCharsets.US_ASCII));
        streamAscii(digest, sb, lifecyclesFrom, lifecyclesTo);
        digest.update(new byte[]{'#', '\n'});
        streamAscii(digest, sb, refsFrom, sb.length());

        return new Body(sb.toString(), digest.digest());
    }

    /// All characters of the body are ASCII (hex, digits, tier names, percent-encoded names).
    private static void streamAscii(MessageDigest digest, StringBuilder text, int from, int to) {
        var chars = new char[CHUNK];
        var bytes = new byte[CHUNK];

        for (var start = from; start < to; start += CHUNK) {
            var end = Math.min(start + CHUNK, to);

            text.getChars(start, end, chars, 0);
            for (var i = 0; i < end - start; i++) {
                bytes[i] = (byte) chars[i];
            }

            digest.update(bytes, 0, end - start);
        }
    }

    @SuppressWarnings("JBCT-EX-01")  // SHA-256 is mandated by the Java platform spec; absence is a JVM defect, not a business failure
    private static MessageDigest createSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new ExceptionInInitializerError(e);
        }
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
