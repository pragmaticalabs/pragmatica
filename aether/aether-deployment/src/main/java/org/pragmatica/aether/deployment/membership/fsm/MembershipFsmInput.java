// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;


/// Cluster-membership FSM input alphabet (cluster-convergence-reconciler-spec §6).
///
/// Two-branch sealed root:
///   - `MembershipFsmEvent` — observed-fact inputs from SWIM, slot machinery, transport
///     reachability aggregator, drain coordinator, and the join-deadline scheduler.
///   - `LifecycleCommand` — externally-directed inputs from operators, the reconciler,
///     CTM, and the consensus drain coordinator. These carry a `Cause` justification that
///     is published to the `audit.lifecycle.commands` stream.
///
/// Both branches expose `peer()` and `at()` so the reducer can dispatch per-peer and the
/// wiring layer can stamp `NodeLifecycleValue.transitionedAt` from a single source.
public sealed interface MembershipFsmInput permits MembershipFsmEvent, LifecycleCommand {
    NodeId peer();
    HlcTimestamp at();
}
