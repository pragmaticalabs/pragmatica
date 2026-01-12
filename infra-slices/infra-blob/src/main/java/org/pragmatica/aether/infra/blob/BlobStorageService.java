package org.pragmatica.aether.infra.blob;

import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.Map;

/**
 * Blob storage service providing object storage operations.
 * Uses a simple in-memory implementation for testing and development.
 */
public interface BlobStorageService extends Slice {
    // ========== Bucket Operations ==========
    /**
     * Creates a new bucket.
     *
     * @param bucket Bucket name
     * @return Unit on success
     */
    Promise<Unit> createBucket(String bucket);

    /**
     * Deletes a bucket. Bucket must be empty.
     *
     * @param bucket Bucket name
     * @return true if bucket existed and was deleted
     */
    Promise<Boolean> deleteBucket(String bucket);

    /**
     * Checks if a bucket exists.
     *
     * @param bucket Bucket name
     * @return true if bucket exists
     */
    Promise<Boolean> bucketExists(String bucket);

    /**
     * Lists all bucket names.
     *
     * @return List of bucket names
     */
    Promise<List<String>> listBuckets();

    // ========== Blob Operations ==========
    /**
     * Uploads a blob. Overwrites if exists.
     *
     * @param bucket      Bucket name
     * @param key         Blob key (path)
     * @param data        Blob content
     * @param contentType MIME content type
     * @return Metadata of the uploaded blob
     */
    Promise<BlobMetadata> put(String bucket, String key, byte[] data, String contentType);

    /**
     * Uploads a blob with custom metadata. Overwrites if exists.
     *
     * @param bucket         Bucket name
     * @param key            Blob key (path)
     * @param data           Blob content
     * @param contentType    MIME content type
     * @param customMetadata User-defined metadata
     * @return Metadata of the uploaded blob
     */
    Promise<BlobMetadata> put(String bucket,
                              String key,
                              byte[] data,
                              String contentType,
                              Map<String, String> customMetadata);

    /**
     * Downloads a blob.
     *
     * @param bucket Bucket name
     * @param key    Blob key
     * @return Blob content with metadata, or empty if not found
     */
    Promise<Option<BlobContent>> get(String bucket, String key);

    /**
     * Gets blob metadata without downloading content.
     *
     * @param bucket Bucket name
     * @param key    Blob key
     * @return Metadata, or empty if not found
     */
    Promise<Option<BlobMetadata>> getMetadata(String bucket, String key);

    /**
     * Deletes a blob.
     *
     * @param bucket Bucket name
     * @param key    Blob key
     * @return true if blob existed and was deleted
     */
    Promise<Boolean> delete(String bucket, String key);

    /**
     * Checks if a blob exists.
     *
     * @param bucket Bucket name
     * @param key    Blob key
     * @return true if blob exists
     */
    Promise<Boolean> exists(String bucket, String key);

    /**
     * Lists blobs in a bucket.
     *
     * @param bucket Bucket name
     * @return List of blob metadata
     */
    Promise<List<BlobMetadata>> list(String bucket);

    /**
     * Lists blobs in a bucket with a prefix filter.
     *
     * @param bucket Bucket name
     * @param prefix Key prefix to filter by
     * @return List of blob metadata matching the prefix
     */
    Promise<List<BlobMetadata>> listWithPrefix(String bucket, String prefix);

    /**
     * Copies a blob to a new location.
     *
     * @param sourceBucket Source bucket
     * @param sourceKey    Source key
     * @param destBucket   Destination bucket
     * @param destKey      Destination key
     * @return Metadata of the copied blob
     */
    Promise<BlobMetadata> copy(String sourceBucket, String sourceKey, String destBucket, String destKey);

    // ========== Factory Methods ==========
    /**
     * Creates an in-memory blob storage service with default configuration.
     */
    static BlobStorageService blobStorageService() {
        return InMemoryBlobStorageService.inMemoryBlobStorageService();
    }

    /**
     * Creates an in-memory blob storage service with custom configuration.
     */
    static BlobStorageService blobStorageService(BlobStorageConfig config) {
        return InMemoryBlobStorageService.inMemoryBlobStorageService(config);
    }

    // ========== Slice Lifecycle ==========
    @Override
    default Promise<Unit> start() {
        return Promise.success(Unit.unit());
    }

    @Override
    default Promise<Unit> stop() {
        return Promise.success(Unit.unit());
    }

    @Override
    default List<SliceMethod< ?, ?>> methods() {
        return List.of();
    }
}
