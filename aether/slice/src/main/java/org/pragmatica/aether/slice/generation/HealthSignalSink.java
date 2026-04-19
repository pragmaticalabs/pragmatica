// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.generation;

import org.pragmatica.lang.Contract;


/// Thin sink abstraction for emitters of `HealthSignal`s.
///
/// Emitters such as `CoreSwimHealthDetector` and `QuicClusterNetwork` publish
/// membership-relevant events through this sink. The leader-only
/// `HealthReconciler` consumes them via its activator. Non-leader nodes (or
/// unit tests) receive `noop()` so emissions become free calls.
///
/// See `aether/docs/specs/cluster-generation-spec.md` §8.1.
@Contract public interface HealthSignalSink {
    void emit(HealthSignal signal);

    static HealthSignalSink noop() {
        return signal -> {};
    }
}
