// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.pragmatica.lang.Result;


@FunctionalInterface
public interface StreamPartitionRecovery {
    Result<Long> appendRecoveredEvent(String streamName, int partition, byte[] payload, long timestamp);
    StreamPartitionRecovery NOOP = (_, _, _, _) -> Result.success(0L);
}
