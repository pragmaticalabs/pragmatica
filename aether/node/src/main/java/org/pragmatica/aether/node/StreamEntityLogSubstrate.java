// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.resource.entity.EntityLogError;
import org.pragmatica.aether.resource.entity.EntityLogSubstrate;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.TierAwareRetention;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.EntityCheckpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.EntityFoldCheckpointValue;
import org.pragmatica.aether.stream.EvictionListener;
import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.aether.stream.StreamCreateOutcome;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.replication.StreamCatalog;
import org.pragmatica.aether.stream.segment.SegmentError;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.aether.stream.segment.TieredStreamReader;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Retry;
import org.pragmatica.lang.utils.Retry.BackoffStrategy;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.StorageInstance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// The node's [EntityLogSubstrate]: an entity keyspace's durable log IS a stream named
/// `entity:<keyspace>`, and its checkpoints are blocks in stream storage pointed at from consensus KV
/// (#345 I3).
///
/// ## Why a stream, and why THIS stream name
/// The stream path already provides, proven and under test, every property the entity's durability claim
/// needs: an append fenced against the partition's committed ownership high-water, an ack withheld until
/// the record is fsync-durable in that partition's WAL, and replication to the replica set carrying the
/// same fencing token. Re-implementing any of that for entities would mean a second, less-tested copy of
/// the hardest code in the system.
///
/// The name is [EntityPartitionArc#arcName], the SAME `entity:<keyspace>` coordinate the write fence, the
/// linearizable read pipeline and the ownership records already key on. That is what makes #345 I1's
/// narrow-C decision pay off here rather than need undoing: the ownership records minted then are already
/// in the right family under the right key, so making the keyspace a real stream renames nothing.
///
/// ## The checkpoint split, and why it is not a storage ref
/// A checkpoint's BYTES go through [StorageInstance#put], whose tier chain ends in a DHT tier — so the
/// block is fetchable from any node by content id. Its NAME cannot go through [StorageInstance#createRef]:
/// stream storage's metadata store is in-memory, snapshotted to the writing node's own disk, so a ref
/// resolves only there. That is useless in the one situation a checkpoint exists for — a DIFFERENT node
/// taking the partition over. The pointer therefore goes into consensus KV, which every node can read,
/// and only the pointer does: the folded state itself stays out of consensus.
public final class StreamEntityLogSubstrate implements EntityLogSubstrate {
    private static final Logger LOG = LoggerFactory.getLogger(StreamEntityLogSubstrate.class);

    /// How long a read waits in place for an in-flight seal before reporting
    /// [EntityLogError.FoldInProgress]: 20 attempts 100 ms apart, about 2 seconds (#1240). A seal that
    /// lands inside the window costs the fold nothing; past it, the fold's next access restarts the
    /// rebuild from the checkpoint.
    private static final Retry IN_FLIGHT_RETRY = Retry.retry()
                                                      .attempts(20)
                                                      .strategy(BackoffStrategy.fixed().interval(TimeSpan.timeSpan(100).millis()));

    private static final int ENTITY_MAX_EVENT_SIZE_BYTES = 4 * 1024 * 1024;
    private static final String AUTO_OFFSET_RESET_EARLIEST = "earliest";
    /// Ring capacity per entity partition — index slots allocated UP FRONT at `INDEX_ENTRY_SIZE` (24)
    /// bytes each, so this is a direct per-partition memory cost, multiplied by a keyspace's partition
    /// count (64 by default). At 10k that is ~240 KB per partition and ~15 MB for a default keyspace;
    /// the `RetentionPolicy` default of 100k would be ~154 MB for the same keyspace, which is why this
    /// deliberately does not inherit it.
    ///
    /// It no longer bounds recovery on the node that sealed the history (#1240): records the ring evicts are
    /// sealed by the segment sealer and [#read] serves them from there. It still bounds recovery on a node
    /// taking the partition over, which reads no other node's segments — there the fold refuses once the
    /// checkpoint lags further behind than this node's ring reaches.
    private static final long ENTITY_RING_CAPACITY = 10_000L;
    /// Data-region CAP, not an up-front allocation: the ring grows one segment at a time toward it.
    private static final long ENTITY_RING_BYTES = 64L * 1024 * 1024;
    /// Age bound, deliberately far out and deliberately FINITE: entity state has no reason to expire on a
    /// clock, but `evictByAge` computes `now - maxAgeMs`, so `Long.MAX_VALUE` would underflow.
    private static final long ENTITY_RING_MAX_AGE_MS = 365L * 24 * 60 * 60 * 1000;
    private static final TimeSpan AT_ONCE = TimeSpan.timeSpan(0).millis();

    /// How a writer reclaims the checkpoint blocks its pointer swaps displace (PR-3c, spec 6.4 item 1): how long a displaced block outlives
    /// the swap, and the bounded retry of a delete the store refuses.
    public record CheckpointReclaim(TimeSpan grace, Retry deleteRetry) {
        public static CheckpointReclaim checkpointReclaim(TimeSpan grace, Retry deleteRetry) {
            return new CheckpointReclaim(grace, deleteRetry);
        }
    }

    /// The production reclaim: a 60 s grace, two checkpoint ticks (`[guess]`, spec 6.4), and five delete attempts.
    public static final CheckpointReclaim CHECKPOINT_RECLAIM = CheckpointReclaim.checkpointReclaim(TimeSpan.timeSpan(60).seconds(),
                                                                                                   Retry.retry()
                                                                                                        .attempts(5)
                                                                                                        .strategy(BackoffStrategy.exponential()
                                                                                                                                 .initialDelay(TimeSpan.timeSpan(5).seconds())
                                                                                                                                 .maxDelay(TimeSpan.timeSpan(60).seconds())
                                                                                                                                 .factor(2.0)
                                                                                                                                 .withoutJitter()));

