// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;

/// Feature flag controlling NTT (NodeTopologyTracker) instrumentation during the
/// membership v2 E1-E4 migration ramp (spec §14).
///
/// The flag exists so observation-only NTT code can land in rc1 without behavior
/// change, then be ramped to [`#UNIVERSAL`] once divergence-logger telemetry confirms
/// NTT matches the existing FSM path. Removed entirely at post-cutover cleanup.
public enum NttObservationFlag {
    /// NTT inert — no observation, no reaction. Production default during initial E1
    /// ramp; preserves pre-v2 behaviour bit-for-bit so the new code path is dormant
    /// until explicitly enabled per cluster.
    OFF,

    /// Full NTT instrumentation active on every node; leader-only reaction. Every node
    /// builds the per-peer NTT map and emits divergence telemetry, but only the
    /// current leader consumes `TopologyUnhealthy` to drive reconciliation actions.
    UNIVERSAL
}
