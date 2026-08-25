// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.AlertManager;
import org.pragmatica.aether.api.routes.RetentionRoutes.RetentionInvariantWatch;
import org.pragmatica.aether.api.routes.RetentionRoutes.RetentionInvariantWatch.AlertSink;
import org.pragmatica.aether.api.routes.RetentionRoutes.RetentionPartitionView;
import org.pragmatica.aether.api.routes.RetentionRoutes.RetentionResponse;
import org.pragmatica.aether.api.routes.RetentionRoutes.WalDetail;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.EntityCheckpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.EntityFoldCheckpointValue;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.StreamPartitionManager.PartitionWalView;
import org.pragmatica.aether.stream.StreamPartitionManager.StreamWalView;
import org.pragmatica.aether.stream.StreamPartitionManager.WalSnapshot;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.aether.stream.wal.PartitionWal.WalStats;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.api.routes.RetentionRoutes.RetentionInvariantWatch.retentionInvariantWatch;

/// The #634-3/4 tri-floor join and its invariant, driven through the package-visible assembler seams —
/// the `ClusterTopologyRoutes` precedent for testing a decision that otherwise only runs behind an HTTP
/// route.
///
/// Every violation assertion here is ARMED by its opposite in the same test: a checker that flags
/// everything is as useless as one that flags nothing, and this one exists precisely because the reclaim
/// site cannot see all three floors and would false-alarm on the legitimate all-segments-reclaimed case.
///
/// Three of the four review MAJORs are pinned here rather than merely fixed: the alert severity must
/// pass the REAL `AlertManager` validator ([AlertBinding]), a raise must survive the two-tick debounce
/// the non-atomic cut demands ([InvariantWatch]), and the whole path must work through the production
/// entry point `tick` and not only through the test-visible `check` ([InvariantWatch#tick_raisesOnTheSecondObservation_forAMaterializedEmptyPartitionUnderACheckpoint]).
class RetentionRoutesTest {
    private static final String KEYSPACE = "orders";
    /// Deliberately EQUAL to the keyspace: the `entity:` prefix is the only thing separating an entity
    /// log's checkpoint from a same-named plain stream's, so the collision is exercised, not avoided.
    private static final String PLAIN_STREAM = "orders";
    private static final String ENTITY_STREAM = EntityPartitionArc.arcName(KEYSPACE);
    private static final int PARTITION = 0;
    private static final int SEGMENT_ONLY_PARTITION = 3;
    private static final long CHECKPOINT = 10L;
    private static final long RING_BUDGET = 64 * 1024 * 1024L;

    @Nested
    class CoveredFrom {
        /// Segments cover `[earliestSegment, sealedThrough]`, the ring `[ringTail, head]` and the WAL
        /// `(truncatedUpto, lastOffset]` — the floor is whichever reaches furthest back, and each source
        /// gets to win once so no branch of the min is dead.
        @Test
        void coveredFrom_prefersTheOldestSource() {
            var wal = Option.some(walStats(9L, 30L));

            assertThat(RetentionRoutes.coveredFrom(wal, 20L, 5L))
                .as("sealed segments reach furthest back")
                .isEqualTo(5L);
            assertThat(RetentionRoutes.coveredFrom(wal, 20L, -1L))
                .as("with the segments gone the WAL's replayable window starts at truncatedUpto + 1")
                .isEqualTo(10L);
            assertThat(RetentionRoutes.coveredFrom(Option.some(walStats(30L, 30L)), 20L, -1L))
                .as("a WAL whose whole window sits at or below the watermark covers nothing — records there are"
                    + " discarded on replay regardless of their physical presence, so only the ring is left")
                .isEqualTo(20L);
        }

