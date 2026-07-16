// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.testkit;

import org.pragmatica.lang.Cause;


/// The single boundary where the kit surfaces a [Cause] as a JUnit test failure. Kept in one place
/// so the rest of the kit stays exception-free and composes over `Result`/`Promise`.
sealed interface TestKitFailures {
    @SuppressWarnings("JBCT-EX-01")  // sole throw site: turns a kit Cause into a test-failing AssertionError
    static <T> T raise(Cause cause) {
        throw new AssertionError(cause.message());
    }

    record unused() implements TestKitFailures {}
}
