// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.drain;

import org.pragmatica.consensus.NodeId;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;


/// Unit tests for the core-only orphan self-drain checker
/// (slot-based-core-membership-redesign §5 dynamic filled-by-connected predicate).
///
/// Every conjunct of the §5 predicate is exercised in isolation: each test holds all other
/// conjuncts true and flips exactly one to verify it gates the drain. The trigger is a
/// recording fake (an AtomicInteger counter) so no JVM exit occurs; production wires the
/// trigger to `SelfDrainCoordinator::onOrphanDetected`.
///
/// `liveFilled` = count of slot occupants that are in the connected-members set. A slot bound to
/// a dead/disconnected occupant is NOT live-filled, so the node WAITS (it could be rebound there).
class OrphanSelfDrainCheckerTest {
    private static final NodeId SELF = nodeId("self-node").unwrap();
    private static final NodeId SLOT_A = nodeId("slot-a").unwrap();
    private static final NodeId SLOT_B = nodeId("slot-b").unwrap();
    private static final NodeId SLOT_C = nodeId("slot-c").unwrap();
    private static final NodeId SLOT_D = nodeId("slot-d").unwrap();
    private static final NodeId SLOT_E = nodeId("slot-e").unwrap();
    private static final int CONFIGURED = 5;
    /// Five distinct occupants, none of them self.
    private static final Set<NodeId> OCCUPANTS_WITHOUT_SELF = Set.of(SLOT_A, SLOT_B, SLOT_C, SLOT_D, SLOT_E);
    /// Five occupants, self among them.
    private static final Set<NodeId> OCCUPANTS_WITH_SELF = Set.of(SELF, SLOT_A, SLOT_B, SLOT_C, SLOT_D);
    /// All five non-self occupants are connected (every slot live-filled, self not among them).
    private static final Set<NodeId> CONNECTED_ALL_OCCUPANTS = Set.of(SELF, SLOT_A, SLOT_B, SLOT_C, SLOT_D, SLOT_E);

    private static AtomicInteger triggers() {
        return new AtomicInteger(0);
    }

    private static OrphanSelfDrainChecker checker(BooleanSupplier coreRole,
                                                  BooleanSupplier rabiaActive,
                                                  BooleanSupplier inQuorum,
                                                  Supplier<Set<NodeId>> slotOccupants,
                                                  Supplier<Set<NodeId>> connectedMembers,
                                                  IntSupplier configured,
                                                  AtomicInteger triggerCount) {
        OrphanSelfDrainChecker.OrphanDrainTrigger trigger = _ -> triggerCount.incrementAndGet();
        return OrphanSelfDrainChecker.orphanSelfDrainChecker(SELF, coreRole, rabiaActive, inQuorum,
                                                             slotOccupants, connectedMembers, configured, trigger);
    }

    @Nested class Fires {
        @Test
        void check_triggersDrain_whenEverySlotLiveFilledAndSelfNotAmongThem() {
            var count = triggers();
            // 5 occupants, all connected, none is self → liveFilled=5==configured, self surplus.
            var coord = checker(() -> true, () -> true, () -> true,
                                () -> OCCUPANTS_WITHOUT_SELF, () -> CONNECTED_ALL_OCCUPANTS,
                                () -> CONFIGURED, count);

            coord.check();

            assertThat(count.get()).isEqualTo(1);
        }
    }

    @Nested class DoesNotFire {
        @Test
        void check_doesNotTrigger_whenSelfIsALiveOccupant() {
            var count = triggers();
            // self occupies a slot and is connected → self is a live occupant → not orphan.
            var coord = checker(() -> true, () -> true, () -> true,
                                () -> OCCUPANTS_WITH_SELF, () -> CONNECTED_ALL_OCCUPANTS,
                                () -> CONFIGURED, count);

            coord.check();

            assertThat(count.get()).isZero();
        }

        @Test
        void check_doesNotTrigger_whenSlotHasDeadOccupant_liveFilledBelowConfigured() {
            var count = triggers();
            // 5 occupants but SLOT_E is NOT connected (dead) → liveFilled=4 < 5 → WAIT, do not drain.
            Set<NodeId> connectedMinusOne = Set.of(SELF, SLOT_A, SLOT_B, SLOT_C, SLOT_D);
            var coord = checker(() -> true, () -> true, () -> true,
                                () -> OCCUPANTS_WITHOUT_SELF, () -> connectedMinusOne,
                                () -> CONFIGURED, count);

            coord.check();

            assertThat(count.get())
                    .as("a dead/disconnected occupant drops liveFilled below configured → node waits")
                    .isZero();
        }

        @Test
        void check_doesNotTrigger_whenOccupantSetPartial_liveFilledBelowConfigured() {
            var count = triggers();
            // only 4 slots have occupants → liveFilled <= 4 < 5 → not converged → wait.
            Set<NodeId> partial = Set.of(SLOT_A, SLOT_B, SLOT_C, SLOT_D);
            var coord = checker(() -> true, () -> true, () -> true,
                                () -> partial, () -> CONNECTED_ALL_OCCUPANTS,
                                () -> CONFIGURED, count);

            coord.check();

            assertThat(count.get()).isZero();
        }

        @Test
        void check_doesNotTrigger_whenNotActive() {
            var count = triggers();
            var coord = checker(() -> true, () -> false, () -> true,
                                () -> OCCUPANTS_WITHOUT_SELF, () -> CONNECTED_ALL_OCCUPANTS,
                                () -> CONFIGURED, count);

            coord.check();

            assertThat(count.get()).isZero();
        }

        @Test
        void check_doesNotTrigger_whenNotInQuorum() {
            var count = triggers();
            var coord = checker(() -> true, () -> true, () -> false,
                                () -> OCCUPANTS_WITHOUT_SELF, () -> CONNECTED_ALL_OCCUPANTS,
                                () -> CONFIGURED, count);

            coord.check();

            assertThat(count.get()).isZero();
        }

        @Test
        void check_doesNotTrigger_whenWorkerRole() {
            var count = triggers();
            // Worker (observing) → coreRole false → strict gate short-circuits, never evaluates
            // slot/connected logic even though every other conjunct would hold.
            Supplier<Set<NodeId>> explodesSet = () -> {
                throw new AssertionError("worker must not evaluate slot-binding logic");
            };
            var coord = checker(() -> false, () -> true, () -> true,
                                explodesSet, explodesSet, () -> CONFIGURED, count);

            coord.check();

            assertThat(count.get()).isZero();
        }

        @Test
        void check_doesNotTrigger_whenConfiguredSizeZero() {
            var count = triggers();
            // No generation snapshot yet → configured=0 → never act.
            var coord = checker(() -> true, () -> true, () -> true,
                                Set::of, Set::of, () -> 0, count);

            coord.check();

            assertThat(count.get()).isZero();
        }
    }

    @Nested class StrictGateOrdering {
        @Test
        void check_evaluatesCoreRoleFirst_beforeAnyOtherSignal() {
            var count = triggers();
            BooleanSupplier explodes = () -> {
                throw new AssertionError("must not evaluate after core-role gate fails");
            };
            Supplier<Set<NodeId>> explodesSet = () -> {
                throw new AssertionError("must not read slots/connected after core-role gate fails");
            };
            var coord = checker(() -> false, explodes, explodes,
                                explodesSet, explodesSet,
                                () -> {throw new AssertionError("no configured read");}, count);

            coord.check();

            assertThat(count.get()).isZero();
        }
    }
}
