// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.replication.StreamPartitionOwnershipWriter.CommittedOwnership;
import org.pragmatica.aether.stream.replication.StreamPartitionOwnershipWriter.HrwOwner;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class StreamPartitionOwnershipWriterTest {
    private static final NodeId OWNER_A = NodeId.nodeId("core-a").unwrap();
    private static final NodeId OWNER_B = NodeId.nodeId("core-b").unwrap();
    private static final String STREAM = "orders";
    private static final int PARTITION = 3;
    private static final long COMMITTED_TERM = 9L;

    private static final BooleanSupplier LEADER = () -> true;
    private static final BooleanSupplier FOLLOWER = () -> false;
    private static final Supplier<Long> RABIA_TERM = () -> COMMITTED_TERM;
    private static final HlcClock CLOCK = HlcClock.hlcClock(new NodeId("core-a"));

    private static StreamPartitionOwnershipValue ownership(NodeId owner, Epoch epoch, long ownershipTerm) {
        return StreamPartitionOwnershipValue.streamPartitionOwnershipValue(owner, epoch, ownershipTerm, HlcTimestamp.ZERO);
    }

    private static CommittedOwnership committed(Option<StreamPartitionOwnershipValue> value) {
        return (stream, partition) -> value;
    }

    private static HrwOwner hrw(Option<NodeId> owner) {
        return (stream, partition) -> owner;
    }

    private static StreamPartitionOwnershipWriter writer(BooleanSupplier isLeader,
                                                         CommittedOwnership committedOwnership,
                                                         HrwOwner hrwOwner) {
        return StreamPartitionOwnershipWriter.streamPartitionOwnershipWriter(isLeader,
                                                                             RABIA_TERM,
                                                                             CLOCK,
                                                                             committedOwnership,
                                                                             hrwOwner);
    }

    private static StreamPartitionOwnershipValue valueOf(KVCommand<AetherKey> command) {
        return (StreamPartitionOwnershipValue) ((KVCommand.Put<AetherKey, ?>) command).value();
    }

    private static StreamPartitionOwnershipKey keyOf(KVCommand<AetherKey> command) {
        return (StreamPartitionOwnershipKey) command.key();
    }

    /// The fence-relevant projection of an ownership value: owner + ownerEpoch + ownershipTerm. This is
    /// the part that must be a pure function of committed state for replicas to converge; the advisory
    /// `transferredAt` HLC stamp is node-local by construction and is excluded.
    private record FenceFields(NodeId owner, Epoch ownerEpoch, long ownershipTerm) {}

    private static FenceFields fenceFields(KVCommand<AetherKey> command) {
        var value = valueOf(command);

        return new FenceFields(value.owner(), value.ownerEpoch(), value.ownershipTerm());
    }

    private static KVCommand<AetherKey> require(Option<KVCommand<AetherKey>> command) {
        return command.fold(() -> Assertions.fail("expected a Put command"), Fn1.id());
    }

    @Nested
    class Decide {
        private final StreamPartitionOwnershipWriter writer = writer(LEADER,
                                                                     committed(Option.none()),
                                                                     hrw(Option.none()));

        @Test
        void decide_noCommittedRecord_emitsInitialPutWithTermOne() {
            var command = require(writer.decide(STREAM,
                                                PARTITION,
                                                Option.none(),
                                                OWNER_A,
                                                Epoch.epoch(COMMITTED_TERM, 0)));

            assertThat(keyOf(command)).isEqualTo(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(STREAM,
                                                                                                         PARTITION));
            assertThat(valueOf(command).owner()).isEqualTo(OWNER_A);
            assertThat(valueOf(command).ownerEpoch())
                .as("ownerEpoch couples the committed generation term with the initial ownershipTerm 1 as its local counter")
                .isEqualTo(Epoch.epoch(COMMITTED_TERM, 1));
            assertThat(valueOf(command).ownershipTerm())
                .as("a first-ever ownership record starts at ownershipTerm 1")
                .isEqualTo(1L);
        }

        @Test
        void decide_ownerUnchanged_isNoOp() {
            var current = ownership(OWNER_A, Epoch.epoch(COMMITTED_TERM, 1), 1L);

            assertThat(writer.decide(STREAM, PARTITION, Option.some(current), OWNER_A, Epoch.epoch(COMMITTED_TERM, 0)))
                .as("HRW owner equals committed owner — no consensus write")
                .isEqualTo(Option.none());
        }

        @Test
        void decide_ownerChanged_emitsPutWithAdvancedEpochAndBumpedTerm() {
            var current = ownership(OWNER_A, Epoch.epoch(COMMITTED_TERM, 4), 4L);

            var command = require(writer.decide(STREAM,
                                                PARTITION,
                                                Option.some(current),
                                                OWNER_B,
                                                Epoch.epoch(COMMITTED_TERM, 0)));

            assertThat(valueOf(command).owner())
                .as("the new owner is the HRW owner")
                .isEqualTo(OWNER_B);
            assertThat(valueOf(command).ownerEpoch())
                .as("ownerEpoch advances to (committedTerm, bumped ownershipTerm) — the local counter is the takeover counter")
                .isEqualTo(Epoch.epoch(COMMITTED_TERM, 5));
            assertThat(valueOf(command).ownerEpoch().isStrictlyAfter(current.ownerEpoch()))
                .as("the successor's epoch STRICTLY dominates the deposed owner's committed epoch — even at the SAME generation "
                    + "term (the same-term HRW reshuffle gap is closed by the ownershipTerm local counter)")
                .isTrue();
            assertThat(valueOf(command).ownershipTerm())
                .as("ownershipTerm is bumped by one on owner change")
                .isEqualTo(5L);
        }

        @Test
        void decide_ownerChanged_sameTermReshuffle_stillAdvancesEpoch() {
            // Same committed generation term as the writer's rabiaTerm (COMMITTED_TERM): a node-join HRW
            // reshuffle with NO leader re-election. The epoch must still advance via the ownershipTerm
            // local counter, so the deposed-but-alive owner is fenced.
            var current = ownership(OWNER_A, Epoch.epoch(COMMITTED_TERM, 1), 1L);

            var command = require(writer.decide(STREAM,
                                                PARTITION,
                                                Option.some(current),
                                                OWNER_B,
                                                Epoch.epoch(COMMITTED_TERM, 0)));

            assertThat(valueOf(command).ownerEpoch())
                .as("same-term reshuffle still advances the epoch via the ownershipTerm local counter")
                .isEqualTo(Epoch.epoch(COMMITTED_TERM, 2));
            assertThat(valueOf(command).ownerEpoch().isStrictlyAfter(current.ownerEpoch()))
                .as("(term, 2) strictly dominates the deposed owner's (term, 1) at the SAME term — fence holds")
                .isTrue();
        }

        @Test
        void decide_sameCommittedState_isDeterministicOnFenceFields() {
            var current = ownership(OWNER_A, Epoch.epoch(COMMITTED_TERM, 4), 4L);

            var first = writer.decide(STREAM, PARTITION, Option.some(current), OWNER_B, Epoch.epoch(COMMITTED_TERM, 0));
            var second = writer.decide(STREAM, PARTITION, Option.some(current), OWNER_B, Epoch.epoch(COMMITTED_TERM, 0));

            // The fence-relevant projection (owner + ownerEpoch + ownershipTerm) is a pure function of
            // committed state, so two replicas presented the same committed state reach the IDENTICAL
            // decision and write IDENTICAL fence fields — that is what makes them converge. The advisory
            // `transferredAt` HLC stamp is deliberately EXCLUDED: it embeds the writing node's own
            // `NodeId` and advances a per-call logical counter, so it is node-local by construction and
            // is NOT part of the fence (`fenceEpoch()` reads `ownerEpoch` only). Asserting full-value
            // equality would test a property the design intentionally does not hold.
            assertThat(first.map(StreamPartitionOwnershipWriterTest::fenceFields))
                .as("the decision and the fence fields are a pure function of committed state — replicas converge")
                .isEqualTo(second.map(StreamPartitionOwnershipWriterTest::fenceFields));
        }
    }

    @Nested
    class WriteOwnershipChange {
        @Test
        void writeOwnershipChange_follower_emitsNothing() {
            var writer = writer(FOLLOWER,
                                committed(Option.none()),
                                hrw(Option.some(OWNER_A)));

            assertThat(writer.writeOwnershipChange(STREAM, PARTITION))
                .as("only the leader writes ownership — a follower never emits")
                .isEqualTo(Option.none());
        }

        @Test
        void writeOwnershipChange_leaderNoRecord_emitsInitialPut() {
            var writer = writer(LEADER,
                                committed(Option.none()),
                                hrw(Option.some(OWNER_A)));

            var command = require(writer.writeOwnershipChange(STREAM, PARTITION));

            assertThat(valueOf(command).owner()).isEqualTo(OWNER_A);
            assertThat(valueOf(command).ownerEpoch()).isEqualTo(Epoch.epoch(COMMITTED_TERM, 1));
            assertThat(valueOf(command).ownershipTerm()).isEqualTo(1L);
        }

        @Test
        void writeOwnershipChange_leaderOwnerUnchanged_emitsNothing() {
            var current = ownership(OWNER_A, Epoch.epoch(COMMITTED_TERM, 1), 1L);
            var writer = writer(LEADER,
                                committed(Option.some(current)),
                                hrw(Option.some(OWNER_A)));

            assertThat(writer.writeOwnershipChange(STREAM, PARTITION))
                .as("leader with an unchanged HRW owner does not re-write — idempotent")
                .isEqualTo(Option.none());
        }

        @Test
        void writeOwnershipChange_leaderOwnerChanged_emitsTakeoverPut() {
            var current = ownership(OWNER_A, Epoch.epoch(COMMITTED_TERM, 2), 2L);
            var writer = writer(LEADER,
                                committed(Option.some(current)),
                                hrw(Option.some(OWNER_B)));

            var command = require(writer.writeOwnershipChange(STREAM, PARTITION));

            assertThat(valueOf(command).owner()).isEqualTo(OWNER_B);
            assertThat(valueOf(command).ownerEpoch()).isEqualTo(Epoch.epoch(COMMITTED_TERM, 3));
            assertThat(valueOf(command).ownerEpoch().isStrictlyAfter(current.ownerEpoch()))
                .as("the takeover epoch strictly dominates the deposed owner's committed epoch")
                .isTrue();
            assertThat(valueOf(command).ownershipTerm()).isEqualTo(3L);
        }

        @Test
        void writeOwnershipChange_leaderNoPlacement_emitsNothing() {
            var writer = writer(LEADER,
                                committed(Option.none()),
                                hrw(Option.none()));

            assertThat(writer.writeOwnershipChange(STREAM, PARTITION))
                .as("no HRW placement (empty member view) — nothing to write")
                .isEqualTo(Option.none());
        }
    }

    /// #265 increment 6: the batch decide — [StreamPartitionOwnershipWriter#writeOwnershipChanges] decides a
    /// WHOLE reconcile pass at once and returns the moved partitions' Puts as ONE list (which the driver
    /// applies as a single consensus batch). A follower and unchanged partitions contribute nothing.
    @Nested
    class WriteOwnershipChanges {
        private static final Epoch COMMITTED_EPOCH = Epoch.epoch(COMMITTED_TERM, 1L);
        private static final Epoch TAKEOVER_EPOCH = Epoch.epoch(COMMITTED_TERM, 2L);

        /// Committed = OWNER_A at (term, 1) for EVERY partition, so any partition whose HRW owner is OWNER_B
        /// is a genuine move and any partition whose HRW owner is OWNER_A is unchanged.
        private static CommittedOwnership committedAllA() {
            return (stream, partition) -> Option.some(ownership(OWNER_A, COMMITTED_EPOCH, 1L));
        }

        private static HrwOwner hrwByPartition(Map<Integer, NodeId> owners) {
            return (stream, partition) -> Option.option(owners.get(partition));
        }

        private static List<PartitionKey> keys(int... partitions) {
            return IntStream.of(partitions)
                            .mapToObj(partition -> PartitionKey.partitionKey(STREAM, partition))
                            .toList();
        }

        private static void assertTakeoverToB(KVCommand<AetherKey> command) {
            var value = valueOf(command);

            assertThat(value.owner()).isEqualTo(OWNER_B);
            assertThat(value.ownerEpoch())
                .as("a moved partition advances the epoch to (committedTerm, bumped ownershipTerm)")
                .isEqualTo(TAKEOVER_EPOCH);
            assertThat(value.ownershipTerm()).isEqualTo(2L);
        }

        @Test
        void writeOwnershipChanges_allMoved_returnsOnePutPerPartitionWithAdvancedEpoch() {
            var writer = writer(LEADER,
                                committedAllA(),
                                hrwByPartition(Map.of(0, OWNER_B, 1, OWNER_B, 2, OWNER_B)));

            var commands = writer.writeOwnershipChanges(keys(0, 1, 2));

            assertThat(commands)
                .as("one consensus Put per genuinely-moved partition")
                .hasSize(3)
                .allSatisfy(WriteOwnershipChanges::assertTakeoverToB);
            assertThat(commands.stream().map(WriteOwnershipChanges::partitionOf).toList())
                .as("the batch holds exactly the three moved partitions")
                .containsExactlyInAnyOrder(0, 1, 2);
        }

        @Test
        void writeOwnershipChanges_unchangedPartitions_contributeNothing() {
            // Partitions 0,1,2 move A→B; partitions 3,4 keep committed OWNER_A (HRW == committed) so their
            // decide is a no-op and they must not appear in the batch.
            var writer = writer(LEADER,
                                committedAllA(),
                                hrwByPartition(Map.of(0, OWNER_B, 1, OWNER_B, 2, OWNER_B, 3, OWNER_A, 4, OWNER_A)));

            var commands = writer.writeOwnershipChanges(keys(0, 1, 2, 3, 4));

            assertThat(commands)
                .as("only the three moved partitions contribute; the two unchanged ones add nothing")
                .hasSize(3);
            assertThat(commands.stream().map(WriteOwnershipChanges::partitionOf).toList())
                .containsExactlyInAnyOrder(0, 1, 2);
        }

        @Test
        void writeOwnershipChanges_follower_returnsEmptyBatch() {
            var writer = writer(FOLLOWER,
                                committedAllA(),
                                hrwByPartition(Map.of(0, OWNER_B, 1, OWNER_B, 2, OWNER_B)));

            assertThat(writer.writeOwnershipChanges(keys(0, 1, 2)))
                .as("a follower emits no ownership writes — every per-pair decide short-circuits on the leader gate")
                .isEmpty();
        }

        @Test
        void writeOwnershipChanges_emptyInput_returnsEmptyBatch() {
            var writer = writer(LEADER,
                                committedAllA(),
                                hrwByPartition(Map.of(0, OWNER_B)));

            assertThat(writer.writeOwnershipChanges(List.of()))
                .as("an empty reconcile pass yields an empty batch — the driver applies nothing")
                .isEmpty();
        }

        private static int partitionOf(KVCommand<AetherKey> command) {
            return keyOf(command).partition();
        }
    }
}
