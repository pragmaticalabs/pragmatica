// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.EntityCheckpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.EntityFoldCheckpointValue;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.StreamPartitionManager.PartitionWalView;
import org.pragmatica.aether.stream.StreamPartitionManager.WalSnapshot;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.aether.stream.wal.PartitionWal.WalStats;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// #634-3/4 — the TRI-FLOOR retention surface: per `(stream, partition)`, every local source of
/// history (WAL replayable window, in-memory ring, sealed segments) joined with the two retention
/// floors that drive reclamation (the durable sealed bound and the entity checkpoint), plus the
/// JOINT invariant evaluated over all of them.
///
/// ## Why the invariant lives here and nowhere else
/// The reclaim site (`RetentionEnforcer.isReclaimable`) already enforces the segment-side floor
/// structurally, so re-asserting it there would be vacuous — and the enforcer cannot see the WAL from
/// its module, so a checker there would false-alarm on the legitimate all-segments-reclaimed case: a
/// lying sensor (#634 ticket ruling). This assembler is the one place all three floors are visible,
/// so it is the one place the invariant can be evaluated honestly:
///
///   **an entity partition with a committed checkpoint must have SOME local source starting at or
///   below `checkpoint + 1`** — `coveredFrom <= checkpoint + 1`, where `coveredFrom` is the MINIMUM
///   of the sources' start offsets. This is deliberately the NECESSARY half of reachability, not the
///   sufficient one: the check does not prove the union of sources is hole-free up to the head
///   (reclamation is oldest-first on every mover, so an interior hole has no producer today — but a
///   clean verdict here means "no source starts too late", not "every record is present"). A
///   violation means a future fold cannot rebuild without serving state that is missing committed
///   writes, so it will REFUSE; this surface says so BEFORE that refusal is the first symptom. The
///   three sources are read as a NON-ATOMIC cut (snapshot, then segment index, then KV), which is
///   why the periodic watch debounces to two consecutive observations before raising.
///
/// ## Read shape
/// Assembled ON REQUEST from the manager's walSnapshot (volatile reads over the live registry), the
/// sealed-segment index, and committed KV checkpoint pointers — no hot-path accounting, per the
/// observability-first rule. The [RetentionInvariantWatch] nested helper is the periodic half:
/// today's alert evaluation only runs while a dashboard client is connected, so a violation nobody
/// polls for would otherwise stay invisible — the watch re-checks on a slow tick, WARN-logs and
/// raises an operator alert exactly once per newly-violated partition.
public final class RetentionRoutes implements RouteSource {
    /// The invariant alert's severity — UPPERCASE, because `AlertManager.isValidSeverity` is an
    /// exact case-sensitive match (review catch: a lowercase literal made every raise fail
    /// validation, rendering the whole periodic half inert). Public so the node's binding and the
    /// test that proves the real `AlertManager` accepts it share ONE constant.
    public static final String ALERT_SEVERITY = "CRITICAL";
    /// The invariant alert's name, shared the same way.
    public static final String ALERT_NAME = "retention-invariant";

    private final Supplier<ManageableNode> nodeSupplier;

