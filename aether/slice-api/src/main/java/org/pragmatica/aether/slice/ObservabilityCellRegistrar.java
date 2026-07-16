// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Unit;


// Registry-agnostic seam by which the lower dispatch modules (aether-invoke bridge/router wiring) hand a
// freshly minted per-injection-point ObservabilityStrategyCell to the node-level write-side registry and
// take it back at unload (#277 increment 2). The registry (aether/node) implements this; the lower
// modules depend only on this interface, so they never reach the registry directly and stay
// registry-agnostic. register() adds the cell to the key's live set and seeds it with the last-known
// strategy; deregister() drops it so a later KV-update cannot touch an unloaded injection point. The
// NOOP binding mints-and-forgets — cells stay at IDENTITY forever, which is exactly the passthrough
// behaviour a node wired without observability (and every stub) needs.
public interface ObservabilityCellRegistrar {
    Unit register(ObservabilityStrategyCell cell);
    Unit deregister(ObservabilityStrategyCell cell);
    ObservabilityCellRegistrar NOOP = new Noop();

    record Noop() implements ObservabilityCellRegistrar {
        @Override
        public Unit register(ObservabilityStrategyCell cell) {
            return Unit.unit();
        }

        @Override
        public Unit deregister(ObservabilityStrategyCell cell) {
            return Unit.unit();
        }
    }
}
