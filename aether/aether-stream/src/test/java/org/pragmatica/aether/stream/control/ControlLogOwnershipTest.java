// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.control;

import org.pragmatica.aether.stream.forward.AcceptingStreamForwardTransport;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import org.pragmatica.aether.stream.EvictionListener;
import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.control.ControlLogFixture.TestRefusal;
import org.pragmatica.aether.stream.control.ControlOutcome.Admitted;
import org.pragmatica.aether.stream.control.ControlOutcome.IndexUnavailable;
import org.pragmatica.aether.stream.control.ControlOutcome.Refused;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamForwardHandler;
import org.pragmatica.aether.stream.forward.StreamForwardMessage;
import org.pragmatica.aether.stream.replication.ReplicaSetController;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.control.ControlLogFixture.EPOCH_1;
import static org.pragmatica.aether.stream.control.ControlLogFixture.EPOCH_2;
import static org.pragmatica.aether.stream.control.ControlLogFixture.PARTITION;
import static org.pragmatica.aether.stream.control.ControlLogFixture.PEER;
import static org.pragmatica.aether.stream.control.ControlLogFixture.SELF;
import static org.pragmatica.aether.stream.control.ControlLogFixture.STREAM;
import static org.pragmatica.aether.stream.control.ControlLogFixture.ack;
import static org.pragmatica.aether.stream.control.ControlLogFixture.appendReady;
import static org.pragmatica.aether.stream.control.ControlLogFixture.await;
import static org.pragmatica.aether.stream.control.ControlLogFixture.awaitAdmissionReady;
import static org.pragmatica.aether.stream.control.ControlLogFixture.awaitTrue;
import static org.pragmatica.aether.stream.control.ControlLogFixture.land;
import static org.pragmatica.aether.stream.control.ControlLogFixture.latestReady;
import static org.pragmatica.aether.stream.control.ControlLogFixture.owner;
import static org.pragmatica.aether.stream.control.ControlLogFixture.record;
import static org.pragmatica.aether.stream.control.ControlLogFixture.replica;
import static org.pragmatica.aether.stream.control.ControlLogFixture.ringHead;
import static org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager;


/// #2590 P1: a change of owner. A promoted owner rebuilds its admitted view to its LOCAL head before admitting anything
/// (N2-b), and the deposed owner's queued item meets the ownership fence at its append (`NotOwnerAppend`, the writer's
/// `LocalOnly`), so the writer's retry lands on the new owner.
class ControlLogOwnershipTest {
    /// Behaviour 5 (N2-b). A replica holds a committed record and, above it, a successor's seed that was replicated but
    /// never committed (#1730 keeps it across the failover). The replica is promoted; a deposed write sent to it is refused
    /// against the seed. Mutation: the promoted owner rebuilds to the committed head only.
    @Test
    void promotion_replicatedUncommittedSeed_deposedWriteRefused() {
        try (var node = replica(3, 2)) {
            land(node, record("k", 1, 1));
            node.manager()
                .commitAdvanced(STREAM, PARTITION, 0L, EPOCH_1);
            land(node, record("k", 2, 1));
            awaitTrue("the replica's index holds the committed record",
                      () -> latestReady(node, "k").map(IndexedRecord::offset)
                                                  .or(-1L) == 0L);

            promote(node);

            assertThat(appendReady(node, record("k", 1, 5))).containsExactly(new Refused(TestRefusal.SUPERSEDED));
            assertThat(ringHead(node)).as("nothing appended").isEqualTo(1L);
        }
    }

