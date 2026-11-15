// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.consensus.rabia;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.StateMachine.Batch.Id;
import org.pragmatica.consensus.Command;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// #2011: remembers which submissions (batch id, correlation ids) a voter has already committed, so a `NewBatch`
/// (or a proposal's batch) that arrives AFTER its slot's Decision cannot re-enter `pendingBatches`, be proposed
/// again and be committed a second time.
///
/// Batch ids are content hashes (#958), so the id alone cannot tell a late delivery of an old submission from a
/// legitimate later submission of identical commands. Correlation ids can: each `createBatch` call mints a fresh
/// one. An incoming batch is therefore stale exactly when ALL its correlation ids were committed under that batch
/// id; a batch carrying some new correlation id is admitted with the committed ones stripped.
///
/// Bounded by the applied log, not by the ids it holds (#3284): the ledger remembers the correlation ids of its last
/// `capacity` records, and each correlation id is recognised for exactly the `capacity` records that follow its own,
/// whether or not its batch id is recorded again. A batch id that repeats (identical commands, e.g. a periodic barrier)
/// therefore holds at most the ids recorded under it in the window, never every id it was ever committed with, and
/// the total held is at most the correlation ids carried by `capacity` records. The window counts records, never wall
/// clock time, so two ledgers fed the same records hold the same entries.
///
/// A delivery delayed by more than `capacity` records is not recognised. NewBatch is not offline-buffered by the
/// transport, so its delay is bounded by the in-flight retry budget (about 5 s, `QuicTransportTuning` consensus retry,
/// 200 x 25 ms), which this window covers up to about 13k commits per second [unverified: under load; sized from the
/// retry budget, not measured].
///
/// Memory: about 457 bytes per correlation id (measured for the earlier per-batch-id layout; the record window and the
/// per-id sequence tag add a little), so about 30 MB per node at the default 65,536 single-id records once that many
/// have been committed. The ledger is NOT cleared by stop or reset (those clear the pending queue); that is harmless for
/// correctness because correlation ids are unique, but the memory stays held for the life of the engine.
///
/// #2045: the ledger is a function of the applied log. A voter records only the correlation ids a Decision carries,
/// never the ids it merged locally, so every replica that applied the same slots holds the same entries and refuses the
/// same late batches. A voter that skipped slots through a snapshot restore lacks their entries; the voters that have
/// them answer its proposal of such a batch with `BatchCommitted`, which it records here; such a record also occupies
/// a place in that voter's window, which already differs from the others by the skipped slots.
final class CommittedBatchLedger {
    static final int DEFAULT_CAPACITY = 65_536;

    private final int capacity;
    private final Map<Id, Map<CorrelationId, Long>> committed = new HashMap<>();
    private final ArrayDeque<Recorded> window = new ArrayDeque<>();
    private long sequence;

    /// One record of the window: the correlation ids recorded under `batchId` at position `sequence`.
    private record Recorded(long sequence, Id batchId, List<CorrelationId> correlationIds) {}

    CommittedBatchLedger(int capacity) {
        this.capacity = capacity;
    }

    synchronized Unit record(Id batchId, Iterable<CorrelationId> correlationIds) {
        var ids = new ArrayList<CorrelationId>();
        var position = ++sequence;

        correlationIds.forEach(ids::add);
        var entry = committed.computeIfAbsent(batchId, _ -> new HashMap<>());

        ids.forEach(id -> entry.put(id, position));
        window.addLast(new Recorded(position, batchId, List.copyOf(ids)));
        trimToCapacity();

        return Unit.unit();
    }

    /// The part of `incoming` not yet committed: the batch itself when nothing of it was committed, the batch
    /// without its committed correlation ids when only some were, empty when all were.
    synchronized <C extends Command> Option<Batch<C>> unseen(Batch<C> incoming) {
        return Option.option(committed.get(incoming.id())).fold(() -> some(incoming),
                                                                known -> withoutCommitted(incoming, known.keySet()));
    }

    /// The correlation ids of `incoming` already committed under its batch id; empty when none are.
    synchronized Option<List<CorrelationId>> committedOf(Batch<?> incoming) {
        return Option.option(committed.get(incoming.id()))
                     .map(known -> incoming.correlationIds()
                                           .stream()
                                           .filter(known::containsKey)
                                           .toList())
                     .filter(ids -> !ids.isEmpty());
    }

    private static <C extends Command> Option<Batch<C>> withoutCommitted(Batch<C> incoming, Set<CorrelationId> known) {
        var remaining = incoming.correlationIds().stream().filter(id -> !known.contains(id)).toList();

        return remaining.isEmpty()
               ? none()
               : some(new Batch<>(incoming.id(), remaining, incoming.timestamp(), incoming.commands()));
    }

    synchronized int sizeForTesting() {
        return committed.size();
    }

    /// The correlation ids held across all batch ids: the quantity #3284 bounds.
    synchronized int correlationCountForTesting() {
        return committed.values()
                        .stream()
                        .mapToInt(Map::size)
                        .sum();
    }

    /// A deep copy of the held entries with their record positions, for comparing two ledgers.
    synchronized Map<Id, Map<CorrelationId, Long>> stateForTesting() {
        var copy = new HashMap<Id, Map<CorrelationId, Long>>();

        committed.forEach((id, entry) -> copy.put(id, Map.copyOf(entry)));
        return copy;
    }

    private void trimToCapacity() {
        while (window.size() > capacity) {
            expire(window.removeFirst());
        }
    }

    /// Forgets the ids of the oldest record, except where the same id was recorded again later under that batch id.
    private void expire(Recorded oldest) {
        Option.option(committed.get(oldest.batchId()))
              .onPresent(entry -> forget(oldest, entry));
    }

    private void forget(Recorded oldest, Map<CorrelationId, Long> entry) {
        oldest.correlationIds()
              .forEach(id -> entry.remove(id, oldest.sequence()));
        if (entry.isEmpty()) {
            committed.remove(oldest.batchId());
        }
    }
}
