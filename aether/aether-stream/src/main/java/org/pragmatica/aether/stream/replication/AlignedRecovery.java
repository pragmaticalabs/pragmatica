// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.pragmatica.lang.Result;


/// Offset-addressed, non-replicating append seam for every replica-side recovery apply (#1505): the catch-up apply
/// ({@link PartitionBackfill}), governor-failover segment replay ({@link GovernorFailoverHandler}), and catch-up
/// failover recovery ({@link FailoverRecovery}).
/// Each caught-up event is offered at its OWN source offset. The seam succeeds with `offset` exactly when the
/// replica now holds that event there: appended now, or already held with identical content. It refuses without
/// appending when offsets below `offset` are missing, or when a DIFFERENT event is held at `offset`.
///
/// Production binds `StreamPartitionManager::appendRecovered`'s offset-addressed overload. The live receive path
/// ({@link ReplicationReceiveHandler.RecoveredAppender}) lands through the same ordered section, so every replica
/// append shares one offset authority per partition. The tail-append seam these paths used before, the
/// `StreamPartitionRecovery` interface, was removed in #1505 F1.
@FunctionalInterface
public interface AlignedRecovery {
    Result<Long> appendRecovered(String streamName, int partition, long offset, byte[] payload, long timestamp);
}