        /// `-1` in, `-1` out. The manager now reports `-1` (not `0`) for an EMPTY materialized ring, so
        /// this is the input shape a restarted-empty partition actually produces — and the row it feeds
        /// is violated rather than quietly healthy when a checkpoint exists.
        @Test
        void coveredFrom_returnsMinusOne_whenNothingLocal() {
            assertThat(RetentionRoutes.coveredFrom(Option.none(), -1L, -1L))
                .as("no source holds anything here — distinct from 'covered from offset 0'")
                .isEqualTo(-1L);
        }
    }

    @Nested
    class AssembleRetention {
        @Test
        void assembleRetention_flagsViolation_whenNoSourceReachesCheckpointPlusOne() {
            var store = storeWithCheckpoint(KEYSPACE, PARTITION, CHECKPOINT);
            var violatedRow = onlyRow(RetentionRoutes.assembleRetention(ringOnlySnapshot(ENTITY_STREAM, PARTITION, 15L),
                                                                        new SegmentIndex(),
                                                                        store));
            var cleanRow = onlyRow(RetentionRoutes.assembleRetention(ringOnlySnapshot(ENTITY_STREAM, PARTITION, 11L),
                                                                     new SegmentIndex(),
                                                                     store));

            assertThat(violatedRow.checkpointFloor()).isEqualTo(CHECKPOINT);
            assertThat(violatedRow.coveredFrom()).isEqualTo(15L);
            assertThat(violatedRow.violated()).isTrue();
            assertThat(violatedRow.violation())
                .as("the message must name the exact gap a fold from the checkpoint would refuse over")
                .contains("records 11..14");
            assertThat(cleanRow.violated())
                .as("a source reaching checkpoint + 1 must NOT be flagged — else the verdict above proves nothing")
                .isFalse();
            assertThat(cleanRow.violation()).isEmpty();
        }

        /// The restarted-empty case (review catch): the row exists only because this node MATERIALIZED
        /// the partition, so "no local source at all" under a committed checkpoint is a violation, not
        /// an unevaluable `-1`. Treating it as unevaluable is what let a node come back holding nothing
        /// and report healthy — the precise blind spot this surface exists to close.
        @Test
        void assembleRetention_flagsViolation_whenMaterializedPartitionHoldsNothing() {
            var emptySnapshot = ringOnlySnapshot(ENTITY_STREAM, PARTITION, -1L);
            var violatedRow = onlyRow(RetentionRoutes.assembleRetention(emptySnapshot,
                                                                        new SegmentIndex(),
                                                                        storeWithCheckpoint(KEYSPACE,
                                                                                            PARTITION,
                                                                                            CHECKPOINT)));
            var uncheckpointedRow = onlyRow(RetentionRoutes.assembleRetention(emptySnapshot,
                                                                              new SegmentIndex(),
                                                                              emptyStore()));

            assertThat(violatedRow.coveredFrom()).as("no WAL, an empty ring and no segments hold nothing")
                                                 .isEqualTo(-1L);
            assertThat(violatedRow.violated()).isTrue();
            assertThat(violatedRow.violation())
                .as("the message must say the partition holds nothing, not name a records N..M range")
                .contains("no local source holds ANY history")
                .contains(String.valueOf(CHECKPOINT));
            assertThat(uncheckpointedRow.violated())
                .as("the SAME empty partition WITHOUT a committed checkpoint is normal — else every"
                    + " freshly materialized partition would page an operator")
                .isFalse();
        }

