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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactId;
import org.pragmatica.aether.artifact.GroupId;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.deployment.schema.SchemaError;
import org.pragmatica.aether.resource.artifact.ArtifactStore;
import org.pragmatica.aether.slice.SliceManifest;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.aether.slice.repository.Location;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.assertj.core.api.InstanceOfAssertFactories;

import static org.assertj.core.api.Assertions.assertThat;

/// #542 — the deploy-time single-migrator gate.
///
/// Datasource names are cluster-global: `BlueprintArtifactParser` maps `schema/V001__*.sql` to
/// `"database"` for EVERY blueprint using the default layout, and that name resolves to one physical
/// database through the node-global config. Two blueprints migrating it interleave unrelated version
/// sequences, so the publish that would become the second migrator is refused before any command is
/// applied. Sharing a datasource for reads and writes stays legal — only duplicate migration
/// ownership is rejected.
class BlueprintPublishOwnershipTest {
    private static final String OWNER_COORDS = "org.example:orders-app:1.0.0";
    private static final String OWNER_UPGRADE_COORDS = "org.example:orders-app:2.0.0";
    private static final String OTHER_COORDS = "org.example:billing-app:1.0.0";
    private static final BlueprintId OWNER = BlueprintId.blueprintId(OWNER_COORDS).unwrap();
    private static final BlueprintId OTHER_OWNER = BlueprintId.blueprintId(OTHER_COORDS).unwrap();
    private static final Artifact SLICE = Artifact.artifact("org.example:orders-api:1.0.0").unwrap();
    private static final String SLICE_CLASS = "org.example.orders.OrdersSlice";
    private static final String DATASOURCE = "database";
    private static final Cause NOT_IN_REPOSITORY = Causes.cause("Artifact not present in local repository");
    private static final Cause NOT_IN_STORE = Causes.cause("Artifact not present in artifact store");

    private static final String SLICE_STANZA = """

            [[slices]]
            artifact = "org.example:orders-api:1.0.0"
            instances = 1
            """;

    @TempDir
    Path tempDir;

    private TestKVStore store;
    private TestClusterNode cluster;
    private Path sliceJar;

    @BeforeEach
    void setUp() throws IOException {
        store = new TestKVStore();
        cluster = new TestClusterNode(store);
        sliceJar = writeSliceJar();
    }

    @Nested
    class Rejection {
        @Test
        void publishFromArtifact_fails_whenDatasourceIsMigratedByAnotherBlueprint() {
            seedSchemaOwnedBy(OTHER_OWNER);

            publish(OWNER_COORDS, withMigrations(OWNER_COORDS)).onSuccess(_ -> Assertions.fail("Publish must be refused for a datasource another blueprint migrates"))
                                                               .onFailure(BlueprintPublishOwnershipTest::assertOwnershipConflict);
        }

        @Test
        void publishFromArtifact_leavesExistingOwnerIntact_whenRejected() {
            seedSchemaOwnedBy(OTHER_OWNER);

            publish(OWNER_COORDS, withMigrations(OWNER_COORDS)).onSuccess(_ -> Assertions.fail("Publish must be refused"));

            assertThat(recordedOwner()).as("a rejected publish must not overwrite the incumbent migrator")
                                       .isEqualTo(OTHER_COORDS);
        }

        @Test
        void publishFromArtifact_writesNoCommands_whenRejected() {
            seedSchemaOwnedBy(OTHER_OWNER);

            publish(OWNER_COORDS, withMigrations(OWNER_COORDS)).onSuccess(_ -> Assertions.fail("Publish must be refused"));

            assertThat(store.get(AppBlueprintKey.appBlueprintKey(OWNER)).isPresent())
                    .as("the gate runs before the batch, so not even the blueprint atom lands")
                    .isFalse();
        }

        @Test
        void datasourceOwnershipConflict_reportsHttpConflict() {
            var cause = SchemaError.DatasourceOwnershipConflict.datasourceOwnershipConflict(DATASOURCE,
                                                                                             OTHER_OWNER,
                                                                                             OWNER);

            assertThat(cause.httpStatus()).as("a state conflict on a well-formed request is 409, not 500")
                                          .isEqualTo(HttpStatus.CONFLICT);
        }
    }

    @Nested
    class Acceptance {
        @Test
        void publishFromArtifact_succeeds_whenDatasourceIsUnclaimed() {
            publish(OWNER_COORDS, withMigrations(OWNER_COORDS)).onFailure(BlueprintPublishOwnershipTest::failOnUnexpectedFailure);

            assertThat(recordedOwner()).as("the first migrator claims the datasource")
                                       .isEqualTo(OWNER_COORDS);
        }

