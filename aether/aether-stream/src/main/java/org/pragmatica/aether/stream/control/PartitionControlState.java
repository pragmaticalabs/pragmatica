// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.control;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import java.util.stream.Stream;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.aether.stream.StreamPartitionManager.ControlRunAppend;
import org.pragmatica.aether.stream.control.ControlOutcome.Admitted;
import org.pragmatica.aether.stream.control.ControlOutcome.IndexUnavailable;
import org.pragmatica.aether.stream.control.ControlOutcome.OutcomeUnknown;
import org.pragmatica.aether.stream.control.ControlOutcome.Refused;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;


/// The control state of one partition on this node (#2590 P1, §5.2): the latest-per-key COMMITTED index, the owner's
/// in-flight TAIL, their validity, and the serial queue every change runs on.
///
/// **Admitted view (N1).** Admission reads the committed index plus the tail: records this owner appended whose barrier
/// has not resolved, and any appended record of the local log above the committed offset (a catch-up that landed, a
/// replicated record kept by #1730 across a promotion). Admission and the local append run as ONE queue task, so no two
/// admissions of a partition interleave. A tail record leaves the tail only by committing (into the index) or by a
/// truncation, which invalidates everything; ANY failure after the append keeps it there (N3).
///
/// **Committed feed.** The index applies a record only once its offset is at or below the committed offset this node
/// knows ([ControlPartitionLog#committedThrough]); the transfer runs on the queue and inserts into the index BEFORE it
/// removes from the tail (N2-a), so no admission ever finds a record in neither.
///
/// **Validity.** The state is valid for one ring incarnation, and its tail for one ownership epoch. A new ring (a
/// materialization), a suffix truncation, or a hole in the retained log leave it INVALID; a new ownership epoch leaves
/// the tail unusable for admission (a promotion, N2-b). Either is repaired by a rebuild from the log, run off the queue,
/// installed on it, and refused over a hole. Until a rebuild completes, admission answers [IndexUnavailable] and reads
/// fail with [ControlLogError.General#INDEX_UNAVAILABLE]: an invalid index is never read as an empty one.
final class PartitionControlState {
    private static final Logger log = LoggerFactory.getLogger(PartitionControlState.class);
    private static final int PAGE = 512;

    enum Phase {
        INVALID,
        REBUILDING,
        VALID
    }

    /// What a rebuild was started against; installed only if nothing invalidated the state since.
    record RebuildTarget(long generation,
                         boolean asOwner,
                         Option<Long> ring,
                         Option<Epoch> ownerEpoch,
                         long committed,
                         long head,
                         long limit) {}

    /// The admission decision for one record of a batch.
    sealed interface Decision {
        record Admit(ControlRecord record) implements Decision {}

        record Decided(ControlOutcome outcome) implements Decision {}
    }

    /// One record's slot in a staged append: decided before the append, or appended at `offset`.
    sealed interface Slot {
        record Final(ControlOutcome outcome) implements Slot {}

        record Appended(long offset) implements Slot {}
    }

    /// A queue task's result: one slot per record, and the run when anything was appended.
    record Staged(List<Slot> slots, Option<ControlRunAppend> run) {
        static Staged unavailable(int count) {
            return new Staged(List.copyOf(Collections.nCopies(count, new Slot.Final(IndexUnavailable.INDEX_UNAVAILABLE))),
                              none());
        }

        List<ControlOutcome> outcomes(Result<Unit> confirmed) {
            return slots.stream()
                        .map(slot -> outcome(slot, confirmed))
                        .toList();
        }

        private static ControlOutcome outcome(Slot slot, Result<Unit> confirmed) {
            return switch (slot) {
                case Slot.Final(var outcome) -> outcome;
                case Slot.Appended(var offset) -> confirmed.fold(OutcomeUnknown::new, _ -> new Admitted(offset));
            };
        }
    }