        /// A partition held ONLY as sealed segments (not materialized — no ring, no WAL) is still part of
        /// the picture: segments alone can satisfy or violate the invariant, so dropping the row would hide
        /// the very partitions whose history has been tiered away.
        @Test
        void assembleRetention_includesSegmentOnlyPartitions() {
            var segmentIndex = new SegmentIndex();

            segmentIndex.addSegment(PLAIN_STREAM, SEGMENT_ONLY_PARTITION, 100L, 200L);

            var response = RetentionRoutes.assembleRetention(snapshot(PLAIN_STREAM,
                                                                      PARTITION,
                                                                      Option.some(detailedStats()),
                                                                      300L),
                                                             segmentIndex,
                                                             emptyStore());
            var segmentRow = rowOf(response, PLAIN_STREAM, SEGMENT_ONLY_PARTITION);
            var walRow = rowOf(response, PLAIN_STREAM, PARTITION);

            assertThat(coordinatesOf(response)).containsExactly("orders:0", "orders:3");
            assertThat(segmentRow.wal().isEmpty()).isTrue();
            assertThat(segmentRow.ringTail()).isEqualTo(-1L);
            assertThat(segmentRow.earliestSegment()).isEqualTo(100L);
            assertThat(segmentRow.sealedThrough()).isEqualTo(200L);
            assertThat(segmentRow.coveredFrom()).as("segments are the only local source for this partition")
                                                .isEqualTo(100L);
            assertThat(walRow.wal().isPresent())
                .as("the materialized partition DOES report its WAL — else the absence above is not the"
                    + " segment-only shape")
                .isTrue();
            assertThat(detailOf(walRow).fsyncMeanMicros())
                .as("raw nanos become operator microseconds at read time: 8000ns over 4 forces is 2us")
                .isEqualTo(2.0);
            assertThat(detailOf(walRow).fsyncMaxMicros()).isEqualTo(3.0);
        }

        @Test
        void assembleRetention_neverFlagsNonEntityStreams() {
            var store = storeWithCheckpoint(KEYSPACE, PARTITION, CHECKPOINT);
            var plainRow = onlyRow(RetentionRoutes.assembleRetention(ringOnlySnapshot(PLAIN_STREAM, PARTITION, 50L),
                                                                     new SegmentIndex(),
                                                                     store));
            var entityRow = onlyRow(RetentionRoutes.assembleRetention(ringOnlySnapshot(ENTITY_STREAM, PARTITION, 50L),
                                                                      new SegmentIndex(),
                                                                      store));

            assertThat(plainRow.checkpointFloor())
                .as("a bare stream name is not an entity log — answering its floor out of the same-named"
                    + " keyspace's checkpoint is the collision the entity: prefix exists to make impossible")
                .isEqualTo(-1L);
            assertThat(plainRow.violated()).as("no checkpoint floor means nothing to violate, however late the sources")
                                           .isFalse();
            assertThat(entityRow.violated())
                .as("the SAME store and the SAME late sources under the PREFIXED name do violate — else the clean"
                    + " verdict above is vacuous")
                .isTrue();
        }
    }

    @Nested
    class WalTotals {
        @Test
        void walTotalBytes_sumsAcrossStreamsAndPartitions() {
            var snapshot = new WalSnapshot(List.of(new StreamWalView("alpha",
                                                                     List.of(sizedPartition(0, 100L),
                                                                             sizedPartition(1, 250L))),
                                                   new StreamWalView("beta",
                                                                     List.of(sizedPartition(0, 40L),
                                                                             wallessPartition(1)))));

            assertThat(RetentionRoutes.walTotalBytes(snapshot))
                .as("a partition with no WAL contributes nothing rather than skewing the node's disk total")
                .isEqualTo(390L);
        }

        @Test
        void walTotalBytes_isZero_whenNoPartitionHasAWal() {
            var snapshot = new WalSnapshot(List.of(new StreamWalView("alpha", List.of(wallessPartition(0)))));

            assertThat(RetentionRoutes.walTotalBytes(snapshot)).isZero();
        }
    }

