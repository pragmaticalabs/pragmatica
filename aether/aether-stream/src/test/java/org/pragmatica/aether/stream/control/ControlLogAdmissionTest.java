// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.control;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.stream.control.ControlOutcome.Admitted;
import org.pragmatica.aether.stream.control.ControlOutcome.IndexUnavailable;
import org.pragmatica.aether.stream.control.ControlOutcome.OutcomeUnknown;
import org.pragmatica.aether.stream.control.ControlOutcome.Refused;
import org.pragmatica.aether.stream.control.ControlLogFixture.TestRefusal;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.control.ControlLogFixture.PARTITION;
import static org.pragmatica.aether.stream.control.ControlLogFixture.PEER;
import static org.pragmatica.aether.stream.control.ControlLogFixture.PEER2;
import static org.pragmatica.aether.stream.control.ControlLogFixture.STREAM;
import static org.pragmatica.aether.stream.control.ControlLogFixture.ack;
import static org.pragmatica.aether.stream.control.ControlLogFixture.appendReady;
import static org.pragmatica.aether.stream.control.ControlLogFixture.await;
import static org.pragmatica.aether.stream.control.ControlLogFixture.awaitAdmissionReady;
import static org.pragmatica.aether.stream.control.ControlLogFixture.awaitTrue;
import static org.pragmatica.aether.stream.control.ControlLogFixture.latestReady;
import static org.pragmatica.aether.stream.control.ControlLogFixture.owner;
import static org.pragmatica.aether.stream.control.ControlLogFixture.record;
import static org.pragmatica.aether.stream.control.ControlLogFixture.ringHead;
import static org.pragmatica.aether.stream.control.ControlLogFixture.valueOf;


/// #2590 P1: owner-side admission. The admitted view is the committed index plus the in-flight tail (N1), it is judged on
/// one serial queue per partition, a record leaves the tail only by committing (N2-a, N3), and only the fsync plus the
/// confirmation barrier acknowledges.
class ControlLogAdmissionTest {
    @Nested
    class AdmittedView {
        /// Behaviour 8 (N1). A successor's seed (epoch 2) is appended and waits in its barrier; a deposed writer's record
        /// (epoch 1) arriving meanwhile is refused against the seed in the tail. Mutation: admit against the committed index
        /// only.
        @Test
        void admission_seedInBarrier_deposedWriteRefused() {
            try (var node = owner(2, 2)) {
                awaitAdmissionReady(node);
                var seed = node.controlLog()
                               .append(STREAM, PARTITION, List.of(record("k", 2, 1)));

                awaitTrue("the seed is appended and in its barrier", () -> ringHead(node) == 0L);
                var deposed = node.controlLog()
                                  .append(STREAM, PARTITION, List.of(record("k", 1, 9)));

                ack(node, PEER, 1L);

                assertThat(await(seed)).containsExactly(new Admitted(0L));
                assertThat(await(deposed)).containsExactly(new Refused(TestRefusal.SUPERSEDED));
                assertThat(ringHead(node)).as("the refused record was never appended").isEqualTo(0L);
            }
        }

        /// Behaviour 8 (N1). Two writers of one key on one partition. The first is held between its admission and its
        /// append; the second must not be judged until the first has appended, so the later record is the latest.
        /// Mutation: run admission and the append on the caller's thread, without the queue: the second is judged against
        /// a view without the first, appends first, and the first then lands on top of it.
        @Test
        void admission_concurrentOnOnePartition_serialized() throws Exception {
            try (var node = owner(1, 1)) {
                awaitAdmissionReady(node);
                var entered = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                var calls = new AtomicInteger();

                node.state()
                    .admissionProbe(() -> holdFirst(calls, entered, release));
                var first = new AtomicReference<Promise<List<ControlOutcome>>>();

                Thread.ofVirtual()
                      .start(() -> first.set(node.controlLog()
                                                 .append(STREAM, PARTITION, List.of(record("k", 1, 1)))));
                assertThat(entered.await(10, TimeUnit.SECONDS)).as("the first writer reached its append").isTrue();
                var second = node.controlLog()
                                 .append(STREAM, PARTITION, List.of(record("k", 1, 2)));

                Thread.sleep(300);
                assertThat(second.isResolved()).as("the second writer waits for the first").isFalse();
                release.countDown();
                awaitTrue("the first writer's append returned", () -> first.get() != null);

                assertThat(await(first.get())).containsExactly(new Admitted(0L));
                assertThat(await(second)).containsExactly(new Admitted(1L));
                awaitTrue("both are indexed", () -> latestReady(node, "k").map(IndexedRecord::offset)
                                                                          .or(-1L) == 1L);
                assertThat(valueOf(latestReady(node, "k").unwrap()).seq()).as("the later record is the latest")
                                                                          .isEqualTo(2L);
            }
        }

