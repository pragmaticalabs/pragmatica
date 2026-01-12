package org.pragmatica.aether.infra.blob;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;

/**
 * In-memory implementation of BlobStorageService.
 * Uses ConcurrentHashMap for thread-safe storage.
 */
final class InMemoryBlobStorageService implements BlobStorageService {
    private static final BlobStorageConfig DEFAULT_CONFIG = new BlobStorageConfig("default",
                                                                                  100 * 1024 * 1024,
                                                                                  "default");

    private final BlobStorageConfig config;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    private InMemoryBlobStorageService(BlobStorageConfig config) {
        this.config = config;
    }

    static InMemoryBlobStorageService inMemoryBlobStorageService() {
        return new InMemoryBlobStorageService(getDefaultConfig());
    }

    private static BlobStorageConfig getDefaultConfig() {
        return BlobStorageConfig.blobStorageConfig()
                                .fold(err -> DEFAULT_CONFIG, config -> config);
    }

    static InMemoryBlobStorageService inMemoryBlobStorageService(BlobStorageConfig config) {
        return new InMemoryBlobStorageService(config);
    }

    // ========== Bucket Operations ==========
    @Override
    public Promise<Unit> createBucket(String bucketName) {
        var bucket = new Bucket(bucketName);
        return option(buckets.putIfAbsent(bucketName, bucket))
                     .fold(() -> Promise.success(unit()),
                           existing -> BlobStorageError.bucketAlreadyExists(bucketName)
                                                       .promise());
    }

    @Override
    public Promise<Boolean> deleteBucket(String bucketName) {
        return getBucketOrFail(bucketName)
                              .flatMap(bucket -> deleteBucketIfEmpty(bucketName, bucket));
    }

    private Promise<Boolean> deleteBucketIfEmpty(String bucketName, Bucket bucket) {
        return bucket.isEmpty()
               ? Promise.success(option(buckets.remove(bucketName))
                                       .isPresent())
               : BlobStorageError.storageFailed("Cannot delete non-empty bucket: " + bucketName)
                                 .promise();
    }

    @Override
    public Promise<Boolean> bucketExists(String bucketName) {
        return Promise.success(buckets.containsKey(bucketName));
    }

    @Override
    public Promise<List<String>> listBuckets() {
        return Promise.success(List.copyOf(buckets.keySet()));
    }

    // ========== Blob Operations ==========
    @Override
    public Promise<BlobMetadata> put(String bucketName, String key, byte[] data, String contentType) {
        return put(bucketName, key, data, contentType, Map.of());
    }

    @Override
    public Promise<BlobMetadata> put(String bucketName,
                                     String key,
                                     byte[] data,
                                     String contentType,
                                     Map<String, String> customMetadata) {
        return validateBlobSize(key, data)
                               .flatMap(unit -> getBucketOrFail(bucketName))
                               .map(bucket -> bucket.put(key, data, contentType, customMetadata));
    }

    private Promise<Unit> validateBlobSize(String key, byte[] data) {
        return data.length <= config.maxBlobSize()
               ? Promise.success(unit())
               : BlobStorageError.blobTooLarge(key,
                                               data.length,
                                               config.maxBlobSize())
                                 .promise();
    }

    @Override
    public Promise<Option<BlobContent>> get(String bucketName, String key) {
        return getBucketOrFail(bucketName)
                              .map(bucket -> bucket.get(key));
    }

    @Override
    public Promise<Option<BlobMetadata>> getMetadata(String bucketName, String key) {
        return getBucketOrFail(bucketName)
                              .map(bucket -> bucket.getMetadata(key));
    }

    @Override
    public Promise<Boolean> delete(String bucketName, String key) {
        return getBucketOrFail(bucketName)
                              .map(bucket -> bucket.delete(key));
    }

    @Override
    public Promise<Boolean> exists(String bucketName, String key) {
        return getBucketOrFail(bucketName)
                              .map(bucket -> bucket.exists(key));
    }

    @Override
    public Promise<List<BlobMetadata>> list(String bucketName) {
        return getBucketOrFail(bucketName)
                              .map(Bucket::list);
    }

    @Override
    public Promise<List<BlobMetadata>> listWithPrefix(String bucketName, String prefix) {
        return getBucketOrFail(bucketName)
                              .map(bucket -> bucket.listWithPrefix(prefix));
    }

    @Override
    public Promise<BlobMetadata> copy(String sourceBucket,
                                      String sourceKey,
                                      String destBucket,
                                      String destKey) {
        return get(sourceBucket, sourceKey)
                  .flatMap(opt -> copyBlob(opt, destBucket, destKey));
    }

    private Promise<BlobMetadata> copyBlob(Option<BlobContent> sourceOpt,
                                           String destBucket,
                                           String destKey) {
        return sourceOpt.fold(() -> BlobStorageError.blobNotFound(destBucket, destKey)
                                                    .promise(),
                              source -> put(destBucket,
                                            destKey,
                                            source.data(),
                                            source.metadata()
                                                  .contentType(),
                                            source.metadata()
                                                  .customMetadata()));
    }

    // ========== Lifecycle ==========
    @Override
    public Promise<Unit> stop() {
        buckets.clear();
        return Promise.success(unit());
    }

    // ========== Internal Helpers ==========
    private Promise<Bucket> getBucketOrFail(String bucketName) {
        return option(buckets.get(bucketName))
                     .fold(() -> BlobStorageError.bucketNotFound(bucketName)
                                                 .<Bucket> promise(),
                           Promise::success);
    }

    // ========== Internal Classes ==========
    private static final class Bucket {
        private final String name;
        private final ConcurrentHashMap<String, StoredBlob> blobs = new ConcurrentHashMap<>();

        Bucket(String name) {
            this.name = name;
        }

        BlobMetadata put(String key, byte[] data, String contentType, Map<String, String> customMetadata) {
            var metadata = BlobMetadata.blobMetadata(name, key, data.length, contentType, customMetadata);
            var blob = new StoredBlob(metadata, Arrays.copyOf(data, data.length));
            blobs.put(key, blob);
            return metadata;
        }

        Option<BlobContent> get(String key) {
            return option(blobs.get(key))
                         .map(blob -> BlobContent.blobContent(blob.metadata, blob.data));
        }

        Option<BlobMetadata> getMetadata(String key) {
            return option(blobs.get(key))
                         .map(blob -> blob.metadata);
        }

        boolean delete(String key) {
            return option(blobs.remove(key))
                         .isPresent();
        }

        boolean exists(String key) {
            return blobs.containsKey(key);
        }

        List<BlobMetadata> list() {
            return blobs.values()
                        .stream()
                        .map(blob -> blob.metadata)
                        .toList();
        }

        List<BlobMetadata> listWithPrefix(String prefix) {
            return blobs.entrySet()
                        .stream()
                        .filter(e -> e.getKey()
                                      .startsWith(prefix))
                        .map(e -> e.getValue().metadata)
                        .toList();
        }

        boolean isEmpty() {
            return blobs.isEmpty();
        }
    }

    private record StoredBlob(BlobMetadata metadata, byte[] data) {}
}