    private RetentionRoutes(Supplier<ManageableNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static RetentionRoutes retentionRoutes(Supplier<ManageableNode> nodeSupplier) {
        return new RetentionRoutes(nodeSupplier);
    }

    /// One partition's tri-floor row. Offsets are `-1` when the source/floor is absent — absence is
    /// data here (a partition with no WAL is on the non-durable path; a `-1` checkpoint floor means
    /// no fold has ever checkpointed, or the stream is not an entity log).
    ///
    /// @param wal            the WAL's live counters, absent when this partition has no WAL
    /// @param ringTail       earliest offset still in the in-memory ring
    /// @param sealedThrough  the durable sealed bound — what WAL truncation chases
    /// @param earliestSegment earliest sealed-segment start offset still retained
    /// @param checkpointFloor entity checkpoint (`throughOffset`), `-1` when none
    /// @param coveredFrom    the MINIMUM start offset across local sources, `-1` when this node
    ///                       holds nothing replayable — which under a committed checkpoint is itself
    ///                       a violation (the restarted-empty case)
    /// @param violated       the tri-floor invariant failed: a checkpoint exists and either no local
    ///                       source starts at or below `checkpoint + 1`, or nothing local exists at all
    record RetentionPartitionView(String stream,
                                  int partition,
                                  Option<WalDetail> wal,
                                  long ringTail,
                                  long sealedThrough,
                                  long earliestSegment,
                                  long checkpointFloor,
                                  long coveredFrom,
                                  boolean violated,
                                  String violation) {}

    /// WAL counters in operator units: sizes in bytes, latencies in microseconds (mean derived from
    /// the raw accumulators at read time — the WAL reports raw count/total/max). `failStopped` is
    /// the #634-7 fail-stop state: this partition's WAL refused further appends after a failed
    /// fsync, publishes fail until the node restarts.
    record WalDetail(long sizeBytes,
                     long lastOffset,
                     long truncatedUpto,
                     long lastCompactedUpto,
                     long fsyncCount,
                     double fsyncMeanMicros,
                     double fsyncMaxMicros,
                     boolean failStopped) {}

    record RetentionResponse(long walTotalBytes, List<RetentionPartitionView> partitions) {}

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<RetentionResponse> route(ManagementRoute.STORAGE_RETENTION).toJson(this::retention));
    }

    private RetentionResponse retention() {
        var node = nodeSupplier.get();

        return assembleRetention(node.streamPartitionManager().walSnapshot(),
                                 node.streamSegmentIndex(),
                                 node.kvStore());
    }

    /// Package-visible assembler (the `ClusterTopologyRoutes` precedent) so the tri-floor join and the
    /// invariant are unit-testable off a seeded snapshot/index/store without the HTTP layer.
    static RetentionResponse assembleRetention(WalSnapshot snapshot,
                                               SegmentIndex segmentIndex,
                                               KVStore<AetherKey, AetherValue> kvStore) {
        var rows = new HashMap<PartitionCoordinate, RetentionPartitionView>();

        snapshot.streams()
                .forEach(stream -> stream.partitions()
                                         .forEach(partition -> putWalRow(rows,
                                                                         stream.stream(),
                                                                         partition,
                                                                         segmentIndex,
                                                                         kvStore)));
        segmentIndex.listPartitionKeys().forEach(key -> putSegmentOnlyRow(rows, key, segmentIndex, kvStore));
        var partitions = rows.values().stream().sorted(RetentionRoutes::byCoordinate).toList();

        return new RetentionResponse(walTotalBytes(snapshot), partitions);
    }

    /// Total live WAL bytes across every partition on this node — the number the storage capacity
    /// view (#634-3) folds into the `streams` instance, exposed here so both surfaces derive it from
    /// the SAME snapshot rather than two reads racing the append path.
    static long walTotalBytes(WalSnapshot snapshot) {
        return snapshot.streams()
                       .stream()
                       .flatMap(stream -> stream.partitions()
                                                .stream())
                       .flatMap(partition -> partition.wal()
                                                      .stream())
                       .mapToLong(WalStats::sizeBytes)
                       .sum();
    }

    private static void putWalRow(Map<PartitionCoordinate, RetentionPartitionView> rows,
                                  String stream,
                                  PartitionWalView view,
                                  SegmentIndex segmentIndex,
                                  KVStore<AetherKey, AetherValue> kvStore) {
        var coordinate = new PartitionCoordinate(stream, view.partition());

        rows.put(coordinate,
                 buildRow(coordinate,
                          view.wal(),
                          view.ringTailOffset(),
                          view.sealedThroughOffset(),
                          earliestSegmentOffset(segmentIndex, stream, view.partition()),
                          checkpointFloor(kvStore, stream, view.partition())));
    }

    /// A partition this node holds ONLY as sealed segments (not materialized — no ring, no WAL): still
    /// part of the tri-floor picture, because segments alone can satisfy (or violate) the invariant.
    private static void putSegmentOnlyRow(Map<PartitionCoordinate, RetentionPartitionView> rows,
                                          SegmentIndex.PartitionKey key,
                                          SegmentIndex segmentIndex,
                                          KVStore<AetherKey, AetherValue> kvStore) {
        var coordinate = new PartitionCoordinate(key.streamName(), key.partition());

        rows.computeIfAbsent(coordinate,
                             _ -> buildRow(coordinate,
                                           Option.none(),
                                           - 1L,
                                           segmentIndex.lastSealedOffset(key.streamName(), key.partition()),
                                           earliestSegmentOffset(segmentIndex, key.streamName(), key.partition()),
                                           checkpointFloor(kvStore, key.streamName(), key.partition())));
    }

    private static RetentionPartitionView buildRow(PartitionCoordinate coordinate,
                                                   Option<WalStats> wal,
                                                   long ringTail,
                                                   long sealedThrough,
                                                   long earliestSegment,
                                                   long checkpointFloor) {
        var coveredFrom = coveredFrom(wal, ringTail, earliestSegment);
        // A MATERIALIZED partition with a committed checkpoint and NO local source at all is
        // violated, not "unevaluable" (review catch): the row only exists because this node holds
        // the partition (ring materialized or segments retained), so nothing-local under a
        // checkpoint is the restarted-empty case — a fold from that checkpoint has nothing to read.
        var violated = checkpointFloor >= 0 && (coveredFrom < 0 || coveredFrom > checkpointFloor + 1);

        return new RetentionPartitionView(coordinate.stream(),
                                          coordinate.partition(),
                                          wal.map(RetentionRoutes::toWalDetail),
                                          ringTail,
                                          sealedThrough,
                                          earliestSegment,
                                          checkpointFloor,
                                          coveredFrom,
                                          violated,
                                          violated
                                          ? violationText(checkpointFloor, coveredFrom)
                                          : "");
    }

    /// The MINIMUM start offset across the local sources, or `-1` when none exists here: segments
    /// cover `[earliestSegment, sealedThrough]`, the ring covers `[ringTail, head]`, and the WAL's
    /// replayable window is `(truncatedUpto, lastOffset]` — records at or below the watermark are
    /// discarded on replay regardless of their physical presence. A min-of-starts, deliberately —
    /// see the type comment for why the invariant claims the necessary half of reachability only.
    static long coveredFrom(Option<WalStats> wal, long ringTail, long earliestSegment) {
        var floor = Long.MAX_VALUE;

        if (earliestSegment >= 0) {
            floor = Math.min(floor, earliestSegment);
        }

        if (ringTail >= 0) {
            floor = Math.min(floor, ringTail);
        }

        floor = Math.min(floor, walCoveredFrom(wal));

        return floor == Long.MAX_VALUE
               ? -1L
               : floor;
    }

    private static long walCoveredFrom(Option<WalStats> wal) {
        return wal.filter(stats -> stats.lastOffset() >= 0 && stats.lastOffset() > stats.truncatedUpto())
                  .map(stats -> stats.truncatedUpto() + 1)
                  .or(Long.MAX_VALUE);
    }

    /// The entity checkpoint floor for a stream partition: present only for `entity:`-namespaced
    /// streams with a committed [EntityFoldCheckpointValue]. Mirrors the retention wiring's own read
    /// (`AetherNode.entityRetentionFloor`) — same key, same absent-means-`-1` reading.
    static long checkpointFloor(KVStore<AetherKey, AetherValue> kvStore, String stream, int partition) {
        return EntityPartitionArc.keyspaceOf(stream)
                                 .flatMap(keyspace -> kvStore.getTyped(EntityCheckpointKey.entityCheckpointKey(keyspace,
                                                                                                               partition),
                                                                       EntityFoldCheckpointValue.class))
                                 .map(EntityFoldCheckpointValue::throughOffset)
                                 .or(-1L);
    }

    private static String violationText(long checkpointFloor, long coveredFrom) {
        return coveredFrom < 0
               ? "no local source holds ANY history for a partition checkpointed through " + checkpointFloor
                + " — a fold from the checkpoint would refuse"
               : "records " + (checkpointFloor + 1)
                + ".." + (coveredFrom - 1)
                + " are on no local source — a fold from the checkpoint would refuse";
    }

    private static long earliestSegmentOffset(SegmentIndex segmentIndex, String stream, int partition) {
        return segmentIndex.listSegments(stream, partition)
                           .stream()
                           .mapToLong(SegmentIndex.SegmentRef::startOffset)
                           .min()
                           .orElse(-1L);
    }

    private static WalDetail toWalDetail(WalStats stats) {
        return new WalDetail(stats.sizeBytes(),
                             stats.lastOffset(),
                             stats.truncatedUpto(),
                             stats.lastCompactedUpto(),
                             stats.fsyncCount(),
                             meanMicros(stats.fsyncTotalNanos(), stats.fsyncCount()),
                             stats.fsyncMaxNanos() / 1_000.0,
                             stats.failStopped());
    }

    private static double meanMicros(long totalNanos, long count) {
        return count > 0
               ? totalNanos / 1_000.0 / count
               : 0.0;
    }

    private static int byCoordinate(RetentionPartitionView left, RetentionPartitionView right) {
        var byStream = left.stream().compareTo(right.stream());

        return byStream != 0
               ? byStream
               : Integer.compare(left.partition(), right.partition());
    }

    private record PartitionCoordinate(String stream, int partition) {}

    /// The periodic half of the #634-4 invariant (owner-ruled: on-read + periodic alert). Re-checks on
    /// a slow tick, and for each NEWLY violated partition WARN-logs and raises ONE operator alert
    /// through the injection path — the metrics-threshold path is evaluated only while a dashboard
    /// client is connected, which is exactly the visibility gap this watch closes. A raise needs TWO
    /// consecutive violated ticks (the join is a non-atomic cut); a partition that leaves violation
    /// is forgotten, so a relapse re-earns its two ticks and alerts again.
    public static final class RetentionInvariantWatch {
        private static final Logger LOG = LoggerFactory.getLogger(RetentionInvariantWatch.class);

        private final StreamPartitionManager partitionManager;
        private final SegmentIndex segmentIndex;
        private final KVStore<AetherKey, AetherValue> kvStore;
        private final AlertSink alertSink;
        private final Set<String> pending = new HashSet<>();

        private final Set<String> alerted = new HashSet<>();

        /// Narrow raise seam so the watch is testable without an `AlertManager`; the node binds
        /// `AlertManager.inject` with [#ALERT_SEVERITY]. `void` + `@Contract` deliberately (the
        /// `EntityForwardRegistry` sink precedent): this is a notification sink with no outcome the
        /// caller could fold — the injection's own failure is logged at the binding site.
        public interface AlertSink {
            @Contract
            void raise(String name, String message);
        }

        private RetentionInvariantWatch(StreamPartitionManager partitionManager,
                                        SegmentIndex segmentIndex,
                                        KVStore<AetherKey, AetherValue> kvStore,
                                        AlertSink alertSink) {
            this.partitionManager = partitionManager;
            this.segmentIndex = segmentIndex;
            this.kvStore = kvStore;
            this.alertSink = alertSink;
        }

        /// Bound to the three SHARED components directly rather than to a `ManageableNode` instance
        /// (review catch: the node record is constructed twice on the management-enabled path, and a
        /// watch holding one instance would silently read the wrong record the moment it consulted
        /// anything non-shared — binding the components removes the hazard by construction).
        public static RetentionInvariantWatch retentionInvariantWatch(StreamPartitionManager partitionManager,
                                                                      SegmentIndex segmentIndex,
                                                                      KVStore<AetherKey, AetherValue> kvStore,
                                                                      AlertSink alertSink) {
            return new RetentionInvariantWatch(partitionManager, segmentIndex, kvStore, alertSink);
        }

        /// One check pass. Same lifted-catch shape as every periodic tick: `ScheduledExecutorService`
        /// cancels a task whose run throws, and a dead watch is indistinguishable from a healthy
        /// no-violation one.
        @Contract
        public void tick() {
            try {
                check(assembleRetention(partitionManager.walSnapshot(), segmentIndex, kvStore));
            } catch (RuntimeException e) {
                LOG.warn("Retention invariant check failed: {} — retried next tick", e.toString(), e);
            }
        }

        /// Package-visible for tests: evaluate one assembled response against the debounce state.
        ///
        /// TWO consecutive violated observations are required before a raise (review catch: the
        /// tri-floor join reads three sources non-atomically, so a truncate landing between reads can
        /// synthesize a one-tick phantom violation — paging `CRITICAL` on a single observation of a
        /// non-atomic cut is a false-alarm generator). A partition that clears leaves BOTH sets, so a
        /// genuine relapse starts the two-tick count again and re-alerts.
        @Contract
        void check(RetentionResponse response) {
            var violatedNow = new HashSet<String>();

            response.partitions()
                    .stream()
                    .filter(RetentionPartitionView::violated)
                    .forEach(row -> observeViolation(violatedNow, row));
            pending.retainAll(violatedNow);
            alerted.retainAll(violatedNow);
        }

        @Contract
        private void observeViolation(Set<String> violatedNow, RetentionPartitionView row) {
            var id = row.stream() + ":" + row.partition();

            violatedNow.add(id);
            if (!pending.add(id) && alerted.add(id)) {
                var message = "retention invariant violated for " + id + ": " + row.violation();

                LOG.warn("{}", message);
                alertSink.raise(ALERT_NAME, message);
            }
        }
    }
}
