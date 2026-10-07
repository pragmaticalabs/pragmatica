// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.consensus.rabia;

import java.util.HashSet;
import java.util.LinkedHashMap;
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
/// by more than `capacity` commits is not recognised.
final class CommittedBatchLedger {
    static final int DEFAULT_CAPACITY = 16_384;

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
