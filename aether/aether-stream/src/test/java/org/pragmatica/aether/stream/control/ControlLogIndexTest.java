// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.control;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.EvictionListener;
import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.aether.stream.StreamPartitionManager.ControlRunAppend;
import org.pragmatica.aether.stream.replication.ReplicaSetController;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.control.ControlLogFixture.EPOCH_1;
import static org.pragmatica.aether.stream.control.ControlLogFixture.PARTITION;
import static org.pragmatica.aether.stream.control.ControlLogFixture.SELF;
import static org.pragmatica.aether.stream.control.ControlLogFixture.STREAM;
import static org.pragmatica.aether.stream.control.ControlLogFixture.appendReady;
import static org.pragmatica.aether.stream.control.ControlLogFixture.awaitTrue;
import static org.pragmatica.aether.stream.control.ControlLogFixture.land;
import static org.pragmatica.aether.stream.control.ControlLogFixture.latestReady;
import static org.pragmatica.aether.stream.control.ControlLogFixture.owner;
import static org.pragmatica.aether.stream.control.ControlLogFixture.record;
import static org.pragmatica.aether.stream.control.ControlLogFixture.replica;
import static org.pragmatica.aether.stream.control.ControlLogFixture.valueOf;
import static org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager;


/// #2590 P1: the latest-per-key index -- what feeds it (committed offsets only, on the owner and on a replica), and what
/// rebuilds it (a materialization, a truncation) or refuses to (a hole). Every test reads the index through the public
/// [ControlLog#latest] of a control log over a real partition manager.
class ControlLogIndexTest {
    @Nested
    class Feed {
        /// Behaviour 1. Mutation: the index keeps the first record of a key (`putIfAbsent`).
        @Test
        void index_twoRecordsSameKey_latestIsHigherOffset() {
            try (var node = owner(1, 1)) {
                appendReady(node, record("k", 1, 1));
                appendReady(node, record("other", 1, 1));
                appendReady(node, record("k", 1, 2));

                awaitTrue("the third record is indexed",
                          () -> latestReady(node, "k").map(IndexedRecord::offset)
                                                      .or(-1L) == 2L);
                var latest = latestReady(node, "k").unwrap();

                assertThat(latest.offset()).isEqualTo(2L);
                assertThat(valueOf(latest).seq()).isEqualTo(2L);
            }
        }

        /// Behaviour 2. A replica holds three records and the owner has announced only the first committed: the index
        /// holds the first alone. Mutation: apply on what the replica holds (its head) instead of the committed offset.
        @Test
        void replicaIndex_appliesOnlyThroughCommittedOffset() {
            try (var node = replica(3, 2)) {
                assertThat(latestReady(node, "a").isEmpty()).as("control: an empty valid index").isTrue();
                land(node, record("a", 1, 1));
                land(node, record("b", 1, 1));
                land(node, record("c", 1, 1));

                node.manager()
                    .commitAdvanced(STREAM, PARTITION, 0L, EPOCH_1);
                awaitTrue("the committed record is indexed", () -> latestReady(node, "a").isPresent());

                assertThat(latestReady(node, "b").isEmpty()).as("held, not committed: not indexed").isTrue();
                assertThat(latestReady(node, "c").isEmpty()).as("held, not committed: not indexed").isTrue();

                node.manager()
                    .commitAdvanced(STREAM, PARTITION, 2L, EPOCH_1);
                awaitTrue("the rest is indexed once committed", () -> latestReady(node, "c").isPresent());
                assertThat(latestReady(node, "b").map(IndexedRecord::offset)).isEqualTo(Option.some(1L));
            }
        }

        /// Behaviour 3. The owner writes through the control log; a replica lands the same records and learns the owner's
        /// committed position through `commitAdvanced`, the production sink of the replication receive handler's
        /// `CommitAdvance`. Its index, built empty BEFORE the records arrive, must equal the owner's. Mutation: feed the index
        /// on the owner path only (no committed event from `commitAdvanced`).
        @Test
        void replicaIndex_afterCommitAdvance_equalsOwnerIndex() {
            try (var ownerNode = owner(1, 1); var replicaNode = replica(3, 2)) {
                assertThat(latestReady(replicaNode, "k1").isEmpty()).as("control: the replica's index is valid and empty")
                                                                    .isTrue();
                appendReady(ownerNode, record("k1", 1, 1));
                appendReady(ownerNode, record("k2", 1, 1));
                appendReady(ownerNode, record("k1", 1, 2));
                appendReady(ownerNode, record("k3", 1, 1));
                appendReady(ownerNode, ControlRecord.tombstone("k3", 9L));
                var events = ownerNode.manager()
                                      .readAppended(STREAM, PARTITION, 0L, 100)
                                      .unwrap();

                events.forEach(event -> replicaNode.manager()
                                                   .appendRecovered(STREAM, PARTITION, event.data(), event.timestamp())
                                                   .onFailure(cause -> fail(cause.message())));
                replicaNode.manager()
                           .commitAdvanced(STREAM, PARTITION, events.getLast().offset(), EPOCH_1);

                for (var key : List.of("k1", "k2", "k3")) {
                    awaitTrue("the replica's " + key + " matches the owner's",
                              () -> latestReady(replicaNode, key).equals(latestReady(ownerNode, key)));
                }
                assertThat(latestReady(replicaNode, "k1").map(IndexedRecord::offset)).isEqualTo(Option.some(2L));
                assertThat(latestReady(replicaNode, "k3").isEmpty()).as("the tombstone applied on both").isTrue();
            }
        }
    }

