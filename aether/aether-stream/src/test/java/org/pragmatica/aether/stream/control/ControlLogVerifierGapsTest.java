// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.control;

import org.pragmatica.aether.stream.forward.AcceptingStreamForwardTransport;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.aether.stream.OwnerLease;
import org.pragmatica.aether.stream.EvictionListener;
import org.pragmatica.aether.stream.control.ControlOutcome.Admitted;
import org.pragmatica.aether.stream.control.ControlOutcome.OutcomeUnknown;
import org.pragmatica.aether.stream.control.ControlOutcome.Refused;
import org.pragmatica.aether.stream.forward.ControlOutcomeDto;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ControlAppendForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ControlAppendForwardResponse;
import org.pragmatica.aether.stream.replication.ReplicaSetController;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.control.ControlLogFixture.*;
import static org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager;


/// Verifier's gap tests for #2590 P1 (v-2603): behaviours of changed production lines that no test of the PR pinned. Each
/// test names the mutation of the production line that turns it red.
class ControlLogVerifierGapsTest {
    private static StreamForwardClient clientDropping(CopyOnWriteArrayList<ControlAppendForward> sent) {
        return StreamForwardClient.streamForwardClient(PEER,
                                                       AcceptingStreamForwardTransport.accepting((_, message) -> {
                                                           if (message instanceof ControlAppendForward batch) {
                                                               sent.add(batch);
                                                           }
                                                       }),
                                                       TimeSpan.timeSpan(300).millis());
    }

    /// A forwarded batch not answered in time may have been appended by the owner: unknown, never a refusal.
    /// Mutation: StreamForwardClient.unknownControlOutcomes builds Refused.
    @Test
    void forwardedBatch_unansweredInTime_isOutcomeUnknownForEveryRecord() {
        var sent = new CopyOnWriteArrayList<ControlAppendForward>();
        var outcomes = await(clientDropping(sent).appendControlRemote(SELF,
                                                                      STREAM,
                                                                      PARTITION,
                                                                      List.of(record("a", 1, 1), record("b", 1, 1))));

        assertThat(sent).as("control: the batch was sent").hasSize(1);
        assertOutcomes(outcomes, OutcomeUnknown.class, OutcomeUnknown.class);
    }

    /// An answer with another number of outcomes than records is unknown for every record.
    /// Mutation: StreamForwardClient.controlOutcomes accepts a larger answer (`>=`).
    @Test
    void forwardedBatch_answeredWithTooManyOutcomes_isOutcomeUnknown() {
        var sent = new CopyOnWriteArrayList<ControlAppendForward>();
        var client = clientDropping(sent);
        var pending = client.appendControlRemote(SELF, STREAM, PARTITION, List.of(record("a", 1, 1)));

        awaitTrue("sent", () -> !sent.isEmpty());
        var admitted = ControlOutcomeDto.fromOutcome(new Admitted(0L));

        client.onControlAppendForwardResponse(ControlAppendForwardResponse.controlAppendForwardResponse(SELF,
                                                                                                         sent.getFirst().correlationId(),
                                                                                                         List.of(admitted, admitted)));

        assertOutcomes(await(pending), OutcomeUnknown.class);
    }

    /// A partition this log holds no state for reclaims nothing. Mutation: DefaultControlLog.deletableThroughOffset
    /// answers Long.MAX_VALUE for it.
    @Test
    void floor_partitionWithoutState_isMinusOne() {
        try (var owner = owner(1, 1)) {
            assertThat(owner.controlLog().deletableThroughOffset("no-such-stream", 7)).isEqualTo(-1L);
        }
    }

    /// With every key tombstoned the floor is the applied offset, not unbounded. Mutation:
    /// PartitionControlState.deletableThroughOffset answers Long.MAX_VALUE for an empty valid index.
    @Test
    void floor_allKeysTombstoned_isTheAppliedOffset() {
        try (var owner = owner(1, 1)) {
            appendReady(owner, record("k", 1, 1));
            var tombstone = ControlRecord.tombstone("k", 2L);

            assertOutcomes(await(owner.controlLog().append(STREAM, PARTITION, List.of(tombstone))), Admitted.class);
            awaitTrue("index valid and empty",
                      () -> latestReady(owner, "k").isEmpty() && owner.controlLog().deletableThroughOffset(STREAM, PARTITION) >= 0);

            assertThat(owner.controlLog().deletableThroughOffset(STREAM, PARTITION)).isEqualTo(1L);
        }
    }

