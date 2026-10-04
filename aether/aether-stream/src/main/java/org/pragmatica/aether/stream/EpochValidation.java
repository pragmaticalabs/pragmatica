// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.EpochStart;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// The epoch-validated fetch (#1730 phase 2 / #1873, Kafka's KIP-320): whether a consumer that last read under
/// epoch `Ec` may keep reading a partition from `cursor` now that its owner is at epoch `E`.
///
/// An epoch begins at the offset its owner started writing at; offsets handed out in an earlier epoch above that start
/// were never kept (a restart without a WAL loses its unsealed tail, a failover keeps only what the new owner held), and
/// the new epoch assigns them again. A consumer whose cursor is past the start of the epoch that FOLLOWED its own has read
/// records of a lineage that is gone, and reading on from its cursor would skip the new records at those offsets. It is
/// told, as a typed refusal, where the new lineage began and re-reads from there.
///
/// Pure: the committed epoch and its recorded starts in, the verdict out. The single check site is
/// `StreamPartitionManager#readServing`, which serves both the forwarded and the colocated read.
public final class EpochValidation {
    private EpochValidation() {}

    /// The epoch the consumer adopts when admitted, or the typed reason it may not read.
    public static Result<Epoch> admit(String stream,
                                      int partition,
                                      Epoch ownerEpoch,
                                      List<EpochStart> starts,
                                      Epoch consumerEpoch,
                                      long cursor) {
        if (!startedFor(ownerEpoch, starts)) {
            return new StreamError.OwnerNotActivated(stream, partition).result();
        }

        if (consumerEpoch.equals(Epoch.ZERO)) {
            return Result.success(ownerEpoch);
        }

        if (consumerEpoch.isStrictlyAfter(ownerEpoch)) {
            return new StreamError.StaleEpochRead(stream, partition, consumerEpoch, ownerEpoch).result();
        }

        return judged(ownerEpoch, starts, consumerEpoch, cursor);
    }

    /// The record form of [#admit].
    public static Result<Epoch> admit(String stream,
                                      int partition,
                                      StreamPartitionOwnershipValue record,
                                      Epoch consumerEpoch,
                                      long cursor) {
        return admit(stream, partition, record.ownerEpoch(), record.epochStarts(), consumerEpoch, cursor);
    }

    /// The owner commits the start of its epoch before it serves, so a record whose newest start is another epoch belongs
    /// to an owner that is not yet activated for this one.
    private static boolean startedFor(Epoch ownerEpoch, List<EpochStart> starts) {
        return ! starts.isEmpty() && starts.getLast()
                                           .epoch()
                                           .equals(ownerEpoch);
    }

    private static Result<Epoch> judged(Epoch ownerEpoch, List<EpochStart> starts, Epoch consumerEpoch, long cursor) {
        if (consumerEpoch.compareTo(starts.getFirst().epoch()) < 0) {
            return beyondTheKeptHistory(ownerEpoch,
                                        starts.getFirst().startOffset(),
                                        cursor);
        }

        return firstFollowing(starts, consumerEpoch).filter(next -> cursor > next.startOffset())
                             .<Result<Epoch>> map(next -> new StreamError.EpochDiverged(ownerEpoch,
                                                                                        next.startOffset()).result())
                             .or(() -> Result.success(ownerEpoch));
    }

    /// A consumer older than the oldest kept start cannot be placed against the boundaries that followed its epoch. The oldest
    /// kept start's offset is the lowest offset any start after the consumer's epoch can have: the record drops the oldest
    /// starts by folding their lowest offset into the oldest one it keeps (`StreamPartitionOwnershipValue#restarted`), and a
    /// start that supersedes the earlier ones (a rebuilt ring, a re-created stream beginning at 0) leaves its own. So:
    ///   - a cursor at or below it points only at offsets no later epoch can have re-assigned: admitted;
    ///   - a cursor above it resumes AT it, at or below every offset that may have been re-assigned since the consumer's epoch,
    ///     so it may redeliver and never skips, and is told the boundary is not exact (nothing proves a record was lost).
    private static Result<Epoch> beyondTheKeptHistory(Epoch ownerEpoch, long lowestReassignable, long cursor) {
        return cursor <= lowestReassignable
               ? Result.success(ownerEpoch)
               : new StreamError.EpochDiverged(ownerEpoch, lowestReassignable, false).result();
    }

    private static Option<EpochStart> firstFollowing(List<EpochStart> starts, Epoch consumerEpoch) {
        return Option.from(starts.stream().filter(start -> start.epoch()
                                                                .compareTo(consumerEpoch) > 0).findFirst());
    }
}
