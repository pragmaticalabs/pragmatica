// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.lang.Cause;


/// A ring refused a step that would break one of its own invariants (a tail that does not rise, a seed on a seeded value, a
/// cut outside `[tail - 1, head]`, an append below the tail, a read value bound to another ring, a capacity below 1).
/// A defect or a misuse, never a close race: it is a typed cause so that `OffHeapRingBuffer#guardedAccess` does not map it to
/// `BUFFER_CLOSED`, and it sits outside the `StreamError` hierarchy so the exhaustive `ReadRefusal` switch is untouched.
/// The ring logs it at ERROR and counts it where it is raised (`OffHeapRingBuffer#stateViolationCount`).
public record RingStateViolated(String detail) implements Cause {
    public static RingStateViolated ringStateViolated(String detail) {
        return new RingStateViolated(detail);
    }

    @Override
    public String message() {
        return "Ring state violated: " + detail;
    }
}
