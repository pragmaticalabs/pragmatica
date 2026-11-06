// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.pragmatica.aether.slice.RetentionDimensions;
import org.pragmatica.aether.slice.RetentionMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.stream.SegmentTierPressure;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.CoreError;
import org.pragmatica.lang.io.FileError;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.storage.EncryptionError;
import org.pragmatica.storage.SnapshotManager;
import org.pragmatica.storage.StorageError;
import org.pragmatica.storage.StorageGarbageCollector;
import org.pragmatica.storage.SharedDelete;
import org.pragmatica.storage.StorageInstance;
import org.pragmatica.storage.TierScope;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;


public final class RetentionEnforcer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RetentionEnforcer.class);
    /// Block reads one age-learning pass keeps in flight at most (#1616 R2).
    static final int AGE_READ_CONCURRENCY = 8;
    /// Identity sentinel for "no pass in flight".
    private static final Promise<Integer> IDLE = Promise.success(0);
    private static final TimeSpan DEFAULT_INTERVAL = TimeSpan.timeSpan(5 * 60 * 1000L).millis();
    /// The node default age of the factories that take no age: 24 h, `AetherNode.DEFAULT_STREAM_RETENTION_MS`.
    static final long DEFAULT_AGE_MS = 24 * 60 * 60 * 1000L;
    /// A stream withheld for this many consecutive passes raises [OperatorWarningCode#STREAM_RETENTION_WITHHELD].
    static final int WITHHELD_PASSES = 2;
    private static final String ENTITY_STREAM_PREFIX = "entity:";

    /// How far a partition's sealed log may be reclaimed without destroying state something still needs
    /// to recover (#345 I3).
    ///
    /// ## Why this exists
    /// An entity stream is ruled by the node default age whatever its config says (#2201 3.2 item 3). For ordinary
    /// event streams that is fine — a consumer that falls behind its retention window has missed events, which
    /// is the documented bargain.
    ///
    /// It is NOT fine for a durable entity, whose state IS its log. Under the plain age policy an
    /// entity's segments would be deleted a fixed interval after its last write, so a key written once
    /// and read for a year would silently cease to exist. That is data loss, not a missed event, and no
    /// later read could detect it.
    ///
    /// So a partition may declare how far deletion is safe. The contract is deliberately expressed as the
    /// highest offset that may be RECLAIMED rather than the lowest that must be kept, because the safe
    /// default is then the identity: [#NONE] returns [Long#MAX_VALUE], meaning the floor never blocks and
    /// the age policy alone decides, exactly as before this existed.
    @FunctionalInterface
    public interface SegmentRetentionFloor {
        /// The highest offset whose deletion cannot lose anything for `(streamName, partition)`.
        ///
        /// For an entity partition this is its committed checkpoint's `throughOffset`: everything at or
        /// below it is already folded into a durable snapshot, and everything above it is still the only
        /// copy of a mutation. An entity partition with NO checkpoint yet returns `-1` — nothing may be
        /// reclaimed, because nothing has been folded anywhere.
        long deletableThroughOffset(String streamName, int partition);
        /// The default for every stream that is not an entity log: no floor, so retention behaves exactly
        /// as it did before the floor existed.
        SegmentRetentionFloor NONE = (_, _) -> Long.MAX_VALUE;
    }

    private final StorageInstance storage;
    private final SegmentIndex index;
    /// Each stream's policy (#2201 Q6 = B); a stream it does not answer is withheld.
    private final StreamRetentionSource source;
    /// The age that rules a stream which declares no dimension, and every entity stream (#2201 3.2 item 3).
    private final long defaultAgeMs;
    private final SegmentRetentionFloor retentionFloor;
    private final RefDurability floorDurability;
    /// Reads the age of a segment rebuilt after a restart from its own block (#1604); none keeps such segments'
    /// age unknown.
    private final Option<SegmentReader> ageReader;
    private final SegmentTierPressure pressure;
    private final PressureRelief relief;
    /// Set while the durable tier is at or above [SegmentTierPressure#WARN_AT], so one episode warns once.
    private final AtomicBoolean underPressure = new AtomicBoolean(false);
    /// Segments whose block could not be read for its age for a reason no retry changes, remembered for the process
    /// lifetime (#1616 R3, #1630).
    private final Set<String> unreadableAges = ConcurrentHashMap.newKeySet();
    /// Per stream, the consecutive passes that withheld it; at [#WITHHELD_PASSES] it is raised once.
    private final Map<String, Integer> withheldPasses = new ConcurrentHashMap<>();
    private volatile OperatorWarningSink operatorWarnings = OperatorWarningSink.logOnly();
    private volatile String nodeId = "local";
    /// The run that deletes the segments this enforcer released from every tier (#2258); started and closed with it.
    private volatile Option<SharedSegmentCollection> sharedCollection = none();
    /// The pass in flight, or [#IDLE] (#1616 R2).
    private final AtomicReference<Promise<Integer>> passInFlight = new AtomicReference<>(IDLE);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> scheduledFuture;

    private RetentionEnforcer(StorageInstance storage,
                              SegmentIndex index,
                              StreamRetentionSource source,
                              long defaultAgeMs,
                              SegmentRetentionFloor retentionFloor,
                              RefDurability floorDurability,
                              Option<SegmentReader> ageReader,
                              SegmentTierPressure pressure,
                              PressureRelief relief) {
        this.storage = storage;
        this.index = index;
        this.source = source;
        this.defaultAgeMs = defaultAgeMs;
        this.retentionFloor = retentionFloor;
        this.floorDurability = floorDurability;
        this.ageReader = ageReader;
        this.pressure = pressure;
        this.relief = relief;
    }

    public static RetentionEnforcer retentionEnforcer(StorageInstance storage,
                                                      SegmentIndex index,
                                                      RetentionPolicy retentionPolicy) {
        return new RetentionEnforcer(storage,
                                     index,
                                     StreamRetentionSource.fixed(retentionPolicy),
                                     DEFAULT_AGE_MS,
                                     SegmentRetentionFloor.NONE,
                                     RefDurability.LIVE,
                                     none(),
                                     SegmentTierPressure.NONE,
                                     PressureRelief.NONE);
    }

    public static RetentionEnforcer retentionEnforcer(StorageInstance storage,
                                                      SegmentIndex index,
                                                      RetentionPolicy retentionPolicy,
                                                      SegmentRetentionFloor retentionFloor) {
        return new RetentionEnforcer(storage,
                                     index,
                                     StreamRetentionSource.fixed(retentionPolicy),
                                     DEFAULT_AGE_MS,
                                     retentionFloor,
                                     RefDurability.LIVE,
                                     none(),
                                     SegmentTierPressure.NONE,
                                     PressureRelief.NONE);
    }

    public static RetentionEnforcer retentionEnforcer(StorageInstance storage, SegmentIndex index, long retentionMs) {
        return new RetentionEnforcer(storage,
                                     index,
                                     ageOnly(retentionMs),
                                     retentionMs,
                                     SegmentRetentionFloor.NONE,
                                     RefDurability.LIVE,
                                     none(),
                                     SegmentTierPressure.NONE,
                                     PressureRelief.NONE);
    }

    public static RetentionEnforcer retentionEnforcer(StorageInstance storage,
                                                      SegmentIndex index,
                                                      long retentionMs,
                                                      SegmentRetentionFloor retentionFloor) {
        return new RetentionEnforcer(storage,
                                     index,
                                     ageOnly(retentionMs),
                                     retentionMs,
                                     retentionFloor,
                                     RefDurability.LIVE,
                                     none(),
                                     SegmentTierPressure.NONE,
                                     PressureRelief.NONE);
    }

    /// As [#retentionEnforcer(StorageInstance, SegmentIndex, long, SegmentRetentionFloor)], reading the age of a
    /// segment whose timestamp is unknown -- every segment rebuilt after a restart -- from its block (#1604).
    public static RetentionEnforcer retentionEnforcer(StorageInstance storage,
                                                      SegmentIndex index,
                                                      long retentionMs,
                                                      SegmentRetentionFloor retentionFloor,
                                                      SegmentReader ageReader) {
        return new RetentionEnforcer(storage,
                                     index,
                                     ageOnly(retentionMs),
                                     retentionMs,
                                     retentionFloor,
                                     RefDurability.LIVE,
                                     some(ageReader),
                                     SegmentTierPressure.NONE,
                                     PressureRelief.NONE);
    }

    /// The production wiring (#1278, #1604): the durable segment tier's pressure is watched and relieved, and the
    /// floor refs are made durable by a forced snapshot of `snapshots` before retention drops what they license --
    /// the metadata store reaches disk only through those snapshots. Both derive from the one manager, so a caller
    /// cannot pair a snapshot-backed store with a non-durable floor ([RefDurability#LIVE] would let a snapshot
    /// captured concurrently with the drops hold neither the dropped refs nor their floor).
    public static RetentionEnforcer retentionEnforcer(StorageInstance storage,
                                                      SegmentIndex index,
                                                      long retentionMs,
                                                      SegmentRetentionFloor retentionFloor,
                                                      SnapshotManager snapshots,
                                                      StorageGarbageCollector collector,
                                                      SegmentReader ageReader,
                                                      SegmentTierPressure pressure) {
        return retentionEnforcer(storage,
                                 index,
                                 retentionMs,
                                 retentionFloor,
                                 RefDurability.snapshotted(snapshots),
                                 ageReader,
                                 pressure,
                                 PressureRelief.snapshotBounded(snapshots, collector));
    }

    /// The production wiring with each stream's own policy (#2201 Q6 = B): `source` answers it, and `defaultAgeMs` rules a
    /// stream that declares no dimension and every entity stream.
    public static RetentionEnforcer retentionEnforcer(StorageInstance storage,
                                                      SegmentIndex index,
                                                      StreamRetentionSource source,
                                                      long defaultAgeMs,
                                                      SegmentRetentionFloor retentionFloor,
                                                      SnapshotManager snapshots,
                                                      StorageGarbageCollector collector,
                                                      SegmentReader ageReader,
                                                      SegmentTierPressure pressure) {
        return new RetentionEnforcer(storage,
                                     index,
                                     source,
                                     defaultAgeMs,
                                     retentionFloor,
                                     RefDurability.snapshotted(snapshots),
                                     some(ageReader),
                                     pressure,
                                     PressureRelief.snapshotBounded(snapshots, collector));
    }

    /// Per-stream retention without the snapshot-backed durability and pressure steps -- for tests that drive passes over
    /// a memory store. Package-private on purpose: production goes through the [SnapshotManager] overload.
    static RetentionEnforcer retentionEnforcer(StorageInstance storage,
                                               SegmentIndex index,
                                               StreamRetentionSource source,
                                               long defaultAgeMs,
                                               SegmentRetentionFloor retentionFloor,
                                               Option<SegmentReader> ageReader) {
        return new RetentionEnforcer(storage,
                                     index,
                                     source,
                                     defaultAgeMs,
                                     retentionFloor,
                                     RefDurability.LIVE,
                                     ageReader,
                                     SegmentTierPressure.NONE,
                                     PressureRelief.NONE);
    }

    /// Every stream answers an age-only policy that declares nothing, so `retentionMs` rules it as the node default age:
    /// the single node-wide age of the factories that predate per-stream retention.
    private static StreamRetentionSource ageOnly(long retentionMs) {
        return StreamRetentionSource.fixed(RetentionPolicy.retentionPolicy(Long.MAX_VALUE, Long.MAX_VALUE, retentionMs));
    }

    /// Where this node's operator warnings go, and the node they name (#2201 3.2 item 8).
    @Contract
    public void operatorWarnings(OperatorWarningSink sink, String nodeId) {
        this.operatorWarnings = sink;
        this.nodeId = nodeId;
        sharedCollection.onPresent(collection -> collection.operatorWarnings(sink, nodeId));
    }

    /// The shared-tier collection of the segments this enforcer releases (#2258), started and closed with this enforcer, so
    /// a node runs both from node start to node stop. Set before [#start].
    @Contract
    public void sharedCollection(SharedSegmentCollection collection) {
        collection.operatorWarnings(operatorWarnings, nodeId);
        this.sharedCollection = some(collection);
    }

    /// Whether the shared-tier collection is scheduled alongside this enforcer.
    public boolean isCollectingShared() {
        return sharedCollection.filter(SharedSegmentCollection::isScheduled)
                               .isPresent();
    }

    /// As above with the durability and relief steps supplied directly -- for tests that inject a failing or
    /// observing step. Package-private on purpose: production goes through the [SnapshotManager] overload.
    static RetentionEnforcer retentionEnforcer(StorageInstance storage,
                                               SegmentIndex index,
                                               long retentionMs,
                                               SegmentRetentionFloor retentionFloor,
                                               RefDurability floorDurability,
                                               SegmentReader ageReader,
                                               SegmentTierPressure pressure,
                                               PressureRelief relief) {
        return new RetentionEnforcer(storage,
                                     index,
                                     ageOnly(retentionMs),
                                     retentionMs,
                                     retentionFloor,
                                     floorDurability,
                                     some(ageReader),
                                     pressure,
                                     relief);
    }

    @Contract
    public void start() {
        start(DEFAULT_INTERVAL);
    }

    @Contract
    public void start(TimeSpan interval) {
        if (closed.get()) {
            return;
        }

        scheduledFuture = SharedScheduler.scheduleAtFixedRate(this::enforce, interval);
        sharedCollection.onPresent(SharedSegmentCollection::start);
        log.info("RetentionEnforcer started with interval={}ms, defaultAgeMs={}", interval.millis(), defaultAgeMs);
    }

    /// Whether this enforcer has a pass scheduled: started and not closed. A node starts its enforcer at node start and
    /// closes it at node stop (#2201 3.2 item 6); this is how that is observed.
    public boolean isScheduled() {
        return ! closed.get() && option(scheduledFuture).filter(future -> !future.isCancelled())
                                       .isPresent();
    }

    @Contract
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            option(scheduledFuture).onPresent(f -> f.cancel(false));
            sharedCollection.onPresent(SharedSegmentCollection::close);
            log.info("RetentionEnforcer stopped");
        }
    }

    @Contract
    void enforce() {
        enforceNow();
    }

    /// One retention pass: first learn the age of every segment whose age is unknown (#1604), then reclaim
    /// what the policy expires. Resolves with the number of segments whose refs were dropped.
    ///
    /// Single-flight (#1616 R2): ticks are fixed-rate and a pass after a restart can read many blocks, so a call
    /// while a pass is running joins that pass instead of starting a second one over the same segments.
    Promise<Integer> enforceNow() {
        if (closed.get()) {
            return Promise.success(0);
        }

        var mine = Promise.<Integer> promise();
        var running = passInFlight.compareAndExchange(IDLE, mine);

        return running == IDLE
               ? runPass(mine)
               : running;
    }

    private Promise<Integer> runPass(Promise<Integer> mine) {
        return learnUnknownAges().flatMap(_ -> reclaimExpired(System.currentTimeMillis()))
                               .map(this::relieveUnderPressure)
                               .fold(result -> finishPass(mine, result));
    }

    /// The slot is freed BEFORE the joined callers are resumed, so a call made after the pass resolved starts
    /// a fresh one.
    private Promise<Integer> finishPass(Promise<Integer> mine, Result<Integer> result) {
        passInFlight.set(IDLE);
        mine.resolve(result);

        return mine;
    }

    /// Under pressure the refs just dropped must free their blocks in this pass, not after the collector's
    /// grace period (#1604): [PressureRelief] makes the drops durable and collects exactly what they
    /// orphaned. Off pressure the normal GC cadence reclaims them.
    private int relieveUnderPressure(int removed) {
        if (pressure.utilization() >= SegmentTierPressure.WARN_AT) {
            var collected = relief.relieve();

            log.info("Disk pressure: retention dropped {} segment ref(s) and collected {} block(s) in the same pass",
                     removed,
                     collected);
        }

        reportPressure();

        return removed;
    }

    /// Once per pressure episode (#1604): the tier is at or above [SegmentTierPressure#WARN_AT], so seals
    /// will soon fail and owner publishes will be refused at [SegmentTierPressure#REFUSE_AT]. The episode is raised as
    /// [OperatorWarningCode#STREAM_SEGMENT_DISK_PRESSURE] and closed by [OperatorWarningCode#STREAM_SEGMENT_DISK_PRESSURE_RELIEVED]
    /// when the tier falls below the warning ratio (#2258, spec 3.2 item 8).
    @Contract
    private void reportPressure() {
        var utilization = pressure.utilization();

        if (utilization < SegmentTierPressure.WARN_AT) {
            relieveEpisode(utilization);

            return;
        }

        if (underPressure.compareAndSet(false, true)) {
            OperatorWarnings.raise(log,
                                   operatorWarnings,
                                   OperatorWarningCode.STREAM_SEGMENT_DISK_PRESSURE,
                                   pressureSubject(),
                                   "Durable stream segment tier is {}% full on this node (warning at {}%): stream seals fail when it "
                                  + "is full and owner publishes are refused (SEGMENT_TIER_FULL) from {}%. Raise [streaming] "
                                  + "segment_disk_max_bytes or add disk, or shorten retention",
                                   Math.round(utilization * 100),
                                   Math.round(SegmentTierPressure.WARN_AT * 100),
                                   Math.round(SegmentTierPressure.REFUSE_AT * 100));
        }
    }

    @Contract
    private void relieveEpisode(double utilization) {
        if (underPressure.compareAndSet(true, false)) {
            OperatorWarnings.raise(log,
                                   operatorWarnings,
                                   OperatorWarningCode.STREAM_SEGMENT_DISK_PRESSURE_RELIEVED,
                                   pressureSubject(),
                                   "Durable stream segment tier is {}% full on this node, below the {}% warning",
                                   Math.round(utilization * 100),
                                   Math.round(SegmentTierPressure.WARN_AT * 100));
        }
    }

    private String pressureSubject() {
        return "node " + nodeId + " storage streams";
    }

    /// Reclaim in three steps, so a restart never finds a partition's refs gone without the floor that says they
    /// were reclaimed (#1278): write each partition's new floor ref, make them durable together
    /// ([RefDurability]), and only then drop the expired segment refs. A floor that cannot be written or made
    /// durable reclaims nothing for its partition this pass -- the direction that keeps data.
    private Promise<Integer> reclaimExpired(long now) {
        var keys = index.listPartitionKeys();
        var policies = policiesOf(keys);
        var plans = keys.stream()
                        .flatMap(key -> option(policies.get(key.streamName())).flatMap(policy -> policy)
                                              .map(policy -> planReclaim(key, policy, now))
                                              .stream())
                        .filter(plan -> !plan.expired()
                                             .isEmpty())
                        .toList();

        if (plans.isEmpty()) {
            return Promise.success(0);
        }

        return Promise.allOf(plans.stream().map(this::writeFloor).toList()).map(written -> reclaimDurable(written.stream()
                                                                                                                 .flatMap(Result::stream)
                                                                                                                 .toList()));
    }

    private int reclaimDurable(List<ReclaimPlan> floored) {
        if (floored.isEmpty()) {
            return 0;
        }

        return floorDurability.persist()
                              .onFailure(RetentionEnforcer::logFloorNotDurable)
                              .map(_ -> reclaimAll(floored))
                              .or(0);
    }

    private int reclaimAll(List<ReclaimPlan> floored) {
        var totalRemoved = floored.stream().mapToInt(this::reclaim).sum();

        if (totalRemoved > 0) {
            log.info("Retention enforcement removed {} expired segment(s)", totalRemoved);
        }

        return totalRemoved;
    }

    /// The expired segments of one partition and the floor their reclamation records: the highest end offset
    /// among them.
    ///
    /// `incarnation` is the life of the stream the plan was decided under (#1278 review): the floor is written under
    /// that life's name, and nothing is dropped or recorded if the stream was destroyed or recreated before the
    /// floor became durable.
    private record ReclaimPlan(String streamName,
                               long incarnation,
                               int partition,
                               List<SegmentIndex.SegmentRef> expired,
                               long through) {
        static ReclaimPlan reclaimPlan(String streamName,
                                       long incarnation,
                                       int partition,
                                       List<SegmentIndex.SegmentRef> expired) {
            return new ReclaimPlan(streamName,
                                   incarnation,
                                   partition,
                                   expired,
                                   expired.stream().mapToLong(SegmentIndex.SegmentRef::endOffset).max().orElse(-1L));
        }

        String floorRef(long floor) {
            return SegmentIndex.floorRefName(SegmentIndex.durableName(streamName, incarnation), partition, floor);
        }
    }

    private ReclaimPlan planReclaim(SegmentIndex.PartitionKey key, RetentionPolicy policy, long now) {
        return ReclaimPlan.reclaimPlan(key.streamName(),
                                       index.incarnationOf(key.streamName()),
                                       key.partition(),
                                       findExpiredSegments(key.streamName(), key.partition(), policy, now));
    }

    /// The policy that judges each stream of this pass, or none for a stream withheld this pass; the withheld transitions
    /// are reported here, once per pass (#2201 3.2 items 2 and 8).
    private Map<String, Option<RetentionPolicy>> policiesOf(Set<SegmentIndex.PartitionKey> keys) {
        var policies = new java.util.HashMap<String, Option<RetentionPolicy>>();

        keys.forEach(key -> policies.computeIfAbsent(key.streamName(), this::effectivePolicy));
        reportWithheld(policies);

        return policies;
    }

    /// An entity stream keeps the node default age whatever its config says: its configured ring age stands in for
    /// "never" and its checkpoint floor protects its segments (#2201 3.2 item 3). A stream that declares no dimension is
    /// ruled by the node default age, today's rule. A stream the source does not answer, or whose mode this node cannot
    /// read, is withheld.
    private Option<RetentionPolicy> effectivePolicy(String streamName) {
        if (streamName.startsWith(ENTITY_STREAM_PREFIX)) {
            return some(defaultAgePolicy());
        }

        return source.policyOf(streamName)
                     .filter(policy -> policy.mode() != RetentionMode.UNKNOWN)
                     .map(policy -> policy.declared()
                                          .any()
                                    ? policy
                                    : defaultAgePolicy());
    }

    private RetentionPolicy defaultAgePolicy() {
        return RetentionPolicy.retentionPolicy(Long.MAX_VALUE, Long.MAX_VALUE, defaultAgeMs).withDeclared(RetentionDimensions.AGE);
    }

    /// A stream withheld for [#WITHHELD_PASSES] consecutive passes is raised once; the first pass that judges it again
    /// closes it. A raised stream that is gone from this node's index is closed as well.
    @Contract
    private void reportWithheld(Map<String, Option<RetentionPolicy>> policies) {
        policies.forEach((streamName, policy) -> policy.onPresent(_ -> resumeWithheld(streamName,
                                                                                      "its retention policy is readable again"))
                                                       .onEmpty(() -> countWithheld(streamName)));
        new HashSet<>(withheldPasses.keySet()).stream()
                                              .filter(streamName -> !policies.containsKey(streamName))
                                              .forEach(streamName -> resumeWithheld(streamName,
                                                                                    "this node no longer holds its sealed segments"));
    }

    @Contract
    private void countWithheld(String streamName) {
        if (withheldPasses.merge(streamName, 1, Integer::sum) == WITHHELD_PASSES) {
            OperatorWarnings.raise(log,
                                   operatorWarnings,
                                   OperatorWarningCode.STREAM_RETENTION_WITHHELD,
                                   streamSubject(streamName),
                                   "Retention on node {} has withheld the sealed history of stream {} for {} passes: this node has no "
                                  + "retention policy it can read for it (its committed config is not applied here yet, or its "
                                  + "retention mode is unknown to this build). Nothing of it is reclaimed until it is readable; "
                                  + "its disk use grows meanwhile",
                                   nodeId,
                                   streamName,
                                   WITHHELD_PASSES);
        }
    }

    @Contract
    private void resumeWithheld(String streamName, String reason) {
        option(withheldPasses.remove(streamName)).filter(passes -> passes >= WITHHELD_PASSES)
              .onPresent(_ -> OperatorWarnings.raise(log,
                                                     operatorWarnings,
                                                     OperatorWarningCode.STREAM_RETENTION_RESUMED,
                                                     streamSubject(streamName),
                                                     "Retention on node {} judges stream {} again: {}",
                                                     nodeId,
                                                     streamName,
                                                     reason));
    }

    private String streamSubject(String streamName) {
        return "node " + nodeId + " stream " + streamName;
    }

    private String segmentSubject(String streamName, int partition, SegmentIndex.SegmentRef ref) {
        return "node " + nodeId
             + " stream " + streamName
             + "[" + partition
             + "] " + ref.startOffset()
             + "-" + ref.endOffset();
    }

    /// A floor no higher than the one already recorded needs no write: the recorded one is durable already.
    private Promise<ReclaimPlan> writeFloor(ReclaimPlan plan) {
        if (plan.through() <= index.reclaimedThrough(plan.streamName(), plan.partition())) {
            return Promise.success(plan);
        }
        // #2258: a floor is a fact of this node, read back from its ref NAME only, so its block stays on private tiers.
        return storage.putRef(plan.floorRef(plan.through()),
                              encodeFloor(plan.through()),
                              TierScope.PRIVATE)
                      .map(_ -> plan)
                      .onFailure(cause -> logFloorWriteFailure(plan, cause));
    }

    static byte[] encodeFloor(long through) {
        return ByteBuffer.allocate(Long.BYTES)
                         .putLong(through)
                         .array();
    }

    /// The floor is durable: drop the segment refs it licenses, record it, and drop the floor it supersedes. The
    /// superseded floor goes last, so a crash leaves at most two floors and the higher one wins on rebuild.
    private int reclaim(ReclaimPlan plan) {
        if (plan.incarnation() != index.incarnationOf(plan.streamName())) {
            return 0;
        }

        var previous = index.reclaimedThrough(plan.streamName(), plan.partition());
        var released = 0;
        var through = previous;

        for (var ref : plan.expired()) {
            if (!releaseSegment(plan.streamName(), plan.partition(), ref)) {
                break;
            }

            released++;
            through = ref.endOffset();
        }

        if (released == 0) {
            return 0;
        }

        index.recordReclaimed(plan.streamName(), plan.incarnation(), plan.partition(), through);
        if (previous >= 0 && previous < through) {
            dropFloor(plan, previous);
        }

        return released;
    }

    @Contract
    private void dropFloor(ReclaimPlan plan, long through) {
        storage.deleteRef(plan.floorRef(through))
               .onFailure(cause -> log.warn("Failed to drop the superseded reclaimed-through floor {}/{}:{}: {}",
                                            plan.streamName(),
                                            plan.partition(),
                                            through,
                                            cause.message()));
    }

    private static void logFloorWriteFailure(ReclaimPlan plan, Cause cause) {
        log.warn("Retention reclaimed nothing for {}/{} this pass: its reclaimed-through floor {} could not be written: {}",
                 plan.streamName(),
                 plan.partition(),
                 plan.through(),
                 cause.message());
    }

    private static void logFloorNotDurable(Cause cause) {
        log.warn("Retention reclaimed nothing this pass: the reclaimed-through floors could not be made durable: {}",
                 cause.message());
    }

    /// A segment rebuilt from its ref name after a restart has no timestamp, and under the age policy it
    /// would never expire (#1604). Its block's own events carry the true event time, so it is read once and
    /// the result kept in the index; a block that cannot be read keeps its age unknown -- withheld, the
    /// direction that cannot delete data -- and is tried again next pass.
    ///
    /// Bounded (#1616 R2): each read decodes a whole block into heap, and after a restart every segment is
    /// unknown-aged, so the reads go [#AGE_READ_CONCURRENCY] at a time rather than all at once. A block that
    /// could not be read is remembered for the life of the process and not read again.
    private Promise<Unit> learnUnknownAges() {
        return ageReader.fold(Promise::unitPromise, reader -> learnInBatches(reader, pendingAges(), 0));
    }

    private List<PendingAge> pendingAges() {
        return index.listPartitionKeys()
                    .stream()
                    .flatMap(this::pendingAges)
                    .toList();
    }

    private Stream<PendingAge> pendingAges(SegmentIndex.PartitionKey key) {
        return index.listSegments(key.streamName(),
                                  key.partition())
                    .stream()
                    .filter(ref -> ref.maxTimestamp() <= 0)
                    .map(ref -> new PendingAge(key, ref))
                    .filter(pending -> !unreadableAges.contains(pending.id()));
    }

    /// A LOOP over batches of [#AGE_READ_CONCURRENCY], not a `flatMap` per batch (the #1392 / #1395 shape). A batch
    /// whose reads are already settled (a memory tier answers synchronously) used to run the next batch inline, so a
    /// pass over k batches nested k frame groups, measured at 6 frames per batch: after a restart every segment is
    /// unknown-aged and the sealer made one segment per evicted record before it sealed runs (#2201 C-02), so 25,000 pending segments is about 3,100
    /// batches and about 19,000 frames, past a 1 MB stack. A settled batch is consumed in place; only a pending one
    /// suspends the loop, which resumes on the thread that settles it.
    private Promise<Unit> learnInBatches(SegmentReader reader, List<PendingAge> pending, int from) {
        var output = Promise.<Unit> promise();

        learnLoop(reader, pending, from, output);

        return output;
    }

    @Contract
    private void learnLoop(SegmentReader reader, List<PendingAge> pending, int from, Promise<Unit> output) {
        var start = from;

        while (start < pending.size()) {
            var end = Math.min(start + AGE_READ_CONCURRENCY, pending.size());
            var batch = learnBatch(reader, pending.subList(start, end));

            if (!batch.isResolved()) {
                batch.onResult(result -> resumeLearn(result, reader, pending, end, output));

                return;
            }

            if (settledResult(batch) instanceof Result.Failure<Unit>(var cause)) {
                output.fail(cause);

                return;
            }

            start = end;
        }

        output.succeed(unit());
    }

    /// Continues the loop after a batch that settled off-thread; a failed batch ends the pass.
    @Contract
    private void resumeLearn(Result<Unit> result,
                             SegmentReader reader,
                             List<PendingAge> pending,
                             int next,
                             Promise<Unit> output) {
        if (result instanceof Result.Failure<Unit>(var cause)) {
            output.fail(cause);
        } else {
            learnLoop(reader, pending, next, output);
        }
    }

    /// The result of a promise the caller has checked is resolved: `Promise.onResult` runs its consumer inline on a
    /// settled promise, so the holder is filled before this returns. Not `await()`: that is the blocking join.
    private static <T> Result<T> settledResult(Promise<T> resolved) {
        var holder = new AtomicReference<Result<T>>();

        resolved.onResult(holder::set);

        return holder.get();
    }

    /// One batch of age reads. A read that fails or throws is already recovered per segment ([#learnAge]); what is left
    /// is a synchronous throw while STARTING a read, which fails the batch and so the pass, exactly once.
    private Promise<Unit> learnBatch(SegmentReader reader, List<PendingAge> batch) {
        return Result.lift(() -> batch.stream()
                                      .map(age -> learnAge(reader, age))
                                      .toList()).fold(Cause::promise,
                                                      promises -> Promise.allOf(promises).map(_ -> unit()));
    }

    private Promise<Unit> learnAge(SegmentReader reader, PendingAge pending) {
        return reader.segmentFacts(pending.key().streamName(),
                                   pending.key().partition(),
                                   pending.ref())
                     .map(facts -> recordLearned(pending.key(),
                                                 pending.ref(),
                                                 facts))
                     .recover(cause -> ageUnreadable(pending, cause));
    }

    /// A segment whose age is still to be learned; `id` names it for the unreadable set.
    private record PendingAge(SegmentIndex.PartitionKey key, SegmentIndex.SegmentRef ref) {
        String id() {
            return idOf(key.streamName(), key.partition(), ref);
        }

        static String idOf(String streamName, int partition, SegmentIndex.SegmentRef ref) {
            return streamName + "/" + partition + "/" + ref.startOffset();
        }
    }

    /// The age and, in the same read, the size the sealed tier's byte rule counts (#2201 3.2 item 4).
    private Unit recordLearned(SegmentIndex.PartitionKey key,
                               SegmentIndex.SegmentRef ref,
                               SegmentReader.SegmentFacts facts) {
        index.recordLearned(key.streamName(),
                            key.partition(),
                            ref.startOffset(),
                            facts.maxTimestamp(),
                            facts.size(),
                            facts.identity());

        return unit();
    }

    /// FER: the segment stays unknown-aged, which withholds it from age-based reclamation; nothing is lost.
    ///
    /// Only a TRANSIENT failure is retried (#1630, inverting #1616 R3's allow-list): a timeout or an I/O error
    /// ([#isTransient]) is not remembered, and the next pass retries it -- remembering it would pin the segment
    /// against age-out until a restart, which is exactly what pressure relief needs to reclaim. EVERY OTHER failure
    /// is deterministic: a block that is gone, that fails its content check ([StorageError.IntegrityError]), that
    /// cannot be decrypted with the keys this node holds ([EncryptionError], a missing or wrong key), or that does
    /// not decode will fail the same way on every pass, so it is remembered, not read again, and counted into
    /// the one [OperatorWarningCode#STREAM_SEGMENT_UNREADABLE] it raises. An allow-list of deterministic causes let every unlisted one -- a missing
    /// key among them -- be re-read on every pass forever, withheld and logged only at DEBUG.
    private Unit ageUnreadable(PendingAge pending, Cause cause) {
        if (!isTransient(cause) && unreadableAges.add(pending.id())) {
            warnUnreadable(pending, cause);
        }

        log.debug("Age of segment {}:[{}-{}] could not be read from its block; it stays withheld: {}",
                  pending.id(),
                  pending.ref().startOffset(),
                  pending.ref().endOffset(),
                  cause.message());

        return unit();
    }

    /// What a retry can change:
    ///   - a cause classed transient ([Cause#isTransient]): a promise timeout ([CoreError.Timeout]), a DHT client
    ///     timeout or unreachable peer;
    ///   - an I/O error reading a block file. The disk tier reports it as [FileError.ReadFailed] -- what `FileOps`
    ///   maps any `IOException` to (EMFILE, EIO, a permission flake, a file removed mid-read) -- or as
    ///   [StorageError.ReadError] for an exception thrown out of the read. A MISSING block file is neither: the tier
    ///   answers "absent", which reaches here as `SEGMENT_DATA_NOT_FOUND` and stays deterministic (#1639 B1);
    ///   - a tier not yet admitted for reads ([StorageError.TierNotAdmitted], a bounded wait on the DHT marker check);
    ///   - an exhausted VM ([VirtualMachineError], e.g. out of memory decoding a big block). `Promise` fails the
    ///     dependent promise with a [CoreError.Exception] carrying it BEFORE rethrowing it (#1311), so the failure
    ///     does reach this classifier; it says nothing about the block, and remembering it would pin the segment.
    private static boolean isTransient(Cause cause) {
        return cause.isTransient() || cause instanceof FileError.ReadFailed || cause instanceof StorageError.ReadError || cause instanceof StorageError.TierNotAdmitted || exhaustedVm(cause);
    }

    private static boolean exhaustedVm(Cause cause) {
        return cause instanceof CoreError.Exception escaped && escaped.cause() instanceof VirtualMachineError;
    }

    /// Once per segment, when its age read fails for a reason no retry changes (#2201 3.2 item 8). It no longer holds the
    /// prefix for ever: it takes the age of the next newer segment whose age is known ([SealedRetentionRule]), and its
    /// reclamation closes this.
    @Contract
    private void warnUnreadable(PendingAge pending, Cause cause) {
        OperatorWarnings.raise(log,
                               operatorWarnings,
                               OperatorWarningCode.STREAM_SEGMENT_UNREADABLE,
                               segmentSubject(pending.key().streamName(),
                                              pending.key().partition(),
                                              pending.ref()),
                               "The block of stream {}[{}] at offsets {}-{} on node {} cannot be read for its age ({}), for example a "
                              + "missing encryption key or a damaged block; it is not read again until the node restarts, and "
                              + "retention judges it by the age of the next newer segment",
                               pending.key().streamName(),
                               pending.key().partition(),
                               pending.ref().startOffset(),
                               pending.ref().endOffset(),
                               nodeId,
                               cause.message());
    }

    /// The oldest segments the policy expires, a prefix only ([SealedRetentionRule]). The floor can only ever withhold a
    /// segment, never cause one to go; it is also capped at the contiguous sealed watermark (#1278): the reclaimed-through
    /// floor recorded for the segments taken anchors the rebuilt watermark after a restart, so reclaiming a segment above
    /// a hole would let the floor claim the hole as sealed. A segment that straddles the bound is kept whole.
    private List<SegmentIndex.SegmentRef> findExpiredSegments(String streamName,
                                                              int partition,
                                                              RetentionPolicy policy,
                                                              long now) {
        var segments = index.listSegments(streamName, partition);
        var deletableThrough = Math.min(retentionFloor.deletableThroughOffset(streamName, partition),
                                        index.lastSealedOffset(streamName, partition));
        var prefix = SealedRetentionRule.reclaimablePrefix(segments,
                                                           policy,
                                                           deletableThrough,
                                                           now,
                                                           unreadableStarts(streamName, partition));

        return List.copyOf(segments.subList(0, prefix));
    }

    private Set<Long> unreadableStarts(String streamName, int partition) {
        var prefix = streamName + "/" + partition + "/";

        return unreadableAges.stream()
                             .filter(id -> id.startsWith(prefix))
                             .map(id -> Long.parseLong(id.substring(prefix.length())))
                             .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /// Releases a reclaimed segment (#2258, spec 3.2 item 6): out of the index first, so a reader that consults the index
    /// afterwards answers `CursorExpired` instead of meeting a missing block, then its ref, with the shared-delete authority
    /// its identity mark allows ([#authorityOf]). The block itself goes later, in the collector, once the release is in a
    /// durable snapshot; this never deletes a block. A release that fails (node-local metadata) puts the segment back and
    /// ends the partition's pass there, so retention leaves no interior gap.
    private boolean releaseSegment(String streamName, int partition, SegmentIndex.SegmentRef ref) {
        var refName = index.refNameOf(streamName, partition, ref);

        index.removeSegment(streamName, partition, ref.startOffset());
        // deleteRef changes node-local metadata only and settles at once on the production instance.
        var released = storage.deleteRef(refName, authorityOf(ref)).await();

        if (released.isFailure()) {
            index.addSegment(streamName,
                             partition,
                             ref.startOffset(),
                             ref.endOffset(),
                             ref.maxTimestamp(),
                             ref.compressionOrdinal(),
                             ref.encrypted(),
                             ref.originalSize(),
                             ref.identity());
            released.onFailure(cause -> logDeleteFailure(streamName, partition, ref, cause));

            return false;
        }

        if (unreadableAges.remove(PendingAge.idOf(streamName, partition, ref))) {
            OperatorWarnings.raise(log,
                                   operatorWarnings,
                                   OperatorWarningCode.STREAM_SEGMENT_UNREADABLE_RECLAIMED,
                                   segmentSubject(streamName, partition, ref),
                                   "Retention on node {} reclaimed the segment of stream {}[{}] at offsets {}-{} whose age could not be "
                                  + "read: it took the age of the next newer segment",
                                   nodeId,
                                   streamName,
                                   partition,
                                   ref.startOffset(),
                                   ref.endOffset());
        }

        log.debug("Released expired segment {}/{}:[{}-{}] maxTimestamp={} identity={}",
                  streamName,
                  partition,
                  ref.startOffset(),
                  ref.endOffset(),
                  ref.maxTimestamp(),
                  ref.identity());

        return true;
    }

    /// The shared-delete authority a segment's release carries: one this build sealed, or learned to carry the identity
    /// header, is [SharedDelete#AUTHORISED]; one whose block has not been read since a restart is
    /// [SharedDelete#IF_IDENTIFIED] (the collector reads it); one learned to predate the header is never deleted from the
    /// shared tier, because another partition's identical events could share its block.
    static SharedDelete authorityOf(SegmentIndex.SegmentRef ref) {
        return switch (ref.identity()) {
            case IDENTIFIED -> SharedDelete.AUTHORISED;
            case UNKNOWN -> SharedDelete.IF_IDENTIFIED;
            case LEGACY -> SharedDelete.NONE;
        };
    }

    private static void logDeleteFailure(String streamName, int partition, SegmentIndex.SegmentRef ref, Cause cause) {
        log.warn("Failed to release the ref of expired segment {}/{}:[{}-{}]; it stays and the partition's pass stops: {}",
                 streamName,
                 partition,
                 ref.startOffset(),
                 ref.endOffset(),
                 cause.message());
    }
}
