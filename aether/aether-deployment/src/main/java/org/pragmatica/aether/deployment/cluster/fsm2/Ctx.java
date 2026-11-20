// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.pragmatica.consensus.NodeId;


/// What one transition step works with: the settings, the two clock readings taken once at the start of the step
/// (R-3.1.4), and the collector of the step's effects. A `Ctx` lives for exactly one `step` call and is confined
/// to its thread.
final class Ctx {
    private final Settings settings;
    private final long nowMs;
    private final long monoNs;
    private final List<ClusterEffect> effects = new ArrayList<>();
    private int rebalanceBudget = 1;

    Ctx(Settings settings, long nowMs, long monoNs) {
        this.settings = settings;
        this.nowMs = nowMs;
        this.monoNs = monoNs;
    }

    Settings settings() {
        return settings;
    }

    NodeId self() {
        return settings.self();
    }

    long nowMs() {
        return nowMs;
    }

    long monoNs() {
        return monoNs;
    }

    List<ClusterEffect> effects() {
        return List.copyOf(effects);
    }

    /// One rebalance move per reconcile pass (B-061): true while the budget lasts, and spends it.
    boolean takeRebalanceBudget() {
        return rebalanceBudget--> 0;
    }

    Ctx emit(ClusterEffect effect) {
        effects.add(effect);

        return this;
    }

    Ctx warn(String code, String subject, String message) {
        return emit(new ClusterEffect.Warn(code, subject, message));
    }

    Ctx route(RoutedEvent event) {
        return emit(new ClusterEffect.Route(event));
    }

    Ctx metric(String name, Map<String, String> tags) {
        return emit(new ClusterEffect.Metric(name, tags, 1L));
    }

    Ctx metric(String name, long delta, Map<String, String> tags) {
        return emit(new ClusterEffect.Metric(name, tags, delta));
    }
}