    private final String streamName;
    private final int partition;
    private final ControlPartitionLog partitionLog;
    private final ControlKind.Admissions admissions;
    private final MirrorView mirror;
    private final SerialQueue queue = new SerialQueue();
    /// The committed index: written on the queue only, read from any thread.
    private final Map<String, IndexedRecord> index = new ConcurrentHashMap<>();
    /// The in-flight tail, by offset: queue-only.
    private final NavigableMap<Long, ControlRecord> tail = new TreeMap<>();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong runAppends = new AtomicLong();
    private final AtomicLong appendItems = new AtomicLong();
    private volatile Phase phase = Phase.INVALID;
    private volatile boolean rebuildingAsOwner;
    /// The highest offset applied to the index.
    private volatile long appliedThrough = -1L;
    /// The highest offset of the local log already accounted for in the index or the tail (queue-only).
    private long accountedThrough = -1L;
    private volatile Option<Long> builtOnRing = none();
    private volatile Option<Epoch> builtAsOwnerAt = none();
    /// Test seam: runs inside an append task after every record of the batch was judged and before the run is appended.
    private volatile Runnable admissionProbe = PartitionControlState::noProbe;
    /// Test seam: runs inside the committed transfer after a record entered the index and before it leaves the tail.
    private volatile LongConsumer transferProbe = PartitionControlState::noOffsetProbe;

    PartitionControlState(String streamName,
                          int partition,
                          ControlPartitionLog partitionLog,
                          ControlKind.Admissions admissions,
                          MirrorView mirror) {
        this.streamName = streamName;
        this.partition = partition;
        this.partitionLog = partitionLog;
        this.admissions = admissions;
        this.mirror = mirror;
    }

    // ---- the operations (any thread) ----
    /// The owner append of a client batch: admission and the run append as one queue task, then the fsync and the
    /// confirmation barrier awaited OUTSIDE the queue, then the committed transfer queued again.
    Promise<List<ControlOutcome>> append(List<ControlRecord> records) {
        return queue.submit(() -> admitAndAppend(records))
                    .flatMap(this::confirm);
    }

    /// Re-append, as a CARRY, every live latest record below `belowOffset` that is still its key's latest record in the
    /// admitted view. A CARRY bypasses admission (it restates a record that was already admitted) but not the fence, the
    /// fsync or the barrier.
    Promise<List<ControlOutcome>> carryForward(long belowOffset) {
        return queue.submit(() -> carryAndAppend(belowOffset))
                    .flatMap(this::confirm);
    }

    /// The key's latest committed record. Fails while the index is not valid, and starts the rebuild that makes it so.
    Result<Option<IndexedRecord>> latest(String key) {
        if (!indexValid()) {
            requestRebuild(false);

            return ControlLogError.General.INDEX_UNAVAILABLE.result();
        }

        return Result.success(option(index.get(key)));
    }

    /// The retention floor: the highest offset whose deletion loses no live key's latest record. `-1` while the index is
    /// not valid, so nothing is reclaimed on a stale index.
    long deletableThroughOffset() {
        if (!indexValid()) {
            return -1L;
        }

        var oldest = index.values().stream().mapToLong(IndexedRecord::offset).min();

        return oldest.isPresent()
               ? oldest.getAsLong() - 1
               : appliedThrough;
    }

    /// The partition is committed through `through` here: move what that covers into the index, on the queue.
    @Contract
    void committed(long through) {
        queue.submit(() -> advance(through));
    }

    /// The ring dropped its suffix above `keepThrough`. Called under the ring's append lock, so it only invalidates; the
    /// next read or admission starts the rebuild.
    @Contract
    synchronized void truncated(long keepThrough) {
        generation.incrementAndGet();
        phase = Phase.INVALID;
        log.info("Control index of {}[{}] invalidated by a suffix truncation through {}",
                 streamName,
                 partition,
                 keepThrough);
    }

    // ---- queue tasks ----
    private Result<Staged> admitAndAppend(List<ControlRecord> records) {
        appendItems.incrementAndGet();
        if (!admissionReady()) {
            return Result.success(Staged.unavailable(records.size()));
        }

        return syncTail().fold(cause -> unavailableAfter(cause, records.size()),
                               _ -> Result.success(appendAdmitted(judge(records))));
    }

