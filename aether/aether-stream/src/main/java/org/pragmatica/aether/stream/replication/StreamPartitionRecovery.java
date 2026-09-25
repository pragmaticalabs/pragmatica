// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.pragmatica.lang.Result;


/// Tail-append recovery seam: the ring assigns the next offset. Governor-failover recovery
/// ({@link GovernorFailoverHandler}, {@link DefaultFailoverRecovery}) lands through it. The replica catch-up
/// apply does NOT (#1505): it lands at owner offsets through {@link AlignedRecovery}, because a live batch can
/// land between its request and its response. The failover paths were not converted in #1505, which reproduced
/// only the catch-up race; whether a live batch can interleave with failover recovery is unexamined.
@FunctionalInterface
public interface StreamPartitionRecovery {
    Result<Long> appendRecoveredEvent(String streamName, int partition, byte[] payload, long timestamp);
    StreamPartitionRecovery NOOP = (_, _, _, _) -> Result.success(0L);
}
