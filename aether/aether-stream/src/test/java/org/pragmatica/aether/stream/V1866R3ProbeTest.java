// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.storage.AppendLog;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// v1866 round 3: the incarnation obtained ONLY through production create/hydrate (a manager with a cluster node,
/// so `withIncarnation` mints). No test here hand-feeds an incarnation. Each test asserts the behaviour owed; a red
/// test is a demonstrated defect.
class V1866R3ProbeTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId SELF = NodeId.nodeId("node-probe").unwrap();

    @TempDir
    Path n1Wal;

    @TempDir
    Path xWal;

    /// Provenance: destroy (removal applied) then create through production mints a DISTINCT, non-zero incarnation.
    @Test
    void production_destroyThenRecreate_mintsADistinctIncarnation() {
        var cluster = new RecordingClusterNode();
        var n1 = manager(cluster, n1Wal);

        create(n1);
        var first = cluster.lastPut();
        n1.onStreamConfigPut(committed(first));
        n1.destroyStream(STREAM).onFailure(cause -> fail(cause.message()));
        n1.onStreamConfigRemove(removed());
        create(n1);
        var second = cluster.lastPut();
        n1.close();

        assertThat(first.value().config().incarnation()).as("first life minted").isNotZero();
        assertThat(second.value().config().incarnation()).as("second life minted").isNotZero()
                                                          .isNotEqualTo(first.value().config().incarnation());
    }

    /// Republish keeps the incarnation: a create of an already-committed name reuses the committed one.
    @Test
    void production_republishAfterHydrate_keepsTheCommittedIncarnation() {
        var cluster = new RecordingClusterNode();
        var n1 = manager(cluster, n1Wal);

        create(n1);
        var first = cluster.lastPut();
        n1.close();

        var restarted = manager(cluster, n1Wal);
        restarted.onStreamConfigPut(committed(first));
        restarted.createStream(config());
        restarted.close();

        assertThat(cluster.puts()).extracting(put -> put.value().config().incarnation())
                                  .containsOnly(first.value().config().incarnation());
    }

    /// Attack 3: destroy, then create on the same node BEFORE the asynchronously applied removal reaches this node
    /// (`applyRemoveCommand` is fire-and-forget; `appliedConfigs` loses the name only on the Remove notification). The
    /// recreate must still be a new life.
    @Test
    void production_recreateBeforeTheRemovalIsApplied_mintsANewIncarnation() {
        var cluster = new RecordingClusterNode();
        var n1 = manager(cluster, n1Wal);

        create(n1);
        var first = cluster.lastPut();
        n1.onStreamConfigPut(committed(first));
        n1.destroyStream(STREAM).onFailure(cause -> fail(cause.message()));
        create(n1); // the Remove has not been applied here yet
        var second = cluster.lastPut();
        n1.close();

        assertThat(second.value().config().incarnation()).as("a recreate is a new life")
                                                          .isNotEqualTo(first.value().config().incarnation());
    }

    /// Attack 4 end to end, through production: X hydrates life 1 and takes records, goes down, misses the destroy and
    /// the recreate (life 2), and on rejoin sees only life 2's committed Put. X must not serve life 1.
    @Test
    void production_downNode_rejoinsUnderTheNewLife_withNothingOfTheOld() {
        var cluster = new RecordingClusterNode();
        var n1 = manager(cluster, n1Wal);

        create(n1);
        var first = cluster.lastPut();
        var x = manager(new RecordingClusterNode(), xWal);
        x.onStreamConfigPut(committed(first));
        publish(x, 5);
        x.close();

        n1.onStreamConfigPut(committed(first));
        n1.destroyStream(STREAM).onFailure(cause -> fail(cause.message()));
        n1.onStreamConfigRemove(removed());
        create(n1);
        var second = cluster.lastPut();
        n1.close();

        var rejoined = manager(new RecordingClusterNode(), xWal);
        rejoined.onStreamConfigPut(committed(second));
        var head = rejoined.partitionInfo(STREAM, PARTITION).map(info -> info.headOffset()).or(-2L);
        rejoined.close();

        assertThat(head).as("X serves nothing of life 1").isEqualTo(-1L);
    }

    /// Same, when the recreate raced the removal (attack 3's window): X adopts life 1's WAL as the new life.
    @Test
    void production_downNode_afterARecreateThatRacedTheRemoval_servesNothingOfTheOld() {
        var cluster = new RecordingClusterNode();
        var n1 = manager(cluster, n1Wal);

        create(n1);
        var first = cluster.lastPut();
        var x = manager(new RecordingClusterNode(), xWal);
        x.onStreamConfigPut(committed(first));
        publish(x, 5);
        x.close();

        n1.onStreamConfigPut(committed(first));
        n1.destroyStream(STREAM).onFailure(cause -> fail(cause.message()));
        create(n1);
        var second = cluster.lastPut();
        n1.close();

        var rejoined = manager(new RecordingClusterNode(), xWal);
        rejoined.onStreamConfigPut(committed(second));
        var head = rejoined.partitionInfo(STREAM, PARTITION).map(info -> info.headOffset()).or(-2L);
        rejoined.close();

        assertThat(head).as("X serves nothing of life 1").isEqualTo(-1L);
    }

    /// Attack 2: concurrent first creates. n1 materializes on the publish path (ASYNC commit) under its own minted
    /// incarnation; n2's concurrent create proposes another. Round 4 (#1278): the KV applier commits ONE of the two
    /// lives (the other is refused by the life fence, pinned in `KVStoreIncarnationFenceTest`), so the round-3 order
    /// "a committed, then b committed over it" cannot occur; the losing proposer n1 sees only b commit. Ruling A: n1
    /// accepts nothing before a committed life applies (typed, retriable refusal), then every record it accepts under
    /// the committed life survives its restart.
    @Test
    void production_concurrentFirstCreate_recordsAcceptedByTheLosingLife_surviveARestart() {
        var c1 = new RecordingClusterNode();
        var c2 = new RecordingClusterNode();
        var n1 = manager(c1, n1Wal);
        var n2 = manager(c2, xWal);

        n1.ensureStreamMaterialized(config()).onFailure(cause -> fail(cause.message()));
        var beforeCommit = n1.publishLocal(STREAM, PARTITION, "early".getBytes(UTF_8), 999L);
        create(n2);
        var a = c1.lastPut();
        var b = c2.lastPut();
        n2.close();

        assertThat(a.value().config().incarnation()).as("fixture: two lives were minted").isNotEqualTo(b.value().config().incarnation());
        assertThat((boolean) beforeCommit.fold(cause -> cause instanceof StreamError.StreamConfigNotYetVisible, _ -> false))
            .as("ruling A: no accept before a committed life applies: %s", beforeCommit)
            .isTrue();
        n1.onStreamConfigPut(committed(b)); // the fence committed b; n1's proposal a lost
        var accepted = publish(n1, 5);
        n1.close();

        var restarted = manager(new RecordingClusterNode(), n1Wal);
        restarted.onStreamConfigPut(committed(b));
        var head = restarted.partitionInfo(STREAM, PARTITION).map(info -> info.headOffset()).or(-2L);
        restarted.close();

        assertThat(accepted).as("fixture: n1 accepted records").hasSize(5);
        assertThat(head).as("records n1 accepted (offsets %s) survive its restart", accepted).isEqualTo(4L);
    }

    private static StreamPartitionManager manager(RecordingClusterNode cluster, Path walDir) {
        return streamPartitionManager(Long.MAX_VALUE,
                                      EvictionListener.NOOP,
                                      ReplicationManager.NONE,
                                      cluster,
                                      OwnershipEpochHighWater.ownershipEpochHighWater(emptyStore()),
                                      StreamOwnerEpochSource.zero(),
                                      Option.some(AppendLog.Opener.directory(walDir)),
                                      LastSealedOffsetSource.none(),
                                      DurableSealedOffsetSource.none());
    }

    private static StreamConfig config() {
        return StreamConfig.streamConfig(STREAM, 1, RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 600_000), "earliest");
    }

    private static void create(StreamPartitionManager manager) {
        manager.createStream(config()).onFailure(cause -> fail(cause.message()));
    }

    private static List<Long> publish(StreamPartitionManager manager, int count) {
        var offsets = new ArrayList<Long>();

        for (var i = 0; i < count; i++) {
            manager.publishLocal(STREAM, PARTITION, ("e-" + i).getBytes(UTF_8), 1000L + i)
                   .onFailure(cause -> fail(cause.message()))
                   .onSuccess(offsets::add);
        }

        return offsets;
    }

    private static ValuePut<StreamConfigKey, StreamConfigValue> committed(KVCommand.Put<StreamConfigKey, StreamConfigValue> put) {
        return new ValuePut<>(put, Option.empty());
    }

    private static ValueRemove<StreamConfigKey, StreamConfigValue> removed() {
        return new ValueRemove<>(new KVCommand.Remove<StreamConfigKey>(StreamConfigKey.streamConfigKey(STREAM)), Option.empty());
    }

    private static final Serializer TO_STRING_BYTES = new Serializer() {
        @Override
        public <T> void write(ByteBuf byteBuf, T object) {
            byteBuf.writeBytes(String.valueOf(object).getBytes(UTF_8));
        }
    };

    private static final Deserializer UNUSED_DESERIALIZER = new Deserializer() {
        @Override
        public <T> T read(ByteBuf byteBuf) {
            throw new UnsupportedOperationException("unused");
        }
    };

    private static KVStore<AetherKey, AetherValue> emptyStore() {
        return new KVStore<>(MessageRouter.mutable(), TO_STRING_BYTES, UNUSED_DESERIALIZER);
    }

    private static final class RecordingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final List<KVCommand<AetherKey>> applied = Collections.synchronizedList(new ArrayList<>());

        @SuppressWarnings({"unchecked", "rawtypes"})
        List<KVCommand.Put<StreamConfigKey, StreamConfigValue>> puts() {
            synchronized (applied) {
                return applied.stream()
                              .filter(c -> c instanceof KVCommand.Put<?, ?> put && put.key() instanceof StreamConfigKey)
                              .map(c -> (KVCommand.Put<StreamConfigKey, StreamConfigValue>) (KVCommand.Put) c)
                              .toList();
            }
        }

        KVCommand.Put<StreamConfigKey, StreamConfigValue> lastPut() {
            var puts = puts();

            assertThat(puts).as("fixture: a config Put was issued").isNotEmpty();
            return puts.getLast();
        }

        @Override
        public NodeId self() {
            return SELF;
        }

        @Override
        public TopologyManager topologyManager() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Promise<Unit> start() {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.unitPromise();
        }

        @SuppressWarnings("unchecked")
        @Override
        public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            applied.addAll(commands);
            return (Promise<List<R>>) (Promise<?>) Promise.success(List.of());
        }
    }
}
