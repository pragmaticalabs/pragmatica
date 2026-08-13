// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Unit.unit;


/// A keyspace's in-memory state, per partition, derived entirely from its durable log (#345 I3).
///
/// The log is the truth and this is a cache of it. Everything below follows from taking that literally:
/// a partition serves nothing until it has been rebuilt, a rebuild that cannot see the whole log fails
/// rather than serving what it has, and a record that reached the log is applied here even if the
/// caller was told the write did not meet its durability target.
///
/// ## Values stay encoded
/// Each key maps to the SAME bytes the log carries. Applying a replayed record costs no decode, and a
/// checkpoint writes bytes already in hand; only a read decodes, exactly as before I3. Holding decoded
/// objects would move that cost onto every write and every replayed record — the wrong direction, since
/// replay is what has to be fast when a partition is recovering.
final class EntityFold {
    private static final int REPLAY_BATCH = 512;

    private final String keyspace;
    private final EntityLogSubstrate substrate;
    private final ConcurrentHashMap<Integer, PartitionFold> partitions = new ConcurrentHashMap<>();

    private EntityFold(String keyspace, EntityLogSubstrate substrate) {
        this.keyspace = keyspace;
        this.substrate = substrate;
    }

    static EntityFold entityFold(String keyspace, EntityLogSubstrate substrate) {
        return new EntityFold(keyspace, substrate);
    }

    /// The state of one partition's rebuild. `state` is only meaningful once `phase` is READY.
    private static final class PartitionFold {
        private final Map<String, byte[]> state = new ConcurrentHashMap<>();
        private final ConcurrentSkipListSet<Long> appliedAhead = new ConcurrentSkipListSet<>();
        private final AtomicReference<Promise<Unit>> rebuild = new AtomicReference<>();
        private final AtomicLong appliedThrough = new AtomicLong(-1L);
    }

    /// Resolve once `partition` is serving. Callers gate every read and write on this.
    ///
    /// The rebuild is memoized per partition with a compare-and-set, so concurrent operations on
    /// different keys of the same partition trigger exactly ONE replay and all wait on it. A failed
    /// rebuild clears the memo so a later call retries — the conditions that fail a fold (an incomplete
    /// local log, a gap that a later checkpoint closes) are ones that can genuinely resolve later, and
    /// latching the failure forever would turn a transient state into a permanent outage.
    Promise<Unit> ready(int partition) {
        var fold = partitionFold(partition);
        var existing = fold.rebuild.get();

        if (existing != null) {
            return existing;
        }

        var started = Promise.<Unit> promise();

        if (!fold.rebuild.compareAndSet(null, started)) {
            return fold.rebuild.get();
        }

        rebuild(partition, fold).onResult(result -> completeRebuild(fold, started, result));

        return started;
    }

    private static void completeRebuild(PartitionFold fold, Promise<Unit> started, Result<Unit> result) {
        result.onFailure(_ -> fold.rebuild.set(null));
        started.resolve(result);
    }

    /// The current encoded state of `key`, or [Option#none] when it is absent. Only valid after [#ready].
    Option<byte[]> get(int partition, String key) {
        return Option.option(partitionFold(partition).state.get(key));
    }

    /// Apply a record that IS in the log at `offset`.
    ///
    /// Called on the write path after the append resolved an offset, and on the replay path for every
    /// record read back. It is deliberately called even when the replication barrier afterwards fails:
    /// the record is in the log, a recovering node WILL replay it, and refusing to apply it here would
    /// leave this node's view disagreeing with the log it is serving from. The caller still learns the
    /// write missed its durability target — that is the promise's job, not this map's.
    @Contract
    void apply(int partition, long offset, EntityLogRecord record) {
        var fold = partitionFold(partition);

        applyToState(fold, record);
        advanceApplied(fold, offset);
    }

    private static void applyToState(PartitionFold fold, EntityLogRecord record) {
        switch (record.op()) {
            case UPSERT -> fold.state.put(record.key(), record.state());
            case DELETE -> fold.state.remove(record.key());
        }
    }

    /// Advance the contiguous watermark: an offset only counts once every offset below it has landed.
    ///
    /// Applied offsets arrive OUT OF ORDER, because concurrent writes to different keys of one partition
    /// append concurrently and their appends resolve in whatever order the log and the replication barrier
    /// allow. Offset 7 can therefore be applied while 5 is still outstanding.
    ///
    /// Tracking the maximum would be wrong in the one way that loses data silently: a checkpoint claiming
    /// 7 makes recovery resume at 8, and offset 5 — a real, durable, committed mutation — is skipped
    /// forever. So out-of-order offsets are parked in `appliedAhead` and the watermark only steps forward
    /// while the next offset is actually present.
    ///
    /// The drain is a CAS-with-max rather than a lock: `ConcurrentSkipListSet#remove` is atomic, so two
    /// threads can never claim the same offset, and accumulating with [Math#max] means a thread that read
    /// a stale base can never push the watermark backwards.
    private static void advanceApplied(PartitionFold fold, long offset) {
        fold.appliedAhead.add(offset);
        drain(fold);
    }

    /// Two threads racing the drain can leave an offset parked — one adds it just after the other has
    /// already tested for it — so the drain is re-run whenever the watermark is READ. Checkpointing is
    /// the reader, and it is periodic, which bounds how long a parked offset can hold the watermark back
    /// to one checkpoint interval rather than forever.
    private static void drain(PartitionFold fold) {
        var advanced = fold.appliedThrough.get();

        while (fold.appliedAhead.remove(advanced + 1)) {
            advanced++;
        }

        fold.appliedThrough.accumulateAndGet(advanced, Math::max);
    }

