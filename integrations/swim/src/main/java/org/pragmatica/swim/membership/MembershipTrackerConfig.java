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
/// hysteresis window"); use [`#fromDepartureTimeout(TimeSpan, TimeSpan)`] to derive a config
/// from a legacy departure timeout while preserving the same effective debounce.
///
/// **Asymmetric hysteresis (UP fast, DOWN slow).** The UP edge (admitting a node into the
/// stable member set) and the DOWN edge (dropping a node out of it) carry different risk and
/// should be tuned independently:
///
/// - **UP should be FAST.** A node that is already SWIM-healthy is low-risk to admit — SWIM
///   has its own failure detection, so a brief healthy streak is sufficient evidence. A slow
///   UP edge actively hurts: it delays cluster formation and quorum recovery, and — critically
///   for auto-heal — it makes a freshly provisioned replacement node appear in stable
///   membership LATER than the leader reconciler's in-flight provisioning window expects.
///   When the node materialises after the reconciler has already forgotten its in-flight
///   entry, the reconciler re-provisions the same deficit, producing a phantom-node
///   provisioning storm.
/// - **DOWN must stay SLOW.** Dropping a node is destructive (it can trigger drains, leadership
///   churn, and provisioning), so the DOWN edge must debounce transient blips (a missed gossip
///   round, a GC pause) and only react to a genuinely sustained absence.
///
/// Use [`#fromDepartureTimeout(TimeSpan, TimeSpan, int)`] to keep the slow, departure-derived
/// DOWN window while choosing a small, fast UP window.
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

    /// Derive an ASYMMETRIC config: the DOWN window stays the slow, safe departure debounce
    /// (`downHysteresis = ceil(departureTimeout / sampleInterval)`, identical to the 2-arg
    /// overload) while the UP window is set to the caller-supplied `upHysteresis` for fast
    /// admit. See the type-level doc for why fast-UP / slow-DOWN is the correct asymmetry
    /// (slow UP delays formation/quorum recovery and lets provisioned auto-heal nodes appear
    /// after the reconciler's in-flight window, causing re-provisioning storms).
    public static MembershipTrackerConfig fromDepartureTimeout(TimeSpan departureTimeout,
                                                               TimeSpan sampleInterval,
                                                               int upHysteresis) {
        var downHysteresis = Math.max(1, (int) ceilDiv(departureTimeout.nanos(), Math.max(1, sampleInterval.nanos())));
        return new MembershipTrackerConfig(sampleInterval, upHysteresis, downHysteresis);
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
