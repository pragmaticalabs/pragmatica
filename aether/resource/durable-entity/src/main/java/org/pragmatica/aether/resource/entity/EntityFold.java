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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Result.success;
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
///
/// ## Pending timers fold the same way (#345 I4)
/// A key's pending timers are folded from the same records in the same order as its state, so the timer
/// wheel a new owner rebuilds is derived from the log rather than handed over — there is nothing to hand
/// over. A fire is ONE record that both consumes the token and upserts the post-fire state, which is what
/// makes replay of a fired timer produce the fired state instead of re-arming it.
final class EntityFold {
    private static final Logger LOG = LoggerFactory.getLogger(EntityFold.class);
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

    /// One timer still waiting to fire.
    ///
    /// @param fireAtEpochMillis wall-clock instant stamped by the committed OWNER when the schedule was
    ///                          appended
    /// @param command           the encoded command to apply on expiry — held encoded for the same reason
    ///                          state is
    record PendingTimer(long fireAtEpochMillis, byte[] command) {}

    /// A timer whose instant has passed, as handed to the tick that will fire it.
    ///
    /// @param key     the entity key, in the rendered form the log and the fold use
    /// @param token   the timer's identity within that key
    /// @param command the encoded command to apply
    record DueTimer(String key, String token, byte[] command) {}

    /// One partition's rebuild coordination, and the fold it currently PUBLISHES.
    ///
    /// The coordination and the contents are separate objects, and the separation is a durability
    /// requirement rather than tidiness. A rebuild SEEDS a fold from a checkpoint — it installs the
    /// checkpoint's contents and sets the watermark to the checkpoint's offset — and a rebuild can be
    /// re-entered on a partition that is already serving: [#runCatchUp] drops the memo when the log no
    /// longer retains what the fold needs, and [#completeRebuild] drops it on failure. Meanwhile
    /// operations on other keys are already past their readiness gate and still appending.
    ///
    /// Seeding the LIVE fold in that window loses durable records in the one way that stays invisible. A
    /// record the append path had just applied is wiped by the seed, and the watermark is then set in the
    /// same breath, so the fold CLAIMS to have applied it and no catch-up ever re-reads it. A checkpoint
    /// taken from that state makes the loss permanent: the checkpoint pins retention above the offset,
    /// the log copy becomes reclaimable, and a durably-logged timer simply stops firing.
    ///
    /// So a rebuild never touches the published fold. It fills a FRESH [FoldedPartition] and publishes it
    /// with a single reference write, only once the replay has succeeded. Readers therefore see the whole
    /// old fold or the whole new one and never a half-seeded one; a failed rebuild leaves the old fold
    /// serving instead of a gutted one; and records the append path applied to the old instance reach the
    /// new one from the LOG — by the replay when they sit below its head, by the next [#caughtUp] when
    /// they sit above it.
    private static final class PartitionFold {
        private final AtomicReference<FoldedPartition> published = new AtomicReference<>(new FoldedPartition());
        private final AtomicReference<Promise<Unit>> rebuild = new AtomicReference<>();
        private final AtomicReference<Promise<Unit>> catchUp = new AtomicReference<>();
    }

    /// One partition's folded contents at some offset watermark: what a read answers from, and what a
    /// checkpoint records. `timers` maps key to token to the timer pending under it; a key with no pending
    /// timer keeps an empty inner map rather than being removed, which costs one entry per key that ever
    /// held a timer and buys a `computeIfAbsent`-free cancel path. [#timersSnapshot] drops the empties, so
    /// an empty inner map never reaches a checkpoint.
    ///
    /// An instance is mutated in place only by the append path and by catch-up, and both only ever move it
    /// FORWARD. It is never seeded in place — see [PartitionFold] for why that distinction carries the
    /// durability guarantee.
    private static final class FoldedPartition {
        private final Map<String, byte[]> state = new ConcurrentHashMap<>();
        private final Map<String, Map<String, PendingTimer>> timers = new ConcurrentHashMap<>();
        private final ConcurrentSkipListSet<Long> appliedAhead = new ConcurrentSkipListSet<>();
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
        return Option.option(publishedFold(partition).state.get(key));
    }

