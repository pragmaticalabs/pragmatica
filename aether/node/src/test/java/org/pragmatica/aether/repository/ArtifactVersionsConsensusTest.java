// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.repository;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.resource.artifact.ArtifactStore;
import org.pragmatica.aether.resource.artifact.ArtifactStore.ArchivePolicy;
import org.pragmatica.aether.resource.artifact.ArtifactVersionIndex;
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1778: the versions of an artifact live in consensus, where the applier FOLDS concurrent writers. Three nodes,
/// each with its own KV replica, share one consensus log whose commands are held until `flush()`, so every
/// publish is in flight before any of them applies — the exact interleaving that loses a version when each
/// writer does a read-merge-write. The applier and the KV-backed index are the REAL ones.
class ArtifactVersionsConsensusTest {
    private static final byte[] CONTENT = "content".getBytes(StandardCharsets.UTF_8);

    private final Artifact v1 = Artifact.artifact("org.example:lib:1.0.0").unwrap();
    private final Artifact v2 = Artifact.artifact("org.example:lib:2.0.0").unwrap();
    private final Artifact v3 = Artifact.artifact("org.example:lib:3.0.0").unwrap();

    private Log log;
    private List<KvArtifactVersionIndex> nodes;

    @BeforeEach
    void setUp() {
        log = new Log();
        nodes = List.of(log.join(), log.join(), log.join());
    }

    @Test
    void publish_keepsEveryVersion_whenThreeNodesPublishAtOnce() {
        var a = nodes.get(0).publish(v1);
        var b = nodes.get(1).publish(v2);
        var c = nodes.get(2).publish(v3);

        assertThat(log.pending()).as("all three are in flight before any applies").isEqualTo(3);

        log.flush();
        await(a, b, c);

        for (var node : nodes) {
            assertThat(versionsOf(node)).containsExactly("1.0.0", "2.0.0", "3.0.0");
        }
    }

    @Test
    void archive_keepsItsFlag_whenAnotherVersionIsPublishedAtOnce_andAStaleAddCannotUndoIt() {
        var first = nodes.get(0).publish(v1);

        log.flush();
        await(first);

        var archive = nodes.get(0).archive(v1);
        var publish = nodes.get(1).publish(v2);
        var staleAdd = nodes.get(2).publish(v1);

        log.flush();
        await(archive, publish, staleAdd);

        for (var node : nodes) {
            assertThat(versionsOf(node)).as("v1 stays archived whatever the order, v2 is listed").containsExactly("2.0.0");
            assertThat(node.isArchived(v1).await().unwrap()).as("the committed flag reads archived").isTrue();
            assertThat(node.isArchived(v2).await().unwrap()).as("a published version is not archived").isFalse();
        }
    }

    @Test
    void deploys_keepEveryVersion_whenTwoNodesDeployDifferentVersionsAtOnce() {
        var storage = StorageInstance.storageInstance("consensus-artifacts", List.of(MemoryTier.memoryTier(16 * 1024 * 1024)));
        var dht = new MapDht();
        var storeA = ArtifactStore.artifactStore(dht, storage, ArchivePolicy.DEFAULT, nodes.get(0));
        var storeB = ArtifactStore.artifactStore(dht, storage, ArchivePolicy.DEFAULT, nodes.get(1));

        var first = storeA.deploy(v1, CONTENT);
        var second = storeB.deploy(v2, CONTENT);

        waitForPending(2);
        log.applyImmediately();
        log.flush();
        await(first, second);

        for (var store : List.of(storeA, storeB)) {
            assertThat(store.versions(v1.groupId(), v1.artifactId()).await().unwrap().stream().map(v -> v.withQualifier()).sorted().toList())
                .containsExactly("1.0.0", "2.0.0");
        }
    }

    @Test
    void twoUploadersOfDifferentBytes_toOneNewCoordinate_exactlyOneWins_andReadersSeeTheWinnersBytes() {
        var storage = StorageInstance.storageInstance("binding-artifacts", List.of(MemoryTier.memoryTier(16 * 1024 * 1024)));
        var dht = new MapDht();
        var storeA = ArtifactStore.artifactStore(dht, storage, ArchivePolicy.DEFAULT, nodes.get(0));
        var storeB = ArtifactStore.artifactStore(dht, storage, ArchivePolicy.DEFAULT, nodes.get(1));
        var bytesA = "bytes from uploader A".getBytes(StandardCharsets.UTF_8);
        var bytesB = "different bytes from uploader B".getBytes(StandardCharsets.UTF_8);

        var a = storeA.deploy(v1, bytesA);
        var b = storeB.deploy(v1, bytesB);

        waitForPending(2);
        log.applyImmediately();
        log.flush();

        var outcomeA = a.await(timeSpan(5).seconds());
        var outcomeB = b.await(timeSpan(5).seconds());

        assertThat(outcomeA.isSuccess() ^ outcomeB.isSuccess()).as("exactly one uploader wins").isTrue();

        var winnerBytes = outcomeA.isSuccess() ? bytesA : bytesB;
        var loser = outcomeA.isSuccess() ? outcomeB : outcomeA;

        loser.onSuccessRun(Assertions::fail)
             .onFailure(cause -> assertThat(cause).isInstanceOf(ArtifactStore.ArtifactStoreError.ContentConflict.class));

        for (var store : List.of(storeA, storeB)) {
            assertThat(store.resolve(v1).await().onFailureRun(Assertions::fail).unwrap()).as("readers always see the winner's bytes")
                                                                                         .isEqualTo(winnerBytes);
        }
    }