    /// The severity constant is shared with the node's binding precisely so a case regression cannot
    /// hide: the first version raised a lowercase `"critical"`, which `AlertManager.isValidSeverity`
    /// rejects on an exact match, so every raise failed validation and the whole periodic half was
    /// inert while looking wired. This drives the REAL manager, not a stub — a stub would have accepted
    /// the lowercase string and proved nothing.
    @Nested
    class AlertBinding {
        @Test
        void alertSeverity_isAcceptedByTheRealAlertManager() {
            AlertManager.readOnly(emptyStore())
                        .inject(RetentionRoutes.ALERT_NAME,
                                RetentionRoutes.ALERT_SEVERITY,
                                "retention invariant violated for entity:orders:0",
                                Option.none(),
                                Option.none())
                        .await()
                        .onFailure(cause -> fail("the shared severity constant must pass the real validator: "
                                                 + cause.message()))
                        .onSuccess(response -> assertThat(response.severity()).isEqualTo(RetentionRoutes.ALERT_SEVERITY));
        }

        /// The arming half: the validator really is case-sensitive, so the constant's UPPERCASE spelling
        /// is load-bearing rather than cosmetic. Without this the test above would pass against a
        /// validator that accepted anything.
        @Test
        void alertSeverity_lowercased_isRejectedByTheRealAlertManager() {
            AlertManager.readOnly(emptyStore())
                        .inject(RetentionRoutes.ALERT_NAME,
                                RetentionRoutes.ALERT_SEVERITY.toLowerCase(Locale.ROOT),
                                "retention invariant violated for entity:orders:0",
                                Option.none(),
                                Option.none())
                        .await()
                        .onSuccess(_ -> fail("a lowercase severity must be REJECTED — else the uppercase pin above"
                                             + " cannot catch the regression it exists for"))
                        .onFailure(cause -> assertThat(cause.message().toLowerCase(Locale.ROOT)).contains("severity"));
        }
    }

    /// The periodic half. A raise needs TWO consecutive violated observations because the tri-floor join
    /// reads its three sources non-atomically — a truncate landing between reads can synthesize a
    /// one-tick phantom, and paging CRITICAL on a phantom is a false-alarm generator.
    @Nested
    class InvariantWatch {
        @Test
        void check_alertsOnce_onTheSecondConsecutiveViolation() {
            var raised = new ArrayList<String>();
            var watch = watchRecordingInto(raised);

            watch.check(responseOf(violatedRow()));

            assertThat(raised).as("one observation of a non-atomic cut is not evidence enough to page")
                              .isEmpty();

            watch.check(responseOf(violatedRow()));

            assertThat(raised).as("the second consecutive observation earns the raise")
                              .hasSize(1);

            watch.check(responseOf(violatedRow()));

            assertThat(raised).as("a violation already reported must not re-page every tick")
                              .hasSize(1);
            assertThat(raised.getFirst()).contains(RetentionRoutes.ALERT_NAME)
                                         .contains(ENTITY_STREAM + ":" + PARTITION);
        }

        /// The reason the debounce exists, stated as a test: a violation seen once and gone is the
        /// phantom shape a non-atomic cut produces, and it must never reach an operator.
        @Test
        void check_transientViolation_oneTick_neverRaises() {
            var raised = new ArrayList<String>();
            var watch = watchRecordingInto(raised);

            watch.check(responseOf(violatedRow()));
            watch.check(responseOf(cleanRow()));

            assertThat(raised).as("a single-tick violation is a phantom, not an incident")
                              .isEmpty();

            watch.check(responseOf(violatedRow()));
            watch.check(responseOf(violatedRow()));

            assertThat(raised).as("the SAME watch does raise on two consecutive observations — else the silence"
                                  + " above is a broken watch rather than a working debounce")
                              .hasSize(1);
        }

        @Test
        void check_alertsAgain_afterRecoveryAndRelapse() {
            var raised = new ArrayList<String>();
            var watch = watchRecordingInto(raised);

            watch.check(responseOf(violatedRow()));
            watch.check(responseOf(violatedRow()));
            watch.check(responseOf(cleanRow()));
            watch.check(responseOf(violatedRow()));

            assertThat(raised).as("recovery clears BOTH sets, so the relapse re-earns its two ticks rather than"
                                  + " raising on the first sighting")
                              .hasSize(1);

            watch.check(responseOf(violatedRow()));

            assertThat(raised).as("a genuine relapse is news again")
                              .hasSize(2);
        }

