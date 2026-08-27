// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import org.pragmatica.aether.resource.Mutator;

/// The command hierarchy the entity tests mutate `Integer` state with — a sealed root whose variants
/// are records, which is the shape every durable use of [Mutator] requires.
///
/// It is SEALED on purpose, and not merely as a fixture detail: a lambda cannot implement a sealed
/// interface, so the tests cannot accidentally exercise a path with an unserializable transition and
/// report it as working. That is the property `C extends Mutator<S>` exists to enforce at every call
/// site, and a fixture that took a lambda would silently opt out of it.
sealed interface IntOp extends Mutator<Integer> {
    record Add(int delta) implements IntOp {
        @Override
        public Integer apply(Integer state) {
            return state + delta;
        }
    }

    record Multiply(int factor) implements IntOp {
        @Override
        public Integer apply(Integer state) {
            return state * factor;
        }
    }

    record Identity() implements IntOp {
        @Override
        public Integer apply(Integer state) {
            return state;
        }
    }

    /// A command whose `apply` THROWS — the shape an author's buggy mutator has. It exists so the
    /// consume-on-failure path can be reached where it actually fires (inside `apply`, on the per-key
    /// serialization tail) rather than by injecting a failure at the codec or the append, neither of which
    /// is where a bad command fails.
    record Exploding() implements IntOp {
        @Override
        public Integer apply(Integer state) {
            throw new IllegalStateException("command deliberately fails for state " + state);
        }
    }
}
