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

package org.pragmatica.swim.membership;

import org.pragmatica.lang.io.TimeSpan;

import java.util.concurrent.TimeUnit;

/// Tunables for [`MembershipTracker`] (membership-unification-spec §4 "Tunables").
///
/// - `sampleInterval` — period of the single deterministic sample tick.
/// - `upHysteresis` (K-up) — consecutive healthy samples required for a node to ENTER
///   the stable member set.
/// - `downHysteresis` (K-down) — consecutive absent/faulty samples required for a node
///   to LEAVE the stable member set.
///
/// `nttDepartureTimeout` maps onto the hysteresis window as
/// `downHysteresis * sampleInterval` (spec §4 "mapping from nttDepartureTimeout onto the
/// hysteresis window"); use [`#fromDepartureTimeout`] to derive a config from a legacy
/// departure timeout while preserving the same effective debounce.
public record MembershipTrackerConfig(TimeSpan sampleInterval, int upHysteresis, int downHysteresis) {
    public MembershipTrackerConfig {
        if (upHysteresis < 1) {
            upHysteresis = 1;
        }
        if (downHysteresis < 1) {
            downHysteresis = 1;
        }
    }

    public static MembershipTrackerConfig membershipTrackerConfig(TimeSpan sampleInterval,
                                                                  int upHysteresis,
                                                                  int downHysteresis) {
        return new MembershipTrackerConfig(sampleInterval, upHysteresis, downHysteresis);
    }

    /// Defaults: 500ms sample tick, K=3 up, K=3 down (≈1.5s debounce each direction).
    public static MembershipTrackerConfig defaultConfig() {
        return new MembershipTrackerConfig(TimeSpan.timeSpan(500).millis(), 3, 3);
    }

    /// Derive a config whose down-window equals the supplied legacy departure timeout:
    /// `downHysteresis = ceil(departureTimeout / sampleInterval)`. The up-window reuses
    /// the same K for symmetric debounce.
    public static MembershipTrackerConfig fromDepartureTimeout(TimeSpan departureTimeout, TimeSpan sampleInterval) {
        var ticks = Math.max(1, (int) ceilDiv(departureTimeout.nanos(), Math.max(1, sampleInterval.nanos())));
        return new MembershipTrackerConfig(sampleInterval, ticks, ticks);
    }

    private static long ceilDiv(long numerator, long denominator) {
        return (numerator + denominator - 1) / denominator;
    }

    public long sampleIntervalNanos() {
        return sampleInterval.nanos();
    }

    public long sampleIntervalMillis() {
        return TimeUnit.NANOSECONDS.toMillis(sampleInterval.nanos());
    }
}
