// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.cluster.metrics;

import java.util.List;

import org.pragmatica.lang.Contract;


/// Outbound buffer for follower-side peer observations.
///
/// Follower detectors (`CoreSwimHealthDetector`, `QuicClusterNetwork`) push
/// observations here; the holder (typically `ClusterSyncScheduler`) drains the
/// buffer into the next outbound `ClusterSyncPong` so the leader can fold them
/// through `PeerObservationReducer`.
///
/// Implementations must be safe for concurrent push + drain.
///
/// See `aether/docs/specs/clustersync-refactor-spec.md` commit 2.
public interface PeerObservationBuffer {
    /// No-op buffer for tests and bootstrap paths where no scheduler exists yet.
    PeerObservationBuffer NOOP = new PeerObservationBuffer() {
        @Contract
        @Override
        public void pushHealth(PeerHealthObservation observation) {}

        @Contract
        @Override
        public void pushConnectivity(PeerConnectivityObservation observation) {}

        @Override
        public List<PeerHealthObservation> drainHealth() {
            return List.of();
        }

        @Override
        public List<PeerConnectivityObservation> drainConnectivity() {
            return List.of();
        }
    };

    @Contract
    void pushHealth(PeerHealthObservation observation);

    @Contract
    void pushConnectivity(PeerConnectivityObservation observation);

    /// Atomically swap-out and return all currently buffered health observations.
    /// Observations recorded after this call land in the next buffer generation.
    List<PeerHealthObservation> drainHealth();
    /// Atomically swap-out and return all currently buffered connectivity observations.
    /// Observations recorded after this call land in the next buffer generation.
    List<PeerConnectivityObservation> drainConnectivity();
}
