// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.resource.entity.EntityLogError;
import org.pragmatica.aether.resource.entity.EntityLogSubstrate;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
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

    private final StreamPartitionManager partitionManager;
    private final StreamPartitionManager.ReplicaCatchupSource catchupSource;
    private final TieredStreamReader tieredReader;
    private final SegmentIndex segmentIndex;
    private final EvictionListener evictionListener;
    private final StorageInstance storage;
    private final KVStore<AetherKey, AetherValue> kvStore;
    private final Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier;

    private StreamEntityLogSubstrate(StreamPartitionManager partitionManager,
                                     StreamPartitionManager.ReplicaCatchupSource catchupSource,
                                     TieredStreamReader tieredReader,
                                     SegmentIndex segmentIndex,
                                     EvictionListener evictionListener,
                                     StorageInstance storage,
                                     KVStore<AetherKey, AetherValue> kvStore,
                                     Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier) {
        this.partitionManager = partitionManager;
        this.catchupSource = catchupSource;
        this.tieredReader = tieredReader;
        this.segmentIndex = segmentIndex;
        this.evictionListener = evictionListener;
        this.storage = storage;
        this.kvStore = kvStore;
        this.applier = applier;
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
                                            applier);
    }

    @Override
    public Result<Unit> ensureLog(String keyspace, int partitionCount, int replicationFactor, int minSyncReplicas) {
        return StreamCreateOutcome.tolerateAlreadyExists(partitionManager.createStream(entityStreamConfig(keyspace,
                                                                                                          partitionCount,
                                                                                                          replicationFactor,
                                                                                                          minSyncReplicas))).flatMap(_ -> assertExistingShape(keyspace,
                                                                                                                                                              partitionCount,
                                                                                                                                                              replicationFactor,
                                                                                                                                                              minSyncReplicas));
    }

    /// `tolerateAlreadyExists` makes ensure idempotent — and, unguarded, it also made it SHAPE-BLIND
    /// (#596 review S4): a redeploy declaring a different `partition_count` was silently accepted, the
    /// arc re-hashed every key against a stream laid out for the OLD count, and keys landed on partitions
    /// whose history lives elsewhere — reading back as absent, with nothing anywhere saying why. The
    /// declared shape must MATCH the stream that actually exists, or provisioning fails naming both.
    ///
    /// The check runs on the freshly-created stream too, where it trivially passes — cheaper than
    /// threading "was it created or found" out of the outcome classification.
    private Result<Unit> assertExistingShape(String keyspace,
                                             int partitionCount,
                                             int replicationFactor,
                                             int minSyncReplicas) {
        var streamName = EntityPartitionArc.arcName(keyspace);

        return partitionManager.replicaCatalog()
                               .streams()
                               .stream()
                               .filter(spec -> spec.name()
                                                   .equals(streamName))
                               .findFirst()
                               .map(spec -> matchShape(spec, partitionCount, replicationFactor, minSyncReplicas))
                               .orElseGet(Result::unitResult);
    }

    private static Result<Unit> matchShape(StreamCatalog.StreamSpec spec,
                                           int partitionCount,
                                           int replicationFactor,
                                           int minSyncReplicas) {
        if (spec.partitions() == partitionCount && spec.replicas() == replicationFactor && spec.minSyncReplicas() == minSyncReplicas) {
            return Result.unitResult();
        }

        return Causes.cause("entity log for '" + spec.name()
                           + "' already exists with shape (partitions=" + spec.partitions()
                           + ", replicas=" + spec.replicas()
                           + ", minSync=" + spec.minSyncReplicas()
                           + ") but this deployment declares (partitions=" + partitionCount
                           + ", replicas=" + replicationFactor
                           + ", minSync=" + minSyncReplicas
                           + ") — a changed partition_count re-hashes keys onto partitions whose history"
                           + " lives elsewhere, so the mismatch is refused rather than served wrong").result();
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
    /// pushed far out (a finite value, NOT `Long.MAX_VALUE`, because `evictByAge` computes
    /// `now - maxAgeMs` and would underflow); size and count do the bounding, and every evicted record is
    /// handed to the segment sealer, which retains it until it is sealed (#1234).
    private static StreamConfig entityStreamConfig(String keyspace,
                                                   int partitionCount,
                                                   int replicationFactor,
                                                   int minSyncReplicas) {
        return StreamConfig.streamConfig(EntityPartitionArc.arcName(keyspace),
                                         partitionCount,
                                         RetentionPolicy.retentionPolicy(ENTITY_RING_CAPACITY,
                                                                         ENTITY_RING_BYTES,
                                                                         ENTITY_RING_MAX_AGE_MS,
                                                                         TierAwareRetention.tierAwareRetention()),
                                         AUTO_OFFSET_RESET_EARLIEST,
                                         ENTITY_MAX_EVENT_SIZE_BYTES,
                                         ConsistencyMode.EVENTUAL,
                                         replicationFactor,
                                         minSyncReplicas,
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
    /// `minSyncReplicas <= 1` skips the barrier entirely rather than awaiting zero acks — the declared
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

    /// `minSyncReplicas` COUNTS THE OWNER; `awaitReplication` counts DISTINCT NON-SELF acks. The two
    /// differ by exactly one, and this call passed the raw value — so a keyspace configured for `2`
    /// ("the owner plus one peer", per [DurableEntityConfig#minSyncReplicas]) waited for TWO peers.
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
        var minSyncReplicas = partitionManager.minSyncReplicasFor(stream);

        if (minSyncReplicas <= 1) {
            return Promise.success(offset);
        }

        return partitionManager.awaitReplication(stream, partition, offset, minSyncReplicas - 1)
                               .map(_ -> offset)
                               .mapError(cause -> new EntityLogError.ReplicationBarrierUnmet(keyspace,
                                                                                             partition,
                                                                                             offset,
                                                                                             minSyncReplicas,
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
    @Override
    public Promise<Unit> saveCheckpoint(String keyspace, int partition, long throughOffset, byte[] snapshot) {
        return storage.put(snapshot)
                      .flatMap(blockId -> publishCheckpointPointer(keyspace, partition, throughOffset, blockId));
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
                      .map(bytes -> bytes.map(snapshot -> EntityCheckpoint.entityCheckpoint(pointer.throughOffset(),
                                                                                            snapshot)))
                      .recover(_ -> Option.none());
    }
}
