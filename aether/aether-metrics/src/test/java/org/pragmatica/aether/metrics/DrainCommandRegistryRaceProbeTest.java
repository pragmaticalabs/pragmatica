// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.metrics;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;

/// v3858a probe: the race clearIf's `pins.remove(node, pin)` exists for. A clear reads the pin of an old drain; before it removes,
/// another thread clears that drain and pins a NEW drain of the same node. The stale clear must leave the new drain. Deterministic:
/// the registry's map runs the interleaving between the clear's read and its remove; no sleep, no thread.
class DrainCommandRegistryRaceProbeTest {
    private static final NodeId NODE = NodeId.nodeId("drained").unwrap();

    static final class InterleavingMap extends ConcurrentHashMap<NodeId, DrainPin> {
        final AtomicReference<Runnable> interleaving = new AtomicReference<>();

        @Override
        public DrainPin get(Object key) {
            var read = super.get(key);
            var step = interleaving.getAndSet(null);

            if (step != null) {
                step.run();
            }

            return read;
        }
    }

    private static InterleavingMap install(DrainCommandRegistry registry) throws Exception {
        var map = new InterleavingMap();
        var field = DrainCommandRegistry.class.getDeclaredField("pins");

        field.setAccessible(true);
        field.set(registry, map);

        return map;
    }

    @Test
    void staleEdgeClear_racingAClearAndANewDrainOfTheSameNode_keepsTheNewDrain() throws Exception {
        var registry = DrainCommandRegistry.drainCommandRegistry();
        var map = install(registry);
        var old = registry.requestDrain(NODE).unwrap();
        var newer = new AtomicReference<DrainId>();

        map.interleaving.set(() -> {
            registry.clearDrain(old);
            newer.set(registry.requestDrain(NODE).unwrap());
        });
        registry.clearEndedLife(NODE, 0L);

        assertThat(newer.get()).as("instrument: the interleaving ran and pinned a new drain").isNotNull().isNotEqualTo(old);
        assertThat(registry.isDrainRequested(NODE)).as("the stale clear removed the newer drain").isTrue();
        assertThat(registry.pinOf(NODE).unwrap().id()).isEqualTo(newer.get());
    }

    @Test
    void staleIdClear_racingAClearAndANewDrainOfTheSameNode_keepsTheNewDrain() throws Exception {
        var registry = DrainCommandRegistry.drainCommandRegistry();
        var map = install(registry);
        var old = registry.requestDrain(NODE).unwrap();

        map.interleaving.set(() -> {
            registry.clearEndedLife(NODE, 0L);
            registry.requestDrain(NODE);
        });
        registry.clearDrain(old);

        assertThat(registry.isDrainRequested(NODE)).as("the stale id clear removed the newer drain").isTrue();
        assertThat(registry.pinOf(NODE).unwrap().id()).isNotEqualTo(old);
    }
}