    /// Whether `token` is STILL pending on `key`. Two callers, one question: a cancel appends a record
    /// only when there is something to cancel, and a fire re-asks this inside the key's serialization
    /// tail so a timer another tick already consumed is not fired twice.
    boolean isTimerPending(int partition, String key, String token) {
        return Option.option(publishedFold(partition).timers.get(key))
                     .map(timers -> timers.containsKey(token))
                     .or(false);
    }

    /// Every timer on `partition` whose instant is at or before `nowMillis`. Only valid after [#ready] —
    /// a partition that has not been rebuilt answers EMPTY, which is honest ("this node knows of no
    /// pending timer here") and is why the tick drives readiness before asking.
    ///
    /// The instant is compared with `<=` so a timer scheduled with zero delay is due immediately rather
    /// than one tick later.
    List<DueTimer> dueTimers(int partition, long nowMillis) {
        return publishedFold(partition).timers.entrySet()
                            .stream()
                            .flatMap(entry -> dueForKey(entry.getKey(),
                                                        entry.getValue(),
                                                        nowMillis))
                            .toList();
    }

    private static Stream<DueTimer> dueForKey(String key, Map<String, PendingTimer> timers, long nowMillis) {
        return timers.entrySet()
                     .stream()
                     .filter(entry -> entry.getValue()
                                           .fireAtEpochMillis() <= nowMillis)
                     .map(entry -> new DueTimer(key,
                                                entry.getKey(),
                                                entry.getValue().command()));
    }

    /// Apply a record that IS in the log at `offset`.
    ///
    /// Called on the write path after the append resolved an offset, and on the replay path for every
    /// record read back. It is deliberately called even when the replication barrier afterwards fails:
    /// the record is in the log, a recovering node WILL replay it, and refusing to apply it here would
    /// leave this node's view disagreeing with the log it is serving from. The caller still learns the
    /// write missed its durability target — that is the promise's job, not this map's.
    ///
    /// The published fold is read ONCE, so the record and the watermark step land on the SAME instance and
    /// the watermark can never claim an offset whose record went to a different one. An offset the
    /// instance already covers is skipped outright: a rebuild that published while this append was in
    /// flight has already replayed it FROM THE LOG, along with everything after it, so re-applying would
    /// regress a key the replay has since advanced. This is the apply-or-account rule [#applyCaughtUp]
    /// states, on the other side of the same race.
    ///
    /// The apply can fail only on a timer record whose payload this build cannot parse, which on THIS
    /// path means bytes this same process encoded moments ago — a build defect, not data it met. The
    /// caller's promise already reports the append's outcome, so nothing is propagated here — but the
    /// watermark is NOT advanced past the failure (#701): a watermark that steps over an unapplied
    /// record asserts coverage the fold does not have, and everything downstream trusts that claim.
    /// Holding it makes the failure compose into the existing honest machinery instead of a silent
    /// permanent divergence: the checkpoint candidate cannot advance past the record (so the
    /// retention floor HOLDS and the log copy stays replayable), and the next [#caughtUp] gate finds
    /// the fold behind head and replays the record through the path that DOES propagate the failure
    /// — reads refuse loudly rather than serving state missing a committed write. The outage mode,
    /// named and bounded: that partition refuses reads and freezes its checkpoint from the poison
    /// offset until a build that can apply the record replays it (restart/catch-up) — bounded by the
    /// held retention floor, never by luck.
    @Contract
    void apply(int partition, long offset, EntityLogRecord record) {
        var data = publishedFold(partition);

        if (offset <= data.appliedThrough.get()) {
            return;
        }

        applyToState(data, record).onSuccess(_ -> advanceApplied(data, offset))
                    .onFailure(cause -> logUnapplicable(offset, record, cause));
    }