    private final StreamPartitionManager partitionManager;
    private final StreamPartitionManager.ReplicaCatchupSource catchupSource;
    private final TieredStreamReader tieredReader;
    private final SegmentIndex segmentIndex;
    private final EvictionListener evictionListener;
    private final StorageInstance storage;
    private final KVStore<AetherKey, AetherValue> kvStore;
    private final Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier;
    private final CheckpointReclaim reclaim;
    /// Blocks a confirmed swap displaced at a claim EQUAL to the one it committed, per pointer: the fence accepts an equal claim, so such a
    /// block can still be named again (by a late duplicate publish of the same bytes) and is not deleted yet. Each is deleted once a later
    /// re-read shows a claim above its own, which the fence never lets fall back. In memory only: a restart leaks them (L-12).
    private final Map<EntityCheckpointKey, Map<BlockId, Long>> deferred = new ConcurrentHashMap<>();

    private StreamEntityLogSubstrate(StreamPartitionManager partitionManager,
                                     StreamPartitionManager.ReplicaCatchupSource catchupSource,
                                     TieredStreamReader tieredReader,
                                     SegmentIndex segmentIndex,
                                     EvictionListener evictionListener,
                                     StorageInstance storage,
                                     KVStore<AetherKey, AetherValue> kvStore,
                                     Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                     CheckpointReclaim reclaim) {
        this.partitionManager = partitionManager;
        this.catchupSource = catchupSource;
        this.tieredReader = tieredReader;
        this.segmentIndex = segmentIndex;
        this.evictionListener = evictionListener;
        this.storage = storage;
        this.kvStore = kvStore;
        this.applier = applier;
        this.reclaim = reclaim;
    }

    public static EntityLogSubstrate streamEntityLogSubstrate(StreamPartitionManager partitionManager,
                                                              StreamPartitionManager.ReplicaCatchupSource catchupSource,
                                                              TieredStreamReader tieredReader,
                                                              SegmentIndex segmentIndex,
                                                              EvictionListener evictionListener,
                                                              StorageInstance storage,
                                                              KVStore<AetherKey, AetherValue> kvStore,
                                                              Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier) {
        return new StreamEntityLogSubstrate(partitionManager,
                                            catchupSource,
                                            tieredReader,
                                            segmentIndex,
                                            evictionListener,
                                            storage,
                                            kvStore,
                                            applier,
                                            CHECKPOINT_RECLAIM);
    }

    /// The factory above with the checkpoint reclaim given: tests shorten the grace and the retry; production takes [#CHECKPOINT_RECLAIM].
    public static EntityLogSubstrate streamEntityLogSubstrate(StreamPartitionManager partitionManager,
                                                              StreamPartitionManager.ReplicaCatchupSource catchupSource,
                                                              TieredStreamReader tieredReader,
                                                              SegmentIndex segmentIndex,
                                                              EvictionListener evictionListener,
                                                              StorageInstance storage,
                                                              KVStore<AetherKey, AetherValue> kvStore,
                                                              Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                                              CheckpointReclaim reclaim) {
        return new StreamEntityLogSubstrate(partitionManager,
                                            catchupSource,
                                            tieredReader,
                                            segmentIndex,
                                            evictionListener,
                                            storage,
                                            kvStore,
                                            applier,
                                            reclaim);
    }

    @Override
    public Result<Unit> ensureLog(String keyspace, int partitionCount, ReplicationFactors replication) {
        return StreamCreateOutcome.tolerateAlreadyExists(partitionManager.createDeclaredStream(entityStreamConfig(keyspace,
                                                                                                                  partitionCount,
                                                                                                                  replication))).flatMap(_ -> assertExistingShape(keyspace,
                                                                                                                                                                  partitionCount,
                                                                                                                                                                  replication));
    }

    /// `tolerateAlreadyExists` makes ensure idempotent — and, unguarded, it also made it SHAPE-BLIND
    /// (#596 review S4): a redeploy declaring a different `partition_count` was silently accepted, the
    /// arc re-hashed every key against a stream laid out for the OLD count, and keys landed on partitions
    /// whose history lives elsewhere — reading back as absent, with nothing anywhere saying why. The
    /// declared shape must MATCH the stream that actually exists, or provisioning fails naming both.
    ///
    /// The check runs on the freshly-created stream too, where it trivially passes — cheaper than
    /// threading "was it created or found" out of the outcome classification. #1564 R8: different replication
    /// factors are refused typed ([org.pragmatica.aether.slice.ReplicationFactorsError.ChangedOnLiveResource]),
    /// for a local entry not yet committed as well as the committed one
    /// [StreamPartitionManager#createDeclaredStream] already refuses.
    private Result<Unit> assertExistingShape(String keyspace, int partitionCount, ReplicationFactors replication) {
        var streamName = EntityPartitionArc.arcName(keyspace);

        return partitionManager.replicaCatalog()
                               .streams()
                               .stream()
                               .filter(spec -> spec.name()
                                                   .equals(streamName))
                               .findFirst()
                               .map(spec -> matchShape(spec, partitionCount, replication))
                               .orElseGet(Result::unitResult);
    }

    private static Result<Unit> matchShape(StreamCatalog.StreamSpec spec,
                                           int partitionCount,
                                           ReplicationFactors replication) {
        if (spec.partitions() != partitionCount) {
            return Causes.cause("entity log for '" + spec.name()
                               + "' already exists with partitions=" + spec.partitions()
                               + " but this deployment declares partitions=" + partitionCount
                               + " — a changed partition_count re-hashes keys onto partitions whose history"
                               + " lives elsewhere, so the mismatch is refused rather than served wrong").result();
        }

        return replication.sameAsCommitted("entity log '" + spec.name() + "'",
                                           new ReplicationFactors(spec.replicationFactor(),
                                                                  spec.confirmationFactor()))
                          .mapToUnit();
    }

