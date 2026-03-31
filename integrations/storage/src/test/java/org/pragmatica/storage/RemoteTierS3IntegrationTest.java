package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.cloud.aws.s3.S3Client;
import org.pragmatica.cloud.aws.s3.S3Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// S3 integration tests using MinIO in a Testcontainer.
/// Guarded with @Tag("S3") so they do not run in regular `mvn verify`.
/// Run with: mvn verify -pl integrations/storage -Dgroups=S3
@Tag("S3")
@Testcontainers
class RemoteTierS3IntegrationTest {

    private static final String BUCKET = "test-storage";
    private static final String REGION = "us-east-1";
    private static final String ACCESS_KEY = "minioadmin";
    private static final String SECRET_KEY = "minioadmin";
    private static final String PREFIX = "blocks";
    private static final long MAX_BYTES = 100 * 1024 * 1024;

    private static final byte[] CONTENT_A = "s3-integration-alpha-content".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONTENT_B = "s3-integration-bravo-content".getBytes(StandardCharsets.UTF_8);

    @Container
    static final GenericContainer<?> minio = new GenericContainer<>("minio/minio:latest")
        .withExposedPorts(9000)
        .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
        .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
        .withCommand("server /data")
        .waitingFor(new HttpWaitStrategy()
                        .forPort(9000)
                        .forPath("/minio/health/ready"));

    private static S3Client s3Client;
    private RemoteTier tier;

    @BeforeAll
    static void createBucket() {
        var endpoint = "http://" + minio.getHost() + ":" + minio.getMappedPort(9000);
        var config = S3Config.s3Config(endpoint, BUCKET, REGION, ACCESS_KEY, SECRET_KEY);
        s3Client = S3Client.s3Client(config);

        // Create the bucket by putting and deleting a marker object.
        // MinIO with path-style URLs auto-creates buckets on first PUT.
        s3Client.putObject("_init", new byte[0], "application/octet-stream").await()
                .onFailure(c -> fail("Bucket initialization failed: " + c.message()));
        s3Client.deleteObject("_init").await();
    }

    @BeforeEach
    void setUp() {
        tier = RemoteTier.remoteTier(s3Client, PREFIX, MAX_BYTES);
    }

    private static BlockId blockIdOf(byte[] content) {
        return BlockId.blockId(content)
                      .fold(_ -> { fail("BlockId creation failed"); return null; },
                            id -> id);
    }

    @Nested
    class RoundTripTests {

        @Test
        void putGet_roundTrip_throughMinIO() {
            var id = blockIdOf(CONTENT_A);

            tier.put(id, CONTENT_A).await()
                .onFailure(c -> fail("put failed: " + c.message()));

            tier.get(id).await()
                .onFailure(c -> fail("get failed: " + c.message()))
                .onSuccess(opt -> {
                    assertThat(opt.isPresent()).isTrue();
                    opt.onPresent(data -> assertThat(data).isEqualTo(CONTENT_A));
                });
        }
    }

    @Nested
    class ExistsTests {

        @Test
        void exists_afterPut_returnsTrue() {
            var id = blockIdOf(CONTENT_B);

            tier.put(id, CONTENT_B).await()
                .onFailure(c -> fail("put failed: " + c.message()));

            tier.exists(id).await()
                .onFailure(c -> fail("exists failed: " + c.message()))
                .onSuccess(found -> assertThat(found).isTrue());
        }

        @Test
        void exists_missing_returnsFalse() {
            var id = blockIdOf("nonexistent-s3-content".getBytes(StandardCharsets.UTF_8));

            tier.exists(id).await()
                .onFailure(c -> fail("exists failed: " + c.message()))
                .onSuccess(found -> assertThat(found).isFalse());
        }
    }

    @Nested
    class DeleteTests {

        @Test
        void delete_removesBlock() {
            var id = blockIdOf(CONTENT_A);

            tier.put(id, CONTENT_A).await()
                .onFailure(c -> fail("put failed: " + c.message()));

            tier.delete(id).await()
                .onFailure(c -> fail("delete failed: " + c.message()));

            tier.get(id).await()
                .onFailure(c -> fail("get after delete failed: " + c.message()))
                .onSuccess(opt -> assertThat(opt.isEmpty()).isTrue());
        }
    }

    @Nested
    class ListTests {

        @Test
        void listObjects_returnsKeys() {
            var idA = blockIdOf(CONTENT_A);
            var idB = blockIdOf(CONTENT_B);

            tier.put(idA, CONTENT_A).await()
                .onFailure(c -> fail("put A failed: " + c.message()));
            tier.put(idB, CONTENT_B).await()
                .onFailure(c -> fail("put B failed: " + c.message()));

            s3Client.listObjects(PREFIX + "/", 100).await()
                    .onFailure(c -> fail("listObjects failed: " + c.message()))
                    .onSuccess(keys -> assertThat(keys).hasSizeGreaterThanOrEqualTo(2));
        }
    }
}
