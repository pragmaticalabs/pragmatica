// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.resource.artifact;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.dht.Partition;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.BlockLifecycle;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.MetadataStore;
import org.pragmatica.storage.StorageInstance;

import static org.assertj.core.api.Assertions.assertThat;

/// A deploy that fails part-way through its chunk fan-out. Each chunk holds only `StorageInstance#put`'s credit
/// and nothing names it until the metadata is written, so the chunks that DID land are held at refCount 1 behind
/// no artifact: the same class as #1437, on the artifact store's own chunk path.
class ArtifactStoreFailedDeployCreditsTest {
    private static final int CHUNK_SIZE = 64 * 1024;

    private final ConcurrentHashMap<String, byte[]> dht = new ConcurrentHashMap<>();
    private final MetadataStore metadataStore = MetadataStore.inMemoryMetadataStore("failed-deploy-credits");

    @Test
    void deploy_whoseLaterChunkIsRefused_holdsNoCreditsBehindAnArtifactThatWasNeverStored() {
        var artifact = Artifact.artifact("org.example:partial:1.0.0").unwrap();
        var content = distinctChunks(3);
        var storage = StorageInstance.storageInstance("failed-deploy-credits",
                                                      List.of(MemoryTier.memoryTier(CHUNK_SIZE * 5L / 2)),
                                                      metadataStore);
        var store = ArtifactStore.artifactStore(testDht(), storage);

        var deployed = store.deploy(artifact, content).await();

        assertThat(deployed.isFailure()).as("the fixture must refuse a chunk, or this proves nothing").isTrue();
        assertThat(store.exists(artifact).await().unwrap()).as("no metadata was written").isFalse();
        assertThat(heldCredits(content)).as("every credit the failed deploy took must have been given back").isZero();
    }

    /// With content addressing, a chunk the failed deploy shares with a STORED artifact is credited by the deploy
    /// and, unreleased, would sit at refCount 2 for ever. Only the failed deploy's own credit is returned, so the
    /// stored artifact keeps exactly its one.
    @Test
    void deploy_whoseLaterChunkIsRefused_returnsASharedChunkToTheCountItHad() {
        var stored = distinctChunks(3);
        var storage = StorageInstance.storageInstance("failed-deploy-credits",
                                                      List.of(MemoryTier.memoryTier(CHUNK_SIZE * 9L / 2)),
                                                      metadataStore);
        var store = ArtifactStore.artifactStore(testDht(), storage);

        store.deploy(Artifact.artifact("org.example:stored:1.0.0").unwrap(), stored).await().onFailureRun(org.junit.jupiter.api.Assertions::fail);

        var sharesFirstChunk = new byte[CHUNK_SIZE * 4];

        System.arraycopy(stored, 0, sharesFirstChunk, 0, CHUNK_SIZE);
        for (var i = CHUNK_SIZE; i < sharesFirstChunk.length; i++) {
            sharesFirstChunk[i] = (byte) ((i * 7 + (i / CHUNK_SIZE) * 13 + 5) % 251);
        }

        var deployed = store.deploy(Artifact.artifact("org.example:partial:1.0.0").unwrap(), sharesFirstChunk).await();

        assertThat(deployed.isFailure()).as("the fixture must refuse a chunk, or this proves nothing").isTrue();
        assertThat(creditsOn(chunksOf(stored).getFirst())).as("the stored artifact keeps exactly its own credit on the shared chunk")
                                                         .isEqualTo(1);
        assertThat(heldCredits(java.util.Arrays.copyOfRange(sharesFirstChunk, CHUNK_SIZE, sharesFirstChunk.length))).as("the failed deploy's own chunks are all given back")
                                                                                                                .isZero();
    }

    private int creditsOn(byte[] chunk) {
        return metadataStore.getLifecycle(BlockId.blockId(chunk).unwrap())
                            .map(BlockLifecycle::refCount)
                            .or(0);
    }

    private int heldCredits(byte[] content) {
        var held = 0;

        for (var chunk : chunksOf(content)) {
            held += metadataStore.getLifecycle(BlockId.blockId(chunk).unwrap())
                                 .map(BlockLifecycle::refCount)
                                 .or(0);
        }

        return held;
    }

    private static List<byte[]> chunksOf(byte[] content) {
        var chunks = new ArrayList<byte[]>();

        for (var start = 0; start < content.length; start += CHUNK_SIZE) {
            chunks.add(java.util.Arrays.copyOfRange(content, start, Math.min(start + CHUNK_SIZE, content.length)));
        }

        return chunks;
    }

    /// Each chunk differs from every other, or content addressing collapses them into one block.
    private static byte[] distinctChunks(int count) {
        var content = new byte[CHUNK_SIZE * count];

        for (var i = 0; i < content.length; i++) {
            content[i] = (byte) ((i + (i / CHUNK_SIZE) * 31 + 1) % 256);
        }

        return content;
    }

    private DHTClient testDht() {
        return new DHTClient() {
            @Override
            public Promise<Unit> put(byte[] key, byte[] value) {
                dht.put(new String(key, StandardCharsets.UTF_8), value);

                return Promise.unitPromise();
            }

            @Override
            public Promise<Option<byte[]>> get(byte[] key) {
                return Promise.success(Option.option(dht.get(new String(key, StandardCharsets.UTF_8))));
            }

            @Override
            public Promise<Boolean> exists(byte[] key) {
                return Promise.success(dht.containsKey(new String(key, StandardCharsets.UTF_8)));
            }

            @Override
            public Promise<Boolean> remove(byte[] key) {
                return Promise.success(dht.remove(new String(key, StandardCharsets.UTF_8)) != null);
            }

            @Override
            public Partition partitionFor(byte[] key) {
                return Partition.partition(Math.abs(new String(key, StandardCharsets.UTF_8).hashCode()) % 1024).unwrap();
            }
        };
    }
}
