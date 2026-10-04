// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.ReplicationFactors;
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
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.storage.AppendLog;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #1278 round 4: the committed life is the only authority (ruling B). A node that holds an applied committed life
/// adopts it rather than proposing another, and a committed removal ends the life on every node that applies it.
/// Both go through the production paths: a manager with a cluster node, notifications as the KV store emits them.
class StreamLifeAuthorityTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final long COMMITTED_LIFE = 77L;

    @TempDir
    Path walDir;

    /// A committed life applies while this node cannot open its WAL, so no entry is built. A create that follows must
    /// ADOPT the committed life — not propose a new one, which the life fence would refuse and leave this node holding
    /// an uncommitted entry that refuses every write.
    @Test
    void createStream_withAnAppliedLifeAndNoLocalEntry_adoptsTheCommittedLife_proposesNothing_andAcceptsWrites() {
        var cluster = new RecordingClusterNode();
        var refuseOnce = new AtomicBoolean(true);
        var directory = AppendLog.Opener.directory(walDir);
        AppendLog.Opener flaky = name -> refuseOnce.getAndSet(false)
                                         ? Result.failure(() -> "WAL refused once")
                                         : directory.open(name);
        var node = manager(cluster, flaky);

        node.onStreamConfigPut(committed(config().withIncarnation(COMMITTED_LIFE)));
        assertThat(node.partitionInfo(STREAM, PARTITION).isSuccess()).as("fixture: the refused WAL left no entry").isFalse();

        node.createStream(config());
        var published = node.publishLocal(STREAM, PARTITION, "e".getBytes(UTF_8), 1000L);
        node.close();

        var restarted = manager(new RecordingClusterNode(), directory);
        restarted.onStreamConfigPut(committed(config().withIncarnation(COMMITTED_LIFE)));
        var head = restarted.partitionInfo(STREAM, PARTITION).map(info -> info.headOffset()).or(-2L);
        restarted.close();

        assertThat(cluster.streamConfigPuts()).as("a create of a committed name proposes no new life").isEmpty();
        assertThat(published.isSuccess()).as("the adopted committed life accepts writes: %s", published).isTrue();
        assertThat(head).as("the accepted record is in the committed life's WAL").isEqualTo(0L);
    }

    /// A follower holding a committed life with accepted records stops serving it when the committed removal applies.
    @Test
    void onStreamConfigRemove_endsTheCommittedLife_onAFollower() {
        var node = manager(new RecordingClusterNode(), AppendLog.Opener.directory(walDir));

        node.onStreamConfigPut(committed(config().withIncarnation(COMMITTED_LIFE)));
        for (var i = 0; i < 3; i++) {
            assertThat(node.publishLocal(STREAM, PARTITION, ("e-" + i).getBytes(UTF_8), 1000L + i).isSuccess()).isTrue();
        }

        node.onStreamConfigRemove(new ValueRemove<>(new KVCommand.Remove<StreamConfigKey>(StreamConfigKey.streamConfigKey(STREAM)),
                                                    Option.empty()));
        var afterRemoval = node.publishLocal(STREAM, PARTITION, "late".getBytes(UTF_8), 2000L);
        var served = node.partitionInfo(STREAM, PARTITION).isSuccess();
        node.close();

        assertThat(served).as("the removed life is not served").isFalse();
        assertThat(afterRemoval.isFailure()).as("the removed life accepts nothing: %s", afterRemoval).isTrue();
    }

    /// #1883: what a committed config will make [StreamPartitionManager#confirmationFactorFor] report, read BEFORE
    /// the config is applied, follows the adoption rule: only a strictly stronger config of the same life and shape
    /// changes the factor. A lowering stays at the old factor, because that is what acks keep enforcing.
    @Test
    void confirmationFactorAfter_followsTheAdoptionRule_andAgreesWithWhatIsEnforcedOnceApplied() {
        var node = manager(new RecordingClusterNode(), AppendLog.Opener.directory(walDir));
        var life = config().withIncarnation(COMMITTED_LIFE);
        var rf3cf2 = life.withReplication(new ReplicationFactors(3, 2));

        assertThat(node.confirmationFactorAfter(rf3cf2)).as("a stream not held yet has no factor to move from").isZero();
        node.onStreamConfigPut(committed(rf3cf2));
        assertThat(node.confirmationFactorFor(STREAM)).isEqualTo(2);

        var raised = life.withReplication(new ReplicationFactors(3, 3));
        var lowered = life.withReplication(new ReplicationFactors(3, 1));
        var anotherLife = raised.withIncarnation(COMMITTED_LIFE + 1);

        assertThat(node.confirmationFactorAfter(raised)).as("a raise is adopted").isEqualTo(3);
        assertThat(node.confirmationFactorAfter(lowered)).as("a lowering is not adopted: acks keep the old factor").isEqualTo(2);
        assertThat(node.confirmationFactorAfter(rf3cf2)).as("an unchanged config moves nothing").isEqualTo(2);
        assertThat(node.confirmationFactorAfter(anotherLife)).as("a new life is a recreate, not a factor change").isEqualTo(2);

        node.onStreamConfigPut(committed(lowered));
        assertThat(node.confirmationFactorFor(STREAM)).as("the prediction held: the lowering was not enforced").isEqualTo(2);
        node.onStreamConfigPut(committed(raised));
        assertThat(node.confirmationFactorFor(STREAM)).as("the prediction held: the raise was enforced").isEqualTo(3);
        node.close();
    }

    /// #1883: why a committed config is not applied over the enforced one follows the adoption rule exactly, and names
    /// the actual cause: a partition-count change (either direction) first, otherwise weaker-or-equal durability.
    @Test
    void notAppliedReason_followsTheAdoptionRule_andNamesTheCause() {
        var enforced = config().withIncarnation(COMMITTED_LIFE).withReplication(new ReplicationFactors(3, 3));
        var weaker = enforced.withReplication(new ReplicationFactors(3, 2));
        var mixedRfUp = enforced.withReplication(new ReplicationFactors(5, 1));
        var stronger = enforced.withReplication(new ReplicationFactors(5, 4));

        assertThat(StreamPartitionManager.notAppliedReason(weaker, enforced).or("")).startsWith("durability only increases online");
        assertThat(StreamPartitionManager.notAppliedReason(enforced, enforced).isEmpty()).as("unchanged").isTrue();
        assertThat(StreamPartitionManager.notAppliedReason(stronger, enforced).isEmpty()).as("adopted").isTrue();
        assertThat(StreamPartitionManager.notAppliedReason(mixedRfUp, enforced).isEmpty()).as("RF up adopts the whole config").isTrue();
        assertThat(StreamPartitionManager.notAppliedReason(enforced.withIncarnation(COMMITTED_LIFE + 1).withReplication(new ReplicationFactors(3, 1)), enforced).isEmpty())
            .as("another life is a recreate").isTrue();
        var morePartitions = new StreamConfig(STREAM, enforced.partitions() * 2, enforced.retention(), enforced.autoOffsetReset(), enforced.maxEventSizeBytes(),
                                              enforced.consistencyMode(), 5, 4, enforced.compression(), enforced.encryptionKeyId(), COMMITTED_LIFE);

        assertThat(StreamPartitionManager.notAppliedReason(morePartitions, enforced).or("")).as("a stronger config with another partition count is not adopted")
                                                                                          .startsWith("partition count of an existing stream cannot change");
    }

    private static StreamPartitionManager manager(RecordingClusterNode cluster, AppendLog.Opener opener) {
        return streamPartitionManager(Long.MAX_VALUE,
                                      EvictionListener.NOOP,
                                      ReplicationManager.NONE,
                                      cluster,
                                      OwnershipEpochHighWater.ownershipEpochHighWater(emptyStore()),
                                      StreamOwnerEpochSource.zero(),
                                      Option.some(opener),
                                      LastSealedOffsetSource.none(),
                                      DurableSealedOffsetSource.none());
    }

    private static StreamConfig config() {
        return StreamConfig.streamConfig(STREAM, 1, RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 600_000), "earliest");
    }

    private static ValuePut<StreamConfigKey, StreamConfigValue> committed(StreamConfig config) {
        return new ValuePut<>(new KVCommand.Put<>(StreamConfigKey.streamConfigKey(STREAM), StreamConfigValue.streamConfigValue(config)),
                              Option.empty());
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

        List<KVCommand<AetherKey>> streamConfigPuts() {
            synchronized (applied) {
                return applied.stream()
                              .filter(command -> command instanceof KVCommand.Put<?, ?> put && put.key() instanceof StreamConfigKey)
                              .toList();
            }
        }

        @Override
        public NodeId self() {
            return NodeId.nodeId("node-life").unwrap();
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
