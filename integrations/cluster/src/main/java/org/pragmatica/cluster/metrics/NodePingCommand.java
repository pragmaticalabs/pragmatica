// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.cluster.metrics;

import org.pragmatica.serialization.Codec;

/// Membership v2 (B5a) — leader→node command piggybacked on the cluster-sync ping.
///
/// The leader stamps a per-target command onto each outbound `ClusterSyncPing`. The
/// receiving node acts on the command after the existing fencing/metrics handling. This
/// is the heartbeat-carried control channel for node lifecycle actions that must be
/// driven from the leader (operator/CTM-commanded drain), avoiding a separate RPC path.
///
/// - [`#NONE`] — no command; the steady-state default (no behavior change).
/// - [`#DRAIN`] — the leader requests this node begin a graceful drain. The receiver
///   triggers its local `DrainProcedure` (CAS-guarded, idempotent across repeated pings).
@Codec
public enum NodePingCommand {
    NONE,
    DRAIN
}