    private List<Decision> judge(List<ControlRecord> records) {
        var overlay = new HashMap<String, Option<ControlRecord>>();
        var decisions = new ArrayList<Decision>(records.size());

        for (var record : records) {
            decisions.add(decide(record, overlay));
        }

        admissionProbe.run();

        return decisions;
    }

    /// One record against the admitted view, plus the records admitted before it in the same batch (`overlay`).
    private Decision decide(ControlRecord record, Map<String, Option<ControlRecord>> overlay) {
        return switch (record.kind()) {
            case ControlKind.Builtin.CARRY -> new Decision.Decided(new Refused(ControlLogError.General.CARRY_NOT_WRITABLE));
            case ControlKind.Builtin.TOMBSTONE -> admit(record, none(), overlay);
            case ControlKind.Named named -> admitNamed(named, record, overlay);
        };
    }

    private Decision admitNamed(ControlKind.Named kind,
                                ControlRecord record,
                                Map<String, Option<ControlRecord>> overlay) {
        return admissions.admissionFor(kind)
                         .toResult(ControlLogError.UnknownKind.FACTORY.apply(kind.name()))
                         .flatMap(admission -> admission.admit(viewLatest(record.key(),
                                                                          overlay),
                                                               record,
                                                               mirror))
                         .fold(cause -> new Decision.Decided(new Refused(cause)),
                               _ -> admit(record,
                                          some(record),
                                          overlay));
    }

    private static Decision admit(ControlRecord record,
                                  Option<ControlRecord> becomes,
                                  Map<String, Option<ControlRecord>> overlay) {
        overlay.put(record.key(), becomes);

        return new Decision.Admit(record);
    }

    /// The key's latest record in the admitted view: an earlier record of this batch, else the newest tail record, else
    /// the committed index. A tombstone reads as no record; a CARRY reads as the record it restates.
    private Option<ControlRecord> viewLatest(String key, Map<String, Option<ControlRecord>> overlay) {
        return overlay.containsKey(key)
               ? overlay.get(key)
               : newestInTail(key).map(PartitionControlState::heldBy)
                             .or(() -> committedLatest(key));
    }

    private Option<ControlRecord> committedLatest(String key) {
        return option(index.get(key)).map(IndexedRecord::record);
    }

    private Option<Map.Entry<Long, ControlRecord>> newestInTail(String key) {
        return Option.from(tail.descendingMap().entrySet().stream().filter(entry -> isOfKey(entry, key)).findFirst());
    }

    private static boolean isOfKey(Map.Entry<Long, ControlRecord> entry, String key) {
        return entry.getValue()
                    .key()
                    .equals(key);
    }

    private static Option<ControlRecord> heldBy(Map.Entry<Long, ControlRecord> entry) {
        return switch (entry.getValue()
                            .kind()) {
            case ControlKind.Builtin.TOMBSTONE -> none();
            // Recovery: design-out -- a tail CARRY was encoded by this owner ([#carryDecision]), so it always decodes.
            case ControlKind.Builtin.CARRY -> carried(entry.getValue()).option();
            case ControlKind.Named _ -> some(entry.getValue());
        };
    }

    private static Result<ControlRecord> carried(ControlRecord carry) {
        return ControlRecordCodec.decode(carry.payload()).flatMap(decoded -> decoded.toResult(ControlLogError.MalformedRecord.FACTORY.apply("a CARRY whose payload is not a control record")));
    }

    /// Appends every admitted record as ONE run and puts it in the tail at its offset. A refusal of the run refuses each
    /// admitted record with the substrate's cause; nothing was appended.
    private Staged appendAdmitted(List<Decision> decisions) {
        var admitted = decisions.stream().flatMap(PartitionControlState::admittedRecord).toList();

        if (admitted.isEmpty()) {
            return new Staged(finalSlots(decisions), none());
        }

        return appendRun(admitted).fold(cause -> new Staged(refusedSlots(decisions, cause), none()),
                                        run -> new Staged(appendedSlots(decisions, run), some(run)));
    }

    private static Stream<ControlRecord> admittedRecord(Decision decision) {
        return switch (decision) {
            case Decision.Admit(var record) -> Stream.of(record);
            case Decision.Decided _ -> Stream.empty();
        };
    }

