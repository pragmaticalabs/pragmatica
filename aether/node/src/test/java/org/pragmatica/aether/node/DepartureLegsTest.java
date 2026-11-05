// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.deployment.membership.ntt.DepartureOutcome;
import org.pragmatica.aether.deployment.membership.ntt.DrainWindow;
import org.pragmatica.aether.worker.governor.CommunityDrainCoordinator;
import org.pragmatica.aether.worker.governor.CommunityPlacementMessage;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// #2034 T19: the departure composite gives the DHT leg the legs deadline less the movement budget only when a movement drain is
/// present, reads that presence once, and reports the DHT leg's outcome whatever the movement leg did.
class DepartureLegsTest {
    private static final TimeSpan BUDGET = TimeSpan.timeSpan(10).seconds();
    private static final long LEGS_DEADLINE = 1_000_000_000_000L;
    private static final TimeSpan SETTLE = TimeSpan.timeSpan(2).seconds();

    @Test
    @Timeout(10)
    void movementPresent_dhtLegDeadlineIsLegsDeadlineLessTheMovementBudget() {
        var seen = new AtomicReference<DrainWindow>();
        var legs = DepartureLegs.composed(capturing(seen, DepartureOutcome.COMPLETED),
                                          () -> Option.some(movement(Promise.success(Unit.unit()))),
                                          BUDGET);

        var outcome = legs.apply(window()).await(SETTLE);

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(seen.get().legsDeadlineNanos()).isEqualTo(LEGS_DEADLINE - BUDGET.nanos());
        assertThat(seen.get().eventFloorNanos()).as("the event floor keeps the full legs deadline").isEqualTo(LEGS_DEADLINE);
    }

    @Test
    @Timeout(10)
    void movementLegTimesOut_dhtAtRisk_compositeIsAtRisk() {
        var legs = DepartureLegs.composed(capturing(new AtomicReference<>(), DepartureOutcome.COMPLETED_WITH_DATA_AT_RISK),
                                          () -> Option.some(movement(Causes.cause("movement ack timed out").promise())),
                                          BUDGET);

        var outcome = legs.apply(window()).await(SETTLE);

        assertThat(outcome.isSuccess()).as("a failed movement leg never fails the departure").isTrue();
        assertThat(outcome.unwrap()).isEqualTo(DepartureOutcome.COMPLETED_WITH_DATA_AT_RISK);
    }

    @Test
    @Timeout(10)
    void movementLegSucceeds_dhtAtRisk_compositeIsAtRisk() {
        var legs = DepartureLegs.composed(capturing(new AtomicReference<>(), DepartureOutcome.COMPLETED_WITH_DATA_AT_RISK),
                                          () -> Option.some(movement(Promise.success(Unit.unit()))),
                                          BUDGET);

        assertThat(legs.apply(window()).await(SETTLE).unwrap()).isEqualTo(DepartureOutcome.COMPLETED_WITH_DATA_AT_RISK);
    }

    @Test
    @Timeout(10)
    void movementAbsent_dhtLegSeesTheFullLegsDeadline() {
        var seen = new AtomicReference<DrainWindow>();
        var legs = DepartureLegs.composed(capturing(seen, DepartureOutcome.COMPLETED), Option::none, BUDGET);

        assertThat(legs.apply(window()).await(SETTLE).unwrap()).isEqualTo(DepartureOutcome.COMPLETED);
        assertThat(seen.get().legsDeadlineNanos()).isEqualTo(LEGS_DEADLINE);
    }

    @Test
    @Timeout(10)
    void movementAbsent_dhtAtRisk_compositeIsAtRisk() {
        var legs = DepartureLegs.composed(capturing(new AtomicReference<>(), DepartureOutcome.COMPLETED_WITH_DATA_AT_RISK),
                                          Option::none,
                                          BUDGET);

        assertThat(legs.apply(window()).await(SETTLE).unwrap()).isEqualTo(DepartureOutcome.COMPLETED_WITH_DATA_AT_RISK);
    }

    /// E4: the movement drain is read once per departure; the read that shortened the DHT deadline also decides the movement leg.
    @Test
    @Timeout(10)
    void movementDrain_isReadOncePerDeparture() {
        var reads = new AtomicInteger();
        var quiesced = new AtomicInteger();
        var coordinator = movement(Promise.success(Unit.unit()), quiesced);
        var legs = DepartureLegs.composed(capturing(new AtomicReference<>(), DepartureOutcome.COMPLETED),
                                          () -> countedRead(reads, coordinator),
                                          BUDGET);

        legs.apply(window()).await(SETTLE);

        assertThat(reads.get()).isEqualTo(1);
        assertThat(quiesced.get()).as("the movement leg ran on that same read").isEqualTo(1);
    }

    private static Option<CommunityDrainCoordinator> countedRead(AtomicInteger reads, CommunityDrainCoordinator coordinator) {
        reads.incrementAndGet();

        return Option.some(coordinator);
    }

    private static DrainWindow window() {
        return DrainWindow.drainWindow(LEGS_DEADLINE, 1_700_000_000_000L, () -> false);
    }

    private static Function<DrainWindow, Promise<DepartureOutcome>> capturing(AtomicReference<DrainWindow> seen,
                                                                            DepartureOutcome outcome) {
        return window -> captured(seen, window, outcome);
    }

    private static Promise<DepartureOutcome> captured(AtomicReference<DrainWindow> seen, DrainWindow window, DepartureOutcome outcome) {
        seen.set(window);

        return Promise.success(outcome);
    }

    private static CommunityDrainCoordinator movement(Promise<Unit> onQuiesced) {
        return movement(onQuiesced, new AtomicInteger());
    }

    private static CommunityDrainCoordinator movement(Promise<Unit> onQuiesced, AtomicInteger quiesced) {
        return new StubMovement(onQuiesced, quiesced);
    }

    private record StubMovement(Promise<Unit> quiescedResult, AtomicInteger quiesced) implements CommunityDrainCoordinator {
        @Override
        public Unit onRequest(CommunityPlacementMessage.DrainRequested request) {
            return Unit.unit();
        }

        @Override
        public Unit onAccepted(CommunityPlacementMessage.DrainAccepted response) {
            return Unit.unit();
        }

        @Override
        public Promise<Unit> onQuiesced() {
            quiesced.incrementAndGet();

            return quiescedResult;
        }
    }
}
