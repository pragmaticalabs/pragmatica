// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.util.function.Supplier;

import org.pragmatica.aether.deployment.cluster.HierarchyStateWriter;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

/// Switches auto-heal off through the leader's consensus-backed toggle, the way an operator retries it (#2939).
///
/// A disable is fenced on the cluster configuration it records, and on the auto-heal record it replaces, both read from the leader's own
/// applied state. On a cluster seconds old the configuration seed is still being decided and applied, so the leader refuses the toggle with
/// [HierarchyStateWriter.Refusal#CONFLICT] (observed on CI ember-shard-5, and once in 106 fresh clusters under CPU load on cloudbb-3: "Hierarchy
/// state changed before conditional commit"). The refusal is the fence doing its job; the retry reads the settled state. Any other
/// refusal is not retried, and a refusal that outlasts the budget is reported with its cause.
final class AutoHealToggle {
    static final String DISABLED = "disabled";
    private static final long RETRY_PAUSE_MS = 50L;

    private AutoHealToggle() {}

    /// Calls `toggle` until it commits, or until `budgetMs` has passed while it keeps refusing with CONFLICT.
    ///
    /// @return [#DISABLED], or the message of the cause that ended the attempts
    @SuppressWarnings("JBCT-EX-01")
    static String disable(Supplier<Promise<Boolean>> toggle, TimeSpan attemptBound, long budgetMs) {
        var deadline = System.currentTimeMillis() + budgetMs;
        var last = toggle.get().await(attemptBound);

        while (last.fold(cause -> cause == HierarchyStateWriter.Refusal.CONFLICT, _ -> false) && System.currentTimeMillis() < deadline) {
            pause();
            last = toggle.get().await(attemptBound);
        }

        return last.fold(Cause::message, _ -> DISABLED);
    }

    private static void pause() {
        try {
            Thread.sleep(RETRY_PAUSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while retrying the auto-heal toggle", e);
        }
    }
}
