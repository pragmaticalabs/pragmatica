package org.pragmatica.storage;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.cloud.aws.AwsConfig;
import org.pragmatica.cloud.aws.AwsCredentials;
import org.pragmatica.cloud.aws.AwsCredentialsProvider;
import org.pragmatica.cloud.aws.AwsSigV4Signer;
import org.pragmatica.cloud.aws.s3.S3Client;
import org.pragmatica.cloud.aws.s3.S3Config;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.http.JdkHttpOperations;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// S3 integration tests against a real S3-compatible endpoint: MinIO in a Testcontainer by default (the
/// owner-specified simulator; image overridable with `aether.s3.image`), or any endpoint named by system properties, so the same tests can point at
/// another S3-compatible store without code changes:
/// `aether.s3.endpoint`, `aether.s3.region`, `aether.s3.bucket`, `aether.s3.accessKey`, `aether.s3.secretKey`,
/// `aether.s3.pathStyle`. Every test writes under its own random prefix.
///
/// Guarded with @Tag("S3"), excluded from the default build by the root pom's `exclude-tagged-tests` profile.
/// Run with: `mvn test -pl integrations/storage -Dgroups=S3 -Dsurefire.excludedGroups=` (the second flag clears
/// the exclusion; `-Dgroups=S3` alone selects nothing, #2256).
@Tag("S3")
class RemoteTierS3IntegrationTest {
    private static final String REGION = System.getProperty("aether.s3.region", "us-east-1");
    private static final String BUCKET = System.getProperty("aether.s3.bucket", "aether-it");
    private static final String ACCESS_KEY = System.getProperty("aether.s3.accessKey", "minioadmin");
    private static final String SECRET_KEY = System.getProperty("aether.s3.secretKey", "minioadmin");
    private static final boolean PATH_STYLE = Boolean.parseBoolean(System.getProperty("aether.s3.pathStyle", "true"));
    private static final int CHUNK = 64 * 1024;
    /// MinIO's own images left the public registries in 2025 (`minio/minio` and `quay.io/minio/minio` no longer
    /// pull anonymously). Chainguard builds the same MinIO server from source and publishes it openly; its
    /// entrypoint is the `minio` binary and it runs as a non-root user, hence `server /tmp/minio-data`.
    /// Pinned by digest, not `:latest`: the test pulls third-party code into CI, and a moving tag would run whatever
    /// was pushed last, unreviewed (supply chain) and unreproducible. Chainguard's free tier serves only `latest`,
    /// so the digest is the only stable reference; bump it deliberately. `-Daether.s3.image` overrides it.
    private static final String MINIO_DIGEST = "cgr.dev/chainguard/minio@sha256:709099319add48dfb44aa596a7070d3ddd8dd04d7ae881765719aec29df9e510";
    private static final String MINIO_IMAGE = System.getProperty("aether.s3.image", MINIO_DIGEST);

    private static final byte[] CONTENT_A = "s3-integration-alpha-content".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONTENT_B = "s3-integration-bravo-content".getBytes(StandardCharsets.UTF_8);

    private static GenericContainer<?> minio;
    private static String endpoint;
    private static S3Client s3Client;

    @TempDir
    Path dir;

    private String prefix;
    private RemoteTier tier;

    @BeforeAll
    static void startEndpoint() {
        endpoint = Option.option(System.getProperty("aether.s3.endpoint"))
                         .filter(value -> !value.isBlank())
                         .or(RemoteTierS3IntegrationTest::startMinio);
        s3Client = clientWith(JdkHttpOperations.jdkHttpOperations());
        createBucket();
    }

    @AfterAll
    static void stopEndpoint() {
        Option.option(minio).onPresent(GenericContainer::stop);
    }

    private static String startMinio() {
        minio = new GenericContainer<>(MINIO_IMAGE).withExposedPorts(9000)
                                                   .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
                                                   .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
                                                   .withCommand("server", "/tmp/minio-data")
                                                   .waitingFor(new HttpWaitStrategy().forPort(9000)
                                                                                     .forPath("/minio/health/ready"));
        minio.start();

        return "http://" + minio.getHost() + ":" + minio.getMappedPort(9000);
    }