    /// The backing stream's shape.
    ///
    /// `autoOffsetReset` is `earliest` because a fold reads from the beginning of what it needs, never
    /// from the tail — the opposite of a live consumer's default.
    ///
    /// ## `maxCount` is the RING CAPACITY, not just a policy number
    /// `StreamPartitionManager.buildRing` passes `retention.maxCount()` straight into the ring as its
    /// capacity, and `OffHeapRingBuffer.floorBytes` is
    /// `HEADER_SIZE + INDEX_ENTRY_SIZE * capacity + firstSegmentBytes(maxBytes)`. A `Long.MAX_VALUE`
    /// "infinite retention" therefore OVERFLOWS that multiplication and allocates a ~40-byte control
    /// segment, which then throws `IndexOutOfBoundsException` on the first index write. Learned the hard
    /// way: it is not a config that retains everything, it is a config that cannot allocate.
    ///
    /// So the ring is bounded and ordinary. That costs nothing, because the ring is NOT where entity
    /// durability lives: records seal to the tier, and what protects them there is the checkpoint-derived
    /// recovery floor in `RetentionEnforcer`, not this policy.
    ///
    /// ## What the ring window has to be big enough for
    /// A recovering owner replays from its checkpoint forward through its ring and the segments it sealed
    /// (#1240), so on that node the window no longer has to cover the checkpoint lag. A node taking the
    /// partition over reads only its own ring, so there the window must stay ahead of the checkpoint
    /// interval, or the fold refuses rather than serving state missing writes. Age-based eviction is
    /// pushed far out (a finite value, 365 days, standing in for "never"); the ring age tick
    /// (`StreamPartitionManager.evictRingsByAge`) excludes `entity:` rings by name, so size and count do the
    /// bounding, and every evicted record is handed to the segment sealer, which retains it until it is
    /// sealed (#1234).
    private static StreamConfig entityStreamConfig(String keyspace,
                                                   int partitionCount,
                                                   ReplicationFactors replication) {
        return StreamConfig.streamConfig(EntityPartitionArc.arcName(keyspace),
                                         partitionCount,
                                         RetentionPolicy.retentionPolicy(ENTITY_RING_CAPACITY,
                                                                         ENTITY_RING_BYTES,
                                                                         ENTITY_RING_MAX_AGE_MS,
                                                                         TierAwareRetention.tierAwareRetention()),
                                         AUTO_OFFSET_RESET_EARLIEST,
                                         ENTITY_MAX_EVENT_SIZE_BYTES,
                                         ConsistencyMode.EVENTUAL,
                                         replication.replicationFactor(),
                                         replication.confirmationFactor(),
                                         StreamCompression.NONE,
                                         Option.none());
    }

    /// Fenced, fsync-durable, then replicated-to-barrier.
    ///
    /// `publishLocal` already covers the first two: it fences the append against the partition high-water
    /// before touching the ring, and does not return the offset until the WAL fsync is durable. The
    /// barrier is the third, and it is separate ON PURPOSE — a record that is durable on the owner but has
    /// not reached a peer is a REAL state, and conflating it with "not written" would make a retrying
    /// caller believe it had lost a write it actually has. [EntityLogError.ReplicationBarrierUnmet] says
    /// exactly which of the two happened.
    ///
    /// `confirmationFactor <= 1` skips the barrier entirely rather than awaiting zero acks — the declared
    /// single-replica mode, where the honest guarantee is restart-durability and nothing more.
    @Override
    public Promise<Long> append(String keyspace, int partition, byte[] record) {
        var stream = EntityPartitionArc.arcName(keyspace);

        return partitionManager.publishLocal(stream,
                                             partition,
                                             record,
                                             System.currentTimeMillis())
                               .mapError(cause -> translateAppendFailure(keyspace, partition, cause))
                               .async()
                               .flatMap(offset -> awaitBarrier(keyspace, stream, partition, offset));
    }

    /// The stream's own fence rejection, renamed into the entity module's vocabulary so that module needs
    /// no dependency on stream error types. `StaleEpochAppend` is what `ensureNotStale` raises when a
    /// deposed owner's epoch is older than the partition's committed high-water — the same condition the
    /// storage engine's gate enforced before I3 moved the fence to the log.
    ///
    /// #1230: `NotOwnerAppend` — the stream's owner-write admission found the committed record naming another
    /// node — is the same stale-owner condition reached by a different check: `EntityOwnerAdmission` passed
    /// on a read the record has since moved past. It too becomes `StaleOwnerAppend`, naming the committed
    /// owner, so the caller re-resolves instead of seeing an opaque `StorageFailed`.
    private static Cause translateAppendFailure(String keyspace, int partition, Cause cause) {
        return switch (cause) {
            case StreamError.StaleEpochAppend stale -> new EntityLogError.StaleOwnerAppend(keyspace,
                                                                                           partition,
                                                                                           staleDetail(stale));
            case StreamError.NotOwnerAppend notOwner -> new EntityLogError.StaleOwnerAppend(keyspace,
                                                                                            partition,
                                                                                            notOwnerDetail(notOwner));
            default -> cause;
        };
    }

    private static String staleDetail(StreamError.StaleEpochAppend stale) {
        return "presented " + stale.presented() + ", current " + stale.current();
    }

    private static String notOwnerDetail(StreamError.NotOwnerAppend notOwner) {
        return "the committed owner is " + notOwner.committedOwner()
                                                   .id();
    }

