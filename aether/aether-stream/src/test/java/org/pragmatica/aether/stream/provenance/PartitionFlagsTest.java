// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.provenance;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionRecoveryKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.PartitionRecoveryReason;
import org.pragmatica.aether.slice.kvstore.AetherValue.PartitionRecoveryReasonKind;
import org.pragmatica.aether.slice.kvstore.AetherValue.PartitionRecoveryState;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionRecoveryValue;
import org.pragmatica.aether.slice.kvstore.KvstoreCodecsSlice;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.serialization.SliceCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #1596: the durable partition flag against a real [KVStore] applier, whose leader-authorized CAS is what makes a
/// raise idempotent and two concurrent raises lossless. Each test names the mutation that reddens it.
class PartitionFlagsTest {
    private static final NodeId SELF = NodeId.nodeId("node-1").unwrap();
    private static final LeaderValue LEADER = LeaderValue.leaderValue(SELF, 1);
    private static final String STREAM = "orders";
    private static final int PARTITION = 2;
    private static final StreamPartitionRecoveryKey KEY = StreamPartitionRecoveryKey.streamPartitionRecoveryKey(STREAM,
                                                                                                                 PARTITION);
    private static final PartitionRecoveryReason DIVERGED = reason(PartitionRecoveryReasonKind.DIVERGED, "first differing offset 11");
    private static final PartitionRecoveryReason MISSING = reason(PartitionRecoveryReasonKind.HISTORY_MISSING, "no history");

    private KVStore<AetherKey, AetherValue> kvStore;
    private TestNode node;
    private PartitionFlags flags;

    @BeforeEach
    void setUp() {
        kvStore = emptyStore();
        node = new TestNode(kvStore);
        seedLeader(kvStore);
        flags = PartitionFlags.kvPartitionFlags(node, kvStore, codec());
    }

    @Test
    void raise_onAnUnflaggedPartition_commitsItFlagged_andStatusReadsIt() {
        var raised = flags.raise(STREAM, PARTITION, DIVERGED).await().unwrap();

        assertThat(raised.record()).isEqualTo(StreamPartitionRecoveryValue.streamPartitionRecoveryValue(PartitionRecoveryState.FLAGGED,
                                                                                                        Set.of(DIVERGED)));
        assertThat(raised.recordDigest()).matches("[0-9a-f]{64}");
        assertThat(flags.status(STREAM, PARTITION)).isEqualTo(Option.some(raised));
        assertThat(flags.status(STREAM, PARTITION + 1)).isEqualTo(Option.none());
    }

    /// R7-2: raising the same reason again writes nothing and keeps the digest. Red under "always write".
    @Test
    void raise_sameReasonAgain_writesNothing_andKeepsTheDigest() {
        var first = flags.raise(STREAM, PARTITION, DIVERGED).await().unwrap();
        var transactions = node.transactions.get();

        var again = flags.raise(STREAM, PARTITION, DIVERGED).await().unwrap();

        assertThat(node.transactions.get()).as("no transaction for an unchanged record").isEqualTo(transactions);
        assertThat(again.recordDigest()).isEqualTo(first.recordDigest());
    }

    /// A new reason joins the set and changes the digest -- the operator must see new evidence.
    @Test
    void raise_newReason_joinsTheSet_andChangesTheDigest() {
        var first = flags.raise(STREAM, PARTITION, DIVERGED).await().unwrap();
        var second = flags.raise(STREAM, PARTITION, MISSING).await().unwrap();

        assertThat(second.record().reasons()).containsExactlyInAnyOrder(DIVERGED, MISSING);
        assertThat(second.recordDigest()).isNotEqualTo(first.recordDigest());
    }