    /// Behaviour 5 (N2-b). Until the promoted owner's rebuild completes, admission answers IndexUnavailable. The rebuild's
    /// read of the log is held to make the window observable. Mutation: admit against the empty state on promotion.
    @Test
    void promotion_beforeRebuild_returnsIndexUnavailable() throws Exception {
        var holding = new AtomicBoolean(false);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var node = replicaOver(log -> holdingReads(log, holding, held, release))) {
            land(node, record("k", 1, 1));
            node.manager()
                .commitAdvanced(STREAM, PARTITION, 0L, EPOCH_1);
            awaitTrue("the replica's index is valid", () -> latestReady(node, "k").isPresent());
            holding.set(true);

            promote(node);

            assertThat(await(node.controlLog()
                                 .append(STREAM, PARTITION, List.of(record("k", 2, 1))))).containsExactly(IndexUnavailable.INDEX_UNAVAILABLE);
            assertThat(held.await(10, TimeUnit.SECONDS)).as("the promotion rebuild is reading the log").isTrue();
            assertThat(await(node.controlLog()
                                 .append(STREAM, PARTITION, List.of(record("k", 2, 1))))).containsExactly(IndexUnavailable.INDEX_UNAVAILABLE);
            assertThat(ringHead(node)).as("nothing appended while rebuilding").isEqualTo(0L);

            release.countDown();
            holding.set(false);
            awaitAdmissionReady(node);
            var admitted = node.controlLog()
                               .append(STREAM, PARTITION, List.of(record("k", 2, 1)));

            awaitTrue("appended once rebuilt", () -> ringHead(node) == 1L);
            ack(node, PEER, 1L);

            assertThat(await(admitted)).containsExactly(new Admitted(1L));
        }
    }

    /// Behaviour 14 (N2-b). The old owner's item is past admission when the ownership moves; its append meets the fence,
    /// answers `NotOwnerAppend`, which the writer reads as `LocalOnly`. The writer's retry re-resolves the owner and is
    /// forwarded, as one batch, to the new owner's queue, where it is admitted. Mutation: append without the fence.
    @Test
    void demotion_queuedItem_mapsToLocalOnly() throws Exception {
        var newOwnerId = NodeId.nodeId("ctl-new-owner").unwrap();
        var resolvedOwner = new AtomicReference<>(SELF);

        try (var newOwner = owner(1, 1); var oldOwner = routedOwner(resolvedOwner, newOwnerId, newOwner)) {
            awaitAdmissionReady(oldOwner.node());
            awaitAdmissionReady(newOwner);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);

            oldOwner.node()
                    .state()
                    .admissionProbe(() -> holdOnce(entered, release));
            var queued = oldOwner.node()
                                 .controlLog()
                                 .append(STREAM, PARTITION, List.of(record("k", 1, 1)));

            assertThat(entered.await(10, TimeUnit.SECONDS)).as("the item passed admission").isTrue();
            oldOwner.node()
                    .manager()
                    .ownerWriteAdmission((_, _) -> Option.some(newOwnerId));
            resolvedOwner.set(newOwnerId);
            release.countDown();

            var outcome = await(queued);

            assertThat(outcome).hasSize(1);
            assertThat(outcome.getFirst()).isInstanceOfSatisfying(Refused.class,
                                                                  refused -> assertThat(refused.cause()).isInstanceOf(StreamError.NotOwnerAppend.class));
            assertThat(outcome.getFirst()
                              .isLocalOnly()).as("the writer's LocalOnly").isTrue();
            assertThat(ringHead(oldOwner.node())).as("nothing appended on the old owner").isEqualTo(-1L);

            assertThat(await(oldOwner.node()
                                     .controlLog()
                                     .append(STREAM, PARTITION, List.of(record("k", 1, 1))))).as("the retry lands on the new owner")
                                                                                             .containsExactly(new Admitted(0L));
            assertThat(ringHead(newOwner)).isEqualTo(0L);
            assertThat(oldOwner.forwardedBatches()
                               .get()).as("the retry crossed as one forwarded batch")
                                      .isEqualTo(1);
        }
    }

    private static void promote(ControlLogFixture.Node node) {
        node.role()
            .set(ReplicaSetController.Role.OWNER);
        node.ownerEpoch()
            .set(EPOCH_2);
    }

    private static void holdOnce(CountDownLatch entered, CountDownLatch release) {
        if (entered.getCount() > 0) {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread()
                      .interrupt();
            }
        }
    }

    /// An owner whose control log forwards to `newOwner` (through its forward handler, in process) once
    /// `resolvedOwner` names it.
    record RoutedOwner(ControlLogFixture.Node node, AtomicInteger forwardedBatches) implements AutoCloseable {
        @Override
        public void close() {
            node.close();
        }
    }

    private static RoutedOwner routedOwner(AtomicReference<NodeId> resolvedOwner,
                                           NodeId newOwnerId,
                                           ControlLogFixture.Node newOwner) {
        var forwarded = new AtomicInteger();
        var clientRef = new AtomicReference<StreamForwardClient>();
        var handler = StreamForwardHandler.streamForwardHandler(newOwnerId,
                                                                newOwner.manager(),
                                                                AcceptingStreamForwardTransport.accepting((_, message) -> respond(clientRef, message)),
                                                                newOwner.controlLog());
        var client = StreamForwardClient.streamForwardClient(SELF, AcceptingStreamForwardTransport.accepting((_, message) -> deliver(handler, forwarded, message)));

        clientRef.set(client);
        var replication = replicationManager(SELF, ControlLogFixture.registry(1));
        var manager = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replication);
        var base = ControlLogFixture.node(manager, replication, MirrorView.EMPTY, 1L, ReplicaSetController.Role.OWNER);
        var routed = ControlLog.controlLog(ControlPartitionLog.partitionManagerLog(manager, Option.none(), Option.none()),
                                           ControlLogFixture.admissions(),
                                           MirrorView.EMPTY,
                                           SELF,
                                           (_, _) -> Option.some(resolvedOwner.get()),
                                           client::appendControlRemote);

        manager.partitionLogEvents(routed.events());
        ControlLogFixture.createStream(manager, 1, 1);

        return new RoutedOwner(new ControlLogFixture.Node(manager,
                                                          replication,
                                                          routed,
                                                          base.ownerEpoch(),
                                                          base.role()),
                               forwarded);
    }

    private static void deliver(StreamForwardHandler handler,
                                AtomicInteger forwarded,
                                StreamForwardMessage message) {
        if (message instanceof StreamForwardMessage.ControlAppendForward batch) {
            forwarded.incrementAndGet();
            handler.onControlAppendForward(batch);
        }
    }

    private static void respond(AtomicReference<StreamForwardClient> client, StreamForwardMessage message) {
        if (message instanceof StreamForwardMessage.ControlAppendForwardResponse response) {
            client.get()
                  .onControlAppendForwardResponse(response);
        }
    }

    private static ControlLogFixture.Node replicaOver(UnaryOperator<ControlPartitionLog> wrap) {
        var replication = replicationManager(SELF, ControlLogFixture.registry(3));
        var manager = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replication);
        var node = ControlLogFixture.node(manager, replication, MirrorView.EMPTY, 1L, ReplicaSetController.Role.REPLICA, wrap);

        ControlLogFixture.createStream(manager, 3, 2);

        return node;
    }

    /// The real log, except that while `holding` is set a read of it is answered only after `release`.
    private static ControlPartitionLog holdingReads(ControlPartitionLog log,
                                                    AtomicBoolean holding,
                                                    CountDownLatch held,
                                                    CountDownLatch release) {
        return new ControlLogIndexTest.ForwardingLog(log) {
            @Override
            public Promise<List<RawEvent>> read(String streamName, int partition, long fromOffset, int maxEvents) {
                if (!holding.get()) {
                    return log.read(streamName, partition, fromOffset, maxEvents);
                }

                Promise<List<RawEvent>> gated = Promise.promise();

                held.countDown();
                Thread.ofVirtual()
                      .start(() -> answerAfter(release, gated, log.read(streamName, partition, fromOffset, maxEvents)));
                return gated;
            }
        };
    }

    private static void answerAfter(CountDownLatch release, Promise<List<RawEvent>> gated, Promise<List<RawEvent>> read) {
        try {
            release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread()
                  .interrupt();
        }
        read.onResult(gated::resolve);
    }
}
