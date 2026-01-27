package org.pragmatica.aether.infra.artifact;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.infra.artifact.ArtifactStore.ArtifactStoreError;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.dht.Partition;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactStoreIntegrityTest {
    private ConcurrentHashMap<String, byte[]> storage;
    private ArtifactStore store;

    @BeforeEach
    void setup() {
        storage = new ConcurrentHashMap<>();
        store = ArtifactStore.artifactStore(testDht());
    }

    @Test
    void resolve_succeeds_withValidArtifact() {
        var artifact = Artifact.artifact("org.example:test:1.0.0").unwrap();
        var content = "test content for artifact".getBytes(StandardCharsets.UTF_8);

        store.deploy(artifact, content)
             .await()
             .onFailureRun(Assertions::fail);

        store.resolve(artifact)
             .await()
             .onFailureRun(Assertions::fail)
             .onSuccess(resolved -> assertThat(resolved).isEqualTo(content));
    }

    @Test
    void resolveWithMetadata_succeeds_withValidArtifact() {
        var artifact = Artifact.artifact("org.example:test:1.0.0").unwrap();
        var content = "test content for artifact".getBytes(StandardCharsets.UTF_8);

        store.deploy(artifact, content)
             .await()
             .onFailureRun(Assertions::fail);

        store.resolveWithMetadata(artifact)
             .await()
             .onFailureRun(Assertions::fail)
             .onSuccess(resolved -> {
                 assertThat(resolved.content()).isEqualTo(content);
                 assertThat(resolved.metadata().size()).isEqualTo(content.length);
                 assertThat(resolved.metadata().sha1()).isNotEmpty();
             });
    }

    @Test
    void resolve_fails_withCorruptedContent() {
        var artifact = Artifact.artifact("org.example:test:1.0.0").unwrap();
        var content = "original content".getBytes(StandardCharsets.UTF_8);

        store.deploy(artifact, content)
             .await()
             .onFailureRun(Assertions::fail);

        // Corrupt the first chunk (same size to avoid array bounds issues)
        var chunkKey = "artifacts/org.example/test/1.0.0/content/0";
        storage.put(chunkKey, "CORRUPTED_CONTEN".getBytes(StandardCharsets.UTF_8));

        store.resolve(artifact)
             .await()
             .onSuccessRun(Assertions::fail)
             .onFailure(cause -> assertThat(cause).isInstanceOf(ArtifactStoreError.CorruptedArtifact.class));
    }

    @Test
    void resolveWithMetadata_fails_withCorruptedContent() {
        var artifact = Artifact.artifact("org.example:test:1.0.0").unwrap();
        var content = "original content".getBytes(StandardCharsets.UTF_8);

        store.deploy(artifact, content)
             .await()
             .onFailureRun(Assertions::fail);

        // Corrupt the first chunk (same size to avoid array bounds issues)
        var chunkKey = "artifacts/org.example/test/1.0.0/content/0";
        storage.put(chunkKey, "CORRUPTED_CONTEN".getBytes(StandardCharsets.UTF_8));

        store.resolveWithMetadata(artifact)
             .await()
             .onSuccessRun(Assertions::fail)
             .onFailure(cause -> {
                 assertThat(cause).isInstanceOf(ArtifactStoreError.CorruptedArtifact.class);
                 assertThat(cause.message()).contains("org.example:test:1.0.0");
             });
    }

    @Test
    void resolve_fails_withMissingArtifact() {
        var artifact = Artifact.artifact("org.example:missing:1.0.0").unwrap();

        store.resolve(artifact)
             .await()
             .onSuccessRun(Assertions::fail)
             .onFailure(cause -> assertThat(cause).isInstanceOf(ArtifactStoreError.NotFound.class));
    }

    @Test
    void resolve_succeeds_withLargeArtifact() {
        var artifact = Artifact.artifact("org.example:large:1.0.0").unwrap();
        // Create content larger than chunk size (64KB) to test multi-chunk handling
        var content = new byte[100_000];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i % 256);
        }

        store.deploy(artifact, content)
             .await()
             .onFailureRun(Assertions::fail);

        store.resolveWithMetadata(artifact)
             .await()
             .onFailureRun(Assertions::fail)
             .onSuccess(resolved -> {
                 assertThat(resolved.content()).isEqualTo(content);
                 assertThat(resolved.metadata().chunkCount()).isGreaterThan(1);
             });
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