    /// CreateBucket is not part of [S3Client] (nothing in production creates buckets), so it is signed here.
    /// An existing bucket (409 BucketAlreadyOwnedByYou) is fine.
    private static void createBucket() {
        var url = config().bucketUrl();
        var signed = AwsSigV4Signer.sign(AwsConfig.awsConfig(ACCESS_KEY, SECRET_KEY, REGION), "s3", "PUT", url, Map.of(), new byte[0])
                                   .fold(cause -> fail(cause.message()), headers -> headers);
        var builder = HttpRequest.newBuilder().uri(URI.create(url)).PUT(HttpRequest.BodyPublishers.noBody());
        signed.forEach(builder::header);
        JdkHttpOperations.jdkHttpOperations()
                         .sendString(builder.build())
                         .await()
                         .onFailure(cause -> fail("bucket creation failed: " + cause.message()))
                         .onSuccess(result -> assertThat(result.statusCode()).as(result.body()).isIn(200, 409));
    }

    private static S3Config config() {
        return S3Config.s3Endpoint(endpoint, BUCKET, REGION, PATH_STYLE);
    }

    private static S3Client clientWith(HttpOperations http) {
        return S3Client.s3Client(config(),
                                 http,
                                 AwsCredentialsProvider.staticCredentials(AwsCredentials.awsCredentials(ACCESS_KEY, SECRET_KEY)),
                                 some(Duration.ofSeconds(10)));
    }

    private static RemoteTier durableTier(S3Client client, String prefix) {
        return RemoteTier.durableRemoteTier(client, prefix, 8, timeSpan(10).seconds())
                         .fold(cause -> fail(cause.message()), tier -> tier);
    }

    @BeforeEach
    void setUp() {
        prefix = "it-" + UUID.randomUUID() + "/";
        tier = durableTier(s3Client, prefix);
    }

    private static BlockId blockIdOf(byte[] content) {
        return BlockId.blockId(content).fold(cause -> fail(cause.message()), id -> id);
    }

    private String keyOf(BlockId id) {
        return prefix + id.shardPrefix() + "/" + id.hexString();
    }

    @Nested
    class TierOperations {

        @Test
        void putGet_roundTrip_underThePrefix() {
            var id = blockIdOf(CONTENT_A);

            tier.put(id, CONTENT_A).await().onFailure(c -> fail("put failed: " + c.message()));

            tier.get(id).await()
                .onFailure(c -> fail("get failed: " + c.message()))
                .onSuccess(opt -> assertThat(opt.or(new byte[0])).isEqualTo(CONTENT_A));
            s3Client.headObject(keyOf(id)).await().onSuccess(found -> assertThat(found).isTrue());
        }

        @Test
        void exists_afterPut_trueAndMissing_false() {
            var id = blockIdOf(CONTENT_B);

            tier.put(id, CONTENT_B).await().onFailure(c -> fail("put failed: " + c.message()));

            tier.exists(id).await().onSuccess(found -> assertThat(found).isTrue());
            tier.exists(blockIdOf("never-written".getBytes(StandardCharsets.UTF_8)))
                .await()
                .onFailure(c -> fail("exists failed: " + c.message()))
                .onSuccess(found -> assertThat(found).isFalse());
        }

        @Test
        void delete_removesBlock() {
            var id = blockIdOf(CONTENT_A);

            tier.put(id, CONTENT_A).await().onFailure(c -> fail("put failed: " + c.message()));
            tier.delete(id).await().onFailure(c -> fail("delete failed: " + c.message()));

            tier.get(id).await()
                .onFailure(c -> fail("get after delete failed: " + c.message()))
                .onSuccess(opt -> assertThat(opt.isEmpty()).isTrue());
        }

        @Test
        void listObjects_returnsTheBlocksUnderThePrefix() {
            tier.put(blockIdOf(CONTENT_A), CONTENT_A).await().onFailure(c -> fail(c.message()));
            tier.put(blockIdOf(CONTENT_B), CONTENT_B).await().onFailure(c -> fail(c.message()));

            s3Client.listObjects(prefix, 100).await()
                    .onFailure(c -> fail("listObjects failed: " + c.message()))
                    .onSuccess(keys -> assertThat(keys).hasSize(2).allMatch(key -> key.startsWith(prefix)));
        }

        @Test
        void probe_succeeds_andLeavesNoObject() {
            tier.probe("node-it").await().onFailure(c -> fail("probe failed: " + c.message()));

            s3Client.listObjects(prefix, 100).await().onSuccess(keys -> assertThat(keys).isEmpty());
        }

        /// A missing bucket is a rejection (terminal), never an absent block.
        @Test
        void get_missingBucket_isRejectedNotAbsent() {
            var client = S3Client.s3Client(S3Config.s3Endpoint(endpoint, "no-such-bucket-" + UUID.randomUUID(), REGION, PATH_STYLE),
                                           JdkHttpOperations.jdkHttpOperations(),
                                           AwsCredentialsProvider.staticCredentials(AwsCredentials.awsCredentials(ACCESS_KEY, SECRET_KEY)),
                                           Option.none());

            durableTier(client, prefix).get(blockIdOf(CONTENT_A))
                                       .await()
                                       .onSuccess(opt -> fail("a missing bucket must fail, got " + opt))
                                       .onFailure(cause -> assertThat(cause).isInstanceOf(RemoteTierError.Rejected.class));
        }

