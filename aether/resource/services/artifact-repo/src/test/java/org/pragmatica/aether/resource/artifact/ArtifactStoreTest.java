// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.resource.artifact;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.resource.artifact.ArtifactStore.ArtifactStoreError;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.BlockMetadata;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;
import org.pragmatica.storage.StorageInstance.TierInfo;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.dht.DHTError;
import org.pragmatica.dht.Partition;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactStoreTest {
    private static final int CHUNK_SIZE = 64 * 1024;

    private ConcurrentHashMap<String, byte[]> dhtStorage;
    private ArtifactStore store;

    @BeforeEach
    void setup() {
        dhtStorage = new ConcurrentHashMap<>();
        var storageInstance = StorageInstance.storageInstance("test-artifacts",
                                                             List.of(MemoryTier.memoryTier(64 * 1024 * 1024)));
        store = ArtifactStore.artifactStore(testDht(), storageInstance);
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

        @Test
        void deploy_returnsBlockIdsInMetadata() {
            var artifact = Artifact.artifact("org.example:blockid-test:1.0.0").unwrap();
            var content = new byte[CHUNK_SIZE * 2 + 500];
            fillWithPattern(content);

            store.deploy(artifact, content)
                 .await()
                 .onFailureRun(Assertions::fail);

            store.resolveWithMetadata(artifact)
                 .await()
                 .onFailureRun(Assertions::fail)
                 .onSuccess(resolved -> {
                     assertThat(resolved.metadata().blockIds()).hasSize(3);
                     resolved.metadata().blockIds()
                             .forEach(hex -> assertThat(hex).matches("[0-9a-f]{64}"));
                 });
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
        void delete_deployedArtifact_removesMetadata() {
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

    @Nested
    class ChunkRetryTests {
        /// Regression: 1MB+ artifact pushes returned HTTP 500 because the chunk fan-out
        /// in `ArtifactStoreImpl.deploy` invoked `storage.put` directly with no retry,
        /// while the durable DHT tier surfaces transient `BackpressureRefused` as
        /// `DHTError.PeerUnreachable` synchronously (`dht-resilience-spec.md` Layer 3).
        /// With N=16 chunks for a 1MB payload, any single replica's backpressure window
        /// failed the whole deploy. Fix wraps each chunk write in the same bounded retry
        /// (`storagePutWithRetry`) already used for metadata/versions DHT writes.
        ///
        /// Fixture uses `FlakyStorage` (a `StorageInstance` mock) instead of a
        /// `FlakyTier` plugged into the real `DefaultStorageInstance`. The real
        /// implementation dedups put attempts by content hash via `metadataStore.claimBlock`
        /// — once a `put` is in-flight or completed, subsequent puts of the same content
        /// short-circuit via `deduplicateBlock`. That makes the underlying-tier retry
        /// invisible from the StorageInstance API and would silently mask the retry
        /// behavior we're testing here.
        @Test
        void deploy_transientChunkFailure_retriesAndSucceeds() {
            var flakyStorage = new FlakyStorage(1);  // 1 transient failure, then success
            var flakyStore = ArtifactStore.artifactStore(testDht(), flakyStorage);
            var artifact = Artifact.artifact("org.example:retry-test:1.0.0").unwrap();
            var content = new byte[CHUNK_SIZE * 2 + 100];  // 3 chunks
            fillWithPattern(content);

            flakyStore.deploy(artifact, content)
                      .await()
                      .onFailureRun(Assertions::fail);

            assertThat(flakyStorage.transientFailuresInjected()).isGreaterThanOrEqualTo(1);
        }

        @Test
        void deploy_exhaustedRetries_failsWithLastCause() {
            var flakyStorage = new FlakyStorage(99);  // always fail with PeerUnreachable
            var flakyStore = ArtifactStore.artifactStore(testDht(), flakyStorage);
            var artifact = Artifact.artifact("org.example:exhaust-retry:1.0.0").unwrap();
            var content = new byte[CHUNK_SIZE + 1];  // 2 chunks

            flakyStore.deploy(artifact, content)
                      .await()
                      .onSuccessRun(Assertions::fail)
                      .onFailure(cause -> assertThat(cause).isInstanceOf(DHTError.PeerUnreachable.class));
        }

        @Test
        void deploy_nonTransientFailure_failsWithoutRetry() {
            var flakyStorage = new FlakyStorage(1, FlakyStorage.Mode.NON_TRANSIENT);
            var flakyStore = ArtifactStore.artifactStore(testDht(), flakyStorage);
            var artifact = Artifact.artifact("org.example:no-retry:1.0.0").unwrap();
            var content = "small".getBytes(StandardCharsets.UTF_8);

            flakyStore.deploy(artifact, content)
                      .await()
                      .onSuccessRun(Assertions::fail)
                      .onFailure(cause -> assertThat(cause).isInstanceOf(NonTransientCause.class));

            // Non-transient failure → must surface on the first attempt, NOT after exhausting MAX_DHT_PUT_ATTEMPTS
            assertThat(flakyStorage.totalPutAttempts()).isEqualTo(1);
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
                dhtStorage.put(new String(key, StandardCharsets.UTF_8), value);
                return Promise.unitPromise();
            }

            @Override
            public Promise<Option<byte[]>> get(byte[] key) {
                return Promise.success(Option.option(dhtStorage.get(new String(key, StandardCharsets.UTF_8))));
            }

            @Override
            public Promise<Boolean> exists(byte[] key) {
                return Promise.success(dhtStorage.containsKey(new String(key, StandardCharsets.UTF_8)));
            }

            @Override
            public Promise<Boolean> remove(byte[] key) {
                return Promise.success(dhtStorage.remove(new String(key, StandardCharsets.UTF_8)) != null);
            }

            @Override
            public Partition partitionFor(byte[] key) {
                return Partition.partition(Math.abs(new String(key, StandardCharsets.UTF_8).hashCode()) % 1024).unwrap();
            }
        };
    }

    /// Storage instance mock that injects a configurable number of failures into `put`
    /// before serving them normally. Bypasses the real `DefaultStorageInstance` dedup
    /// machinery (`metadataStore.claimBlock` → `deduplicateBlock`) which would otherwise
    /// short-circuit a retry of a previously-failed put, masking the chunk-retry policy
    /// in `ArtifactStoreImpl.deploy`.
    private static final class FlakyStorage implements StorageInstance {
        enum Mode {TRANSIENT, NON_TRANSIENT}

        private final int failuresToInject;
        private final Mode mode;
        private final AtomicInteger putAttempts = new AtomicInteger(0);
        private final AtomicInteger transientFailuresInjected = new AtomicInteger(0);
        private final ConcurrentHashMap<BlockId, byte[]> blocks = new ConcurrentHashMap<>();

        FlakyStorage(int failuresToInject) {
            this(failuresToInject, Mode.TRANSIENT);
        }

        FlakyStorage(int failuresToInject, Mode mode) {
            this.failuresToInject = failuresToInject;
            this.mode = mode;
        }

        int totalPutAttempts() {
            return putAttempts.get();
        }

        int transientFailuresInjected() {
            return transientFailuresInjected.get();
        }

        @Override
        public Promise<BlockId> put(byte[] content) {
            return put(content, BlockMetadata.blockMetadata(content.length));
        }

        @Override
        public Promise<BlockId> put(byte[] content, BlockMetadata metadata) {
            int n = putAttempts.incrementAndGet();
            if (n <= failuresToInject) {
                if (mode == Mode.TRANSIENT) {
                    transientFailuresInjected.incrementAndGet();
                    return new DHTError.PeerUnreachable(NodeId.randomNodeId(), "flaky-storage-injected").promise();
                }
                return new NonTransientCause("non-transient flaky failure").promise();
            }
            return BlockId.blockId(content).async()
                          .onSuccess(id -> blocks.put(id, content));
        }

        @Override
        public Promise<Option<byte[]>> get(BlockId id) {
            return Promise.success(Option.option(blocks.get(id)));
        }

        @Override
        public Promise<Boolean> exists(BlockId id) {
            return Promise.success(blocks.containsKey(id));
        }

        @Override
        public Promise<Unit> createRef(String name, BlockId id) {
            return Promise.unitPromise();
        }

        @Override
        public Option<BlockId> resolveRef(String name) {
            return Option.none();
        }

        @Override
        public Promise<Unit> deleteRef(String name) {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> delete(BlockId id) {
            blocks.remove(id);
            return Promise.unitPromise();
        }

        @Override
        public String name() {
            return "flaky-storage";
        }

        @Override
        public List<TierInfo> tierInfo() {
            return List.of();
        }

        @Override
        public void shutdown() {
        }
    }

    private record NonTransientCause(String message) implements org.pragmatica.lang.Cause {}
}
