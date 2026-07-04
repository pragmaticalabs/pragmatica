// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.AtomicStrategy;

import java.util.concurrent.atomic.AtomicReference;

// Per-injection-point system-observability cell (#277). One instance wraps ONE dispatch injection point
// (keyed `artifactBase + "/" + methodName`) at either native seam — east-west/topic/timer
// (ObservabilityInterceptor.intercept) or north-south (SliceRouter.invokeHandler). It holds an
// AtomicStrategy whose lambda IS the behaviour: the write-side registry pre-composes the KV config into
// one "around" strategy and swaps it in wholesale, so the per-call hot path is one volatile read + one
// invoke, with zero allocation while off.
//
// OFF is the InvocationStrategy.IDENTITY singleton (proceed -> proceed.apply()): a call runs untouched.
// Distinct from user interceptors (frozen at construction) — this is the outer, runtime-switchable
// system layer. Behaviour is swapped, never a config snapshot; facet composition bodies land later.
public final class ObservabilityStrategyCell {
    /// The "around" strategy shape shared by both dispatch seams: receive the `proceed` thunk and
    /// return the (possibly decorated) Promise. Uniform enough that the east-west interceptor and the
    /// north-south router attach the same cell type. IDENTITY runs `proceed` untouched.
    @FunctionalInterface
    public interface InvocationStrategy {
        Promise<?> around(Fn0<Promise<?>> proceed);

        InvocationStrategy IDENTITY = Fn0::apply;
    }

    private final String key;
    private final AtomicStrategy<InvocationStrategy> strategy;
    // Reserved per-cell attachment point for future stateful facets (e.g. metrics latency accumulators)
    // that must survive across calls; unused while every strategy composes to identity.
    private final AtomicReference<Object> storage = new AtomicReference<>();

    private ObservabilityStrategyCell(String key) {
        this.key = key;
        this.strategy = AtomicStrategy.atomicStrategy(InvocationStrategy.IDENTITY);
    }

    public static ObservabilityStrategyCell observabilityStrategyCell(String artifactBase, String methodName) {
        return new ObservabilityStrategyCell(artifactBase + "/" + methodName);
    }

    public InvocationStrategy strategy() {
        return strategy.strategy();
    }

    @Contract
    public void swap(InvocationStrategy next) {
        strategy.swap(next);
    }

    public String key() {
        return key;
    }

    public AtomicReference<Object> storage() {
        return storage;
    }
}
