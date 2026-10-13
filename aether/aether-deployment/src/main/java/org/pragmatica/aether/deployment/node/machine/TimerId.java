// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import org.pragmatica.aether.artifact.Artifact;


/// The identity of one timer the node FSM scheduled (spec R-3.1.3). `incarnation` is the FSM incarnation of the Active
/// state that scheduled it, so a timer outliving a quorum cycle is dropped on arrival. `token` ties the timer to the
/// object it watches: a write correlation, a chain id or a row version.
public record TimerId(TimerKind kind, Artifact artifact, long incarnation, long token) {
    /// What a timer watches.
    public enum TimerKind {
        /// One attempt of an observed write: 30 s to see the local apply (spec R-3.4.9 (3)).
        WRITE_CONFIRM,
        /// The overall bound of an activation chain (B-169).
        CHAIN_BOUND,
        /// The 90 s check after a committed ACTIVATING row (N7, N9).
        ACTIVATING_CHECK,
        /// The first-refusal escalation of a parked start (B-158).
        PARKED_ESCALATION,
        /// The tombstone grace after which the node Removes its own UNLOADED row (R-1.6.3).
        TOMBSTONE_GRACE,
        /// The 2 s spacing between FAILED-write cycles (N19).
        FAILED_RETRY
    }

    public static TimerId timerId(TimerKind kind, Artifact artifact, long incarnation, long token) {
        return new TimerId(kind, artifact, incarnation, token);
    }
}
