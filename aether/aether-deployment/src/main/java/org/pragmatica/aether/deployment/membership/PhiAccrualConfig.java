// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership;

/// Tuning parameters for [`PhiAccrualDetector`] (Spike-1, issue #231; design in
/// `aether/docs/internal/membership-failure-detection-unification.md` §5E).
///
/// All durations are in milliseconds. These are tuning parameters per the project
/// configurability rule — callers may construct cluster-specific instances; [`#DEFAULT`]
/// carries the §5E sketch values.
///
/// @param windowSize       K — bounded sliding window of inter-arrival intervals retained
///                         per peer (§5E: "K ~ 100"). Older intervals are evicted FIFO.
/// @param minSamples       K_min — warmup floor. φ is forced to 0 until a peer has at least
///                         this many inter-arrival samples, so a barely-heard peer is never
///                         suspected (§5E coverage split / cold-start S15/S16). Default 8 ≈
///                         8s of 1s-cadence history: large enough that μ/σ are statistically
///                         meaningful (a 2-3 sample window yields wildly unstable σ and would
///                         produce false suspicion spikes), small enough to be usable shortly
///                         after a peer first connects.
/// @param sigmaFloorMillis σ floor. The window stddev is clamped to at least this value before
///                         computing the tail, preventing divide-by-zero and over-tight
///                         suspicion on near-constant intervals (§5E "generous σ"). The floor is
///                         CADENCE-AWARE: at the 1s ping cadence a single arrival that lands one
///                         whole cadence late (≈+1000ms past μ) must read as a modest surprise,
///                         NOT a near-certain death. With a 50ms floor that late arrival is ≈20σ
///                         → φ saturates at the 9.0 cap on a SINGLE missed cadence, so one
///                         GC/scheduler hiccup or a single dropped pong instantly crosses
///                         Φ_evict=8.0. A 200ms floor turns the same late arrival into ≈1σ — a
///                         barely-surprising blip — so transient jitter, brief stalls, and the
///                         heavier tail/loss of cloud links no longer trip eviction; sustained
///                         multi-cadence silence still climbs past Φ_evict as elapsed grows.
/// @param threshold        Φ_evict — suspicion level above which [`PhiAccrualDetector#suspected`]
///                         returns true (§5E: "e.g. 8").
public record PhiAccrualConfig(int windowSize, int minSamples, double sigmaFloorMillis, double threshold) {
    /// Reference configuration: K=100, K_min=8, σ-floor=200ms (cadence-aware, see
    /// `sigmaFloorMillis`), Φ_evict=8.0. The σ-floor was raised from the §5E sketch's 50ms to
    /// 200ms so a single ~1-cadence-late pong reads as ≈1σ rather than ≈20σ, giving the leader-side
    /// actuator (`PhiObserver`) headroom for cloud jitter and packet loss before a peer is ever
    /// considered suspected on any single tick.
    public static final PhiAccrualConfig DEFAULT = new PhiAccrualConfig(100, 8, 200.0, 8.0);
}
