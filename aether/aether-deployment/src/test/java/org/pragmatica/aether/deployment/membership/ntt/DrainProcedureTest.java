// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.DrainReason;
import org.pragmatica.aether.deployment.drain.InFlightRequestTracker;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Unit coverage for the §8.2 unified drain procedure (Phase 2b replacement for
/// `SelfDrainCoordinator`'s execution surface).
///
/// Scenarios:
///   * `initiate` is single-shot — second invocation is a no-op (CAS guard).
///   * `state()` advances `INACTIVE → DRAINING → EXITED` across one drain.
///   * Tracker gate is closed at `INACTIVE → DRAINING`, and the registered
///     `onAllDrained` callback fires the JVM-exit hook.
///   * A throwing `swimLeaveEmitter` does NOT prevent `jvmExit` from running
///     (drain is uninterruptible by best-effort side effects).
class DrainProcedureTest {
    private static final DrainReason TEST_REASON = DrainReason.OPERATOR_COMMAND;

    @Nested
    class SingleShot {
        @Test
        void initiate_drainsOnce_andExitsViaTracker() {
            var tracker = InFlightRequestTracker.inFlightRequestTracker();
            var exits = new AtomicInteger(0);
            var leaves = new AtomicInteger(0);
            var procedure = DrainProcedure.drainProcedure(tracker,
                                                          leaves::incrementAndGet,
                                                          exits::incrementAndGet,
                                                          timeSpan(5).seconds());

            assertThat(procedure.state()).isEqualTo(DrainProcedure.DrainState.INACTIVE);

            procedure.initiate(TEST_REASON);

            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(exits.get()).isEqualTo(1);
                assertThat(leaves.get()).isEqualTo(1);
                assertThat(procedure.state()).isEqualTo(DrainProcedure.DrainState.EXITED);
            });
            assertThat(tracker.isAcceptingNewWork()).isFalse();
        }

        @Test
        void initiate_isIdempotent_secondInvocationIsNoOp() {
            var tracker = InFlightRequestTracker.inFlightRequestTracker();
            var exits = new AtomicInteger(0);
            var leaves = new AtomicInteger(0);
            var procedure = DrainProcedure.drainProcedure(tracker,
                                                          leaves::incrementAndGet,
                                                          exits::incrementAndGet,
                                                          timeSpan(5).seconds());

            procedure.initiate(TEST_REASON);
            procedure.initiate(DrainReason.OVERPROVISION_SCALE_DOWN);
            procedure.initiate(DrainReason.OVERPROVISION_PARTITION_HEAL);

            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(exits.get()).isEqualTo(1));
            assertThat(leaves.get()).isEqualTo(1);
            assertThat(procedure.state()).isEqualTo(DrainProcedure.DrainState.EXITED);
        }

        @Test
        void initiate_emitsDrainInitiatedOnce_atTransition() {
            var tracker = InFlightRequestTracker.inFlightRequestTracker();
            var emitted = new java.util.concurrent.CopyOnWriteArrayList<DrainReason>();
            var procedure = DrainProcedure.drainProcedure(tracker,
                                                          () -> {},
                                                          emitted::add,
                                                          () -> {});

            procedure.initiate(TEST_REASON);
            // Re-triggers must NOT emit again (single-shot CAS gate).
            procedure.initiate(DrainReason.OVERPROVISION_SCALE_DOWN);

            assertThat(emitted).containsExactly(TEST_REASON);
        }

        @Test
        void initiate_throwingEmitter_doesNotBlockDrain() {
            var tracker = InFlightRequestTracker.inFlightRequestTracker();
            var exits = new AtomicInteger(0);
            var procedure = DrainProcedure.drainProcedure(tracker,
                                                          () -> {},
                                                          reason -> {throw new RuntimeException("boom");},
                                                          exits::incrementAndGet);

            procedure.initiate(TEST_REASON);

            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(exits.get()).isEqualTo(1));
            assertThat(procedure.state()).isEqualTo(DrainProcedure.DrainState.EXITED);
        }
    }

    @Nested
    class StateTransitions {
        @Test
        void state_transitions_inactive_draining_exited() {
            var tracker = InFlightRequestTracker.inFlightRequestTracker();
            tracker.enter();
            var exits = new AtomicInteger(0);
            var procedure = DrainProcedure.drainProcedure(tracker,
                                                          () -> {},
                                                          exits::incrementAndGet,
                                                          timeSpan(5).seconds());

            assertThat(procedure.state()).isEqualTo(DrainProcedure.DrainState.INACTIVE);

            procedure.initiate(TEST_REASON);

            assertThat(procedure.state()).isEqualTo(DrainProcedure.DrainState.DRAINING);
            assertThat(exits.get()).isZero();

            tracker.exit();

            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(procedure.state()).isEqualTo(DrainProcedure.DrainState.EXITED);
                assertThat(exits.get()).isEqualTo(1);
            });
        }

        @Test
        void graceExpiry_forcesExit_evenWithInflightOutstanding() {
            var tracker = InFlightRequestTracker.inFlightRequestTracker();
            tracker.enter();
            var exits = new AtomicInteger(0);
            var procedure = DrainProcedure.drainProcedure(tracker,
                                                          () -> {},
                                                          exits::incrementAndGet,
                                                          timeSpan(150).millis());

            procedure.initiate(TEST_REASON);

            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(exits.get()).isEqualTo(1);
                assertThat(procedure.state()).isEqualTo(DrainProcedure.DrainState.EXITED);
            });
        }
    }

    @Nested
    class BestEffortLeave {
        @Test
        void throwingLeaveEmitter_doesNotBlockExit() {
            var tracker = InFlightRequestTracker.inFlightRequestTracker();
            var exits = new AtomicInteger(0);
            Runnable throwingLeave = () -> {throw new RuntimeException("boom");};
            var procedure = DrainProcedure.drainProcedure(tracker,
                                                          throwingLeave,
                                                          exits::incrementAndGet,
                                                          timeSpan(5).seconds());

            procedure.initiate(TEST_REASON);

            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(exits.get()).isEqualTo(1));
            assertThat(procedure.state()).isEqualTo(DrainProcedure.DrainState.EXITED);
        }
    }

    @Nested
    class DeparturePushGate {
        @Test
        void quiescedExit_waitsForDeparturePush_toSettle() {
            var tracker = InFlightRequestTracker.inFlightRequestTracker();
            var exits = new AtomicInteger(0);
            Promise<Unit> push = Promise.promise();
            var procedure = DrainProcedure.drainProcedure(tracker,
                                                          () -> {},
                                                          reason -> {},
                                                          () -> push,
                                                          exits::incrementAndGet);

            procedure.initiate(TEST_REASON);

            // Tracker has no in-flight work, but the push has not settled — exit is gated.
            assertThat(procedure.state()).isEqualTo(DrainProcedure.DrainState.DRAINING);
            assertThat(exits.get()).isZero();

            push.succeed(Unit.unit());

            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(exits.get()).isEqualTo(1);
                assertThat(procedure.state()).isEqualTo(DrainProcedure.DrainState.EXITED);
            });
        }

        @Test
        void graceExpiry_forcesExit_evenWithDeparturePushOutstanding() {
            var tracker = InFlightRequestTracker.inFlightRequestTracker();
            var exits = new AtomicInteger(0);
            Promise<Unit> neverSettles = Promise.promise();
            var procedure = DrainProcedure.drainProcedure(tracker,
                                                          () -> {},
                                                          reason -> {},
                                                          () -> neverSettles,
                                                          exits::incrementAndGet,
                                                          timeSpan(150).millis());

            procedure.initiate(TEST_REASON);

            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(exits.get()).isEqualTo(1);
                assertThat(procedure.state()).isEqualTo(DrainProcedure.DrainState.EXITED);
            });
        }

        @Test
        void throwingDeparturePush_doesNotBlockExit() {
            var tracker = InFlightRequestTracker.inFlightRequestTracker();
            var exits = new AtomicInteger(0);
            var procedure = DrainProcedure.drainProcedure(tracker,
                                                          () -> {},
                                                          reason -> {},
                                                          () -> {throw new RuntimeException("boom");},
                                                          exits::incrementAndGet);

            procedure.initiate(TEST_REASON);

            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(exits.get()).isEqualTo(1));
            assertThat(procedure.state()).isEqualTo(DrainProcedure.DrainState.EXITED);
        }
    }
}
