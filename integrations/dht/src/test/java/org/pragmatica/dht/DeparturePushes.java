/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.dht;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import java.util.Set;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// The convenience shapes of a departure push the tests use (#3345 removed them from [DHTRebalancer], whose only entry is the
/// window + tuning + drain-set supplier form).
///
/// A `budget` is the attempts window: the push's deadline is `now + budget + eventDeliveryWait`, and an attempt waits at most
/// `min(1 s, budget / 4)` for its ack. The default window is 15 s of attempts.
final class DeparturePushes {
    static final TimeSpan DEFAULT_BUDGET = timeSpan(15).seconds();

    private DeparturePushes() {}

    static DeparturePushTuning tuningFor(TimeSpan budget) {
        var resend = Math.max(1L, Math.min(DeparturePushTuning.DEFAULT.resendAfter().nanos(), budget.nanos() / 4));

        return new DeparturePushTuning(timeSpan(resend).nanos(),
                                       DeparturePushTuning.DEFAULT.nackBackoffCap(),
                                       DeparturePushTuning.DEFAULT.reportAfter(),
                                       DeparturePushTuning.DEFAULT.eventDeliveryWait());
    }

    static Promise<DeparturePushReport> push(DHTRebalancer rebalancer, DeparturePushObserver observer) {
        return push(rebalancer, DEFAULT_BUDGET, Set.of(), observer);
    }

    static Promise<DeparturePushReport> push(DHTRebalancer rebalancer, TimeSpan budget, DeparturePushObserver observer) {
        return push(rebalancer, budget, Set.of(), observer);
    }

    static Promise<DeparturePushReport> push(DHTRebalancer rebalancer, Set<NodeId> drainSet, DeparturePushObserver observer) {
        return push(rebalancer, DEFAULT_BUDGET, drainSet, observer);
    }

    static Promise<DeparturePushReport> push(DHTRebalancer rebalancer,
                                             TimeSpan budget,
                                             Set<NodeId> drainSet,
                                             DeparturePushObserver observer) {
        var tuning = tuningFor(budget);
        var deadline = System.nanoTime() + budget.nanos() + tuning.eventDeliveryWait().nanos();

        return rebalancer.pushOnDeparture(DepartureWindow.departureWindow(deadline), tuning, () -> drainSet, observer);
    }
}
