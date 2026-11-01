// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.aether.slice.RetentionDimensions;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.aether.stream.provenance.PartitionFlags;
import org.pragmatica.aether.stream.provenance.PartitionFlags.PartitionFlag;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;
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
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #2201 I-20 / NT-23 and the two named exceptions of PR-2a (know `548514c110` Q1, `d9cd07cda1` S1): what a stream's per-partition
/// state (spec 5.1 rows 4-19) carries across the end of a life. A removed stream re-created under the same name starts with none of
/// the dead life's state on all three removal paths; a committed incarnation that replaces a losing proposal starts with none of the
/// losing life's; a committed-config adoption of the SAME life keeps everything.
///
/// The state is seeded through the paths that write it in production, never by touching a map: the replica epoch verdict
/// ([StreamPartitionManager#markVerifiedForEpoch]), the owner's visible position ([StreamPartitionManager#commitAdvanced]), a cut
/// ([StreamPartitionManager#beginCut] / [StreamPartitionManager#endCut], the pair the ring's ordered section calls), a settled
/// catch-up install ([StreamPartitionManager#releaseProvenance]) and a durable-flag raise (records with no owner-epoch history).
/// It is observed through behaviour where one exists (the epoch verdict, the cut generation, a second raise) and otherwise through
/// [StreamPartitionManager#heldPartitionRows].
class StreamPartitionStateLifetimeTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final Epoch EPOCH = Epoch.epoch(1L, 1L, 1L);
    private static final long CUT_MASK = (1L << 24) - 1;
    private static final byte[] PAYLOAD = "e".getBytes(UTF_8);
    /// The rows NT-23 names.
    private static final Set<String> NAMED_ROWS = Set.of("epochTrust", "replicaCommitted", "pendingInstalls", "cutGenerations", "raisedLocally");

    @TempDir
    Path walDir;

    private final AtomicReference<Role> role = new AtomicReference<>(Role.OWNER);
    private final List<AetherValue.PartitionRecoveryReason> raised = new CopyOnWriteArrayList<>();

    /// #2201 NT-23: a stream removed on any of the three paths and re-created under the same name starts with empty `epochTrust`,
    /// `replicaCommitted`, `pendingInstalls`, `cutGenerations` and `raisedLocally`. RED at `15ac4ef4da` on all three arms: none of
    /// the five has a removal site on a removal path. The adoption arm is a PIN, green by design at every commit: a committed
    /// config of the SAME life is adopted in place (`adoptConfig` -> `withConfig`) and must keep the state.
    @Nested
    class RecreatedStreamStartsEmpty {
        private StreamPartitionManager manager;

        @BeforeEach
        void setUp() {
            manager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));
            manager.ownerEpochSource((_, _) -> EPOCH);
            manager.epochStarts((_, _, _) -> Option.some(0L));
            manager.placementRoleSupplier((_, _) -> role.get());
            manager.partitionFlags(recordingFlags(raised));
        }

        @AfterEach
        void tearDown() {
            manager.close();
        }

        @Test
        void destroyStream_recreatedStream_startsWithNoneOfTheDeadLifesPartitionState() {
            removedAndRecreated(_ -> manager.destroyStream(STREAM).onFailure(cause -> fail(cause.message())));
        }

        @Test
        void onStreamConfigRemove_recreatedStream_startsWithNoneOfTheDeadLifesPartitionState() {
            removedAndRecreated(_ -> manager.onStreamConfigRemove(removal()));
        }

        @Test
        void reapIdleStreams_recreatedStream_startsWithNoneOfTheDeadLifesPartitionState() {
            removedAndRecreated(_ -> reap(manager));
        }

        /// PIN, green by design: the adopted entry shares the stream's state, because the state is keyed by stream, not by entry.
        @Test
        void adoptConfig_sameLifeStrongerDurability_keepsThePartitionState() {
            create(manager);
            seed(manager);
            var enforced = manager.enforcedConfig(STREAM).unwrap();
            var stronger = enforced.withReplication(new ReplicationFactors(enforced.replicationFactor() + 1,
                                                                          enforced.confirmationFactor()));

            manager.onStreamConfigPut(committed(stronger));

            assertThat(manager.enforcedConfig(STREAM).map(StreamConfig::replicationFactor).or(0))
                .as("fixture: the stronger config of the same life was adopted").isEqualTo(enforced.replicationFactor() + 1);
            assertThat(manager.heldPartitionRows(STREAM, PARTITION)).as("adoption keeps every row").containsAll(NAMED_ROWS);
            assertThat(manager.replicaVerified(STREAM, PARTITION)).as("epochTrust kept: still verified for the epoch").isTrue();
            assertThat(manager.cutGeneration(STREAM, PARTITION) & CUT_MASK).as("cutGenerations kept").isEqualTo(2L);
        }

        private void removedAndRecreated(Consumer<Unit> removal) {
            create(manager);
            seed(manager);

            removal.accept(Unit.unit());
            assertThat(manager.streamInfo(STREAM).isEmpty()).as("fixture: the stream was removed").isTrue();
            create(manager);

            assertThat(manager.heldPartitionRows(STREAM, PARTITION)).as("the re-created stream inherits none of the named rows")
                                                                    .doesNotContainAnyElementsOf(NAMED_ROWS);
            assertThat(manager.cutGeneration(STREAM, PARTITION) & CUT_MASK).as("cutGenerations starts at zero").isZero();

            caughtUp(manager, 0);
            assertThat(manager.replicaVerified(STREAM, PARTITION)).as("epochTrust is judged afresh: the new copy holds offset 0, at the epoch's start")
                                                                  .isFalse();

            live(manager, 1);
            assertThat(raised).as("raisedLocally starts empty: the new life raises its own HISTORY_MISSING").hasSize(2);
        }
    }

    /// #2201 PR-2a Q1 (know `548514c110`, `d9cd07cda1`): a committed config of ANOTHER incarnation replaces this node's losing
    /// proposal (`replaceLosingLife`), and the committed life inherits none of the state recorded under the name while the losing
    /// life was local. RED at `15ac4ef4da`: the replacement discards the entry and nothing else. The losing life accepted no write
    /// (ruling A), so its ring holds nothing that could diverge or be flagged: `divergedAt` and `raisedLocally` cannot be seeded
    /// through a losing life, and the four rows below are the ones the entry-less paths write.
    @Nested
    class LosingLifeReplaced {
        private static final Set<String> ENTRY_LESS_ROWS = Set.of("epochTrust", "replicaCommitted", "pendingInstalls", "cutGenerations");

        @Test
        void replaceLosingLife_committedIncarnation_inheritsNoneOfTheLosingLifesPartitionState() {
            var node = clusterManager();

            try {
                node.createStream(config()).onFailure(cause -> fail(cause.message()));
                var proposed = node.enforcedConfig(STREAM).map(StreamConfig::incarnation).or(-1L);

                assertThat(node.publishLocal(STREAM, PARTITION, PAYLOAD, 1L).isFailure())
                    .as("fixture: the proposal is not committed and accepts no write").isTrue();
                node.placementRoleSupplier((_, _) -> Role.REPLICA);
                node.markVerifiedForEpoch(STREAM, PARTITION, EPOCH);
                node.commitAdvanced(STREAM, PARTITION, 3L, EPOCH);
                cut(node);
                settleInstall(node);
                assertThat(node.heldPartitionRows(STREAM, PARTITION)).as("fixture: the state was recorded").containsAll(ENTRY_LESS_ROWS);

                node.onStreamConfigPut(committed(config().withIncarnation(proposed + 1)));

                assertThat(node.enforcedConfig(STREAM).map(StreamConfig::incarnation).or(-1L))
                    .as("fixture: the committed incarnation replaced the proposal").isEqualTo(proposed + 1);
                assertThat(node.heldPartitionRows(STREAM, PARTITION)).as("the committed life inherits none of it")
                                                                     .doesNotContainAnyElementsOf(ENTRY_LESS_ROWS);
                assertThat(node.cutGeneration(STREAM, PARTITION) & CUT_MASK).as("cutGenerations starts at zero").isZero();
            } finally {
                node.close();
            }
        }

        private StreamPartitionManager clusterManager() {
            return streamPartitionManager(Long.MAX_VALUE,
                                          EvictionListener.NOOP,
                                          ReplicationManager.NONE,
                                          new RecordingClusterNode(),
                                          OwnershipEpochHighWater.ownershipEpochHighWater(emptyStore()),
                                          StreamOwnerEpochSource.zero(),
                                          Option.some(AppendLog.Opener.directory(walDir)),
                                          LastSealedOffsetSource.none(),
                                          DurableSealedOffsetSource.none());
        }
    }

    private void create(StreamPartitionManager manager) {
        role.set(Role.OWNER);
        manager.createStream(config()).onFailure(cause -> fail(cause.message()));
        role.set(Role.REPLICA);
    }

    /// Life one: every named row holds an entry, each through the path that writes it in production.
    private void seed(StreamPartitionManager manager) {
        caughtUp(manager, 0);
        live(manager, 1);
        manager.syncReplicated(STREAM, PARTITION).await();
        manager.markVerifiedForEpoch(STREAM, PARTITION, EPOCH);
        manager.commitAdvanced(STREAM, PARTITION, 1L, EPOCH);
        cut(manager);
        settleInstall(manager);

        assertThat(raised).as("fixture: records with no owner-epoch history raised HISTORY_MISSING once").hasSize(1);
        assertThat(manager.replicaVerified(STREAM, PARTITION)).as("fixture: verified for the epoch").isTrue();
        assertThat(manager.cutGeneration(STREAM, PARTITION) & CUT_MASK).as("fixture: one closed cut").isEqualTo(2L);
        assertThat(manager.heldPartitionRows(STREAM, PARTITION)).as("fixture: every named row holds an entry").containsAll(NAMED_ROWS);
    }

    private static void cut(StreamPartitionManager manager) {
        var token = new Object();

        manager.beginCut(STREAM, PARTITION, token);
        manager.endCut(STREAM, PARTITION, token);
    }

    private static void settleInstall(StreamPartitionManager manager) {
        manager.releaseProvenance(StreamPartitionManager.InstalledSlice.installedSlice(STREAM, PARTITION, List.of(), List.of()));
    }

    private static void caughtUp(StreamPartitionManager manager, long offset) {
        manager.appendRecovered(STREAM, PARTITION, offset, PAYLOAD, 1L).onFailure(cause -> fail(cause.message()));
    }

    private static void live(StreamPartitionManager manager, long offset) {
        manager.appendRecovered(STREAM, PARTITION, offset, PAYLOAD, 1L, EPOCH).onFailure(cause -> fail(cause.message()));
    }

    /// The reaper takes an expired, idle stream whose rings hold nothing: the age tick hands the (old-stamped) records over first.
    private static void reap(StreamPartitionManager manager) {
        manager.evictRingsByAge();
        busyWait(5);
        assertThat(manager.reapIdleStreams()).as("fixture: the idle, emptied stream is reaped").isEqualTo(1);
    }

    private static void busyWait(long millis) {
        var deadline = System.nanoTime() + millis * 1_000_000L;

        while (System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    /// A `time` retention with a 1 ms age: old records age out on the tick, and an idle stream expires at once.
    private static StreamConfig config() {
        return StreamConfig.streamConfig(STREAM,
                                         1,
                                         RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 1).withDeclared(RetentionDimensions.AGE),
                                         "earliest");
    }

    private static ValuePut<StreamConfigKey, StreamConfigValue> committed(StreamConfig config) {
        return new ValuePut<>(new KVCommand.Put<>(StreamConfigKey.streamConfigKey(STREAM), StreamConfigValue.streamConfigValue(config)),
                              Option.empty());
    }

    private static ValueRemove<StreamConfigKey, StreamConfigValue> removal() {
        return new ValueRemove<>(new KVCommand.Remove<StreamConfigKey>(StreamConfigKey.streamConfigKey(STREAM)), Option.empty());
    }

    private static PartitionFlags recordingFlags(List<AetherValue.PartitionRecoveryReason> raised) {
        return new PartitionFlags() {
            @Override
            public Promise<PartitionFlag> raise(String stream, int partition, AetherValue.PartitionRecoveryReason reason) {
                raised.add(reason);

                return Promise.success(new PartitionFlag(AetherValue.StreamPartitionRecoveryValue.raised(Option.none(), reason), "digest"));
            }

            @Override
            public Option<PartitionFlag> status(String stream, int partition) {
                return Option.none();
            }

            @Override
            public AetherValue.PartitionRecoveryReason local(AetherValue.PartitionRecoveryReasonKind kind, String evidence) {
                return AetherValue.PartitionRecoveryReason.partitionRecoveryReason(kind, Option.some("self"), evidence);
            }
        };
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

    /// Accepts every proposal and applies none, so a created stream stays an uncommitted proposal.
    private static final class RecordingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
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
            return (Promise<List<R>>) (Promise<?>) Promise.success(List.of());
        }
    }
}