    private Result<ControlRunAppend> appendRun(List<ControlRecord> records) {
        runAppends.incrementAndGet();

        return partitionLog.appendRun(streamName,
                                      partition,
                                      records.stream().map(ControlRecordCodec::encode).toList(),
                                      System.currentTimeMillis())
                           .onSuccess(run -> enterTail(run, records));
    }

    @Contract
    private void enterTail(ControlRunAppend run, List<ControlRecord> records) {
        for (var i = 0; i < records.size(); i++) {
            tail.put(run.firstOffset() + i, records.get(i));
        }

        accountedThrough = Math.max(accountedThrough, run.lastOffset());
    }

    private static List<Slot> finalSlots(List<Decision> decisions) {
        return decisions.stream()
                        .map(decision -> (Slot) new Slot.Final(finalOutcome(decision)))
                        .toList();
    }

    private static ControlOutcome finalOutcome(Decision decision) {
        return switch (decision) {
            case Decision.Decided(var outcome) -> outcome;
            case Decision.Admit _ -> IndexUnavailable.INDEX_UNAVAILABLE;
        };
    }

    private static List<Slot> refusedSlots(List<Decision> decisions, Cause cause) {
        return decisions.stream()
                        .map(decision -> (Slot) new Slot.Final(refusedOrFinal(decision, cause)))
                        .toList();
    }

    private static ControlOutcome refusedOrFinal(Decision decision, Cause cause) {
        return switch (decision) {
            case Decision.Decided(var outcome) -> outcome;
            case Decision.Admit _ -> new Refused(cause);
        };
    }

    private static List<Slot> appendedSlots(List<Decision> decisions, ControlRunAppend run) {
        var slots = new ArrayList<Slot>(decisions.size());
        var next = run.firstOffset();

        for (var decision : decisions) {
            if (decision instanceof Decision.Decided(var outcome)) {
                slots.add(new Slot.Final(outcome));
            } else {
                slots.add(new Slot.Appended(next++));
            }
        }

        return List.copyOf(slots);
    }

    private Result<Staged> carryAndAppend(long belowOffset) {
        if (!admissionReady()) {
            return Result.success(Staged.unavailable(1));
        }

        return syncTail().fold(cause -> unavailableAfter(cause, 1),
                               _ -> Result.success(appendCarries(carryDecisions(belowOffset))));
    }

    /// A candidate is carried only if it is still its key's latest record in the admitted view (N1): a newer record in the
    /// tail already moves the floor, and a CARRY appended after it would make the index regress to the older value.
    private List<Decision> carryDecisions(long belowOffset) {
        var candidates = index.values()
                              .stream()
                              .filter(indexed -> indexed.offset() < belowOffset)
                              .sorted(Comparator.comparingLong(IndexedRecord::offset));

        return candidates.map(this::carryDecision)
                         .toList();
    }

    private Decision carryDecision(IndexedRecord indexed) {
        return newestInTail(indexed.record().key()).isEmpty()
               ? new Decision.Admit(ControlRecord.controlRecord(ControlKind.Builtin.CARRY,
                                                                indexed.record().key(),
                                                                ControlRecordCodec.encode(indexed.record()),
                                                                indexed.record().hlc()))
               : new Decision.Decided(new Refused(ControlLogError.General.CARRY_SUPERSEDED));
    }

    private Staged appendCarries(List<Decision> decisions) {
        return appendAdmitted(decisions);
    }

    /// The committed transfer: every record in `(appliedThrough, min(through, head)]`, in offset order, into the index and
    /// then out of the tail.
    private Result<Unit> advance(long through) {
        var to = Math.min(through, partitionLog.head(streamName, partition));

        if (!indexValid() || to <= appliedThrough) {
            return Result.unitResult();
        }

        return readRing(appliedThrough + 1, to).flatMap(PartitionControlState::decodeAll)
                       .flatMap(records -> transfer(records, to))
                       .recover(this::invalidatedUnit);
    }

