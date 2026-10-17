/*
 *  Copyright (c) 2023-2025 Sergiy Yevtushenko.
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
 *
 */

package org.pragmatica.lang.utils;

import java.util.stream.Stream;

import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pragmatica.lang.Functions.ThrowingRunnable;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Named.named;
import static org.pragmatica.lang.Tuple.tuple;
import static org.pragmatica.lang.Unit.unit;

/// #1311: every lift-style `catch (Throwable)` in core converted a [VirtualMachineError] into a failed
/// Result, an empty Option or a warning line — a plausible-looking value that no guard could see. All of
/// them now go through [Causes#rethrowIfFatal(Throwable)] first, and this is the pin for the rule as a
/// MECHANISM: one row per site, each given a supplier that overflows the stack for real, each expected to
/// let the StackOverflowError out. The site count here is the site count in `core/src/main`
/// (`grep -rn "catch (Throwable" core/src/main/java`), minus the one named exception: the scheduler's
/// timer-loop dispatch catch, which keeps the single timer thread alive by design (see the comment there).
///
/// `ScheduledTask.runGuarded` is pinned at the frame level (same package): end-to-end the rethrow lands in
/// the task's in-flight `Future`, which `cancel()` owns and nothing reads.
@Timeout(60)
class LiftFamilyVirtualMachineErrorTest {
    static Stream<Named<Runnable>> sites() {
        return Stream.of(named("Result.lift(mapper, supplier)", () -> Result.lift(Causes::fromThrowable, () -> overflow())),
                         named("Result.lift(mapper, runnable)", () -> Result.lift(Causes::fromThrowable, (ThrowingRunnable) () -> overflow())),
                         named("Unit.lift", () -> unit().lift(() -> overflow())),
                         named("Tuple1.lift", () -> tuple(1).lift((_) -> overflow())),
                         named("Tuple2.lift", () -> tuple(1, 2).lift((_, _) -> overflow())),
                         named("Tuple3.lift", () -> tuple(1, 2, 3).lift((_, _, _) -> overflow())),
                         named("Tuple4.lift", () -> tuple(1, 2, 3, 4).lift((_, _, _, _) -> overflow())),
                         named("Tuple5.lift", () -> tuple(1, 2, 3, 4, 5).lift((_, _, _, _, _) -> overflow())),
                         named("Tuple6.lift", () -> tuple(1, 2, 3, 4, 5, 6).lift((_, _, _, _, _, _) -> overflow())),
                         named("Tuple7.lift", () -> tuple(1, 2, 3, 4, 5, 6, 7).lift((_, _, _, _, _, _, _) -> overflow())),
                         named("Tuple8.lift", () -> tuple(1, 2, 3, 4, 5, 6, 7, 8).lift((_, _, _, _, _, _, _, _) -> overflow())),
                         named("Tuple9.lift", () -> tuple(1, 2, 3, 4, 5, 6, 7, 8, 9).lift((_, _, _, _, _, _, _, _, _) -> overflow())),
                         named("Tuple10.lift", () -> tuple(1, 2, 3, 4, 5, 6, 7, 8, 9, 10).lift((_, _, _, _, _, _, _, _, _, _) -> overflow())),
                         named("Tuple11.lift", () -> tuple(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11).lift((_, _, _, _, _, _, _, _, _, _, _) -> overflow())),
                         named("Tuple12.lift", () -> tuple(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12).lift((_, _, _, _, _, _, _, _, _, _, _, _) -> overflow())),
                         named("Tuple13.lift", () -> tuple(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13).lift((_, _, _, _, _, _, _, _, _, _, _, _, _) -> overflow())),
                         named("Tuple14.lift", () -> tuple(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14).lift((_, _, _, _, _, _, _, _, _, _, _, _, _, _) -> overflow())),
                         named("Tuple15.lift", () -> tuple(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15).lift((_, _, _, _, _, _, _, _, _, _, _, _, _, _, _) -> overflow())),
                         named("Option.lift", () -> Option.lift(() -> overflow())),
                         named("VirtualThreadScheduler.ScheduledTask.runGuarded", () -> new VirtualThreadScheduler.ScheduledTask(() -> overflow(), 0L, 0L).runGuarded()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sites")
    void liftSite_letsStackOverflowErrorEscape(Runnable site) {
        assertThatThrownBy(site::run).isInstanceOf(StackOverflowError.class);
    }

    private static <T> T overflow() {
        return overflow(0);
    }

    private static <T> T overflow(int depth) {
        return overflow(depth + 1);
    }
}