    /// The confirmation factor COUNTS THE OWNER; `awaitReplication` counts DISTINCT NON-SELF acks. The two
    /// differ by exactly one, and this call once passed the raw value — so a keyspace configured for `2`
    /// ("the owner plus one peer") waited for TWO peers.
    ///
    /// At the default `replicationFactor = 3` that is satisfiable only while BOTH peers are alive and
    /// caught up, so losing a single peer failed every entity write with `ReplicationBarrierUnmet` —
    /// precisely the failure replication is there to survive. At `replicationFactor = 2` there is only
    /// one non-self replica in existence, so no entity write could ever succeed.
    ///
    /// Both stream writers already subtract: `StreamWriteRouter.awaitMinSync` and
    /// `StreamForwardHandler.awaitMinSync`. This is the third writer on the same barrier and it was the
    /// odd one out.
    private Promise<Long> awaitBarrier(String keyspace, String stream, int partition, long offset) {
        var confirmationFactor = partitionManager.confirmationFactorFor(stream);

        if (confirmationFactor <= 1) {
            return Promise.success(offset);
        }

        return partitionManager.awaitReplication(stream, partition, offset, confirmationFactor - 1)
                               .map(_ -> offset)
                               .mapError(cause -> new EntityLogError.ReplicationBarrierUnmet(keyspace,
                                                                                             partition,
                                                                                             offset,
                                                                                             confirmationFactor,
                                                                                             cause));
    }

    /// Reads span both tiers this node holds (#1240), as contract item 3 of [EntityLogSubstrate] requires.
    /// An offset the ring still holds is read from the ring. An offset below the ring's earliest was
    /// evicted and handed to the segment sealer, so it is read from the segments this node sealed. Before
    /// #1240 every read went to the ring, so a partition whose checkpoint lagged further than the ring
    /// reaches (10k records, or fewer large ones) refused every operation although its history sat sealed
    /// in local storage.
    ///
    /// A sealed read is capped at the ring's earliest offset, so the two tiers never overlap, and the
    /// records it returns must sit at exactly `fromOffset, fromOffset + 1, …`: the fold assigns offsets by
    /// position, so a skipped or repeated record would silently fold every later record at the wrong
    /// offset. A mismatch is refused, never repaired.
    ///
    /// Both tiers hold APPENDED records, as the #1274 ruling requires of entity folds: the ring read uses
    /// [StreamPartitionManager#readAppended], bounded by the APPENDED head rather than the stream-consumer
    /// visible position (#1235), and a sealed record was appended before it was evicted. Entity-log
    /// visibility is the fold's own contract, not a stream consumer's.
    ///
    /// A node reads only the segments IT sealed — the index is node-local — so a node that never held the
    /// partition still refuses exactly as before: [#earliestRetainedOffset] stays at its ring. A PROMOTED
    /// REPLICA is the case this widens: replica rings are materialized with the same node-wide eviction
    /// listener as owner rings, so a replica has sealed segments of the partition, and after promotion it
    /// can rebuild from that history where it previously refused. A suffix sealed only after it joined
    /// leaves a hole below, and the read that reaches the hole refuses loudly rather than folding around it.
    @Override
    public Promise<List<byte[]>> read(String keyspace, int partition, long fromOffset, int maxRecords) {
        var stream = EntityPartitionArc.arcName(keyspace);
        var ringEarliest = partitionManager.earliestRetainedOffset(stream, partition);

        return belowRing(fromOffset, ringEarliest)
               ? readSealed(keyspace, stream, partition, fromOffset, sealedBound(fromOffset, maxRecords, ringEarliest))
               : readRing(keyspace, stream, partition, fromOffset, maxRecords);
    }

    private static boolean belowRing(long fromOffset, long ringEarliest) {
        return ringEarliest < 0 || fromOffset < ringEarliest;
    }

    private static int sealedBound(long fromOffset, int maxRecords, long ringEarliest) {
        return ringEarliest < 0
               ? maxRecords
               : (int) Math.min(maxRecords, ringEarliest - fromOffset);
    }

    private Promise<List<byte[]>> readRing(String keyspace,
                                           String stream,
                                           int partition,
                                           long fromOffset,
                                           int maxRecords) {
        return partitionManager.readAppended(stream, partition, fromOffset, maxRecords)
                               .fold(cause -> evictedDuringRead(keyspace,
                                                                stream,
                                                                partition,
                                                                fromOffset,
                                                                maxRecords,
                                                                cause),
                                     events -> Promise.success(payloads(events)));
    }

    /// The ring evicted `fromOffset` between the tier choice and the ring read — a real race under a
    /// concurrently appending partition, and the reroute is what absorbs it. The offset is then in the
    /// sealer or in a segment, and the sealed read picks it up.
    ///
    /// The bound comes from the refusal's own `tailOffset` rather than a fresh
    /// `earliestRetainedOffset` read: the two can differ, and a ring replaced between them would answer
    /// `-1`, which would make the bound negative.
    private Promise<List<byte[]>> evictedDuringRead(String keyspace,
                                                    String stream,
                                                    int partition,
                                                    long fromOffset,
                                                    int maxRecords,
                                                    Cause cause) {
        return switch (cause) {
            case StreamError.CursorExpired expired -> readSealed(keyspace,
                                                                 stream,
                                                                 partition,
                                                                 fromOffset,
                                                                 sealedBound(fromOffset,
                                                                             maxRecords,
                                                                             expired.tailOffset()));
            default -> cause.promise();
        };
    }

    /// An evicted offset whose seal has not landed is IN FLIGHT (#1234): the sealer retains it and the WAL
    /// holds it, and it becomes readable once the seal lands. The read waits for it in place for about two
    /// seconds ([#IN_FLIGHT_RETRY]); a seal still pending after that is reported as the transient
    /// [EntityLogError.FoldInProgress]. The offset is never skipped. The cost of the fallback: the fold
    /// discards its partial rebuild, so its next access re-reads everything from the checkpoint.
    private Promise<List<byte[]>> readSealed(String keyspace,
                                             String stream,
                                             int partition,
                                             long fromOffset,
                                             int count) {
        return IN_FLIGHT_RETRY.execute(() -> readSealedOnce(keyspace, stream, partition, fromOffset, count),
                                       SegmentError.SealInFlight.class::isInstance).mapError(cause -> stillInFlight(keyspace,
                                                                                                                    stream,
                                                                                                                    partition,
                                                                                                                    cause));
    }