    private Result<Unit> transfer(List<Logged> records, long to) {
        for (var logged : records) {
            var applied = applyToIndex(logged);

            if (applied.isFailure()) {
                return applied;
            }

            transferProbe.accept(logged.offset());
            tail.remove(logged.offset());
        }

        appliedThrough = to;
        accountedThrough = Math.max(accountedThrough, to);

        return Result.unitResult();
    }

    private Result<Unit> applyToIndex(Logged logged) {
        return logged.record()
                     .map(record -> applyToIndex(logged.offset(),
                                                 record))
                     .or(Result::unitResult);
    }

    private Result<Unit> applyToIndex(long offset, ControlRecord record) {
        return switch (record.kind()) {
            case ControlKind.Builtin.TOMBSTONE -> removeFromIndex(record.key());
            case ControlKind.Builtin.CARRY -> carried(record).map(restated -> putInIndex(offset, restated));
            case ControlKind.Named _ -> Result.success(putInIndex(offset, record));
        };
    }

    private Unit putInIndex(long offset, ControlRecord record) {
        index.put(record.key(), new IndexedRecord(offset, record));

        return unit();
    }

    private Result<Unit> removeFromIndex(String key) {
        index.remove(key);

        return Result.unitResult();
    }

    /// Accounts for every record the local log holds above what the index and the tail already cover, so the admitted
    /// view includes a record that landed without this owner appending it.
    private Result<Unit> syncTail() {
        var head = partitionLog.head(streamName, partition);

        if (head <= accountedThrough) {
            return Result.unitResult();
        }

        return readRing(accountedThrough + 1, head).flatMap(PartitionControlState::decodeAll)
                       .map(records -> intoTail(records, head));
    }

    private Unit intoTail(List<Logged> records, long head) {
        records.stream()
               .filter(logged -> logged.offset() > appliedThrough)
               .forEach(logged -> logged.record()
                                        .onPresent(record -> tail.put(logged.offset(),
                                                                      record)));
        accountedThrough = head;

        return unit();
    }

    /// Recovery: FER, design-out of a wrong answer -- a failed read or an undecodable record leaves the state INVALID
    /// (answers [IndexUnavailable], floor `-1`) and the next read or admission rebuilds from the log; `value` is what the
    /// caller is told meanwhile.
    private <T> T invalidated(Cause cause, T value) {
        invalidate();
        log.warn("Control index of {}[{}] invalidated: {}", streamName, partition, cause.message());

        return value;
    }

    private Unit invalidatedUnit(Cause cause) {
        return invalidated(cause, unit());
    }

    private Result<Staged> unavailableAfter(Cause cause, int count) {
        return Result.success(invalidated(cause, Staged.unavailable(count)));
    }

    // ---- confirmation, outside the queue ----
    private Promise<List<ControlOutcome>> confirm(Staged staged) {
        return staged.run()
                     .map(run -> confirmedOutcomes(staged, run))
                     .or(() -> Promise.success(staged.outcomes(Result.unitResult())));
    }

    private Promise<List<ControlOutcome>> confirmedOutcomes(Staged staged, ControlRunAppend run) {
        return awaitRun(run).fold(confirmed -> Promise.success(staged.outcomes(confirmed)));
    }

    /// The fsync, then the barrier with the confirmation factor read now, then the transfer queued. A failure of either
    /// wait leaves the run in the tail (N3): it was appended and replicated, and may still commit.
    private Promise<Unit> awaitRun(ControlRunAppend run) {
        return run.durable()
                  .flatMap(_ -> partitionLog.awaitConfirmed(streamName,
                                                            partition,
                                                            run.lastOffset()))
                  .flatMap(_ -> queue.submit(() -> advance(run.lastOffset())));
    }

    // ---- validity and rebuild ----
    private boolean indexValid() {
        return phase == Phase.VALID && builtOnRing.equals(partitionLog.ringIncarnation(streamName, partition));
    }

