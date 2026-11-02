// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.lang.Cause;


/// Which failures the retry interceptor retries (#280 R26).
///
/// The interceptor sees only the cause a method returned, never where it came from, so it cannot
/// tell a business verdict from an infrastructure failure by origin. The classification therefore
/// lives on the cause — [Cause.Transient] says "worth retrying", [Cause.Terminal] says "never" —
/// and the policy says what to do with the unclassified rest, which is what every business
/// failure is.
///
/// **Idempotency.** A retry policy re-drives the method, and a failure can mean the work was done: a timeout on an INSERT
/// or a publish says nothing about whether the row or the event is already there. A [Cause.Wrapped] reports its origin's
/// classification, so a wrapper around a timeout is transient unless its type says otherwise, and a [#TRANSIENT] policy on a
/// method that is not idempotent then re-drives a write whose outcome is unknown. Use a retry policy only on an idempotent
/// method (or one that carries its own deduplication key), and give a cause that reports an unknown outcome an explicit
/// non-transient classification, as `PublishOutcomeUnknown` does.
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
