// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.List;

import org.pragmatica.aether.stream.provenance.ProvenanceEntry;
import org.pragmatica.lang.Result;


/// Offset-addressed, non-replicating append seam for every replica-side recovery apply (#1505): the catch-up apply
/// ({@link PartitionBackfill}), governor-failover segment replay ({@link GovernorFailoverHandler}), and catch-up
/// failover recovery ({@link FailoverRecovery}).
/// Each caught-up event is offered at its OWN source offset. The seam succeeds with `offset` exactly when the
/// replica now holds that event there: appended now, or already held with identical content. It refuses without
/// appending when offsets below `offset` are missing, or when a DIFFERENT event is held at `offset`.
///
/// A catch-up append carries no epoch of its own (#1596): its records are attributed by the SOURCE's owner-epoch
/// slice, which [#applyAttributed] checks against this copy (N13) and records before the records are applied.
///
/// Production binds `StreamPartitionManager#alignedRecovery`. The live receive path
/// ({@link ReplicationReceiveHandler.RecoveredAppender}) lands through the same ordered section, so every replica
/// append shares one offset authority per partition. The tail-append seam these paths used before, the
/// `StreamPartitionRecovery` interface, was removed in #1505 F1.
public interface AlignedRecovery {
    Result<Long> appendRecovered(String streamName, int partition, long offset, byte[] payload, long timestamp);

    /// Apply one page whose records carry the source's owner-epoch `slice` (#1596, #1638 F1): the slice is checked
    /// against this copy's history (N13) and recorded, then `apply` runs -- appending through [#appendRecovered] --
    /// and the install is settled: when `apply` fails, the entries this install recorded that no record reaches are
    /// dropped, so the retry installs cleanly and the copy is never ranked by an epoch it did not receive. A failed
    /// install means `apply` never runs. Install, apply and settle are one call so no caller can apply outside them.
    Result<Long> applyAttributed(String streamName,
                                 int partition,
                                 long fromOffset,
                                 long toOffset,
                                 List<ProvenanceEntry> slice,
                                 PageApply apply);

    /// [#applyAttributed] for records from a place that carries no provenance (a sealed segment without a slice,
    /// #1596): what lies past this copy's head is marked `UNKNOWN(d)`, which equals no other copy's range, so a
    /// comparison over it fails closed.
    Result<Long> applyUnattributed(String streamName, int partition, long toOffset, PageApply apply);

    /// The page's appends, run between the install and its settlement.
    @FunctionalInterface
    interface PageApply {
        Result<Long> apply();
    }

    @FunctionalInterface
    interface Appender {
        Result<Long> appendRecovered(String streamName, int partition, long offset, byte[] payload, long timestamp);
    }

    @FunctionalInterface
    interface AttributedApplier {
        Result<Long> applyAttributed(String streamName,
                                     int partition,
                                     long fromOffset,
                                     long toOffset,
                                     List<ProvenanceEntry> slice,
                                     PageApply apply);
    }

    @FunctionalInterface
    interface UnattributedApplier {
        Result<Long> applyUnattributed(String streamName, int partition, long toOffset, PageApply apply);
    }

    static AlignedRecovery alignedRecovery(Appender appender,
                                           AttributedApplier attributed,
                                           UnattributedApplier unattributed) {
        return new AlignedRecovery() {
            @Override
            public Result<Long> appendRecovered(String streamName,
                                                int partition,
                                                long offset,
                                                byte[] payload,
                                                long timestamp) {
                return appender.appendRecovered(streamName, partition, offset, payload, timestamp);
            }

            @Override
            public Result<Long> applyAttributed(String streamName,
                                                int partition,
                                                long fromOffset,
                                                long toOffset,
                                                List<ProvenanceEntry> slice,
                                                PageApply apply) {
                return attributed.applyAttributed(streamName, partition, fromOffset, toOffset, slice, apply);
            }

            @Override
            public Result<Long> applyUnattributed(String streamName, int partition, long toOffset, PageApply apply) {
                return unattributed.applyUnattributed(streamName, partition, toOffset, apply);
            }
        };
    }

    /// A seam that installs NO provenance, for appliers whose partitions keep no log and so record none (test
    /// doubles). Never production: a catch-up through it leaves the applied records unattributed.
    static AlignedRecovery appendOnly(Appender appender) {
        return alignedRecovery(appender, (_, _, _, _, _, apply) -> apply.apply(), (_, _, _, apply) -> apply.apply());
    }
}
