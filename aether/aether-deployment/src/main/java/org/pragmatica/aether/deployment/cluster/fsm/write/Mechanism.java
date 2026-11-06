// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm.write;

/// The ways a leader-issued deployment-FSM write reaches its committed end state (#2584). Every [WriteSite] declares
/// the mechanisms it relies on, and each declared (site, mechanism) pair has a convergence scenario.
public enum Mechanism {
    HANDLER_REISSUE,
    PASS_REDERIVE,
    DURABLE_REBUILD,
    APPLIED_THEN_FAILED,
    INTENT_LOST_THEN_LEADER_CHANGE,
    SUPERSEDED_BY_REJOIN,
    REFUSED_DROPS_INTENT,
    NOT_LEADER
}