    /// The in-flight check runs before the segment read: the sink indexes a segment before the sealer
    /// releases it, so no evicted offset is in neither place.
    private Promise<List<byte[]>> readSealedOnce(String keyspace,
                                                 String stream,
                                                 int partition,
                                                 long fromOffset,
                                                 int count) {
        if (evictionListener.holdsUnsealed(stream, partition, fromOffset)) {
            return new SegmentError.SealInFlight(stream, partition, fromOffset).promise();
        }

        return tieredReader.read(stream, partition, fromOffset, count)
                           .mapError(cause -> sealedReadFailure(keyspace, stream, partition, cause))
                           .flatMap(events -> contiguousPayloads(keyspace, stream, partition, fromOffset, events));
    }

    private static Cause stillInFlight(String keyspace, String stream, int partition, Cause cause) {
        return switch (cause) {
            case SegmentError.SealInFlight inFlight -> foldInProgress(keyspace, stream, partition, inFlight);
            default -> cause;
        };
    }

    private static Cause foldInProgress(String keyspace,
                                        String stream,
                                        int partition,
                                        SegmentError.SealInFlight inFlight) {
        LOG.info("Entity log {}/{} offset {} is still being sealed; the fold retries on the next access",
                 stream,
                 partition,
                 inFlight.fromOffset());

        return new EntityLogError.FoldInProgress(keyspace, partition);
    }

    /// A hole in the sealed range, or a range retention already reclaimed, cannot be read around: the fold
    /// would serve state missing committed writes. Both refuse the fold loudly. Any other failure (a storage
    /// read error) keeps its own cause and classification.
    private static Cause sealedReadFailure(String keyspace, String stream, int partition, Cause cause) {
        return switch (cause) {
            case SegmentError.SealedRangeMissing missing -> refused(keyspace, stream, partition, missing);
            case StreamError.CursorExpired expired -> refused(keyspace, stream, partition, expired);
            default -> cause;
        };
    }

    private Promise<List<byte[]>> contiguousPayloads(String keyspace,
                                                     String stream,
                                                     int partition,
                                                     long fromOffset,
                                                     List<RawEvent> events) {
        if (events.isEmpty()) {
            return emptySealedRead(keyspace, stream, partition, fromOffset);
        }

        return firstMisplaced(events, fromOffset).map(position -> notContiguous(keyspace,
                                                                                stream,
                                                                                partition,
                                                                                fromOffset + position,
                                                                                events.get(position).offset()))
                             .or(() -> Promise.success(payloads(events)));
    }

    private static Option<Integer> firstMisplaced(List<RawEvent> events, long fromOffset) {
        return Option.from(IntStream.range(0,
                                           events.size())
                                    .filter(position -> events.get(position)
                                                              .offset() != fromOffset + position)
                                    .boxed()
                                    .findFirst());
    }

    private static Promise<List<byte[]>> notContiguous(String keyspace,
                                                       String stream,
                                                       int partition,
                                                       long expected,
                                                       long found) {
        return refused(keyspace,
                       stream,
                       partition,
                       new EntityLogError.MalformedRecord("sealed history is not contiguous: expected offset " + expected
                                                         + ", found " + found)).promise();
    }

    /// Nothing sealed at or above `fromOffset`, not in flight, and below the ring: no tier holds it. Past
    /// the head that is simply the end of the log; at or below it, it is a refusal.
    private Promise<List<byte[]>> emptySealedRead(String keyspace, String stream, int partition, long fromOffset) {
        return fromOffset > headOffset(keyspace, partition)
               ? Promise.success(List.of())
               : refused(keyspace,
                         stream,
                         partition,
                         new EntityLogError.MalformedRecord("offset " + fromOffset
                                                           + " is in neither the ring, the sealer nor a sealed segment")).promise();
    }

    private static Cause refused(String keyspace, String stream, int partition, Cause reason) {
        LOG.error("Entity log {}/{} cannot be rebuilt from its sealed history: {}", stream, partition, reason.message());

        return new EntityLogError.FoldFailed(keyspace, partition, reason);
    }

    private static List<byte[]> payloads(List<RawEvent> events) {
        return events.stream()
                     .map(RawEvent::data)
                     .toList();
    }

    @Override
    public long headOffset(String keyspace, int partition) {
        return partitionManager.nextExpectedOffset(EntityPartitionArc.arcName(keyspace), partition) - 1;
    }

    @Override
    public long cutGeneration(String keyspace, int partition) {
        return partitionManager.cutGeneration(EntityPartitionArc.arcName(keyspace), partition);
    }

    /// The log's COMMITTED position (#2087): the ring's visible position, i.e. durable here AND acknowledged by the keyspace's
    /// confirmation peers on an owner, or reported visible by the owner on a replica. Never above the appended head.
    @Override
    public long committedOffset(String keyspace, int partition) {
        return partitionManager.partitionBuffer(EntityPartitionArc.arcName(keyspace),
                                                partition)
                               .map(ring -> Math.min(ring.headOffset(),
                                                     ring.visibleOffset()))
                               .or(-1L);
    }

    /// Resolves once the committed position reaches `offset`, woken by the ring's own visible-advance listener (which runs on
    /// the ring's notifier, never under its append lock); fails after `timeout`. The listener is registered BEFORE the position
    /// is re-read, so an advance between the first check and the registration is not missed.
    @Override
    public Promise<Unit> awaitCommitted(String keyspace, int partition, long offset, TimeSpan timeout) {
        if (committedOffset(keyspace, partition) >= offset) {
            return Promise.unitPromise();
        }

        return partitionManager.partitionBuffer(EntityPartitionArc.arcName(keyspace),
                                                partition)
                               .fold(() -> Causes.cause("partition " + partition
                                                       + " of '" + keyspace
                                                       + "' is not held here").<Unit> promise(),
                                     ring -> awaitVisible(ring,
                                                          () -> committedOffset(keyspace, partition) >= offset,
                                                          timeout));
    }