        @Test
        void probe_wrongCredentials_fails() {
            var client = S3Client.s3Client(config(),
                                           JdkHttpOperations.jdkHttpOperations(),
                                           AwsCredentialsProvider.staticCredentials(AwsCredentials.awsCredentials("wrong", "wrong-secret")),
                                           Option.none());

            durableTier(client, prefix).probe("node-it")
                                       .await()
                                       .onSuccess(_ -> fail("wrong credentials must fail the probe"))
                                       .onFailure(cause -> assertThat(cause).isInstanceOf(RemoteTierError.Rejected.class));
        }
    }

    @Nested
    class StorageInstanceOverS3 {

        @Test
        void putGetDelete_roundTrip_withS3AsLastTier() {
            var disk = LocalDiskTier.localDiskTier(dir.resolve("disk"), 1 << 24).flatMap(LocalDiskTier::open)
                                    .fold(cause -> fail(cause.message()), d -> d);
            var storage = StorageInstance.storageInstance("content", List.of(MemoryTier.memoryTier(1 << 20), disk, tier));

            var id = storage.put(CONTENT_A).await().fold(cause -> fail(cause.message()), blockId -> blockId);
            s3Client.getObject(keyOf(id)).await()
                    .onSuccess(raw -> assertThat(raw.or(new byte[0])).as("the acknowledged block is in the bucket").isEqualTo(CONTENT_A));
            storage.get(id).await().onSuccess(opt -> assertThat(opt.or(new byte[0])).isEqualTo(CONTENT_A));

            storage.delete(id).await().onFailure(cause -> fail(cause.message()));

            s3Client.headObject(keyOf(id)).await().onSuccess(found -> assertThat(found).as("delete reaches the bucket").isFalse());
        }

        /// A multi-chunk ContentStore object written through memory + S3 reads back from S3 alone (a second
        /// instance over the same metadata, with no other tier), and a corrupted chunk in the bucket fails the
        /// read with an integrity error instead of returning wrong bytes.
        @Test
        void contentStore_multiChunk_readsBackFromS3_andVerifiesIntegrity() {
            var metadata = MetadataStore.inMemoryMetadataStore("content");
            var writer = StorageInstance.storageInstance("content", List.of(MemoryTier.memoryTier(1 << 22), tier), metadata);
            var payload = new byte[5 * CHUNK + 1234];
            new Random(42).nextBytes(payload);
            var config = ContentStoreConfig.contentStoreConfig(CHUNK, Compression.NONE);

            NamedContent.namedContent(writer, config).put("video.bin", payload).await().onFailure(cause -> fail(cause.message()));

            var s3Only = StorageInstance.storageInstance("content", List.of(durableTier(s3Client, prefix)), metadata);
            var reader = NamedContent.namedContent(s3Only, config);
            reader.get("video.bin").await()
                  .onFailure(cause -> fail(cause.message()))
                  .onSuccess(opt -> assertThat(Arrays.equals(opt.or(new byte[0]), payload)).as("read back from S3 alone").isTrue());

            var chunkKeys = s3Client.listObjects(prefix, 1000).await().fold(cause -> fail(cause.message()), keys -> keys);
            assertThat(chunkKeys).as("6 chunks, a manifest, a name binding and the 3 versions of the object entry that naming it wrote (none collected yet)").hasSize(11);
            var firstChunk = Arrays.copyOfRange(payload, 0, CHUNK);
            s3Client.putObject(keyOf(blockIdOf(firstChunk)), "tampered".getBytes(StandardCharsets.UTF_8), "application/octet-stream")
                    .await()
                    .onFailure(cause -> fail(cause.message()));

            var freshReader = NamedContent.namedContent(StorageInstance.storageInstance("content",
                                                                                           List.of(durableTier(s3Client, prefix)),
                                                                                           metadata),
                                                         config);
            freshReader.get("video.bin").await()
                       .onSuccess(opt -> fail("a tampered chunk must not read back"))
                       .onFailure(cause -> assertThat(cause).isInstanceOf(StorageError.IntegrityError.class));
        }
    }

    @Nested
    class AckSemantics {

