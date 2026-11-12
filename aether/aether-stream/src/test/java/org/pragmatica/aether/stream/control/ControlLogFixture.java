// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.control;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;

import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.OwnerLease;
import org.pragmatica.aether.stream.EvictionListener;
import org.pragmatica.aether.stream.LastSealedOffsetSource;
import org.pragmatica.aether.stream.OffHeapRingBuffer;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicaSetController;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateAck.replicateAck;


/// Shared fixture of the #2590 P1 control-log tests: real partition managers (the ring, the WAL when asked, the replication
/// barrier fed by hand-delivered acks), a control log over each, and one test kind whose admission predicate is the shape
/// P2's cursor conditions take: an owner epoch that only moves forward, a sequence within an epoch, and an assignee read
/// from the mirror.
final class ControlLogFixture {
    static final String STREAM = "ctl";
    static final int PARTITION = 0;
    static final NodeId SELF = NodeId.nodeId("ctl-owner").unwrap();
    static final NodeId PEER = NodeId.nodeId("ctl-peer-1").unwrap();
    static final NodeId PEER2 = NodeId.nodeId("ctl-peer-2").unwrap();
    static final Epoch EPOCH_1 = Epoch.epoch(1L, 1L, 1L);
    static final Epoch EPOCH_2 = Epoch.epoch(1L, 2L, 1L);
    static final ControlKind.Named TEST = new ControlKind.Named("TEST");
    static final TimeSpan WAIT = TimeSpan.timeSpan(10).seconds();

    private ControlLogFixture() {}

    /// The test kind's refusals.
    enum TestRefusal implements Cause {
        SUPERSEDED("a record of a newer epoch is the latest"),
        STALE("not newer than the latest record of its epoch"),
        MOVED("the writer is not the key's assignee");

        private final String message;

        TestRefusal(String message) {
            this.message = message;
        }

        @Override
        public String message() {
            return message;
        }
    }

    /// The test kind's payload.
    record TestValue(long epoch, long seq, String writer) {
        byte[] encode() {
            var writerBytes = writer.getBytes(StandardCharsets.UTF_8);

            return ByteBuffer.allocate(16 + writerBytes.length)
                             .putLong(epoch)
                             .putLong(seq)
                             .put(writerBytes)
                             .array();
        }

        static TestValue decode(byte[] payload) {
            var buffer = ByteBuffer.wrap(payload);
            var epoch = buffer.getLong();
            var seq = buffer.getLong();
            var writer = new byte[buffer.remaining()];

            buffer.get(writer);

            return new TestValue(epoch, seq, new String(writer, StandardCharsets.UTF_8));
        }
    }

    static ControlRecord record(String key, long epoch, long seq) {
        return record(key, epoch, seq, "w");
    }

    static ControlRecord record(String key, long epoch, long seq, String writer) {
        return ControlRecord.controlRecord(TEST, key, new TestValue(epoch, seq, writer).encode(), epoch * 1000 + seq);
    }

    static TestValue valueOf(IndexedRecord indexed) {
        return TestValue.decode(indexed.record()
                                       .payload());
    }

    /// The test kind's admission: refused when the mirror names another assignee for the key, when the latest record is of
    /// a newer epoch, or when it is of the same epoch and not older.
    static Result<Unit> admitTest(Option<ControlRecord> latest, ControlRecord incoming, MirrorView mirror) {
        var value = TestValue.decode(incoming.payload());
        var assignee = mirror.read(incoming.key())
                             .map(bytes -> new String(bytes, StandardCharsets.UTF_8));

        if (assignee.filter(name -> !name.equals(value.writer()))
                    .isPresent()) {
            return TestRefusal.MOVED.result();
        }

        return latest.map(record -> TestValue.decode(record.payload()))
                     .map(previous -> ordered(previous, value))
                     .or(Result::unitResult);
    }

