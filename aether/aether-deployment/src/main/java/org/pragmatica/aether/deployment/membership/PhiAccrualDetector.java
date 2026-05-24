// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// Leaderless φ-accrual failure detector (Spike-1 core algorithm; issue #231, design in
/// `aether/docs/internal/membership-failure-detection-unification.md` §5E).
///
/// PURE ALGORITHM ONLY — no wiring, no I/O, no logging in the compute path, no wall-clock
/// reads. The caller supplies a monotonic `nowMs` at every entry point. Per §5E the clock is
/// "local monotonic only (arrivals measured at self) → no cross-node clock sync required".
///
/// Per peer it accumulates a bounded sliding window of pong inter-arrival intervals and derives
/// a continuous suspicion value φ. φ ≈ 0 just after a heartbeat and rises monotonically with
/// silence; one threshold (Φ_evict) converts it to a boolean. There is no vote to corrupt, so
/// the stale-CONNECTED flap that motivated the quorum aggregator cannot recur — silence is
/// silence (§5E).
///
/// Distribution model: §5E uses the classic Hayashibara normal-CDF approximation (same logistic
/// tail as Akka's `PhiAccrualFailureDetector`). The tail computation is isolated in a single
/// private method ([`PhiAccrualDetectorState#pLater`]) so it can be swapped for a heavy-tailed
/// model later — §5E flags that real RTT is often heavy-tailed and may need a different
/// distribution chosen from measured Spike-1 data.
///
/// Threading: backed by a [`ConcurrentHashMap`] keyed by peer; each peer's window is a private
/// object whose mutation and read are guarded by its own monitor. Heartbeats arrive from many
/// per-pong listener pool threads; `phi`/`suspected` are queried from another thread. Distinct
/// peers never contend; same-peer operations serialize on the per-peer monitor.
public interface PhiAccrualDetector {
    /// Record a heartbeat (pong) arrival for `peer` at monotonic `nowMs`. If a prior arrival
    /// exists, the interval `nowMs - lastArrival` is pushed into the bounded window; then
    /// `lastArrival` is updated. The first heartbeat from a peer only seeds `lastArrival`
    /// (no interval yet).
    @Contract
    void heartbeat(NodeId peer, long nowMs);

    /// Current suspicion level for `peer` at monotonic `nowMs`. Returns 0.0 when the peer is
    /// unknown or still in warmup (fewer than `minSamples` intervals). Otherwise
    /// `φ = -log10(P_later(elapsed))` where `elapsed = nowMs - lastArrival` and `P_later` is the
    /// tail probability of the modelled inter-arrival distribution.
    double phi(NodeId peer, long nowMs);

    /// Whether `peer` is suspected at monotonic `nowMs`: `phi(peer, nowMs) > threshold`.
    boolean suspected(NodeId peer, long nowMs);

    /// Drop all accumulated state for `peer` (e.g. on decommission). A subsequent `heartbeat`
    /// restarts warmup from zero.
    @Contract
    void forget(NodeId peer);

    /// Construct a detector with the supplied tuning parameters.
    static PhiAccrualDetector phiAccrualDetector(PhiAccrualConfig config) {
        return new PhiAccrualDetectorState(config, new ConcurrentHashMap<>());
    }

    /// Construct a detector with [`PhiAccrualConfig#DEFAULT`].
    static PhiAccrualDetector phiAccrualDetector() {
        return phiAccrualDetector(PhiAccrualConfig.DEFAULT);
    }
}

/// Mutable record-shaped implementation. See interface docs for the concurrency model.
record PhiAccrualDetectorState(PhiAccrualConfig config, Map<NodeId, PeerWindow> windows) implements PhiAccrualDetector {
    /// Smallest tail probability fed into `-log10`, capping φ at a finite value
    /// (`-log10(1e-9) = 9`) so unbounded silence never yields infinity.
    private static final double TINY_EPSILON = 1.0e-9;

    @Contract
    @Override
    public void heartbeat(NodeId peer, long nowMs) {
        windows.computeIfAbsent(peer, _ -> new PeerWindow(config.windowSize()))
               .record(nowMs);
    }

    @Override
    public double phi(NodeId peer, long nowMs) {
        return Option.option(windows.get(peer))
                     .map(window -> window.phi(nowMs, config.minSamples(), config.sigmaFloorMillis(), TINY_EPSILON))
                     .or(0.0);
    }

    @Override
    public boolean suspected(NodeId peer, long nowMs) {
        return phi(peer, nowMs) > config.threshold();
    }

    @Contract
    @Override
    public void forget(NodeId peer) {
        windows.remove(peer);
    }
}

/// Per-peer bounded sliding window of inter-arrival intervals plus the last-arrival timestamp.
/// Maintains running sum and sum-of-squares for O(1) mean/stddev. All access is serialized on
/// the instance monitor (see [`PhiAccrualDetector`] concurrency model).
final class PeerWindow {
    private final int capacity;
    private final ArrayDeque<Long> intervals;
    private long lastArrivalMs;
    private boolean hasArrival;
    private double sum;
    private double sumOfSquares;

    PeerWindow(int capacity) {
        this.capacity = capacity;
        this.intervals = new ArrayDeque<>(capacity);
    }

    synchronized void record(long nowMs) {
        if (hasArrival) {
            push(nowMs - lastArrivalMs);
        }
        lastArrivalMs = nowMs;
        hasArrival = true;
    }

    synchronized double phi(long nowMs, int minSamples, double sigmaFloorMillis, double tinyEpsilon) {
        if (!hasArrival || intervals.size() < minSamples) {
            return 0.0;
        }
        var count = intervals.size();
        var mean = sum / count;
        var stdDev = stdDev(count, mean, sigmaFloorMillis);
        var elapsed = (double) (nowMs - lastArrivalMs);
        var pLater = pLater(elapsed, mean, stdDev);

        return -Math.log10(Math.max(pLater, tinyEpsilon));
    }

    private double stdDev(int count, double mean, double sigmaFloorMillis) {
        var variance = Math.max(0.0, (sumOfSquares / count) - (mean * mean));

        return Math.max(Math.sqrt(variance), sigmaFloorMillis);
    }

    /// Hayashibara normal-CDF tail approximation (logistic form, as in Akka's
    /// PhiAccrualFailureDetector). Isolated here so the distribution is swappable for a
    /// heavy-tailed model per §5E.
    private static double pLater(double elapsed, double mean, double stdDev) {
        var y = (elapsed - mean) / stdDev;
        var e = Math.exp(-y * (1.5976 + 0.070566 * y * y));

        return elapsed > mean
               ? e / (1.0 + e)
               : 1.0 - 1.0 / (1.0 + e);
    }

    private void push(long interval) {
        if (intervals.size() == capacity) {
            var evicted = intervals.removeFirst();
            sum -= evicted;
            sumOfSquares -= (double) evicted * evicted;
        }
        intervals.addLast(interval);
        sum += interval;
        sumOfSquares += (double) interval * interval;
    }
}
