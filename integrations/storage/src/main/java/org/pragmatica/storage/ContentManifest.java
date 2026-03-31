package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Number;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Manifest linking chunks of large content.
/// Serialized as pipe-delimited text with a magic header for detection.
public record ContentManifest(String name, long totalSize, int chunkCount, List<String> chunkBlockIds) {

    static final String MAGIC_HEADER = "AHSE-MANIFEST:";

    public ContentManifest {
        chunkBlockIds = List.copyOf(chunkBlockIds);
    }

    public static ContentManifest contentManifest(String name, long totalSize, List<String> chunkBlockIds) {
        return new ContentManifest(name, totalSize, chunkBlockIds.size(), chunkBlockIds);
    }

    /// Serialize manifest to bytes for storage.
    public byte[] toBytes() {
        var body = MAGIC_HEADER + name + "|" + totalSize + "|" + chunkCount + "|" + String.join("|", chunkBlockIds);
        return body.getBytes(StandardCharsets.UTF_8);
    }

    /// Try to parse bytes as a manifest. Returns None if not a manifest.
    public static Option<ContentManifest> fromBytes(byte[] data) {
        if (!isManifest(data)) {
            return none();
        }

        return parseManifestBody(new String(data, StandardCharsets.UTF_8));
    }

    /// Check if raw bytes start with the manifest magic header.
    public static boolean isManifest(byte[] data) {
        var headerBytes = MAGIC_HEADER.getBytes(StandardCharsets.UTF_8);

        if (data.length < headerBytes.length) {
            return false;
        }

        return Arrays.equals(data, 0, headerBytes.length, headerBytes, 0, headerBytes.length);
    }

    private static Option<ContentManifest> parseManifestBody(String text) {
        var body = text.substring(MAGIC_HEADER.length());
        var parts = body.split("\\|");

        // Minimum: name + totalSize + chunkCount + at least 1 chunkId
        if (parts.length < 4) {
            return none();
        }

        var name = parts[0];
        var chunkIds = Arrays.asList(parts).subList(3, parts.length);

        return Result.all(Number.parseLong(parts[1]), Number.parseInt(parts[2]))
                     .map((totalSize, chunkCount) -> validateAndBuild(name, totalSize, chunkCount, chunkIds))
                     .option()
                     .flatMap(opt -> opt);
    }

    private static Option<ContentManifest> validateAndBuild(String name, long totalSize, int chunkCount,
                                                             List<String> chunkIds) {
        if (chunkIds.size() != chunkCount) {
            return none();
        }

        return some(contentManifest(name, totalSize, chunkIds));
    }
}