    /// The highest offset every record at or below which is applied to `state` — the only offset a
    /// checkpoint may honestly claim. Re-drains first; see [#drain].
    long checkpointableThrough(int partition) {
        var fold = partitionFold(partition);

        drain(fold);

        return fold.appliedThrough.get();
    }

    /// The encoded fold of `partition`, for a checkpoint at [#checkpointableThrough].
    byte[] snapshot(int partition) {
        return EntityFoldSnapshot.encode(Map.copyOf(partitionFold(partition).state));
    }

    private PartitionFold partitionFold(int partition) {
        return partitions.computeIfAbsent(partition, _ -> new PartitionFold());
    }

    /// Load the checkpoint, prove the log between it and the head is readable HERE, then replay it.
    private Promise<Unit> rebuild(int partition, PartitionFold fold) {
        if (!substrate.holdsPartition(keyspace, partition)) {
            return new EntityLogError.PartitionNotHeld(keyspace, partition).promise();
        }

        if (!substrate.localLogComplete(keyspace, partition)) {
            return new EntityLogError.FoldInProgress(keyspace, partition).promise();
        }

        return substrate.loadCheckpoint(keyspace, partition)
                        .flatMap(checkpoint -> restoreThenReplay(partition, fold, checkpoint));
    }

    private Promise<Unit> restoreThenReplay(int partition,
                                            PartitionFold fold,
                                            Option<EntityLogSubstrate.EntityCheckpoint> checkpoint) {
        return restore(partition, fold, checkpoint).async()
                      .flatMap(_ -> replayFrom(partition,
                                               fold,
                                               checkpoint.map(c -> c.throughOffset() + 1).or(0L)));
    }

    private Result<Unit> restore(int partition,
                                 PartitionFold fold,
                                 Option<EntityLogSubstrate.EntityCheckpoint> checkpoint) {
        return checkpoint.fold(() -> Result.unitResult(),
                               c -> EntityFoldSnapshot.decode(c.snapshot())
                                                      .map(state -> seed(fold,
                                                                         state,
                                                                         c.throughOffset()))
                                                      .mapError(cause -> new EntityLogError.FoldFailed(keyspace,
                                                                                                       partition,
                                                                                                       cause)));
    }

    private static Unit seed(PartitionFold fold, Map<String, byte[]> state, long throughOffset) {
        fold.state.clear();
        fold.state.putAll(state);
        fold.appliedThrough.set(throughOffset);

        return unit();
    }

    /// Replay `[from, head]`, refusing when this node cannot see all of it.
    ///
    /// The gap check is the safety core. `from` is where the checkpoint left off; `earliestRetained` is
    /// the oldest offset still readable here. If the second is greater than the first, the records
    /// between them are on no node this one can reach — the previous owner's WAL and sealed segments are
    /// node-local, and a replica's copy lives only in its ring. Folding anyway would produce state
    /// missing committed mutations, and every later read would look perfectly healthy.
    private Promise<Unit> replayFrom(int partition, PartitionFold fold, long from) {
        var head = substrate.headOffset(keyspace, partition);

        if (head < from) {
            return Promise.unitPromise();
        }

        var earliestRetained = substrate.earliestRetainedOffset(keyspace, partition);

        if (earliestRetained > from) {
            return new EntityLogError.FoldFailed(keyspace, partition, gapCause(from, earliestRetained)).promise();
        }

        return replayBatch(partition, fold, from, head);
    }

    private static Cause gapCause(long from, long earliestRetained) {
        return new EntityLogError.MalformedRecord("checkpoint resumes at " + from
                                                 + " but the earliest readable offset here is " + earliestRetained
                                                 + " — the records in between are on no reachable node, so the"
                                                 + " partition cannot be rebuilt without losing committed writes");
    }

    private Promise<Unit> replayBatch(int partition, PartitionFold fold, long from, long head) {
        return substrate.read(keyspace, partition, from, REPLAY_BATCH)
                        .flatMap(records -> applyBatch(partition, fold, from, head, records));
    }

    private Promise<Unit> applyBatch(int partition, PartitionFold fold, long from, long head, List<byte[]> records) {
        if (records.isEmpty()) {
            return truncatedCause(partition, from, head);
        }

        var offset = from;

        for (var raw : records) {
            var decoded = EntityLogRecord.decode(raw);

            if (decoded instanceof Result.Failure<EntityLogRecord>(var cause)) {
                return new EntityLogError.FoldFailed(keyspace, partition, cause).promise();
            }

            var applyAt = offset;

            decoded.onSuccess(record -> applyReplayed(fold, record, applyAt));
            offset++;
        }

        return offset > head
               ? Promise.unitPromise()
               : replayBatch(partition, fold, offset, head);
    }

    /// Replay applies records strictly in offset order, so the watermark moves with them directly — the
    /// out-of-order parking that [#advanceApplied] handles cannot arise here.
    private static void applyReplayed(PartitionFold fold, EntityLogRecord record, long offset) {
        applyToState(fold, record);
        fold.appliedThrough.set(offset);
    }

    /// A read that returns nothing while offsets below `head` are still outstanding means the log stopped
    /// being readable mid-replay — retention moving underneath us, or a partition released to another
    /// node. Refusing is the only safe answer: the alternative is a partition that serves state missing
    /// everything from here on.
    private Promise<Unit> truncatedCause(int partition, long from, long head) {
        return new EntityLogError.FoldFailed(keyspace,
                                             partition,
                                             new EntityLogError.MalformedRecord("log ended at offset " + from
                                                                               + " while replaying toward head " + head)).promise();
    }
}
