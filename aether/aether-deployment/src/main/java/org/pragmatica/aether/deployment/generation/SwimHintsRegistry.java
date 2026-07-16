// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.cluster.metrics.HealthHintWire;
import org.pragmatica.cluster.metrics.PeerHealthObservation;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Option;


public final class SwimHintsRegistry {
    private final Map<NodeId, TimedHint> hints = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Fn0<Long> nowSupplier;
    private final Runnable onChange;

    private SwimHintsRegistry(Duration ttl, Fn0<Long> nowSupplier, Runnable onChange) {
        this.ttl = ttl;
        this.nowSupplier = nowSupplier;
        this.onChange = onChange;
    }

    public static SwimHintsRegistry swimHintsRegistry(Duration ttl, Runnable onChange) {
        return new SwimHintsRegistry(ttl, System::currentTimeMillis, onChange);
    }

    public static SwimHintsRegistry swimHintsRegistry(Duration ttl, Fn0<Long> nowSupplier, Runnable onChange) {
        return new SwimHintsRegistry(ttl, nowSupplier, onChange);
    }

    @Contract
    public void onPeerHealth(PeerHealthObservation obs) {
        toHint(obs.hint()).apply(() -> clear(obs.peerId()),
                                 hint -> putHint(obs.peerId(), hint, obs.producedAtMs()));
    }

    @Contract
    public void putHint(NodeId node, HealthHint hint, long observedAtMs) {
        var prev = hints.put(node, new TimedHint(hint, observedAtMs));

        if (prev == null || prev.hint != hint) {
            onChange.run();
        }
    }

    @Contract
    public void clear(NodeId node) {
        if (hints.remove(node) != null) {
            onChange.run();
        }
    }

    public boolean isEmpty() {
        return hints.isEmpty();
    }

    public Map<NodeId, HealthHint> currentTtlFiltered() {
        var now = nowSupplier.apply();
        var deadline = now - ttl.toMillis();
        var snapshot = new HashMap<NodeId, HealthHint>();

        hints.forEach((node, timed) -> {
            if (timed.observedAtMs >= deadline) {
                snapshot.put(node, timed.hint);
            } else {
                hints.remove(node, timed);
            }
        });

        return snapshot;
    }

    private static Option<HealthHint> toHint(HealthHintWire wire) {
        return switch (wire) {
            case FAULTY -> Option.some(HealthHint.FAULTY);
            case SUSPECTED -> Option.some(HealthHint.SUSPECTED);
            case HEALTHY -> Option.none();
        };
    }

    private record TimedHint(HealthHint hint, long observedAtMs) {}
}