    @Contract
    private void logUnapplicable(long offset, EntityLogRecord record, Cause cause) {
        LOG.error("Entity keyspace '{}' could not apply its OWN {} record for key '{}' at offset {}: {}"
                 + " — the applied watermark HOLDS below this record (#701): this partition's checkpoint is"
                 + " frozen here (the retention floor holds, so the log copy stays replayable) and the next"
                 + " read gate will replay the record and refuse loudly rather than serve state missing a"
                 + " committed write. Recovery: deploy a build that can apply the record (or repair it) and"
                 + " restart/catch-up replays it from the retained log",
                  keyspace,
                  record.op(),
                  record.key(),
                  offset,
                  cause.message());
    }

    private static Result<Unit> applyToState(FoldedPartition data, EntityLogRecord record) {
        return switch (record.op()) {
            case UPSERT -> success(applyUpsert(data, record));
            case DELETE -> success(applyDelete(data, record.key()));
            case TIMER_SCHEDULE -> record.timerPayload().map(payload -> applyTimerSchedule(data, record.key(), payload));
            case TIMER_CANCEL -> record.timerPayload().map(payload -> applyTimerCancel(data,
                                                                                       record.key(),
                                                                                       payload.token()));
            case TIMER_FIRE -> record.timerPayload().map(payload -> applyTimerFire(data, record.key(), payload));
        };
    }

    private static Unit applyUpsert(FoldedPartition data, EntityLogRecord record) {
        data.state.put(record.key(), record.state());

        return unit();
    }

    /// A tombstone clears the key's PENDING TIMERS along with its state (spec §5.1 — delete auto-cancels).
    /// Leaving them would arm a fire against a key that no longer exists, once per tick forever: the fire
    /// would find no state, consume the timer, and log an error that named a deletion nobody made a
    /// mistake about.
    private static Unit applyDelete(FoldedPartition data, String key) {
        data.state.remove(key);
        data.timers.remove(key);

        return unit();
    }

    private static Unit applyTimerSchedule(FoldedPartition data, String key, EntityLogRecord.TimerPayload payload) {
        data.timers.computeIfAbsent(key,
                                    _ -> new ConcurrentHashMap<>())
                   .put(payload.token(),
                        new PendingTimer(payload.fireAtEpochMillis(),
                                         payload.body()));

        return unit();
    }

    /// Idempotent by construction: removing a token that is not there is a no-op. That is what makes a
    /// replayed cancel safe, a caller's second cancel safe, and the consume-on-failure record safe when a
    /// fire already removed the token.
    private static Unit applyTimerCancel(FoldedPartition data, String key, String token) {
        Option.option(data.timers.get(key)).onPresent(timers -> timers.remove(token));

        return unit();
    }