        /// Behaviour 9 (N2-a). A probe runs inside the committed transfer, after the seed entered the index and before it
        /// left the tail, and judges a deposed record at exactly that point: the seed must be in the view there.
        /// Mutation: remove from the tail before inserting into the index.
        @Test
        void transfer_probeBetweenInsertAndRemove_deposedWriteRefused() {
            try (var node = owner(1, 1)) {
                awaitAdmissionReady(node);
                var verdicts = new AtomicReference<List<ControlOutcome>>(List.of());
                var deposed = record("k", 1, 9);

                node.state()
                    .transferProbe(offset -> verdicts.updateAndGet(seen -> appended(seen,
                                                                                   node.state()
                                                                                       .verdictNow(deposed))));

                assertThat(appendReady(node, record("k", 2, 1))).containsExactly(new Admitted(0L));
                awaitTrue("the transfer ran", () -> !verdicts.get()
                                                           .isEmpty());

                assertThat(verdicts.get()).as("mid-transfer, the seed is in the admitted view")
                                          .allMatch(verdict -> verdict.equals(new Refused(TestRefusal.SUPERSEDED)));
                assertThat(appendReady(node, deposed)).containsExactly(new Refused(TestRefusal.SUPERSEDED));
            }
        }

        /// Behaviour 11. Mutation: append the batch before judging it.
        @Test
        void admission_refused_appendsNothing() {
            try (var node = owner(1, 1)) {
                assertThat(appendReady(node, record("k", 2, 1))).containsExactly(new Admitted(0L));

                assertThat(appendReady(node, record("k", 1, 1))).containsExactly(new Refused(TestRefusal.SUPERSEDED));
                assertThat(ringHead(node)).as("nothing reached the ring").isEqualTo(0L);
            }
        }

        /// Behaviour 15 (R4.1). The ownership record carries no committed in-sync set (`isrVersion == 0`, minted before
        /// #1730): nothing is admitted, and the writer is told IndexUnavailable. Mutation: drop the guard.
        @Test
        void admission_isrVersionZero_indexUnavailable() {
            try (var node = owner(1, 1, MirrorView.EMPTY, Option.none(), 0L)) {
                awaitTrue("the owner's index is built",
                          () -> await(node.controlLog()
                                          .append(STREAM, PARTITION, List.of(record("k", 1, 1)))).getFirst() == IndexUnavailable.INDEX_UNAVAILABLE
                                && node.state()
                                       .phase() == PartitionControlState.Phase.VALID);

                for (var attempt = 0; attempt < 3; attempt++) {
                    assertThat(await(node.controlLog()
                                         .append(STREAM, PARTITION, List.of(record("k", 1, 1 + attempt))))).containsExactly(IndexUnavailable.INDEX_UNAVAILABLE);
                }
                assertThat(ringHead(node)).as("nothing was appended").isEqualTo(-1L);
            }
        }
    }

    @Nested
    class Acknowledgement {
        @TempDir
        Path walDir;

        /// Behaviour 12. Confirmation factor 3: the record is acknowledged only once both peers acknowledged it.
        /// Mutation: acknowledge on the local fsync.
        @Test
        void append_cf3_admittedOnlyAfterTwoPeerFsyncAcks() throws Exception {
            try (var node = owner(3, 3)) {
                awaitAdmissionReady(node);
                var outcome = node.controlLog()
                                  .append(STREAM, PARTITION, List.of(record("k", 1, 1)));

                awaitTrue("appended", () -> ringHead(node) == 0L);
                ack(node, PEER, 0L);
                Thread.sleep(300);
                assertThat(outcome.isResolved()).as("one of two peer acks is not an acknowledgement").isFalse();

                ack(node, PEER2, 0L);

                assertThat(await(outcome)).containsExactly(new Admitted(0L));
            }
        }

