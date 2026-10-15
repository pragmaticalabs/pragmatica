// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForwardResponse;
import org.pragmatica.aether.stream.forward.StreamForwardTransport;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.forward.StreamForwardHandler.streamForwardHandler;
import static org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForward.publishForward;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager;

/// Streams audit A-03 (#2201): "acked means durable on N" is assembled by each caller, and the pre-append floor only
/// checks that the committed ISR LISTS enough peers. An ISR member that has died but is still listed passes the floor;
/// the event is appended; and the barrier then times out (5 s), an unknown outcome for a publish that could never have
/// been acknowledged. Every variant below runs the real caller class, the real manager and the real
/// `DefaultReplicationManager`; the replica set is the only fixture.
///
/// **Limit of the dead-peer fixture (a fix that uses real liveness must supply that input here).** "Dead" is modelled only as "listed in the committed
/// ISR and never acking"; the floor has no liveness input today, so nothing in the fixture says the peer is down. A fix that decides on a real
/// liveness signal (SWIM membership, a heartbeat age) will not flip these tripwires until the fixture feeds that signal to the manager; the fixture
/// then changes, the assertion (nothing appended) does not.
///
/// The "dead" peer is modelled as: registered, listed in the committed ISR, and never acking. How the overhauled floor
/// learns of the death (a liveness input) does not exist yet, so the REAL assertions state only the outcome the
/// overhaul owes: the publish is refused BEFORE anything is appended. [unverified: the fixture gains a liveness input
/// when the overhaul defines one.]
///
/// Five floor variants (1b-core): (1) router single publish, (2) router batch publish, (3) forward handler, (4) forward
/// materialize-retry, (5) entity path (no floor at all; `EntityAppendFloorTripwireTest` in the node module).
class ReplicaFloorTripwireTest {
    private static final NodeId SELF = new NodeId("owner-self");
    private static final NodeId DEAD = new NodeId("replica-dead");
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final int DECLARED_CF = 2;
    private static final int RAISED_CF = 3;
    private static final long TIMESTAMP = 7L;
    private static final byte[] PAYLOAD = "p".getBytes();
    private static final String ENABLE = "A-03 tripwire: the pre-append floor now covers an ISR member that is listed but dead; delete this tripwire and enable the real assertion beside it (#2201)";
    private static final String REAL = "A-03 real assertion, RED today: a publish no listed-but-dead replica can acknowledge must be refused before the append (#2201)";

    private ReplicaRegistry registry;
    private ReplicationManager replication;
    private StreamPartitionManager manager;
    private final List<ForwardAnswer> answers = new CopyOnWriteArrayList<>();

    private record ForwardAnswer(NodeId target, Object message) {}

