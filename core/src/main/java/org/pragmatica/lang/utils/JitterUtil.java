/*
 *  Copyright (c) 2025 Sergiy Yevtushenko.
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

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;


/// Reusable jitter helper for retry/scheduling sites.
///
/// Multiplies a base delay by a uniformly distributed random factor in
/// `[minFactor, maxFactor]` to break thundering-herd patterns when many peers
/// retry simultaneously after a correlated event (network blip, leader churn).
///
/// Two API styles:
///  - `applyJitter(baseMs, minFactor, maxFactor)` — process-wide `ThreadLocalRandom`.
///  - `applyJitter(baseMs, minFactor, maxFactor, randomSupplier)` — injectable supplier
///    of `[0.0, 1.0)` doubles for deterministic tests.
public sealed interface JitterUtil {
    /// Default lower factor (0.5x). Pair with `MAX_FACTOR_DEFAULT` for ±50% jitter.
    double MIN_FACTOR_DEFAULT = 0.5d;
    /// Default upper factor (1.5x). Pair with `MIN_FACTOR_DEFAULT` for ±50% jitter.
    double MAX_FACTOR_DEFAULT = 1.5d;
    /// Light jitter lower factor (0.8x). For protocol-sensitive timers (Rabia sync, SWIM probe).
    double LIGHT_MIN_FACTOR = 0.8d;

    /// Light jitter upper factor (1.2x). For protocol-sensitive timers.
    double LIGHT_MAX_FACTOR = 1.2d;

    /// Apply jitter using `ThreadLocalRandom` as the entropy source.
    ///
    /// @param baseMs    base delay in milliseconds (must be ≥ 0)
    /// @param minFactor lower bound of the multiplicative factor (typically 0.5 or 0.8)
    /// @param maxFactor upper bound of the multiplicative factor (typically 1.5 or 1.2)
    /// @return jittered delay in milliseconds, never less than 1ms when `baseMs > 0`
    static long applyJitter(long baseMs, double minFactor, double maxFactor) {
        return applyJitter(baseMs,
                           minFactor,
                           maxFactor,
                           () -> ThreadLocalRandom.current().nextDouble());
    }

    /// Apply jitter using an injected `[0.0, 1.0)` random supplier (for deterministic tests).
    static long applyJitter(long baseMs, double minFactor, double maxFactor, DoubleSupplier randomSupplier) {
        if (baseMs <= 0L) {
            return baseMs;
        }

        var factor = minFactor + (maxFactor - minFactor) * randomSupplier.getAsDouble();

        return Math.max(1L, (long)(baseMs * factor));
    }

    /// Apply default jitter (0.5x — 1.5x).
    static long applyJitter(long baseMs) {
        return applyJitter(baseMs, MIN_FACTOR_DEFAULT, MAX_FACTOR_DEFAULT);
    }

    /// Convenience for tests: derive a `DoubleSupplier` from a seeded `Random`.
    static DoubleSupplier supplierFrom(Random random) {
        return random::nextDouble;
    }

    record unused() implements JitterUtil {}
}