    /// The admitted view is usable: the index is valid and its tail was built under the current ownership epoch (a new
    /// epoch is a promotion, N2-b, and starts the owner rebuild), and the ownership record carries a committed in-sync set
    /// (R4.1: with `isrVersion == 0` a promoted replica may lag a confirmed record, so nothing is admitted).
    private boolean admissionReady() {
        if (!indexValid() || !builtAsOwnerAt.equals(some(partitionLog.ownerEpoch(streamName, partition)))) {
            requestRebuild(true);

            return false;
        }

        return partitionLog.isrVersion(streamName, partition) > 0;
    }

    /// Starts a rebuild unless one that covers the need is running. An owner rebuild is needed for admission: it also
    /// rebuilds the tail.
    @Contract
    private void requestRebuild(boolean asOwner) {
        startRebuild(asOwner).onPresent(target -> readRange(partitionLog.retainedFrom(streamName, partition),
                                                            target.limit()).onResult(read -> installLater(target, read)));
    }

    private synchronized Option<RebuildTarget> startRebuild(boolean asOwner) {
        if (phase == Phase.REBUILDING && (rebuildingAsOwner || !asOwner)) {
            return none();
        }

        if (phase == Phase.VALID && !asOwner && indexValid()) {
            return none();
        }

        phase = Phase.REBUILDING;
        rebuildingAsOwner = asOwner;

        return some(rebuildTarget(generation.incrementAndGet(), asOwner));
    }

    @Contract
    private synchronized void invalidate() {
        generation.incrementAndGet();
        phase = Phase.INVALID;
    }

    /// Validity is set only if nothing invalidated the state since the rebuild started.
    private synchronized boolean markValid(RebuildTarget target) {
        if (generation.get() != target.generation()) {
            return false;
        }

        builtOnRing = target.ring();
        builtAsOwnerAt = target.ownerEpoch();
        phase = Phase.VALID;

        return true;
    }

    /// An owner rebuilds to its LOCAL head (N2-b): the records above the committed offset are #1730's kept tail and
    /// commit later under the new epoch. Any other node rebuilds to the committed offset it knows.
    private RebuildTarget rebuildTarget(long rebuildGeneration, boolean asOwner) {
        var committed = partitionLog.committedThrough(streamName, partition);
        var head = partitionLog.head(streamName, partition);

        return new RebuildTarget(rebuildGeneration,
                                 asOwner,
                                 partitionLog.ringIncarnation(streamName, partition),
                                 asOwner
                                 ? some(partitionLog.ownerEpoch(streamName, partition))
                                 : none(),
                                 committed,
                                 head,
                                 asOwner
                                 ? head
                                 : Math.min(committed, head));
    }

    @Contract
    private void installLater(RebuildTarget target, Result<List<RawEvent>> read) {
        queue.submit(() -> install(target, read));
    }

    private Result<Unit> install(RebuildTarget target, Result<List<RawEvent>> read) {
        if (generation.get() != target.generation()) {
            return Result.unitResult();
        }

        return read.flatMap(PartitionControlState::decodeAll)
                   .flatMap(records -> installRecords(target, records))
                   .recover(this::invalidatedUnit);
    }

    private Result<Unit> installRecords(RebuildTarget target, List<Logged> records) {
        if (!target.ring().equals(partitionLog.ringIncarnation(streamName, partition))) {
            return ControlLogError.General.INDEX_UNAVAILABLE.result();
        }

        index.clear();
        tail.clear();
        var committedThrough = Math.min(target.committed(), target.limit());

        for (var logged : records) {
            var placed = place(target, logged, committedThrough);

            if (placed.isFailure()) {
                return placed;
            }
        }

        appliedThrough = committedThrough;
        accountedThrough = target.asOwner()
                           ? target.head()
                           : target.limit();

        return markValid(target)
               ? advance(partitionLog.committedThrough(streamName, partition))
               : Result.unitResult();
    }

    private Result<Unit> place(RebuildTarget target, Logged logged, long committedThrough) {
        if (logged.offset() <= committedThrough) {
            return applyToIndex(logged);
        }

        if (target.asOwner()) {
            logged.record().onPresent(record -> tail.put(logged.offset(), record));
        }

        return Result.unitResult();
    }

    // ---- reading the log ----
    /// A decoded record of the log at its offset: [Option#none] for an event that is not a control record.
    record Logged(long offset, Option<ControlRecord> record) {}

