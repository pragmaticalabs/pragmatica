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
        return resumeBound(starts, consumerEpoch).filter(bound -> cursor > bound)
                          .<Result<Epoch>> map(bound -> new StreamError.EpochDiverged(ownerEpoch,
                                                                                      bound,
                                                                                      provenLossFrom(starts,
                                                                                                     consumerEpoch,
                                                                                                     cursor)).result())
                          .or(() -> Result.success(ownerEpoch));
    }

    /// The offset at or below every offset an epoch after the consumer's may have re-assigned: the start that followed the
    /// consumer's epoch, or, for a consumer older than every kept start, the oldest kept start, whose offset the record keeps as
    /// the lowest of those it folded (`StreamPartitionOwnershipValue#capped`). A cursor at or below it is admitted; above it the
    /// consumer re-reads from it, so it may redeliver and never skips.
    private static Option<Long> resumeBound(List<EpochStart> starts, Epoch consumerEpoch) {
        return consumerEpoch.compareTo(starts.getFirst().epoch()) < 0
               ? Option.some(starts.getFirst().startOffset())
               : firstFollowing(starts, consumerEpoch).map(EpochStart::startOffset);
    }

    /// Where the loss is PROVEN to begin, or -1: the first start that is exactly known to be of an epoch after the consumer's
    /// and lies below its cursor. The new owner began assigning there, so the offsets the consumer read from it on are not in the
    /// new lineage. Judged on the start that supersedes the consumer's epoch, not on the oldest one: a folded oldest start proves
    /// nothing for a consumer that began after the epoch it folded from, while a later exact start still does. Offsets increase
    /// along the list, so the first match is the lowest.
    private static long provenLossFrom(List<EpochStart> starts, Epoch consumerEpoch, long cursor) {
        return starts.stream()
                     .filter(start -> start.epoch()
                                           .compareTo(consumerEpoch) > 0
                                      && start.exactFor(consumerEpoch)
                                      && start.startOffset() < cursor)
                     .findFirst()
                     .map(EpochStart::startOffset)
                     .orElse(StreamError.EpochDiverged.NO_PROVEN_LOSS);
    }

    private static Option<EpochStart> firstFollowing(List<EpochStart> starts, Epoch consumerEpoch) {
        return Option.from(starts.stream().filter(start -> start.epoch()
                                                                .compareTo(consumerEpoch) > 0).findFirst());
    }
}