        /// Behaviour 10 (N3). The owner's WAL fsync fails after the record was written and replicated: the writer is told
        /// the outcome is unknown, the record stays in the tail (a deposed write is refused against it), and when it
        /// commits later -- through a replica after a failover, here its committed position delivered -- it enters the
        /// index. Mutation: drop the record from the tail when the fsync fails.
        @Test
        void ownerFsyncFails_recordStaysInTail_deposedWriteRefused_laterCommitIndexes() throws Exception {
            try (var node = owner(1, 1, MirrorView.EMPTY, Option.some(walDir), 1L)) {
                awaitAdmissionReady(node);
                FailingFsyncChannel.inject(node.manager(), STREAM, PARTITION);

                var seed = await(node.controlLog()
                                     .append(STREAM, PARTITION, List.of(record("k", 2, 1))));

                assertThat(seed).hasSize(1);
                assertThat(seed.getFirst()).isInstanceOf(OutcomeUnknown.class);
                assertThat(seed.getFirst()
                               .isLocalOnly()).as("the writer's LocalOnly").isTrue();
                assertThat(await(node.controlLog()
                                     .append(STREAM, PARTITION, List.of(record("k", 1, 9))))).containsExactly(new Refused(TestRefusal.SUPERSEDED));

                node.controlLog()
                    .events()
                    .committed(STREAM, PARTITION, 0L);

                awaitTrue("the record committed later and is indexed", () -> latestReady(node, "k").isPresent());
                assertThat(valueOf(latestReady(node, "k").unwrap()).epoch()).isEqualTo(2L);
            }
        }
    }

    @Nested
    class Carry {
        /// Behaviour 17. The key's assignee moved since the record was admitted; the kind's admission would refuse the
        /// record now, but a CARRY restates what was admitted and bypasses it. Mutation: run admission on CARRY.
        @Test
        void carry_assignmentMoved_stillLands() {
            var assignees = new ControlLogFixture.Assignees();

            assignees.assign("k", "w1");
            try (var node = owner(1, 1, assignees, Option.none(), 1L)) {
                assertThat(appendReady(node, record("k", 1, 1, "w1"))).containsExactly(new Admitted(0L));
                awaitTrue("indexed", () -> latestReady(node, "k").isPresent());
                assignees.assign("k", "w2");

                assertThat(await(node.controlLog()
                                     .carryForward(STREAM, PARTITION, 1L))).containsExactly(new Admitted(1L));
                awaitTrue("the carry is indexed", () -> latestReady(node, "k").map(IndexedRecord::offset)
                                                                              .or(-1L) == 1L);
                assertThat(valueOf(latestReady(node, "k").unwrap())).as("the restated record")
                                                                    .isEqualTo(new ControlLogFixture.TestValue(1, 1, "w1"));
                assertThat(node.controlLog()
                               .deletableThroughOffset(STREAM, PARTITION)).as("the floor moved past the carried record")
                                                                          .isEqualTo(0L);
            }
        }

        /// Behaviour 17 (N1). A newer record of the key is in flight when the carry runs: the carry is dropped, and the
        /// index never regresses to the carried value. Mutation: an unconditional CARRY.
        @Test
        void carry_interleavedNewerWrite_dropped() {
            try (var node = owner(2, 2)) {
                awaitAdmissionReady(node);
                var first = node.controlLog()
                                .append(STREAM, PARTITION, List.of(record("k", 1, 1)));

                ack(node, PEER, 0L);
                assertThat(await(first)).containsExactly(new Admitted(0L));
                awaitTrue("indexed", () -> latestReady(node, "k").isPresent());
                var newer = node.controlLog()
                                .append(STREAM, PARTITION, List.of(record("k", 1, 2)));

                awaitTrue("the newer record is in its barrier", () -> ringHead(node) == 1L);
                var carried = node.controlLog()
                                  .carryForward(STREAM, PARTITION, 1L);

                ack(node, PEER, 2L);

                assertThat(await(newer)).containsExactly(new Admitted(1L));
                assertThat(await(carried)).containsExactly(new Refused(ControlLogError.General.CARRY_SUPERSEDED));
                awaitTrue("the newer record is indexed", () -> latestReady(node, "k").map(IndexedRecord::offset)
                                                                                     .or(-1L) >= 1L);
                assertThat(valueOf(latestReady(node, "k").unwrap()).seq()).as("the index never regresses").isEqualTo(2L);
            }
        }
    }

    private static void holdFirst(AtomicInteger calls, CountDownLatch entered, CountDownLatch release) {
        if (calls.incrementAndGet() == 1) {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread()
                      .interrupt();
            }
        }
    }

    private static List<ControlOutcome> appended(List<ControlOutcome> seen, ControlOutcome verdict) {
        var all = new java.util.ArrayList<>(seen);

        all.add(verdict);
        return List.copyOf(all);
    }
}
