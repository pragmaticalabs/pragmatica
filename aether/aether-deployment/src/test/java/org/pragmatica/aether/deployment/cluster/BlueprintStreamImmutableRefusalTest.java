// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactId;
import org.pragmatica.aether.artifact.GroupId;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.deployment.cluster.BlueprintPublishOwnershipTest.TestClusterNode;
import org.pragmatica.aether.deployment.cluster.BlueprintPublishOwnershipTest.TestKVStore;
import org.pragmatica.aether.deployment.validation.StreamResourceValidator;
import org.pragmatica.aether.resource.artifact.ArtifactFile;
import org.pragmatica.aether.resource.artifact.ArtifactStore;
import org.pragmatica.aether.slice.SliceManifest;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.StreamDeclaration;
import org.pragmatica.aether.slice.TruncationDeclaration;
import org.pragmatica.aether.slice.TruncationPolicy;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.aether.slice.repository.Location;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #3334 (section 2.5), deploy path: a redeploy that changes an immutable key of a COMMITTED stream is refused at deploy
/// validation, before any command is applied, by a named rule; the committed `StreamConfigKey` row is unchanged.
/// `StreamConfigChangeNotApplied` stays the backstop for a local KV that lags a commit.
///
/// The committed row is looked up by engine key (`namespace:alias:version`) in the node's KV store and threaded into
/// [StreamResourceValidator#partition] by `BlueprintService` on BOTH publish paths: the artifact path (T-154, T-155,
/// T-237) and the body path (slice jars' `resources.toml`).
class BlueprintStreamImmutableRefusalTest {
    private static final String COORDS = "org.example:stream-app:1.0.0";
    private static final Artifact SLICE = Artifact.artifact("org.example:stream-app-publisher:1.0.0").unwrap();
    private static final String ENGINE_KEY = "org.example.stream-app:orders:1.0.0";
    private static final Cause NOT_IN_REPOSITORY = (Repository.Absent) () -> "Artifact not present in local repository";
    private static final Cause NOT_IN_STORE = Causes.cause("Artifact not present in artifact store");

    private static final String DSL = """
            id = "org.example:stream-app:1.0.0"

            [[slices]]
            artifact = "org.example:stream-app-publisher:1.0.0"
            instances = 3
            """;

    @TempDir
    Path tempDir;

    private TestKVStore store;
    private TestClusterNode cluster;

    @BeforeEach
    void setUp() {
        store = new TestKVStore();
        cluster = new TestClusterNode(store);
    }

    /// T-154: partitions 16 -> 32 on a running stream is refused before commit.
    @Nested
    class PartitionCount {
        @Test
        void aPartitionChange_isRefusedBeforeCommit_andTheRowIsUnchanged() {
            var row = seed(StreamConfig.DEFAULT.withName(ENGINE_KEY).withPartitions(16));

            assertRefused(publishArtifact(stream("partitions = 32")), StreamResourceValidator.RULE_PARTITION_COUNT_IMMUTABLE);
            assertThat(committedRow()).isEqualTo(row);
            assertThat(cluster.batches).as("the refusal must come before any batch is applied").isEmpty();
        }

        @Test
        void aPartitionChange_onTheBodyPath_isRefusedBeforeCommit_andTheRowIsUnchanged() {
            var row = seed(StreamConfig.DEFAULT.withName(ENGINE_KEY).withPartitions(16));

            assertRefused(publishBody(stream("partitions = 32")), StreamResourceValidator.RULE_PARTITION_COUNT_IMMUTABLE);
            assertThat(committedRow()).isEqualTo(row);
            assertThat(cluster.batches).isEmpty();
        }

        @Test
        void anUnchangedRedeploy_isCommitted() {
            seed(StreamConfig.DEFAULT.withName(ENGINE_KEY).withPartitions(16));

            publishArtifact(stream("partitions = 16")).onFailure(BlueprintStreamImmutableRefusalTest::failOnRefusal);

            assertThat(cluster.batches).isNotEmpty();
        }

        @Test
        void aFirstDeploy_withNoCommittedRow_isCommitted() {
            publishArtifact(stream("partitions = 32")).onFailure(BlueprintStreamImmutableRefusalTest::failOnRefusal);

            assertThat(cluster.batches).isNotEmpty();
        }
    }

    /// T-155: durability only increases.
    @Nested
    class Durability {
        @Test
        void aLoweredReplicationFactor_isRefusedBeforeCommit_andTheRowIsUnchanged() {
            var row = seed(factors(3, 2));

            assertRefused(publishArtifact(stream("partitions = 4\nreplication_factor = 2\nconfirmation_factor = 2")),
                          StreamResourceValidator.RULE_DURABILITY_ONLY_INCREASES);
            assertThat(committedRow()).isEqualTo(row);
            assertThat(cluster.batches).isEmpty();
        }

        @Test
        void aRaisedReplicationFactor_isCommitted() {
            seed(factors(2, 2));

            publishArtifact(stream("partitions = 4\nreplication_factor = 3\nconfirmation_factor = 2")).onFailure(BlueprintStreamImmutableRefusalTest::failOnRefusal);

            assertThat(cluster.batches).isNotEmpty();
        }
    }