        /// A version upgrade republishes the same blueprint identity with a new version. Ownership is
        /// compared on `ArtifactBase`, so it is the incumbent advancing its own schema, not a second
        /// migrator.
        @Test
        void publishFromArtifact_succeeds_whenSameBlueprintRedeclaresMigrations() {
            seedSchemaOwnedBy(OWNER);

            publish(OWNER_UPGRADE_COORDS,
                    withMigrations(OWNER_UPGRADE_COORDS)).onFailure(BlueprintPublishOwnershipTest::failOnUnexpectedFailure);

            assertThat(recordedOwner()).isEqualTo(OWNER_UPGRADE_COORDS);
        }

        /// Shared usage is legal: only the blueprint that ships the migration scripts owns them.
        @Test
        void publishFromArtifact_succeeds_whenSecondBlueprintDeclaresNoMigrations() {
            seedSchemaOwnedBy(OTHER_OWNER);

            publish(OWNER_COORDS, withoutMigrations(OWNER_COORDS)).onFailure(BlueprintPublishOwnershipTest::failOnUnexpectedFailure);

            assertThat(recordedOwner()).as("a non-migrating blueprint neither claims nor disturbs the record")
                                       .isEqualTo(OTHER_COORDS);
        }
    }

    // --- helpers ---

    /// The cause must reach the caller UNWRAPPED. `ProblemResponses.resolveStatus` keys the response
    /// code off `cause instanceof HttpStatusAware`, so any accumulation or composition on the way out
    /// silently downgrades the publish rejection from 409 to 500.
    private static void assertOwnershipConflict(Cause cause) {
        assertThat(cause).isInstanceOf(SchemaError.DatasourceOwnershipConflict.class);
        assertThat(cause).asInstanceOf(InstanceOfAssertFactories.type(HttpStatusAware.class))
                         .extracting(HttpStatusAware::httpStatus)
                         .isEqualTo(HttpStatus.CONFLICT);
        assertThat(cause.message()).contains(DATASOURCE)
                                   .contains(OTHER_COORDS)
                                   .contains(OWNER_COORDS);
    }

    private static void failOnUnexpectedFailure(Cause cause) {
        Assertions.fail("Unexpected publish failure: " + cause.message());
    }

    private Result<ExpandedBlueprint> publish(String coords, byte[] blueprintJar) {
        return BlueprintService.blueprintService(cluster, store, repository(), artifactStore(blueprintJar))
                               .publishFromArtifact(coords + ":blueprint")
                               .await();
    }

    private void seedSchemaOwnedBy(BlueprintId owner) {
        store.processCommand(new KVCommand.Put<>(SchemaVersionKey.schemaVersionKey(DATASOURCE),
                                                 SchemaVersionValue.schemaVersionValue(DATASOURCE,
                                                                                       1,
                                                                                       "V001__init.sql",
                                                                                       SchemaStatus.COMPLETED,
                                                                                       owner.asString(),
                                                                                       owner)));
    }

    private String recordedOwner() {
        return store.get(SchemaVersionKey.schemaVersionKey(DATASOURCE))
                    .filter(SchemaVersionValue.class::isInstance)
                    .map(SchemaVersionValue.class::cast)
                    .map(SchemaVersionValue::owningBlueprint)
                    .map(BlueprintId::asString)
                    .or("<no record>");
    }

    private Repository repository() {
        return artifact -> SLICE.equals(artifact)
                           ? sliceLocation(artifact)
                           : NOT_IN_REPOSITORY.promise();
    }

    private Promise<Location> sliceLocation(Artifact artifact) {
        return Result.lift(Causes::fromThrowable, () -> sliceJar.toUri().toURL())
                     .flatMap(url -> Location.location(artifact, url))
                     .async();
    }

    private static byte[] withMigrations(String blueprintId) {
        return blueprintJar(blueprintId, Option.some("schema/V001__init.sql"));
    }

    private static byte[] withoutMigrations(String blueprintId) {
        return blueprintJar(blueprintId, Option.none());
    }

    /// A blueprint jar carrying `META-INF/blueprint.toml` and, when requested, one migration script
    /// under the default layout — which `BlueprintArtifactParser` maps to the `"database"` datasource.
    private static byte[] blueprintJar(String blueprintId, Option<String> migrationEntryPath) {
        var bytes = new ByteArrayOutputStream();

        try (var zip = new ZipOutputStream(bytes)) {
            writeEntry(zip, "META-INF/blueprint.toml", "id = \"" + blueprintId + "\"\n" + SLICE_STANZA);
            migrationEntryPath.onPresent(path -> writeMigration(zip, path));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build test blueprint jar", e);
        }

        return bytes.toByteArray();
    }

