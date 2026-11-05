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

import java.util.function.BooleanSupplier;


/// The absolute drain-clock bounds of one graceful-departure push (#2034), in [System#nanoTime] units.
///
/// - `deadlineNanos`: the push resolves by then. Resends stop [DeparturePushTuning#eventDeliveryWait] earlier, so the
///   events it raised have that long to land.
/// - `eventFloorNanos`: a later bound the event wait may extend to when `deadlineNanos` leaves it less than
///   [DeparturePushTuning#eventDeliveryWait] (a late quiesce): the events still get a chance to land before the halt.
///   Equal to `deadlineNanos` when the caller runs nothing after the push.
/// - `halted`: true once the node has halted (in-JVM hosts keep running); the push then stops at once and raises nothing more.
public record DepartureWindow(long deadlineNanos, long eventFloorNanos, BooleanSupplier halted) {
    /// A window ending at `deadlineNanos` with nothing after it, never halted.
    public static DepartureWindow departureWindow(long deadlineNanos) {
        return departureWindow(deadlineNanos, () -> false);
    }

    /// A window ending at `deadlineNanos` with nothing after it.
    public static DepartureWindow departureWindow(long deadlineNanos, BooleanSupplier halted) {
        return new DepartureWindow(deadlineNanos, deadlineNanos, halted);
    }
}
