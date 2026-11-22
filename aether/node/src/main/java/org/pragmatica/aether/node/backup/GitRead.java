// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

/// What a git read found (#3464): the thing is there, the thing is not there, or git could not say.
///
/// The two-valued reads this replaces mapped every non-zero exit to "absent", so an unreachable remote or a
/// damaged repository read as an empty one. Here only a definite answer is [Absent]; everything else —
/// another exit status, a timeout, output that was lost or does not parse — is [Unreadable], and the caller
/// decides what an unreadable source means (a restore blocks, a write retries).
public sealed interface GitRead<T> {
    /// The thing exists, and `value` is what was read.
    record Present<T>(T value) implements GitRead<T> {}

    /// Git answered, and the answer is that the thing does not exist.
    record Absent<T>() implements GitRead<T> {}

    /// Git could not answer; `reason` names why.
    record Unreadable<T>(String reason) implements GitRead<T> {}

    static <T> GitRead<T> present(T value) {
        return new Present<>(value);
    }

    static <T> GitRead<T> absent() {
        return new Absent<>();
    }

    static <T> GitRead<T> unreadable(String reason) {
        return new Unreadable<>(reason);
    }
}
