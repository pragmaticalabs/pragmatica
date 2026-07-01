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
    /// re-includes such a replica in {@link PartitionBackfill#redriveCandidates} (owner-aware, offset-
    /// quiesced); re-arming `backfill` routes through the authoritative HRW owner and reaches CAUGHT_UP at
    /// the owner's TRUE tail. The genuine single-node owner-self-promote case is NOT re-armed.
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
        void redriveCandidates_caughtUpNonOwnerStaleAt0_reArmsBackfill_reachesOwnerTail() {
            // t0: empty member view, self is the SOLE registered replica. With no peer and the member view
            // empty, the cold-start lone-replica path self-promotes self to CAUGHT_UP@0 once the bound
            // elapses (the false-CAUGHT_UP@0 residual state).
            registry.registerReplica(STREAM, PARTITION, SELF_NON_OWNER);

            var members = new AtomicReference<>(List.<NodeId>of()); // empty at t0
            var clock = new AtomicLong(0L);
            var transport = ownerSource(OWNER, 0L, eventsFrom(0, 16)); // owner holds 0..15
            var backfill = partitionBackfill(registry,
                                             recovery,
                                             transport,
                                             reachableProbeNever(),
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

            // t1: member view populates; the HRW owner is now a DIFFERENT node holding 0..15. WITHOUT any
            // new live batch, the redrive must re-include this stale CAUGHT_UP@0 non-owner partition...
            members.set(POPULATED);
            assertThat(backfill.redriveCandidates()).containsExactly(PartitionKey.partitionKey(STREAM, PARTITION));

            // ...and re-arming backfill re-backfills from the owner, reaching CAUGHT_UP at the TRUE tail (15).
            var applied = backfill.backfill(STREAM, PARTITION).await();
            assertThat(applied.isSuccess()).isTrue();
            assertThat(descriptorFor(SELF_NON_OWNER).state()).isEqualTo(ReplicationState.CAUGHT_UP);
            assertThat(descriptorFor(SELF_NON_OWNER).confirmedOffset()).isEqualTo(15L);

            // Quiescence: once re-verified at the owner tail, the partition is no longer a redrive candidate
            // (no per-tick owner probe on a genuinely-complete replica).
            assertThat(backfill.redriveCandidates()).isEmpty();
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
        void backfill_promotedOwnerSurvivorUnreachable_boundedWaitThenDegradedPromoteAtLocal() {
            // A survivor confirms 0..19 but is UNREACHABLE (catch-up transport fails). Self must NOT wedge: it
            // stays non-authoritative (SYNCING) within the bound, then — once the bound elapses — promotes at
            // its LOCAL watermark (4) with a loud warning: a degraded recovery, not a wedge.
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
            var held = descriptorFor(OWNER);
            assertThat(held.state()).isEqualTo(ReplicationState.SYNCING);
            assertThat(held.confirmedOffset()).isEqualTo(4L);

            // Bound elapsed: proceed at the local watermark (degraded, but available — not wedged).
            clock.set(BOUND.millis() + 1);
            var applied = backfill.backfill(STREAM, PARTITION).await();
            assertThat(applied.isSuccess()).isTrue();
            var promoted = descriptorFor(OWNER);
            assertThat(promoted.state()).isEqualTo(ReplicationState.CAUGHT_UP);
            assertThat(promoted.confirmedOffset()).isEqualTo(4L); // best available offset
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
