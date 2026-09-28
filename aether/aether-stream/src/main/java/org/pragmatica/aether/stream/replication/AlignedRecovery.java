// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.List;

import org.pragmatica.aether.stream.provenance.ProvenanceEntry;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// Offset-addressed, non-replicating append seam for every replica-side recovery apply (#1505): the catch-up apply
/// ({@link PartitionBackfill}), governor-failover segment replay ({@link GovernorFailoverHandler}), and catch-up
/// failover recovery ({@link FailoverRecovery}).
/// Each caught-up event is offered at its OWN source offset. The seam succeeds with `offset` exactly when the
/// replica now holds that event there: appended now, or already held with identical content. It refuses without
/// appending when offsets below `offset` are missing, or when a DIFFERENT event is held at `offset`.
///
/// A catch-up append carries no epoch of its own (#1596): its records are attributed by the SOURCE's owner-epoch
/// slice, which [#installProvenance] checks against this copy (N13) and records before the records are applied.
///
/// Production binds `StreamPartitionManager#alignedRecovery`. The live receive path
/// ({@link ReplicationReceiveHandler.RecoveredAppender}) lands through the same ordered section, so every replica
/// append shares one offset authority per partition. The tail-append seam these paths used before, the
/// `StreamPartitionRecovery` interface, was removed in #1505 F1.
public interface AlignedRecovery {
    Result<Long> appendRecovered(String streamName, int partition, long offset, byte[] payload, long timestamp);

    /// Check the source's slice against this copy's history and record it (#1596); a failure means nothing of the
    /// page may be applied.
    Result<Unit> installProvenance(String streamName,
                                   int partition,
                                   long fromOffset,
                                   long toOffset,
                                   List<ProvenanceEntry> slice);

    @FunctionalInterface
    interface Appender {
        Result<Long> appendRecovered(String streamName, int partition, long offset, byte[] payload, long timestamp);
    }

    @FunctionalInterface
    interface Installer {
        Result<Unit> installProvenance(String streamName,
                                       int partition,
                                       long fromOffset,
                                       long toOffset,
                                       List<ProvenanceEntry> slice);
    }

    static AlignedRecovery alignedRecovery(Appender appender, Installer installer) {
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
            public Result<Unit> installProvenance(String streamName,
                                                  int partition,
                                                  long fromOffset,
                                                  long toOffset,
                                                  List<ProvenanceEntry> slice) {
                return installer.installProvenance(streamName, partition, fromOffset, toOffset, slice);
            }
        };
    }

    /// A seam that installs NO provenance, for appliers whose partitions keep no log and so record none (test
    /// doubles). Never production: a catch-up through it leaves the applied records unattributed.
    static AlignedRecovery appendOnly(Appender appender) {
        return alignedRecovery(appender, (_, _, _, _, _) -> Result.unitResult());
    }
}