    static Promise<Unit> awaitVisible(org.pragmatica.aether.stream.OffHeapRingBuffer ring,
                                      java.util.function.BooleanSupplier reached,
                                      TimeSpan timeout) {
        var committed = Promise.<Unit> promise();
        java.util.function.LongConsumer listener = _ -> {
            if (reached.getAsBoolean()) {
                committed.resolve(Result.unitResult());
            }
        };

        ring.addAppendListener(listener);
        if (reached.getAsBoolean()) {
            committed.resolve(Result.unitResult());
        }

        return committed.timeout(timeout)
                        .onResultRun(() -> ring.removeAppendListener(listener));
    }

    /// The earliest offset [#read] can serve here (#1240): the lowest of the offsets this node sealed, the
    /// offsets its sealer still holds in flight, and the ring's earliest. A hole between those tiers is not
    /// hidden by this answer: the read that reaches it fails `SealedRangeMissing`, which refuses the fold.
    @Override
    public long earliestRetainedOffset(String keyspace, int partition) {
        var stream = EntityPartitionArc.arcName(keyspace);
        var ringEarliest = partitionManager.earliestRetainedOffset(stream, partition);

        return Stream.of(segmentIndex.nextSealedOffset(stream, partition, -1L),
                         evictionListener.lowestUnsealed(stream, partition))
                     .flatMap(Option::stream)
                     .filter(offset -> belowRing(offset, ringEarliest))
                     .min(Long::compare)
                     .orElse(ringEarliest);
    }

    /// Answered from the replica catch-up view, which is the cluster's existing statement that this
    /// node's copy of a partition is complete — the same signal the partition manager already gates
    /// replica RELEASE on, so it is load-bearing in production rather than a fresh predicate invented
    /// here.
    ///
    /// The node's own row reaches `CAUGHT_UP` through backfill completion and watermark rebuild, not only
    /// through peer acks, so this does become true for a node that has just taken a partition over once
    /// its catch-up finishes. Until then the fold correctly waits.
    /// Answered from the partition manager's own materialization state: a node holds a partition exactly
    /// when it has a ring for it. Local, synchronous, and true by construction rather than inferred from
    /// a replica descriptor that may not exist yet.
    @Override
    public boolean holdsPartition(String keyspace, int partition) {
        return partitionManager.partitionBuffer(EntityPartitionArc.arcName(keyspace),
                                                partition)
                               .isPresent();
    }

    @Override
    public boolean localLogComplete(String keyspace, int partition) {
        return catchupSource.catchupView(EntityPartitionArc.arcName(keyspace),
                                         partition)
                            .selfCaughtUp();
    }

    /// Write the block first, publish the pointer second. A crash between the two leaves an unreferenced
    /// block — wasted space that the next checkpoint's pointer never names — whereas the reverse order
    /// would leave a committed pointer to a block that does not exist, which a recovering node could not
    /// distinguish from corruption.
    ///
    /// Then reclaim (PR-3c, I-15): the pointer read before the put names the block this save may displace, and the pointer re-read after
    /// the publish decides what may be deleted ([#reclaimDisplaced]). A block a pointer in this node's view names is not deleted: checked again
    /// when the delete runs ([#deleteUnlessNamed]). A crash between the put and the publish, or between the publish and a scheduled delete,
    /// leaks the blocks written since the last confirmed swap; nothing sweeps them (L-12).
    @Override
    public Promise<Unit> saveCheckpoint(String keyspace,
                                        int partition,
                                        long throughOffset,
                                        byte[] snapshot,
                                        Option<byte[]> lineage) {
        var key = EntityCheckpointKey.entityCheckpointKey(keyspace, partition);
        var replaced = committedPointer(key);

        return storage.put(CheckpointFrame.frame(keyspace, partition, throughOffset, snapshot, lineage))
                      .flatMap(blockId -> publishThenReclaim(key,
                                                             replaced,
                                                             ClaimedBlock.claimedBlock(blockId, throughOffset)));
    }

    private Promise<Unit> publishThenReclaim(EntityCheckpointKey key,
                                             Option<ClaimedBlock> replaced,
                                             ClaimedBlock written) {
        return publishCheckpointPointer(key.keyspace(), key.partition(), written.through(), written.block()).map(_ -> reclaimDisplaced(key,
                                                                                                                                       replaced,
                                                                                                                                       written));
    }

    /// A block and the claim of the pointer that names it.
    private record ClaimedBlock(BlockId block, long through) {
        static ClaimedBlock claimedBlock(BlockId block, long through) {
            return new ClaimedBlock(block, through);
        }
    }

    private Option<ClaimedBlock> committedPointer(EntityCheckpointKey key) {
        return kvStore.getTyped(key, EntityFoldCheckpointValue.class)
                      .flatMap(StreamEntityLogSubstrate::claimedBlockOf);
    }

    private static Option<ClaimedBlock> claimedBlockOf(EntityFoldCheckpointValue pointer) {
        return BlockId.fromHex(pointer.blockIdHex())
                      .option()
                      .map(block -> ClaimedBlock.claimedBlock(block,
                                                              pointer.throughOffset()));
    }

