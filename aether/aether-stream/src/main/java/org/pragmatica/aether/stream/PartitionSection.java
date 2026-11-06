// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.function.Supplier;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.AppendLog;


/// The admission of an owner append into a partition's ordered section (#2201 A-01, spec 5.2 items 1-2). [#admit] is the guard
/// [OffHeapRingBuffer#appendOrdered(byte[], long, Supplier, org.pragmatica.lang.Functions.Fn1)] runs under the ring's append lock,
/// before an offset exists: the owner-epoch fence, then the provenance check against the log's last recorded epoch, then the log's
/// health, refused as [StreamError.PartitionFailStopped] (#2201 D-01). A stalled publish that passed the pre-section checks at an old epoch is refused here once a newer epoch has written, and
/// a publish on a log that can no longer write takes no offset. Every check is O(1) (the log caches its last epoch key and its
/// health), so a refusal holds the lock for microseconds.
///
/// Only the owner publish and the owner batch are admitted through a section; WAL replay and the replica receive keep their own
/// checks (the quarantine fence, the offset check) and enter with [#ADMIT_ALL].
record PartitionSection(String streamName,
                        int partition,
                        Supplier<Result<Unit>> fence,
                        Supplier<Result<Unit>> provenance,
                        Supplier<Option<AppendLog>> wal) {
    /// Admits every append: the section of a caller that checks for itself.
    static final PartitionSection ADMIT_ALL = partitionSection("",
                                                               - 1,
                                                               Result::unitResult,
                                                               Result::unitResult,
                                                               Option::none);

    static PartitionSection partitionSection(String streamName,
                                             int partition,
                                             Supplier<Result<Unit>> fence,
                                             Supplier<Result<Unit>> provenance,
                                             Supplier<Option<AppendLog>> wal) {
        return new PartitionSection(streamName, partition, fence, provenance, wal);
    }

    /// The guard, in order: fence, provenance, log health ([AppendLog#writable], a refusal becoming [StreamError.PartitionFailStopped]).
    /// A partition without a log has no health to check.
    Result<Unit> admit() {
        return fence.get()
                    .flatMap(_ -> provenance.get())
                    .flatMap(_ -> walWritable());
    }

    private Result<Unit> walWritable() {
        return wal.get()
                  .map(this::writableOrFailStopped)
                  .or(Result::unitResult);
    }

    private Result<Unit> writableOrFailStopped(AppendLog log) {
        return log.writable()
                  .mapError(cause -> new StreamError.PartitionFailStopped(streamName, partition, cause));
    }
}