    /// A concurrent raise lands between this raise's read and its write: the CAS refuses, the raise re-reads and
    /// commits the union, so neither reason is lost. Red under "write without the expected previous record".
    @Test
    void raise_racingAnotherRaise_losesNeitherReason() {
        node.beforeNextTransaction(() -> node.applyNow(transaction(Option.none(),
                                                                   StreamPartitionRecoveryValue.raised(Option.none(), MISSING))));

        var raised = flags.raise(STREAM, PARTITION, DIVERGED).await().unwrap();

        assertThat(raised.record().reasons()).containsExactlyInAnyOrder(DIVERGED, MISSING);
        assertThat(kvStore.getTyped(KEY, StreamPartitionRecoveryValue.class).map(StreamPartitionRecoveryValue::reasons))
            .isEqualTo(Option.some(Set.of(DIVERGED, MISSING)));
    }

    @Test
    void raise_withoutACommittedLeader_isRefused() {
        var leaderless = emptyStore();
        var leaderlessFlags = PartitionFlags.kvPartitionFlags(new TestNode(leaderless), leaderless, codec());

        leaderlessFlags.raise(STREAM, PARTITION, DIVERGED)
                       .await()
                       .onSuccess(_ -> fail("a raise needs a committed leader to witness it"))
                       .onFailure(cause -> assertThat(cause).isEqualTo(PartitionFlags.FlagError.NO_LEADER));
    }

    @Test
    void local_namesThisNodesCopy() {
        assertThat(flags.local(PartitionRecoveryReasonKind.LOCAL_MISMATCH, "x").storageId()).isEqualTo(Option.some("node-1"));
    }

    /// The leader row lives under a key outside [AetherKey]; the applier stores it like any other (raw batch, as the
    /// deployment tests seed it).
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void seedLeader(KVStore<AetherKey, AetherValue> store) {
        store.process(store.createBatch((List) List.of(new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER))));
    }

    private static KVCommand<AetherKey> transaction(Option<StreamPartitionRecoveryValue> expected,
                                                    StreamPartitionRecoveryValue replacement) {
        var mutation = new KVCommand.Mutation<AetherKey, AetherValue>(KEY,
                                                                      expected.map(AetherValue.class::cast),
                                                                      Option.some(replacement));

        return new KVCommand.LeaderTransaction<>(KEY, "concurrent", LEADER, List.of(), List.of(mutation));
    }

    private static PartitionRecoveryReason reason(PartitionRecoveryReasonKind kind, String evidence) {
        return PartitionRecoveryReason.partitionRecoveryReason(kind, Option.some("node-1"), evidence);
    }

    /// The value's own codecs and the framework's; the digest encodes through them canonically.
    private static SliceCodec codec() {
        return SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), KvstoreCodecsSlice.CODECS);
    }

    private static KVStore<AetherKey, AetherValue> emptyStore() {
        return new KVStore<>(MessageRouter.mutable(), new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        }, new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        });
    }

    /// A single node whose consensus is the local applier: `apply` processes the batch at once, as a committed
    /// batch is applied. A hook can run a competing command first, as another node's raise would.
    private static final class TestNode implements ClusterNode<KVCommand<AetherKey>> {
        private final KVStore<AetherKey, AetherValue> kvStore;
        private final AtomicInteger transactions = new AtomicInteger();
        private final List<Runnable> before = new ArrayList<>();

        private TestNode(KVStore<AetherKey, AetherValue> kvStore) {
            this.kvStore = kvStore;
        }

        void beforeNextTransaction(Runnable hook) {
            before.add(hook);
        }

        void applyNow(KVCommand<AetherKey> command) {
            kvStore.process(kvStore.createBatch(List.of(command)));
        }

        @Override
        public NodeId self() {
            return SELF;
        }

        @Override
        public TopologyManager topologyManager() {
            return null;
        }

        @Override
        public Promise<Unit> start() {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.unitPromise();
        }

        @Override
        public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            transactions.incrementAndGet();
            before.forEach(Runnable::run);
            before.clear();

            return Promise.success(kvStore.process(kvStore.createBatch(commands)));
        }
    }
}
