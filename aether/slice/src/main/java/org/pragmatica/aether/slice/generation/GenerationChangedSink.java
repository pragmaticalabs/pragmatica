// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.generation;

import org.pragmatica.lang.Contract;


/// Receiver for `GenerationChangedNotice` events emitted by `HealthReconciler`.
///
/// `noop()` is the default for tests and code paths that don't yet wire the event bus.
///
/// See `aether/docs/specs/cluster-generation-spec.md` §14.4.
public interface GenerationChangedSink {
    @Contract void emit(GenerationChangedNotice notice);

    static GenerationChangedSink noop() {
        return _ -> {};
    }
}
