package org.pragmatica.aether.infra.blob;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;

/**
 * Error types for blob storage operations.
 */
public sealed interface BlobStorageError extends Cause {
    /**
     * Blob not found.
     */
    record BlobNotFound(String bucket, String key) implements BlobStorageError {
        @Override
        public String message() {
            return "Blob not found: " + bucket + "/" + key;
        }
    }

    /**
     * Bucket not found.
     */
    record BucketNotFound(String bucket) implements BlobStorageError {
        @Override
        public String message() {
            return "Bucket not found: " + bucket;
        }
    }

    /**
     * Bucket already exists.
     */
    record BucketAlreadyExists(String bucket) implements BlobStorageError {
        @Override
        public String message() {
            return "Bucket already exists: " + bucket;
        }
    }

    /**
     * Blob already exists.
     */
    record BlobAlreadyExists(String bucket, String key) implements BlobStorageError {
        @Override
        public String message() {
            return "Blob already exists: " + bucket + "/" + key;
        }
    }

    /**
     * Blob size exceeds limit.
     */
    record BlobTooLarge(String key, long size, long maxSize) implements BlobStorageError {
        @Override
        public String message() {
            return "Blob '" + key + "' size " + size + " exceeds maximum " + maxSize;
        }
    }

    /**
     * Storage operation failed.
     */
    record StorageFailed(String operation, Option<Throwable> cause) implements BlobStorageError {
        @Override
        public String message() {
            return "Storage operation failed: " + operation + cause.fold(() -> "", c -> " - " + c.getMessage());
        }
    }

    /**
     * Invalid configuration.
     */
    record InvalidConfiguration(String reason) implements BlobStorageError {
        @Override
        public String message() {
            return "Invalid blob storage configuration: " + reason;
        }
    }

    /**
     * Invalid key format.
     */
    record InvalidKey(String key, String reason) implements BlobStorageError {
        @Override
        public String message() {
            return "Invalid blob key '" + key + "': " + reason;
        }
    }

    // Factory methods
    static BlobNotFound blobNotFound(String bucket, String key) {
        return new BlobNotFound(bucket, key);
    }

    static BucketNotFound bucketNotFound(String bucket) {
        return new BucketNotFound(bucket);
    }

    static BucketAlreadyExists bucketAlreadyExists(String bucket) {
        return new BucketAlreadyExists(bucket);
    }

    static BlobAlreadyExists blobAlreadyExists(String bucket, String key) {
        return new BlobAlreadyExists(bucket, key);
    }

    static BlobTooLarge blobTooLarge(String key, long size, long maxSize) {
        return new BlobTooLarge(key, size, maxSize);
    }

    static StorageFailed storageFailed(String operation) {
        return new StorageFailed(operation, Option.none());
    }

    static StorageFailed storageFailed(String operation, Throwable cause) {
        return new StorageFailed(operation, Option.option(cause));
    }

    static InvalidConfiguration invalidConfiguration(String reason) {
        return new InvalidConfiguration(reason);
    }

    static InvalidKey invalidKey(String key, String reason) {
        return new InvalidKey(key, reason);
    }
}
