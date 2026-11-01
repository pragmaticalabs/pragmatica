// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

/// The late-bound seams of [StreamPartitionManager] (#2201 A-14, spec 5.1 target, bullet 4). Each defaults to a value that
/// lets a unit, Forge or legacy manager run without the cluster wiring; production binds every one of them in
/// `StreamAssembly` except [#EPOCH_STARTS]. [StreamPartitionManager#unboundSeams] lists the ones still at their default.
public enum StreamSeam {
    OWNER_EPOCH_SOURCE,
    PARTITION_FLAGS,
    OPERATOR_WARNINGS,
    OWNERSHIP_RECORDS,
    TIERED_READER,
    EPOCH_STARTS,
    REPAIR_REPORT_BOUND,
    EXHAUSTION_SINK,
    PLACEMENT_ROLE_SUPPLIER,
    CLUSTER_SIZE_SUPPLIER,
    COMMITTED_CONFIG_SOURCE,
    CATCHUP_SOURCE,
    OWNER_RELEASE_GUARD,
    OWNER_WRITE_ADMISSION,
    OWNER_SERVE_GATE,
    OWNER_BLOCK_SOURCE,
    STREAM_FOOTPRINT,
    SEGMENT_TIER_PRESSURE,
    RESHUFFLE_CONCURRENCY
}