        @Test
        void check_raisesNothing_whenClean() {
            var raised = new ArrayList<String>();
            var watch = watchRecordingInto(raised);

            watch.check(responseOf(cleanRow()));
            watch.check(responseOf(cleanRow()));

            assertThat(raised).isEmpty();

            watch.check(responseOf(violatedRow()));
            watch.check(responseOf(violatedRow()));

            assertThat(raised).as("the SAME sink does raise on violated rows — else the silence above is an"
                                  + " unwired sink rather than a clean verdict")
                              .hasSize(1);
        }

        /// End to end through the PRODUCTION entry point, over real components rather than hand-built
        /// rows: `check` is package-visible and only tests call it, so every assertion above would stay
        /// green if `tick` had stopped calling it. This also exercises the two changes that make the
        /// restarted-empty case visible at all — the manager reporting `-1` for an empty materialized
        /// ring, and `violated` widening to cover a nothing-local partition under a checkpoint — and it
        /// is the only test that proves the watch reads the components it was BOUND to.
        @Test
        void tick_raisesOnTheSecondObservation_forAMaterializedEmptyPartitionUnderACheckpoint() {
            var manager = StreamPartitionManager.streamPartitionManager(RING_BUDGET);

            try {
                manager.createStream(singlePartitionEntityStream())
                       .onFailure(cause -> fail(cause.message()));

                var raised = new ArrayList<String>();
                var watch = retentionInvariantWatch(manager,
                                                    new SegmentIndex(),
                                                    storeWithCheckpoint(KEYSPACE, PARTITION, CHECKPOINT),
                                                    recordInto(raised));

                watch.tick();

                assertThat(raised).as("first observation only arms the debounce").isEmpty();

                watch.tick();

                assertThat(raised).as("a materialized-but-empty partition under a committed checkpoint must reach"
                                      + " the operator through tick(), not only through check()")
                                  .hasSize(1);
                assertThat(raised.getFirst()).contains(RetentionRoutes.ALERT_NAME)
                                             .contains(ENTITY_STREAM + ":" + PARTITION)
                                             .contains("no local source holds ANY history");
            } finally {
                manager.close();
            }
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    /// ONE partition with a deliberately small ring, so the `tick` proof allocates ~90 KiB of off-heap
    /// rather than the ~10 MiB the default 100k-event retention would reserve across four partitions,
    /// and so the assembled response has exactly one row to reason about.
    private static StreamConfig singlePartitionEntityStream() {
        return StreamConfig.streamConfig(ENTITY_STREAM,
                                         1,
                                         RetentionPolicy.retentionPolicy(1_000, 64 * 1024L, 60_000L),
                                         "latest");
    }

    /// The watch binds three components directly (no `ManageableNode`). `check`-driven tests never
    /// consult them, so they are supplied EMPTY rather than stubbed — a real manager with no stream
    /// allocates nothing, and an empty index/store answer honestly if anything ever does read them.
    private static RetentionInvariantWatch watchRecordingInto(List<String> raised) {
        return retentionInvariantWatch(StreamPartitionManager.streamPartitionManager(),
                                       new SegmentIndex(),
                                       emptyStore(),
                                       recordInto(raised));
    }

    private static AlertSink recordInto(List<String> raised) {
        return (name, message) -> raised.add(name + "|" + message);
    }

    /// Extracted so the fold's type variable is pinned by the return type — nested directly inside
    /// `assertThat` the poly expression is ambiguous to javac.
    private static WalDetail detailOf(RetentionPartitionView row) {
        return row.wal()
                  .fold(() -> fail("row " + row.stream() + "/" + row.partition() + " must carry WAL detail"),
                        detail -> detail);
    }

    private static WalStats walStats(long truncatedUpto, long lastOffset) {
        return new WalStats(1_024L, lastOffset, truncatedUpto, -1L, 1L, 1_000L, 1_000L);
    }

    /// 8000ns across 4 forces, slowest 3000ns — the numbers the microsecond conversion is pinned against.
    private static WalStats detailedStats() {
        return new WalStats(1_024L, 300L, 299L, -1L, 4L, 8_000L, 3_000L);
    }

    private static WalSnapshot snapshot(String stream, int partition, Option<WalStats> wal, long ringTail) {
        return new WalSnapshot(List.of(new StreamWalView(stream,
                                                         List.of(new PartitionWalView(partition, wal, ringTail, -1L)))));
    }

    /// A materialized partition whose ONLY history source is the in-memory ring — no WAL, no segments — so
    /// `ringTail` alone decides `coveredFrom` and the invariant assertions read off one input. Pass `-1`
    /// for the empty ring the manager now reports after a restart with nothing replayed.
    private static WalSnapshot ringOnlySnapshot(String stream, int partition, long ringTail) {
        return snapshot(stream, partition, Option.none(), ringTail);
    }

    private static PartitionWalView sizedPartition(int partition, long sizeBytes) {
        return new PartitionWalView(partition,
                                    Option.some(new WalStats(sizeBytes, 0L, -1L, -1L, 1L, 1_000L, 1_000L)),
                                    0L,
                                    -1L);
    }

    private static PartitionWalView wallessPartition(int partition) {
        return new PartitionWalView(partition, Option.none(), 0L, -1L);
    }

    private static RetentionResponse responseOf(RetentionPartitionView row) {
        return new RetentionResponse(0L, List.of(row));
    }

    private static RetentionPartitionView violatedRow() {
        return new RetentionPartitionView(ENTITY_STREAM,
                                          PARTITION,
                                          Option.none(),
                                          15L,
                                          -1L,
                                          -1L,
                                          CHECKPOINT,
                                          15L,
                                          true,
                                          "records 11..14 are on no local source");
    }

    private static RetentionPartitionView cleanRow() {
        return new RetentionPartitionView(ENTITY_STREAM,
                                          PARTITION,
                                          Option.none(),
                                          11L,
                                          -1L,
                                          -1L,
                                          CHECKPOINT,
                                          11L,
                                          false,
                                          "");
    }

    private static RetentionPartitionView onlyRow(RetentionResponse response) {
        assertThat(response.partitions()).hasSize(1);

        return response.partitions()
                       .getFirst();
    }

    private static RetentionPartitionView rowOf(RetentionResponse response, String stream, int partition) {
        return response.partitions()
                       .stream()
                       .filter(row -> row.stream().equals(stream) && row.partition() == partition)
                       .findFirst()
                       .orElseThrow(() -> new AssertionError("row not assembled: " + stream + "/" + partition));
    }

    private static List<String> coordinatesOf(RetentionResponse response) {
        return response.partitions()
                       .stream()
                       .map(row -> row.stream() + ":" + row.partition())
                       .toList();
    }

    private static KVStore<AetherKey, AetherValue> storeWithCheckpoint(String keyspace, int partition, long throughOffset) {
        var store = emptyStore();

        store.process(store.createBatch(List.of(checkpointPut(keyspace, partition, throughOffset))));

        return store;
    }

    private static KVCommand<AetherKey> checkpointPut(String keyspace, int partition, long throughOffset) {
        return new KVCommand.Put<AetherKey, AetherValue>(EntityCheckpointKey.entityCheckpointKey(keyspace, partition),
                                                         EntityFoldCheckpointValue.entityFoldCheckpointValue(throughOffset,
                                                                                                              "deadbeef"));
    }

    private static KVStore<AetherKey, AetherValue> emptyStore() {
        return new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    /// Nothing here restores a snapshot, so a read is a bug rather than a value worth stubbing.
    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                throw new UnsupportedOperationException("not used by this test");
            }
        };
    }
}
