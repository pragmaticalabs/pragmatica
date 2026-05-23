// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.drain;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// Regression tests for the self-drain gate added to `InFlightRequestTracker`:
///
///   * `setAcceptingNewWork(false)` closes the gate; `tryEnter()` returns false; legacy
///     `enter()` becomes a no-op (does NOT increment the counter).
///   * `onAllDrained` callback fires exactly once when the counter reaches zero AFTER
///     the gate was closed.
///   * Re-registering `onAllDrained` after firing does NOT re-arm.
///   * Toggle-back to `accepting=true` (operator abort) re-opens the gate but does NOT
///     re-arm the drained callback.
class InFlightRequestTrackerGateTest {
    @Nested class GateSemantics {
        @Test
        void newTracker_acceptsWork() {
            var tracker = InFlightRequestTracker.inFlightRequestTracker();
            assertThat(tracker.isAcceptingNewWork()).isTrue();
            assertThat(tracker.tryEnter()).isTrue();
            assertThat(tracker.count()).isEqualTo(1);
        }

        @Test
        void closedGate_rejectsTryEnter_andSwallowsEnter() {
            var tracker = InFlightRequestTracker.inFlightRequestTracker();
            tracker.setAcceptingNewWork(false);

            assertThat(tracker.isAcceptingNewWork()).isFalse();
            assertThat(tracker.tryEnter()).isFalse();
            tracker.enter();
            assertThat(tracker.count()).isZero();
        }

        @Test
        void closedGate_doesNotRejectAlreadyAdmittedExits() {
            var tracker = InFlightRequestTracker.inFlightRequestTracker();
            tracker.tryEnter();
            tracker.tryEnter();
            tracker.setAcceptingNewWork(false);

            tracker.exit();
            tracker.exit();
            assertThat(tracker.count()).isZero();
        }

        @Test
        void reopenedGate_resumesAcceptingWork() {
            var tracker = InFlightRequestTracker.inFlightRequestTracker();
            tracker.setAcceptingNewWork(false);
            tracker.setAcceptingNewWork(true);

            assertThat(tracker.isAcceptingNewWork()).isTrue();
            assertThat(tracker.tryEnter()).isTrue();
            assertThat(tracker.count()).isEqualTo(1);
        }
    }

    @Nested class OnAllDrained {
        @Test
        void firesOnce_whenCounterReachesZero_afterGateClosed() {
            var fires = new AtomicInteger(0);
            var tracker = InFlightRequestTracker.inFlightRequestTracker();
            tracker.tryEnter();
            tracker.tryEnter();
            tracker.onAllDrained(fires::incrementAndGet);

            tracker.exit();
            assertThat(fires.get()).isZero();

            tracker.setAcceptingNewWork(false);
            assertThat(fires.get()).isZero();

            tracker.exit();
            assertThat(fires.get()).isEqualTo(1);
        }

        @Test
        void doesNotFire_underNormalTraffic_whenGateIsOpen() {
            var fires = new AtomicInteger(0);
            var tracker = InFlightRequestTracker.inFlightRequestTracker();
            tracker.onAllDrained(fires::incrementAndGet);

            for (int i = 0; i < 5; i++) {
                tracker.tryEnter();
                tracker.exit();
            }
            assertThat(fires.get()).isZero();
        }

        @Test
        void firesImmediately_whenGateClosesWithZeroInFlight() {
            var fires = new AtomicInteger(0);
            var tracker = InFlightRequestTracker.inFlightRequestTracker();
            tracker.onAllDrained(fires::incrementAndGet);

            tracker.setAcceptingNewWork(false);
            assertThat(fires.get()).isEqualTo(1);
        }

        @Test
        void firesImmediately_whenCallbackRegisteredAfterAlreadyQuiesced() {
            var fires = new AtomicInteger(0);
            var tracker = InFlightRequestTracker.inFlightRequestTracker();
            tracker.setAcceptingNewWork(false);

            tracker.onAllDrained(fires::incrementAndGet);
            assertThat(fires.get()).isEqualTo(1);
        }

        @Test
        void doesNotRefire_onRepeatedZeroCrossings() {
            var fires = new AtomicInteger(0);
            var tracker = InFlightRequestTracker.inFlightRequestTracker();
            tracker.onAllDrained(fires::incrementAndGet);
            tracker.setAcceptingNewWork(false);
            assertThat(fires.get()).isEqualTo(1);

            // Even if we hypothetically re-open and process more work, the
            // self-drain "drained" event is a one-shot per coordinator lifecycle.
            tracker.setAcceptingNewWork(true);
            tracker.tryEnter();
            tracker.exit();
            tracker.setAcceptingNewWork(false);
            assertThat(fires.get()).isEqualTo(1);
        }
    }
}