    /// An owner's rebuild indexes only what is committed: a record appended and not yet acknowledged by CF-1 peers stays
    /// out of the index and reads as absent, then appears once the acks arrive. Mutation: StreamPartitionManager.
    /// committedThrough answers the ring head on the owner (instead of its visible position).
    @Test
    void ownerRebuild_unacknowledgedRecord_isNotInTheCommittedIndex() {
        try (var owner = owner(3, 3)) {
            awaitAdmissionReady(owner);
            var pending = owner.controlLog().append(STREAM, PARTITION, List.of(record("k", 1, 1)));

            awaitTrue("appended", () -> ringHead(owner) == 0L);
            owner.ownerEpoch().set(EPOCH_2);
            awaitAdmissionReady(owner);

            assertThat(latestReady(owner, "k").isEmpty()).as("not committed: no peer has acknowledged it").isTrue();

            ack(owner, PEER, 0L);
            ack(owner, PEER2, 0L);
            // #2981 step 2 (design §2.2 check 2): the append was registered under EPOCH_1's ownership and confirmed under EPOCH_2's, so
            // its acknowledgement is unknown (OwnershipMovedBeforeAck); the record itself is committed once the acks are in.
            assertOutcomes(await(pending), OutcomeUnknown.class);
            awaitTrue("committed", () -> latestReady(owner, "k").isPresent());
        }
    }

    /// A rebuild forgets the tail it held: a record the ring truncated must not stay in the admitted view. The owner holds
    /// two unacknowledged records, the ring drops the second, and the same record is then admitted again at its offset.
    /// Mutation: PartitionControlState.installRecords does not clear the tail.
    @Test
    void ownerRebuildAfterTruncation_forgetsTruncatedTailRecord() {
        try (var owner = owner(3, 3)) {
            awaitAdmissionReady(owner);
            owner.controlLog().append(STREAM, PARTITION, List.of(record("a", 1, 1)));
            awaitTrue("a appended", () -> ringHead(owner) == 0L);
            owner.controlLog().append(STREAM, PARTITION, List.of(record("b", 1, 1)));
            awaitTrue("b appended", () -> ringHead(owner) == 1L);

            owner.manager().partitionBuffer(STREAM, PARTITION).unwrap().truncateSuffix(0L).onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()));
            awaitAdmissionReady(owner);
            var again = owner.controlLog().append(STREAM, PARTITION, List.of(record("b", 1, 1)));