    @Nested
    class Rebuild {
        @TempDir
        Path walDir;

        /// Behaviour 4. A WAL-backed owner restarts: the new process's index is rebuilt from the log it recovers.
        /// Mutation: the rebuild installs without replaying the log.
        @Test
        void materialize_afterRestart_rebuildsIndexFromLog() {
            try (var before = owner(1, 1, MirrorView.EMPTY, Option.some(walDir), 1L)) {
                appendReady(before, record("k1", 1, 1));
                appendReady(before, record("k2", 1, 1));
                appendReady(before, record("k1", 1, 2));
            }

            try (var after = owner(1, 1, MirrorView.EMPTY, Option.some(walDir), 1L)) {
                assertThat(ControlLogFixture.ringHead(after)).as("control: the WAL was recovered").isEqualTo(2L);

                awaitTrue("the restarted index holds k1", () -> latestReady(after, "k1").isPresent());
                assertThat(latestReady(after, "k1").map(IndexedRecord::offset)).isEqualTo(Option.some(2L));
                assertThat(latestReady(after, "k2").map(IndexedRecord::offset)).isEqualTo(Option.some(1L));
            }
        }

        /// Behaviour 6. A suffix truncation of the ring (#1730) removes an indexed record: the ring's truncation hook
        /// invalidates the index and the rebuild rolls it back. Mutation: no truncation hook.
        @Test
        void truncation_ofIndexedSuffix_rebuildsIndex() {
            try (var node = replica(3, 2)) {
                land(node, record("k", 1, 1));
                land(node, record("x", 1, 1));
                land(node, record("k", 1, 2));
                node.manager()
                    .commitAdvanced(STREAM, PARTITION, 2L, EPOCH_1);
                awaitTrue("k is indexed at its second record",
                          () -> latestReady(node, "k").map(IndexedRecord::offset)
                                                      .or(-1L) == 2L);

                node.manager()
                    .partitionBuffer(STREAM, PARTITION)
                    .unwrap()
                    .truncateSuffix(0L)
                    .onFailure(cause -> fail(cause.message()));

                assertThat(latestReady(node, "k").map(IndexedRecord::offset)).as("rolled back to the record the log kept")
                                                                            .isEqualTo(Option.some(0L));
                assertThat(latestReady(node, "x").isEmpty()).as("the truncated record is gone").isTrue();
            }
        }

        /// Behaviour 7. A rebuild over a retained log with a hole (a replica missing a sealed segment below its ring) is
        /// refused and the index stays INVALID until the hole is closed. Mutation: ignore holes (no contiguity check).
        @Test
        void rebuild_overSealedHole_staysInvalid() {
            var hole = new AtomicBoolean(true);
            var reads = new AtomicInteger();

            try (var node = replicaOver(log -> withHole(log, hole, reads))) {
                land(node, record("k", 1, 1));
                land(node, record("x", 1, 1));
                land(node, record("k", 1, 2));
                node.manager()
                    .commitAdvanced(STREAM, PARTITION, 2L, EPOCH_1);

                assertThat(node.controlLog()
                               .latest(STREAM, PARTITION, "k")
                               .isFailure()).as("the first read starts the rebuild")
                                            .isTrue();
                awaitTrue("the rebuild read the holed log and gave up",
                          () -> reads.get() > 0 && node.state()
                                                       .phase() == PartitionControlState.Phase.INVALID);

                assertThat(node.controlLog()
                               .latest(STREAM, PARTITION, "k")
                               .isFailure()).as("an index over a hole is not served")
                                            .isTrue();
                awaitTrue("the retried rebuild gave up again",
                          () -> reads.get() > 1 && node.state()
                                                       .phase() == PartitionControlState.Phase.INVALID);

                hole.set(false);

                assertThat(latestReady(node, "k").map(IndexedRecord::offset)).as("the backfill closed the hole")
                                                                            .isEqualTo(Option.some(2L));
            }
        }

