// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateAck.replicateAck;

/// #2981 step 2 (design §2.2 check 2, §8): the `awaitReplication` continuation. The ownership `(owner, ownerEpoch, ownershipTerm)`
/// is captured at registration; when the replicas have confirmed: unchanged + valid lease -> acknowledged; changed ->
/// `OwnershipMovedBeforeAck`; unchanged + lapsed -> parked until a renewal edge, or `OwnerLeaseLapsedBeforeAck` at the 5 s budget.
class OwnerLeaseAckParkTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId SELF = NodeId.randomNodeId();
    private static final NodeId PEER = NodeId.randomNodeId();
    private static final Epoch E1 = Epoch.epoch(1L, 1L, 1L);
    private static final TimeSpan ANSWER_WAIT = TimeSpan.timeSpan(10).seconds();
    private static final TimeSpan SETTLE = TimeSpan.timeSpan(300).millis();

    private final AtomicBoolean leaseValid = new AtomicBoolean(true);
    private final ConcurrentLinkedDeque<Boolean> scriptedLease = new ConcurrentLinkedDeque<>();
    private final AtomicReference<Option<StreamPartitionOwnershipValue>> record = new AtomicReference<>(Option.some(record(1L,
                                                                                                                           List.of(SELF,
                                                                                                                                   PEER),
                                                                                                                           1L)));
    private ReplicationManager replication;
    private StreamPartitionManager manager;

    @AfterEach
    void closeManager() {
        Option.option(manager).onPresent(StreamPartitionManager::close);
    }

    /// LOW-1: the lease lapses BETWEEN registration and resolution -> parked, not "ok"; the renewal edge then completes it "ok"
    /// with the view unchanged, and the acked record becomes visible.
    @Test
    void lapseBetweenRegistrationAndResolution_parks_thenARenewalEdgeAcknowledges() {
        start(replicationWithPeer());
        var answer = manager.awaitReplication(STREAM, PARTITION, publish("e0"), 1);

        leaseValid.set(false);
        ack(0L);
        assertThat(unresolvedAfterSettling(answer)).as("a lapsed owner does not acknowledge").isTrue();
        assertThat(visible()).as("nor expose").isEqualTo(-1L);
        manager.onLeaseRegained();
        assertThat(unresolvedAfterSettling(answer)).as("control: a renewal edge while still lapsed answers nothing").isTrue();
        leaseValid.set(true);
        manager.onLeaseRegained();
        assertThat(answer.await(ANSWER_WAIT).isSuccess()).as("the renewal edge acknowledges the parked waiter").isTrue();
        assertThat(visible()).as("acknowledged => visible (rev1309 F1)").isEqualTo(0L);
    }

    /// F-1a-4: a parked waiter no renewal re-validates answers `OwnerLeaseLapsedBeforeAck` at its 5 s budget, never sooner.
    @Test
    void parkedWaiter_withNoRenewal_answersLapsedBeforeAck_atItsBudget() {
        start(replicationWithPeer());
        var registeredAt = System.nanoTime();
        var answer = manager.awaitReplication(STREAM, PARTITION, publish("e0"), 1);

        leaseValid.set(false);
        ack(0L);
        var outcome = answer.await(ANSWER_WAIT);
        var waitedMillis = (System.nanoTime() - registeredAt) / 1_000_000L;

        assertThat(outcome.isFailure()).isTrue();
        outcome.onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.OwnerLeaseLapsedBeforeAck.class));
        assertThat(waitedMillis).as("the budget runs from registration: 5 s").isBetween(4_900L, 9_000L);
        assertThat(visible()).isEqualTo(-1L);
    }

    /// The ownership moved (a new term) between registration and resolution -> `OwnershipMovedBeforeAck`, under a valid lease.
    @Test
    void ownershipMovedBetweenRegistrationAndResolution_answersMovedBeforeAck() {
        start(replicationWithPeer());
        var answer = manager.awaitReplication(STREAM, PARTITION, publish("e0"), 1);

        record.set(Option.some(record(2L, List.of(SELF, PEER), 1L)));
        ack(0L);
        answer.await(ANSWER_WAIT)
              .onSuccess(_ -> fail("a moved ownership must not acknowledge"))
              .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.OwnershipMovedBeforeAck.class));
    }

    /// A parked waiter whose ownership moves before the renewal edge is answered moved, not "ok".
    @Test
    void parkedWaiter_whoseOwnershipMoves_answersMovedAtTheRenewalEdge() {
        start(replicationWithPeer());
        var answer = manager.awaitReplication(STREAM, PARTITION, publish("e0"), 1);

        leaseValid.set(false);
        ack(0L);
        record.set(Option.some(record(2L, List.of(SELF, PEER), 1L)));
        leaseValid.set(true);
        manager.onLeaseRegained();
        answer.await(ANSWER_WAIT)
              .onSuccess(_ -> fail("a moved ownership must not acknowledge"))
              .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.OwnershipMovedBeforeAck.class));
    }

    /// F-1a-2: an ISR-only record change between registration and resolution keeps "ok".
    @Test
    void isrOnlyChangeBetweenRegistrationAndResolution_stillAcknowledges() {
        start(replicationWithPeer());
        var answer = manager.awaitReplication(STREAM, PARTITION, publish("e0"), 1);

        record.set(Option.some(record(1L, List.of(SELF), 2L)));
        ack(0L);
        assertThat(answer.await(ANSWER_WAIT).isSuccess()).isTrue();
        assertThat(visible()).isEqualTo(0L);
    }

    /// `refreshVisible` entered from `awaitReplication`'s success (design §8): the continuation found the lease valid, but it
    /// lapsed before the refresh ran; the refresh goes through `ownerAdvance` and exposes nothing. The acks were taken under a
    /// lapse (so the ack observer exposed nothing either) and the barrier is resolved by hand afterwards.
    @Test
    void awaitReplicationSuccess_leaseLapsingBeforeTheRefresh_staysAtTheReplicaBound() {
        var gated = new ManualAwaitReplication(replicationWithPeer());

        start(gated);
        var answer = manager.awaitReplication(STREAM, PARTITION, publish("e0"), 1);

        leaseValid.set(false);
        ack(0L);
        assertThat(visible()).as("control: the ack observer exposed nothing").isEqualTo(-1L);
        scriptedLease.add(true);
        gated.barrier.succeed(Unit.unit());
        assertThat(answer.await(ANSWER_WAIT).isSuccess()).as("control: the continuation's verdict saw a valid lease").isTrue();
        assertThat(scriptedLease).as("control: the scripted answer was consumed by the verdict").isEmpty();
        assertThat(visible()).as("the refresh re-read the lease and fell back").isEqualTo(-1L);
    }

    /// Confirmation factor 1 bypasses the check: no park even under a lapsed lease.
    @Test
    void confirmationFactorOne_neverParks() {
        start(replicationWithPeer(), 1);
        leaseValid.set(false);
        var answer = manager.awaitReplication(STREAM, PARTITION, publish("e0"), 1);

        ack(0L);
        assertThat(answer.await(ANSWER_WAIT).isSuccess()).isTrue();
    }

    // ---- fixture ------------------------------------------------------------------------------------------------------------

    private void start(ReplicationManager replicationManager) {
        start(replicationManager, 2);
    }

    private void start(ReplicationManager replicationManager, int confirmationFactor) {
        replication = replicationManager;
        manager = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replicationManager);
        manager.ownerLease(this::leaseAnswer);
        manager.ownershipRecords((_, _) -> record.get());
        manager.createStream(StreamConfig.streamConfig(STREAM,
                                                       1,
                                                       RetentionPolicy.retentionPolicy(),
                                                       "earliest",
                                                       1_048_576L,
                                                       ConsistencyMode.EVENTUAL,
                                                       3,
                                                       confirmationFactor,
                                                       StreamCompression.NONE,
                                                       Option.none()))
               .onFailure(cause -> fail(cause.message()));
    }

    /// Whether `answer` is still unresolved after a settling pause: a continuation may run on another thread.
    private static boolean unresolvedAfterSettling(Promise<Unit> answer) {
        answer.await(SETTLE);
        return !answer.isResolved();
    }

    private boolean leaseAnswer() {
        return Option.option(scriptedLease.poll())
                     .or(leaseValid::get);
    }

    private long publish(String payload) {
        return manager.publishLocal(STREAM, PARTITION, payload.getBytes(UTF_8), 1L)
                      .onFailure(cause -> fail("publish failed: " + cause.message()))
                      .or(-1L);
    }

    private void ack(long offset) {
        replication.handleAck(replicateAck(PEER, STREAM, PARTITION, offset));
    }

    private long visible() {
        return manager.partitionBuffer(STREAM, PARTITION).map(OffHeapRingBuffer::visibleOffset).or(Long.MIN_VALUE);
    }

    private static StreamPartitionOwnershipValue record(long term, List<NodeId> isr, long isrVersion) {
        return new StreamPartitionOwnershipValue(SELF, E1, term, HlcTimestamp.ZERO, isr, isrVersion, false, List.of(), List.of());
    }

    private static ReplicationManager replicationWithPeer() {
        ReplicaRegistry registry = replicaRegistry();

        registry.registerReplica(STREAM, PARTITION, SELF);
        registry.registerReplica(STREAM, PARTITION, PEER);
        return replicationManager(SELF, registry);
    }

    /// Delegates to a real replication manager, but every `awaitReplication` answers the one barrier the test resolves by hand.
    private static final class ManualAwaitReplication implements ReplicationManager {
        private final ReplicationManager delegate;
        private final Promise<Unit> barrier = Promise.promise();

        private ManualAwaitReplication(ReplicationManager delegate) {
            this.delegate = delegate;
        }

        @Contract
        @Override
        public void observeAcks(AckObserver observer) {
            delegate.observeAcks(observer);
        }

        @Contract
        @Override
        public void replicateEvent(String streamName,
                                   int partition,
                                   long offset,
                                   byte[] payload,
                                   long timestamp,
                                   Epoch ownerEpoch) {
            delegate.replicateEvent(streamName, partition, offset, payload, timestamp, ownerEpoch);
        }

        @Contract
        @Override
        public void handleAck(ReplicationMessage.ReplicateAck ack) {
            delegate.handleAck(ack);
        }

        @Override
        public ReplicaRegistry registry() {
            return delegate.registry();
        }

        @Override
        public Promise<Unit> awaitReplication(String streamName, int partition, long offset, int minAcks) {
            return barrier;
        }

        @Override
        public long replicatedThrough(String streamName, int partition, int minAcks) {
            return delegate.replicatedThrough(streamName, partition, minAcks);
        }

        @Override
        public long replicatedThrough(ReplicationMessage.ReplicateAck pending, int minAcks) {
            return delegate.replicatedThrough(pending, minAcks);
        }
    }
}