    private static Result<List<Logged>> decodeAll(List<RawEvent> events) {
        return Result.allOf(events.stream().map(PartitionControlState::decoded).toList());
    }

    private static Result<Logged> decoded(RawEvent event) {
        return ControlRecordCodec.decode(event.data()).map(record -> new Logged(event.offset(), record));
    }

    /// `[from, to]` of the retained log, paged, every page continuing exactly where the last ended.
    private Promise<List<RawEvent>> readRange(long from, long to) {
        return from > to
               ? Promise.success(List.of())
               : readPage(from, to, List.of());
    }

    private Promise<List<RawEvent>> readPage(long next, long to, List<RawEvent> soFar) {
        return partitionLog.read(streamName,
                                 partition,
                                 next,
                                 (int) Math.min(PAGE, to - next + 1))
                           .flatMap(page -> contiguous(next, page, to).async())
                           .flatMap(page -> nextPage(page, to, soFar));
    }

    private Promise<List<RawEvent>> nextPage(List<RawEvent> page, long to, List<RawEvent> soFar) {
        var all = concat(soFar, page);
        var last = page.getLast().offset();

        return last >= to
               ? Promise.success(all)
               : readPage(last + 1, to, all);
    }

    /// The ring's `[from, to]`, synchronously, paged and contiguous: the committed feed and the tail sync read records
    /// the ring still holds.
    private Result<List<RawEvent>> readRing(long from, long to) {
        return readRingFrom(from, to, List.of());
    }

    private Result<List<RawEvent>> readRingFrom(long next, long to, List<RawEvent> soFar) {
        if (next > to) {
            return Result.success(soFar);
        }

        return partitionLog.readRing(streamName,
                                     partition,
                                     next,
                                     (int) Math.min(PAGE, to - next + 1))
                           .flatMap(events -> contiguous(next, events, to))
                           .flatMap(page -> readRingFrom(next + page.size(),
                                                         to,
                                                         concat(soFar, page)));
    }

    /// The page must start at `expected` and continue without a gap; it is trimmed to `to`. An empty page below `to` is a
    /// hole too: the retained log does not hold the offset.
    private static Result<List<RawEvent>> contiguous(long expected, List<RawEvent> page, long to) {
        if (page.isEmpty()) {
            return ControlLogError.IndexHole.FACTORY.apply(expected, -1L).result();
        }

        var kept = new ArrayList<RawEvent>(page.size());

        for (var event : page) {
            if (event.offset() > to) {
                break;
            }

            if (event.offset() != expected + kept.size()) {
                return ControlLogError.IndexHole.FACTORY.apply(expected + kept.size(),
                                                               event.offset())
                                                        .result();
            }

            kept.add(event);
        }

        return Result.success(List.copyOf(kept));
    }

    private static List<RawEvent> concat(List<RawEvent> first, List<RawEvent> second) {
        var all = new ArrayList<RawEvent>(first.size() + second.size());

        all.addAll(first);
        all.addAll(second);

        return List.copyOf(all);
    }

    // ---- test seams ----
    private static void noProbe() {}

    private static void noOffsetProbe(long offset) {}

    @Contract
    void admissionProbe(Runnable probe) {
        this.admissionProbe = probe;
    }

    @Contract
    void transferProbe(LongConsumer probe) {
        this.transferProbe = probe;
    }

    /// The verdict `record` would get against the admitted view NOW. Only a probe running on the queue may call it.
    ControlOutcome verdictNow(ControlRecord record) {
        return switch (decide(record, new HashMap<>())) {
            case Decision.Admit _ -> new Admitted(-1L);
            case Decision.Decided(var outcome) -> outcome;
        };
    }

    Phase phase() {
        return phase;
    }

    boolean admissionReadyNow() {
        return admissionReady();
    }

    long runAppendCount() {
        return runAppends.get();
    }

    /// Append tasks run on the queue so far: one per batch, whatever its size.
    long appendItemCount() {
        return appendItems.get();
    }

    int tailSize() {
        return tail.size();
    }
}