        /// [v3:R3.3]: an INVALID index is never read as an empty one -- a read fails with INDEX_UNAVAILABLE, so a caller
        /// can never fall through to a legacy record. Mutation: answer an invalid index as empty.
        @Test
        void latest_whileIndexInvalid_failsIndexUnavailableNeverEmpty() {
            var hole = new AtomicBoolean(true);

            try (var node = replicaOver(log -> withHole(log, hole, new AtomicInteger()))) {
                land(node, record("k", 1, 1));
                land(node, record("x", 1, 1));
                node.manager()
                    .commitAdvanced(STREAM, PARTITION, 1L, EPOCH_1);

                for (var attempt = 0; attempt < 3; attempt++) {
                    node.controlLog()
                        .latest(STREAM, PARTITION, "k")
                        .onSuccess(found -> fail("an invalid index answered " + found))
                        .onFailure(cause -> assertThat(cause).isEqualTo(ControlLogError.General.INDEX_UNAVAILABLE));
                    ControlLogFixture.sleepBriefly();
                }
            }
        }
    }

    private static ControlLogFixture.Node replicaOver(UnaryOperator<ControlPartitionLog> wrap) {
        var replication = replicationManager(SELF, ControlLogFixture.registry(3));
        var manager = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replication);

        var node = ControlLogFixture.node(manager,
                                          replication,
                                          MirrorView.EMPTY,
                                          1L,
                                          ReplicaSetController.Role.REPLICA,
                                          wrap);

        ControlLogFixture.createStream(manager, 3, 2);

        return node;
    }

    /// The real log, except that while `hole` is set a read drops offset 1, as a replica missing the sealed segment holding
    /// it would answer.
    private static ControlPartitionLog withHole(ControlPartitionLog log, AtomicBoolean hole, AtomicInteger reads) {
        return new ForwardingLog(log) {
            @Override
            public Promise<List<RawEvent>> read(String streamName, int partition, long fromOffset, int maxEvents) {
                reads.incrementAndGet();

                return super.read(streamName, partition, fromOffset, maxEvents)
                            .map(events -> hole.get()
                                           ? withoutOffsetOne(events)
                                           : events);
            }
        };
    }

    private static List<RawEvent> withoutOffsetOne(List<RawEvent> events) {
        var kept = new ArrayList<RawEvent>();

        events.stream()
              .filter(event -> event.offset() != 1L)
              .forEach(kept::add);
        return List.copyOf(kept);
    }

    /// A [ControlPartitionLog] delegating every call; a test overrides the one it changes.
    static class ForwardingLog implements ControlPartitionLog {
        private final ControlPartitionLog delegate;

        ForwardingLog(ControlPartitionLog delegate) {
            this.delegate = delegate;
        }

        @Override
        public Result<ControlRunAppend> appendRun(String streamName, int partition, List<byte[]> payloads, long timestamp) {
            return delegate.appendRun(streamName, partition, payloads, timestamp);
        }

        @Override
        public Promise<Unit> awaitConfirmed(String streamName, int partition, long lastOffset) {
            return delegate.awaitConfirmed(streamName, partition, lastOffset);
        }

        @Override
        public Promise<List<RawEvent>> read(String streamName, int partition, long fromOffset, int maxEvents) {
            return delegate.read(streamName, partition, fromOffset, maxEvents);
        }

        @Override
        public Result<List<RawEvent>> readRing(String streamName, int partition, long fromOffset, int maxEvents) {
            return delegate.readRing(streamName, partition, fromOffset, maxEvents);
        }

        @Override
        public long retainedFrom(String streamName, int partition) {
            return delegate.retainedFrom(streamName, partition);
        }

        @Override
        public long head(String streamName, int partition) {
            return delegate.head(streamName, partition);
        }

        @Override
        public long committedThrough(String streamName, int partition) {
            return delegate.committedThrough(streamName, partition);
        }

        @Override
        public Epoch ownerEpoch(String streamName, int partition) {
            return delegate.ownerEpoch(streamName, partition);
        }

        @Override
        public long isrVersion(String streamName, int partition) {
            return delegate.isrVersion(streamName, partition);
        }

        @Override
        public Option<Long> ringIncarnation(String streamName, int partition) {
            return delegate.ringIncarnation(streamName, partition);
        }
    }
}
