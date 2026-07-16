/*
 *  Copyright (c) 2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.pragmatica.lang.utils;

import org.pragmatica.lang.Contract;

import java.util.Objects;

/// A cell holding one hot-swappable strategy lambda behind a single `volatile` field.
///
/// The behaviour itself — not a configuration snapshot — is the swapped value: callers read the
/// current strategy on the hot path and invoke it, while a control plane replaces the whole lambda
/// atomically via [#swap]. There is exactly one live strategy at any moment; a swap publishes the
/// next one wholesale (set-one-lambda-at-a-time), never a partially-applied blend.
///
/// **Hot-path cost contract:** [#strategy] is one `volatile` read and nothing else; the caller then
/// performs one invoke on the returned lambda. No lock, no map lookup, no branch. When the cell holds
/// an identity/no-op strategy the invoke is a trivial passthrough with zero allocation, so an
/// always-present cell that is currently "off" costs a predicted volatile load plus a single call.
///
/// **Composition happens at swap time, not per call.** Any layering of behaviours (decorators,
/// facet combinations) is pre-composed by the control plane into ONE lambda before [#swap]; the hot
/// path never composes. This keeps per-invocation cost constant regardless of how many behaviours are
/// active, and lets a swap flip behaviour cluster-wide without touching call sites.
///
/// The `volatile` field gives a swap happens-before visibility to any thread that subsequently reads
/// [#strategy]; the cell holds no other state and is safe to share across threads.
public final class AtomicStrategy<F> {
    private volatile F strategy;

    private AtomicStrategy(F initial) {
        this.strategy = initial;
    }

    /// Create a cell seeded with an initial strategy.
    ///
    /// @param initial the strategy served until the first [#swap]; must not be null
    ///
    /// @return a cell holding the given strategy
    public static <F> AtomicStrategy<F> atomicStrategy(F initial) {
        Objects.requireNonNull(initial, "initial strategy must not be null");

        return new AtomicStrategy<>(initial);
    }

    /// Read the current strategy. Hot-path accessor: one `volatile` read.
    ///
    /// @return the strategy in effect at the moment of the call
    public F strategy() {
        return strategy;
    }

    /// Replace the current strategy wholesale. The next [#strategy] read on any thread observes it.
    ///
    /// @param next the strategy to install; must not be null
    @Contract
    public void swap(F next) {
        this.strategy = Objects.requireNonNull(next, "strategy must not be null");
    }
}
