// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.worker.metrics;

import org.pragmatica.serialization.Codec;


/// Cumulative offered-load counts for one origin (external HTTP ingress or internal slice-to-slice) of one
/// artifact or route, counted on one node and never reset (the consumer takes per-window deltas, as it does for
/// the execution counters).
///
/// `arrivals` is counted BEFORE any admission or throttle check; every arrival ends as exactly one of
/// `rejected` (refused by admission, rate limit or a missing slice), `completed` or `failed` (both only after
/// admission, so `accepted = completed + failed`). An arrival that has not settled yet is still in flight:
/// `arrivals - accepted - rejected`.
@Codec
public record ArrivalCounts(long arrivals, long accepted, long rejected, long completed, long failed) {
    public static final ArrivalCounts ZERO = new ArrivalCounts(0, 0, 0, 0, 0);

    public static ArrivalCounts arrivalCounts(long arrivals, long accepted, long rejected, long completed, long failed) {
        return new ArrivalCounts(arrivals, accepted, rejected, completed, failed);
    }

    public ArrivalCounts plus(ArrivalCounts other) {
        return new ArrivalCounts(arrivals + other.arrivals,
                                 accepted + other.accepted,
                                 rejected + other.rejected,
                                 completed + other.completed,
                                 failed + other.failed);
    }
}
