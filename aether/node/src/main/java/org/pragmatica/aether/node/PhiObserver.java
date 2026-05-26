// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.pragmatica.aether.deployment.membership.PhiAccrualDetector;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.concurrent.CancellableTask;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// #231 Step 1 — OBSERVE-only, ALL-TO-ALL φ-accrual instrumentation. Now that ClusterSync
/// task-assignment registration is removed, the metrics ping-pong is quorum-driven all-to-all
/// again, so EVERY node feeds φ continuously from its own per-peer pong stream. This restores the
/// signal needed to (a) validate all-to-all is back and (b) move toward leaderless φ.
///
/// PURE INSTRUMENTATION: nothing here reads `suspected()`/`phi()` to drive any action — the only
/// effects are (a) `heartbeat` fed into the detector (every node) and (b) log lines. No
/// decommission, no disconnect, no reducer or 3-miss interaction. The mutable pong counter lives
/// here (NOT in the pure detector), keyed by peer.
///
/// The periodic tick logs the per-peer φ and the current role (LEADER vs follower) so leader and
/// follower streams can be compared in the logs. There is no longer any leadership-transition
/// reset — every node feeds continuously regardless of role.
///
/// Monotonic clock: `System.nanoTime() / 1_000_000L` — the detector's docstring requires a
/// monotonic millisecond source ("local monotonic only, arrivals measured at self"). The 3-miss
/// path (`ClusterSyncContext`) uses raw `System.nanoTime()`; this is the millisecond form of the
/// same monotonic source and is never shared with that path.
public final class PhiObserver {
    private static final Logger log = LoggerFactory.getLogger(PhiObserver.class);
    private static final TimeSpan LOG_INTERVAL = TimeSpan.timeSpan(1).seconds();

    private final NodeId self;
    private final PhiAccrualDetector detector;
    private final BooleanSupplier isLeader;
    private final Map<NodeId, AtomicLong> pongCounts;
    private final CancellableTask task;

    private PhiObserver(NodeId self, PhiAccrualDetector detector, BooleanSupplier isLeader) {
        this.self = self;
        this.detector = detector;
        this.isLeader = isLeader;
        this.pongCounts = new ConcurrentHashMap<>();
        this.task = CancellableTask.cancellableTask();
    }

    /// Construct an all-to-all observer with a default-tuned detector. `isLeader` is retained only
    /// for the periodic role log line.
    public static PhiObserver phiObserver(NodeId self, BooleanSupplier isLeader) {
        return new PhiObserver(self, PhiAccrualDetector.phiAccrualDetector(), isLeader);
    }

    /// Per-node pong listener: every node feeds the detector and bumps the peer's pong counter.
    @Contract
    public void onPong(NodeId peer) {
        detector.heartbeat(peer, nowMs());
        pongCounts.computeIfAbsent(peer, _ -> new AtomicLong())
                  .incrementAndGet();
    }

    /// Begin the periodic role-tick + per-peer φ logging.
    @Contract
    public void start() {
        task.set(SharedScheduler.scheduleAtFixedRate(this::tick, LOG_INTERVAL));
    }

    /// Cancel the periodic task (node shutdown).
    @Contract
    public void stop() {
        task.cancel();
    }

    private void tick() {
        logRole(isLeader.getAsBoolean());
        logAllPeers();
    }
    private void logRole(boolean leader) {
        log.info("[PHI-OBSERVE] self={} role={} trackedPeers={}",
                 self,
                 leader ? "LEADER" : "follower",
                 pongCounts.size());
    }

    private void logAllPeers() {
        var now = nowMs();
        pongCounts.forEach((peer, count) -> logPeer(peer, count.get(), now));
    }

    private void logPeer(NodeId peer, long count, long now) {
        log.info("[PHI-OBSERVE] self={} peer={} pongs={} phi={} suspected={}",
                 self,
                 peer,
                 count,
                 String.format("%.2f", detector.phi(peer, now)),
                 detector.suspected(peer, now));
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }
}
