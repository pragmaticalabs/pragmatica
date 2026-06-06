// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Cause;


/// Marker for a failure that signifies a TRANSIENT resource-capacity shortage — the operation could
/// not be admitted right now, but the condition may clear as other consumers release capacity
/// (stream-offheap-budget-spec §6 / decision #7).
///
/// A `Cause` carrying this marker (with {@link #transientCapacity()} true) tells the resource
/// provisioning + slice-loading classification path to treat the failure as
/// {@link SliceLoadingFailure.Intermittent} (retry with backoff, then surface `DeploymentFailed`
/// after MAX_RETRIES) rather than {@link SliceLoadingFailure.Fatal} (permanent, no retry). This keeps
/// the marker in `slice-api` so `aether-stream`'s `StreamError` can carry it without inverting the
/// `slice-api → aether-stream` dependency direction, and `resource-api`'s `SpiResourceProvider` /
/// `SliceLoadingFailure.classify` can read it without depending on `aether-stream`.
public interface ResourceCapacityExhausted extends Cause {
    /// Whether this specific cause represents a transient capacity shortage. Defaults to true; a type
    /// that conditionally carries the marker (e.g. a shared enum where only one constant is a capacity
    /// failure) overrides this to discriminate per-instance.
    default boolean transientCapacity() {
        return true;
    }

    /// Whether `cause` is a transient resource-capacity exhaustion (marker present AND predicate true).
    static boolean isTransientCapacity(Cause cause) {
        return cause instanceof ResourceCapacityExhausted exhausted && exhausted.transientCapacity();
    }
}
