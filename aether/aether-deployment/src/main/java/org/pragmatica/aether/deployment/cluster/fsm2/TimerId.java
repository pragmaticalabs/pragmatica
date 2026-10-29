// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

/// The identity of one scheduled timer: its `kind` and the `subject` it is about (a node, an artifact pair, a
/// blueprint, or empty). The FSM names timers; the interpreter's injected scheduler fires them back as
/// `ClusterInput.Timer` inputs carrying the incarnation they were scheduled in (R-3.1.3).
public record TimerId(Kind kind, String subject) {
    public enum Kind {
        /// The 30 s reconcile tick (C1).
        TICK,
        /// The 2 s post-activation reconcile (C1).
        POST_ACTIVATION,
        /// A delayed reconcile: the 1 s arm of C11, the 5 s arm of C9, the 2 s retry of J5, a retry delay (R-3.3.7).
        RECONCILE,
        /// The re-read of an unresolved C17 / C17s (R-4.3.0).
        DEPARTING_REREAD,
        /// One step of a drain loop (C18).
        DRAIN_STEP
    }

    public static TimerId timerId(Kind kind, String subject) {
        return new TimerId(kind, subject);
    }

    public static TimerId timerId(Kind kind) {
        return new TimerId(kind, "");
    }
}
