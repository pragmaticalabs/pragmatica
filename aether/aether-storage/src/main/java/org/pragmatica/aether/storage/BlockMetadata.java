package org.pragmatica.aether.storage;

/// Metadata for a storage block.
///
/// @param rawSize uncompressed content size in bytes
/// @param contentType MIME type or application-defined type
/// @param createdAt creation timestamp (millis since epoch)
/// @param compression compression algorithm applied to stored content
public record BlockMetadata(long rawSize,
                            String contentType,
                            long createdAt,
                            Compression compression) {

    public static BlockMetadata blockMetadata(long rawSize, String contentType, Compression compression) {
        return new BlockMetadata(rawSize, contentType, System.currentTimeMillis(), compression);
    }

    public static BlockMetadata blockMetadata(long rawSize) {
        return new BlockMetadata(rawSize, "application/octet-stream", System.currentTimeMillis(), Compression.NONE);
    }
}
