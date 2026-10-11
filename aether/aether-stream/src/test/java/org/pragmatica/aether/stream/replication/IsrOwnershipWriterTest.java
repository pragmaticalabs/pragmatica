// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.List;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1730: the leader's ISR-aware ownership writer. Failover elects only from the live committed ISR, never a node that
/// may lack acknowledged records; with no live ISR member it elects nobody (unclean failover is off). Every write is a
/// guarded transaction, so a decision taken on a record that has since changed is refused by the applier rather than
/// overwriting the change.
class IsrOwnershipWriterTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId A = new NodeId("node-a");
    private static final NodeId B = new NodeId("node-b");
    private static final NodeId C = new NodeId("node-c");
    private static final NodeId D = new NodeId("node-d");
    private static final Epoch GENERATION = Epoch.epoch(1L, 2L, 0L);
    private static final LeaderValue LEADER = new LeaderValue(A, 1L);

    private final IsrOwnershipWriter writer = writer(List.of(A, B, C, D), (_, _, owner) -> List.of(owner, B, C));

    @Nested
    class FirstRecord {
        @Test
        void next_noRecord_mintsTheIsrFromThePlacement() {
            var record = writer.next(STREAM, PARTITION, Option.none(), A, GENERATION, List.of(A, B, C)).unwrap();

            assertThat(record.owner()).isEqualTo(A);
            assertThat(record.isr()).containsExactly(A, B, C);
            assertThat(record.isrVersion()).isEqualTo(1L);
        }
    }

    @Nested
    class OwnerUnchanged {
        @Test
        void next_isrMemberLeftTheLiveSet_isDropped_ownershipKept() {
            var current = record(A, 3L, List.of(A, B, C), 4L);
            var next = writer.next(STREAM, PARTITION, Option.some(current), A, GENERATION, List.of(A, B)).unwrap();

            assertThat(next.isr()).containsExactly(A, B);
            assertThat(next.isrVersion()).isEqualTo(5L);
            assertThat(next.ownershipTerm()).isEqualTo(3L);
            assertThat(next.ownerEpoch()).isEqualTo(current.ownerEpoch());
        }

        @Test
        void next_everyIsrMemberLive_writesNothing() {
            var current = record(A, 3L, List.of(A, B), 4L);

            assertThat(writer.next(STREAM, PARTITION, Option.some(current), A, GENERATION, List.of(A, B, C)).isEmpty()).isTrue();
        }
    }

    @Nested
    class Failover {
        @Test
        void next_ownerGone_desiredIsALiveIsrMember_movesToIt() {
            var current = record(A, 3L, List.of(A, B, C), 4L);
            var next = writer.next(STREAM, PARTITION, Option.some(current), B, GENERATION, List.of(B, C, D)).unwrap();

            assertThat(next.owner()).isEqualTo(B);
            assertThat(next.isr()).containsExactly(B, C);
            assertThat(next.ownershipTerm()).isEqualTo(4L);
            assertThat(next.ownerEpoch().isStrictlyAfter(current.ownerEpoch())).isTrue();
        }

        /// The election rule itself: D is live and is the desired (HRW) owner, but it is not in the ISR, so it may
        /// lack acknowledged records. A live ISR member is elected instead.
        @Test
        void next_ownerGone_desiredOutsideTheIsr_electsALiveIsrMemberInstead() {
            var current = record(A, 3L, List.of(A, C), 4L);
            var next = writer.next(STREAM, PARTITION, Option.some(current), D, GENERATION, List.of(B, C, D)).unwrap();

            assertThat(next.owner()).isEqualTo(C);
            assertThat(next.isr()).containsExactly(C);
        }

        /// Unclean failover is off: with no ISR member live nobody is elected, however many other nodes are live. The
        /// refusal itself is committed once (owner ruling: it is an announced transition), changing nothing else.
        @Test
        void next_ownerGone_noIsrMemberLive_electsNobody_andCommitsTheRefusalOnce() {
            var current = record(A, 3L, List.of(A, B), 4L);
            var refused = writer.next(STREAM, PARTITION, Option.some(current), D, GENERATION, List.of(C, D)).unwrap();

            assertThat(refused).isEqualTo(current.withFailoverRefused(true));
            assertThat(writer.next(STREAM, PARTITION, Option.some(refused), D, GENERATION, List.of(C, D)).isEmpty())
                .as("a still-refused partition is not rewritten")
                .isTrue();
        }

        @Test
        void next_refusedPartition_anIsrMemberReturns_isElected_andTheRefusalClears() {
            var refused = record(A, 3L, List.of(A, B), 4L).withFailoverRefused(true);
            var next = writer.next(STREAM, PARTITION, Option.some(refused), B, GENERATION, List.of(B, C)).unwrap();

            assertThat(next.owner()).isEqualTo(B);
            assertThat(next.failoverRefused()).isFalse();
        }

        @Test
        void next_refusedPartition_theOwnerReturns_keepsOwnership_andTheRefusalClears() {
            var refused = record(A, 3L, List.of(A, B), 4L).withFailoverRefused(true);
            var next = writer.next(STREAM, PARTITION, Option.some(refused), A, GENERATION, List.of(A, B)).unwrap();

            assertThat(next).isEqualTo(refused.withFailoverRefused(false));
        }
    }

    @Nested
    class PlannedMove {
        /// The committed owner is still live (an entity hosting change): the new owner leads, the live ISR stays, and
        /// the new owner's activation catches up from those live holders before it serves.
        @Test
        void next_ownerLive_desiredElsewhere_movesAndKeepsTheLiveIsr() {
            var current = record(A, 3L, List.of(A, B), 4L);
            var next = writer.next(STREAM, PARTITION, Option.some(current), D, GENERATION, List.of(A, B, D)).unwrap();

            assertThat(next.owner()).isEqualTo(D);
            assertThat(next.isr()).containsExactly(D, A, B);
        }
    }

    @Nested
    class Guarded {
        /// The CAS: the owner commits an ISR change first; the leader's write decided on the older record is then
        /// refused by the real applier, and the owner's change stands.
        /// The leader's own write is guarded the same way.
        @Test
        void decide_emitsAGuardedTransactionExpectingTheCommittedRecord() {
            var current = record(A, 3L, List.of(A, B, C), 4L);
            var command = writer(List.of(A, B), (_, _, owner) -> List.of(owner)).decide(STREAM,
                                                                                        PARTITION,
                                                                                        Option.some(current),
                                                                                        A,
                                                                                        GENERATION)
                                                                                .unwrap();

            assertThat(command).isInstanceOfSatisfying(KVCommand.LeaderTransaction.class,
                                                       transaction -> assertThat(((KVCommand.Mutation<?, ?>) transaction.mutations()
                                                                                                                    .getFirst()).expected()).isEqualTo(Option.some(current)));
        }

        @Test
        void decide_onARecordThatMoved_isRefusedByTheApplier() {
            var store = store();
            var initial = record(A, 3L, List.of(A, B, C), 4L);

            seed(store, new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER));
            seed(store, IsrOwnershipWriter.guarded(LEADER, STREAM, PARTITION, Option.none(), initial));
            assertThat(committed(store)).as("fixture control: the guarded first write applies").isEqualTo(Option.some(initial));

            var ownersShrink = initial.withIsr(List.of(A, B));

            seed(store, IsrOwnershipWriter.guarded(LEADER, STREAM, PARTITION, Option.some(initial), ownersShrink));
            assertThat(committed(store)).isEqualTo(Option.some(ownersShrink));

            var reExpanded = initial.withIsr(List.of(A, B, C, D));

            seed(store, IsrOwnershipWriter.guarded(LEADER, STREAM, PARTITION, Option.some(initial), reExpanded));
            assertThat(committed(store)).as("a write decided on the superseded record does not overwrite the shrink")
                                        .isEqualTo(Option.some(ownersShrink));
        }

        /// A superseded owner's ISR proposal fails its CAS: A, deposed by a failover to B, still proposes an ISR change
        /// against the record it last held. The applier refuses it, and B's ownership and ISR stand.
        @Test
        void supersededOwner_isrProposalOnItsOldRecord_isRefusedByTheApplier() {
            var store = store();
            var heldByA = record(A, 3L, List.of(A, B, C), 4L);
            var failedOverToB = writer.next(STREAM, PARTITION, Option.some(heldByA), B, GENERATION, List.of(B, C, D)).unwrap();

            seed(store, new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER));
            seed(store, IsrOwnershipWriter.guarded(LEADER, STREAM, PARTITION, Option.none(), heldByA));
            seed(store, IsrOwnershipWriter.guarded(LEADER, STREAM, PARTITION, Option.some(heldByA), failedOverToB));
            assertThat(committed(store)).as("fixture control: the failover committed").isEqualTo(Option.some(failedOverToB));

            seed(store, IsrOwnershipWriter.guarded(LEADER, STREAM, PARTITION, Option.some(heldByA), heldByA.withIsr(List.of(A))));

            assertThat(committed(store)).as("the deposed owner's shrink to itself is refused").isEqualTo(Option.some(failedOverToB));
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void seed(KVStore<AetherKey, AetherValue> store, KVCommand command) {
        store.process(store.createBatch(List.of(command)));
    }

    /// #1883: the leader records the members it removes for liveness, in the same commit, so the owner and the leader
    /// read ONE liveness input.
    @Nested
    class Fenced {
        @Test
        void next_isrMemberDropped_isFencedInTheSameCommit() {
            var current = record(A, 3L, List.of(A, B, C), 4L);
            var next = writer.next(STREAM, PARTITION, Option.some(current), A, GENERATION, List.of(A, B)).unwrap();

            assertThat(next.isr()).containsExactly(A, B);
            assertThat(next.fenced()).containsExactly(C);
            assertThat(next.isrVersion()).as("one commit, one version step").isEqualTo(5L);
        }

        @Test
        void next_settledFencedRecord_writesNothing() {
            var current = fencedRecord(A, List.of(A, B), List.of(C), 5L);

            assertThat(writer.next(STREAM, PARTITION, Option.some(current), A, GENERATION, List.of(A, B)).isEmpty())
                .as("a reconcile of a settled record must not commit (it would re-fire every node's reconcile)")
                .isTrue();
        }

        @Test
        void next_fencedMemberListedLiveAgain_isUnfenced_notReadmitted() {
            var current = fencedRecord(A, List.of(A, B), List.of(C), 5L);
            var next = writer.next(STREAM, PARTITION, Option.some(current), A, GENERATION, List.of(A, B, C)).unwrap();

            assertThat(next.fenced()).isEmpty();
            assertThat(next.isr()).as("expansion is the owner's, on its own evidence").containsExactly(A, B);
            assertThat(next.isrVersion()).isEqualTo(6L);
        }

        @Test
        void next_deadOwnerElectedAway_isFenced() {
            var current = record(A, 3L, List.of(A, B, C), 4L);
            var next = writer.next(STREAM, PARTITION, Option.some(current), A, GENERATION, List.of(B, C)).unwrap();

            assertThat(next.owner()).isIn(B, C);
            assertThat(next.fenced()).containsExactly(A);
        }

        /// #1873: a failover begins a new epoch but keeps the earlier epochs' starts, so a consumer asleep across it is still
        /// checked against the boundary it crossed.
        @Test
        void next_failover_keepsTheEpochStartsOfTheEarlierEpochs() {
            var started = new org.pragmatica.aether.slice.kvstore.AetherValue.EpochStart(GENERATION.withCounter(3L), 7L);
            var current = record(A, 3L, List.of(A, B, C), 4L).withEpochStart(7L);
            var next = writer.next(STREAM, PARTITION, Option.some(current), A, GENERATION, List.of(B, C)).unwrap();

            assertThat(next.owner()).isIn(B, C);
            assertThat(next.epochStarts()).as("the new epoch has no start until its owner commits it").containsExactly(started);
        }

        @Test
        void next_fencedSetIsBounded_newestKept_andSettlesThere() {
            var stale = java.util.stream.IntStream.range(0, StreamPartitionOwnershipValue.FENCED_MAX)
                                                  .mapToObj(i -> new NodeId("gone-" + i))
                                                  .toList();
            var newest = new NodeId("gone-newest");
            var current = fencedRecord(A, List.of(A, newest), stale, 5L);
            var next = writer.next(STREAM, PARTITION, Option.some(current), A, GENERATION, List.of(A)).unwrap();

            assertThat(next.fenced()).hasSize(StreamPartitionOwnershipValue.FENCED_MAX)
                                     .doesNotContain(stale.getFirst())
                                     .endsWith(newest);
            assertThat(writer.next(STREAM, PARTITION, Option.some(next), A, GENERATION, List.of(A)).isEmpty())
                .as("at the cap the record is settled, not rewritten on every reconcile")
                .isTrue();
        }
    }

    private static Option<StreamPartitionOwnershipValue> committed(KVStore<AetherKey, AetherValue> store) {
        return store.getTyped(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(STREAM, PARTITION),
                              StreamPartitionOwnershipValue.class);
    }

    private static StreamPartitionOwnershipValue record(NodeId owner, long term, List<NodeId> isr, long isrVersion) {
        return StreamPartitionOwnershipValue.streamPartitionOwnershipValue(owner,
                                                                           GENERATION.withCounter(term),
                                                                           term,
                                                                           HlcTimestamp.ZERO,
                                                                           isr,
                                                                           isrVersion);
    }

    private static StreamPartitionOwnershipValue fencedRecord(NodeId owner, List<NodeId> isr, List<NodeId> fenced, long isrVersion) {
        return StreamPartitionOwnershipValue.streamPartitionOwnershipValue(owner,
                                                                           GENERATION.withCounter(3L),
                                                                           3L,
                                                                           HlcTimestamp.ZERO,
                                                                           isr,
                                                                           isrVersion,
                                                                           fenced);
    }

    private static IsrOwnershipWriter writer(List<NodeId> live, InitialIsr initialIsr) {
        return new IsrOwnershipWriter(() -> true,
                                      () -> GENERATION,
                                      HlcClock.hlcClock(A),
                                      (_, _) -> Option.none(),
                                      (_, _) -> Option.none(),
                                      new StreamPartitionOwnershipWriter.IsrInputs() {
                                          @Override
                                          public List<NodeId> liveMembers() {
                                              return live;
                                          }

                                          @Override
                                          public List<NodeId> initialIsr(String stream, int partition, NodeId owner) {
                                              return initialIsr.of(stream, partition, owner);
                                          }
                                      },
                                      () -> Option.some(LEADER));
    }

    @FunctionalInterface
    private interface InitialIsr {
        List<NodeId> of(String stream, int partition, NodeId owner);
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
