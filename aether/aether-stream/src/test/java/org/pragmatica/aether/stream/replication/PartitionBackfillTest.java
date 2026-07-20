// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.CommittedStreamOwnerSource;
import org.pragmatica.aether.stream.CommittedStreamOwnerSource.CommittedOwner;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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

    /// Fix B (owner-immediate cold-start break): the HRW owner of a partition is authoritative for its own
    /// data, so when self IS the owner and no caught-up peer source exists it self-promotes IMMEDIATELY at
    /// its local watermark — WITHOUT waiting out the source-wait bound. A non-owner still observes the
    /// existing bounded-wait behavior. Membership is supplied via the explicit-member test factory so the
    /// HRW owner can be computed deterministically.
    @Nested
    class OwnerImmediateSelfPromotion {
        private static final NodeId NODE_AA = NodeId.nodeId("node-aa").unwrap();
        private static final NodeId NODE_BB = NodeId.nodeId("node-bb").unwrap();
        private static final NodeId NODE_CC = NodeId.nodeId("node-cc").unwrap();
        private static final TimeSpan BOUND = TimeSpan.timeSpan(10).seconds();
        private static final List<NodeId> MEMBERS = List.of(NODE_AA, NODE_BB, NODE_CC);
        // HRW owner of (STREAM, PARTITION) across MEMBERS — deterministic, computed from the same ranking
        // the production code uses. The owner self-promotes immediately; a NON-owner does not.
        private static final NodeId OWNER = ReplicaPlacement.rank(STREAM, PARTITION, MEMBERS).getFirst();
        private static final NodeId NON_OWNER = ReplicaPlacement.rank(STREAM, PARTITION, MEMBERS).getLast();

        @Test
        void backfill_selfIsOwner_noSource_selfPromotesImmediately_withoutAdvancingClock() {
            // Owner is a SYNCING replica with NO caught-up peer source. Clock stays at 0 (bound NOT
            // elapsed) — the owner must STILL promote immediately at its local watermark.
            registry.registerReplica(STREAM, PARTITION, OWNER); // self == owner, SYNCING

            var clock = new AtomicLong(0L);
            ReplicaWatermarkProbe failIfProbed = (_, _, _) -> {
                throw new AssertionError("owner must not probe peers — it self-promotes immediately");
            };
            var backfill = partitionBackfill(registry,
                                             recovery,
                                             CatchupTransport.NOOP,
                                             failIfProbed,
                                             selfWatermarkOf(7L),
                                             OWNER,
                                             BOUND,
                                             clock::get,
                                             () -> MEMBERS);

            var applied = backfill.backfill(STREAM, PARTITION).await();

            assertThat(applied.isSuccess()).isTrue();
            // Clock never advanced past the bound, yet the owner is CAUGHT_UP at its local watermark.
            assertThat(clock.get()).isZero();
            assertThat(descriptorFor(OWNER).state()).isEqualTo(ReplicationState.CAUGHT_UP);
            assertThat(descriptorFor(OWNER).confirmedOffset()).isEqualTo(7L);
        }

        @Test
        void backfill_selfIsNonOwner_noSource_staysSyncingUntilBound() {
            // A NON-owner replica must NOT take the owner-immediate path. Here the owner source is a NOOP
            // transport (returns nothing past fromOffset), so backfill fails-soft to SYNCING — the highest
            // applied offset stays below the response watermark, so there is NO false-promote, and the
            // cold-start probe path is never consulted. (Pre-#333 this asserted a pure bounded-wait; the
            // non-owner now attempts the authoritative owner source first, but still stays SYNCING when the
            // owner yields nothing.)
            registry.registerReplica(STREAM, PARTITION, OWNER);
            registry.registerReplica(STREAM, PARTITION, NON_OWNER); // self

            var clock = new AtomicLong(0L);
            ReplicaWatermarkProbe failIfProbed = (_, _, _) -> {
                throw new AssertionError("non-owner must not probe peers on the owner-source path");
            };
            var backfill = partitionBackfill(registry,
                                             recovery,
                                             CatchupTransport.NOOP,
                                             failIfProbed,
                                             selfWatermarkOf(9L),
                                             NON_OWNER,
                                             BOUND,
                                             clock::get,
                                             () -> MEMBERS);

            clock.set(BOUND.millis() - 1); // still inside the wait window
            assertThat(backfill.backfill(STREAM, PARTITION).await().isFailure()).isTrue();
            assertThat(descriptorFor(NON_OWNER).state()).isEqualTo(ReplicationState.SYNCING);
            assertThat(descriptorFor(NON_OWNER).confirmedOffset()).isEqualTo(-1L);
        }

        @Test
        void backfill_selfIsHrwOwner_butCommittedOwnerElsewhere_doesNotSelfPromote_staysSyncing() {
            // #491 F4: self HRW-ranks itself owner (empty/partial ring), but the committed ownership record
            // names a DIFFERENT node — the m1 divergence. The committed-owner gate must BLOCK the owner-
            // immediate self-promote: self stays SYNCING at -1 instead of falsely promoting at its local
            // watermark and looping catch-up against itself while the authoritative owner is elsewhere.
            registry.registerReplica(STREAM, PARTITION, OWNER); // self == HRW owner, SYNCING

            var clock = new AtomicLong(0L);
            ReplicaWatermarkProbe failIfProbed = (_, _, _) -> {
                throw new AssertionError("blocked self-election must not reach the probe path within the bound");
            };
            var backfill = partitionBackfill(registry,
                                             recovery,
                                             CatchupTransport.NOOP,
                                             failIfProbed,
                                             selfWatermarkOf(7L),
                                             OWNER,
                                             BOUND,
                                             clock::get,
                                             () -> MEMBERS,
                                             committedOwnerIs(NON_OWNER));

            assertThat(backfill.backfill(STREAM, PARTITION).await().isFailure())
                .as("committed owner elsewhere blocks the HRW self-promote — backfill stays SYNCING")
                .isTrue();
            assertThat(descriptorFor(OWNER).state()).isEqualTo(ReplicationState.SYNCING);
            assertThat(descriptorFor(OWNER).confirmedOffset()).isEqualTo(-1L);
        }

        @Test
        void backfill_selfIsHrwOwner_committedOwnerIsSelf_selfPromotesImmediately() {
            // #491 F4 permissive arm: when the committed record names SELF, the gate is a no-op and the
            // owner-immediate self-promote fires exactly as with no committed record — a legitimately-
            // committed owner is never starved.
            registry.registerReplica(STREAM, PARTITION, OWNER);

            var clock = new AtomicLong(0L);
            ReplicaWatermarkProbe failIfProbed = (_, _, _) -> {
                throw new AssertionError("committed owner == self self-promotes immediately — no probe");
            };
            var backfill = partitionBackfill(registry,
                                             recovery,
                                             CatchupTransport.NOOP,
                                             failIfProbed,
                                             selfWatermarkOf(7L),
                                             OWNER,
                                             BOUND,
                                             clock::get,
                                             () -> MEMBERS,
                                             committedOwnerIs(OWNER));

            assertThat(backfill.backfill(STREAM, PARTITION).await().isSuccess())
                .as("committed owner == self keeps the owner-immediate self-promote")
                .isTrue();
            assertThat(descriptorFor(OWNER).state()).isEqualTo(ReplicationState.CAUGHT_UP);
            assertThat(descriptorFor(OWNER).confirmedOffset()).isEqualTo(7L);
        }

        private static CommittedStreamOwnerSource committedOwnerIs(NodeId owner) {
            return (_, _) -> Option.some(new CommittedOwner(owner, Epoch.ZERO));
        }

        private SelfWatermark selfWatermarkOf(long watermark) {
            return (_, _) -> watermark;
        }
    }

    /// #491 m2: when the #445 empty-owner path routes a NON-owner replica to the bounded no-source wait,
    /// the cold-start self-promote MUST be suppressed while a committed owner EXISTS — this is a FAILOVER
    /// (the owner is authoritative and its ring will fill), not a genuine cold-start deadlock. Promoting
    /// here would flip a false CAUGHT_UP at self's empty local watermark past the #445 distrust gate. The
    /// redrive's owner re-pull catches self up once the owner returns non-empty history (recovery arm).
    @Nested
    class EmptyOwnerFailoverPromotionGate {
        private static final NodeId NODE_AA = NodeId.nodeId("node-aa").unwrap();
        private static final NodeId NODE_BB = NodeId.nodeId("node-bb").unwrap();
        private static final NodeId NODE_CC = NodeId.nodeId("node-cc").unwrap();
        private static final TimeSpan BOUND = TimeSpan.timeSpan(10).seconds();
        private static final List<NodeId> MEMBERS = List.of(NODE_AA, NODE_BB, NODE_CC);
        private static final NodeId OWNER = ReplicaPlacement.rank(STREAM, PARTITION, MEMBERS).getFirst();
        private static final NodeId NON_OWNER = ReplicaPlacement.rank(STREAM, PARTITION, MEMBERS).getLast();

        @Test
        void backfill_emptyOwner_withinBound_staysSyncing_pinned() {
            // Arm (a) pin: NON-owner, empty owner response, clock INSIDE the bound → NO_SOURCE, SYNCING@-1.
            registry.registerReplica(STREAM, PARTITION, NON_OWNER); // self

            var clock = new AtomicLong(0L);
            var backfill = partitionBackfill(registry,
                                             recovery,
                                             fixedSource(List.of()), // owner ring still empty (#445)
                                             failIfProbed(),
                                             selfWatermarkOf(-1L),
                                             NON_OWNER,
                                             BOUND,
                                             clock::get,
                                             () -> MEMBERS,
                                             committedOwnerIs(OWNER));

            clock.set(BOUND.millis() - 1); // still inside the wait window
            assertThat(backfill.backfill(STREAM, PARTITION).await().isFailure())
                .as("within the bound the empty-owner replica stays SYNCING")
                .isTrue();
            assertThat(descriptorFor(NON_OWNER).state()).isEqualTo(ReplicationState.SYNCING);
            assertThat(descriptorFor(NON_OWNER).confirmedOffset()).isEqualTo(-1L);
        }

        @Test
        void backfill_emptyOwner_boundElapsed_committedOwnerPresent_staysSyncing_notColdStartPromoted() {
            // Arm (b) THE FIX: clock PAST the bound, owner STILL empty, a committed owner PRESENT ⇒ the
            // cold-start self-promote is SUPPRESSED (the probe is never reached). Pre-fix this degraded to a
            // false CAUGHT_UP@-1 self-promote past the #445 empty-owner distrust gate — the regression removed.
            registry.registerReplica(STREAM, PARTITION, NON_OWNER); // self

            var clock = new AtomicLong(0L);
            var backfill = partitionBackfill(registry,
                                             recovery,
                                             fixedSource(List.of()),
                                             failIfProbed(),
                                             selfWatermarkOf(-1L),
                                             NON_OWNER,
                                             BOUND,
                                             clock::get,
                                             () -> MEMBERS,
                                             committedOwnerIs(OWNER));

            clock.set(BOUND.millis() + 1); // bound elapsed
            assertThat(backfill.backfill(STREAM, PARTITION).await().isFailure())
                .as("committed owner present suppresses the cold-start self-promote — stays SYNCING")
                .isTrue();
            assertThat(descriptorFor(NON_OWNER).state())
                .as("no degraded CAUGHT_UP@-1 promote past the empty-owner distrust gate")
                .isEqualTo(ReplicationState.SYNCING);
            assertThat(descriptorFor(NON_OWNER).confirmedOffset()).isEqualTo(-1L);
        }

        @Test
        void backfill_ownerBecomesNonEmpty_promotesAtOwnerTail_recovery() {
            // Arm (c) recovery: the owner's ring fills (transport now returns 0..4) → backfill promotes
            // CAUGHT_UP at the owner's true tail, exactly as the redrive's owner re-pull would.
            registry.registerReplica(STREAM, PARTITION, NON_OWNER); // self

            var clock = new AtomicLong(BOUND.millis() + 1L); // bound already elapsed
            var backfill = partitionBackfill(registry,
                                             recovery,
                                             fixedSource(eventsFrom(0, 5)), // owner now holds 0..4
                                             failIfProbed(),
                                             selfWatermarkOf(-1L),
                                             NON_OWNER,
                                             BOUND,
                                             clock::get,
                                             () -> MEMBERS,
                                             committedOwnerIs(OWNER));

            assertThat(backfill.backfill(STREAM, PARTITION).await().isSuccess())
                .as("a non-empty owner response promotes at the owner tail")
                .isTrue();
            assertThat(descriptorFor(NON_OWNER).state()).isEqualTo(ReplicationState.CAUGHT_UP);
            assertThat(descriptorFor(NON_OWNER).confirmedOffset()).isEqualTo(4L);
        }

        private static ReplicaWatermarkProbe failIfProbed() {
            return (_, _, _) -> {
                throw new AssertionError("committed-owner gate must short-circuit before the cold-start probe");
            };
        }

        private static CommittedStreamOwnerSource committedOwnerIs(NodeId owner) {
            return (_, _) -> Option.some(new CommittedOwner(owner, Epoch.ZERO));
        }

        private SelfWatermark selfWatermarkOf(long watermark) {
            return (_, _) -> watermark;
        }
    }

    /// #333: a NON-owner backfills from the DETERMINISTIC HRW OWNER (computed locally from the member
    /// view), NOT the blind local registry `CAUGHT_UP` set. Peer watermark state is never propagated
    /// cross-node (production registry uses `WatermarkStore.NOOP`), so a registry-selected source can be
    /// stale/behind and drive a false `CAUGHT_UP`-with-a-hole, after which every live batch is rejected as
    /// a gap forever. Pulling from the owner (whose reported tail is the true watermark) closes the wedge —
    /// the same owner-forward principle the read path uses.
    @Nested
    class OwnerSourceBackfill {
        private static final NodeId NODE_AA = NodeId.nodeId("node-aa").unwrap();
        private static final NodeId NODE_BB = NodeId.nodeId("node-bb").unwrap();
        private static final NodeId NODE_CC = NodeId.nodeId("node-cc").unwrap();
        private static final TimeSpan BOUND = TimeSpan.timeSpan(10).seconds();
        private static final List<NodeId> MEMBERS = List.of(NODE_AA, NODE_BB, NODE_CC);
        private static final NodeId OWNER = ReplicaPlacement.rank(STREAM, PARTITION, MEMBERS).getFirst();
        private static final NodeId NON_OWNER = ReplicaPlacement.rank(STREAM, PARTITION, MEMBERS).getLast();

        @Test
        void backfill_nonOwnerBehindOwner_pullsFromHrwOwner_reachesCaughtUpAtOwnerTail() {
            // self (non-owner) holds offsets 0..1 locally; the HRW owner holds 0..15. Backfill must request
            // the missing suffix 2..15 FROM THE OWNER, contiguous from local head + 1 (= 2), and reach
            // CAUGHT_UP@15 — never a false CAUGHT_UP@0 from a blind/behind source (#333).
            seedLocal(2); // local offsets 0,1
            registry.registerReplica(STREAM, PARTITION, OWNER);
            registry.registerReplica(STREAM, PARTITION, NON_OWNER); // self, SYNCING

            var transport = ownerSource(OWNER, 2L, eventsFrom(2, 14)); // owner returns offsets 2..15
            var backfill = partitionBackfill(registry,
                                             recovery,
                                             transport,
                                             failIfProbed(),
                                             localHeadWatermark(),
                                             NON_OWNER,
                                             BOUND,
                                             new AtomicLong(0L)::get,
                                             () -> MEMBERS);

            var applied = backfill.backfill(STREAM, PARTITION).await();

            assertThat(applied.isSuccess()).isTrue();
            assertThat(applied.or(-1L)).isEqualTo(14L);
            var self = descriptorFor(NON_OWNER);
            assertThat(self.state()).isEqualTo(ReplicationState.CAUGHT_UP);
            assertThat(self.confirmedOffset()).isEqualTo(15L);
            // Local ring now holds 0..15 contiguously (offsets preserved, no shift).
            var local = manager.readLocal(STREAM, PARTITION, 0, 100).or(List.of());
            assertThat(local).hasSize(16);
            for (var i = 0; i < 16; i++) {
                assertThat(local.get(i).offset()).isEqualTo((long) i);
            }
        }

        @Test
        void backfill_staleCaughtUpRegistryPeer_ignored_pullsFromAuthoritativeOwner() {
            // A blind/stale registry peer is marked CAUGHT_UP at a behind offset 0 (the cross-node watermark
            // NOOP trap) AND the HRW owner holds 0..15. Backfill must IGNORE the blind peer and pull from
            // the owner — the transport asserts its target IS the owner — reaching CAUGHT_UP@15, not a false
            // CAUGHT_UP@0. Under the pre-#333 registry source-selection this peer would have been chosen.
            seedLocal(2); // local offsets 0,1
            registry.registerReplica(STREAM, PARTITION, OWNER);
            registry.registerReplica(STREAM, PARTITION, NON_OWNER); // self
            var blind = NodeId.nodeId("node-blind").unwrap();
            registry.registerReplica(STREAM, PARTITION, blind);
            registry.updateWatermark(STREAM, PARTITION, blind, 0L); // stale CAUGHT_UP @ 0

            var transport = ownerSource(OWNER, 2L, eventsFrom(2, 14));
            var backfill = partitionBackfill(registry,
                                             recovery,
                                             transport,
                                             failIfProbed(),
                                             localHeadWatermark(),
                                             NON_OWNER,
                                             BOUND,
                                             new AtomicLong(0L)::get,
                                             () -> MEMBERS);

            var applied = backfill.backfill(STREAM, PARTITION).await();

            assertThat(applied.isSuccess()).isTrue();
            assertThat(descriptorFor(NON_OWNER).state()).isEqualTo(ReplicationState.CAUGHT_UP);
            assertThat(descriptorFor(NON_OWNER).confirmedOffset()).isEqualTo(15L);
        }

        private void seedLocal(int count) {
            for (var i = 0; i < count; i++) {
                recovery.appendRecoveredEvent(STREAM, PARTITION, ("event-" + i).getBytes(), 1000L + i).unwrap();
            }
        }

        // Self's local watermark = the local ring HEAD (highest offset held), the base for the contiguous
        // owner-source fromOffset (head + 1). Backed by the same manager the recovery seam writes into.
        private SelfWatermark localHeadWatermark() {
            return (stream, partition) -> manager.partitionInfo(stream, partition)
                                                 .map(StreamPartitionManager.PartitionInfo::headOffset)
                                                 .or(-1L);
        }

        // The owner-source path never probes peers — a probe call is a regression.
        private ReplicaWatermarkProbe failIfProbed() {
            return (_, _, _) -> {
                throw new AssertionError("owner-source backfill must not probe peers");
            };
        }

        // A catch-up source that asserts it is targeted at the HRW owner from the expected (contiguous)
        // offset, then returns `events` (toOffset = last event offset, or fromOffset-1 when empty).
        private CatchupTransport ownerSource(NodeId expectedOwner, long expectedFrom, List<EventData> events) {
            return (target, request) -> {
                assertThat(target).as("backfill must target the HRW owner").isEqualTo(expectedOwner);
                assertThat(request.fromOffset()).as("fromOffset must be local head + 1 (contiguous)")
                                                .isEqualTo(expectedFrom);
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
    }

    /// #333 write-idle residual: a node that self-promoted to CAUGHT_UP under an empty/partial member view
    /// (cold-start owner-self-promote) can later turn out to be a NON-owner once the member view populates,
    /// holding none of the real owner's history. The 5s redrive skips it (it is CAUGHT_UP) and on a
    /// write-idle partition the gap loop never fires, so it would serve stale/empty data forever. The fix
    /// re-includes such a replica in {@link PartitionBackfill#redriveCandidates} (owner-aware, offset- and
    /// interval-quiesced); re-arming `backfill` PROBES THE CURRENT HRW OWNER once and pulls ONLY when the
    /// owner is ahead, reaching CAUGHT_UP at the owner's TRUE tail. A successful pull (and each probe
    /// dispatch) stamps the re-verify clock, so a genuinely-complete replica is re-checked at most once per
    /// interval — never every tick. The genuine single-node owner-self-promote case is NOT re-armed.
    @Nested
    class WriteIdleResidualReverify {
        private static final NodeId NODE_AA = NodeId.nodeId("node-aa").unwrap();
        private static final NodeId NODE_BB = NodeId.nodeId("node-bb").unwrap();
        private static final NodeId NODE_CC = NodeId.nodeId("node-cc").unwrap();
        private static final TimeSpan BOUND = TimeSpan.timeSpan(10).seconds();
        private static final List<NodeId> POPULATED = List.of(NODE_AA, NODE_BB, NODE_CC);
        // After the member view populates, the HRW owner of (STREAM, PARTITION) is a DIFFERENT node than
        // self; self is the last-ranked member (a guaranteed non-owner).
        private static final NodeId OWNER = ReplicaPlacement.rank(STREAM, PARTITION, POPULATED).getFirst();
        private static final NodeId SELF_NON_OWNER = ReplicaPlacement.rank(STREAM, PARTITION, POPULATED).getLast();

        @Test
        void redriveCandidates_caughtUpNonOwnerStaleAt0_reArmsBackfill_probesOwnerThenReachesTail() {
            // t0: empty member view, self is the SOLE registered replica. With no peer and the member view
            // empty, the cold-start lone-replica path self-promotes self to CAUGHT_UP@-1 once the bound
            // elapses — a NO-PULL promotion, so lastReverifyMs stays unset and the null ⇒ elapsed rule keeps
            // the residual re-verify-eligible.
            registry.registerReplica(STREAM, PARTITION, SELF_NON_OWNER);

            var members = new AtomicReference<>(List.<NodeId>of()); // empty at t0
            var clock = new AtomicLong(0L);
            var probeCount = new AtomicInteger(0);
            var transport = ownerSource(OWNER, 0L, eventsFrom(0, 16)); // owner holds 0..15
            var backfill = partitionBackfill(registry,
                                             recovery,
                                             transport,
                                             countingOwnerProbe(probeCount, 15L),
                                             localHeadWatermark(), // empty partition -> head -1
                                             SELF_NON_OWNER,
                                             BOUND,
                                             clock::get,
                                             members::get);

            backfill.backfill(STREAM, PARTITION).await();   // arm the bound at t=0
            clock.set(BOUND.millis() + 1);                  // bound elapsed -> lone-replica self-promote@-1
            assertThat(backfill.backfill(STREAM, PARTITION).await().isSuccess()).isTrue();
            assertThat(descriptorFor(SELF_NON_OWNER).state()).isEqualTo(ReplicationState.CAUGHT_UP);
            assertThat(descriptorFor(SELF_NON_OWNER).confirmedOffset()).isEqualTo(-1L);
            assertThat(probeCount.get()).as("cold-start lone-replica self-promote probes no peers").isEqualTo(0);

            // t1: member view populates; the HRW owner is now a DIFFERENT node holding 0..15. WITHOUT any
            // new live batch, the redrive must re-include this stale CAUGHT_UP@-1 non-owner partition (no
            // pull ⇒ never re-verified)...
            members.set(POPULATED);
            assertThat(backfill.redriveCandidates()).containsExactly(PartitionKey.partitionKey(STREAM, PARTITION));

            // ...and re-arming backfill PROBES THE CURRENT HRW OWNER (exactly once — never a broad peer
            // sweep); the owner is ahead (15 > -1) so it pulls and reaches CAUGHT_UP at the owner's TRUE tail.
            var applied = backfill.backfill(STREAM, PARTITION).await();
            assertThat(applied.isSuccess()).isTrue();
            assertThat(probeCount.get()).as("re-verify probes the HRW owner exactly once").isEqualTo(1);
            assertThat(descriptorFor(SELF_NON_OWNER).state()).isEqualTo(ReplicationState.CAUGHT_UP);
            assertThat(descriptorFor(SELF_NON_OWNER).confirmedOffset()).isEqualTo(15L);

            // Quiescence: the successful pull stamped lastReverifyMs (at promote), so within the interval the
            // genuinely-complete replica is no longer a candidate — no per-tick owner probe.
            assertThat(backfill.redriveCandidates()).isEmpty();
        }

        @Test
        void redriveCandidates_caughtUpNonOwner_intervalElapsed_ownerNotAhead_probesOnce_noOp_staysCaughtUp() {
            // A non-owner replica already CAUGHT_UP at the owner's tail via a real pull (lastReverifyMs stamped
            // at promote). Once the re-verify interval elapses it becomes a candidate again; the re-verify
            // PROBES the owner exactly once, finds it NOT ahead, and is a PURE no-op — the replica stays
            // CAUGHT_UP at its tail (no NO_SOURCE demotion, no SYNCING flip) and re-quiesces for the next
            // interval. This is the idle-owner arm that must NOT trip the no-source demotion class.
            registry.registerReplica(STREAM, PARTITION, OWNER);
            registry.registerReplica(STREAM, PARTITION, SELF_NON_OWNER); // self, SYNCING

            var clock = new AtomicLong(0L);
            var probeCount = new AtomicInteger(0);
            var transport = ownerSource(OWNER, 0L, eventsFrom(0, 16)); // owner holds 0..15
            var backfill = partitionBackfill(registry,
                                             recovery,
                                             transport,
                                             countingOwnerProbe(probeCount, 15L),
                                             localHeadWatermark(),
                                             SELF_NON_OWNER,
                                             BOUND,
                                             clock::get,
                                             () -> POPULATED);

            // Initial SYNCING pull → CAUGHT_UP@15 (stamps lastReverifyMs at promote); the pull path takes no
            // probe, and the fresh pull-promotion is quiesced within the interval.
            assertThat(backfill.backfill(STREAM, PARTITION).await().isSuccess()).isTrue();
            assertThat(descriptorFor(SELF_NON_OWNER).confirmedOffset()).isEqualTo(15L);
            assertThat(probeCount.get()).as("the SYNCING pull path issues no probe").isEqualTo(0);
            assertThat(backfill.redriveCandidates()).as("a fresh pull-promotion is quiesced within the interval").isEmpty();

            // The interval elapses with no live write and the owner still at 15 → candidate → re-verify.
            clock.set(BOUND.millis() + 1);
            assertThat(backfill.redriveCandidates()).containsExactly(PartitionKey.partitionKey(STREAM, PARTITION));

            var applied = backfill.backfill(STREAM, PARTITION).await();

            // Owner not ahead (15 == 15) → pure no-op: one probe, a SUCCESS (0 applied), self stays
            // CAUGHT_UP@15, NOT demoted to SYNCING and NOT failed with a NO_SOURCE cause.
            assertThat(applied.isSuccess()).as("owner-not-ahead re-verify is a success no-op, not a NO_SOURCE failure").isTrue();
            assertThat(probeCount.get()).as("the re-verify probes the owner exactly once").isEqualTo(1);
            assertThat(descriptorFor(SELF_NON_OWNER).state()).isEqualTo(ReplicationState.CAUGHT_UP);
            assertThat(descriptorFor(SELF_NON_OWNER).confirmedOffset()).isEqualTo(15L);
            assertThat(backfill.redriveCandidates()).as("the no-op re-stamps lastReverifyMs, re-quiescing the replica").isEmpty();
        }

        @Test
        void redriveCandidates_caughtUpNonOwnerColdStartNoPull_ownerNotAhead_reverifyNoOpQuiescesWithinInterval() {
            // COVERAGE GAP (the NO-PULL path): a non-owner that reached CAUGHT_UP via a cold-start lone-replica
            // self-promote (NOT a pull) has NO `reverifiedAtOffset` record. If its HRW owner is never ahead, the
            // owner-not-ahead re-verify no-op MUST stamp `reverifiedAtOffset` — else `offsetMoved` reads the
            // absent record as moved (.or(true)) FOREVER, making it a redrive candidate (an owner probe) EVERY
            // tick. With the stamp it quiesces to at most ONE probe per interval. The pulled-first
            // …intervalElapsed_ownerNotAhead_probesOnce_noOp… test above cannot catch this — there the pull
            // already set `reverifiedAtOffset`, so `offsetMoved` was false regardless of the no-op stamp.
            registry.registerReplica(STREAM, PARTITION, SELF_NON_OWNER);

            var members = new AtomicReference<>(List.<NodeId>of()); // empty at t0
            var clock = new AtomicLong(0L);
            var probeCount = new AtomicInteger(0);
            var backfill = partitionBackfill(registry,
                                             recovery,
                                             failIfCatchup(),                     // owner never ahead ⇒ never a pull
                                             countingOwnerProbe(probeCount, -1L),  // HRW owner also empty (head -1)
                                             localHeadWatermark(),                 // empty partition -> head -1
                                             SELF_NON_OWNER,
                                             BOUND,
                                             clock::get,
                                             members::get);

            // Cold-start lone-replica self-promote to CAUGHT_UP@-1 (NO pull ⇒ reverifiedAtOffset + lastReverifyMs unset).
            backfill.backfill(STREAM, PARTITION).await();   // arm the bound at t=0
            clock.set(BOUND.millis() + 1);
            assertThat(backfill.backfill(STREAM, PARTITION).await().isSuccess()).isTrue();
            assertThat(descriptorFor(SELF_NON_OWNER).state()).isEqualTo(ReplicationState.CAUGHT_UP);
            assertThat(descriptorFor(SELF_NON_OWNER).confirmedOffset()).isEqualTo(-1L);
            assertThat(probeCount.get()).as("cold-start lone-replica self-promote probes no peers").isEqualTo(0);

            // Members populate; the HRW owner is a DIFFERENT node, also empty (head -1). First re-verify: the
            // never-verified residual is a candidate, probes the owner once, finds it NOT ahead (-1 <= -1) → no-op.
            members.set(POPULATED);
            assertThat(backfill.redriveCandidates()).containsExactly(PartitionKey.partitionKey(STREAM, PARTITION));
            assertThat(backfill.backfill(STREAM, PARTITION).await().isSuccess()).isTrue();
            assertThat(probeCount.get()).as("first re-verify probes the HRW owner exactly once").isEqualTo(1);
            assertThat(descriptorFor(SELF_NON_OWNER).state()).isEqualTo(ReplicationState.CAUGHT_UP);
            assertThat(descriptorFor(SELF_NON_OWNER).confirmedOffset()).as("the no-op never demotes the replica").isEqualTo(-1L);

            // THE FIX: the no-op stamped reverifiedAtOffset(@-1), so WITHIN the interval offsetMoved is false and
            // the replica is NOT a candidate — no per-tick owner probe. Without the stamp this would be a per-tick
            // candidate (the bug): offsetMoved's absent-record .or(true) would dominate the staleCaughtUpNonOwner OR.
            assertThat(backfill.redriveCandidates()).as("the no-op stamp quiesces offsetMoved within the interval").isEmpty();
            assertThat(probeCount.get()).as("no per-tick probe within the interval").isEqualTo(1);

            // After the interval elapses it becomes a candidate again and re-verifies EXACTLY once (per-interval,
            // NOT per-tick), still a no-op, still CAUGHT_UP@-1.
            clock.set(2 * BOUND.millis() + 2);
            assertThat(backfill.redriveCandidates()).containsExactly(PartitionKey.partitionKey(STREAM, PARTITION));
            assertThat(backfill.backfill(STREAM, PARTITION).await().isSuccess()).isTrue();
            assertThat(probeCount.get()).as("re-verify probes once PER INTERVAL, not per tick").isEqualTo(2);
            assertThat(descriptorFor(SELF_NON_OWNER).confirmedOffset()).isEqualTo(-1L);
        }

        @Test
        void redriveCandidates_selfIsHrwOwner_caughtUp_notReArmed() {
            // NEGATIVE: a node that IS the HRW owner self-promoted to CAUGHT_UP must NOT be re-armed — the
            // genuine single-node / owner-self-promote case stays CAUGHT_UP with no spurious backfill.
            registry.registerReplica(STREAM, PARTITION, OWNER); // self == HRW owner
            registry.updateWatermark(STREAM, PARTITION, OWNER, 0L); // CAUGHT_UP@0 (owner self-promote)

            var clock = new AtomicLong(BOUND.millis() + 1);
            var backfill = partitionBackfill(registry,
                                             recovery,
                                             failIfCatchup(),
                                             reachableProbeNever(),
                                             selfWatermarkOf(0L),
                                             OWNER,
                                             BOUND,
                                             clock::get,
                                             () -> POPULATED);

            // Owner is never a redrive candidate, so backfill is never re-armed (the transport asserts it).
            assertThat(backfill.redriveCandidates()).isEmpty();
            assertThat(descriptorFor(OWNER).state()).isEqualTo(ReplicationState.CAUGHT_UP);
        }

        @Test
        void redriveCandidates_completeNonOwnerReplica_notReArmed() {
            // A non-owner replica that already reached CAUGHT_UP at the owner's true tail via a real backfill
            // is offset-quiesced — it is NOT a redrive candidate (no per-tick owner probe at scale).
            seedLocal(2); // local offsets 0,1
            registry.registerReplica(STREAM, PARTITION, OWNER);
            registry.registerReplica(STREAM, PARTITION, SELF_NON_OWNER); // self, SYNCING

            var transport = ownerSource(OWNER, 2L, eventsFrom(2, 14)); // owner returns offsets 2..15
            var backfill = partitionBackfill(registry,
                                             recovery,
                                             transport,
                                             reachableProbeNever(),
                                             localHeadWatermark(),
                                             SELF_NON_OWNER,
                                             BOUND,
                                             new AtomicLong(0L)::get,
                                             () -> POPULATED);

            assertThat(backfill.backfill(STREAM, PARTITION).await().isSuccess()).isTrue();
            assertThat(descriptorFor(SELF_NON_OWNER).confirmedOffset()).isEqualTo(15L);
            // Genuinely complete: redrive must not re-include it.
            assertThat(backfill.redriveCandidates()).isEmpty();
        }

        @Test
        void redriveCandidates_liveReplicatingNonOwner_quiescedAcrossManyTicks_noPerTickProbe() {
            // Reconciler-under-load invariant: a non-owner CAUGHT_UP replica kept current by LIVE replication
            // must NOT become a perpetual redrive candidate (which would issue one owner catch-up probe per
            // active replica-partition per 5s tick). The replica-side live path (ReplicationReceiveHandler)
            // advances the LOCAL ring and acks the OWNER, but NEVER touches self's own registry descriptor —
            // so self's `confirmedOffset` stays at the backfilled offset (== reverifiedAtOffset) and the
            // partition stays out of the candidate set across arbitrarily many ticks.
            seedLocal(2);
            registry.registerReplica(STREAM, PARTITION, OWNER);
            registry.registerReplica(STREAM, PARTITION, SELF_NON_OWNER); // self

            var transport = ownerSource(OWNER, 2L, eventsFrom(2, 14)); // owner 2..15
            var backfill = partitionBackfill(registry,
                                             recovery,
                                             transport,
                                             reachableProbeNever(),
                                             localHeadWatermark(),
                                             SELF_NON_OWNER,
                                             BOUND,
                                             new AtomicLong(0L)::get,
                                             () -> POPULATED);

            assertThat(backfill.backfill(STREAM, PARTITION).await().isSuccess()).isTrue();

            // Model live replication: keep appending new events to the LOCAL ring (the receive path), which
            // the production receive handler does WITHOUT updating self's own registry descriptor. Self's
            // registry confirmedOffset therefore stays 15 — the quiesce invariant — across every tick.
            for (var tick = 0; tick < 10; tick++) {
                recovery.appendRecoveredEvent(STREAM, PARTITION, ("live-" + tick).getBytes(), 2000L + tick).unwrap();
                assertThat(backfill.redriveCandidates())
                        .as("live-replicating non-owner must stay quiesced on tick %d (no per-tick owner probe)", tick)
                        .isEmpty();
            }
        }

        private void seedLocal(int count) {
            for (var i = 0; i < count; i++) {
                recovery.appendRecoveredEvent(STREAM, PARTITION, ("event-" + i).getBytes(), 1000L + i).unwrap();
            }
        }

        private SelfWatermark selfWatermarkOf(long watermark) {
            return (_, _) -> watermark;
        }

        private SelfWatermark localHeadWatermark() {
            return (stream, partition) -> manager.partitionInfo(stream, partition)
                                                 .map(StreamPartitionManager.PartitionInfo::headOffset)
                                                 .or(-1L);
        }

        private ReplicaWatermarkProbe reachableProbeNever() {
            return (_, _, _) -> {
                throw new AssertionError("owner-source re-verify must not probe peers");
            };
        }

        /// A probe that counts every invocation and answers `ownerHead` for the HRW OWNER (any other target
        /// is unreachable). Used by the probe-first re-verify arms to assert the owner is probed EXACTLY once
        /// (never a broad peer sweep) and to drive the owner-ahead / owner-not-ahead branches.
        private ReplicaWatermarkProbe countingOwnerProbe(AtomicInteger count, long ownerHead) {
            return (target, _, _) -> probeOwner(count, target, ownerHead);
        }

        private Promise<Long> probeOwner(AtomicInteger count, NodeId target, long ownerHead) {
            count.incrementAndGet();

            return target.equals(OWNER)
                   ? Promise.success(ownerHead)
                   : ReplicationError.General.REPLICATION_TIMEOUT.promise();
        }

        private CatchupTransport failIfCatchup() {
            return (_, _) -> {
                throw new AssertionError("HRW owner must not be re-armed for backfill");
            };
        }

        private CatchupTransport ownerSource(NodeId expectedOwner, long expectedFrom, List<EventData> events) {
            return (target, request) -> {
                assertThat(target).as("backfill must target the HRW owner").isEqualTo(expectedOwner);
                assertThat(request.fromOffset()).as("fromOffset must be local head + 1 (contiguous)")
                                                .isEqualTo(expectedFrom);
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
    }

    /// #336 phase-2 (lossless owner-kill failover): when `replicas > minSyncReplicas` a freshly HRW-promoted
    /// owner can be BEHIND a surviving replica, because different client-acked writes were confirmed by
    /// different peers. Promoting it at its short local watermark serves a truncated log (silent data loss).
    /// The promoted owner must catch up to the MAX `confirmedOffset` among surviving replicas — the true
    /// acked watermark — before it becomes authoritative, and must not wedge when a survivor is unreachable.
    @Nested
    class PromotedOwnerLosslessFailover {
        private static final NodeId NODE_AA = NodeId.nodeId("node-aa").unwrap();
        private static final NodeId NODE_BB = NodeId.nodeId("node-bb").unwrap();
        private static final NodeId NODE_CC = NodeId.nodeId("node-cc").unwrap();
        private static final NodeId NODE_DD = NodeId.nodeId("node-dd").unwrap();
        private static final TimeSpan BOUND = TimeSpan.timeSpan(10).seconds();
        private static final List<NodeId> MEMBERS = List.of(NODE_AA, NODE_BB, NODE_CC, NODE_DD);
        // The promoted owner is the HRW owner of (STREAM, PARTITION); the survivors are the other members,
        // deterministically ranked by the same placement the production code uses.
        private static final NodeId OWNER = ReplicaPlacement.rank(STREAM, PARTITION, MEMBERS).getFirst();
        private static final List<NodeId> SURVIVORS = ReplicaPlacement.rank(STREAM, PARTITION, MEMBERS)
                                                                      .stream()
                                                                      .filter(node -> !node.equals(OWNER))
                                                                      .toList();

        @Test
        void backfill_promotedOwnerBehindSurvivor_catchesUpToSurvivorTail_beforeAuthoritative() {
            // Self is the freshly-promoted HRW owner holding only 0..4 locally (CAUGHT_UP@4 from before the
            // owner death); a surviving replica confirms 0..19 (min-sync acked those offsets on the survivor,
            // not on self). Promotion must pull 5..19 FROM the survivor and reach CAUGHT_UP@19 — never
            // self-promote at the short local watermark 4.
            var survivor = SURVIVORS.getFirst();
            seedLocal(5); // local offsets 0..4 -> head 4
            registry.registerReplica(STREAM, PARTITION, OWNER);
            registry.updateWatermark(STREAM, PARTITION, OWNER, 4L);      // self CAUGHT_UP@4 (behind)
            registry.registerReplica(STREAM, PARTITION, survivor);
            registry.updateWatermark(STREAM, PARTITION, survivor, 19L);  // survivor CAUGHT_UP@19

            var transport = survivorSource(survivor, 5L, eventsFrom(5, 15)); // returns offsets 5..19
            var backfill = ownerBackfill(transport, new AtomicLong(0L));

            var applied = backfill.backfill(STREAM, PARTITION).await();

            assertThat(applied.isSuccess()).isTrue();
            var self = descriptorFor(OWNER);
            assertThat(self.state()).isEqualTo(ReplicationState.CAUGHT_UP);
            assertThat(self.confirmedOffset()).isEqualTo(19L); // authoritative only at the survivor tail
            // Local ring now holds 0..19 contiguously — the promoted owner serves the FULL acked log.
            var local = manager.readLocal(STREAM, PARTITION, 0, 100).or(List.of());
            assertThat(local).hasSize(20);
            for (var i = 0; i < 20; i++) {
                assertThat(local.get(i).offset()).isEqualTo((long) i);
            }
        }

        @Test
        void backfill_promotedOwnerAtOrAboveMax_authoritativeImmediately_noCatchup() {
            // Self (promoted owner) already holds 0..9; the highest survivor confirms only 0..9. No survivor
            // is ahead, so self is authoritative immediately — no catch-up request, no bounded wait.
            var survivor = SURVIVORS.getFirst();
            seedLocal(10); // local 0..9 -> head 9
            registry.registerReplica(STREAM, PARTITION, OWNER);
            registry.updateWatermark(STREAM, PARTITION, OWNER, 9L);
            registry.registerReplica(STREAM, PARTITION, survivor);
            registry.updateWatermark(STREAM, PARTITION, survivor, 9L); // not ahead

            var clock = new AtomicLong(0L); // bound NOT elapsed — must not matter
            var backfill = ownerBackfill(failIfCatchup(), clock);

            var applied = backfill.backfill(STREAM, PARTITION).await();

            assertThat(applied.isSuccess()).isTrue();
            assertThat(clock.get()).isZero();
            var self = descriptorFor(OWNER);
            assertThat(self.state()).isEqualTo(ReplicationState.CAUGHT_UP);
            assertThat(self.confirmedOffset()).isEqualTo(9L);
        }

        @Test
        void backfill_promotedOwnerBehindMultipleSurvivors_catchesUpToTheMaxConfirmed() {
            // Three survivors at differing confirmed offsets (12, 20, 8). The catch-up target is the MAX (20),
            // and the source is the survivor holding it — not a lower one.
            seedLocal(5); // local 0..4 -> head 4
            registry.registerReplica(STREAM, PARTITION, OWNER);
            registry.updateWatermark(STREAM, PARTITION, OWNER, 4L);
            var low = SURVIVORS.get(0);
            var high = SURVIVORS.get(1);
            var mid = SURVIVORS.get(2);
            registry.registerReplica(STREAM, PARTITION, low);
            registry.updateWatermark(STREAM, PARTITION, low, 12L);
            registry.registerReplica(STREAM, PARTITION, high);
            registry.updateWatermark(STREAM, PARTITION, high, 20L);
            registry.registerReplica(STREAM, PARTITION, mid);
            registry.updateWatermark(STREAM, PARTITION, mid, 8L);

            var transport = survivorSource(high, 5L, eventsFrom(5, 16)); // offsets 5..20 FROM the @20 survivor
            var backfill = ownerBackfill(transport, new AtomicLong(0L));

            var applied = backfill.backfill(STREAM, PARTITION).await();

            assertThat(applied.isSuccess()).isTrue();
            var self = descriptorFor(OWNER);
            assertThat(self.state()).isEqualTo(ReplicationState.CAUGHT_UP);
            assertThat(self.confirmedOffset()).isEqualTo(20L);
        }

        @Test
        void backfill_promotedOwnerSurvivorUnreachable_staysSyncingAcrossBound_neverTruncatesBelowSurvivorTail() {
            // A survivor confirms 0..19 but is UNREACHABLE (catch-up transport fails). The promoted owner holds
            // only 0..4. Per #445 it must NEVER degrade-promote at its LOCAL watermark (4) — that would truncate
            // the acked suffix 5..19 that lives on the survivor. It stays SYNCING (non-authoritative) both within
            // AND past the bound; the redrive keeps retrying until the survivor answers (a transient outage) or
            // leaves the member view (the self-heal, covered separately). The old bounded-wait degraded promote
            // is GONE.
            var survivor = SURVIVORS.getFirst();
            seedLocal(5); // local 0..4 -> head 4
            registry.registerReplica(STREAM, PARTITION, OWNER);
            registry.updateWatermark(STREAM, PARTITION, OWNER, 4L);
            registry.registerReplica(STREAM, PARTITION, survivor);
            registry.updateWatermark(STREAM, PARTITION, survivor, 19L);

            CatchupTransport unreachable = (_, _) -> ReplicationError.General.REPLICATION_TIMEOUT.promise();
            var clock = new AtomicLong(0L);
            var backfill = ownerBackfill(unreachable, clock);

            // Within the bound: stays non-authoritative (SYNCING), NOT falsely promoted to the survivor tail.
            assertThat(backfill.backfill(STREAM, PARTITION).await().isFailure()).isTrue();
            assertThat(descriptorFor(OWNER).state()).isEqualTo(ReplicationState.SYNCING);
            assertThat(descriptorFor(OWNER).confirmedOffset()).isEqualTo(4L);

            // Bound elapsed: STILL SYNCING — the local-watermark degraded promote is gone (it truncated below
            // the survivor tail). No CAUGHT_UP@4 is ever recorded while the survivor is known ahead + unreachable.
            clock.set(BOUND.millis() + 1);
            assertThat(backfill.backfill(STREAM, PARTITION).await().isFailure()).isTrue();
            assertThat(descriptorFor(OWNER).state()).isEqualTo(ReplicationState.SYNCING);
            assertThat(descriptorFor(OWNER).confirmedOffset()).isEqualTo(4L);

            // Even far past the bound, repeated redrive ticks never truncate.
            clock.set(BOUND.millis() * 3);
            assertThat(backfill.backfill(STREAM, PARTITION).await().isFailure()).isTrue();
            assertThat(descriptorFor(OWNER).state()).isEqualTo(ReplicationState.SYNCING);
        }

        @Test
        void redriveCandidates_promotedOwnerBehindSurvivor_isReDriven_thenQuiesces() {
            // The trigger: a freshly-promoted owner that is (prematurely) CAUGHT_UP but BEHIND a survivor must
            // be re-included in the redrive so the catch-up actually runs. Once it reaches the survivor tail
            // it quiesces (no per-tick owner probe).
            var survivor = SURVIVORS.getFirst();
            seedLocal(5);
            registry.registerReplica(STREAM, PARTITION, OWNER);
            registry.updateWatermark(STREAM, PARTITION, OWNER, 4L);      // CAUGHT_UP@4, behind
            registry.registerReplica(STREAM, PARTITION, survivor);
            registry.updateWatermark(STREAM, PARTITION, survivor, 19L);

            var backfill = ownerBackfill(failIfCatchup(), new AtomicLong(0L));
            assertThat(backfill.redriveCandidates()).containsExactly(PartitionKey.partitionKey(STREAM, PARTITION));

            // Once self is no longer behind the survivor, the owner quiesces (not a redrive candidate).
            registry.updateWatermark(STREAM, PARTITION, OWNER, 19L);
            assertThat(backfill.redriveCandidates()).isEmpty();
        }

        private PartitionBackfill ownerBackfill(CatchupTransport transport, AtomicLong clock) {
            return partitionBackfill(registry,
                                     recovery,
                                     transport,
                                     failIfProbed(),
                                     localHeadWatermark(),
                                     OWNER,
                                     BOUND,
                                     clock::get,
                                     () -> MEMBERS);
        }

        private void seedLocal(int count) {
            for (var i = 0; i < count; i++) {
                recovery.appendRecoveredEvent(STREAM, PARTITION, ("event-" + i).getBytes(), 1000L + i).unwrap();
            }
        }

        private SelfWatermark localHeadWatermark() {
            return (stream, partition) -> manager.partitionInfo(stream, partition)
                                                 .map(StreamPartitionManager.PartitionInfo::headOffset)
                                                 .or(-1L);
        }

        private ReplicaWatermarkProbe failIfProbed() {
            return (_, _, _) -> {
                throw new AssertionError("promoted-owner catch-up must not probe peers");
            };
        }

        private CatchupTransport failIfCatchup() {
            return (_, _) -> {
                throw new AssertionError("no catch-up must be issued when self already covers the max survivor");
            };
        }

        // A catch-up source that asserts it is targeted at the max-confirmed survivor from the expected
        // (contiguous) offset, then returns `events` (toOffset = last event offset, or fromOffset-1 empty).
        private CatchupTransport survivorSource(NodeId expectedSurvivor, long expectedFrom, List<EventData> events) {
            return (target, request) -> {
                assertThat(target).as("promoted owner must catch up from the max-confirmed survivor")
                                  .isEqualTo(expectedSurvivor);
                assertThat(request.fromOffset()).as("fromOffset must be local head + 1 (contiguous)")
                                                .isEqualTo(expectedFrom);
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
    }

    /// #336 fresh-owner blind-registry probe fallback. When a node that was NOT previously a replica becomes
    /// HRW owner (empty ring, `localWatermark == -1`) the local-registry `aheadSurvivor` reads every peer at
    /// the `-1` registration default (peer watermarks are never propagated — `WatermarkStore.NOOP`), so it
    /// would false-self-promote `CAUGHT_UP@-1` on an empty ring while a survivor holds the whole partition
    /// (effective RF=1, empty owner). The fix probes the blind survivors' REAL tails and catches up from the
    /// max reachable one; an unreachable survivor routes to the bounded-wait escape (never a false-ready
    /// empty self-promote past a possibly-ahead peer, never a wedge).
    @Nested
    class FreshOwnerBlindRegistryProbeFallback {
        private static final NodeId NODE_AA = NodeId.nodeId("node-aa").unwrap();
        private static final NodeId NODE_BB = NodeId.nodeId("node-bb").unwrap();
        private static final NodeId NODE_CC = NodeId.nodeId("node-cc").unwrap();
        private static final TimeSpan BOUND = TimeSpan.timeSpan(10).seconds();
        private static final List<NodeId> MEMBERS = List.of(NODE_AA, NODE_BB, NODE_CC);
        private static final NodeId OWNER = ReplicaPlacement.rank(STREAM, PARTITION, MEMBERS).getFirst();
        private static final NodeId SURVIVOR = ReplicaPlacement.rank(STREAM, PARTITION, MEMBERS).getLast();

        @Test
        void backfill_freshOwnerEmptyRing_blindSurvivor_probesRealTail_catchesUpNotFalseReady() {
            // Self is a FRESH HRW owner: empty local ring (localWatermark=-1) and the survivor's real tail
            // (24) is INVISIBLE in the local registry (registration default -1, WatermarkStore.NOOP).
            // aheadSurvivor sees -1 (not ahead) and would false-self-promote CAUGHT_UP@-1 on the empty ring;
            // the probe reveals the real tail 24 → the owner catches up and reaches CAUGHT_UP@24.
            registry.registerReplica(STREAM, PARTITION, OWNER);      // self, fresh, empty ring, SYNCING@-1
            registry.registerReplica(STREAM, PARTITION, SURVIVOR);   // blind: local confirmedOffset stays -1

            var backfill = partitionBackfill(registry,
                                             recovery,
                                             survivorSource(SURVIVOR, 0L, eventsFrom(0, 25)), // survivor holds 0..24
                                             reachableProbe(SURVIVOR, 24L),
                                             localHeadWatermark(),   // empty ring -> -1
                                             OWNER,
                                             BOUND,
                                             new AtomicLong(0L)::get,
                                             () -> MEMBERS);

            var applied = backfill.backfill(STREAM, PARTITION).await();

            assertThat(applied.isSuccess()).isTrue();
            assertThat(applied.or(-1L)).isEqualTo(25L);
            var self = descriptorFor(OWNER);
            assertThat(self.state()).isEqualTo(ReplicationState.CAUGHT_UP);
            assertThat(self.confirmedOffset()).isEqualTo(24L); // NOT a false-ready CAUGHT_UP@-1
            // The survivor the owner pulled 0..24 FROM is now recorded CAUGHT_UP@24 (no longer blind@-1): the
            // owner has direct evidence it holds >= 24, so a "CAUGHT_UP non-owner exists" check passes at once.
            var survivor = descriptorFor(SURVIVOR);
            assertThat(survivor.state()).isEqualTo(ReplicationState.CAUGHT_UP);
            assertThat(survivor.confirmedOffset()).isEqualTo(24L);
            // Local ring now holds the full partition 0..24 (offsets preserved).
            var local = manager.readLocal(STREAM, PARTITION, 0, 100).or(List.of());
            assertThat(local).hasSize(25);
            for (var i = 0; i < 25; i++) {
                assertThat(local.get(i).offset()).isEqualTo((long) i);
            }

            // Idempotent: caught up at the survivor tail, the owner is no longer a redrive candidate.
            assertThat(backfill.redriveCandidates()).isEmpty();
        }

        @Test
        void backfill_freshOwnerEmptyRing_blindSurvivorUnreachable_boundedWait_noEmptySelfPromote() {
            // Same fresh owner, but the blind survivor is UNREACHABLE (probe fails). Self must NOT self-promote
            // a false-ready CAUGHT_UP@-1 past a peer that might hold the data — it holds SYNCING within the
            // bound (the redrive retries), and degrades only once the bound elapses.
            registry.registerReplica(STREAM, PARTITION, OWNER);
            registry.registerReplica(STREAM, PARTITION, SURVIVOR);

            ReplicaWatermarkProbe unreachable = (_, _, _) -> ReplicationError.General.REPLICATION_TIMEOUT.promise();
            var clock = new AtomicLong(0L);
            var backfill = partitionBackfill(registry,
                                             recovery,
                                             failIfCatchup(),   // no catch-up must be issued to an unreachable survivor
                                             unreachable,
                                             localHeadWatermark(),
                                             OWNER,
                                             BOUND,
                                             clock::get,
                                             () -> MEMBERS);

            // Within the bound: stays SYNCING (no false-ready empty CAUGHT_UP), backfill fails-soft.
            assertThat(backfill.backfill(STREAM, PARTITION).await().isFailure()).isTrue();
            var held = descriptorFor(OWNER);
            assertThat(held.state()).isEqualTo(ReplicationState.SYNCING);
            assertThat(held.confirmedOffset()).isEqualTo(-1L);

            // Bound elapsed: degraded self-promote at the local watermark (available, not wedged).
            clock.set(BOUND.millis() + 1);
            assertThat(backfill.backfill(STREAM, PARTITION).await().isSuccess()).isTrue();
            assertThat(descriptorFor(OWNER).state()).isEqualTo(ReplicationState.CAUGHT_UP);
        }

        @Test
        void backfill_ownerWithKnownSurvivorOffset_noProbe_localRegistryHitPathUntouched() {
            // NEGATIVE: when the local registry DOES carry the survivor's offset (not blind), aheadSurvivor is
            // authoritative and the probe fallback must NOT fire. Survivor known at 3, owner ring at 5 -> no
            // survivor ahead -> self-promote WITHOUT probing (a probe would be a regression).
            seedLocal(6); // local 0..5 -> head 5
            registry.registerReplica(STREAM, PARTITION, OWNER);
            registry.updateWatermark(STREAM, PARTITION, OWNER, 5L);
            registry.registerReplica(STREAM, PARTITION, SURVIVOR);
            registry.updateWatermark(STREAM, PARTITION, SURVIVOR, 3L); // KNOWN, not blind, not ahead

            var backfill = partitionBackfill(registry,
                                             recovery,
                                             failIfCatchup(),
                                             failIfProbed(),          // must not be consulted for a known survivor
                                             localHeadWatermark(),
                                             OWNER,
                                             BOUND,
                                             new AtomicLong(0L)::get,
                                             () -> MEMBERS);

            assertThat(backfill.backfill(STREAM, PARTITION).await().isSuccess()).isTrue();
            var self = descriptorFor(OWNER);
            assertThat(self.state()).isEqualTo(ReplicationState.CAUGHT_UP);
            assertThat(self.confirmedOffset()).isEqualTo(5L); // self-promoted at its own tail
        }

        private void seedLocal(int count) {
            for (var i = 0; i < count; i++) {
                recovery.appendRecoveredEvent(STREAM, PARTITION, ("event-" + i).getBytes(), 1000L + i).unwrap();
            }
        }

        private SelfWatermark localHeadWatermark() {
            return (stream, partition) -> manager.partitionInfo(stream, partition)
                                                 .map(StreamPartitionManager.PartitionInfo::headOffset)
                                                 .or(-1L);
        }

        private ReplicaWatermarkProbe reachableProbe(NodeId target, long tail) {
            return (probed, _, _) -> probed.equals(target)
                                     ? Promise.success(tail)
                                     : ReplicationError.General.REPLICATION_TIMEOUT.promise();
        }

        private ReplicaWatermarkProbe failIfProbed() {
            return (_, _, _) -> {
                throw new AssertionError("known-survivor owner promote must not probe peers");
            };
        }

        private CatchupTransport failIfCatchup() {
            return (_, _) -> {
                throw new AssertionError("no catch-up must be issued on the no-reachable-survivor path");
            };
        }

        private CatchupTransport survivorSource(NodeId expectedSurvivor, long expectedFrom, List<EventData> events) {
            return (target, request) -> {
                assertThat(target).as("fresh owner must catch up from the probed survivor").isEqualTo(expectedSurvivor);
                assertThat(request.fromOffset()).as("fromOffset must be local head + 1 (contiguous)")
                                                .isEqualTo(expectedFrom);
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
    }

    /// #336 post-failover replicas-view reseat (REPORTING side; data serving unchanged). After an owner-kill
    /// failover the promoted owner serves all data correctly, but the operator replicas-view
    /// (`GET /api/streams/replicas/...`) reports stale watermarks: the promoted owner's OWN registry row and
    /// the replacement replica's row both stay frozen (e.g. `confirmedOffset=-1`) while the ring tail is
    /// correct. Fix A: the redrive reseats the promoted owner's own row to its authoritative ring tail
    /// (`selfWatermark.localWatermark`). Fix B: a NON-owner reaching CAUGHT_UP acks the CURRENT HRW owner
    /// (reusing the live `ReplicateAck`), so the owner's replicas-view shows the replica CAUGHT_UP at the
    /// backfilled tail — both without waiting for the next live write.
    @Nested
    class PostFailoverReplicasViewReseat {
        private static final NodeId NODE_AA = NodeId.nodeId("node-aa").unwrap();
        private static final NodeId NODE_BB = NodeId.nodeId("node-bb").unwrap();
        private static final NodeId NODE_CC = NodeId.nodeId("node-cc").unwrap();
        private static final TimeSpan BOUND = TimeSpan.timeSpan(10).seconds();
        private static final List<NodeId> MEMBERS = List.of(NODE_AA, NODE_BB, NODE_CC);
        private static final NodeId OWNER = ReplicaPlacement.rank(STREAM, PARTITION, MEMBERS).getFirst();
        private static final NodeId NON_OWNER = ReplicaPlacement.rank(STREAM, PARTITION, MEMBERS).getLast();

        @Test
        void redriveCandidates_promotedOwnerRowLagsRingTail_reseatsOwnRowToRingTail_thenQuiesces() {
            // Fix A: self IS the HRW owner and is (prematurely) CAUGHT_UP@-1 in its own registry row — the
            // exact post-failover staleness (a replica->owner promotion never re-ran ownerSelfPromote, and
            // the live path acks only peers) — while its authoritative ring tail is 25. The plain
            // `state != CAUGHT_UP` filter can never catch this; the new owner-branch predicate must.
            registry.registerReplica(STREAM, PARTITION, OWNER);         // self == HRW owner
            registry.updateWatermark(STREAM, PARTITION, OWNER, -1L);    // CAUGHT_UP@-1 (frozen, lags ring)

            var backfill = partitionBackfill(registry,
                                             recovery,
                                             failIfCatchup(),                 // owner self-promote: no catch-up
                                             ReplicationTransport.NOOP,       // owner never acks itself
                                             failIfProbed(),                  // owner path: no probe
                                             selfWatermarkOf(25L),            // ring tail = 25 (authoritative)
                                             OWNER,
                                             BOUND,
                                             () -> MEMBERS,
                                             CommittedStreamOwnerSource.none());

            // Frozen owner row is a redrive candidate precisely because its registry offset lags the ring tail.
            assertThat(descriptorFor(OWNER).state()).isEqualTo(ReplicationState.CAUGHT_UP);
            assertThat(descriptorFor(OWNER).confirmedOffset()).isEqualTo(-1L);
            assertThat(backfill.redriveCandidates()).containsExactly(PartitionKey.partitionKey(STREAM, PARTITION));

            // Re-driving reseats the owner's OWN row to the ring tail (25) WITHOUT a live write.
            assertThat(backfill.backfill(STREAM, PARTITION).await().isSuccess()).isTrue();
            assertThat(descriptorFor(OWNER).state()).isEqualTo(ReplicationState.CAUGHT_UP);
            assertThat(descriptorFor(OWNER).confirmedOffset()).isEqualTo(25L);

            // Idempotent: once the row equals the ring tail it is no longer a candidate (no oscillation).
            assertThat(backfill.redriveCandidates()).isEmpty();
        }

        @Test
        void backfill_nonOwnerReachesCaughtUp_acksCurrentOwner_ownerViewShowsCaughtUp() {
            // Fix B: self (the replacement replica) is a NON-owner that backfills the missing suffix from the
            // HRW owner and reaches CAUGHT_UP@15. It must ack the CURRENT owner so the owner's replicas-view
            // shows this replica CAUGHT_UP@15 before the next live write.
            seedLocal(2); // local offsets 0,1 -> head 1, fromOffset 2
            registry.registerReplica(STREAM, PARTITION, OWNER);
            registry.registerReplica(STREAM, PARTITION, NON_OWNER); // self, SYNCING@-1

            var sent = new AtomicReference<SentMessage>();
            ReplicationTransport capturing = (target, message) -> sent.set(new SentMessage(target, message));
            var backfill = partitionBackfill(registry,
                                             recovery,
                                             ownerSource(OWNER, 2L, eventsFrom(2, 14)), // owner holds 2..15
                                             capturing,
                                             failIfProbed(),
                                             localHeadWatermark(),
                                             NON_OWNER,
                                             BOUND,
                                             () -> MEMBERS,
                                             CommittedStreamOwnerSource.none());

            var applied = backfill.backfill(STREAM, PARTITION).await();

            assertThat(applied.isSuccess()).isTrue();
            assertThat(applied.or(-1L)).isEqualTo(14L);
            assertThat(descriptorFor(NON_OWNER).state()).isEqualTo(ReplicationState.CAUGHT_UP);
            assertThat(descriptorFor(NON_OWNER).confirmedOffset()).isEqualTo(15L);

            // The ack is a live ReplicateAck addressed to the CURRENT HRW owner, carrying the backfilled tail.
            var message = sent.get();
            assertThat(message).as("a completed non-owner backfill must ack the owner").isNotNull();
            assertThat(message.target()).isEqualTo(OWNER);
            assertThat(message.message()).isInstanceOf(ReplicationMessage.ReplicateAck.class);
            var ack = (ReplicationMessage.ReplicateAck) message.message();
            assertThat(ack.replicaId()).isEqualTo(NON_OWNER);
            assertThat(ack.streamName()).isEqualTo(STREAM);
            assertThat(ack.partition()).isEqualTo(PARTITION);
            assertThat(ack.confirmedOffset()).isEqualTo(15L);

            // Owner-side convergence: delivering that ack to the owner's own registry (a DISTINCT node) flips
            // its view of the replica to CAUGHT_UP@15 — exactly what the replicas-view projects.
            var ownerRegistry = replicaRegistry();
            ownerRegistry.registerReplica(STREAM, PARTITION, OWNER);
            ownerRegistry.registerReplica(STREAM, PARTITION, NON_OWNER); // SYNCING@-1 before the ack
            ReplicationManager.replicationManager(OWNER, ownerRegistry, ReplicationTransport.NOOP).handleAck(ack);

            var ownerViewOfReplica = ownerRegistry.replicasFor(STREAM, PARTITION).stream()
                                                  .filter(d -> d.nodeId().equals(NON_OWNER))
                                                  .findFirst()
                                                  .orElseThrow();
            assertThat(ownerViewOfReplica.state()).isEqualTo(ReplicationState.CAUGHT_UP);
            assertThat(ownerViewOfReplica.confirmedOffset()).isEqualTo(15L);
        }

        private void seedLocal(int count) {
            for (var i = 0; i < count; i++) {
                recovery.appendRecoveredEvent(STREAM, PARTITION, ("event-" + i).getBytes(), 1000L + i).unwrap();
            }
        }

        private SelfWatermark selfWatermarkOf(long watermark) {
            return (_, _) -> watermark;
        }

        private SelfWatermark localHeadWatermark() {
            return (stream, partition) -> manager.partitionInfo(stream, partition)
                                                 .map(StreamPartitionManager.PartitionInfo::headOffset)
                                                 .or(-1L);
        }

        private ReplicaWatermarkProbe failIfProbed() {
            return (_, _, _) -> {
                throw new AssertionError("replicas-view reseat must not probe peers");
            };
        }

        private CatchupTransport failIfCatchup() {
            return (_, _) -> {
                throw new AssertionError("owner self-reseat must not issue a catch-up request");
            };
        }

        private CatchupTransport ownerSource(NodeId expectedOwner, long expectedFrom, List<EventData> events) {
            return (target, request) -> {
                assertThat(target).as("backfill must target the HRW owner").isEqualTo(expectedOwner);
                assertThat(request.fromOffset()).as("fromOffset must be local head + 1 (contiguous)")
                                                .isEqualTo(expectedFrom);
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

        private record SentMessage(NodeId target, ReplicationMessage message) {}
    }

    /// #445: the backfill orchestrator ranks its owner/member view over the SAME reconciled snapshot the
    /// materialization gate ({@link ReplicaSetController#roleFor}) and the registry reconcile use — NOT an
    /// independent LIVE topology read. Wires a real controller + backfill over ONE shared registry, then
    /// constructs the pre-fix divergence: reconcile against a view where self is a NON-owner replica, then
    /// mutate the LIVE membership so a fresh read would rank self as its own HRW owner. A live-sourced
    /// backfill would then owner-self-promote a FALSE `CAUGHT_UP` at self's empty local watermark (serving
    /// nothing while the real owner holds the data — the ACKED-THEN-LOST wedge). Single-sourced from the
    /// controller's reconciled snapshot, self stays a non-owner and pulls the real history from the
    /// reconciled HRW owner, reaching `CAUGHT_UP` at the owner's true tail.
    @Nested
    class SingleSourcedPlacementView {
        private static final NodeId NODE_AA = NodeId.nodeId("node-aa").unwrap();
        private static final NodeId NODE_BB = NodeId.nodeId("node-bb").unwrap();
        private static final NodeId NODE_CC = NodeId.nodeId("node-cc").unwrap();
        private static final TimeSpan BOUND = TimeSpan.timeSpan(10).seconds();
        private static final List<NodeId> MEMBERS = List.of(NODE_AA, NODE_BB, NODE_CC);
        private static final NodeId OWNER = ReplicaPlacement.rank(STREAM, PARTITION, MEMBERS).getFirst();
        private static final NodeId SELF_REPLICA = ReplicaPlacement.rank(STREAM, PARTITION, MEMBERS).getLast();

        private record FakeCatalog(List<StreamCatalog.StreamSpec> specs) implements StreamCatalog {
            @Override public List<StreamSpec> streams() {
                return specs;
            }
        }

        @Test
        void backfillOwnerView_tracksReconciledSnapshot_notDivergentLiveRead() {
            // replicas=3 over 3 members -> RF=3, so SELF_REPLICA is a non-owner REPLICA under the reconciled
            // view while OWNER is a DIFFERENT node holding 0..7.
            var liveMembers = new AtomicReference<>(MEMBERS);
            var catalog = new FakeCatalog(List.of(new StreamCatalog.StreamSpec(STREAM, 1, 3, 0)));
            var controller = ReplicaSetController.replicaSetController(registry,
                                                                      SELF_REPLICA,
                                                                      liveMembers::get,
                                                                      () -> liveMembers.get().size(),
                                                                      catalog,
                                                                      (_, _) -> {},
                                                                      Runnable::run);
            // Reconcile pins the snapshot to the full 3-node view: SELF_REPLICA is registered a non-owner
            // replica and the reconciled placement owner is OWNER.
            controller.reconcile();
            assertThat(controller.roleFor(STREAM, PARTITION)).isEqualTo(ReplicaSetController.Role.REPLICA);

            // Diverge the LIVE membership to {SELF_REPLICA} alone WITHOUT reconciling: a fresh live read would
            // now rank SELF_REPLICA as its own HRW owner. A live-sourced backfill would owner-self-promote a
            // false CAUGHT_UP@-1 on its empty ring; the reconciled snapshot must keep it a non-owner.
            liveMembers.set(List.of(SELF_REPLICA));

            var backfill = partitionBackfill(registry,
                                             recovery,
                                             ownerSource(OWNER, 0L, eventsFrom(0, 8)),
                                             failIfProbed(),
                                             localHeadWatermark(),
                                             SELF_REPLICA,
                                             BOUND,
                                             new AtomicLong(0L)::get,
                                             controller::reconciledMembers);

            var applied = backfill.backfill(STREAM, PARTITION).await();

            // Pulled the real history from the reconciled HRW OWNER (8 events, 0..7) and reached CAUGHT_UP@7 —
            // NOT a false CAUGHT_UP@-1 from owner-self-promote off the divergent live view (which would have
            // tripped failIfProbed / left the ring empty).
            assertThat(applied.isSuccess()).isTrue();
            assertThat(applied.or(-1L)).isEqualTo(8L);
            var self = descriptorFor(SELF_REPLICA);
            assertThat(self.state()).isEqualTo(ReplicationState.CAUGHT_UP);
            assertThat(self.confirmedOffset()).isEqualTo(7L);
            var local = manager.readLocal(STREAM, PARTITION, 0, 100).or(List.of());
            assertThat(local).hasSize(8);
            for (var i = 0; i < 8; i++) {
                assertThat(local.get(i).offset()).isEqualTo((long) i);
            }
        }

        private SelfWatermark localHeadWatermark() {
            return (stream, partition) -> manager.partitionInfo(stream, partition)
                                                 .map(StreamPartitionManager.PartitionInfo::headOffset)
                                                 .or(-1L);
        }

        private ReplicaWatermarkProbe failIfProbed() {
            return (_, _, _) -> {
                throw new AssertionError("reconciled non-owner backfill must pull from the HRW owner, not probe peers");
            };
        }

        private CatchupTransport ownerSource(NodeId expectedOwner, long expectedFrom, List<EventData> events) {
            return (target, request) -> {
                assertThat(target).as("backfill must target the reconciled HRW owner").isEqualTo(expectedOwner);
                assertThat(request.fromOffset()).as("fromOffset must be local head + 1 (contiguous)")
                                                .isEqualTo(expectedFrom);
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
    }

    /// #445 Defect A: a NON-owner must NOT promote off an EMPTY owner read. During stream-owner failover a
    /// freshly-provisioned/re-elected HRW owner holds an empty ring while the acked history survives on a
    /// replica; the #333 "owner tail is the true watermark" assumption is VIOLATED, and the old code
    /// false-flipped the non-owner to CAUGHT_UP@-1 (truncating a stream whose watermark lived on the
    /// survivor). An EMPTY owner response now routes to the SAME probe-gated no-source path a cold-start
    /// non-owner uses: within the bound self stays SYNCING; past it self promotes ONLY when every peer is
    /// reachable and none is probed strictly ahead — so a survivor still ahead keeps self SYNCING (no
    /// truncation), while a genuinely-empty partition still promotes. A NON-empty owner response is unchanged
    /// (healthy backfills-with-data still promote immediately, no probe).
    @Nested
    class EmptyOwnerReadDoesNotFalsePromote {
        private static final NodeId NODE_AA = NodeId.nodeId("node-aa").unwrap();
        private static final NodeId NODE_BB = NodeId.nodeId("node-bb").unwrap();
        private static final NodeId NODE_CC = NodeId.nodeId("node-cc").unwrap();
        private static final TimeSpan BOUND = TimeSpan.timeSpan(10).seconds();
        private static final List<NodeId> MEMBERS = List.of(NODE_AA, NODE_BB, NODE_CC);
        private static final NodeId OWNER = ReplicaPlacement.rank(STREAM, PARTITION, MEMBERS).getFirst();
        // The two non-owners, ordered by NodeId (the axis losesTieBreak / peerNodeIds use): LOW < HIGH.
        private static final List<NodeId> NON_OWNERS = ReplicaPlacement.rank(STREAM, PARTITION, MEMBERS)
                                                                       .stream()
                                                                       .filter(node -> !node.equals(OWNER))
                                                                       .sorted()
                                                                       .toList();
        private static final NodeId LOW_NON_OWNER = NON_OWNERS.getFirst();
        private static final NodeId HIGH_NON_OWNER = NON_OWNERS.getLast();

        @Test
        void backfill_emptyOwnerRead_survivorProbedAhead_staysSyncing_noFalseCaughtUpAtMinusOne() {
            // A1: self is a non-owner with an EMPTY local ring. The HRW OWNER returns an EMPTY catch-up response
            // (fresh/re-elected owner, empty ring) — the run-13 trap. A surviving co-replica is probe-reachable
            // and holds the real watermark (24, strictly ahead of self's -1). Self must NOT false-flip
            // CAUGHT_UP@-1: the empty owner read routes to the probe path, which sees the survivor ahead and
            // keeps self SYNCING (no truncation of the survivor's history).
            var survivor = LOW_NON_OWNER;
            registry.registerReplica(STREAM, PARTITION, OWNER);
            registry.registerReplica(STREAM, PARTITION, survivor);
            registry.registerReplica(STREAM, PARTITION, HIGH_NON_OWNER); // self, SYNCING@-1, empty ring

            var clock = new AtomicLong(0L);
            var backfill = partitionBackfill(registry,
                                             recovery,
                                             emptyOwnerSource(OWNER),
                                             probeAheadSurvivor(survivor, 24L),
                                             localHeadWatermark(),          // empty ring -> -1
                                             HIGH_NON_OWNER,
                                             BOUND,
                                             clock::get,
                                             () -> MEMBERS);

            // Within the bound: no source, stays SYNCING (the probe is not even consulted yet).
            assertThat(backfill.backfill(STREAM, PARTITION).await().isFailure()).isTrue();
            assertThat(descriptorFor(HIGH_NON_OWNER).state()).isEqualTo(ReplicationState.SYNCING);

            // Past the bound: the probe runs, finds the survivor ahead (24 > -1), and self STAYS SYNCING —
            // never a false CAUGHT_UP@-1 off the empty owner.
            clock.set(BOUND.millis() + 1);
            assertThat(backfill.backfill(STREAM, PARTITION).await().isFailure()).isTrue();
            assertThat(descriptorFor(HIGH_NON_OWNER).state()).isEqualTo(ReplicationState.SYNCING);
            assertThat(descriptorFor(HIGH_NON_OWNER).confirmedOffset()).isEqualTo(-1L);
            // Local ring untouched (no truncation, nothing applied).
            assertThat(manager.readLocal(STREAM, PARTITION, 0, 100).or(List.of())).isEmpty();
        }

        @Test
        void backfill_emptyOwnerRead_genuinelyEmptyPartition_allPeersReachableNoneAhead_selfPromotesAtMinusOne() {
            // A2: EMPTY owner read, but the partition is GENUINELY empty — every reachable peer is at -1 and
            // none is ahead. The empty-system-stream case must still work: after the bound, self promotes@-1.
            // self is the LOWEST-NodeId non-owner (wins the -1 tie-break); the peer is the higher non-owner,
            // reachable at -1. (The owner lives in the member view but holds nothing and is not a registered
            // replica here — the cold-start state where only self+peer have registered.)
            var peer = HIGH_NON_OWNER;
            registry.registerReplica(STREAM, PARTITION, LOW_NON_OWNER); // self, blind@-1
            registry.registerReplica(STREAM, PARTITION, peer);          // blind@-1

            var clock = new AtomicLong(0L);
            var backfill = partitionBackfill(registry,
                                             recovery,
                                             emptyOwnerSource(OWNER),
                                             reachableProbeAt(peer, -1L),   // peer reachable, not ahead
                                             localHeadWatermark(),          // empty ring -> -1
                                             LOW_NON_OWNER,
                                             BOUND,
                                             clock::get,
                                             () -> MEMBERS);

            backfill.backfill(STREAM, PARTITION).await(); // arm the bound at t=0
            clock.set(BOUND.millis() + 1);                // bound elapsed
            assertThat(backfill.backfill(STREAM, PARTITION).await().isSuccess()).isTrue();
            assertThat(descriptorFor(LOW_NON_OWNER).state()).isEqualTo(ReplicationState.CAUGHT_UP);
            assertThat(descriptorFor(LOW_NON_OWNER).confirmedOffset()).isEqualTo(-1L);
            // The peer is untouched — only the winner promotes.
            assertThat(descriptorFor(peer).state()).isEqualTo(ReplicationState.SYNCING);
        }

        @Test
        void backfill_nonEmptyOwnerRead_promotesImmediatelyAtOwnerTail_noProbe() {
            // A3 (regression guard): the healthy path is unchanged. The owner returns NON-empty history
            // (events 0..7); self promotes to CAUGHT_UP@7 immediately, without consulting the probe.
            registry.registerReplica(STREAM, PARTITION, OWNER);
            registry.registerReplica(STREAM, PARTITION, HIGH_NON_OWNER); // self, SYNCING@-1

            var backfill = partitionBackfill(registry,
                                             recovery,
                                             ownerSource(OWNER, 0L, eventsFrom(0, 8)),
                                             failIfProbed(),
                                             localHeadWatermark(),
                                             HIGH_NON_OWNER,
                                             BOUND,
                                             new AtomicLong(0L)::get,
                                             () -> MEMBERS);

            var applied = backfill.backfill(STREAM, PARTITION).await();

            assertThat(applied.isSuccess()).isTrue();
            assertThat(applied.or(-1L)).isEqualTo(8L);
            var self = descriptorFor(HIGH_NON_OWNER);
            assertThat(self.state()).isEqualTo(ReplicationState.CAUGHT_UP);
            assertThat(self.confirmedOffset()).isEqualTo(7L);
            var local = manager.readLocal(STREAM, PARTITION, 0, 100).or(List.of());
            assertThat(local).hasSize(8);
            for (var i = 0; i < 8; i++) {
                assertThat(local.get(i).offset()).isEqualTo((long) i);
            }
        }

        private SelfWatermark localHeadWatermark() {
            return (stream, partition) -> manager.partitionInfo(stream, partition)
                                                 .map(StreamPartitionManager.PartitionInfo::headOffset)
                                                 .or(-1L);
        }

        // An owner catch-up source that asserts it is targeted at the HRW owner and returns an EMPTY response
        // (no payloads) from the requested offset — the fresh/re-elected empty-ring owner (#445).
        private CatchupTransport emptyOwnerSource(NodeId expectedOwner) {
            return (target, request) -> {
                assertThat(target).as("empty-owner backfill must target the HRW owner").isEqualTo(expectedOwner);
                return Promise.success(catchupResponse(target,
                                                       request.streamName(),
                                                       request.partition(),
                                                       request.fromOffset(),
                                                       request.fromOffset() - 1,
                                                       List.of(),
                                                       List.of()));
            };
        }

        private CatchupTransport ownerSource(NodeId expectedOwner, long expectedFrom, List<EventData> events) {
            return (target, request) -> {
                assertThat(target).as("backfill must target the HRW owner").isEqualTo(expectedOwner);
                assertThat(request.fromOffset()).as("fromOffset must be local head + 1 (contiguous)")
                                                .isEqualTo(expectedFrom);
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

        // Probe: the survivor is reachable and strictly ahead (`tail`); every other peer is reachable but empty
        // (-1). All reachable, so what keeps self SYNCING is the survivor being AHEAD, not an unreachable peer.
        private ReplicaWatermarkProbe probeAheadSurvivor(NodeId survivor, long tail) {
            return (target, _, _) -> target.equals(survivor)
                                     ? Promise.success(tail)
                                     : Promise.success(-1L);
        }

        // Probe: the given peer is reachable at `tail`; any other target is reachable at -1.
        private ReplicaWatermarkProbe reachableProbeAt(NodeId peer, long tail) {
            return (target, _, _) -> target.equals(peer)
                                     ? Promise.success(tail)
                                     : Promise.success(-1L);
        }

        private ReplicaWatermarkProbe failIfProbed() {
            return (_, _, _) -> {
                throw new AssertionError("non-empty owner backfill must pull from the owner, not probe peers");
            };
        }
    }

    /// #445 Defect B: a promoted HRW owner that is BEHIND a survivor CONFIRMED strictly ahead must never
    /// truncate. The old code, on a failed catch-up from that survivor, degraded (after the bound) to a
    /// self-promote at the LOCAL watermark — BELOW the survivor tail — silently truncating acked data. The
    /// fix keeps self SYNCING on a catch-up failure and lets the redrive retry: a transiently-unavailable
    /// survivor recovers on a later tick, and a genuinely-dead survivor leaves the member view, after which
    /// promoteOwner re-evaluates and self-promotes safely at the local watermark.
    @Nested
    class PromotedOwnerSurvivorCatchupFailureStaysSyncing {
        private static final NodeId NODE_AA = NodeId.nodeId("node-aa").unwrap();
        private static final NodeId NODE_BB = NodeId.nodeId("node-bb").unwrap();
        private static final NodeId NODE_CC = NodeId.nodeId("node-cc").unwrap();
        private static final NodeId NODE_DD = NodeId.nodeId("node-dd").unwrap();
        private static final TimeSpan BOUND = TimeSpan.timeSpan(10).seconds();
        private static final List<NodeId> MEMBERS = List.of(NODE_AA, NODE_BB, NODE_CC, NODE_DD);
        private static final NodeId OWNER = ReplicaPlacement.rank(STREAM, PARTITION, MEMBERS).getFirst();
        private static final NodeId SURVIVOR = ReplicaPlacement.rank(STREAM, PARTITION, MEMBERS)
                                                               .stream()
                                                               .filter(node -> !node.equals(OWNER))
                                                               .toList()
                                                               .getFirst();

        @Test
        void backfill_survivorConfirmedAhead_catchupFails_staysSyncingAcrossTicks_neverTruncates() {
            // B1: self is the promoted owner holding 0..4 (CAUGHT_UP@4); a survivor is registry-confirmed@19.
            // The catch-up transport FAILS on every tick. Self must stay SYNCING across the bound and never
            // self-promote at localWatermark 4 (< 19) — the truncation is impossible now.
            seedLocal(5); // local 0..4 -> head 4
            registry.registerReplica(STREAM, PARTITION, OWNER);
            registry.updateWatermark(STREAM, PARTITION, OWNER, 4L);      // self CAUGHT_UP@4 (behind)
            registry.registerReplica(STREAM, PARTITION, SURVIVOR);
            registry.updateWatermark(STREAM, PARTITION, SURVIVOR, 19L);  // survivor CAUGHT_UP@19

            CatchupTransport unreachable = (_, _) -> ReplicationError.General.REPLICATION_TIMEOUT.promise();
            var clock = new AtomicLong(0L);
            var backfill = ownerBackfill(unreachable, clock);

            // t=0 (within bound), t=BOUND+1 (bound elapsed), t=3*BOUND (well past): SYNCING@4 every time,
            // never CAUGHT_UP below the survivor tail.
            for (var tick : List.of(0L, BOUND.millis() + 1, BOUND.millis() * 3)) {
                clock.set(tick);
                assertThat(backfill.backfill(STREAM, PARTITION).await().isFailure()).isTrue();
                assertThat(descriptorFor(OWNER).state()).isEqualTo(ReplicationState.SYNCING);
                assertThat(descriptorFor(OWNER).confirmedOffset()).isEqualTo(4L);
            }
        }

        @Test
        void backfill_survivorConfirmedAhead_catchupSucceeds_promotesAtSurvivorTail() {
            // B2 (positive path unchanged): the catch-up SUCCEEDS — self pulls 5..19 from the survivor and
            // reaches CAUGHT_UP@19 (the full acked log), never the short local watermark.
            seedLocal(5); // local 0..4 -> head 4
            registry.registerReplica(STREAM, PARTITION, OWNER);
            registry.updateWatermark(STREAM, PARTITION, OWNER, 4L);
            registry.registerReplica(STREAM, PARTITION, SURVIVOR);
            registry.updateWatermark(STREAM, PARTITION, SURVIVOR, 19L);

            var backfill = ownerBackfill(survivorSource(SURVIVOR, 5L, eventsFrom(5, 15)), new AtomicLong(0L));

            var applied = backfill.backfill(STREAM, PARTITION).await();

            assertThat(applied.isSuccess()).isTrue();
            var self = descriptorFor(OWNER);
            assertThat(self.state()).isEqualTo(ReplicationState.CAUGHT_UP);
            assertThat(self.confirmedOffset()).isEqualTo(19L);
            var local = manager.readLocal(STREAM, PARTITION, 0, 100).or(List.of());
            assertThat(local).hasSize(20);
            for (var i = 0; i < 20; i++) {
                assertThat(local.get(i).offset()).isEqualTo((long) i);
            }
        }

        @Test
        void backfill_survivorLeavesMemberView_promotedOwnerSelfHeals_promotesAtLocalWatermark() {
            // B3 (self-heal, no permanent wedge): while the survivor is known-ahead and unreachable self stays
            // SYNCING. When the genuinely-dead survivor leaves the member view (unregistered by the reconciler),
            // promoteOwner re-evaluates: no survivor is ahead any more, so self safely self-promotes at its
            // local watermark (4) — the best available acked data. This is NOT a truncation: the survivor's
            // extra offsets are gone with it.
            seedLocal(5); // local 0..4 -> head 4
            registry.registerReplica(STREAM, PARTITION, OWNER);
            registry.updateWatermark(STREAM, PARTITION, OWNER, 4L);
            registry.registerReplica(STREAM, PARTITION, SURVIVOR);
            registry.updateWatermark(STREAM, PARTITION, SURVIVOR, 19L);

            CatchupTransport unreachable = (_, _) -> ReplicationError.General.REPLICATION_TIMEOUT.promise();
            var clock = new AtomicLong(BOUND.millis() + 1); // past the bound — old code would have truncated here
            var backfill = ownerBackfill(unreachable, clock);

            // Survivor present + unreachable: stays SYNCING (never truncates), even past the bound.
            assertThat(backfill.backfill(STREAM, PARTITION).await().isFailure()).isTrue();
            assertThat(descriptorFor(OWNER).state()).isEqualTo(ReplicationState.SYNCING);
            assertThat(descriptorFor(OWNER).confirmedOffset()).isEqualTo(4L);

            // The dead survivor leaves the member view (its replica descriptor is unregistered): now nothing is
            // ahead, so promoteOwner self-heals — self-promote at the local watermark, no permanent wedge.
            registry.unregisterReplica(STREAM, PARTITION, SURVIVOR);
            assertThat(backfill.backfill(STREAM, PARTITION).await().isSuccess()).isTrue();
            assertThat(descriptorFor(OWNER).state()).isEqualTo(ReplicationState.CAUGHT_UP);
            assertThat(descriptorFor(OWNER).confirmedOffset()).isEqualTo(4L);
        }

        private PartitionBackfill ownerBackfill(CatchupTransport transport, AtomicLong clock) {
            return partitionBackfill(registry,
                                     recovery,
                                     transport,
                                     failIfProbed(),
                                     localHeadWatermark(),
                                     OWNER,
                                     BOUND,
                                     clock::get,
                                     () -> MEMBERS);
        }

        private void seedLocal(int count) {
            for (var i = 0; i < count; i++) {
                recovery.appendRecoveredEvent(STREAM, PARTITION, ("event-" + i).getBytes(), 1000L + i).unwrap();
            }
        }

        private SelfWatermark localHeadWatermark() {
            return (stream, partition) -> manager.partitionInfo(stream, partition)
                                                 .map(StreamPartitionManager.PartitionInfo::headOffset)
                                                 .or(-1L);
        }

        private ReplicaWatermarkProbe failIfProbed() {
            return (_, _, _) -> {
                throw new AssertionError("promoted-owner catch-up must not probe a registry-known survivor");
            };
        }

        private CatchupTransport survivorSource(NodeId expectedSurvivor, long expectedFrom, List<EventData> events) {
            return (target, request) -> {
                assertThat(target).as("promoted owner must catch up from the confirmed survivor")
                                  .isEqualTo(expectedSurvivor);
                assertThat(request.fromOffset()).as("fromOffset must be local head + 1 (contiguous)")
                                                .isEqualTo(expectedFrom);
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
