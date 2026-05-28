// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;


/// Coarse classification of the lifecycle decision the FSM committed (or evaluated as a
/// no-op). Mirrors the NTT side of the divergence-logger comparison; intentionally coarse
/// because Stage 5 / E1 is observation-only and E3 chaos-suite analysis groups decisions
/// by category, not by every reducer cell.
///
/// - [`#PROVISION`]   — FSM committed a JOINING / ON_DUTY promotion (a new peer was admitted
///                       to the active set).
/// - [`#DECOMMISSION`] — FSM committed a STOPPED transition (operator-forced, drain-failed,
///                       SWIM-driven, or transport-driven terminal write).
/// - [`#DRAIN`]       — FSM committed a DRAINING transition (graceful or operator drain).
/// - [`#NO_ACTION`]   — Reducer returned no writes (legal no-op cell or reducer-rejected).
/// - [`#OTHER`]       — Any other decision (re-derived external KV write, etc.).
public enum FsmDecisionType {
    PROVISION,
    DECOMMISSION,
    DRAIN,
    NO_ACTION,
    OTHER
}