    private static void writeMigration(ZipOutputStream zip, String path) {
        try {
            writeEntry(zip, path, "CREATE TABLE orders(id BIGINT PRIMARY KEY);");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write test migration entry", e);
        }
    }

    private static void writeEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /// A minimal slice jar on disk: `BlueprintExpander` resolves the blueprint's one slice through
    /// `Repository` and reads its `SliceManifest` off a real `JarFile`, so this must be a file, not a
    /// byte array. It declares no dependency file, which `DependencyFile.load` treats as "no
    /// dependencies".
    private Path writeSliceJar() throws IOException {
        var manifest = new Manifest();
        var attributes = manifest.getMainAttributes();

        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(SliceManifest.SLICE_ARTIFACT_ATTR, SLICE.asString());
        attributes.putValue(SliceManifest.SLICE_CLASS_ATTR, SLICE_CLASS);
        attributes.putValue(SliceManifest.ENVELOPE_VERSION_ATTR, "1000");

        var target = tempDir.resolve("orders-api-1.0.0.jar");

        try (var out = new JarOutputStream(Files.newOutputStream(target), manifest)) {
            out.putNextEntry(new ZipEntry("org/example/orders/"));
            out.closeEntry();
        }

        return target;
    }

    private static ArtifactStore artifactStore(byte[] blueprintJar) {
        return new ArtifactStore() {
            @Override public Promise<DeployResult> deploy(Artifact artifact, byte[] content) {
                return NOT_IN_STORE.promise();
            }

            @Override public Promise<byte[]> resolve(Artifact artifact) {
                return Promise.success(blueprintJar);
            }

            @Override public Promise<ResolvedArtifact> resolveWithMetadata(Artifact artifact) {
                return NOT_IN_STORE.promise();
            }

            @Override public Promise<Boolean> exists(Artifact artifact) {
                return Promise.success(false);
            }

            @Override public Promise<Option<ArtifactMetadata>> metadata(Artifact artifact) {
                return Promise.success(Option.none());
            }

            @Override public Promise<List<Version>> versions(GroupId groupId, ArtifactId artifactId) {
                return Promise.success(List.of());
            }

            @Override public Promise<Unit> delete(Artifact artifact) {
                return Promise.unitPromise();
            }

            @Override public Metrics metrics() {
                return new Metrics(0, 0, 0L);
            }
        };
    }

    private record TestClusterNode(TestKVStore store) implements ClusterNode<KVCommand<AetherKey>> {
        @Override
        public NodeId self() {
            return NodeId.nodeId("test-node").unwrap();
        }

        @Override
        public TopologyManager topologyManager() {
            return null;
        }

        @Override
        public Promise<Unit> start() {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.unitPromise();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            return Promise.success(commands.stream()
                                           .map(command -> (R) store.processCommand(command))
                                           .toList());
        }
    }

    private static final class TestKVStore extends KVStore<AetherKey, AetherValue> {
        private final Map<AetherKey, AetherValue> storage = new HashMap<>();

        TestKVStore() {
            super(null, null, null);
        }

        @Override
        public Map<AetherKey, AetherValue> snapshot() {
            return new HashMap<>(storage);
        }

        @Override
        public Option<AetherValue> get(AetherKey key) {
            return Option.option(storage.get(key));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <KK, VV> void forEach(Class<KK> keyClass, Class<VV> valueClass, BiConsumer<KK, VV> consumer) {
            storage.forEach((key, value) -> acceptMatching(keyClass, valueClass, consumer, key, value));
        }

        @SuppressWarnings("unchecked")
        private static <KK, VV> void acceptMatching(Class<KK> keyClass,
                                                    Class<VV> valueClass,
                                                    BiConsumer<KK, VV> consumer,
                                                    AetherKey key,
                                                    AetherValue value) {
            if (keyClass.isInstance(key) && valueClass.isInstance(value)) {
                consumer.accept((KK) key, (VV) value);
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R> List<R> process(Batch<KVCommand<AetherKey>> batch) {
            return batch.commands()
                        .stream()
                        .map(command -> (R) processCommand(command))
                        .toList();
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        Option<AetherValue> processCommand(KVCommand command) {
            return switch (command) {
                case KVCommand.Put<?, ?> put -> {
                    storage.put((AetherKey) put.key(), (AetherValue) put.value());
                    yield Option.none();
                }
                case KVCommand.Remove<?> remove -> {
                    storage.remove((AetherKey) remove.key());
                    yield Option.none();
                }
                case KVCommand.Get<?> get -> Option.option(storage.get((AetherKey) get.key()));
                default -> Option.none();
            };
        }
    }
}
