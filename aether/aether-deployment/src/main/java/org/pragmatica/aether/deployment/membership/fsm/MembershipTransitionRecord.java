// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.pragmatica.consensus.NodeId;

/// One per-member [MembershipFsm] state-transition observation (cluster-topology-overhaul
/// spec, Wave 1 Enrichment A). Emitted from the single dispatch chokepoint
/// (`MemberTracking#dispatch`) whenever an event produces an actual state change
/// (`fromState != toState`), through the listener installed via
/// [MembershipFsm#onTransition].
///
/// Pure diagnostic data: consumers (the per-node transition journal in `aether-node`)
/// only record it; emitting has NO control-flow effect. The listener defaults to a no-op.
///
/// @param nodeId      the member whose FSM transitioned
/// @param fromState   state name before the dispatch (e.g. `Observed`, `Member`, `Suspect`)
/// @param toState     state name after the dispatch (e.g. `Member`, `Dead`)
/// @param cause       the dispatched [MembershipEvent]'s simple type name (e.g. `SwimFaulty`,
///                    `UpHysteresisMet`, `Stopped`)
/// @param incarnation the member's SWIM-incarnation high-water mark AFTER the dispatch
/// @param role        the member's last-known descriptor role label (blank when unknown)
/// @param wallClockMs wall-clock timestamp (epoch ms) of the transition
public record MembershipTransitionRecord(NodeId nodeId,
                                         String fromState,
                                         String toState,
                                         String cause,
                                         long incarnation,
                                         String role,
                                         long wallClockMs) {}
