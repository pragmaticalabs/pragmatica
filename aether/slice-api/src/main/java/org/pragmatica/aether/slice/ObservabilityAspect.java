// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

// The always-on system-observability Aspect (#277). One instance wraps one slice; it holds a volatile
// AspectObservabilityConfig snapshot that a node-side write-side registry swaps wholesale on a KV-update
// event (push-on-event). The per-call hot path reads only this local volatile field — no shared-registry
// lookup (goal G-3).
//
// In this foundation step apply() is a passthrough: the aspect is sourced at the slice seam
// (SliceFactory) and registered so its config is KV-swappable, but the per-call read/emit is rehomed
// onto this seam in a later step. Distinct from user interceptors (frozen at construction) — this is the
// outer, runtime-switchable system layer.
public final class ObservabilityAspect<T> implements Aspect<T> {
    private final String injectionPoint;
    private volatile AspectObservabilityConfig observabilityConfig;

    private ObservabilityAspect(String injectionPoint, AspectObservabilityConfig initial) {
        this.injectionPoint = injectionPoint;
        this.observabilityConfig = initial;
    }

    public static <T> ObservabilityAspect<T> observabilityAspect(String injectionPoint) {
        return new ObservabilityAspect<>(injectionPoint, AspectObservabilityConfig.OFF);
    }

    @Override
    public T apply(T instance) {
        return instance;
    }

    @SuppressWarnings("JBCT-RET-01")
    public void swap(AspectObservabilityConfig config) {
        this.observabilityConfig = config;
    }

    public AspectObservabilityConfig observabilityConfig() {
        return observabilityConfig;
    }

    public String injectionPoint() {
        return injectionPoint;
    }
}
