// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import java.util.List;

import org.pragmatica.lang.Cause;


/// What the node decides to do about a pair whose goal and committed observation are known (spec R-3.4.3).
sealed interface Plan {
    /// Nothing to do. `satisfied` says the committed row already meets the goal, so the goal's generation counts as applied.
    record Settled(boolean satisfied) implements Plan {}

    /// An observed UNKNOWN (N18) or an unknown goal: warn, write nothing.
    record Unknown() implements Plan {}

    /// Start a chain. `gated` chains begin a load or an activation and wait for a committed target (N2).
    record Run(Chain.ChainKind kind, List<Chain.Step> steps, boolean gated) implements Plan {}

    /// Write FAILED now with `cause` (N5). Gated like a start: the committed-target check comes first.
    record Fail(Cause cause) implements Plan {}

    static Plan satisfied() {
        return new Settled(true);
    }

    static Plan adopted() {
        return new Settled(false);
    }

    static Plan unknown() {
        return new Unknown();
    }

    static Plan run(Chain.ChainKind kind, List<Chain.Step> steps, boolean gated) {
        return new Run(kind, steps, gated);
    }

    static Plan fail(Cause cause) {
        return new Fail(cause);
    }
}