        /// The ack point: a put resolves only after the S3 PUT was answered 200. The PUT is held at the HTTP
        /// layer; until released, the put stays pending and the object is absent; once released, the put resolves
        /// and the object is there. Mutation that reddens it: `RemoteTier#put` resolving before the PUT returns.
        @Test
        void put_resolvesOnlyAfterTheS3PutReturned() {
            var gated = new GatedHttp(JdkHttpOperations.jdkHttpOperations());
            var storage = StorageInstance.storageInstance("content",
                                                          List.of(MemoryTier.memoryTier(1 << 20), durableTier(clientWith(gated), prefix)));

            var put = storage.put(CONTENT_A);

            settle(() -> gated.held.size() == 1);
            assertThat(gated.held).as("the PUT reached the HTTP layer and is held").hasSize(1);
            assertThat(put.isResolved()).as("no ack while the PUT is outstanding").isFalse();
            s3Client.headObject(keyOf(blockIdOf(CONTENT_A))).await().onSuccess(found -> assertThat(found).isFalse());

            gated.releaseAll();

            put.await(timeSpan(10).seconds()).onFailure(cause -> fail(cause.message()));
            s3Client.headObject(keyOf(blockIdOf(CONTENT_A))).await().onSuccess(found -> assertThat(found).isTrue());
        }

        /// A failing endpoint fails the put (retriable, typed), and nothing is acknowledged.
        @Test
        void put_failingEndpoint_failsUnavailable() {
            var failing = new GatedHttp(JdkHttpOperations.jdkHttpOperations());
            failing.failPuts = true;
            var storage = StorageInstance.storageInstance("content",
                                                          List.of(MemoryTier.memoryTier(1 << 20), durableTier(clientWith(failing), prefix)));

            storage.put(CONTENT_A)
                   .await(timeSpan(10).seconds())
                   .onSuccess(_ -> fail("a failing endpoint must not acknowledge"))
                   .onFailure(cause -> {
                       assertThat(cause).isInstanceOf(RemoteTierError.Unavailable.class);
                       assertThat(cause.isTerminal()).isFalse();
                   });
        }

        /// Durability is what lets an instance whose only durable tier is S3 seal a log range, and the seal is
        /// acknowledged only once the block is in the bucket. Mutation that reddens it: `RemoteTier#isDurable`
        /// false for the production tier (the seal is refused with NoDurableTier).
        @Test
        void seal_onMemoryPlusS3_isAcknowledgedAfterTheBlockIsInTheBucket() {
            var storage = StorageInstance.storageInstance("streams",
                                                          List.of(MemoryTier.memoryTier(1 << 20), tier),
                                                          MetadataStore.inMemoryMetadataStore("streams"),
                                                          WritePolicy.WRITE_THROUGH,
                                                          some(dir.resolve("logs")));
            var wal = storage.openLog("orders/0").fold(cause -> fail(cause.message()), log -> log);
            IntStream.range(0, 3).forEach(i -> wal.append(i, new byte[]{(byte) i}, 1L).await().onFailure(cause -> fail(cause.message())));
            var block = "segment [0-2]".getBytes(StandardCharsets.UTF_8);

            storage.seal(wal, 0, 2, "streams/orders/0/0-2", block)
                   .await()
                   .onFailure(cause -> fail("a durable S3 tier must admit the seal: " + cause.message()));

            assertThat(wal.sealedThrough()).isEqualTo(2L);
            s3Client.headObject(keyOf(blockIdOf(block))).await().onSuccess(found -> assertThat(found).isTrue());
        }
    }

    private static void settle(BooleanSupplier condition) {
        var deadline = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    /// Delegates to a real transport, but holds every PUT until released, or answers it 503.
    static final class GatedHttp implements HttpOperations {
        private final HttpOperations delegate;
        final List<Promise<Unit>> held = new CopyOnWriteArrayList<>();
        volatile boolean failPuts;

        GatedHttp(HttpOperations delegate) {
            this.delegate = delegate;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Promise<HttpResult<T>> send(HttpRequest request, BodyHandler<T> handler) {
            if (!"PUT".equals(request.method())) {
                return delegate.send(request, handler);
            }
            if (failPuts) {
                return Promise.success(new HttpResult<>(503, HttpHeaders.of(Map.of(), (a, b) -> true),
                                                        (T) "<Error><Code>ServiceUnavailable</Code><Message>down</Message></Error>"));
            }
            var gate = Promise.<Unit>promise();
            held.add(gate);
            return gate.flatMap(_ -> delegate.send(request, handler));
        }

        void releaseAll() {
            held.forEach(gate -> gate.succeed(Unit.unit()));
        }
    }
}
