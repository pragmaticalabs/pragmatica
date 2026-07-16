// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import java.util.function.IntSupplier;

import org.pragmatica.lang.io.TimeSpan;


/// Configuration for the periodic `PeerConnectivityObservation` emission task.
/// See `aether/docs/specs/reachability-aggregator-spec.md` "Periodic Observation
/// Mode" subsection.
///
/// Every node, every `period`, emits one `PeerConnectivityObservation` per
/// topology peer — `CONNECTED` for currently QUIC-reachable peers, `DISCONNECTED`
/// otherwise. The aggregator applies a TTL of `ttl` (3× period) before evicting
/// stale per-observer entries. `capFloor` provides the lower bound used by the
/// observation-buffer cap supplier in `AetherNode` (final cap is
/// `max(capFloor, topologySize × 4)`).
///
/// @param period   emission cadence (default 5s)
/// @param ttl      aggregator TTL (default 15s, 3× period)
/// @param capFloor lower bound for the observation-buffer cap (default 64)
public record PeriodicObservationConfig(TimeSpan period, TimeSpan ttl, IntSupplier capFloor) {
    private static final int DEFAULT_CAP_FLOOR = 64;

    public static PeriodicObservationConfig defaultConfig() {
        return new PeriodicObservationConfig(TimeSpan.timeSpan(5).seconds(),
                                             TimeSpan.timeSpan(15).seconds(),
                                             () -> DEFAULT_CAP_FLOOR);
    }
}
