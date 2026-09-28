// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.storage.StorageInstance;
import org.pragmatica.storage.TierLevel;


/// How full this node's durable tier for sealed stream segments is (#1604): used / max of the local-disk tier,
/// `0` when there is none. Sealed segments can land only there, so a full tier stops every seal, the WAL can no
/// longer be truncated, and the WAL disk fills next. Two thresholds act on it before that:
///   - [#WARN_AT]: retention warns (once per pressure episode);
///   - [#REFUSE_AT]: an owner publish is refused with [StreamError.General#SEGMENT_TIER_FULL], a transient,
///     retryable refusal, while there is still WAL disk to hold what was already accepted. Replica appends are
///     never refused -- a replica that dropped what its owner accepted would diverge.
@FunctionalInterface
public interface SegmentTierPressure {
    double WARN_AT = 0.85;
    double REFUSE_AT = 0.95;
    SegmentTierPressure NONE = () -> 0.0;
    double utilization();

    /// The local-disk tier of `storage`, read live: two counters per call, no I/O.
    static SegmentTierPressure localDiskOf(StorageInstance storage) {
        return () -> storage.tierInfo()
                            .stream()
                            .filter(tier -> tier.level() == TierLevel.LOCAL_DISK && tier.maxBytes() > 0)
                            .mapToDouble(tier -> (double) tier.usedBytes() / tier.maxBytes())
                            .max()
                            .orElse(0.0);
    }
}