    @BeforeEach
    void setUp() {
        registry = replicaRegistry();
        replication = replicationManager(SELF, registry);
        manager = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replication);
        answers.clear();
    }

    private void listInIsr(List<NodeId> isr) {
        registry.registerReplica(STREAM, PARTITION, SELF);
        registry.registerReplica(STREAM, PARTITION, DEAD);
        replication.inSyncReplicaSource((_, _) -> Option.some(isr));
    }

    private StreamForwardTransport capturing() {
        return (target, message) -> answers.add(new ForwardAnswer(target, message));
    }

    /// The forward handler answers asynchronously, after its barrier (5 s ack timeout when a listed peer never acks).
    private PublishForwardResponse answer() {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);

        while (answers.isEmpty() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(answers).hasSize(1);
        return (PublishForwardResponse) answers.getFirst().message();
    }

    private void materialize(int confirmationFactor) {
        manager.createStream(config(confirmationFactor)).onFailure(cause -> fail(cause.message()));
    }

    /// Controls, GREEN and meant to stay green: with the ISR already shrunk below `CF - 1` every variant that has a floor
    /// refuses BEFORE the append. This is the half of the finding the floor does cover.
    @Nested
    class ShrunkIsrIsRefusedBeforeTheAppend {
        @BeforeEach
        void shrunk() {
            listInIsr(List.of(SELF));
        }

        @Test
        void routerSinglePublish_isRefused_nothingAppended() {
            materialize(DECLARED_CF);

            var outcome = StreamWriteRouter.localOnly(manager).publish(STREAM, PARTITION, PAYLOAD, TIMESTAMP).await();

            assertThat(outcome.isFailure()).isTrue();
            assertThat(manager.nextExpectedOffset(STREAM, PARTITION)).isZero();
        }

        @Test
        void routerBatchPublish_isRefused_nothingAppended() {
            materialize(DECLARED_CF);

            StreamWriteRouter.localOnly(manager).publishBatch(STREAM, PARTITION, List.of(PAYLOAD, PAYLOAD), TIMESTAMP).await();

            assertThat(manager.nextExpectedOffset(STREAM, PARTITION)).isZero();
        }

        @Test
        void forwardHandler_isRefused_nothingAppended() {
            materialize(DECLARED_CF);

            streamForwardHandler(SELF, manager, capturing()).onPublishForward(publishForward(DEAD, "c1", STREAM, PARTITION, PAYLOAD, TIMESTAMP));

            assertThat(answer().success()).isFalse();
            assertThat(answer().outcomeUnknown()).isFalse();
            assertThat(manager.nextExpectedOffset(STREAM, PARTITION)).isZero();
        }

        @Test
        void forwardMaterializeRetry_isRefused_nothingAppended() {
            manager.committedConfigSource(_ -> Option.some(config(DECLARED_CF)));

            streamForwardHandler(SELF, manager, capturing()).onPublishForward(publishForward(DEAD, "c1", STREAM, PARTITION, PAYLOAD, TIMESTAMP));

            assertThat(answer().success()).isFalse();
            assertThat(answer().outcomeUnknown()).isFalse();
            assertThat(manager.nextExpectedOffset(STREAM, PARTITION)).isZero();
        }
    }

    /// Positive control, GREEN now and after the fix: with every listed replica alive and acking the publish succeeds, so the real assertions
    /// below cannot be satisfied by refusing everything.
    @Nested
    class HealthyIsrIsAcked {
        @Test
        void routerSinglePublish_succeeds_whenEveryListedReplicaAcks() {
            listInIsr(List.of(SELF, DEAD));
            registry.updateWatermark(STREAM, PARTITION, DEAD, 1_000L);
            materialize(DECLARED_CF);

            var outcome = StreamWriteRouter.localOnly(manager).publish(STREAM, PARTITION, PAYLOAD, TIMESTAMP).await();

            assertThat(outcome.isSuccess()).as("control: " + outcome).isTrue();
            assertThat(manager.nextExpectedOffset(STREAM, PARTITION)).isEqualTo(1L);
        }
    }

    /// The finding: the ISR still lists a replica that is gone. Each variant appends and then times out.
    @Nested
    class DeadButListedPassesTheFloor {
        @BeforeEach
        void listed() {
            listInIsr(List.of(SELF, DEAD));
        }

        @Test
        void routerSinglePublish_appendsThenReportsUnknown_tripwire() {
            materialize(DECLARED_CF);

            var outcome = StreamWriteRouter.localOnly(manager).publish(STREAM, PARTITION, PAYLOAD, TIMESTAMP).await();

            assertThat(outcome.isFailure()).as("control: the barrier never met").isTrue();
            assertThat(manager.nextExpectedOffset(STREAM, PARTITION)).as(ENABLE).isEqualTo(1L);
        }

        @Disabled(REAL + " [variant 1: router single]")
        @Test
        void routerSinglePublish_isRefusedBeforeTheAppend() {
            materialize(DECLARED_CF);

            StreamWriteRouter.localOnly(manager).publish(STREAM, PARTITION, PAYLOAD, TIMESTAMP).await();

            assertThat(manager.nextExpectedOffset(STREAM, PARTITION)).isZero();
        }

        @Test
        void routerBatchPublish_appendsThenReportsUnknown_tripwire() {
            materialize(DECLARED_CF);

            var outcomes = StreamWriteRouter.localOnly(manager).publishBatch(STREAM, PARTITION, List.of(PAYLOAD, PAYLOAD), TIMESTAMP).await();

            assertThat(outcomes.isSuccess()).isTrue();
            assertThat(manager.nextExpectedOffset(STREAM, PARTITION)).as(ENABLE).isEqualTo(2L);
        }

        @Disabled(REAL + " [variant 2: router batch]")
        @Test
        void routerBatchPublish_isRefusedBeforeTheAppend() {
            materialize(DECLARED_CF);

            StreamWriteRouter.localOnly(manager).publishBatch(STREAM, PARTITION, List.of(PAYLOAD, PAYLOAD), TIMESTAMP).await();

            assertThat(manager.nextExpectedOffset(STREAM, PARTITION)).isZero();
        }

        @Test
        void forwardHandler_appendsThenReportsUnknown_tripwire() {
            materialize(DECLARED_CF);

            streamForwardHandler(SELF, manager, capturing()).onPublishForward(publishForward(DEAD, "c1", STREAM, PARTITION, PAYLOAD, TIMESTAMP));

            assertThat(answer().success()).isFalse();
            assertThat(answer().outcomeUnknown()).as(ENABLE).isTrue();
            assertThat(manager.nextExpectedOffset(STREAM, PARTITION)).as(ENABLE).isEqualTo(1L);
        }

        @Disabled(REAL + " [variant 3: forward handler]")
        @Test
        void forwardHandler_isRefusedBeforeTheAppend() {
            materialize(DECLARED_CF);

            streamForwardHandler(SELF, manager, capturing()).onPublishForward(publishForward(DEAD, "c1", STREAM, PARTITION, PAYLOAD, TIMESTAMP));

            assertThat(answer().outcomeUnknown()).isFalse();
            assertThat(manager.nextExpectedOffset(STREAM, PARTITION)).isZero();
        }

        @Test
        void forwardMaterializeRetry_appendsThenReportsUnknown_tripwire() {
            manager.committedConfigSource(_ -> Option.some(config(DECLARED_CF)));

            streamForwardHandler(SELF, manager, capturing()).onPublishForward(publishForward(DEAD, "c1", STREAM, PARTITION, PAYLOAD, TIMESTAMP));

            assertThat(answer().success()).isFalse();
            assertThat(answer().outcomeUnknown()).as(ENABLE).isTrue();
            assertThat(manager.nextExpectedOffset(STREAM, PARTITION)).as(ENABLE).isEqualTo(1L);
        }

        @Disabled(REAL + " [variant 4: forward materialize-retry]")
        @Test
        void forwardMaterializeRetry_isRefusedBeforeTheAppend() {
            manager.committedConfigSource(_ -> Option.some(config(DECLARED_CF)));

            streamForwardHandler(SELF, manager, capturing()).onPublishForward(publishForward(DEAD, "c1", STREAM, PARTITION, PAYLOAD, TIMESTAMP));

            assertThat(answer().outcomeUnknown()).isFalse();
            assertThat(manager.nextExpectedOffset(STREAM, PARTITION)).isZero();
        }
    }

    /// The forward handler reads the confirmation factor twice per publish (floor at entry, barrier after the append).
    /// A raise between the two moves the barrier of the publish already in flight, which the router pins against
    /// (`StreamWritePathContractTest`, #1361 M12). The assembly is repeated per caller, so it can drift per caller.
    @Nested
    class BarrierReadsTheFactorAgain {
        @Test
        void forwardHandler_barrierFollowsAConfigRaisedDuringTheAppend_tripwire() {
            var awaited = publishForwardedWhileRaisingTheFactor();

            assertThat(awaited).as(ENABLE).containsExactly(RAISED_CF - 1);
        }

        @Disabled("A-03 real assertion, RED today: one confirmation-factor read per publish feeds floor and barrier on the forward path too (#2201)")
        @Test
        void forwardHandler_barrierUsesTheFactorReadAtEntry() {
            var awaited = publishForwardedWhileRaisingTheFactor();

            assertThat(awaited).containsExactly(DECLARED_CF - 1);
        }

        private List<Integer> publishForwardedWhileRaisingTheFactor() {
            var awaited = new ArrayList<Integer>();
            var raising = new RaiseOnReplicate(awaited);

            manager = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, raising);
            raising.onSend = () -> manager.onStreamConfigPut(configPut(config(RAISED_CF)));
            materialize(DECLARED_CF);
            streamForwardHandler(SELF, manager, capturing()).onPublishForward(publishForward(DEAD, "c1", STREAM, PARTITION, PAYLOAD, TIMESTAMP));
            assertThat(answer().success()).as("control: the stand-in barrier is met at once").isTrue();
            return awaited;
        }
    }

    /// A replication stand-in: the append is replicated by nobody, the barrier is met at once and its `minAcks` recorded;
    /// `onSend` runs inside the ordered section, between the caller's floor read and its barrier read.
    private static final class RaiseOnReplicate implements ReplicationManager {
        private final ReplicaRegistry registry = replicaRegistry();
        private final List<Integer> awaited;
        private Runnable onSend = () -> {};

        RaiseOnReplicate(List<Integer> awaited) {
            this.awaited = awaited;
        }

        @Contract
        @Override
        public void replicateEvent(String streamName, int partition, long offset, byte[] payload, long timestamp, Epoch ownerEpoch) {
            onSend.run();
        }

        @Contract
        @Override
        public void handleAck(ReplicationMessage.ReplicateAck ack) {}

        @Override
        public ReplicaRegistry registry() {
            return registry;
        }

        @Override
        public Promise<Unit> awaitReplication(String streamName, int partition, long offset, int minAcks) {
            awaited.add(minAcks);
            return Promise.unitPromise();
        }

        @Override
        public long replicatedThrough(String streamName, int partition, int minAcks) {
            return Long.MAX_VALUE;
        }

        @Override
        public long replicatedThrough(ReplicationMessage.ReplicateAck pending, int minAcks) {
            return Long.MAX_VALUE;
        }

        @Contract
        @Override
        public void observeAcks(AckObserver observer) {}

        @Override
        public Result<Unit> ensureReplicaFloor(String streamName, int partition, int minAcks) {
            return Result.unitResult();
        }
    }

    private static ValuePut<StreamConfigKey, StreamConfigValue> configPut(StreamConfig config) {
        return new ValuePut<>(new KVCommand.Put<>(StreamConfigKey.streamConfigKey(config.name()),
                                                  StreamConfigValue.streamConfigValue(config)),
                              Option.none());
    }

    private static StreamConfig config(int confirmationFactor) {
        return StreamConfig.streamConfig(STREAM,
                                         1,
                                         RetentionPolicy.retentionPolicy(1_000, 1024 * 1024, 60_000),
                                         "earliest",
                                         1_048_576L,
                                         ConsistencyMode.EVENTUAL,
                                         3,
                                         confirmationFactor,
                                         StreamCompression.NONE,
                                         Option.none());
    }
}
