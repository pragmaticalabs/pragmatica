// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.consensus.rabia;

import java.util.HashSet;
import java.util.LinkedHashMap;
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
/// Bounded: the `capacity` most recently committed batch ids are kept, oldest evicted first. A delivery delayed
/// by more than `capacity` commits is not recognised. NewBatch is not offline-buffered by the transport, so its
/// delay is bounded by the in-flight retry budget (about 5 s, `QuicTransportTuning` consensus retry), which this window
/// covers up to about 13k commits per second [unverified: under load; sized from the retry budget, not measured].
///
/// Memory: about 457 bytes per entry (one correlation id each, measured), so about 30 MB per node at the default 65,536
/// entries once a node has committed that many batches; more when entries carry merged correlation ids. The ledger is
/// NOT cleared by stop or reset (those clear the pending queue); that is harmless for correctness because correlation
/// ids are unique, but the memory stays held for the life of the engine.
///
/// #2045: the ledger is a function of the applied log. A voter records only the correlation ids a Decision carries,
/// never the ids it merged locally, so every replica that applied the same slots holds the same entries and refuses the
/// same late batches. A voter that skipped slots through a snapshot restore lacks their entries; the voters that have
/// them answer its proposal of such a batch with `BatchCommitted`, which it records here.
final class CommittedBatchLedger {
    static final int DEFAULT_CAPACITY = 65_536;

    private final int capacity;
    private final Map<Id, Set<CorrelationId>> committed = new LinkedHashMap<>();

    CommittedBatchLedger(int capacity) {
        this.capacity = capacity;
    }

    synchronized Unit record(Id batchId, Iterable<CorrelationId> correlationIds) {
        var merged = Option.option(committed.remove(batchId)).or(HashSet::new);

        correlationIds.forEach(merged::add);
        committed.put(batchId, merged);
        trimToCapacity();

        return Unit.unit();
    }

    /// The part of `incoming` not yet committed: the batch itself when nothing of it was committed, the batch
    /// without its committed correlation ids when only some were, empty when all were.
    synchronized <C extends Command> Option<Batch<C>> unseen(Batch<C> incoming) {
        return Option.option(committed.get(incoming.id())).fold(() -> some(incoming),
                                                                known -> withoutCommitted(incoming, known));
    }

    /// The correlation ids of `incoming` already committed under its batch id; empty when none are.
    synchronized Option<List<CorrelationId>> committedOf(Batch<?> incoming) {
        return Option.option(committed.get(incoming.id()))
                     .map(known -> incoming.correlationIds()
                                           .stream()
                                           .filter(known::contains)
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

    private void trimToCapacity() {
        var excess = committed.size() - capacity;

        if (excess > 0) {
            committed.keySet().stream().limit(excess).toList().forEach(committed::remove);
        }
    }
}