    /// ONE record, so the token leaving the pending set and the post-fire state landing are the same
    /// event. Split across two records there would be an offset between them at which a crash re-arms a
    /// timer whose command was already applied — an at-least-once timer wearing a one-shot API.
    private static Unit applyTimerFire(FoldedPartition data, String key, EntityLogRecord.TimerPayload payload) {
        applyTimerCancel(data, key, payload.token());
        data.state.put(key, payload.body());

        return unit();
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
    private static void advanceApplied(FoldedPartition data, long offset) {
        if (offset <= data.appliedThrough.get()) {
            return;
        }

        data.appliedAhead.add(offset);
        drain(data);
    }

    /// Two threads racing the drain can leave an offset parked — one adds it just after the other has
    /// already tested for it — so the drain is re-run by the CHECKPOINT readers,
    /// [#checkpointableThrough] and [#checkpointCandidate]. They are periodic, which bounds how long a
    /// parked offset can hold the watermark back to one checkpoint interval rather than forever.
    ///
    /// The other readers of the watermark do NOT drain, and that is safe in one direction only. The
    /// [#caughtUp] gate and [#runCatchUp]'s replay bound read it raw, so a parked offset reads as
    /// further BEHIND than the fold really is — the fold re-reads records it has already applied, and
    /// [#applyCaughtUp] discards them. Erring that way costs a re-read; erring the other way would skip
    /// a record.
    private static void drain(FoldedPartition data) {
        var advanced = data.appliedThrough.get();

        while (data.appliedAhead.remove(advanced + 1)) {
            advanced++;
        }

        var settled = data.appliedThrough.accumulateAndGet(advanced, Math::max);

        data.appliedAhead.headSet(settled + 1).clear();
    }

    /// The highest offset every record at or below which is applied to `state` — the only offset a
    /// checkpoint may honestly claim. Re-drains first; see [#drain].
    ///
    /// Answers for the fold published RIGHT NOW. A caller that also needs the contents must not read them
    /// separately — see [#checkpointCandidate], which is the only safe pairing.
    long checkpointableThrough(int partition) {
        var data = publishedFold(partition);

        drain(data);

        return data.appliedThrough.get();
    }

    /// The two halves of a checkpoint, both read from ONE published fold.
    ///
    /// @param throughOffset the offset the checkpoint may claim
    /// @param snapshot      the encoded contents, folded at or beyond that offset
    record CheckpointCandidate(long throughOffset, byte[] snapshot) {}

    /// A checkpoint for `partition`, or [Option#none] when this node has nothing to say about it — a
    /// partition never folded here answers a watermark of `-1`, which correctly means "no claim", not
    /// "checkpointed through offset 0".
    ///
    /// The offset and the contents come from the SAME [FoldedPartition], and that is the entire reason this
    /// exists beside [#checkpointableThrough] and [#snapshot]. Read as two calls, a rebuild publishing
    /// between them pairs one instance's offset with another instance's contents — and the UNSAFE direction
    /// is reachable, because a rebuild's replay stops at the log head it read when it started while the
    /// outgoing instance keeps advancing on the append path past that head. The pairing then files contents
    /// folded through 100 under a claim of 102, and records 101 and 102 are lost permanently: recovery
    /// resumes at 103, and the checkpoint pins retention above 102 so the log copies become reclaimable.
    /// Capturing the instance once removes that pairing rather than narrowing it.
    ///
    /// Within one instance the skew can only run the safe way: [#drain] settles the watermark first and the
    /// contents are read after, so the snapshot is at or AHEAD of the offset it is filed under. Recovery
    /// handles that direction, because replaying a record already present in the snapshot is idempotent for
    /// every op.
    Option<CheckpointCandidate> checkpointCandidate(int partition) {
        var data = publishedFold(partition);

        drain(data);
        var through = data.appliedThrough.get();

        return through < 0L
               ? Option.none()
               : Option.some(new CheckpointCandidate(through, encodedFold(data)));
    }

    /// Apply every log record past the watermark, so the fold reflects the log's CURRENT head — records
    /// this node appended AND records replication landed behind its back (#596 review S1).
    ///
    /// Without this, a fold was fed by exactly one thing after its rebuild: the owner's own append path.
    /// A REPLICA's fold was therefore frozen at rebuild time — `BOUNDED_STALE` there served a snapshot,
    /// not a bounded lag — and a replica later PROMOTED kept the frozen view, mutating on top of stale
    /// state and silently dropping every record replicated after its rebuild. Catch-up on access closes
    /// both: staleness becomes replication lag, and a new owner's first operation drains the gap before
    /// it serves or mutates anything.
    ///
    /// One runner per partition; joiners wait and RE-CHECK rather than applying concurrently, because two
    /// interleaved appliers could write one key's older state over its newer one. The skip rules inside
    /// the batch protect the owner's hot path the same way: an offset at or below the watermark, or
    /// parked in `appliedAhead`, was already applied by the append path — re-applying its state could
    /// regress a key the owner has since advanced, so it is only ACCOUNTED, never re-applied.
    ///
    /// The published fold is read ONCE and carried through the whole run, so a rebuild publishing
    /// mid-catch-up cannot leave half the batch on one instance and half on another. Such a run applies
    /// to an instance nobody reads any more, which costs work and loses nothing: the re-check on the next
    /// call sees the new instance's watermark and drains the gap against it.
    Promise<Unit> caughtUp(int partition) {
        var fold = partitionFold(partition);
        var data = fold.published.get();
        var head = substrate.headOffset(keyspace, partition);

        if (data.appliedThrough.get() >= head) {
            return Promise.unitPromise();
        }

        var running = fold.catchUp.get();

        if (running != null) {
            return running.flatMap(_ -> caughtUp(partition));
        }

        var started = Promise.<Unit> promise();
        // Lost the CAS: another runner won — and may ALREADY have completed and nulled the slot, so
        // re-reading it here can NPE (#701). Re-entering re-checks the watermark (the winner may have
        // finished the work outright) and re-reads the slot under the same guards as any fresh call.
        if (!fold.catchUp.compareAndSet(null, started)) {
            return caughtUp(partition);
        }
        // A synchronous throw out of runCatchUp would otherwise escape BETWEEN the won CAS and the
        // onResult attach, leaving the slot holding a promise nothing will ever resolve — every
        // later caller then waits on it forever (#701's liveness sibling: same window, hang instead
        // of NPE). Lifting converts the throw into a resolved failure through the same completion.
        Result.lift(() -> runCatchUp(partition, fold, data, head))
              .onSuccess(run -> run.onResult(result -> completeCatchUp(fold, started, result)))
              .onFailure(cause -> completeCatchUp(fold,
                                                  started,
                                                  cause.result()));

        return started;
    }

    private static void completeCatchUp(PartitionFold fold, Promise<Unit> started, Result<Unit> result) {
        fold.catchUp.set(null);
        started.resolve(result);
    }

    /// A fold whose watermark has fallen behind what the log still RETAINS cannot be caught up record by
    /// record — the missing records are gone from here, and only the (necessarily newer) checkpoint can
    /// bridge them. Clearing the rebuild memo makes the next access re-run the full rebuild; the failure
    /// returned here is transient, exactly like [EntityLogError.FoldInProgress].
    ///
    /// Clearing the memo on a LIVE partition is safe only because the rebuild it re-arms builds a fresh
    /// fold and publishes it whole; see [PartitionFold].
    private Promise<Unit> runCatchUp(int partition, PartitionFold fold, FoldedPartition data, long head) {
        var from = data.appliedThrough.get() + 1;

        if (from > head) {
            return Promise.unitPromise();
        }

        if (substrate.earliestRetainedOffset(keyspace, partition) > from) {
            fold.rebuild.set(null);

            return new EntityLogError.FoldInProgress(keyspace, partition).promise();
        }

        return catchUpBatch(partition, data, from, head);
    }

    private Promise<Unit> catchUpBatch(int partition, FoldedPartition data, long from, long head) {
        return substrate.read(keyspace, partition, from, REPLAY_BATCH)
                        .flatMap(records -> applyCatchUpBatch(partition, data, from, head, records));
    }

    /// An empty read below the head is a replication gap still in flight, not corruption — transient,
    /// unlike the rebuild replay's refusal, because a replica's ring fills as replication lands.
    private Promise<Unit> applyCatchUpBatch(int partition,
                                            FoldedPartition data,
                                            long from,
                                            long head,
                                            List<byte[]> records) {
        if (records.isEmpty()) {
            return new EntityLogError.FoldInProgress(keyspace, partition).promise();
        }

        var offset = from;

        for (var raw : records) {
            var applyAt = offset;
            var applied = EntityLogRecord.decode(raw).flatMap(record -> applyCaughtUp(data, record, applyAt));

            if (applied instanceof Result.Failure<Unit>(var cause)) {
                return new EntityLogError.FoldFailed(keyspace, partition, cause).promise();
            }

            offset++;
        }

        return offset > head
               ? Promise.unitPromise()
               : catchUpBatch(partition, data, offset, head);
    }

    /// Apply-or-account: state is written ONLY for an offset the append path has not already applied.
    /// `remove` on the parked set is atomic, so exactly one side ever accounts an offset; either way the
    /// watermark advances monotonically via max. Timer records are subject to the SAME skip — re-applying
    /// a schedule the append path already applied would be harmless, but re-applying one the owner has
    /// since cancelled would resurrect a consumed timer.
    private static Result<Unit> applyCaughtUp(FoldedPartition data, EntityLogRecord record, long offset) {
        return offset > data.appliedThrough.get() && !data.appliedAhead.remove(offset)
               ? applyToState(data, record).onSuccess(_ -> account(data, offset))
               : success(account(data, offset));
    }

    private static Unit account(FoldedPartition data, long offset) {
        data.appliedThrough.accumulateAndGet(offset, Math::max);

        return unit();
    }

    /// The encoded fold of `partition`, for a checkpoint at [#checkpointableThrough]. A caller writing a
    /// checkpoint wants [#checkpointCandidate] instead, which reads both halves from one instance.
    byte[] snapshot(int partition) {
        return encodedFold(publishedFold(partition));
    }

    private static byte[] encodedFold(FoldedPartition data) {
        return EntityFoldSnapshot.encode(Map.copyOf(data.state), timersSnapshot(data));
    }

    /// Keys whose timers have all fired or been cancelled keep an empty inner map (see [FoldedPartition]);
    /// they are dropped here so a checkpoint stays proportional to timers that actually exist.
    private static Map<String, Map<String, PendingTimer>> timersSnapshot(FoldedPartition data) {
        return data.timers.entrySet()
                          .stream()
                          .filter(entry -> !entry.getValue()
                                                 .isEmpty())
                          .collect(Collectors.toMap(Map.Entry::getKey,
                                                    entry -> Map.copyOf(entry.getValue())));
    }

    private PartitionFold partitionFold(int partition) {
        return partitions.computeIfAbsent(partition, _ -> new PartitionFold());
    }

    private FoldedPartition publishedFold(int partition) {
        return partitionFold(partition).published.get();
    }

    /// Load the checkpoint, prove the log between it and the head is readable HERE, then replay it — all
    /// into a fold NOTHING is reading, which is published only once the replay has succeeded.
    private Promise<Unit> rebuild(int partition, PartitionFold fold) {
        if (!substrate.holdsPartition(keyspace, partition)) {
            return new EntityLogError.PartitionNotHeld(keyspace, partition).promise();
        }

        if (!substrate.localLogComplete(keyspace, partition)) {
            return new EntityLogError.FoldInProgress(keyspace, partition).promise();
        }

        var building = new FoldedPartition();

        return substrate.loadCheckpoint(keyspace, partition)
                        .flatMap(checkpoint -> restoreThenReplay(partition, building, checkpoint))
                        .map(_ -> publish(fold, building));
    }

    /// The single reference write that makes a rebuilt fold visible. Everything before it ran on an
    /// instance no reader could reach, so there is no window in which a partition serves a fold that is
    /// seeded but not yet replayed.
    ///
    /// Last writer wins, and the published fold's coverage may therefore go BACKWARDS: a rebuild replays
    /// only to the head it read when it started, while the outgoing instance keeps advancing on the append
    /// path, and two rebuilds racing (both [#runCatchUp] and [#completeRebuild] clear the memo with a plain
    /// `set`) can land out of order.
    ///
    /// For READS that costs staleness and nothing else — every operation passes [#caughtUp] after
    /// [#ready], which drains the new instance to the log head before it serves anything.
    ///
    /// For CHECKPOINTS it would cost more than staleness, and the guard is not here. A regressed fold
    /// files an honest but LOWER claim, and the substrate publishes checkpoint pointers with a blind put,
    /// so that lower pointer would replace a higher one whose retention floor had already let the log
    /// below it be reclaimed — leaving the records in between nowhere. [EntityCheckpointDriver] therefore
    /// refuses to write a checkpoint that does not advance the last one it wrote. The pairing half is
    /// here, in [#checkpointCandidate]; the monotonicity half is there, because only the writer knows what
    /// it last published.
    private static Unit publish(PartitionFold fold, FoldedPartition built) {
        fold.published.set(built);

        return unit();
    }

    private Promise<Unit> restoreThenReplay(int partition,
                                            FoldedPartition building,
                                            Option<EntityLogSubstrate.EntityCheckpoint> checkpoint) {
        return restore(partition, building, checkpoint).async()
                      .flatMap(_ -> replayFrom(partition,
                                               building,
                                               checkpoint.map(c -> c.throughOffset() + 1).or(0L)));
    }

    private Result<Unit> restore(int partition,
                                 FoldedPartition building,
                                 Option<EntityLogSubstrate.EntityCheckpoint> checkpoint) {
        return checkpoint.fold(() -> Result.unitResult(),
                               c -> EntityFoldSnapshot.decode(c.snapshot())
                                                      .map(snapshot -> seed(building,
                                                                            snapshot,
                                                                            c.throughOffset()))
                                                      .mapError(cause -> new EntityLogError.FoldFailed(keyspace,
                                                                                                       partition,
                                                                                                       cause)));
    }

    /// State AND timers are seeded together, because the checkpoint recorded them together. Seeding only
    /// the state would leave a recovering node with a fold whose keys are current and whose timer wheel is
    /// empty — every timer scheduled before the checkpoint silently dropped, on the one node that took
    /// over, with nothing downstream able to tell.
    ///
    /// `building` is a fold no reader can reach and no append path can touch, so there is nothing to clear
    /// and nothing to race: seeding is a plain fill of an empty instance. That is what makes it atomic
    /// against the append path — not ordering inside this method, but the fact that the instance is
    /// unpublished until [#publish].
    private static Unit seed(FoldedPartition building, EntityFoldSnapshot.FoldedState snapshot, long throughOffset) {
        building.state.putAll(snapshot.state());
        snapshot.timers().forEach((key, timers) -> building.timers.put(key, new ConcurrentHashMap<>(timers)));
        building.appliedThrough.set(throughOffset);

        return unit();
    }

    /// Replay `[from, head]`, refusing when this node cannot see all of it.
    ///
    /// The gap check is the safety core. `from` is where the checkpoint left off; `earliestRetained` is
    /// the oldest offset still readable here. If the second is greater than the first, the records
    /// between them are on no node this one can reach — the previous owner's WAL and sealed segments are
    /// node-local, and a replica's copy lives only in its ring. Folding anyway would produce state
    /// missing committed mutations, and every later read would look perfectly healthy.
    private Promise<Unit> replayFrom(int partition, FoldedPartition building, long from) {
        var head = substrate.headOffset(keyspace, partition);

        if (head < from) {
            return Promise.unitPromise();
        }

        var earliestRetained = substrate.earliestRetainedOffset(keyspace, partition);

        if (earliestRetained > from) {
            return new EntityLogError.FoldFailed(keyspace, partition, gapCause(from, earliestRetained)).promise();
        }

        return replayBatch(partition, building, from, head);
    }

    private static Cause gapCause(long from, long earliestRetained) {
        return new EntityLogError.MalformedRecord("checkpoint resumes at " + from
                                                 + " but the earliest readable offset here is " + earliestRetained
                                                 + " — the records in between are on no reachable node, so the"
                                                 + " partition cannot be rebuilt without losing committed writes");
    }

    private Promise<Unit> replayBatch(int partition, FoldedPartition building, long from, long head) {
        return substrate.read(keyspace, partition, from, REPLAY_BATCH)
                        .flatMap(records -> applyBatch(partition, building, from, head, records));
    }

    private Promise<Unit> applyBatch(int partition,
                                     FoldedPartition building,
                                     long from,
                                     long head,
                                     List<byte[]> records) {
        if (records.isEmpty()) {
            return truncatedCause(partition, from, head);
        }

        var offset = from;

        for (var raw : records) {
            var applyAt = offset;
            var applied = EntityLogRecord.decode(raw).flatMap(record -> applyReplayed(building, record, applyAt));

            if (applied instanceof Result.Failure<Unit>(var cause)) {
                return new EntityLogError.FoldFailed(keyspace, partition, cause).promise();
            }

            offset++;
        }

        return offset > head
               ? Promise.unitPromise()
               : replayBatch(partition, building, offset, head);
    }

    /// Replay applies records strictly in offset order, so the watermark moves with them directly — the
    /// out-of-order parking that [#advanceApplied] handles cannot arise here. The watermark advances only
    /// on a SUCCESSFUL apply, so a payload this build cannot parse fails the fold at the offset it was
    /// met rather than being counted as folded.
    private static Result<Unit> applyReplayed(FoldedPartition building, EntityLogRecord record, long offset) {
        return applyToState(building, record).onSuccess(_ -> building.appliedThrough.set(offset));
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
