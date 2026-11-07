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
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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

    /// A3-0: the anchor layout depends on two endpoint properties the simulator only imitates -- keys listed in ascending
    /// order and a LIST that sees a key once its PUT returned. Checked here against the real endpoint, past one LIST page.
    @Nested
    class Anchors {
        private final AnchorName name = AnchorName.anchorName("ahse-dir/content").fold(cause -> fail(cause.message()), n -> n);

        @Test
        void latestIsTheHighestOfMoreVersionsThanOneListPage_writtenOutOfOrder_andPruneKeepsTheBound() {
            var versions = new java.util.ArrayList<>(IntStream.range(0, 1100).boxed().toList());

            java.util.Collections.shuffle(versions, new Random(7));
            versions.forEach(version -> tier.putAnchor(name, version, valueOf(version))
                                            .await()
                                            .onFailure(cause -> fail(cause.message())));

            assertLatest(1099);

            tier.pruneAnchors(name, 1090, 2000).await().onFailure(cause -> fail(cause.message()));
            assertThat(anchorKeys()).as("one page is the newest 1000 keys: versions 100..1089 deleted, 0..99 not yet listed")
                                    .hasSize(110);

            tier.pruneAnchors(name, 1090, 2000).await().onFailure(cause -> fail(cause.message()));
            assertThat(anchorKeys()).as("the next prune works off the rest of the backlog").hasSize(10);
            assertLatest(1099);
        }

        /// B-1 (v-2453) on the real endpoint: the empty "folder" object an S3 console creates at the anchor's prefix
        /// does not hide the newest version. RED at `fbc86d5a2d`, where it was the one key listed and the answer was none.
        @Test
        void aFolderPlaceholderAtTheAnchorsPrefix_doesNotHideTheNewestVersion() {
            tier.putAnchor(name, 7, valueOf(7)).await().onFailure(cause -> fail(cause.message()));
            s3Client.putObject(prefix + "anchors/" + name.storageSegment() + "/", new byte[0], "application/octet-stream")
                    .await()
                    .onFailure(cause -> fail(cause.message()));

            assertLatest(7);
        }

        /// B-1: an object under the version prefix that is no version key fails the read closed, never none.
        @Test
        void aStrayObjectUnderTheVersionPrefix_failsClosed() {
            tier.putAnchor(name, 7, valueOf(7)).await().onFailure(cause -> fail(cause.message()));
            s3Client.putObject(prefix + "anchors/" + name.storageSegment() + "/v.keep", new byte[0], "application/octet-stream")
                    .await()
                    .onFailure(cause -> fail(cause.message()));

            tier.latestAnchor(name)
                .await()
                .onSuccess(latest -> fail("a stray key must fail the read closed, got " + latest))
                .onFailure(cause -> assertThat(cause).isInstanceOf(AnchorError.AnchorKeyMalformed.class));
        }

        private List<String> anchorKeys() {
            return s3Client.listObjects(prefix + "anchors/", 1000).await().fold(cause -> fail(cause.message()), keys -> keys);
        }

        private void assertLatest(int version) {
            tier.latestAnchor(name)
                .await()
                .onFailure(cause -> fail(cause.message()))
                .onSuccess(latest -> {
                               assertThat(latest.unwrap().version()).isEqualTo(version);
                               assertThat(latest.unwrap().value()).isEqualTo(valueOf(version));
                           });
        }

        private static byte[] valueOf(long version) {
            return ("root-" + version).getBytes(StandardCharsets.UTF_8);
        }
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

    /// Last-Modified has whole-second precision: a refresh is visible only in a later second.
    private static void waitPastTheNextSecond() {
        LockSupport.parkNanos(Duration.ofMillis(1_100).toNanos());
    }

    /// A2b: the scan/stat/touch contract against the real endpoint -- Last-Modified from HEAD and from LIST, start-after
    /// paging in the endpoint's key order, and the touch as a marker object listed right after its block.
    @Nested
    class ScanStatTouch extends TierScanStatTouchContract {
        @Override
        protected StorageTier newTier() {
            return durableTier(s3Client, "it-" + UUID.randomUUID() + "/");
        }

        @Override
        protected void age(BlockId id) {
            waitPastTheNextSecond();
        }
    }

    /// G2 on the real endpoint, through [StorageInstance#ensureHeld]: the re-seed of a recorded block lost from the
    /// bucket lands in it, and the refresh of a held one moves its Last-Modified.
    @Nested
    class EnsureHeld {
        private final byte[] content = "re-seeded into the bucket".getBytes(StandardCharsets.UTF_8);

        private StorageInstance node() {
            var disk = LocalDiskTier.localDiskTier(dir, 1 << 20).flatMap(LocalDiskTier::open).fold(cause -> fail(cause.message()), d -> d);

            return StorageInstance.storageInstance("a2b-s3", List.of(disk, tier));
        }

        @Test
        void reseedOfARecordedButAbsentBlock_landsInTheBucket() {
            var node = node();
            var id = node.put(content).await().fold(cause -> fail(cause.message()), blockId -> blockId);

            tier.delete(id).await().onFailure(cause -> fail(cause.message()));
            assertThat(s3Client.headObject(keyOf(id)).await().<Boolean>fold(cause -> fail(cause.message()), held -> held)).as("precondition").isFalse();

            node.ensureHeld(id, TierLevel.REMOTE).await().onFailure(cause -> fail(cause.message()));

            assertThat(s3Client.getObject(keyOf(id)).await().fold(cause -> fail(cause.message()), found -> found).map(Arrays::toString))
                .isEqualTo(some(Arrays.toString(content)));
        }

        @Test
        void refreshOfAHeldBlock_movesItsLastModified() {
            var node = node();
            var id = node.put(content).await().fold(cause -> fail(cause.message()), blockId -> blockId);
            var before = tier.stat(id).await().fold(cause -> fail(cause.message()), stat -> stat).unwrap();

            waitPastTheNextSecond();
            node.ensureHeld(id, TierLevel.REMOTE).await().onFailure(cause -> fail(cause.message()));

            var after = tier.stat(id).await().fold(cause -> fail(cause.message()), stat -> stat).unwrap();

            assertThat(after.writtenAtMillis()).isGreaterThan(before.writtenAtMillis());
            assertThat(after.size()).isEqualTo(before.size());
        }
    }

    /// v2829 FIX 5: a touch racing a delete of the same block never brings the block back. The S3 touch never writes
    /// the block object -- it HEADs it and writes a marker beside it -- so no ordering of the two can recreate it: at worst
    /// the marker outlives the block. Two shapes, 50 rounds each: the touch and the delete issued together, and the
    /// delete sent the moment the touch sends its write, after its HEAD. A touch that wrote the block -- a copy of the
    /// object onto itself, even conditional on its ETag -- left it in 35 and 43 of 50 rounds of the second shape on
    /// MinIO.
    @Nested
    class TouchRacingDelete {
        private static final int ROUNDS = 50;

        @Test
        void touchIssuedWithADelete_neverResurrectsTheBlock() {
            var resurrected = IntStream.range(0, ROUNDS).filter(this::resurrectsTogether).boxed().toList();

            assertThat(resurrected).as("rounds that left the deleted block in the bucket, of %d", ROUNDS).isEmpty();
        }

        @Test
        void deleteSentAtTheTouchWrite_neverResurrectsTheBlock() {
            var http = new DeleteOnWriteHttp(JdkHttpOperations.jdkHttpOperations(), tier);
            var racing = durableTier(clientWith(http), prefix);
            var resurrected = IntStream.range(0, ROUNDS).filter(round -> resurrectsAtWrite(racing, http, round)).boxed().toList();

            assertThat(http.deletes).as("every round raced a delete against the touch's write").hasSize(ROUNDS);
            assertThat(resurrected).as("rounds that left the deleted block in the bucket, of %d", ROUNDS).isEmpty();
        }

        private boolean resurrectsTogether(int round) {
            var id = stored("touch issued with a delete " + round);
            var touch = tier.touch(id);
            var delete = tier.delete(id);

            touch.await().onFailure(cause -> fail("round " + round + ": touch failed: " + cause.message()));
            delete.await().onFailure(cause -> fail("round " + round + ": delete failed: " + cause.message()));

            return held(id);
        }

        private boolean resurrectsAtWrite(RemoteTier racing, DeleteOnWriteHttp http, int round) {
            var id = stored("delete sent at the touch write " + round);

            racing.touch(id).await().onFailure(cause -> fail("round " + round + ": touch failed: " + cause.message()));
            http.deletes.getLast().await().onFailure(cause -> fail("round " + round + ": delete failed: " + cause.message()));

            return held(id);
        }

        private BlockId stored(String text) {
            var content = text.getBytes(StandardCharsets.UTF_8);
            var id = blockIdOf(content);

            tier.put(id, content).await().onFailure(cause -> fail(cause.message()));

            return id;
        }

        private boolean held(BlockId id) {
            return s3Client.headObject(keyOf(id)).await().<Boolean>fold(cause -> fail(cause.message()), found -> found);
        }
    }

    /// The touch on the real endpoint: it writes a marker beside the block and never the block; on an absent block it
    /// writes nothing; scan lists no marker; delete removes the marker too.
    @Nested
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    class TouchMarker {
        @Test
        void touch_writesAMarker_andScanListsOnlyTheBlock() {
            var content = "marked, not rewritten".getBytes(StandardCharsets.UTF_8);
            var id = blockIdOf(content);

            tier.put(id, content).await().onFailure(cause -> fail(cause.message()));

            var written = s3Client.statObject(keyOf(id)).await().fold(cause -> fail(cause.message()), stat -> stat).unwrap();

            waitPastTheNextSecond();
            assertThat(tier.touch(id).await().<Boolean>fold(cause -> fail(cause.message()), touched -> touched)).isTrue();

            assertThat(s3Client.statObject(keyOf(id)).await().fold(cause -> fail(cause.message()), stat -> stat).unwrap())
                .as("the block object is not rewritten")
                .isEqualTo(written);
            assertThat(s3Client.headObject(keyOf(id) + ".touched").await().<Boolean>fold(cause -> fail(cause.message()), found -> found))
                .isTrue();
            assertThat(tier.scan(KeyShard.all(), Option.none(), 10).await().fold(cause -> fail(cause.message()), page -> page).items())
                .extracting(BlockStat::id)
                .containsExactly(id);
        }

        @Test
        void touch_ofAnAbsentBlock_isFalse_andWritesNoMarker() {
            var id = blockIdOf("never stored on the endpoint".getBytes(StandardCharsets.UTF_8));

            assertThat(tier.touch(id).await().<Boolean>fold(cause -> fail(cause.message()), touched -> touched)).isFalse();
            assertThat(s3Client.listObjects(prefix, 10).await().<List<String>>fold(cause -> fail(cause.message()), keys -> keys)).isEmpty();
        }

        @Test
        void delete_removesTheMarker() {
            var content = "touched, then deleted on the endpoint".getBytes(StandardCharsets.UTF_8);
            var id = blockIdOf(content);

            tier.put(id, content).await().onFailure(cause -> fail(cause.message()));
            tier.touch(id).await().onFailure(cause -> fail(cause.message()));
            tier.delete(id).await().onFailure(cause -> fail(cause.message()));

            assertThat(s3Client.listObjects(prefix, 10).await().<List<String>>fold(cause -> fail(cause.message()), keys -> keys)).isEmpty();
        }
    }

    /// Delegates to a real transport; the moment a PUT is sent -- the touch's only write -- sends a delete of the block
    /// it names (the PUT's key without a `.touched` suffix) through `deleting`, without waiting for it.
    static final class DeleteOnWriteHttp implements HttpOperations {
        private final HttpOperations delegate;
        private final RemoteTier deleting;
        final List<Promise<Unit>> deletes = new CopyOnWriteArrayList<>();

        DeleteOnWriteHttp(HttpOperations delegate, RemoteTier deleting) {
            this.delegate = delegate;
            this.deleting = deleting;
        }

        @Override
        public <T> Promise<HttpResult<T>> send(HttpRequest request, BodyHandler<T> handler) {
            if (request.method().equals("PUT")) {
                var path = request.uri().getPath();
                var hex = path.substring(path.lastIndexOf('/') + 1).replace(".touched", "");

                deletes.add(deleting.delete(BlockId.fromHex(hex).fold(cause -> fail(cause.message()), id -> id)));
            }

            return delegate.send(request, handler);
        }
    }
}
