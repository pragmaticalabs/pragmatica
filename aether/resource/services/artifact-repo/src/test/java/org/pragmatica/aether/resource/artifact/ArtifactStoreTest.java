package org.pragmatica.aether.resource.artifact;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.resource.artifact.ArtifactStore.ArtifactStoreError;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.dht.Partition;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactStoreTest {
    private static final int CHUNK_SIZE = 64 * 1024;

    private ConcurrentHashMap<String, byte[]> storage;
    private ArtifactStore store;

    @BeforeEach
    void setup() {
        storage = new ConcurrentHashMap<>();
        store = ArtifactStore.artifactStore(testDht());
    }

    @Nested
    class DeployTests {
        @Test
        void deploy_smallContent_storesChunksAndMetadata() {
            var artifact = Artifact.artifact("org.example:test-artifact:1.0.0").unwrap();
            var content = "small test content".getBytes(StandardCharsets.UTF_8);

            store.deploy(artifact, content)
                 .await()
                 .onFailureRun(Assertions::fail);

            store.exists(artifact)
                 .await()
                 .onFailureRun(Assertions::fail)
                 .onSuccess(exists -> assertThat(exists).isTrue());
        }

        @Test
        void deploy_largeContent_chunksCorrectly() {
            var artifact = Artifact.artifact("org.example:large-artifact:1.0.0").unwrap();
            var content = new byte[CHUNK_SIZE * 3 + 100];
            fillWithPattern(content);

            store.deploy(artifact, content)
                 .await()
                 .onFailureRun(Assertions::fail);

            store.resolveWithMetadata(artifact)
                 .await()
                 .onFailureRun(Assertions::fail)
                 .onSuccess(resolved -> {
                     assertThat(resolved.content()).isEqualTo(content);
                     assertThat(resolved.metadata().chunkCount()).isEqualTo(4);
                 });
        }

        @Test
        void deploy_exactChunkSize_noExtraChunk() {
            var artifact = Artifact.artifact("org.example:exact-chunk:1.0.0").unwrap();
            var content = new byte[CHUNK_SIZE];
            fillWithPattern(content);

            store.deploy(artifact, content)
                 .await()
                 .onFailureRun(Assertions::fail);

            store.resolveWithMetadata(artifact)
                 .await()
                 .onFailureRun(Assertions::fail)
                 .onSuccess(resolved -> assertThat(resolved.metadata().chunkCount()).isEqualTo(1));
        }

        @Test
        void deploy_oneByteOverChunkSize_twoChunks() {
            var artifact = Artifact.artifact("org.example:over-chunk:1.0.0").unwrap();
            var content = new byte[CHUNK_SIZE + 1];
            fillWithPattern(content);

            store.deploy(artifact, content)
                 .await()
                 .onFailureRun(Assertions::fail);

            store.resolveWithMetadata(artifact)
                 .await()
                 .onFailureRun(Assertions::fail)
                 .onSuccess(resolved -> assertThat(resolved.metadata().chunkCount()).isEqualTo(2));
        }
    }

    @Nested
    class ResolveTests {
        @Test
        void resolve_deployedArtifact_returnsOriginalContent() {
            var artifact = Artifact.artifact("org.example:resolve-test:1.0.0").unwrap();
            var content = "content to deploy and then resolve".getBytes(StandardCharsets.UTF_8);

            store.deploy(artifact, content)
                 .await()
                 .onFailureRun(Assertions::fail);

            store.resolve(artifact)
                 .await()
                 .onFailureRun(Assertions::fail)
                 .onSuccess(resolved -> assertThat(resolved).isEqualTo(content));
        }

        @Test
        void resolveWithMetadata_deployedArtifact_returnsCorrectMetadata() {
            var artifact = Artifact.artifact("org.example:meta-test:1.0.0").unwrap();
            var content = new byte[CHUNK_SIZE * 2 + 500];
            fillWithPattern(content);

            store.deploy(artifact, content)
                 .await()
                 .onFailureRun(Assertions::fail);

            store.resolveWithMetadata(artifact)
                 .await()
                 .onFailureRun(Assertions::fail)
                 .onSuccess(resolved -> {
                     assertThat(resolved.metadata().size()).isEqualTo(content.length);
                     assertThat(resolved.metadata().chunkCount()).isEqualTo(3);
                     assertThat(resolved.metadata().md5()).isNotEmpty();
                     assertThat(resolved.metadata().sha1()).isNotEmpty();
                     assertThat(resolved.metadata().deployedAt()).isGreaterThan(0);
                 });
        }
    }

    @Nested
    class ExistsTests {
        @Test
        void exists_nonExistentArtifact_returnsFalse() {
            var artifact = Artifact.artifact("org.example:missing:1.0.0").unwrap();

            store.exists(artifact)
                 .await()
                 .onFailureRun(Assertions::fail)
                 .onSuccess(exists -> assertThat(exists).isFalse());
        }
    }

    @Nested
    class DeleteTests {
        @Test
        void delete_deployedArtifact_removesAll() {
            var artifact = Artifact.artifact("org.example:delete-test:1.0.0").unwrap();
            var content = "content to deploy and delete".getBytes(StandardCharsets.UTF_8);

            store.deploy(artifact, content)
                 .await()
                 .onFailureRun(Assertions::fail);

            store.delete(artifact)
                 .await()
                 .onFailureRun(Assertions::fail);

            store.exists(artifact)
                 .await()
                 .onFailureRun(Assertions::fail)
                 .onSuccess(exists -> assertThat(exists).isFalse());

            store.resolve(artifact)
                 .await()
                 .onSuccessRun(Assertions::fail)
                 .onFailure(cause -> assertThat(cause).isInstanceOf(ArtifactStoreError.NotFound.class));
        }
    }

    @Nested
    class VersionsTests {
        @Test
        void versions_afterMultipleDeploys_listsAll() {
            var v1 = Artifact.artifact("org.example:versioned:1.0.0").unwrap();
            var v2 = Artifact.artifact("org.example:versioned:2.0.0").unwrap();
            var content = "version content".getBytes(StandardCharsets.UTF_8);

            store.deploy(v1, content)
                 .await()
                 .onFailureRun(Assertions::fail);

            store.deploy(v2, content)
                 .await()
                 .onFailureRun(Assertions::fail);

            store.versions(v1.groupId(), v1.artifactId())
                 .await()
                 .onFailureRun(Assertions::fail)
                 .onSuccess(versions -> {
                     assertThat(versions).hasSize(2);
                     assertThat(versions.stream().map(v -> v.withQualifier()).toList())
                             .containsExactlyInAnyOrder("1.0.0", "2.0.0");
                 });
        }
    }

    private static void fillWithPattern(byte[] content) {
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i % 256);
        }
    }

    private DHTClient testDht() {
        return new DHTClient() {
            @Override
            public Promise<Unit> put(byte[] key, byte[] value) {
                storage.put(new String(key, StandardCharsets.UTF_8), value);
                return Promise.unitPromise();
            }

            @Override
            public Promise<Option<byte[]>> get(byte[] key) {
                return Promise.success(Option.option(storage.get(new String(key, StandardCharsets.UTF_8))));
            }

            @Override
            public Promise<Boolean> exists(byte[] key) {
                return Promise.success(storage.containsKey(new String(key, StandardCharsets.UTF_8)));
            }

            @Override
            public Promise<Boolean> remove(byte[] key) {
                return Promise.success(storage.remove(new String(key, StandardCharsets.UTF_8)) != null);
            }

            @Override
            public Partition partitionFor(byte[] key) {
                return Partition.partition(Math.abs(new String(key, StandardCharsets.UTF_8).hashCode()) % 1024).unwrap();
            }
        };
    }
}