    private static Result<Unit> ordered(TestValue previous, TestValue incoming) {
        if (incoming.epoch() < previous.epoch()) {
            return TestRefusal.SUPERSEDED.result();
        }

        return incoming.epoch() == previous.epoch() && incoming.seq() <= previous.seq()
               ? TestRefusal.STALE.result()
               : Result.unitResult();
    }

    static ControlKind.Admissions admissions() {
        return ControlKind.Admissions.admissions(Map.of(TEST.name(), ControlLogFixture::admitTest));
    }

    /// A mutable mirror of key assignees, for the carry test.
    static final class Assignees implements MirrorView {
        private final Map<String, String> assignees = new ConcurrentHashMap<>();

        void assign(String key, String writer) {
            assignees.put(key, writer);
        }

        @Override
        public Option<byte[]> read(String key) {
            return Option.option(assignees.get(key))
                         .map(name -> name.getBytes(StandardCharsets.UTF_8));
        }
    }

    /// One node: its partition manager, its replication manager (acks are delivered to it by hand), and its control log.
    record Node(StreamPartitionManager manager,
                ReplicationManager replication,
                ControlLog controlLog,
                AtomicReference<Epoch> ownerEpoch,
                AtomicReference<ReplicaSetController.Role> role) implements AutoCloseable {
        DefaultControlLog log() {
            return (DefaultControlLog) controlLog;
        }

        PartitionControlState state() {
            return log().state(STREAM, PARTITION);
        }

        @Override
        public void close() {
            manager.close();
        }
    }

    static Node owner(int replicas, int confirmationFactor) {
        return owner(replicas, confirmationFactor, MirrorView.EMPTY, Option.none(), 1L);
    }

    static Node owner(int replicas,
                      int confirmationFactor,
                      MirrorView mirror,
                      Option<Path> walDir,
                      long isrVersion) {
        var registry = registry(replicas);
        var replication = replicationManager(SELF, registry);
        var manager = streamPartitionManager(Long.MAX_VALUE,
                                             EvictionListener.NOOP,
                                             replication,
                                             walDir,
                                             LastSealedOffsetSource.none());
        var node = node(manager, replication, mirror, isrVersion, ReplicaSetController.Role.OWNER);

        createStream(manager, replicas, confirmationFactor);

        return node;
    }

    /// A node holding the partition as a replica: its records are landed with `appendRecovered`, its committed position
    /// comes from `commitAdvanced`, as the replication receive handler delivers them.
    static Node replica(int replicas, int confirmationFactor) {
        var replication = replicationManager(SELF, registry(replicas));
        var manager = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replication);
        var node = node(manager, replication, MirrorView.EMPTY, 1L, ReplicaSetController.Role.REPLICA);

        createStream(manager, replicas, confirmationFactor);

