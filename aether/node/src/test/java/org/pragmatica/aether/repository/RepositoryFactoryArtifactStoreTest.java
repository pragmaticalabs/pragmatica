// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.repository;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.dht.Partition;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1778: `RepositoryFactory.artifactStore` is the one place a node builds its artifact store (`AetherNode` calls it;
/// `AetherNodeArtifactStoreWiringTest` pins that call). What it builds must reach consensus for the coordinate index
/// and must run under the configured retention.
class RepositoryFactoryArtifactStoreTest {
    private static final byte[] CONTENT = "content".getBytes(StandardCharsets.UTF_8);

    private final Artifact artifact = Artifact.artifact("org.example:lib:1.0.0").unwrap();
    private final List<KVCommand<AetherKey>> submitted = new CopyOnWriteArrayList<>();

    @Test
    void artifactStore_sendsTheBindingAndTheVersionThroughConsensus() {
        var store = storeWith(SliceConfig.sliceConfig());

        store.deploy(artifact, CONTENT).await().onFailureRun(Assertions::fail);

        assertThat(submitted.stream().map(command -> command.key().getClass().getSimpleName()).toList())
            .as("the content binding first, then the version, both as consensus commands")
            .containsExactly("ArtifactContentKey", "ArtifactVersionsKey");
    }

    @Test
    void artifactStore_runsUnderTheConfiguredRetention() throws InterruptedException {
        var store = storeWith(SliceConfig.sliceConfig().withArtifactArchiveRetention(timeSpan(1).millis()));

        store.deploy(artifact, CONTENT).await().onFailureRun(Assertions::fail);
        Thread.sleep(20);

        store.archive(artifact).await().onFailureRun(Assertions::fail);
        assertThat(submitted.getLast().key()).isInstanceOf(AetherKey.ArtifactVersionsKey.class);
    }

    @Test
    void artifactStore_passesTheConfiguredVersionBound_toTheIndex() {
        var store = storeWith(SliceConfig.sliceConfig().withArtifactMaxVersions(1));

        store.deploy(artifact, CONTENT).await().onFailureRun(Assertions::fail);

        store.deploy(Artifact.artifact("org.example:lib:2.0.0").unwrap(), CONTENT).await().onSuccessRun(Assertions::fail);
    }

    @Test
    void artifactStore_withTheDefaultRetention_refusesToArchiveAFreshVersion() {
        var store = storeWith(SliceConfig.sliceConfig());

        store.deploy(artifact, CONTENT).await().onFailureRun(Assertions::fail);

        store.archive(artifact).await().onSuccessRun(Assertions::fail);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private org.pragmatica.aether.resource.artifact.ArtifactStore storeWith(SliceConfig config) {
        var kv = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        }, new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        });
        var cluster = (ClusterNode<KVCommand<AetherKey>>) mock(ClusterNode.class);

        when(cluster.apply(anyList())).thenAnswer(invocation -> {
            var commands = (List<KVCommand<AetherKey>>) invocation.getArgument(0);

            submitted.addAll(commands);
            kv.process(kv.createBatch((List) commands));

            return Promise.success(new ArrayList<>());
        });

        var storage = StorageInstance.storageInstance("wiring-artifacts", List.of(MemoryTier.memoryTier(16 * 1024 * 1024)));

        return RepositoryFactory.artifactStore(new MapDht(), storage, config, cluster, kv);
    }

    private static final class MapDht implements DHTClient {
        private final ConcurrentHashMap<String, byte[]> map = new ConcurrentHashMap<>();

        @Override
        public Promise<Unit> put(byte[] key, byte[] value) {
            map.put(new String(key, StandardCharsets.UTF_8), value);

            return Promise.unitPromise();
        }

        @Override
        public Promise<Option<byte[]>> get(byte[] key) {
            return Promise.success(Option.option(map.get(new String(key, StandardCharsets.UTF_8))));
        }

        @Override
        public Promise<Boolean> exists(byte[] key) {
            return Promise.success(map.containsKey(new String(key, StandardCharsets.UTF_8)));
        }

        @Override
        public Promise<Boolean> remove(byte[] key) {
            return Promise.success(map.remove(new String(key, StandardCharsets.UTF_8)) != null);
        }

        @Override
        public Partition partitionFor(byte[] key) {
            return Partition.partition(1).unwrap();
        }
    }
}
