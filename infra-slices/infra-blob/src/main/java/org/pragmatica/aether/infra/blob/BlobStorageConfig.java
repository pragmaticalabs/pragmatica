package org.pragmatica.aether.infra.blob;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;

/**
 * Configuration for blob storage service.
 *
 * @param name          Service name for identification
 * @param maxBlobSize   Maximum allowed blob size in bytes
 * @param defaultBucket Default bucket name
 */
public record BlobStorageConfig(String name,
                                long maxBlobSize,
                                String defaultBucket) {
    private static final String DEFAULT_NAME = "default";
    private static final long DEFAULT_MAX_BLOB_SIZE = 100 * 1024 * 1024;

    // 100 MB
    private static final String DEFAULT_BUCKET = "default";

    /**
     * Creates default configuration.
     */
    public static Result<BlobStorageConfig> blobStorageConfig() {
        return success(new BlobStorageConfig(DEFAULT_NAME, DEFAULT_MAX_BLOB_SIZE, DEFAULT_BUCKET));
    }

    /**
     * Creates configuration with specified name.
     */
    public static Result<BlobStorageConfig> blobStorageConfig(String name) {
        return validateName(name)
                           .map(n -> new BlobStorageConfig(n, DEFAULT_MAX_BLOB_SIZE, DEFAULT_BUCKET));
    }

    private static Result<String> validateName(String name) {
        return Option.option(name)
                     .filter(n -> !n.isBlank())
                     .map(String::trim)
                     .toResult(BlobStorageError.invalidConfiguration("Service name cannot be null or empty"));
    }

    /**
     * Creates a new configuration with the specified name.
     */
    public BlobStorageConfig withName(String name) {
        return new BlobStorageConfig(name, maxBlobSize, defaultBucket);
    }

    /**
     * Creates a new configuration with the specified max blob size.
     */
    public BlobStorageConfig withMaxBlobSize(long size) {
        return new BlobStorageConfig(name, size, defaultBucket);
    }

    /**
     * Creates a new configuration with the specified default bucket.
     */
    public BlobStorageConfig withDefaultBucket(String bucket) {
        return new BlobStorageConfig(name, maxBlobSize, bucket);
    }
}
