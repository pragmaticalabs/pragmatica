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

/// v1866 round 4 probes, production create path only (cluster-backed managers; no hand-fed incarnation).
class V1866R4ProbeTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId SELF = NodeId.nodeId("node-probe").unwrap();

    @TempDir
    Path n1Wal;

    @TempDir
    Path n2Wal;

    /// Attack 2: the refused proposer. n1 proposes life b on the publish path; n2's life a is the one the fence commits.
    /// n1's publish is refused retriably until a applies, then accepted into a, and survives n1's restart.
    @Test
    void refusedProposer_adoptsTheCommittedLife_andPublishesResume() {
        var c1 = new RecordingClusterNode();
        var c2 = new RecordingClusterNode();
        var n2 = manager(c2, n2Wal);
        create(n2);
        var a = c2.lastPut();
        n2.close();

        var n1 = manager(c1, n1Wal);
        n1.ensureStreamMaterialized(config()).onFailure(cause -> fail(cause.message()));
        var b = c1.lastPut();
        assertThat(b.value().config().incarnation()).as("fixture: two lives").isNotEqualTo(a.value().config().incarnation());

        var refused = n1.publishLocal(STREAM, PARTITION, "early".getBytes(UTF_8), 999L);
        assertThat((boolean) refused.fold(cause -> cause instanceof StreamError.StreamConfigNotYetVisible, _ -> false))
            .as("positive control: refused before a committed life applies: %s", refused).isTrue();

        n1.onStreamConfigPut(committed(a)); // the fence committed a; n1's Put(b) was refused with no notification
        var retried = n1.ensureStreamMaterialized(config());
        var accepted = publish(n1, 3);
        n1.close();

        var restarted = manager(new RecordingClusterNode(), n1Wal);
        restarted.onStreamConfigPut(committed(a));
        var head = restarted.partitionInfo(STREAM, PARTITION).map(info -> info.headOffset()).or(-2L);
        restarted.close();

        assertThat(retried.isSuccess()).as("the publish path's re-create after losing: %s", retried).isTrue();
        assertThat(accepted).containsExactly(0L, 1L, 2L);
        assertThat(head).isEqualTo(2L);
    }

    /// Attack 5, replication-applied path: a REPLICA still holding its own losing proposal (uncommitted entry b) receives the
    /// committed owner's record. It must not ack it into b: once a applies, b's WALs are deleted with the record.
    @Test
    void replicaHoldingALosingProposal_doesNotAckAReplicatedRecordIntoIt() {
        var c1 = new RecordingClusterNode();
        var replica = manager(c1, n1Wal);
        replica.createStream(config()); // proposal b; the round resolves but b is not the applied life
        var b = c1.lastPut();
        var a = new KVCommand.Put<StreamConfigKey, StreamConfigValue>(b.key(),
                                                                      StreamConfigValue.streamConfigValue(b.value().config()
                                                                                                           .withIncarnation(b.value().config().incarnation() + 1)));

        var acked = replica.appendRecovered(STREAM, PARTITION, 0L, "owner-0".getBytes(UTF_8), 1000L);
        replica.onStreamConfigPut(committed(a));
        var head = replica.partitionInfo(STREAM, PARTITION).map(info -> info.headOffset()).or(-2L);
        replica.close();

        assertThat(acked.isFailure()).as("a replica must not ack into an uncommitted losing life (acked=%s, head after adopting a=%d)",
                                         acked, head).isTrue();
    }

    /// Positive control for the probe above: once the replica's entry is the applied committed life, the same append is acked.
    @Test
    void replicaOfTheCommittedLife_acksAReplicatedRecord() {
        var c1 = new RecordingClusterNode();
        var replica = manager(c1, n1Wal);
        replica.createStream(config());
        replica.onStreamConfigPut(committed(c1.lastPut()));

        var acked = replica.appendRecovered(STREAM, PARTITION, 0L, "owner-0".getBytes(UTF_8), 1000L);
        replica.close();

        assertThat(acked.isSuccess()).as("control: %s", acked).isTrue();
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
