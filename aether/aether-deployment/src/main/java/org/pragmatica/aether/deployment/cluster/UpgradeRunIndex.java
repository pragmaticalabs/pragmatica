// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.slice.kvstore.AetherKey.UpgradeRunKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.UpgradeRunValue;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;


/// #1543 part F — every node's view of the one committed rolling-upgrade run, and the place a transition is seen exactly as the
/// cluster committed it: the listener gets (previous, next) on EVERY node, so the operator events are derived from one commit and
/// raised by whichever node owns the cluster-events partition.
public final class UpgradeRunIndex {
    private volatile Option<UpgradeRunValue> current = Option.none();

    private UpgradeRunIndex() {}

    public static UpgradeRunIndex upgradeRunIndex() {
        return new UpgradeRunIndex();
    }

    @FunctionalInterface
    public interface TransitionListener {
        Unit onTransition(Option<UpgradeRunValue> before, UpgradeRunValue after);
    }

    private final AtomicReference<TransitionListener> listener = new AtomicReference<>((_, _) -> Unit.unit());

    public Unit onTransition(TransitionListener transitionListener) {
        listener.set(transitionListener);

        return Unit.unit();
    }

    public Unit put(UpgradeRunValue value) {
        return listener.get()
                       .onTransition(swap(value),
                                     value);
    }

    private synchronized Option<UpgradeRunValue> swap(UpgradeRunValue value) {
        var before = current;

        current = Option.some(value);

        return before;
    }

    public synchronized Unit remove() {
        current = Option.none();

        return Unit.unit();
    }

    /// Replace the view from a KV snapshot (state restore); anything that is not the run is ignored. No event is raised: a restore is
    /// not a transition.
    public synchronized Unit restore(Map<?, ?> snapshot) {
        current = snapshot.entrySet()
                          .stream()
                          .filter(entry -> entry.getKey() instanceof UpgradeRunKey && entry.getValue() instanceof UpgradeRunValue)
                          .map(entry -> (UpgradeRunValue) entry.getValue())
                          .findFirst()
                          .map(Option::some)
                          .orElse(Option.none());

        return Unit.unit();
    }

    public Option<UpgradeRunValue> run() {
        return current;
    }
}
