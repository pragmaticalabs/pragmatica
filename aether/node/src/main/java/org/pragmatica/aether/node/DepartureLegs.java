// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.function.Function;
import java.util.function.Supplier;

import org.pragmatica.aether.deployment.membership.ntt.DepartureOutcome;
import org.pragmatica.aether.deployment.membership.ntt.DrainWindow;
import org.pragmatica.aether.worker.governor.CommunityDrainCoordinator;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;


/// The two legs of a draining node's departure (#427, #2034): the DHT push, then the community movement drain, in that order.
///
/// The movement leg waits up to its own budget for the core's acknowledgement. When it is present, the DHT leg's deadline is the
/// legs deadline less that budget, so an extended DHT window never eats the movement's time (H3). The movement drain is read
/// ONCE per departure and the same value decides both the DHT deadline and whether the movement leg runs (E4), so a coordinator
/// bound in between cannot give the DHT leg the full window and then run the movement past it. The composite's outcome is the DHT
/// leg's: a movement failure or timeout never hides "completed with data at risk".
public sealed interface DepartureLegs {
    /// Compose the legs. `movementDrain` is read when the departure runs.
    static Function<DrainWindow, Promise<DepartureOutcome>> composed(Function<DrainWindow, Promise<DepartureOutcome>> dhtLeg,
                                                                     Supplier<Option<CommunityDrainCoordinator>> movementDrain,
                                                                     TimeSpan movementBudget) {
        return window -> run(dhtLeg, movementDrain.get(), movementBudget, window);
    }

    private static Promise<DepartureOutcome> run(Function<DrainWindow, Promise<DepartureOutcome>> dhtLeg,
                                                 Option<CommunityDrainCoordinator> movement,
                                                 TimeSpan movementBudget,
                                                 DrainWindow window) {
        return dhtLeg.apply(dhtWindow(movement, movementBudget, window))
                     .flatMap(outcome -> movementThen(movement, outcome));
    }

    private static DrainWindow dhtWindow(Option<CommunityDrainCoordinator> movement,
                                         TimeSpan movementBudget,
                                         DrainWindow window) {
        return movement.isPresent()
               ? window.withDeadline(window.legsDeadlineNanos() - movementBudget.nanos())
               : window;
    }

    /// FER: the movement leg's own result (success, failure or timeout) is not the departure's outcome; the DHT leg's is.
    private static Promise<DepartureOutcome> movementThen(Option<CommunityDrainCoordinator> movement,
                                                          DepartureOutcome outcome) {
        return movement.fold(() -> Promise.success(outcome),
                             coordinator -> coordinator.onQuiesced()
                                                       .fold(_ -> Promise.success(outcome)));
    }

    record unused() implements DepartureLegs {}
}