    /// T-237: `max_event_size` and `encryption_key_id` are immutable.
    @Nested
    class StorageKeys {
        @Test
        void aChangedMaxEventSize_isRefusedBeforeCommit_namingTheKey_andTheRowIsUnchanged() {
            var row = seed(StreamConfig.DEFAULT.withName(ENGINE_KEY).withPartitions(4));

            assertRefused(publishArtifact(stream("partitions = 4\nmax_event_size = \"2MB\"")), StreamResourceValidator.RULE_STORAGE_KEY_IMMUTABLE)
                    .contains("max_event_size");
            assertThat(committedRow()).isEqualTo(row);
            assertThat(cluster.batches).isEmpty();
        }

        /// R-12 tripwire: `encryption_key_id` is an inert key (#253), so the inert gate refuses it first and the
        /// immutable comparison never runs. This test goes red when the key is wired; enable the design arm below then.
        @Test
        void aChangedEncryptionKey_isRefusedAsInert_andTheRowIsUnchanged() {
            var row = seed(StreamConfig.DEFAULT.withName(ENGINE_KEY).withPartitions(4));

            assertRefused(publishArtifact(stream("partitions = 4\nencryption_key_id = \"key-b\"")), StreamResourceValidator.RULE_INERT_STREAM_CONFIG);
            assertThat(committedRow()).isEqualTo(row);
            assertThat(cluster.batches).isEmpty();
        }

        @Disabled("#253: encryption_key_id is inert, so the inert gate refuses it first (R-12); enable when the key is wired")
        @Test
        void aChangedEncryptionKey_isRefusedAsStorageKeyImmutable_namingTheKey() {
            var row = seed(StreamConfig.DEFAULT.withName(ENGINE_KEY).withPartitions(4));

            assertRefused(publishArtifact(stream("partitions = 4\nencryption_key_id = \"key-b\"")), StreamResourceValidator.RULE_STORAGE_KEY_IMMUTABLE)
                    .contains("encryption_key_id");
            assertThat(committedRow()).isEqualTo(row);
        }
    }

    /// T-252: a `compression` change applies online and has no immutable rule.
    @Nested
    class Compression {
        /// Tripwire: `compression` is inert (#677), so a change off -> on is refused as inert today and the row is
        /// unchanged. Goes red when compression is wired; enable the design arm below then.
        @Test
        void aChangedCompression_isRefusedAsInert_andTheRowIsUnchanged() {
            var row = seed(StreamConfig.DEFAULT.withName(ENGINE_KEY).withPartitions(4));

            assertRefused(publishArtifact(stream("partitions = 4\ncompression = \"lz4\"")), StreamResourceValidator.RULE_INERT_STREAM_CONFIG);
            assertThat(committedRow()).isEqualTo(row);
        }

        @Disabled("#677: compression is inert and descoped in 1.0; enable when compression is wired (an online change)")
        @Test
        void aChangedCompression_isAccepted_asAnOnlineChange() {
            seed(StreamConfig.DEFAULT.withName(ENGINE_KEY).withPartitions(4));

            publishArtifact(stream("partitions = 4\ncompression = \"lz4\"")).onFailure(BlueprintStreamImmutableRefusalTest::failOnRefusal);

            assertThat(cluster.batches).isNotEmpty();
        }
    }

    /// #3495 item 5 (T-156 through the blueprint path): `[truncation] policy` is immutable on a running stream. The row is
    /// seeded `until_consumed` directly (no deploy can commit one while the T-146 gate refuses it), and a `window` redeploy is
    /// refused by the immutable rule, which is a GATING rule: the publish fails, it does not drop the alias and commit.
    @Nested
    class TruncationPolicyChange {
        @Test
        void aPolicyChange_isRefusedBeforeCommit_andTheRowIsUnchanged() {
            var row = seed(StreamConfig.DEFAULT.withName(ENGINE_KEY)
                                              .withPartitions(4)
                                              .withDeclaration(declaredPolicy(TruncationPolicy.UNTIL_CONSUMED)));

            assertRefused(publishArtifact(stream("partitions = 4\n[streams.orders.truncation]\npolicy = \"window\"")),
                          StreamResourceValidator.RULE_TRUNCATION_POLICY_IMMUTABLE);
            assertThat(committedRow()).isEqualTo(row);
            assertThat(cluster.batches).as("the refusal must come before any batch is applied").isEmpty();
        }

        @Test
        void theImmutableRule_isGating() {
            assertThat(StreamResourceValidator.GATING_SECTION_RULES).contains(StreamResourceValidator.RULE_TRUNCATION_POLICY_IMMUTABLE);
        }

        private static StreamDeclaration declaredPolicy(TruncationPolicy policy) {
            return StreamDeclaration.streamDeclaration(Option.none(),
                                                       Option.some(TruncationDeclaration.truncationDeclaration(Option.some(policy), Option.none())),
                                                       Option.none(),
                                                       Option.none());
        }
    }

