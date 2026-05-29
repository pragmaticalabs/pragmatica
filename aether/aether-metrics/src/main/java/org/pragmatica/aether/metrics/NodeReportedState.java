// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

/// Node-authoritative readiness state carried on the metrics heartbeat pong
/// (membership-architecture-v2-spec §7.5.1). Each node reports exactly one of
/// these on every pong; the node — and only the node — knows these locally, so it
/// ANDs its own conditions and reports a single value.
///
/// - [SYNCING] — QUIC-connected and gossiping, but local consensus is not yet
///   `Active` (still applying the snapshot, or re-syncing after a `ConsensusPassive`
///   edge). Not allocatable.
/// - [READY] — local `ConsensusActive` **and** local subsystems up. Allocatable for
///   deployments.
/// - [DRAINING] — the node has entered the §8 drain procedure (by leader command or
///   local quorum-loss). Not allocatable; CDM migrates work off it.
///
/// The enum name is what travels on the wire — it is stamped into the existing
/// `ClusterSyncPong.lifecycleState` field (repurposed per §7.5.3) by the node's
/// outgoing pong builder.
public enum NodeReportedState {
    SYNCING,
    READY,
    DRAINING
}
