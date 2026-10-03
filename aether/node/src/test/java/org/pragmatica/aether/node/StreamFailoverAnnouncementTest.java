// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.OperationalEvent;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.replication.PartitionKey;
import org.pragmatica.aether.stream.replication.StreamPartitionOwnershipWriter;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/// #1730 owner ruling: a stream partition's failover refusal is announced as an operational event on the TRANSITION,
/// once, and its resolution likewise — never per reconcile. Driven end to end through the production pieces: the
/// ISR-aware ownership writer, the real KV applier deciding its guarded transactions, and
/// [AetherNode#driveStreamOwnership] with the production [StreamFailoverAnnouncer].
class StreamFailoverAnnouncementTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId A = new NodeId("node-a");
    private static final NodeId B = new NodeId("node-b");
    private static final NodeId C = new NodeId("node-c");
    private static final Epoch GENERATION = Epoch.epoch(1L, 2L, 0L);
    private static final LeaderValue LEADER = new LeaderValue(C, 1L);
    private static final List<PartitionKey> PASS = List.of(PartitionKey.partitionKey(STREAM, PARTITION));

    private final KVStore<AetherKey, AetherValue> store = store();
    private final AtomicReference<List<NodeId>> live = new AtomicReference<>(List.of(C));
    private final List<OperationalEvent> announced = new CopyOnWriteArrayList<>();

    @Test
    void refusal_isAnnouncedOnce_repeatedReconcilesAnnounceNothing_andRecoveryAnnouncesResolvedOnce() {
        seedLeaderAndRecord(List.of(A, B));
        var writer = writer();
        var retry = AetherNode.StreamOwnershipRetry.streamOwnershipRetry((_, _) -> {}, () -> {});
        var announcer = StreamFailoverAnnouncer.streamFailoverAnnouncer(live::get, announced::add);

        for (var pass = 0; pass < 3; pass++) {
            AetherNode.driveStreamOwnership(writer, this::apply, retry, announcer, PASS);
        }

        assertThat(committed().failoverRefused()).as("the refusal is committed").isTrue();
        assertThat(announced).as("three reconciles of a refused partition: one announcement")
                             .singleElement()
                             .isInstanceOfSatisfying(OperationalEvent.StreamFailoverRefused.class, event -> {
                                 assertThat(event.stream()).isEqualTo(STREAM);
                                 assertThat(event.partition()).isEqualTo(PARTITION);
                                 assertThat(event.owner()).isEqualTo(A.id());
                                 assertThat(event.isr()).containsExactly(A.id(), B.id());
                                 assertThat(event.live()).containsExactly(C.id());
                             });

        live.set(List.of(B, C));
        for (var pass = 0; pass < 3; pass++) {
            AetherNode.driveStreamOwnership(writer, this::apply, retry, announcer, PASS);
        }

        assertThat(committed().owner()).as("an ISR member was elected").isEqualTo(B);
        assertThat(announced).hasSize(2);
        assertThat(announced.get(1)).isInstanceOfSatisfying(OperationalEvent.StreamFailoverResolved.class,
                                                            event -> assertThat(event.owner()).isEqualTo(B.id()));
    }

    /// A transaction the applier REFUSED changed nothing, so nothing is announced for it.
    @Test
    void refusedTransaction_announcesNothing() {
        var before = record(List.of(A, B));
        var id = "refuse-1";
        KVCommand<AetherKey> transaction = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key(),
                                                                                                    id,
                                                                                                    LEADER,
                                                                                                    List.of(),
                                                                                                    List.of(new KVCommand.Mutation<AetherKey, AetherValue>(key(),
                                                                                                                                                           Option.some(before),
                                                                                                                                                           Option.some(before.withFailoverRefused(true)))));

        assertThat(StreamFailoverAnnouncer.transitions(List.of(transaction),
                                                       List.of(new KVCommand.TransactionResult(id, false)),
                                                       List.of(C))).isEmpty();
        assertThat(StreamFailoverAnnouncer.transitions(List.of(transaction),
                                                       List.of(new KVCommand.TransactionResult(id, true)),
                                                       List.of(C))).hasSize(1);
    }

    private StreamPartitionOwnershipWriter writer() {
        return StreamPartitionOwnershipWriter.streamPartitionOwnershipWriter(() -> true,
                                                                             () -> GENERATION,
                                                                             HlcClock.hlcClock(C),
                                                                             (_, _) -> Option.option(committedOrNull()),
                                                                             (_, _) -> Option.some(A),
                                                                             new StreamPartitionOwnershipWriter.IsrInputs() {
                                                                                 @Override
                                                                                 public List<NodeId> liveMembers() {
                                                                                     return live.get();
                                                                                 }

                                                                                 @Override
                                                                                 public List<NodeId> initialIsr(String stream,
                                                                                                                int partition,
                                                                                                                NodeId owner) {
                                                                                     return List.of(owner);
                                                                                 }
                                                                             },
                                                                             () -> store.getTyped(LeaderKey.INSTANCE,
                                                                                                  LeaderValue.class));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Promise<List<Object>> apply(List<KVCommand<AetherKey>> commands) {
        return Promise.success((List<Object>) (List) store.process(store.createBatch((List) commands)));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void seedLeaderAndRecord(List<NodeId> isr) {
        store.process(store.createBatch(List.of(new KVCommand.Put(LeaderKey.INSTANCE, LEADER),
                                                new KVCommand.Put(key(), record(isr)))));
    }

    private static StreamPartitionOwnershipValue record(List<NodeId> isr) {
        return StreamPartitionOwnershipValue.streamPartitionOwnershipValue(A,
                                                                           GENERATION.withCounter(3L),
                                                                           3L,
                                                                           HlcTimestamp.ZERO,
                                                                           isr,
                                                                           4L);
    }

    private StreamPartitionOwnershipValue committed() {
        return store.getTyped(key(), StreamPartitionOwnershipValue.class).unwrap();
    }

    private StreamPartitionOwnershipValue committedOrNull() {
        return store.getTyped(key(), StreamPartitionOwnershipValue.class).or((StreamPartitionOwnershipValue) null);
    }

    private static StreamPartitionOwnershipKey key() {
        return StreamPartitionOwnershipKey.streamPartitionOwnershipKey(STREAM, PARTITION);
    }

    private static KVStore<AetherKey, AetherValue> store() {
        return new KVStore<>(MessageRouter.mutable(), new Serializer() {
            @Override
            public <T> void write(ByteBuf buffer, T value) {}
        }, new Deserializer() {
            @Override
            public <T> T read(ByteBuf buffer) {
                return null;
            }
        });
    }
}
