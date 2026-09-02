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
package org.pragmatica.swim;

import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.serialization.Codec;
import org.pragmatica.serialization.CodecFor;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Configuration for the SWIM protocol.
///
/// @param period          interval between protocol ticks (probe rounds)
/// @param probeTimeout    time to wait for an Ack before escalating to indirect probes
/// @param indirectProbes  number of random members to use for indirect probing (PingReq)
/// @param suspectTimeout  time a member stays in SUSPECT before transitioning to FAULTY
/// @param maxPiggyback    maximum number of membership updates piggybacked per message
/// @param startupDelay    cooldown after quorum before first probe — allows all TCP connections to establish
/// @param clusterName     cluster identifier used to gate ANNOUNCE membership (empty string means no gating)
/// @param swimPortOffset  offset added to a peer's primary (e.g. QUIC) port to derive its SWIM listen port.
///                        `NodeInfo.address()` carries the cluster transport port; SWIM listens on
///                        `port + swimPortOffset`. When ANNOUNCE/Ping/Ack carry the sender's `NodeInfo`
///                        the receiver applies this offset to compute the authoritative SWIM address.
///                        Defaults to `0` for backwards compatibility (when the same port is used).
/// @param joinGrace       per-member NORMAL-phase JOIN-GRACE window. A member first introduced in
///                        `NORMAL`/`RECOVERING` phase that has never yet been observed HEALTHY is treated,
///                        for the duration of this window after its first sighting, EXACTLY like a
///                        cold-boot never-HEALTHY peer: its FAULTY edge emits `UnknownObserved` (not
///                        `FaultyObserved`) and is NOT tombstoned, giving the leader's round-robin probe
///                        cycle time to confirm a freshly-joined CTM replacement before it is FAULTY-evicted.
///                        Once the window elapses, a still-never-HEALTHY FAULTY member IS tombstoned and
///                        emitted FAULTY exactly as today (the 06-02 anti-oscillation contract). A value of
///                        zero disables the window — behavior is then identical to the pre-grace logic.
/// @param lhmMaxScore     Wave-6 Lifeguard Local Health Multiplier cap. The local self-awareness score
///                        rises by 1 on local trouble (own probe cycle timed out with no ack, failed
///                        probe send, a remote suspicion of self requiring refutation) and decays by 1
///                        on each verified probe-ack, saturating in `[0, lhmMaxScore]`. The suspicion
///                        window this node arms is `suspectTimeout × min(score + 1, lhmMaxScore)` —
///                        i.e. the multiplier ALSO saturates at `×lhmMaxScore` (with the default 8 the
///                        stretch caps at exactly ×8; score 7 and score 8 both yield ×8). Suspicion
///                        window only — probe cadence is NOT stretched (the fixed-rate tick keeps
///                        genuine-kill detection latency predictable).
/// @param dogpileExpectedConfirmers Wave-6 Lifeguard dogpile parameter K: the number of independent
///                        confirmers (distinct gossip senders re-asserting the same suspicion) at which
///                        a suspicion window shrinks fully from its LHM-stretched max back to the base
///                        `suspectTimeout`. Effective K is `max(1, min(this, trackedMembers - 1))`
///                        (i.e. `min(K, clusterSize - 2)`). Shrink curve is logarithmic
///                        (`max - (max-min) · log(C+1)/log(K+1)`, clamped at `min`), memberlist-style.
@Codec
@CodecFor({TimeSpan.class, java.net.InetSocketAddress.class})
public record SwimConfig(TimeSpan period,
                         TimeSpan probeTimeout,
                         int indirectProbes,
                         TimeSpan suspectTimeout,
                         int maxPiggyback,
                         TimeSpan startupDelay,
                         String clusterName,
                         int swimPortOffset,
                         TimeSpan joinGrace,
                         int lhmMaxScore,
                         int dogpileExpectedConfirmers) {
    /// Default configuration — suitable for Docker and containerized environments.
    /// `suspectTimeout` is the dominant hop in the SWIM detection chain; lowered
    /// from 15s to 10s to bring p95 detection well under the 60s integration-test
    /// SLO without inviting false-positive FAULTY transitions under transient
    /// packet loss (the chain still requires a successful indirect-probe round
    /// to fail before SUSPECT is entered).
    /// Default join-grace window. Must exceed `startupDelay` (10s) by ~2 probe periods
    /// (`period`=1s) so the SUSPECT→FAULTY join-grace defer in `expireSuspectIfOverdue` is
    /// the SOLE sufficient guard against premature eviction of a freshly-joined-but-not-yet-
    /// probed reachable member — now that transport no longer promotes liveness (SWIM probe-ack
    /// is the sole recovery authority). Cost: a genuinely dead-on-arrival joiner is detected
    /// `joinGrace` later (its FAULTY is merely deferred by this window, never skipped).
    public static final TimeSpan DEFAULT_JOIN_GRACE = timeSpan(12).seconds();
    /// Default Wave-6 Lifeguard LHM cap (memberlist-style awareness cap, ×8 max stretch).
    public static final int DEFAULT_LHM_MAX_SCORE = 8;
    /// Default Wave-6 Lifeguard dogpile expected-confirmer count K.
    public static final int DEFAULT_DOGPILE_EXPECTED_CONFIRMERS = 3;
    /// Fix 1 (#336 at-risk self-refutation): divisor applied to `suspectTimeout` to derive
    /// the "at-risk" window — the inbound-reachability silence after which a node proactively
    /// advances its own incarnation and re-broadcasts `Alive(self, k+1)` so it STRICTLY
    /// out-ranks an in-flight `Suspect(self, k)` it never received (the canonical-precedence
    /// dead-end the failing link created: `Alive` < `Suspect` at equal incarnation). Half the
    /// suspect window (divisor 2) gives the bumped `Alive` a full further suspect window to
    /// propagate before the original suspicion could ripen to FAULTY.
    public static final long AT_RISK_WINDOW_DIVISOR = 2L;
    /// Fix 2 (#336 co-confirmation kill-gate): the minimum number of DISTINCT independent
    /// accusers an EVER-HEALTHY peer's first-hand FAULTY edge needs before it emits the
    /// terminal `DepartedObserved`. A genuinely dead node accrues ≥2 independent accusers
    /// quickly (each peer's own probe cycle fails); a transient single-prober probe-ack miss
    /// does not, so an established core is no longer evicted on one node's lone say-so. The
    /// local first-hand expiry counts as one accuser, so a single co-confirming gossip suffices.
    public static final int MIN_FAULTY_CONFIRMERS = 2;
    /// Fix 3 (#336 cluster-size suspect-window scaling): upper clamp on the canonical
    /// Lifeguard `max(1, ln(N + 1))` size term that widens the suspect window as the live
    /// member count N grows (keeping the window several round-robin re-probe intervals wide
    /// as N approaches the fixed base suspect timeout). The window never exceeds
    /// `suspectTimeout × this`, bounding genuine-kill detection latency.
    public static final double MAX_CLUSTER_SIZE_WINDOW_MULTIPLIER = 3.0;

    public static final SwimConfig DEFAULT = swimConfig(timeSpan(1).seconds(),
                                                        timeSpan(800).millis(),
                                                        3,
                                                        timeSpan(10).seconds(),
                                                        8,
                                                        timeSpan(10).seconds(),
                                                        "",
                                                        0,
                                                        DEFAULT_JOIN_GRACE);

    /// Factory with all parameters including cluster name, swim port offset, and join grace.
    /// Lifeguard tunables default (see [`#withLhmMaxScore(int)`] / [`#withDogpileExpectedConfirmers(int)`]).
    public static SwimConfig swimConfig(TimeSpan period,
                                        TimeSpan probeTimeout,
                                        int indirectProbes,
                                        TimeSpan suspectTimeout,
                                        int maxPiggyback,
                                        TimeSpan startupDelay,
                                        String clusterName,
                                        int swimPortOffset,
                                        TimeSpan joinGrace) {
        return new SwimConfig(period,
                              probeTimeout,
                              indirectProbes,
                              suspectTimeout,
                              maxPiggyback,
                              startupDelay,
                              clusterName,
                              swimPortOffset,
                              joinGrace,
                              DEFAULT_LHM_MAX_SCORE,
                              DEFAULT_DOGPILE_EXPECTED_CONFIRMERS);
    }

    /// Factory with all parameters including cluster name and swim port offset; join grace defaults.
    public static SwimConfig swimConfig(TimeSpan period,
                                        TimeSpan probeTimeout,
                                        int indirectProbes,
                                        TimeSpan suspectTimeout,
                                        int maxPiggyback,
                                        TimeSpan startupDelay,
                                        String clusterName,
                                        int swimPortOffset) {
        return new SwimConfig(period,
                              probeTimeout,
                              indirectProbes,
                              suspectTimeout,
                              maxPiggyback,
                              startupDelay,
                              clusterName,
                              swimPortOffset,
                              DEFAULT_JOIN_GRACE,
                              DEFAULT_LHM_MAX_SCORE,
                              DEFAULT_DOGPILE_EXPECTED_CONFIRMERS);
    }

    /// Factory with all parameters including cluster name; swimPortOffset defaults to 0.
    public static SwimConfig swimConfig(TimeSpan period,
                                        TimeSpan probeTimeout,
                                        int indirectProbes,
                                        TimeSpan suspectTimeout,
                                        int maxPiggyback,
                                        TimeSpan startupDelay,
                                        String clusterName) {
        return new SwimConfig(period,
                              probeTimeout,
                              indirectProbes,
                              suspectTimeout,
                              maxPiggyback,
                              startupDelay,
                              clusterName,
                              0,
                              DEFAULT_JOIN_GRACE,
                              DEFAULT_LHM_MAX_SCORE,
                              DEFAULT_DOGPILE_EXPECTED_CONFIRMERS);
    }

    /// Factory with all timing/probe parameters — clusterName defaults to empty (no gating).
    public static SwimConfig swimConfig(TimeSpan period,
                                        TimeSpan probeTimeout,
                                        int indirectProbes,
                                        TimeSpan suspectTimeout,
                                        int maxPiggyback,
                                        TimeSpan startupDelay) {
        return new SwimConfig(period,
                              probeTimeout,
                              indirectProbes,
                              suspectTimeout,
                              maxPiggyback,
                              startupDelay,
                              "",
                              0,
                              DEFAULT_JOIN_GRACE,
                              DEFAULT_LHM_MAX_SCORE,
                              DEFAULT_DOGPILE_EXPECTED_CONFIRMERS);
    }

    /// Factory with defaults for startupDelay.
    public static SwimConfig swimConfig(TimeSpan period,
                                        TimeSpan probeTimeout,
                                        int indirectProbes,
                                        TimeSpan suspectTimeout,
                                        int maxPiggyback) {
        return new SwimConfig(period,
                              probeTimeout,
                              indirectProbes,
                              suspectTimeout,
                              maxPiggyback,
                              timeSpan(10).seconds(),
                              "",
                              0,
                              DEFAULT_JOIN_GRACE,
                              DEFAULT_LHM_MAX_SCORE,
                              DEFAULT_DOGPILE_EXPECTED_CONFIRMERS);
    }

    /// Factory with defaults.
    public static SwimConfig swimConfig() {
        return DEFAULT;
    }

    /// Build a [`SwimConfig`] from caller-supplied timing values, preserving
    /// [`SwimConfig#DEFAULT`]'s tuning constants for fields that are not exposed
    /// at the call boundary (`indirectProbes`, `maxPiggyback`, `startupDelay`,
    /// `swimPortOffset`).
    ///
    /// Used by `aether-config`'s `TimeoutsConfig.SwimTimeouts` to wire the
    /// toml-parsed `[timeouts.swim]` section into the SWIM detector.
    public static SwimConfig fromTimeouts(TimeSpan period, TimeSpan probeTimeout, TimeSpan suspectTimeout) {
        return new SwimConfig(period,
                              probeTimeout,
                              DEFAULT.indirectProbes(),
                              suspectTimeout,
                              DEFAULT.maxPiggyback(),
                              DEFAULT.startupDelay(),
                              DEFAULT.clusterName(),
                              DEFAULT.swimPortOffset(),
                              DEFAULT.joinGrace(),
                              DEFAULT.lhmMaxScore(),
                              DEFAULT.dogpileExpectedConfirmers());
    }

    /// Derive a new config with the given cluster name.
    public SwimConfig withClusterName(String name) {
        return new SwimConfig(period,
                              probeTimeout,
                              indirectProbes,
                              suspectTimeout,
                              maxPiggyback,
                              startupDelay,
                              name,
                              swimPortOffset,
                              joinGrace,
                              lhmMaxScore,
                              dogpileExpectedConfirmers);
    }

    /// Derive a new config with the given SWIM port offset. The offset is added to
    /// a peer's primary (e.g. QUIC) port to derive its SWIM listen port. See
    /// [`#swimPortOffset()`].
    public SwimConfig withSwimPortOffset(int offset) {
        return new SwimConfig(period,
                              probeTimeout,
                              indirectProbes,
                              suspectTimeout,
                              maxPiggyback,
                              startupDelay,
                              clusterName,
                              offset,
                              joinGrace,
                              lhmMaxScore,
                              dogpileExpectedConfirmers);
    }

    /// Derive a new config with the given per-member join-grace window. See [`#joinGrace()`].
    public SwimConfig withJoinGrace(TimeSpan grace) {
        return new SwimConfig(period,
                              probeTimeout,
                              indirectProbes,
                              suspectTimeout,
                              maxPiggyback,
                              startupDelay,
                              clusterName,
                              swimPortOffset,
                              grace,
                              lhmMaxScore,
                              dogpileExpectedConfirmers);
    }

    /// Derive a new config with the given Lifeguard LHM cap. See [`#lhmMaxScore()`].
    public SwimConfig withLhmMaxScore(int maxScore) {
        return new SwimConfig(period,
                              probeTimeout,
                              indirectProbes,
                              suspectTimeout,
                              maxPiggyback,
                              startupDelay,
                              clusterName,
                              swimPortOffset,
                              joinGrace,
                              maxScore,
                              dogpileExpectedConfirmers);
    }

    /// Derive a new config with the given dogpile expected-confirmer count K.
    /// See [`#dogpileExpectedConfirmers()`].
    public SwimConfig withDogpileExpectedConfirmers(int expectedConfirmers) {
        return new SwimConfig(period,
                              probeTimeout,
                              indirectProbes,
                              suspectTimeout,
                              maxPiggyback,
                              startupDelay,
                              clusterName,
                              swimPortOffset,
                              joinGrace,
                              lhmMaxScore,
                              expectedConfirmers);
    }
}