    private static String stream(String keys) {
        return "[streams.orders]\nversion = \"1.0.0\"\n" + keys + "\n";
    }

    private static StreamConfig factors(int replication, int confirmation) {
        return StreamConfig.streamConfig(ENGINE_KEY,
                                         4,
                                         StreamConfig.DEFAULT.retention(),
                                         StreamConfig.DEFAULT.autoOffsetReset(),
                                         StreamConfig.DEFAULT.maxEventSizeBytes(),
                                         StreamConfig.DEFAULT.consistencyMode(),
                                         replication,
                                         confirmation,
                                         StreamConfig.DEFAULT.compression(),
                                         Option.none());
    }

    private Option<AetherValue> seed(StreamConfig config) {
        var value = StreamConfigValue.streamConfigValue(config);

        store.processCommand(new KVCommand.Put<>(StreamConfigKey.streamConfigKey(ENGINE_KEY), value));

        return committedRow();
    }

    private Option<AetherValue> committedRow() {
        return store.get(StreamConfigKey.streamConfigKey(ENGINE_KEY));
    }

    private Result<PublishedBlueprint> publishArtifact(String resourcesToml) {
        return BlueprintService.blueprintService(cluster, store, repository(Option.none()), artifactStore(blueprintJar(resourcesToml)))
                               .publishFromArtifact(COORDS + ":blueprint")
                               .await();
    }

    private Result<PublishedBlueprint> publishBody(String resourcesToml) {
        return BlueprintService.blueprintService(cluster, store, repository(Option.some(resourcesToml))).publish(DSL).await();
    }

    private static org.assertj.core.api.AbstractStringAssert<?> assertRefused(Result<PublishedBlueprint> result, String rule) {
        var message = new String[]{""};

        result.onSuccess(_ -> fail("expected a refusal naming " + rule)).onFailure(cause -> message[0] = cause.message());

        return assertThat(message[0]).contains(rule).contains("[streams.orders]");
    }

    private static void failOnRefusal(Cause cause) {
        fail("expected the publish to be accepted, got: " + cause.message());
    }

    private Repository repository(Option<String> sliceResources) {
        return artifact -> SLICE.equals(artifact)
                           ? location(artifact, sliceResources)
                           : NOT_IN_REPOSITORY.promise();
    }

    private Promise<Location> location(Artifact artifact, Option<String> sliceResources) {
        return Result.lift(Causes::fromThrowable, () -> sliceJar(sliceResources).toUri().toURL())
                     .flatMap(url -> Location.location(artifact, url))
                     .async();
    }

    private Path sliceJar(Option<String> resources) throws IOException {
        var manifest = new Manifest();
        var attributes = manifest.getMainAttributes();

        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(SliceManifest.SLICE_ARTIFACT_ATTR, SLICE.asString());
        attributes.putValue(SliceManifest.SLICE_CLASS_ATTR, "org.example.stream.StreamSlice");
        attributes.putValue(SliceManifest.ENVELOPE_VERSION_ATTR, "1000");

        var target = tempDir.resolve("stream-app-publisher-" + System.nanoTime() + ".jar");

        try (var out = new JarOutputStream(Files.newOutputStream(target), manifest)) {
            out.putNextEntry(new ZipEntry("org/example/stream/"));
            out.closeEntry();

            if (resources.isPresent()) {
                out.putNextEntry(new ZipEntry("META-INF/resources.toml"));
                out.write(resources.or("").getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }

        return target;
    }

    private static byte[] blueprintJar(String resourcesToml) {
        var bytes = new ByteArrayOutputStream();

        try (var zip = new ZipOutputStream(bytes)) {
            writeEntry(zip, "META-INF/blueprint.toml", DSL);
            writeEntry(zip, "META-INF/resources.toml", resourcesToml);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build test blueprint jar", e);
        }

        return bytes.toByteArray();
    }

    private static void writeEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static ArtifactStore artifactStore(byte[] blueprintJar) {
        return new ArtifactStore() {
            @Override public Promise<DeployResult> deploy(ArtifactFile file, byte[] content) {
                return NOT_IN_STORE.promise();
            }

            @Override public Promise<byte[]> resolve(ArtifactFile file) {
                return Promise.success(blueprintJar);
            }

            @Override public Promise<ResolvedArtifact> resolveWithMetadata(ArtifactFile file) {
                return NOT_IN_STORE.promise();
            }

            @Override public Promise<Boolean> exists(ArtifactFile file) {
                return Promise.success(false);
            }

            @Override public Promise<Option<ArtifactMetadata>> metadata(ArtifactFile file) {
                return Promise.success(Option.none());
            }

            @Override public Promise<List<Version>> versions(GroupId groupId, ArtifactId artifactId) {
                return Promise.success(List.of());
            }

            @Override public Promise<Unit> archive(Artifact artifact) {
                return Promise.unitPromise();
            }

            @Override public Metrics metrics() {
                return new Metrics(0, 0, 0L);
            }
        };
    }
}
