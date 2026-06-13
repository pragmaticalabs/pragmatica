// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.replication.PartitionBackfill.partitionBackfill;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupResponse.catchupResponse;

class PartitionBackfillTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId SELF = NodeId.randomNodeId();
    private static final NodeId SOURCE = NodeId.randomNodeId();

    private ReplicaRegistry registry;
    private StreamPartitionManager manager;
    private StreamPartitionRecovery recovery;

    @BeforeEach
    void setUp() {
        registry = replicaRegistry();
        manager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
        manager.createStream(StreamConfig.streamConfig("orders"));
        recovery = manager::appendRecovered;
    }

    @Nested
    class HappyPath {
        @Test
        void backfill_emptyReplica_appliesAllEventsOffsetPreserving_andFlipsCaughtUp() {
            var m = 5;
            // Source is a CAUGHT_UP peer at watermark m-1; self is freshly registered (SYNCING, -1).
            registry.registerReplica(STREAM, PARTITION, SOURCE);
            registry.updateWatermark(STREAM, PARTITION, SOURCE, m - 1);
            registry.registerReplica(STREAM, PARTITION, SELF);

            var transport = fixedSource(eventsFrom(0, m));
            var backfill = partitionBackfill(registry, recovery, transport, SELF);

            var applied = await(backfill.backfill(STREAM, PARTITION));
            assertThat(applied).isEqualTo((long) m);

            // Local partition now holds all M events, offsets 0..m-1 preserved.
            var local = manager.readLocal(STREAM, PARTITION, 0, 100)
                               .or(List.of());
            assertThat(local).hasSize(m);
            for (var i = 0; i < m; i++) {
                assertThat(local.get(i).offset()).isEqualTo((long) i);
                assertThat(new String(local.get(i).data())).isEqualTo("event-" + i);
            }

            // Self descriptor flipped to CAUGHT_UP at the source watermark.
            var self = descriptorFor(SELF);
            assertThat(self.state()).isEqualTo(ReplicationState.CAUGHT_UP);
            assertThat(self.confirmedOffset()).isEqualTo((long) (m - 1));
        }
    }

    @Nested
    class SourceSelection {
        @Test
        void backfill_noSource_failsAndStaysSyncing() {
            // Only self registered: no caught-up peer to pull from.
            registry.registerReplica(STREAM, PARTITION, SELF);
            var transport = fixedSource(eventsFrom(0, 3));
            var backfill = partitionBackfill(registry, recovery, transport, SELF);

            var result = backfill.backfill(STREAM, PARTITION)
                                 .await();

            assertThat(result.isFailure()).isTrue();
            assertThat(descriptorFor(SELF).state()).isEqualTo(ReplicationState.SYNCING);
            // Nothing applied locally.
            assertThat(manager.readLocal(STREAM, PARTITION, 0, 100).or(List.of())).isEmpty();
        }

        @Test
        void backfill_onlySyncingPeers_failsAndStaysSyncing() {
            // A peer exists but is still SYNCING (never updated to CAUGHT_UP) — not a valid source.
            registry.registerReplica(STREAM, PARTITION, SOURCE);
            registry.registerReplica(STREAM, PARTITION, SELF);
            var backfill = partitionBackfill(registry, recovery, fixedSource(eventsFrom(0, 3)), SELF);

            assertThat(backfill.backfill(STREAM, PARTITION).await().isFailure()).isTrue();
            assertThat(descriptorFor(SELF).state()).isEqualTo(ReplicationState.SYNCING);
        }
    }

    @Nested
    class FailureSafety {
        @Test
        void backfill_sourceUnreachable_failsWithoutCorruption_staysSyncing() {
            registry.registerReplica(STREAM, PARTITION, SOURCE);
            registry.updateWatermark(STREAM, PARTITION, SOURCE, 4L);
            registry.registerReplica(STREAM, PARTITION, SELF);

            CatchupTransport unreachable = (_, _) ->
                    ReplicationError.General.REPLICATION_TIMEOUT.promise();
            var backfill = partitionBackfill(registry, recovery, unreachable, SELF);

            var result = backfill.backfill(STREAM, PARTITION).await();

            assertThat(result.isFailure()).isTrue();
            // Local partition untouched and self remains SYNCING (not falsely CAUGHT_UP).
            assertThat(manager.readLocal(STREAM, PARTITION, 0, 100).or(List.of())).isEmpty();
            assertThat(descriptorFor(SELF).state()).isEqualTo(ReplicationState.SYNCING);
        }
    }

    @Nested
    class ReadPathExclusion {
        @Test
        void syncingReplica_isExcludedBySelectionPredicate_caughtUpIsIncluded() {
            registry.registerReplica(STREAM, PARTITION, SELF);       // SYNCING by registration
            registry.registerReplica(STREAM, PARTITION, SOURCE);
            registry.updateWatermark(STREAM, PARTITION, SOURCE, 9L); // SOURCE -> CAUGHT_UP

            var caughtUp = registry.replicasFor(STREAM, PARTITION).stream()
                                   .filter(d -> d.state() == ReplicationState.CAUGHT_UP)
                                   .map(ReplicaDescriptor::nodeId)
                                   .toList();

            assertThat(caughtUp).containsExactly(SOURCE);
            assertThat(caughtUp).doesNotContain(SELF);
        }
    }

    @Nested
    class DurabilityB2 {
        @Test
        void backfill_truncatedResponse_payloadTimestampMismatch_failsAndStaysSyncing() {
            // Source is CAUGHT_UP at watermark 4; self fresh.
            registry.registerReplica(STREAM, PARTITION, SOURCE);
            registry.updateWatermark(STREAM, PARTITION, SOURCE, 4L);
            registry.registerReplica(STREAM, PARTITION, SELF);

            // Malformed/truncated response: 3 payloads but only 2 timestamps. toOffset still claims the
            // source watermark (4) — the false-ready trap. Must be treated as a parse failure.
            CatchupTransport truncated = (target, request) -> {
                var payloads = new ArrayList<byte[]>(List.of("a".getBytes(), "b".getBytes(), "c".getBytes()));
                var timestamps = new ArrayList<Long>(List.of(1000L, 1001L));
                return Promise.success(catchupResponse(target,
                                                       request.streamName(),
                                                       request.partition(),
                                                       request.fromOffset(),
                                                       4L,
                                                       payloads,
                                                       timestamps));
            };
            var backfill = partitionBackfill(registry, recovery, truncated, SELF);

            var result = backfill.backfill(STREAM, PARTITION).await();

            assertThat(result.isFailure()).isTrue();
            // No promotion: self stays SYNCING, nothing partially applied.
            assertThat(descriptorFor(SELF).state()).isEqualTo(ReplicationState.SYNCING);
            assertThat(manager.readLocal(STREAM, PARTITION, 0, 100).or(List.of())).isEmpty();
        }

        @Test
        void backfill_appliedBelowWatermark_failsAndStaysSyncing_noFalseReady() {
            // Source watermark is 9 but the response only carries events 0..2 (a short/holey page that
            // does not reach the watermark). Applying them must NOT promote to CAUGHT_UP@9.
            registry.registerReplica(STREAM, PARTITION, SOURCE);
            registry.updateWatermark(STREAM, PARTITION, SOURCE, 9L);
            registry.registerReplica(STREAM, PARTITION, SELF);

            // Well-formed response (3 payloads, 3 timestamps) but toOffset=2 while source watermark=9.
            var transport = fixedSource(eventsFrom(0, 3));
            var backfill = partitionBackfill(registry, recovery, transport, SELF);

            var result = backfill.backfill(STREAM, PARTITION).await();

            assertThat(result.isFailure()).isTrue();
            // Highest applied offset (2) < watermark (9) => no promotion; stays SYNCING for a re-run.
            assertThat(descriptorFor(SELF).state()).isEqualTo(ReplicationState.SYNCING);
            assertThat(descriptorFor(SELF).confirmedOffset()).isEqualTo(-1L);
        }
    }

    /// Cold-start deadlock-break: after a SIMULTANEOUS full-cluster restart every replica is SYNCING and
    /// no caught-up source can exist, so each replica waits forever. Once the bounded wait elapses the
    /// highest-watermark replica self-promotes — but ONLY when it can prove every co-replica is reachable
    /// and it wins the highest-watermark contest (deterministic lowest-NodeId tie-break). An unreachable
    /// co-replica blocks promotion (data safety: it might hold newer state).
    @Nested
    class ColdStartSelfPromotion {
        // Deterministic NodeIds so the lowest-NodeId tie-break is reproducible: aa < bb < cc.
        private static final NodeId NODE_AA = NodeId.nodeId("node-aa").unwrap();
        private static final NodeId NODE_BB = NodeId.nodeId("node-bb").unwrap();
        private static final NodeId NODE_CC = NodeId.nodeId("node-cc").unwrap();
        private static final TimeSpan BOUND = TimeSpan.timeSpan(10).seconds();

        @Test
        void backfill_allReplicasSyncing_boundElapsed_allReachable_highestWatermark_selfPromotes() {
            // All three replicas SYNCING (registered, never CAUGHT_UP). Self holds the highest local
            // watermark (8) and both peers are reachable at lower watermarks (5, 3).
            registry.registerReplica(STREAM, PARTITION, NODE_BB);
            registry.registerReplica(STREAM, PARTITION, NODE_CC);
            registry.registerReplica(STREAM, PARTITION, NODE_AA); // self

            var clock = new AtomicLong(0L);
            var probe = reachableProbe(NODE_BB, 5L, NODE_CC, 3L);
            var backfill = partitionBackfill(registry, recovery, CatchupTransport.NOOP, probe, selfWatermarkOf(8L), NODE_AA, BOUND, clock::get);

            // Before the bound: stays SYNCING, no promotion.
            assertThat(backfill.backfill(STREAM, PARTITION).await().isFailure()).isTrue();
            assertThat(descriptorFor(NODE_AA).state()).isEqualTo(ReplicationState.SYNCING);

            // After the bound: self-promotes (highest watermark, all reachable).
            clock.set(BOUND.millis() + 1);
            var applied = backfill.backfill(STREAM, PARTITION).await();
            assertThat(applied.isSuccess()).isTrue();
            assertThat(descriptorFor(NODE_AA).state()).isEqualTo(ReplicationState.CAUGHT_UP);
            assertThat(descriptorFor(NODE_AA).confirmedOffset()).isEqualTo(8L);
            // Peers are untouched — only the winner promotes.
            assertThat(descriptorFor(NODE_BB).state()).isEqualTo(ReplicationState.SYNCING);
            assertThat(descriptorFor(NODE_CC).state()).isEqualTo(ReplicationState.SYNCING);
        }

        @Test
        void backfill_equalWatermarks_onlyLowestNodeIdPromotes_othersStaySyncing() {
            // Two replicas tie at watermark 7. Only the lowest NodeId (NODE_AA) may promote.
            registry.registerReplica(STREAM, PARTITION, NODE_AA);
            registry.registerReplica(STREAM, PARTITION, NODE_BB);

            var clock = new AtomicLong(0L);

            // NODE_AA (lowest id) sees NODE_BB tied at 7, all reachable -> promotes once the bound elapses.
            var lowest = partitionBackfill(registry,
                                           recovery,
                                           CatchupTransport.NOOP,
                                           reachableProbe(NODE_BB, 7L),
                                           selfWatermarkOf(7L),
                                           NODE_AA,
                                           BOUND,
                                           clock::get);
            lowest.backfill(STREAM, PARTITION).await();   // arm the bound at t=0
            clock.set(BOUND.millis() + 1);                // bound elapsed
            assertThat(lowest.backfill(STREAM, PARTITION).await().isSuccess()).isTrue();
            assertThat(descriptorFor(NODE_AA).state()).isEqualTo(ReplicationState.CAUGHT_UP);

            // Reset NODE_AA back to SYNCING to model the symmetric decision NODE_BB makes independently.
            registry.registerReplica(STREAM, PARTITION, NODE_AA);

            var clockB = new AtomicLong(0L);
            // NODE_BB (higher id) sees NODE_AA tied at 7 -> loses the tie-break, stays SYNCING.
            var higher = partitionBackfill(registry,
                                           recovery,
                                           CatchupTransport.NOOP,
                                           reachableProbe(NODE_AA, 7L),
                                           selfWatermarkOf(7L),
                                           NODE_BB,
                                           BOUND,
                                           clockB::get);
            higher.backfill(STREAM, PARTITION).await();   // arm
            clockB.set(BOUND.millis() + 1);               // bound elapsed
            assertThat(higher.backfill(STREAM, PARTITION).await().isFailure()).isTrue();
            assertThat(descriptorFor(NODE_BB).state()).isEqualTo(ReplicationState.SYNCING);
        }

        @Test
        void backfill_oneReplicaUnreachable_doesNotSelfPromote_staysSyncing_dataSafety() {
            // Self holds the highest SEEN watermark (8) but one co-replica is UNREACHABLE — it might hold
            // newer state, so self must NOT promote past it.
            registry.registerReplica(STREAM, PARTITION, NODE_BB);
            registry.registerReplica(STREAM, PARTITION, NODE_CC);
            registry.registerReplica(STREAM, PARTITION, NODE_AA); // self

            var clock = new AtomicLong(0L);
            ReplicaWatermarkProbe probe = (target, _, _) ->
                    target.equals(NODE_BB)
                    ? Promise.success(5L)
                    : ReplicationError.General.REPLICATION_TIMEOUT.promise(); // NODE_CC unreachable
            var backfill = partitionBackfill(registry, recovery, CatchupTransport.NOOP, probe, selfWatermarkOf(8L), NODE_AA, BOUND, clock::get);

            backfill.backfill(STREAM, PARTITION).await();   // arm the bound at t=0
            clock.set(BOUND.millis() + 1);                  // bound elapsed -> promotion attempted
            assertThat(backfill.backfill(STREAM, PARTITION).await().isFailure()).isTrue();
            // Data safety: stays SYNCING despite locally-highest watermark, because a peer is unreachable.
            assertThat(descriptorFor(NODE_AA).state()).isEqualTo(ReplicationState.SYNCING);
            assertThat(descriptorFor(NODE_AA).confirmedOffset()).isEqualTo(-1L);
        }

        @Test
        void backfill_caughtUpSourceExists_normalBackfill_noPromotionPathTaken() {
            // A genuine CAUGHT_UP source exists: the normal backfill path runs and is byte-identical to
            // the pre-fix behavior — the probe/promotion machinery is never consulted.
            registry.registerReplica(STREAM, PARTITION, SOURCE);
            registry.updateWatermark(STREAM, PARTITION, SOURCE, 4L);
            registry.registerReplica(STREAM, PARTITION, SELF);

            var clock = new AtomicLong(BOUND.millis() + 1); // bound already elapsed — must not matter
            ReplicaWatermarkProbe failIfProbed = (_, _, _) -> {
                throw new AssertionError("probe must not be consulted when a caught-up source exists");
            };
            SelfWatermark failIfRead = (_, _) -> {
                throw new AssertionError("self-watermark must not be read on the normal path");
            };
            var backfill = partitionBackfill(registry,
                                             recovery,
                                             fixedSource(eventsFrom(0, 5)),
                                             failIfProbed,
                                             failIfRead,
                                             SELF,
                                             BOUND,
                                             clock::get);

            var applied = backfill.backfill(STREAM, PARTITION).await();
            assertThat(applied.isSuccess()).isTrue();
            assertThat(applied.or(-1L)).isEqualTo(5L);
            assertThat(descriptorFor(SELF).state()).isEqualTo(ReplicationState.CAUGHT_UP);
            assertThat(descriptorFor(SELF).confirmedOffset()).isEqualTo(4L);
        }

        @Test
        void backfill_boundNotYetElapsed_noPromotion_staysSyncing() {
            // All SYNCING, self has the highest watermark and all peers reachable, but the bounded wait
            // has NOT elapsed yet — symmetry must not be broken early (staggered survivor may still appear).
            registry.registerReplica(STREAM, PARTITION, NODE_BB);
            registry.registerReplica(STREAM, PARTITION, NODE_AA); // self

            var clock = new AtomicLong(0L);
            ReplicaWatermarkProbe failIfProbed = (_, _, _) -> {
                throw new AssertionError("probe must not run before the bound elapses");
            };
            var backfill = partitionBackfill(registry,
                                             recovery,
                                             CatchupTransport.NOOP,
                                             failIfProbed,
                                             selfWatermarkOf(9L),
                                             NODE_AA,
                                             BOUND,
                                             clock::get);

            clock.set(BOUND.millis() - 1); // still inside the wait window
            assertThat(backfill.backfill(STREAM, PARTITION).await().isFailure()).isTrue();
            assertThat(descriptorFor(NODE_AA).state()).isEqualTo(ReplicationState.SYNCING);
        }

        @Test
        void backfill_loneReplica_noPeers_boundElapsed_selfPromotes() {
            // Self is the ONLY registered replica of the partition. After a full restart there is no peer
            // that could hold newer state, so once the bound elapses self may safely self-promote (no
            // probe needed — the peer set is empty).
            registry.registerReplica(STREAM, PARTITION, NODE_AA); // self, sole replica

            var clock = new AtomicLong(0L);
            var backfill = partitionBackfill(registry,
                                             recovery,
                                             CatchupTransport.NOOP,
                                             reachableProbe(NODE_BB, 99L),  // never invoked: no peers
                                             selfWatermarkOf(3L),
                                             NODE_AA,
                                             BOUND,
                                             clock::get);

            backfill.backfill(STREAM, PARTITION).await();   // arm the bound at t=0
            clock.set(BOUND.millis() + 1);                  // bound elapsed
            assertThat(backfill.backfill(STREAM, PARTITION).await().isSuccess()).isTrue();
            assertThat(descriptorFor(NODE_AA).state()).isEqualTo(ReplicationState.CAUGHT_UP);
            assertThat(descriptorFor(NODE_AA).confirmedOffset()).isEqualTo(3L);
        }

        @Test
        void backfill_peerHasHigherWatermark_allReachable_doesNotPromote() {
            // All reachable, but a peer holds a strictly higher watermark than self — self must defer to it.
            registry.registerReplica(STREAM, PARTITION, NODE_BB);
            registry.registerReplica(STREAM, PARTITION, NODE_AA); // self

            var clock = new AtomicLong(0L);
            var backfill = partitionBackfill(registry,
                                             recovery,
                                             CatchupTransport.NOOP,
                                             reachableProbe(NODE_BB, 20L),
                                             selfWatermarkOf(8L),
                                             NODE_AA,
                                             BOUND,
                                             clock::get);

            backfill.backfill(STREAM, PARTITION).await();   // arm the bound at t=0
            clock.set(BOUND.millis() + 1);                  // bound elapsed -> promotion attempted, then declined
            assertThat(backfill.backfill(STREAM, PARTITION).await().isFailure()).isTrue();
            assertThat(descriptorFor(NODE_AA).state()).isEqualTo(ReplicationState.SYNCING);
        }

        private SelfWatermark selfWatermarkOf(long watermark) {
            return (_, _) -> watermark;
        }

        private ReplicaWatermarkProbe reachableProbe(NodeId a, long wmA) {
            return (target, _, _) -> target.equals(a)
                                     ? Promise.success(wmA)
                                     : ReplicationError.General.REPLICATION_TIMEOUT.promise();
        }

        private ReplicaWatermarkProbe reachableProbe(NodeId a, long wmA, NodeId b, long wmB) {
            return (target, _, _) -> probeOf(target, a, wmA, b, wmB);
        }

        private Promise<Long> probeOf(NodeId target, NodeId a, long wmA, NodeId b, long wmB) {
            if (target.equals(a)) {
                return Promise.success(wmA);
            }

            return target.equals(b)
                   ? Promise.success(wmB)
                   : ReplicationError.General.REPLICATION_TIMEOUT.promise();
        }
    }

    private ReplicaDescriptor descriptorFor(NodeId nodeId) {
        return registry.replicasFor(STREAM, PARTITION).stream()
                       .filter(d -> d.nodeId().equals(nodeId))
                       .findFirst()
                       .orElseThrow();
    }

    private CatchupTransport fixedSource(List<EventData> events) {
        return (target, request) -> {
            var payloads = new ArrayList<byte[]>();
            var timestamps = new ArrayList<Long>();
            events.forEach(event -> {
                payloads.add(event.data());
                timestamps.add(event.timestamp());
            });
            var toOffset = events.isEmpty() ? request.fromOffset() - 1 : events.getLast().offset();
            return Promise.success(catchupResponse(target,
                                                   request.streamName(),
                                                   request.partition(),
                                                   request.fromOffset(),
                                                   toOffset,
                                                   payloads,
                                                   timestamps));
        };
    }

    private static long await(Promise<Long> promise) {
        return promise.await().or(-1L);
    }

    private static List<EventData> eventsFrom(long startOffset, int count) {
        var events = new ArrayList<EventData>(count);
        for (var i = 0; i < count; i++) {
            var offset = startOffset + i;
            events.add(new EventData(offset, 1000L + offset, ("event-" + offset).getBytes()));
        }
        return events;
    }

    private record EventData(long offset, long timestamp, byte[] data) {}
}
