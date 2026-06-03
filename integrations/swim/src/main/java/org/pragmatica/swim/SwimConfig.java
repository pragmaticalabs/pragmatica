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
                         TimeSpan joinGrace) {

    /// Default configuration — suitable for Docker and containerized environments.
    /// `suspectTimeout` is the dominant hop in the SWIM detection chain; lowered
    /// from 15s to 10s to bring p95 detection well under the 60s integration-test
    /// SLO without inviting false-positive FAULTY transitions under transient
    /// packet loss (the chain still requires a successful indirect-probe round
    /// to fail before SUSPECT is entered).
    /// Default join-grace window. `period`=1s, the leader's round-robin probe cycle reaches
    /// a freshly-joined member within a few periods; the observed premature FAULTY-eviction
    /// of an unconfirmed CTM replacement fires at ~3.7s. 5s comfortably exceeds that ~3.7s
    /// AND covers ~5 probe periods so the leader can reach + confirm the new member, while
    /// keeping a genuinely-dead just-joined node detected only `joinGrace` later (its FAULTY
    /// + tombstone is merely deferred by this window, not skipped).
    public static final TimeSpan DEFAULT_JOIN_GRACE = timeSpan(5).seconds();

    public static final SwimConfig DEFAULT = swimConfig(
        timeSpan(1).seconds(),
        timeSpan(800).millis(),
        3,
        timeSpan(10).seconds(),
        8,
        timeSpan(10).seconds(),
        "",
        0,
        DEFAULT_JOIN_GRACE
    );

    /// Factory with all parameters including cluster name, swim port offset, and join grace.
    public static SwimConfig swimConfig(TimeSpan period,
                                        TimeSpan probeTimeout,
                                        int indirectProbes,
                                        TimeSpan suspectTimeout,
                                        int maxPiggyback,
                                        TimeSpan startupDelay,
                                        String clusterName,
                                        int swimPortOffset,
                                        TimeSpan joinGrace) {
        return new SwimConfig(period, probeTimeout, indirectProbes, suspectTimeout,
                              maxPiggyback, startupDelay, clusterName, swimPortOffset, joinGrace);
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
        return new SwimConfig(period, probeTimeout, indirectProbes, suspectTimeout,
                              maxPiggyback, startupDelay, clusterName, swimPortOffset, DEFAULT_JOIN_GRACE);
    }

    /// Factory with all parameters including cluster name; swimPortOffset defaults to 0.
    public static SwimConfig swimConfig(TimeSpan period,
                                        TimeSpan probeTimeout,
                                        int indirectProbes,
                                        TimeSpan suspectTimeout,
                                        int maxPiggyback,
                                        TimeSpan startupDelay,
                                        String clusterName) {
        return new SwimConfig(period, probeTimeout, indirectProbes, suspectTimeout,
                              maxPiggyback, startupDelay, clusterName, 0, DEFAULT_JOIN_GRACE);
    }

    /// Factory with all timing/probe parameters — clusterName defaults to empty (no gating).
    public static SwimConfig swimConfig(TimeSpan period,
                                        TimeSpan probeTimeout,
                                        int indirectProbes,
                                        TimeSpan suspectTimeout,
                                        int maxPiggyback,
                                        TimeSpan startupDelay) {
        return new SwimConfig(period, probeTimeout, indirectProbes, suspectTimeout,
                              maxPiggyback, startupDelay, "", 0, DEFAULT_JOIN_GRACE);
    }

    /// Factory with defaults for startupDelay.
    public static SwimConfig swimConfig(TimeSpan period,
                                        TimeSpan probeTimeout,
                                        int indirectProbes,
                                        TimeSpan suspectTimeout,
                                        int maxPiggyback) {
        return new SwimConfig(period, probeTimeout, indirectProbes, suspectTimeout,
                              maxPiggyback, timeSpan(10).seconds(), "", 0, DEFAULT_JOIN_GRACE);
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
    public static SwimConfig fromTimeouts(TimeSpan period,
                                          TimeSpan probeTimeout,
                                          TimeSpan suspectTimeout) {
        return new SwimConfig(period,
                              probeTimeout,
                              DEFAULT.indirectProbes(),
                              suspectTimeout,
                              DEFAULT.maxPiggyback(),
                              DEFAULT.startupDelay(),
                              DEFAULT.clusterName(),
                              DEFAULT.swimPortOffset(),
                              DEFAULT.joinGrace());
    }

    /// Derive a new config with the given cluster name.
    public SwimConfig withClusterName(String name) {
        return new SwimConfig(period, probeTimeout, indirectProbes, suspectTimeout,
                              maxPiggyback, startupDelay, name, swimPortOffset, joinGrace);
    }

    /// Derive a new config with the given SWIM port offset. The offset is added to
    /// a peer's primary (e.g. QUIC) port to derive its SWIM listen port. See
    /// [`#swimPortOffset()`].
    public SwimConfig withSwimPortOffset(int offset) {
        return new SwimConfig(period, probeTimeout, indirectProbes, suspectTimeout,
                              maxPiggyback, startupDelay, clusterName, offset, joinGrace);
    }

    /// Derive a new config with the given per-member join-grace window. See [`#joinGrace()`].
    public SwimConfig withJoinGrace(TimeSpan grace) {
        return new SwimConfig(period, probeTimeout, indirectProbes, suspectTimeout,
                              maxPiggyback, startupDelay, clusterName, swimPortOffset, grace);
    }
}