        return node;
    }

    static Node node(StreamPartitionManager manager,
                     ReplicationManager replication,
                     MirrorView mirror,
                     long isrVersion,
                     ReplicaSetController.Role initialRole) {
        return node(manager, replication, mirror, isrVersion, initialRole, UnaryOperator.identity());
    }

    /// A node whose control log reads its partition through `wrap(the real log)`.
    static Node node(StreamPartitionManager manager,
                     ReplicationManager replication,
                     MirrorView mirror,
                     long isrVersion,
                     ReplicaSetController.Role initialRole,
                     UnaryOperator<ControlPartitionLog> wrap) {
        var ownerEpoch = new AtomicReference<>(EPOCH_1);
        var role = new AtomicReference<>(initialRole);

        manager.ownerEpochSource((_, _) -> ownerEpoch.get());
        manager.placementRoleSupplier((_, _) -> role.get());
        manager.ownerLease(OwnerLease.TEST_VALID);
        manager.ownershipRecords((_, _) -> Option.some(ownership(ownerEpoch.get(), isrVersion)));
        var controlLog = ControlLog.controlLog(wrap.apply(ControlPartitionLog.partitionManagerLog(manager,
                                                                                                 Option.none(),
                                                                                                 Option.none())),
                                               admissions(),
                                               mirror);

        manager.partitionLogEvents(controlLog.events());

        return new Node(manager, replication, controlLog, ownerEpoch, role);
    }

    static StreamPartitionOwnershipValue ownership(Epoch epoch, long isrVersion) {
        return StreamPartitionOwnershipValue.streamPartitionOwnershipValue(SELF,
                                                                           epoch,
                                                                           1L,
                                                                           HlcTimestamp.ZERO,
                                                                           List.of(SELF, PEER, PEER2),
                                                                           isrVersion);
    }

    static ReplicaRegistry registry(int replicas) {
        ReplicaRegistry registry = replicaRegistry();

        registry.registerReplica(STREAM, PARTITION, SELF);
        if (replicas > 1) {
            registry.registerReplica(STREAM, PARTITION, PEER);
        }
        if (replicas > 2) {
            registry.registerReplica(STREAM, PARTITION, PEER2);
        }
        return registry;
    }

    static StreamConfig streamConfig(int replicas, int confirmationFactor) {
        return StreamConfig.streamConfig(STREAM,
                                         1,
                                         RetentionPolicy.retentionPolicy(),
                                         "earliest",
                                         1_048_576L,
                                         ConsistencyMode.EVENTUAL,
                                         replicas,
                                         confirmationFactor,
                                         StreamCompression.NONE,
                                         Option.none());
    }

    static void createStream(StreamPartitionManager manager, int replicas, int confirmationFactor) {
        manager.createStream(streamConfig(replicas, confirmationFactor))
               .onFailure(cause -> fail("create failed: " + cause.message()));
    }

    static void ack(Node node, NodeId peer, long offset) {
        node.replication()
            .handleAck(replicateAck(peer, STREAM, PARTITION, offset));
    }

    /// Lands an encoded record on a replica's copy, as a replicated batch would.
    static long land(Node node, ControlRecord record) {
        return node.manager()
                   .appendRecovered(STREAM, PARTITION, ControlRecordCodec.encode(record), 1L)
                   .onFailure(cause -> fail("land failed: " + cause.message()))
                   .or(-1L);
    }

    static long ringHead(Node node) {
        return node.manager()
                   .partitionBuffer(STREAM, PARTITION)
                   .map(OffHeapRingBuffer::headOffset)
                   .or(-1L);
    }

    static List<ControlOutcome> await(Promise<List<ControlOutcome>> outcomes) {
        return outcomes.await(WAIT)
                       .onFailure(cause -> fail("append failed: " + cause.message()))
                       .or(List.of());
    }

    /// Appends once the owner's admitted view is ready: the first attempts after a (re)build are answered
    /// IndexUnavailable while the rebuild runs.
    static List<ControlOutcome> appendReady(Node node, ControlRecord... records) {
        awaitAdmissionReady(node);

        return await(node.controlLog()
                         .append(STREAM, PARTITION, List.of(records)));
    }

    static void awaitAdmissionReady(Node node) {
        awaitTrue("the owner's admitted view becomes ready", () -> node.state().admissionReadyNow());
    }

    /// The key's latest committed record, once the index is valid.
    static Option<IndexedRecord> latestReady(Node node, String key) {
        var holder = new AtomicReference<Option<IndexedRecord>>(Option.none());

        awaitTrue("the index answers",
                  () -> node.controlLog()
                            .latest(STREAM, PARTITION, key)
                            .onSuccess(holder::set)
                            .isSuccess());

        return holder.get();
    }

    static void awaitTrue(String what, BooleanSupplier condition) {
        var deadline = System.nanoTime() + WAIT.nanos();

        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out waiting until " + what);
            }
            sleepBriefly();
        }
    }

    static void sleepBriefly() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread()
                  .interrupt();
        }
    }

    static void assertOutcomes(List<ControlOutcome> outcomes, Class<?>... kinds) {
        assertThat(outcomes).hasSize(kinds.length);
        var actual = new ArrayList<Class<?>>();

        outcomes.forEach(outcome -> actual.add(outcome.getClass()));
        assertThat(actual).as("outcomes " + outcomes)
                          .containsExactly(kinds);
    }
}