            awaitTrue("b appended again at its reused offset", () -> ringHead(owner) == 1L);
            ack(owner, PEER, 1L);
            ack(owner, PEER2, 1L);
            assertOutcomes(await(again), Admitted.class);
        }
    }

    /// The index describes ONE ring: when the partition is materialized again (a destroyed and re-created stream, a new
    /// ring incarnation) the control log must not keep serving the old ring's index. Mutation:
    /// PartitionControlState.indexValid ignores the ring incarnation.
    @Test
    void ringReplaced_oldIndexIsNotServed() {
        try (var owner = owner(1, 1)) {
            appendReady(owner, record("k", 1, 1));
            assertThat(latestReady(owner, "k").map(IndexedRecord::offset)).as("control: indexed on the first ring").isEqualTo(Option.some(0L));

            owner.manager().destroyStream(STREAM).onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()));
            createStream(owner.manager(), 1, 1);

            assertThat(latestReady(owner, "k").isEmpty()).as("the new ring holds no record of k").isTrue();
        }
    }

    /// R4.1 default: a partition with NO committed ownership record has no committed in-sync set, so nothing is admitted.
    /// Mutation: StreamPartitionManager.committedIsrVersion defaults to 1.
    @Test
    void admission_noOwnershipRecord_isIndexUnavailable() {
        try (var owner = owner(1, 1)) {
            owner.manager().ownerLease(OwnerLease.TEST_VALID);
            owner.manager().ownershipRecords((_, _) -> Option.none());

            assertThat(owner.manager().committedIsrVersion(STREAM, PARTITION)).isZero();
            assertOutcomes(await(owner.controlLog().append(STREAM, PARTITION, List.of(record("k", 1, 1)))), ControlOutcome.IndexUnavailable.class);
            assertThat(ringHead(owner)).as("nothing appended").isEqualTo(-1L);
        }
    }

    /// The #1236 replica floor: CF 3 needs two registered peers, only one is registered, so the owner refuses before it
    /// appends. Mutation: StreamPartitionManager.appendControlRun passes a floor of 0.
    @Test
    void append_belowReplicaFloor_isRefusedAndAppendsNothing() {
        var replication = replicationManager(SELF, registry(2));
        var manager = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replication);

        try (var owner = node(manager, replication, MirrorView.EMPTY, 1L, ReplicaSetController.Role.OWNER)) {
            createStream(manager, 3, 3);
            awaitAdmissionReady(owner);

            var outcomes = await(owner.controlLog().append(STREAM, PARTITION, List.of(record("k", 1, 1))));

            assertOutcomes(outcomes, Refused.class);
            assertThat(ringHead(owner)).as("nothing appended").isEqualTo(-1L);
        }
    }

    /// TRIPWIRE (enabled, asserts today's behaviour). An owner restarted with CF 3 recovers its WAL but not its peers'
    /// acknowledgements (nothing about acks is persisted), so its committed position is the seed and an acknowledged
    /// checkpoint reads as ABSENT from `latest` although the owner's own admitted view still holds it. When the substrate
    /// re-learns the acknowledgements after a restart, delete this test and enable the one below.
    @Test
    void ownerRestartCf3_acknowledgedRecord_readsAbsent_tripwire() throws Exception {
        var walDir = Files.createTempDirectory("verifier-wal");

        try (var before = owner(3, 3, MirrorView.EMPTY, Option.some(walDir), 1L)) {
            awaitAdmissionReady(before);
            var pending = before.controlLog().append(STREAM, PARTITION, List.of(record("k1", 1, 1)));

            awaitTrue("appended", () -> ringHead(before) == 0L);
            ack(before, PEER, 0L);
            ack(before, PEER2, 0L);
            assertOutcomes(await(pending), Admitted.class);
        }
        try (var after = owner(3, 3, MirrorView.EMPTY, Option.some(walDir), 1L)) {
            assertThat(ringHead(after)).as("control: the WAL was recovered").isEqualTo(0L);
            assertThat(latestReady(after, "k1").isEmpty()).as("#2590 tripwire: an acknowledged record is readable after an owner restart; "
                                                              + "enable ownerRestartCf3_acknowledgedRecord_staysReadable and delete this tripwire").isTrue();
        }
    }

    @Disabled("#2590: the substrate re-learns peer acknowledgements after an owner restart (needed by S2 'any single node crash')")
    @Test
    void ownerRestartCf3_acknowledgedRecord_staysReadable() throws Exception {
        var walDir = Files.createTempDirectory("verifier-wal");

        try (var before = owner(3, 3, MirrorView.EMPTY, Option.some(walDir), 1L)) {
            awaitAdmissionReady(before);
            var pending = before.controlLog().append(STREAM, PARTITION, List.of(record("k1", 1, 1)));

            awaitTrue("appended", () -> ringHead(before) == 0L);
            ack(before, PEER, 0L);
            ack(before, PEER2, 0L);
            assertOutcomes(await(pending), Admitted.class);
        }
        try (var after = owner(3, 3, MirrorView.EMPTY, Option.some(walDir), 1L)) {
            assertThat(latestReady(after, "k1").map(IndexedRecord::offset)).isEqualTo(Option.some(0L));
        }
    }
}
