// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.drain;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.io.TimeSpan;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Unit tests for the core-only orphan self-drain checker
/// (slot-based-core-membership-redesign §5).
///
/// Every conjunct of the §5 predicate is exercised in isolation: each test holds all other
/// conjuncts true and flips exactly one to verify it gates the drain. The trigger is a
/// recording fake (an AtomicInteger counter) so no JVM exit occurs; production wires the
/// trigger to `SelfDrainCoordinator::onOrphanDetected`.
class OrphanSelfDrainCheckerTest {
    private static final NodeId SELF = nodeId("self-node").unwrap();
    private static final NodeId SLOT_A = nodeId("slot-a").unwrap();
    private static final NodeId SLOT_B = nodeId("slot-b").unwrap();
    private static final NodeId SLOT_C = nodeId("slot-c").unwrap();
    private static final NodeId SLOT_D = nodeId("slot-d").unwrap();
    private static final NodeId SLOT_E = nodeId("slot-e").unwrap();
    private static final int CONFIGURED = 5;
    private static final TimeSpan GRACE = timeSpan(30).seconds();
    private static final Set<NodeId> FULL_WITHOUT_SELF = Set.of(SLOT_A, SLOT_B, SLOT_C, SLOT_D, SLOT_E);
    private static final Set<NodeId> FULL_WITH_SELF = Set.of(SELF, SLOT_A, SLOT_B, SLOT_C, SLOT_D);
    private static final Set<NodeId> PARTIAL = Set.of(SLOT_A, SLOT_B, SLOT_C, SLOT_D);

    /// Mutable clock that starts past the grace window so the default-built checker is
    /// grace-elapsed unless a test pins it pre-grace.
    private static AtomicInteger triggers() {
        return new AtomicInteger(0);
    }

    private static OrphanSelfDrainChecker checker(BooleanSupplier coreRole,
                                                  BooleanSupplier rabiaActive,
                                                  BooleanSupplier inQuorum,
                                                  Supplier<Set<NodeId>> boundSet,
                                                  IntSupplier configured,
                                                  LongSupplier clock,
                                                  AtomicInteger triggerCount) {
        OrphanSelfDrainChecker.OrphanDrainTrigger trigger = _ -> triggerCount.incrementAndGet();
        return OrphanSelfDrainChecker.orphanSelfDrainChecker(SELF, coreRole, rabiaActive, inQuorum,
                                                             boundSet, configured, GRACE, clock, trigger);
    }

    /// Clock anchored at 0, advanced past grace so a checker built at t=0 is grace-elapsed.
    private static LongSupplier elapsedClock() {
        var t = new AtomicReference<Long>(0L);
        // Construction reads anchor=0; subsequent reads return anchor + 2*grace → elapsed.
        return () -> {
            var now = t.get();
            t.set(GRACE.millis() * 2);
            return now;
        };
    }

    @Nested class Fires {
        @Test
        void check_triggersDrain_whenGenuineOrphan() {
            var count = triggers();
            var coord = checker(() -> true, () -> true, () -> true,
                                () -> FULL_WITHOUT_SELF, () -> CONFIGURED, elapsedClock(), count);

            coord.check();

            assertThat(count.get()).isEqualTo(1);
        }
    }

    @Nested class DoesNotFire {
        @Test
        void check_doesNotTrigger_whenSelfInBoundSet() {
            var count = triggers();
            var coord = checker(() -> true, () -> true, () -> true,
                                () -> FULL_WITH_SELF, () -> CONFIGURED, elapsedClock(), count);

            coord.check();

            assertThat(count.get()).isZero();
        }

        @Test
        void check_doesNotTrigger_whenSlotSetPartial() {
            var count = triggers();
            // self absent, but bound set has only 4 of 5 → partial → must not act.
            var coord = checker(() -> true, () -> true, () -> true,
                                () -> PARTIAL, () -> CONFIGURED, elapsedClock(), count);

            coord.check();

            assertThat(count.get()).isZero();
        }

        @Test
        void check_doesNotTrigger_whenNotActive() {
            var count = triggers();
            var coord = checker(() -> true, () -> false, () -> true,
                                () -> FULL_WITHOUT_SELF, () -> CONFIGURED, elapsedClock(), count);

            coord.check();

            assertThat(count.get()).isZero();
        }

        @Test
        void check_doesNotTrigger_whenNotInQuorum() {
            var count = triggers();
            var coord = checker(() -> true, () -> true, () -> false,
                                () -> FULL_WITHOUT_SELF, () -> CONFIGURED, elapsedClock(), count);

            coord.check();

            assertThat(count.get()).isZero();
        }

        @Test
        void check_doesNotTrigger_beforeGraceElapsed() {
            var count = triggers();
            // Clock never advances → elapsed = 0 < grace → pre-grace.
            var coord = checker(() -> true, () -> true, () -> true,
                                () -> FULL_WITHOUT_SELF, () -> CONFIGURED, () -> 0L, count);

            coord.check();

            assertThat(count.get()).isZero();
        }

        @Test
        void check_doesNotTrigger_whenWorkerRole() {
            var count = triggers();
            // Worker (observing) → coreRole false → strict gate short-circuits, never evaluates
            // slot logic even though every other conjunct would hold.
            Supplier<Set<NodeId>> boundSet = () -> {
                throw new AssertionError("worker must not evaluate slot-binding logic");
            };
            var coord = checker(() -> false, () -> true, () -> true,
                                boundSet, () -> CONFIGURED, elapsedClock(), count);

            coord.check();

            assertThat(count.get()).isZero();
        }

        @Test
        void check_doesNotTrigger_whenConfiguredSizeZero() {
            var count = triggers();
            // No generation snapshot yet → configured=0 → never act (empty bound set == 0).
            var coord = checker(() -> true, () -> true, () -> true,
                                Set::of, () -> 0, elapsedClock(), count);

            coord.check();

            assertThat(count.get()).isZero();
        }
    }

    @Nested class StrictGateOrdering {
        @Test
        void check_evaluatesCoreRoleFirst_beforeAnyOtherSignal() {
            var count = triggers();
            // All consensus/KV signals would throw if touched; coreRole=false must short-circuit.
            BooleanSupplier explodes = () -> {
                throw new AssertionError("must not evaluate after core-role gate fails");
            };
            Supplier<Set<NodeId>> explodesSet = () -> {
                throw new AssertionError("must not read slots after core-role gate fails");
            };
            var coord = checker(() -> false, explodes, explodes,
                                explodesSet, () -> {throw new AssertionError("no configured read");},
                                elapsedClock(), count);

            coord.check();

            assertThat(count.get()).isZero();
        }
    }
}
