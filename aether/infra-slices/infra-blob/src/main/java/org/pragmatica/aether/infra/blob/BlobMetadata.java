package org.pragmatica.aether.infra.blob;

import java.time.Instant;
import java.util.Map;

/**
 * Metadata for a stored blob.
 *
 * @param bucket       Bucket name
 * @param key          Blob key (path)
 * @param size         Size in bytes
 * @param contentType  MIME content type
 * @param createdAt    Creation timestamp
 * @param updatedAt    Last update timestamp
 * @param customMetadata User-defined metadata
 */
public record BlobMetadata(String bucket,
                           String key,
                           long size,
                           String contentType,
                           Instant createdAt,
                           Instant updatedAt,
                           Map<String, String> customMetadata) {
    /**
     * Creates blob metadata with default values.
     */
    public static BlobMetadata blobMetadata(String bucket, String key, long size, String contentType) {
        var now = Instant.now();
        return new BlobMetadata(bucket, key, size, contentType, now, now, Map.of());
    }

    /**
     * Creates blob metadata with custom metadata.
     */
    public static BlobMetadata blobMetadata(String bucket,
                                            String key,
                                            long size,
                                            String contentType,
                                            Map<String, String> customMetadata) {
        var now = Instant.now();
        return new BlobMetadata(bucket, key, size, contentType, now, now, Map.copyOf(customMetadata));
    }

    /**
     * Creates updated metadata with new timestamp.
     */
    public BlobMetadata withUpdatedAt(Instant timestamp) {
        return new BlobMetadata(bucket, key, size, contentType, createdAt, timestamp, customMetadata);
    }

    /**
     * Creates metadata with updated size.
     */
    public BlobMetadata withSize(long newSize) {
        return new BlobMetadata(bucket, key, newSize, contentType, createdAt, Instant.now(), customMetadata);
    }

    /**
     * Creates metadata with updated content type.
     */
    public BlobMetadata withContentType(String newContentType) {
        return new BlobMetadata(bucket, key, size, newContentType, createdAt, Instant.now(), customMetadata);
    }

    /**
     * Creates metadata with additional custom metadata.
     */
    public BlobMetadata withCustomMetadata(Map<String, String> additionalMetadata) {
        var merged = new java.util.HashMap<>(customMetadata);
        merged.putAll(additionalMetadata);
        return new BlobMetadata(bucket, key, size, contentType, createdAt, Instant.now(), Map.copyOf(merged));
    }
}
