// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.replication.AcceptingReplicationTransport;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.aether.stream.replication.ReplicationReceiveHandler;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #2981 direction B: an owner acknowledges a `confirmation_factor` 2 append only while the COMMITTED ownership record, read
/// after a consensus round, still carries the ownership the append was admitted under. Two real [StreamPartitionManager]
/// rings joined by real replication: the owner `X` replicates to `R` (its in-sync peer), and `R`'s acks come back to `X`.
///
/// Ownership state is modelled as two cells: `localView` is what `X` has APPLIED, `committed` is what consensus has
/// committed. The [LinearizableBarrier] stand-in applies `committed` into `localView`, which is what a Rabia no-op round
/// guarantees (every change committed before the round has applied here once it applied). The round start runs on a manual
/// executor, so each test decides exactly what is committed when the round starts.
class AckTimeOwnershipCheckTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId X = new NodeId("x");
    private static final NodeId Y = new NodeId("y");
    private static final NodeId R = new NodeId("r");
    private static final TimeSpan ACK_BOUND = TimeSpan.timeSpan(3).seconds();
    private static final StreamPartitionOwnershipValue X_AT_2 = record(X, 2);
    private static final StreamPartitionOwnershipValue Y_AT_3 = record(Y, 3);
    private static final StreamPartitionOwnershipValue X_AT_4 = record(X, 4);

    private final AtomicReference<ReplicationManager> ownerReplication = new AtomicReference<>();
    private final AtomicReference<Option<StreamPartitionOwnershipValue>> localView = new AtomicReference<>(Option.some(X_AT_2));
    private final AtomicReference<Option<StreamPartitionOwnershipValue>> committed = new AtomicReference<>(Option.some(X_AT_2));
    private final AtomicInteger barrierRounds = new AtomicInteger();
    private final List<Runnable> queuedRoundStarts = new ArrayList<>();
    private final AtomicReference<Option<Promise<Unit>>> heldRound = new AtomicReference<>(Option.none());
    private StreamPartitionManager owner;
    private StreamPartitionManager replica;

    @BeforeEach
    void setUp() {
        replica = streamPartitionManager(Long.MAX_VALUE);
        var receiver = ReplicationReceiveHandler.replicationReceiveHandler(R,
                                                                           replica::appendRecovered,
                                                                           replica::nextExpectedOffset,
                                                                           AcceptingReplicationTransport.accepting(this::toOwner),
                                                                           (_, _) -> {});
        var registry = ReplicaRegistry.replicaRegistry();

        registry.registerReplica(STREAM, PARTITION, X);
        registry.registerReplica(STREAM, PARTITION, R);
        ownerReplication.set(ReplicationManager.replicationManager(X,
                                                                   registry,
                                                                   AcceptingReplicationTransport.accepting((_, message) -> deliver(receiver,
                                                                                                                                   message))));
        owner = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, ownerReplication.get());
        assertThat(owner.createStream(config()).isSuccess()).isTrue();
        assertThat(replica.createStream(config()).isSuccess()).isTrue();
        owner.ownershipRecords((_, _) -> localView.get());
        owner.ackOwnershipCheck(AckOwnershipCheck.batched(X,
                                                          (_, _) -> localView.get(),
                                                          this::round,
                                                          queuedRoundStarts::add));
    }

    @AfterEach
    void tearDown() {
        owner.close();
        replica.close();
    }

    /// The N2 interleaving at the ack site: the move to `Y` is committed, but `X` has not applied it, so `X` admits the
    /// append from its stale view and its old in-sync peer `R` confirms it. Before #2981 B this acknowledged "ok" while `Y`
    /// (already activated, its probe a snapshot) never held the record.
    @Test
    void awaitReplication_deposedOwnerAckedByOldIsr_isOutcomeUnknownNotOk() {
        committed.set(Option.some(Y_AT_3));

        var ack = publishAndAwait("late");

        startQueuedRounds();
        var outcome = ack.timeout(ACK_BOUND)
                         .await();

        assertThat(replica.appendedHead(STREAM, PARTITION)).as("the old in-sync peer did confirm the append")
                                                           .isEqualTo(0L);
        assertThat(outcome.isFailure()).as("a deposed owner must not acknowledge (%s)", outcome)
                                       .isTrue();
        assertThat(causeOf(outcome)).isInstanceOf(StreamError.OwnershipMovedBeforeAck.class);
        assertThat(barrierRounds.get()).isEqualTo(1);
    }

    @Test
    void awaitReplication_ownershipUnchanged_acknowledges() {
        var ack = publishAndAwait("steady");

        startQueuedRounds();
        var outcome = ack.timeout(ACK_BOUND)
                         .await();

        assertThat(outcome.isSuccess()).as("a current owner's acknowledgement completes (%s)", outcome)
                                       .isTrue();
        assertThat(barrierRounds.get()).isEqualTo(1);
    }

    /// The owner was current when it admitted the append and when `R` confirmed it; the move commits after that and before
    /// the round. The round sees it, so the acknowledgement loses the race.
    @Test
    void awaitReplication_moveCommitsBetweenAckAndRound_isOutcomeUnknown() {
        var ack = publishAndAwait("raced");

        committed.set(Option.some(Y_AT_3));
        startQueuedRounds();
        var outcome = ack.timeout(ACK_BOUND)
                         .await();

        assertThat(causeOf(outcome)).as("%s", outcome)
                                    .isInstanceOf(StreamError.OwnershipMovedBeforeAck.class);
    }

    /// Owner unchanged but a later tenure (moved away and back, or a ring restart): the epoch and term differ, so the
    /// acknowledgement of an append admitted under the earlier tenure does not complete.
    @Test
    void awaitReplication_sameOwnerLaterEpoch_isOutcomeUnknown() {
        committed.set(Option.some(X_AT_4));

        var ack = publishAndAwait("tenure");

        startQueuedRounds();
        var outcome = ack.timeout(ACK_BOUND)
                         .await();

        assertThat(causeOf(outcome)).as("%s", outcome)
                                    .isInstanceOf(StreamError.OwnershipMovedBeforeAck.class);
    }

    @Test
    void awaitReplication_roundFails_isOutcomeUnknownWithTheRoundCause() {
        var ack = publishAndAwait("timeout");

        heldRound.set(Option.some(new StreamError.LinearizableRoundTimeout(STREAM, PARTITION).promise()));
        startQueuedRounds();
        var outcome = ack.timeout(ACK_BOUND)
                         .await();

        assertThat(causeOf(outcome)).isInstanceOf(StreamError.LinearizableRoundTimeout.class);
    }

    /// Group commit: N acknowledgements resolved before the round starts share ONE round and one record read.
    @Test
    void awaitReplication_batchOfResolvedAcks_usesOneRound() {
        var acks = new ArrayList<Promise<Unit>>();

        for (var i = 0; i < 8; i++) {
            acks.add(publishAndAwait("batch-" + i));
        }
        startQueuedRounds();

        assertThat(Promise.allOf(acks)
                          .timeout(ACK_BOUND)
                          .await()
                          .isSuccess()).isTrue();
        assertThat(acks).allMatch(Promise::isResolved);
        assertThat(acks.stream()
                       .map(Promise::await)
                       .allMatch(Result::isSuccess)).isTrue();
        assertThat(barrierRounds.get()).as("one barrier round for the batch").isEqualTo(1);
    }

    /// A waiter whose acks resolve while a round is in flight never joins that round (it may be ordered before the move it
    /// must see): it waits for the next one, which starts only when the in-flight round ends.
    @Test
    void awaitReplication_ackDuringInFlightRound_waitsForTheNextRound() {
        var pendingRound = Promise.<Unit> promise();
        var first = publishAndAwait("first");

        heldRound.set(Option.some(pendingRound));
        startQueuedRounds();
        heldRound.set(Option.none());
        var second = publishAndAwait("second");

        committed.set(Option.some(Y_AT_3));
        startQueuedRounds();
        assertThat(barrierRounds.get()).as("no second round while the first is in flight").isEqualTo(1);
        pendingRound.succeed(Unit.unit());
        startQueuedRounds();

        assertThat(barrierRounds.get()).as("the late waiter got its own round").isEqualTo(2);
        assertThat(causeOf(second.timeout(ACK_BOUND)
                                 .await())).as("the late waiter sees the move")
                                           .isInstanceOf(StreamError.OwnershipMovedBeforeAck.class);
        assertThat(first.await()
                        .isFailure()).as("the first round applied the move too, before its record read")
                                     .isTrue();
    }

    private Promise<Unit> publishAndAwait(String tag) {
        var offset = owner.publishLocalAtFloor(STREAM, PARTITION, tag.getBytes(StandardCharsets.UTF_8), 1L, 1)
                          .unwrap();

        return owner.awaitReplication(STREAM, PARTITION, offset, 1);
    }

    private Promise<Unit> round(String stream, int partition) {
        barrierRounds.incrementAndGet();

        return heldRound.get()
                        .or(Promise.unitPromise())
                        .map(this::applyCommitted);
    }

    /// What the round guarantees: everything committed before it has applied here.
    private Unit applyCommitted(Unit round) {
        localView.set(committed.get());

        return round;
    }

    private void startQueuedRounds() {
        while (!queuedRoundStarts.isEmpty()) {
            queuedRoundStarts.removeFirst()
                             .run();
        }
    }

    private void deliver(ReplicationReceiveHandler receiver, ReplicationMessage message) {
        if (message instanceof ReplicationMessage.ReplicateEvents events) {
            receiver.onReplicateEvents(events);
        }
    }

    private void toOwner(NodeId target, ReplicationMessage message) {
        if (message instanceof ReplicationMessage.ReplicateAck ack) {
            ownerReplication.get()
                            .handleAck(ack);
        }
    }

    private static Object causeOf(Result<?> outcome) {
        return outcome.fold(cause -> cause, _ -> "success");
    }

    private static StreamPartitionOwnershipValue record(NodeId node, int term) {
        return StreamPartitionOwnershipValue.streamPartitionOwnershipValue(node,
                                                                           Epoch.epoch(0L, term, 0),
                                                                           term,
                                                                           HlcTimestamp.ZERO,
                                                                           List.of(node, R),
                                                                           term);
    }

    private static StreamConfig config() {
        return StreamConfig.streamConfig(STREAM,
                                         1,
                                         RetentionPolicy.retentionPolicy(),
                                         "earliest",
                                         1_048_576L,
                                         ConsistencyMode.EVENTUAL,
                                         ReplicationFactors.BUILT_IN.replicationFactor(),
                                         2,
                                         StreamCompression.NONE,
                                         Option.none());
    }
}
