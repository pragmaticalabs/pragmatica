// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.generation.Epoch;


/// Ambient source of the writer's CURRENT owner epoch for a `(stream, partition)`, used to stamp each
/// stream append with a fencing token (#345 item 1d-ii). The owner stamps every local data-plane
/// append (`publishLocal`) with its believed-current ownership epoch; every replica it replicates to
/// then enforces monotonicity of that token against its per-partition high-water
/// ([org.pragmatica.aether.slice.fence.OwnershipEpochHighWater]), so a deposed owner's append is
/// rejected everywhere with a [StreamError.StaleEpochAppend].
///
/// Unlike the DHT's `OwnerEpochSource` (#345 item 1c) — which lives in an Apache-2.0 module that may
/// NOT depend on the BSL-1.1 `aether/slice` module and so carries the epoch as two primitive `long`s —
/// `aether-stream` already depends on `aether/slice`, so this source returns the [Epoch] directly. No
/// dependency inversion is needed.
///
/// The aether-level wiring binds the production source to the committed
/// `StreamPartitionOwnershipValue.ownerEpoch` for the node's `(stream, partition)` ownership arc
/// (#345 item 1d-i record); non-fenced / legacy / unowned callers use [#zero] (the unfenced floor,
/// [Epoch#ZERO]). A floor-stamped append is never rejected by a fresh high-water and the floor never
/// advances it — so the fence is inert until real ownership epochs are wired (1d-iii driver).
@FunctionalInterface
public interface StreamOwnerEpochSource {
    /// This node's current ownership [Epoch] for `(stream, partition)` — the token stamped onto an
    /// append. The unfenced floor ([Epoch#ZERO]) for an arc this node does not own / has no committed
    /// ownership record for.
    Epoch currentOwnerEpoch(String stream, int partition);

    /// The unfenced floor source ([Epoch#ZERO] → `0:0`) for non-cluster stream paths, legacy callers,
    /// and tests. Every append stamped at the floor is never rejected and never advances a high-water.
    static StreamOwnerEpochSource zero() {
        return (_, _) -> Epoch.ZERO;
    }
}
