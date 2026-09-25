// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.pragmatica.lang.Result;


/// Offset-addressed, non-replicating append seam for the replica catch-up apply ({@link PartitionBackfill}, #1505).
/// Each caught-up event is offered at its OWN source offset. The seam succeeds with `offset` exactly when the
/// replica now holds that event there: appended now, or already held with identical content. It refuses without
/// appending when offsets below `offset` are missing, or when a DIFFERENT event is held at `offset`.
///
/// Production binds `StreamPartitionManager::appendRecovered`'s offset-addressed overload, the same ordered
/// section the live receive path ({@link ReplicationReceiveHandler.RecoveredAppender}) lands through, so the
/// two share one offset authority per partition. The tail-append {@link StreamPartitionRecovery} remains for
/// governor-failover recovery only.
@FunctionalInterface
public interface AlignedRecovery {
    Result<Long> appendRecovered(String streamName, int partition, long offset, byte[] payload, long timestamp);
}
