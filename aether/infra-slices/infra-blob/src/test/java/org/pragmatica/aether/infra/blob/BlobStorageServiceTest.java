package org.pragmatica.aether.infra.blob;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BlobStorageServiceTest {
    private BlobStorageService storage;

    @BeforeEach
    void setUp() {
        storage = BlobStorageService.blobStorageService();
    }

    // ========== Bucket Operations ==========

    @Test
    void createBucket_succeeds() {
        storage.createBucket("test-bucket")
               .await()
               .onFailureRun(Assertions::fail);
    }

    @Test
    void createBucket_fails_whenBucketExists() {
        storage.createBucket("test-bucket")
               .flatMap(unit -> storage.createBucket("test-bucket"))
               .await()
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isInstanceOf(BlobStorageError.BucketAlreadyExists.class));
    }

    @Test
    void deleteBucket_succeeds_whenEmpty() {
        storage.createBucket("test-bucket")
               .flatMap(unit -> storage.deleteBucket("test-bucket"))
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(deleted -> assertThat(deleted).isTrue());
    }

    @Test
    void deleteBucket_fails_whenNotEmpty() {
        storage.createBucket("test-bucket")
               .flatMap(unit -> storage.put("test-bucket", "file.txt", "data".getBytes(), "text/plain"))
               .flatMap(meta -> storage.deleteBucket("test-bucket"))
               .await()
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isInstanceOf(BlobStorageError.StorageFailed.class));
    }

    @Test
    void deleteBucket_fails_whenNotExists() {
        storage.deleteBucket("nonexistent")
               .await()
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isInstanceOf(BlobStorageError.BucketNotFound.class));
    }

    @Test
    void bucketExists_returnsTrue_afterCreate() {
        storage.createBucket("test-bucket")
               .flatMap(unit -> storage.bucketExists("test-bucket"))
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(exists -> assertThat(exists).isTrue());
    }

    @Test
    void bucketExists_returnsFalse_whenNotExists() {
        storage.bucketExists("nonexistent")
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(exists -> assertThat(exists).isFalse());
    }

    @Test
    void listBuckets_returnsAllBuckets() {
        storage.createBucket("bucket1")
               .flatMap(unit -> storage.createBucket("bucket2"))
               .flatMap(unit -> storage.listBuckets())
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(buckets -> assertThat(buckets).containsExactlyInAnyOrder("bucket1", "bucket2"));
    }

    // ========== Blob Put/Get Operations ==========

    @Test
    void put_succeeds() {
        var data = "Hello, World!".getBytes(StandardCharsets.UTF_8);

        storage.createBucket("test-bucket")
               .flatMap(unit -> storage.put("test-bucket", "hello.txt", data, "text/plain"))
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(meta -> {
                   assertThat(meta.bucket()).isEqualTo("test-bucket");
                   assertThat(meta.key()).isEqualTo("hello.txt");
                   assertThat(meta.size()).isEqualTo(data.length);
                   assertThat(meta.contentType()).isEqualTo("text/plain");
               });
    }

    @Test
    void put_withCustomMetadata_succeeds() {
        var data = "content".getBytes();
        var customMeta = Map.of("author", "test", "version", "1.0");

        storage.createBucket("test-bucket")
               .flatMap(unit -> storage.put("test-bucket", "file.txt", data, "text/plain", customMeta))
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(meta -> {
                   assertThat(meta.customMetadata()).containsEntry("author", "test");
                   assertThat(meta.customMetadata()).containsEntry("version", "1.0");
               });
    }

    @Test
    void put_fails_whenBucketNotExists() {
        storage.put("nonexistent", "file.txt", "data".getBytes(), "text/plain")
               .await()
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isInstanceOf(BlobStorageError.BucketNotFound.class));
    }

    @Test
    void put_overwrites_existingBlob() {
        var data1 = "first".getBytes();
        var data2 = "second".getBytes();

        storage.createBucket("test-bucket")
               .flatMap(unit -> storage.put("test-bucket", "file.txt", data1, "text/plain"))
               .flatMap(meta -> storage.put("test-bucket", "file.txt", data2, "text/plain"))
               .flatMap(meta -> storage.get("test-bucket", "file.txt"))
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(opt -> opt.onPresent(content ->
                   assertThat(new String(content.data())).isEqualTo("second")));
    }

    @Test
    void get_returnsBlob_whenExists() {
        var data = "test content".getBytes(StandardCharsets.UTF_8);

        storage.createBucket("test-bucket")
               .flatMap(unit -> storage.put("test-bucket", "file.txt", data, "text/plain"))
               .flatMap(meta -> storage.get("test-bucket", "file.txt"))
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(opt -> {
                   assertThat(opt.isPresent()).isTrue();
                   opt.onPresent(content -> {
                       assertThat(content.data()).isEqualTo(data);
                       assertThat(content.metadata().contentType()).isEqualTo("text/plain");
                   });
               });
    }

    @Test
    void get_returnsEmpty_whenNotExists() {
        storage.createBucket("test-bucket")
               .flatMap(unit -> storage.get("test-bucket", "nonexistent.txt"))
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(opt -> assertThat(opt.isPresent()).isFalse());
    }

    @Test
    void getMetadata_returnsMetadata_withoutData() {
        var data = "test content".getBytes();

        storage.createBucket("test-bucket")
               .flatMap(unit -> storage.put("test-bucket", "file.txt", data, "application/octet-stream"))
               .flatMap(meta -> storage.getMetadata("test-bucket", "file.txt"))
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(opt -> {
                   assertThat(opt.isPresent()).isTrue();
                   opt.onPresent(meta -> {
                       assertThat(meta.size()).isEqualTo(data.length);
                       assertThat(meta.contentType()).isEqualTo("application/octet-stream");
                   });
               });
    }

    // ========== Blob Delete/Exists Operations ==========

    @Test
    void delete_succeeds_whenExists() {
        storage.createBucket("test-bucket")
               .flatMap(unit -> storage.put("test-bucket", "file.txt", "data".getBytes(), "text/plain"))
               .flatMap(meta -> storage.delete("test-bucket", "file.txt"))
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(deleted -> assertThat(deleted).isTrue());
    }

    @Test
    void delete_returnsFalse_whenNotExists() {
        storage.createBucket("test-bucket")
               .flatMap(unit -> storage.delete("test-bucket", "nonexistent.txt"))
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(deleted -> assertThat(deleted).isFalse());
    }

    @Test
    void exists_returnsTrue_whenBlobExists() {
        storage.createBucket("test-bucket")
               .flatMap(unit -> storage.put("test-bucket", "file.txt", "data".getBytes(), "text/plain"))
               .flatMap(meta -> storage.exists("test-bucket", "file.txt"))
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(exists -> assertThat(exists).isTrue());
    }

    @Test
    void exists_returnsFalse_whenBlobNotExists() {
        storage.createBucket("test-bucket")
               .flatMap(unit -> storage.exists("test-bucket", "nonexistent.txt"))
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(exists -> assertThat(exists).isFalse());
    }

    // ========== List Operations ==========

    @Test
    void list_returnsAllBlobs() {
        storage.createBucket("test-bucket")
               .flatMap(unit -> storage.put("test-bucket", "file1.txt", "1".getBytes(), "text/plain"))
               .flatMap(meta -> storage.put("test-bucket", "file2.txt", "2".getBytes(), "text/plain"))
               .flatMap(meta -> storage.put("test-bucket", "file3.txt", "3".getBytes(), "text/plain"))
               .flatMap(meta -> storage.list("test-bucket"))
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(blobs -> {
                   assertThat(blobs).hasSize(3);
                   assertThat(blobs.stream().map(BlobMetadata::key).toList())
                       .containsExactlyInAnyOrder("file1.txt", "file2.txt", "file3.txt");
               });
    }

    @Test
    void listWithPrefix_returnsMatchingBlobs() {
        storage.createBucket("test-bucket")
               .flatMap(unit -> storage.put("test-bucket", "docs/readme.txt", "1".getBytes(), "text/plain"))
               .flatMap(meta -> storage.put("test-bucket", "docs/guide.txt", "2".getBytes(), "text/plain"))
               .flatMap(meta -> storage.put("test-bucket", "images/logo.png", "3".getBytes(), "image/png"))
               .flatMap(meta -> storage.listWithPrefix("test-bucket", "docs/"))
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(blobs -> {
                   assertThat(blobs).hasSize(2);
                   assertThat(blobs.stream().map(BlobMetadata::key).toList())
                       .containsExactlyInAnyOrder("docs/readme.txt", "docs/guide.txt");
               });
    }

    // ========== Copy Operations ==========

    @Test
    void copy_succeeds() {
        var data = "copy me".getBytes();

        storage.createBucket("source-bucket")
               .flatMap(unit -> storage.createBucket("dest-bucket"))
               .flatMap(unit -> storage.put("source-bucket", "original.txt", data, "text/plain"))
               .flatMap(meta -> storage.copy("source-bucket", "original.txt", "dest-bucket", "copied.txt"))
               .flatMap(meta -> storage.get("dest-bucket", "copied.txt"))
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(opt -> {
                   assertThat(opt.isPresent()).isTrue();
                   opt.onPresent(content -> assertThat(content.data()).isEqualTo(data));
               });
    }

    @Test
    void copy_fails_whenSourceNotExists() {
        storage.createBucket("source-bucket")
               .flatMap(unit -> storage.createBucket("dest-bucket"))
               .flatMap(unit -> storage.copy("source-bucket", "nonexistent.txt", "dest-bucket", "copied.txt"))
               .await()
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isInstanceOf(BlobStorageError.BlobNotFound.class));
    }

    @Test
    void copy_withinSameBucket() {
        var data = "duplicate".getBytes();

        storage.createBucket("test-bucket")
               .flatMap(unit -> storage.put("test-bucket", "original.txt", data, "text/plain"))
               .flatMap(meta -> storage.copy("test-bucket", "original.txt", "test-bucket", "backup.txt"))
               .flatMap(meta -> storage.list("test-bucket"))
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(blobs -> assertThat(blobs).hasSize(2));
    }

    // ========== Config Operations ==========

    @Test
    void blobStorageConfig_createsDefaults() {
        BlobStorageConfig.blobStorageConfig()
                         .onFailureRun(Assertions::fail)
                         .onSuccess(config -> {
                             assertThat(config.name()).isEqualTo("default");
                             assertThat(config.maxBlobSize()).isEqualTo(100 * 1024 * 1024);
                             assertThat(config.defaultBucket()).isEqualTo("default");
                         });
    }

    @Test
    void blobStorageConfig_validatesName() {
        BlobStorageConfig.blobStorageConfig("")
                         .onSuccessRun(Assertions::fail)
                         .onFailure(cause -> assertThat(cause.message()).contains("name cannot be null or empty"));
    }

    @Test
    void blobStorageConfig_withMethods_createNewConfig() {
        BlobStorageConfig.blobStorageConfig()
                         .map(c -> c.withName("custom").withMaxBlobSize(50 * 1024 * 1024))
                         .onFailureRun(Assertions::fail)
                         .onSuccess(config -> {
                             assertThat(config.name()).isEqualTo("custom");
                             assertThat(config.maxBlobSize()).isEqualTo(50 * 1024 * 1024);
                         });
    }

    // ========== Size Limit Operations ==========

    @Test
    void put_fails_whenBlobTooLarge() {
        var smallConfig = BlobStorageConfig.blobStorageConfig()
                                           .map(c -> c.withMaxBlobSize(10))
                                           .fold(err -> null, c -> c);
        var smallStorage = BlobStorageService.blobStorageService(smallConfig);
        var largeData = new byte[100];

        smallStorage.createBucket("test-bucket")
                    .flatMap(unit -> smallStorage.put("test-bucket", "large.bin", largeData, "application/octet-stream"))
                    .await()
                    .onSuccessRun(Assertions::fail)
                    .onFailure(cause -> assertThat(cause).isInstanceOf(BlobStorageError.BlobTooLarge.class));
    }

    // ========== Lifecycle Operations ==========

    @Test
    void stop_clearsAllData() {
        storage.createBucket("test-bucket")
               .flatMap(unit -> storage.put("test-bucket", "file.txt", "data".getBytes(), "text/plain"))
               .flatMap(meta -> storage.stop())
               .flatMap(unit -> storage.listBuckets())
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(buckets -> assertThat(buckets).isEmpty());
    }
}
