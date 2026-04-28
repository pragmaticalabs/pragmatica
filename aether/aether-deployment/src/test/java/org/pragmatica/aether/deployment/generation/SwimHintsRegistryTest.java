// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.cluster.metrics.HealthHintWire;
import org.pragmatica.cluster.metrics.PeerHealthObservation;
import org.pragmatica.consensus.NodeId;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;


/// Unit tests for [`SwimHintsRegistry`] covering TTL expiry, change-callback semantics,
/// `onPeerHealth` translation, and `isEmpty` lifecycle.
///
/// All tests inject the clock via the `(ttl, nowSupplier, onChange)` factory variant for
/// deterministic time control.
class SwimHintsRegistryTest {
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final NodeId NODE_B = new NodeId("node-b");
    private static final Duration TTL = Duration.ofMillis(5_000L);

    @Nested
    class TtlExpiry {
        @Test
        void hintWithinTtl_appearsInFilteredView() {
            var clock = new AtomicLong(1_000_000L);
            var changes = new AtomicInteger(0);
            var registry = SwimHintsRegistry.swimHintsRegistry(TTL, clock::get, changes::incrementAndGet);

            registry.putHint(NODE_A, HealthHint.FAULTY, clock.get());
            // Time advances within TTL.
            clock.set(1_000_000L + 1_000L);

            var view = registry.currentTtlFiltered();
            assertThat(view).containsEntry(NODE_A, HealthHint.FAULTY);
        }

        @Test
        void hintPastTtl_omittedFromFilteredViewAndPurged() {
            var clock = new AtomicLong(1_000_000L);
            var changes = new AtomicInteger(0);
            var registry = SwimHintsRegistry.swimHintsRegistry(TTL, clock::get, changes::incrementAndGet);

            registry.putHint(NODE_A, HealthHint.FAULTY, clock.get());
            // Advance past TTL — entry must lazily expire on read.
            clock.set(1_000_000L + TTL.toMillis() + 1_000L);

            var view = registry.currentTtlFiltered();
            assertThat(view).isEmpty();
            // The lazy purge in currentTtlFiltered also removes the underlying entry.
            assertThat(registry.isEmpty()).isTrue();
        }
    }

    @Nested
    class OnChangeCallback {
        @Test
        void firstInsert_firesOnChange() {
            var clock = new AtomicLong(1_000_000L);
            var changes = new AtomicInteger(0);
            var registry = SwimHintsRegistry.swimHintsRegistry(TTL, clock::get, changes::incrementAndGet);

            registry.putHint(NODE_A, HealthHint.FAULTY, clock.get());

            assertThat(changes.get()).isEqualTo(1);
        }

        @Test
        void reInsertSameHint_doesNotFireOnChange() {
            var clock = new AtomicLong(1_000_000L);
            var changes = new AtomicInteger(0);
            var registry = SwimHintsRegistry.swimHintsRegistry(TTL, clock::get, changes::incrementAndGet);
            registry.putHint(NODE_A, HealthHint.FAULTY, clock.get());
            var afterFirst = changes.get();

            registry.putHint(NODE_A, HealthHint.FAULTY, clock.get() + 1L);

            assertThat(changes.get()).isEqualTo(afterFirst);
        }

        @Test
        void promoteSuspectedToFaulty_firesOnChange() {
            var clock = new AtomicLong(1_000_000L);
            var changes = new AtomicInteger(0);
            var registry = SwimHintsRegistry.swimHintsRegistry(TTL, clock::get, changes::incrementAndGet);
            registry.putHint(NODE_A, HealthHint.SUSPECTED, clock.get());
            var afterFirst = changes.get();

            registry.putHint(NODE_A, HealthHint.FAULTY, clock.get() + 1L);

            assertThat(changes.get()).isGreaterThan(afterFirst);
        }
    }

    @Nested
    class OnPeerHealth {
        @Test
        void healthyObservation_clearsExistingHint() {
            var clock = new AtomicLong(1_000_000L);
            var changes = new AtomicInteger(0);
            var registry = SwimHintsRegistry.swimHintsRegistry(TTL, clock::get, changes::incrementAndGet);
            registry.putHint(NODE_A, HealthHint.FAULTY, clock.get());

            var healthy = new PeerHealthObservation(NODE_A, HealthHintWire.HEALTHY, 1L, 0L, clock.get());
            registry.onPeerHealth(healthy);

            assertThat(registry.isEmpty()).isTrue();
            assertThat(registry.currentTtlFiltered()).doesNotContainKey(NODE_A);
        }

        @Test
        void faultyObservation_putsFaultyHint() {
            var clock = new AtomicLong(1_000_000L);
            var changes = new AtomicInteger(0);
            var registry = SwimHintsRegistry.swimHintsRegistry(TTL, clock::get, changes::incrementAndGet);

            var faulty = new PeerHealthObservation(NODE_A, HealthHintWire.FAULTY, 1L, 0L, clock.get());
            registry.onPeerHealth(faulty);

            assertThat(registry.currentTtlFiltered()).containsEntry(NODE_A, HealthHint.FAULTY);
        }

        @Test
        void suspectedObservation_putsSuspectedHint() {
            var clock = new AtomicLong(1_000_000L);
            var changes = new AtomicInteger(0);
            var registry = SwimHintsRegistry.swimHintsRegistry(TTL, clock::get, changes::incrementAndGet);

            var suspected = new PeerHealthObservation(NODE_A, HealthHintWire.SUSPECTED, 1L, 0L, clock.get());
            registry.onPeerHealth(suspected);

            assertThat(registry.currentTtlFiltered()).containsEntry(NODE_A, HealthHint.SUSPECTED);
        }
    }

    @Nested
    class IsEmptyLifecycle {
        @Test
        void emptyOnConstruction() {
            var clock = new AtomicLong(1_000_000L);
            var registry = SwimHintsRegistry.swimHintsRegistry(TTL, clock::get, () -> {});

            assertThat(registry.isEmpty()).isTrue();
        }

        @Test
        void notEmptyAfterInsert() {
            var clock = new AtomicLong(1_000_000L);
            var registry = SwimHintsRegistry.swimHintsRegistry(TTL, clock::get, () -> {});
            registry.putHint(NODE_A, HealthHint.FAULTY, clock.get());

            assertThat(registry.isEmpty()).isFalse();
        }

        @Test
        void emptyAfterClear() {
            var clock = new AtomicLong(1_000_000L);
            var registry = SwimHintsRegistry.swimHintsRegistry(TTL, clock::get, () -> {});
            registry.putHint(NODE_A, HealthHint.FAULTY, clock.get());
            registry.putHint(NODE_B, HealthHint.SUSPECTED, clock.get());

            registry.clear(NODE_A);
            registry.clear(NODE_B);

            assertThat(registry.isEmpty()).isTrue();
        }
    }
}
