// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.PublishOutcomeUnknown;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.forward.AcceptingStreamForwardTransport;
import org.pragmatica.aether.stream.forward.StreamForwardHandler;
import org.pragmatica.aether.stream.forward.StreamForwardMessage;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForwardResponse;
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
/// committed. The [LinearizableBarrier] stand-in is ordered when it is SUBMITTED: it snapshots `committed` then, and applies
/// that snapshot into `localView` when it completes. That is what a Rabia no-op round guarantees (every change committed
/// before the round was ordered has applied here once it applied) and no more: a change committed while a round is in
/// flight may be ordered after it. The round start runs on a manual executor, so each test decides exactly what is
/// committed when each round is ordered.
class AckTimeOwnershipCheckTest {
    private static final String STREAM = "orders";
    private static final String CF1_STREAM = "audit";
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
    private final BlockingQueue<Runnable> queuedRoundStarts = new LinkedBlockingQueue<>();
    private final AtomicReference<Option<Promise<Unit>>> heldRound = new AtomicReference<>(Option.none());
    private final AtomicReference<Boolean> holdAcks = new AtomicReference<>(false);
    private final List<ReplicationMessage.ReplicateAck> heldAcks = new ArrayList<>();
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
        assertThat(owner.createStream(config(CF1_STREAM, 1)).isSuccess()).isTrue();
        assertThat(replica.createStream(config(CF1_STREAM, 1)).isSuccess()).isTrue();
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

    /// Group commit at the ack site: ONE `handleAck` (a replica confirming through offset 7) releases eight waiters at once,
    /// and they share ONE round and one record read.
    @Test
    void awaitReplication_waitersReleasedByOneAck_shareOneRound() {
        holdAcks.set(true);
        var acks = new ArrayList<Promise<Unit>>();

        for (var i = 0; i < 8; i++) {
            acks.add(publishAndAwait("held-" + i));
        }
        assertThat(acks).as("control: no waiter resolved before the replica's ack").noneMatch(Promise::isResolved);
        assertThat(heldAcks).hasSize(8);
        ownerReplication.get()
                        .handleAck(heldAcks.getLast());
        startQueuedRounds();

        assertThat(Promise.allOf(acks)
                          .timeout(ACK_BOUND)
                          .await()
                          .isSuccess()).isTrue();
        assertThat(acks.stream()
                       .map(Promise::await)
                       .allMatch(Result::isSuccess)).isTrue();
        assertThat(barrierRounds.get()).as("one barrier round for the eight waiters one ack released").isEqualTo(1);
    }

    /// A waiter whose acks resolve while a round is in flight never joins that round: the round was ordered before the
    /// waiter's acks resolved, so it may be ordered before a move the waiter must see. Here the move commits after the
    /// in-flight round was ordered; the late waiter waits for the next round (at most one in flight), which sees the move.
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
        assertThat(barrierRounds.get()).as("at most one round per partition in flight").isEqualTo(1);
        assertThat(second.isResolved()).as("the late waiter is not answered by the in-flight round")
                                       .isFalse();
        pendingRound.succeed(Unit.unit());
        assertThat(first.timeout(ACK_BOUND)
                        .await()
                        .isSuccess()).as("the first round was ordered before the move")
                                     .isTrue();
        startQueuedRounds();

