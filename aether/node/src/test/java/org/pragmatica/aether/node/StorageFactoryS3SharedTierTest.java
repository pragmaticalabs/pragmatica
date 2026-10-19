// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.SharedTierConfig;
import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.cloud.aws.AwsConfig;
import org.pragmatica.cloud.aws.AwsCredentials;
import org.pragmatica.cloud.aws.AwsCredentialsProvider;
import org.pragmatica.cloud.aws.AwsSigV4Signer;
import org.pragmatica.cloud.aws.s3.S3Client;
import org.pragmatica.cloud.aws.s3.S3Config;
import org.pragmatica.http.JdkHttpOperations;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.storage.BlockEncryptor;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.EncryptingStorageTier;
import org.pragmatica.storage.EncryptionError;
import org.pragmatica.storage.EncryptionKeyring;
import org.pragmatica.storage.LocalDiskTier;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #249: a node's storage construction with `[storage.content.shared] kind = "s3"` against MinIO -- the probe
/// admits the bucket, blocks land under the prefix, and with `[storage.encryption]` the bytes at rest in the
/// bucket are ciphertext, with the marker that refuses a later keyring-less boot. Same endpoint properties as
/// `RemoteTierS3IntegrationTest` (`aether.s3.endpoint`, `.region`, `.bucket`, `.accessKey`, `.secretKey`,
/// `.pathStyle`, `.image`).
///
/// Run with: `mvn test -pl aether/node -Dgroups=S3 -Dsurefire.excludedGroups=`.
@Tag("S3")
class StorageFactoryS3SharedTierTest {
    private static final String REGION = System.getProperty("aether.s3.region", "us-east-1");
    private static final String BUCKET = System.getProperty("aether.s3.bucket", "aether-node-it");
    private static final String ACCESS_KEY = System.getProperty("aether.s3.accessKey", "minioadmin");
    private static final String SECRET_KEY = System.getProperty("aether.s3.secretKey", "minioadmin");
    private static final boolean PATH_STYLE = Boolean.parseBoolean(System.getProperty("aether.s3.pathStyle", "true"));
    /// Pinned by digest, not `:latest`: the test pulls third-party code into CI, and a moving tag would run whatever
    /// was pushed last, unreviewed (supply chain) and unreproducible. Chainguard's free tier serves only `latest`,
    /// so the digest is the only stable reference; bump it deliberately. `-Daether.s3.image` overrides it.
    private static final String MINIO_DIGEST = "cgr.dev/chainguard/minio@sha256:709099319add48dfb44aa596a7070d3ddd8dd04d7ae881765719aec29df9e510";
    private static final String MINIO_IMAGE = System.getProperty("aether.s3.image", MINIO_DIGEST);
    private static final String NODE_ID = "node-s3";
    private static final byte[] PLAINTEXT = "s3-shared-tier-plaintext-block-249".getBytes(StandardCharsets.UTF_8);

    private static GenericContainer<?> minio;
    private static String endpoint;
    private static S3Client raw;

    @TempDir
    Path tempDir;

    private String prefix;

    @BeforeAll
    static void startEndpoint() {
        endpoint = Option.option(System.getProperty("aether.s3.endpoint"))
                         .filter(value -> !value.isBlank())
                         .or(StorageFactoryS3SharedTierTest::startMinio);
        raw = S3Client.s3Client(S3Config.s3Endpoint(endpoint, BUCKET, REGION, PATH_STYLE),
                                JdkHttpOperations.jdkHttpOperations(),
                                AwsCredentialsProvider.staticCredentials(AwsCredentials.awsCredentials(ACCESS_KEY, SECRET_KEY)),
                                Option.some(Duration.ofSeconds(10)));
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
                                                   .waitingFor(new HttpWaitStrategy().forPort(9000).forPath("/minio/health/ready"));
        minio.start();

        return "http://" + minio.getHost() + ":" + minio.getMappedPort(9000);
    }

