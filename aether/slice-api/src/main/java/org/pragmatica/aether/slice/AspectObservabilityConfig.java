// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

// Per-injection-point snapshot of system-observability facet state. Held in a volatile field on each
// observability Aspect instance and swapped wholesale on a KV-update event (push-on-event: the hot path
// reads only this local reference, never a registry lookup). Immutable so a call sees a complete old or
// complete new snapshot, never a torn mix.
//
// Distinct from org.pragmatica.aether.invoke.ObservabilityConfig (depth / trace-rate tuning) — this is
// the facet on/off + depth snapshot for the always-on system observability aspect (#277).
public record AspectObservabilityConfig(boolean logging, boolean metrics, boolean spans, boolean tracing, int depth) {
    // All facets disabled: the hot-path fast exit (one volatile load + predicted branch, zero cost).
    public static final AspectObservabilityConfig OFF = new AspectObservabilityConfig(false, false, false, false, 0);

    public static AspectObservabilityConfig aspectObservabilityConfig(boolean logging,
                                                                      boolean metrics,
                                                                      boolean spans,
                                                                      boolean tracing,
                                                                      int depth) {
        return new AspectObservabilityConfig(logging, metrics, spans, tracing, depth);
    }

    public boolean allOff() {
        return ! (logging || metrics || spans || tracing);
    }
}
