// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
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
/// - [HELD] — a DRAINING node whose departure push has not resolved (not started counts); the pong
///   carries a `heldReport`. Every consumer that matches DRAINING reads [#isDraining] so it matches
///   HELD as well. Nothing reports HELD yet: the wire value and its consumers land ahead of the
///   reporter. An rc5 node that has not learned the value reads HELD as [SYNCING] (see [#fromWire]):
///   a wire change inside the rc, and a mixed cluster is unsupported.
///
/// The enum name is what travels on the wire — it is stamped into the existing
/// `ClusterSyncPong.lifecycleState` field (repurposed per §7.5.3) by the node's
/// outgoing pong builder.
public enum NodeReportedState {
    SYNCING,
    READY,
    DRAINING,
    HELD;
    /// `true` for [DRAINING] and [HELD]: the node is winding down and is not allocatable. Every consumer
    /// that matched DRAINING reads this instead, so a HELD node is treated as draining.
    public boolean isDraining() {
        return this == DRAINING || this == HELD;
    }
    /// Parse the on-wire lifecycle-state name (as carried on `ClusterSyncPong.lifecycleState` and
    /// `ClusterSyncPing.readinessView` values) back into the enum. Unknown / empty / pre-migration
    /// values fold to [SYNCING] (a node whose state we cannot read is treated as not-yet-ready).
    /// Single source of truth shared by `ClusterSyncPongSignalFan` (pong ingest) and
    /// `ClusterSyncCollector` (follower readiness-view cache).
    public static NodeReportedState fromWire(String wire) {
        return switch (wire) {
            case "READY" -> READY;
            case "DRAINING" -> DRAINING;
            case "HELD" -> HELD;
            default -> SYNCING;
        };
    }
}
