// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.lang.Cause;


/// Which failures the retry interceptor retries (#280 R26).
///
/// The interceptor sees only the cause a method returned, never where it came from, so it cannot
/// tell a business verdict from an infrastructure failure by origin. The classification therefore
/// lives on the cause — [Cause.Transient] says "worth retrying", [Cause.Terminal] says "never" —
/// and the policy says what to do with the unclassified rest, which is what every business
/// failure is.
public enum RetryOn {
    /// Retry only causes that declare themselves transient. The default: an unclassified failure
    /// is returned after the first attempt, so a method that is not idempotent is never re-driven
    /// on its own business verdict.
    TRANSIENT,
    /// Retry everything that is not terminal — the behaviour before #280. Opt in for a method
    /// whose every failure is known to be infrastructural, or whose causes are not yet classified.
    NON_TERMINAL;

    public boolean retries(Cause cause) {
        return switch (this) {
            case TRANSIENT -> cause.isTransient();
            case NON_TERMINAL -> !cause.isTerminal();
        };
    }
}