    /// What the re-read pointer shows decides what this writer deletes (spec 6.4 item 1):
    ///   - It names the block just written: the swap is confirmed, and the block the replaced pointer named is displaced. It is deleted
    ///     after the grace, against a loader that read the replaced pointer just before the swap and is still fetching (one that loses
    ///     the race finds no block: "no usable checkpoint", and it folds from the log, as for a lost block).
    ///   - It names anything else: the fenced applier refused this put (a higher claim is committed) and nothing names the block just
    ///     written, so it is deleted at once. The writer deletes nothing else: the block the winner displaced is the winner's to delete.
    ///
    /// Either way a block is deleted only when its claim is STRICTLY below the claim the re-read shows. The applier refuses a put that
    /// lowers the committed claim, so while the fenced applier is the only writer of the pointer the claim never falls back and no pointer
    /// names such a block again, whoever writes it. That condition does not always hold (a snapshot restore bypasses the fence; a removal
    /// resets the claim), so the delete checks the pointers again when it runs ([#deleteUnlessNamed]). An equal claim is accepted, so a block
    /// at the shown claim waits in [#deferred]. A stale re-read can only show a lower claim, so it defers instead of deleting.
    private Unit reclaimDisplaced(EntityCheckpointKey key, Option<ClaimedBlock> replaced, ClaimedBlock written) {
        var current = committedPointer(key);
        var claim = current.map(ClaimedBlock::through).or(Long.MIN_VALUE);

        if (current.filter(written::equals).isPresent()) {
            replaced.filter(old -> !old.block()
                                       .equals(written.block()))
                    .onPresent(old -> deleteOrDefer(key,
                                                    old,
                                                    claim,
                                                    reclaim.grace()));
        } else {
            deleteOrDefer(key, written, claim, AT_ONCE);
        }

        releaseDeferred(key, claim);

        return Unit.unit();
    }

    private void deleteOrDefer(EntityCheckpointKey key, ClaimedBlock displaced, long claim, TimeSpan after) {
        if (displaced.through() < claim) {
            scheduleDelete(displaced.block(), after);
        } else {
            deferred.computeIfAbsent(key, _ -> new ConcurrentHashMap<>()).put(displaced.block(), displaced.through());
        }
    }

    private void releaseDeferred(EntityCheckpointKey key, long claim) {
        Option.option(deferred.get(key)).onPresent(held -> held.forEach((block, through) -> releaseIfPassed(held,
                                                                                                            block,
                                                                                                            through,
                                                                                                            claim)));
    }

    /// `remove(block, through)` hands each deferred block to exactly one delete, however many saves release at once.
    private void releaseIfPassed(Map<BlockId, Long> held, BlockId block, long through, long claim) {
        if (through < claim && held.remove(block, through)) {
            scheduleDelete(block, reclaim.grace());
        }
    }

    /// Through [StorageInstance#delete], which removes the block from every tier, the shared DHT tier included (a write-quorum remove that
    /// leaves tombstones; anti-entropy carries them to a replica that missed it). This writer may hold no lifecycle record for the block (a
    /// previous owner wrote it), so a `release` would reclaim nothing. A delete the store refuses (the DHT quorum unreachable) is retried
    /// within [CheckpointReclaim#deleteRetry]'s bound; past it the block stays where it is, a leak logged at WARN.
    private void scheduleDelete(BlockId block, TimeSpan after) {
        SharedScheduler.schedule(() -> deleteWithRetry(block), after);
    }

    private void deleteWithRetry(BlockId block) {
        reclaim.deleteRetry().execute(() -> deleteUnlessNamed(block)).onFailure(cause -> logUndeleted(block, cause));
    }

    /// The pointers are read again when the delete runs, on every attempt, and a block one of them names is not deleted: the decision made
    /// at the swap rests on the claim never falling back, which holds only while the fenced applier is the only writer of the pointer (a
    /// snapshot restore bypasses the fence; a pointer removed and rewritten at the same claim names the same block again). A block with an
    /// identity header can be named only by the pointer of the keyspace and partition in it, so that one pointer is read. A block without
    /// one (written before PR-3c, content-addressed with no identity) can be named by pointers of any partition of any keyspace, so every
    /// pointer is read: one scan of the local KV, needed only for such a block, which the upgrade window displaces at most once per pointer.
    ///
    /// The check only withholds deletes, from this node's committed view: a pointer this node has not applied yet, or one committed
    /// between the read and the quorum remove, is not seen.
    private Promise<Unit> deleteUnlessNamed(BlockId block) {
        return storage.get(block)
                      .map(StreamEntityLogSubstrate::pointerOf)
                      .flatMap(pointer -> deleteUnlessNamed(block, pointer));
    }

    private static Option<EntityCheckpointKey> pointerOf(Option<byte[]> content) {
        return content.flatMap(CheckpointFrame::pointerOf);
    }

    private Promise<Unit> deleteUnlessNamed(BlockId block, Option<EntityCheckpointKey> pointer) {
        return namedByAPointer(block.hexString(), pointer)
               ? Promise.unitPromise()
               : storage.delete(block);
    }

    private boolean namedByAPointer(String hex, Option<EntityCheckpointKey> pointer) {
        return pointer.map(key -> names(key, hex))
                      .or(() -> namedByAnyPointer(hex));
    }

    private boolean names(EntityCheckpointKey key, String hex) {
        return kvStore.getTyped(key, EntityFoldCheckpointValue.class)
                      .filter(named -> named.blockIdHex()
                                            .equals(hex))
                      .isPresent();
    }

    private boolean namedByAnyPointer(String hex) {
        var named = new AtomicBoolean();

        kvStore.forEach(EntityCheckpointKey.class,
                        EntityFoldCheckpointValue.class,
                        (_, pointer) -> named.compareAndSet(false,
                                                            pointer.blockIdHex().equals(hex)));

        return named.get();
    }

    private static void logUndeleted(BlockId block, Cause cause) {
        LOG.warn("Checkpoint block {} was displaced but could not be deleted within the retry bound ({}); it stays in storage",
                 block,
                 cause.message());
    }

    @Override
    public boolean recordsLineage() {
        return true;
    }