    private static void createBucket() {
        var url = S3Config.s3Endpoint(endpoint, BUCKET, REGION, PATH_STYLE).bucketUrl();
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

    @BeforeEach
    void setUp() {
        prefix = "node-it-" + UUID.randomUUID() + "/";
    }

    private StorageConfig defaults() {
        return StorageConfig.storageConfig(8L * 1024 * 1024,
                                           64L * 1024 * 1024,
                                           tempDir.resolve("storage").toString(),
                                           tempDir.resolve("metadata-snapshots").toString(),
                                           1000,
                                           "60s",
                                           5);
    }

    private SharedTierConfig.S3 s3Config(String accessKey, String secretKey) throws IOException {
        var file = tempDir.resolve("credentials-" + UUID.randomUUID());
        Files.writeString(file, "[default]\naws_access_key_id = " + accessKey + "\naws_secret_access_key = " + secretKey + "\n");

        return new SharedTierConfig.S3(endpoint, REGION, BUCKET, prefix, PATH_STYLE, new SharedTierConfig.Credentials.File(file), 4,
                                       timeSpan(10).seconds());
    }

    private Result<Map<String, StorageFactory.StorageSetup>> createContent(SharedTierConfig.S3 s3, Option<EncryptionKeyring> keyring) {
        return createSharedOnly("content", s3, keyring, defaults());
    }

    /// A boot whose only configured instance is `[storage.<name>.shared]`, derived as `Main` derives it.
    private static Result<Map<String, StorageFactory.StorageSetup>> createSharedOnly(String name,
                                                                                    SharedTierConfig.S3 s3,
                                                                                    Option<EncryptionKeyring> keyring,
                                                                                    StorageConfig defaults) {
        var derived = StorageFactory.withDerivedPaths(Map.of(name, StorageConfig.sharedOnly(s3)), defaults, keyring.isPresent());

        return StorageFactory.createAll(derived, NODE_ID, Option.none(), keyring, defaults);
    }

    private String keyOf(BlockId id) {
        return prefix + id.shardPrefix() + "/" + id.hexString();
    }

    private byte[] rawObject(String key) {
        return raw.getObject(key).await().fold(cause -> fail(cause.message()), opt -> opt.or(() -> fail("no object at " + key)));
    }

    private static EncryptionKeyring keyring(String keyId) {
        var key = new byte[32];
        new SecureRandom().nextBytes(key);

        return EncryptionKeyring.encryptionKeyring(Map.of(keyId, BlockEncryptor.aesGcm(key, keyId).unwrap()), keyId).unwrap();
    }

    @Test
    void sharedOnlyContent_bootsOnTheBucket_andBlocksLandUnderThePrefix() throws IOException {
        var setups = createContent(s3Config(ACCESS_KEY, SECRET_KEY), Option.none()).fold(cause -> fail(cause.message()), s -> s);
        var content = setups.get("content").instance();

        var id = content.put(PLAINTEXT).await().fold(cause -> fail(cause.message()), blockId -> blockId);

        assertThat(rawObject(keyOf(id))).as("without encryption the bucket holds the plaintext").isEqualTo(PLAINTEXT);
        content.get(id).await().onSuccess(opt -> assertThat(opt.or(new byte[0])).isEqualTo(PLAINTEXT));
        raw.listObjects(prefix + ".aether-probe/", 10).await().onSuccess(keys -> assertThat(keys).as("the probe cleans up").isEmpty());
        assertThat(Files.isDirectory(tempDir.resolve("content/blocks"))).as("content keeps its synthesized placement").isTrue();
    }

    /// Mutation that reddens it: skip the `EncryptingStorageTier.wrap` in `S3SharedTier#s3SharedTier` (the bucket
    /// then holds the plaintext).
    @Test
    void encryptedContent_bucketHoldsCiphertext_andTheMarker() throws IOException {
        var setups = createContent(s3Config(ACCESS_KEY, SECRET_KEY), Option.some(keyring("k1"))).fold(cause -> fail(cause.message()), s -> s);

        var id = setups.get("content").instance().put(PLAINTEXT).await().fold(cause -> fail(cause.message()), blockId -> blockId);
        var atRest = rawObject(keyOf(id));

        assertThat(atRest).as("the bucket must hold ciphertext").isNotEqualTo(PLAINTEXT);
        assertThat(new String(Arrays.copyOf(atRest, 4), StandardCharsets.US_ASCII)).isEqualTo("AEC1");
        assertThat(new String(atRest, StandardCharsets.ISO_8859_1)).doesNotContain(new String(PLAINTEXT, StandardCharsets.ISO_8859_1));
        assertThat(new String(rawObject(prefix + EncryptingStorageTier.MARKER_FILE_NAME), StandardCharsets.UTF_8)).isEqualTo("k1");
        setups.get("content").instance().get(id).await().onSuccess(opt -> assertThat(opt.or(new byte[0])).isEqualTo(PLAINTEXT));
    }

    @Test
    void keyringlessBoot_overEncryptedPrefix_isRefused() throws IOException {
        var s3 = s3Config(ACCESS_KEY, SECRET_KEY);
        createContent(s3, Option.some(keyring("k1"))).onFailure(cause -> fail(cause.message()));
        var second = defaultsInFreshDir();

        createSharedOnly("content", s3, Option.none(), second)
                      .onSuccess(_ -> fail("a keyring-less boot over an encrypted prefix must be refused"))
                      .onFailure(cause -> assertThat(chainHas(cause, EncryptionError.EncryptedTierRequiresKeyring.class)).as(cause.message())
                                                                                                                 .isTrue());
    }

    /// #852 for the bucket: a boot refused by a guard (here the content disk, which holds a plaintext block and no
    /// marker) writes no S3 marker, so backing the keyring out boots. Mutation that reddens it: write the S3 marker
    /// during admission (`S3SharedTier#armEncrypted`) instead of in the commit phase.
    @Test
    void refusedBoot_leavesNoS3Marker_andAKeyringlessBootThenSucceeds() throws IOException {
        var s3 = s3Config(ACCESS_KEY, SECRET_KEY);
        seedPlaintextBlock(tempDir.resolve("content").resolve("blocks"));

        createContent(s3, Option.some(keyring("k1")))
            .onSuccess(_ -> fail("the content disk holds plaintext: enabling encryption must be refused"))
            .onFailure(cause -> assertThat(chainHas(cause, EncryptionError.EnablingOverExistingPlaintext.class)).as(cause.message())
                                                                                                         .isTrue());

        assertThat(markerPresent()).as("a refused boot stamps no bucket").isFalse();
        createContent(s3, Option.none()).onFailure(cause -> fail("the keyring-less boot must succeed: " + cause.message()));
    }

    /// Turning encryption on over a prefix that already holds plaintext blocks is refused at boot, as the local
    /// disk refuses it, and nothing is stamped. Mutation that reddens it: `S3SharedTier#requireEmpty` always passes.
    @Test
    void encryptingOverAPlaintextPrefix_isRefused() throws IOException {
        var s3 = s3Config(ACCESS_KEY, SECRET_KEY);
        var plain = createContent(s3, Option.none()).fold(cause -> fail(cause.message()), setups -> setups);
        plain.get("content").instance().put(PLAINTEXT).await().onFailure(cause -> fail(cause.message()));

        createSharedOnly("content", s3, Option.some(keyring("k1")), defaultsInFreshDir())
                      .onSuccess(_ -> fail("enabling encryption over a plaintext prefix must be refused"))
                      .onFailure(cause -> assertThat(chainHas(cause, EncryptionError.EnablingOverExistingPlaintext.class)).as(cause.message())
                                                                                                                     .isTrue());

        assertThat(markerPresent()).isFalse();
    }

    /// A shared-only `artifacts` is placed exactly as the synthesized default, including encryption tracking the
    /// keyring. Mutation that reddens it: place it on the bare defaults (`StorageFactory#derivedPaths`'s artifacts
    /// case), which leave it unencrypted.
    @Test
    void sharedOnlyArtifacts_isPlacedAsTheSynthesizedDefault_encryptedWithAKeyring() throws IOException {
        var setups = createSharedOnly("artifacts", s3Config(ACCESS_KEY, SECRET_KEY), Option.some(keyring("k1")), defaults())
                         .fold(cause -> fail(cause.message()), s -> s);

        var id = setups.get("artifacts").instance().put(PLAINTEXT).await().fold(cause -> fail(cause.message()), blockId -> blockId);

        assertThat(new String(Arrays.copyOf(rawObject(keyOf(id)), 4), StandardCharsets.US_ASCII)).as("artifacts tracks the keyring")
                                                                                                  .isEqualTo("AEC1");
        assertThat(Files.isDirectory(tempDir.resolve("storage"))).as("artifacts keeps the default disk path").isTrue();
    }

    private boolean markerPresent() {
        return raw.getObject(prefix + EncryptingStorageTier.MARKER_FILE_NAME)
                  .await()
                  .fold(cause -> fail(cause.message()), Option::isPresent);
    }

    private static void seedPlaintextBlock(Path dir) {
        LocalDiskTier.localDiskTier(dir, 64L * 1024 * 1024)
                     .unwrap()
                     .put(BlockId.blockId(PLAINTEXT).unwrap(), PLAINTEXT)
                     .await()
                     .onFailure(cause -> fail("seeding a plaintext block failed: " + cause.message()));
    }

    @Test
    void wrongCredentials_failConstruction() throws IOException {
        createContent(s3Config("not-a-user", "not-a-secret"), Option.none())
            .onSuccess(_ -> fail("wrong credentials must fail storage construction"))
            .onFailure(cause -> assertThat(cause.message()).contains("'content'").contains(BUCKET).doesNotContain("not-a-secret"));
    }

    private StorageConfig defaultsInFreshDir() throws IOException {
        var dir = Files.createDirectories(tempDir.resolve("second-boot"));

        return StorageConfig.storageConfig(8L * 1024 * 1024,
                                           64L * 1024 * 1024,
                                           dir.resolve("storage").toString(),
                                           dir.resolve("metadata-snapshots").toString(),
                                           1000,
                                           "60s",
                                           5);
    }

    private static boolean chainHas(Cause cause, Class<?> type) {
        return type.isInstance(cause) || cause.source().map(source -> chainHas(source, type)).or(false);
    }
}