    @Test
    void publishesPastTheBound_areRefusedWithAClearError_andNothingPresentIsLost() {
        var bounded = new ArrayList<KvArtifactVersionIndex>();

        for (var i = 0; i < 3; i++) {
            bounded.add(log.join(2));
        }

        var a = bounded.get(0).publish(v1);
        var b = bounded.get(1).publish(v2);
        var c = bounded.get(2).publish(v3);

        log.flush();

        var outcomes = List.of(a.await(timeSpan(5).seconds()), b.await(timeSpan(5).seconds()), c.await(timeSpan(5).seconds()));

        assertThat(outcomes.stream().filter(outcome -> outcome.isSuccess()).count()).as("two slots, so exactly two win").isEqualTo(2);
        outcomes.stream()
                .filter(outcome -> !outcome.isSuccess())
                .forEach(outcome -> outcome.onSuccessRun(Assertions::fail)
                                           .onFailure(cause -> assertThat(cause).isInstanceOf(ArtifactStore.ArtifactStoreError.VersionLimitReached.class)));

        for (var node : bounded) {
            assertThat(versionsOf(node)).as("every replica holds the same two present versions").hasSize(2);
        }
    }

    @Test
    void archiving_freesRoomUnderTheBound_andAStaleAddStillCannotResurrect() {
        var bounded = log.join(1);

        var first = bounded.publish(v1);

        log.flush();
        await(first);

        var refused = bounded.publish(v2);

        log.flush();
        refused.await(timeSpan(5).seconds()).onSuccessRun(Assertions::fail);

        var archive = bounded.archive(v1);
        var again = bounded.publish(v2);
        var stale = bounded.publish(v1);

        log.flush();
        await(archive, again, stale);

        assertThat(versionsOf(bounded)).containsExactly("2.0.0");
    }

    private List<String> versionsOf(ArtifactVersionIndex index) {
        return index.versions(v1.groupId(), v1.artifactId())
                    .await()
                    .unwrap()
                    .stream()
                    .map(v -> v.withQualifier())
                    .toList();
    }

    @SafeVarargs
    private static void await(Promise<Unit>... promises) {
        for (var promise : promises) {
            promise.await(timeSpan(5).seconds()).onFailureRun(Assertions::fail);
        }
    }

    private static void await(Promise<?> first, Promise<?> second) {
        first.await(timeSpan(5).seconds()).onFailureRun(Assertions::fail);
        second.await(timeSpan(5).seconds()).onFailureRun(Assertions::fail);
    }

    /// A deploy reaches the index only after its checks and chunk writes, so wait until its command is queued.
    private void waitForPending(int expected) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (log.pending() < expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        assertThat(log.pending()).isEqualTo(expected);
    }

    /// One consensus log, N replicas: commands wait until `flush()`, then apply to EVERY replica in submission
    /// order and resolve their callers.
    private static final class Log {
        private final List<KVStore<AetherKey, AetherValue>> replicas = new ArrayList<>();
        private final List<Held> held = new ArrayList<>();
        private boolean autoApply;

        private record Held(List<KVCommand<AetherKey>> commands, Promise<List<Object>> done) {}

        KvArtifactVersionIndex join() {
            return join(org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactVersionsValue.DEFAULT_MAX_LIVE);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        KvArtifactVersionIndex join(int maxLive) {
            var replica = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), noopSerializer(), noopDeserializer());
            var cluster = (ClusterNode<KVCommand<AetherKey>>) mock(ClusterNode.class);

            replicas.add(replica);
            when(cluster.apply(anyList())).thenAnswer(invocation -> hold((List<KVCommand<AetherKey>>) invocation.getArgument(0)));

            return KvArtifactVersionIndex.kvArtifactVersionIndex(cluster, replica, maxLive);
        }

        private synchronized Promise<List<Object>> hold(List<KVCommand<AetherKey>> commands) {
            var done = Promise.<List<Object>> promise();

            held.add(new Held(commands, done));

            if (autoApply) {
                flush();
            }

            return done;
        }

        /// From now on a command applies as soon as it is submitted: the interleaving of interest is over.
        synchronized void applyImmediately() {
            autoApply = true;
        }

        synchronized int pending() {
            return held.size();
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        synchronized void flush() {
            for (var entry : List.copyOf(held)) {
                replicas.forEach(replica -> replica.process(replica.createBatch((List) entry.commands())));
                entry.done().succeed(List.of());
            }

            held.clear();
        }

        private static Serializer noopSerializer() {
            return new Serializer() {
                @Override
                public <T> void write(ByteBuf byteBuf, T object) {}
            };
        }

        private static Deserializer noopDeserializer() {
            return new Deserializer() {
                @Override
                public <T> T read(ByteBuf byteBuf) {
                    return null;
                }
            };
        }
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