    /// The identity of this node's records ending at `offset` (#2087 B4-cut): the owner epoch it knows for it and, for each of up to the last
    /// [CheckpointFrame#LINEAGE_WINDOW] records ending there (those it still retains), its offset and a hash of its bytes. A window rather than the one
    /// record: after a cut a new lineage can write byte-identical records, and a checkpoint folded over the removed records would still differ in the
    /// earlier ones. [#UNVOUCHED] when the epoch is not known here; none when the claim record is not readable.
    @Override
    public Option<byte[]> lineageAt(String keyspace, int partition, long offset) {
        var floor = partitionManager.sealedFloor(EntityPartitionArc.arcName(keyspace), partition);

        return offset <= floor
               ? Option.some(CheckpointFrame.withFloor(floor, new byte[0]))
               : windowIdentity(keyspace, partition, offset).map(body -> body.length == 0
                                                                         ? body
                                                                         : CheckpointFrame.withFloor(floor, body));
    }

    /// The owner epoch this copy knows for the claim record and, for each of the records it still retains of the window ending there, its offset
    /// and a hash of its bytes ([UNVOUCHED], empty, when the epoch is not known here; none when the claim record is not readable).
    private Option<byte[]> windowIdentity(String keyspace, int partition, long offset) {
        var stream = EntityPartitionArc.arcName(keyspace);
        var from = Math.max(0L, offset - CheckpointFrame.LINEAGE_WINDOW + 1L);

        return readableWindow(stream, partition, from, offset).filter(events -> CheckpointFrame.endsAt(events, offset))
                             .flatMap(events -> partitionManager.epochAt(stream, partition, offset)
                                                                .fold(() -> Option.some(UNVOUCHED),
                                                                      epoch -> CheckpointFrame.lineageOf(events, epoch)));
    }

    /// The records of `[from, offset]` this copy still retains: the whole window, or -- when its start was evicted -- the part from the ring's
    /// tail. None when the claim record itself is not readable.
    private Option<List<RawEvent>> readableWindow(String stream, int partition, long from, long offset) {
        return partitionManager.readAppended(stream,
                                             partition,
                                             from,
                                             (int)(offset - from + 1L))
                               .fold(cause -> retainedPart(cause, stream, partition, offset),
                                     Option::some);
    }

    private Option<List<RawEvent>> retainedPart(Cause cause, String stream, int partition, long offset) {
        return cause instanceof StreamError.CursorExpired expired && expired.tailOffset() <= offset
               ? partitionManager.readAppended(stream,
                                               partition,
                                               expired.tailOffset(),
                                               (int)(offset - expired.tailOffset() + 1L))
                                 .option()
               : Option.none();
    }

    /// A claim at or below the sealed floor the checkpoint's WRITER saw is accepted without validation: that region was sealed before the
    /// checkpoint was taken, the floor only rises, and a cut is refused at or below it, so it was never cut. The CURRENT floor proves nothing: a
    /// cut can rewrite the claim record and the floor can then rise over the new lineage. Otherwise the copy validates over the records of the
    /// window it still holds, the claim record included; if it holds none, or cannot vouch for the owner epoch, the answer is no.
    @Override
    public boolean lineageHolds(String keyspace, int partition, long offset, byte[] recorded) {
        return CheckpointFrame.floorOf(recorded)
                              .filter(written -> offset <= written)
                              .isPresent() || windowIdentity(keyspace, partition, offset).map(local -> CheckpointFrame.agree(CheckpointFrame.bodyOf(recorded),
                                                                                                                             local))
                                                            .or(false);
    }

    public Promise<Unit> saveCheckpoint(String keyspace, int partition, long throughOffset, byte[] snapshot) {
        return saveCheckpoint(keyspace, partition, throughOffset, snapshot, Option.none());
    }

    /// **This pointer is what the retention floor trusts** when reclaiming entity-log segments
    /// below it — which is why regression is refused by the SUBSTRATE, not here: the value is
    /// [org.pragmatica.cluster.state.kvstore.MonotonicFenced], so the consensus applier rejects a
    /// Put that would lower the committed `throughOffset` (#700). Two honest writers either side of
    /// a partition handover therefore cannot re-expose reclaimed offsets; the lower claim is simply
    /// refused (silently — rejected fenced writes emit no notification; the committed claim is then
    /// at least as advanced as what was attempted, which is exactly what this writer wanted).
    private Promise<Unit> publishCheckpointPointer(String keyspace,
                                                   int partition,
                                                   long throughOffset,
                                                   BlockId blockId) {
        List<KVCommand<AetherKey>> command = List.of(new KVCommand.Put<AetherKey, AetherValue>(EntityCheckpointKey.entityCheckpointKey(keyspace,
                                                                                                                                       partition),
                                                                                               EntityFoldCheckpointValue.entityFoldCheckpointValue(throughOffset,
                                                                                                                                                   blockId.hexString())));

        return applier.apply(command)
                      .mapToUnit();
    }

    @Override
    public Promise<Option<EntityCheckpoint>> loadCheckpoint(String keyspace, int partition) {
        return kvStore.getTyped(EntityCheckpointKey.entityCheckpointKey(keyspace, partition),
                                EntityFoldCheckpointValue.class)
                      .fold(() -> Promise.success(Option.none()),
                            pointer -> fetchCheckpointBlock(pointer));
    }

    /// A pointer whose block cannot be fetched resolves to [Option#none] — "no usable checkpoint" — rather
    /// than to a failure. The caller's next step is identical either way: fold from the earliest offset it
    /// can read, and refuse if that leaves a gap. Reporting a distinct error here would add a branch that
    /// ends in the same place, while risking a node refusing to recover over a checkpoint it never needed.
    private Promise<Option<EntityCheckpoint>> fetchCheckpointBlock(EntityFoldCheckpointValue pointer) {
        return BlockId.fromHex(pointer.blockIdHex())
                      .async()
                      .flatMap(storage::get)
                      .map(bytes -> bytes.map(block -> CheckpointFrame.unframe(pointer.throughOffset(),
                                                                               block)))
                      .recover(_ -> Option.none());
    }
}
