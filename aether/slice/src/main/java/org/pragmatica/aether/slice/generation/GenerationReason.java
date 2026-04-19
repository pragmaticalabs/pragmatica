// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.generation;

import org.pragmatica.serialization.Codec;


/// Root cause of the most recent `ClusterGenerationSnapshot` mutation.
///
/// See `aether/docs/specs/cluster-generation-spec.md` §6.
@Codec public enum GenerationReason {
    LEADER_ELECTED,
    MEMBER_ADDED,
    MEMBER_REMOVED,
    HEALTH_CHANGE,
    COMMUNITY_FORMED,
    COMMUNITY_DISSOLVED,
    PARTITION_TRANSFERRED,
    CLUSTER_SIZE_CHANGED,
    SPOKESMAN_REBALANCED,
    PERIODIC_REFRESH
}
