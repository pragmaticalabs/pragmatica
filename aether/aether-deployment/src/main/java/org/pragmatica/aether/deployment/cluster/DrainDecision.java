// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.lang.Cause;


/// What a writer of the manager's pending map decided under the manager's monitor (rule M): a decision is a value, and the
/// action it names runs after the monitor is released.
sealed interface DrainDecision {
    /// The request is refused: nothing was recorded.
    record Refused(Cause cause) implements DrainDecision {
        static Refused refused(Cause cause) {
            return new Refused(cause);
        }
    }

    /// An attempt for the target exists (a raised eviction included): the caller waits on its outcome.
    record Join(DrainAttempt entry) implements DrainDecision {
        static Join join(DrainAttempt entry) {
            return new Join(entry);
        }
    }

    /// A new attempt was put: the caller that decided it requests the drain and resolves its outcome.
    record Open(DrainAttempt entry) implements DrainDecision {
        static Open open(DrainAttempt entry) {
            return new Open(entry);
        }
    }
}