        assertThat(barrierRounds.get()).as("the late waiter got its own round").isEqualTo(2);
        assertThat(causeOf(second.timeout(ACK_BOUND)
                                 .await())).as("the late waiter sees the move")
                                           .isInstanceOf(StreamError.OwnershipMovedBeforeAck.class);
    }

    /// The writer's answer: the local publish path maps the lost race to [PublishOutcomeUnknown], never "ok".
    @Test
    void publish_deposedOwner_isPublishOutcomeUnknown() throws InterruptedException {
        committed.set(Option.some(Y_AT_3));

        var published = router().publish(STREAM, PARTITION, bytes("routed"), 1L);

        startRoundWhenQueued();
        var outcome = published.timeout(ACK_BOUND)
                               .await();

        assertThat(causeOf(outcome)).as("%s", outcome)
                                    .isInstanceOf(PublishOutcomeUnknown.class);
        assertThat(((PublishOutcomeUnknown) causeOf(outcome)).origin()).isInstanceOf(StreamError.OwnershipMovedBeforeAck.class);
    }

    /// The forwarded publish path: the owner answers a forwarded append it may not acknowledge with an outcome-unknown
    /// response, never a success response.
    @Test
    void onPublishForward_deposedOwner_answersOutcomeUnknown() throws InterruptedException {
        committed.set(Option.some(Y_AT_3));
        var response = Promise.<PublishForwardResponse> promise();
        var handler = StreamForwardHandler.streamForwardHandler(X,
                                                                owner,
                                                                AcceptingStreamForwardTransport.accepting((_, message) -> answer(response,
                                                                                                                                 message)));

        handler.onPublishForward(PublishForward.publishForward(Y, "corr-1", STREAM, PARTITION, bytes("fwd"), 1L));
        startRoundWhenQueued();
        var answered = response.timeout(ACK_BOUND)
                               .await()
                               .unwrap();

        assertThat(answered.success()).as("%s", answered)
                                      .isFalse();
        assertThat(answered.outcomeUnknown()).as("%s", answered)
                                             .isTrue();
        assertThat(barrierRounds.get()).isEqualTo(1);
    }

    /// The forwarded path's positive arm: unchanged ownership answers success after one round.
    @Test
    void onPublishForward_ownershipUnchanged_answersSuccess() throws InterruptedException {
        var response = Promise.<PublishForwardResponse> promise();
        var handler = StreamForwardHandler.streamForwardHandler(X,
                                                                owner,
                                                                AcceptingStreamForwardTransport.accepting((_, message) -> answer(response,
                                                                                                                                 message)));

        handler.onPublishForward(PublishForward.publishForward(Y, "corr-2", STREAM, PARTITION, bytes("fwd"), 1L));
        startRoundWhenQueued();

        assertThat(response.timeout(ACK_BOUND)
                           .await()
                           .unwrap()
                           .success()).isTrue();
        assertThat(barrierRounds.get()).isEqualTo(1);
    }

    /// `confirmation_factor` 1: the ack is owner-local and keeps its documented window; no barrier round runs, even when
    /// the committed ownership moved.
    @Test
    void publish_confirmationFactorOne_runsNoRound() {
        committed.set(Option.some(Y_AT_3));

        var published = router().publish(CF1_STREAM, PARTITION, bytes("cf1"), 1L);

        startQueuedRounds();
        var outcome = published.timeout(ACK_BOUND)
                               .await();

        assertThat(outcome.isSuccess()).as("%s", outcome)
                                       .isTrue();
        assertThat(barrierRounds.get()).as("CF=1 runs no barrier round").isZero();
    }

    /// Instrument control: with the seam unbound ([AckOwnershipCheck#UNCHECKED]) the same N2 interleaving acknowledges
    /// "ok". This is the defect #2981 B closes, and it proves the fixture reaches it.
    @Test
    void awaitReplication_seamUnbound_deposedOwnerAcknowledges_control() {
        owner.ackOwnershipCheck(AckOwnershipCheck.UNCHECKED);
        committed.set(Option.some(Y_AT_3));

        var outcome = publishAndAwait("unchecked").timeout(ACK_BOUND)
                                                 .await();

        assertThat(outcome.isSuccess()).as("without the check a deposed owner acknowledges (%s)", outcome)
                                       .isTrue();
        assertThat(barrierRounds.get()).isZero();
    }

    private Promise<Unit> publishAndAwait(String tag) {
        var offset = owner.publishLocalAtFloor(STREAM, PARTITION, bytes(tag), 1L, 1)
                          .unwrap();

        return owner.awaitReplication(STREAM, PARTITION, offset, 1);
    }

    /// The round is ordered at submission: it snapshots what is committed now and applies exactly that when it completes.
    private Promise<Unit> round(String stream, int partition) {
        barrierRounds.incrementAndGet();
        var orderedAfter = committed.get();

        return heldRound.get()
                        .or(Promise.unitPromise())
                        .map(round -> applyCommitted(round, orderedAfter));
    }

    /// What the round guarantees: everything committed before it was ordered has applied here.
    private Unit applyCommitted(Unit round, Option<StreamPartitionOwnershipValue> orderedAfter) {
        localView.set(orderedAfter);

        return round;
    }

    private StreamWriteRouter router() {
        return StreamWriteRouter.streamWriteRouter(owner, Option.none(), X, (_, _) -> Option.some(X));
    }

    private static byte[] bytes(String tag) {
        return tag.getBytes(StandardCharsets.UTF_8);
    }

    private static void answer(Promise<PublishForwardResponse> response, StreamForwardMessage message) {
        if (message instanceof PublishForwardResponse answered) {
            response.succeed(answered);
        }
    }

    private void startQueuedRounds() {
        Runnable start;

        while ((start = queuedRoundStarts.poll()) != null) {
            start.run();
        }
    }

    /// For writers that reach the ack site on another thread: waits (bounded) for the round to be queued, then starts it.
    private void startRoundWhenQueued() throws InterruptedException {
        var start = queuedRoundStarts.poll(ACK_BOUND.millis(), TimeUnit.MILLISECONDS);

        assertThat(start).as("a barrier round was queued").isNotNull();
        start.run();
        startQueuedRounds();
    }

    private void deliver(ReplicationReceiveHandler receiver, ReplicationMessage message) {
        if (message instanceof ReplicationMessage.ReplicateEvents events) {
            receiver.onReplicateEvents(events);
        }
    }

    private void toOwner(NodeId target, ReplicationMessage message) {
        if (message instanceof ReplicationMessage.ReplicateAck ack) {
            routeAck(ack);
        }
    }

    private void routeAck(ReplicationMessage.ReplicateAck ack) {
        if (holdAcks.get()) {
            heldAcks.add(ack);
        } else {
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
        return config(STREAM, 2);
    }

    private static StreamConfig config(String name, int confirmationFactor) {
        return StreamConfig.streamConfig(name,
                                         1,
                                         RetentionPolicy.retentionPolicy(),
                                         "earliest",
                                         1_048_576L,
                                         ConsistencyMode.EVENTUAL,
                                         ReplicationFactors.BUILT_IN.replicationFactor(),
                                         confirmationFactor,
                                         StreamCompression.NONE,
                                         Option.none());
    }
}
