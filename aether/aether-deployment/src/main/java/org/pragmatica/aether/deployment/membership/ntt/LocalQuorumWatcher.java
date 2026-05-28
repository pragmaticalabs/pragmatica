// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;

import org.pragmatica.aether.deployment.membership.MembershipConfig;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.lang.utils.TimeSource;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// Per-node observer of local quorum visibility (membership v2 spec §4, §8.1 third bullet,
/// §12.5, I10). Tracks `localQuorumCount = connectedPeers + 1 (self)` against the simple
/// majority threshold `configuredCoreCount / 2 + 1` (same formula as
/// `TopologyManager.quorumSize() = clusterSize() / 2 + 1`, see
/// `ClusterTopologyManagerRecord.quorumThreshold` line 1200) and emits a
/// [`QuorumLossIntent`] when the count stays below threshold continuously for at least
/// `quorumLossDrainThreshold`.
///
/// **Mechanism only.** Runs on every node, observation-only at E1. The feature flag's gating
/// is upstream; LocalQuorumWatcher itself does not check the flag. At E1 the listener just
/// logs — Stage 6 wires it to the actual §8 drain procedure.
///
/// **Inputs.**
/// - [`#onPeerConnected`] — adds peer to tracked set, recomputes.
/// - [`#onPeerDisconnected`] — removes peer from tracked set, recomputes.
/// - [`#onConfiguredCoreCountChanged`] — updates threshold, recomputes.
///
/// **Output.** Single registered [`Consumer<QuorumLossIntent>`] receives at most one intent
/// per below-threshold window. The window's start is the first below-edge after a contiguous
/// above-period; recovery (count returns to ≥ threshold) closes the window and resets the
/// "first below" timestamp for the next window.
///
/// **Quorum count semantics.** `localQuorumCount = connectedPeers.size() + 1`. Self is implicit
/// — it is never inserted into `connectedPeers`. A duplicate `onPeerConnected(peer)` for an
/// already-tracked peer is a no-op (Set semantics); the same goes for `onPeerDisconnected`
/// on a peer that was not tracked.
///
/// **Initial-state firing suppression.** `configuredCoreCount` starts at `0` meaning "unknown
/// — do not fire". Until the wiring layer calls [`#onConfiguredCoreCountChanged`] with a value
/// `≥ 1`, no intent will ever be emitted regardless of the connected-peer count. This avoids
/// a spurious fire during bootstrap before cluster config is known.
///
/// **Concurrency.** All public methods are thread-safe. Mutation paths take a single intrinsic
/// lock around the transition-decision step (the compound update of the below-since timestamp
/// and the pending scheduled future is inherently coupled and far simpler to express as a
/// short critical section than as a multi-field CAS dance). The connected-peer set itself is
/// a `ConcurrentHashMap.newKeySet()`, the timestamp is an [`AtomicLong`], and the pending
/// future is an [`AtomicReference`]; these expose lock-free reads to the observability
/// accessors ([`#currentLocalQuorumCount`], [`#isBelowThreshold`], [`#belowThresholdSinceNanos`])
/// without taking the lock.
///
/// **Firing-check expiry guard.** When the scheduled task fires, it re-reads state under the
/// same lock and emits the intent only if (a) still below threshold AND (b) the
/// `belowThresholdSinceNanos` timestamp is still the one captured when the task was scheduled.
/// A state change in the window between scheduling and firing makes the task a no-op.
///
/// **Source citations.**
/// - Quorum formula: `ClusterTopologyManagerRecord.quorumThreshold` at
///   `aether/aether-deployment/.../ClusterTopologyManagerRecord.java:1200` —
///   `return configured / 2 + 1;`
/// - Connect/disconnect event source: [`org.pragmatica.consensus.net.quic.PeerConnectivityReporter#onPeerConnected`]
///   line 40 and `#onPeerDisconnected` line 33 in
///   `integrations/consensus/.../PeerConnectivityReporter.java` — wiring is Stage 6.
/// - `coreCount` accessor pattern: today's running code reads cluster size via
///   `TopologyManager.clusterSize()` (consumed by `ClusterTopologyManagerRecord` and
///   `SelfDrainCoordinator`'s `IntSupplier quorumSize`). LocalQuorumWatcher mirrors that
///   "push the configured size into the component" shape via
///   [`#onConfiguredCoreCountChanged`]; Stage 6 will wire it to the canonical
///   `ClusterConfigValue.coreCount` subscriber.
public final class LocalQuorumWatcher {
    private final MembershipConfig config;
    private final TimeSource timeSource;
    private final NttTimerScheduler scheduler;
    private final Set<NodeId> connectedPeers = ConcurrentHashMap.newKeySet();
    private final AtomicLong belowThresholdSinceNanos = new AtomicLong(Long.MIN_VALUE);
    private final AtomicReference<ScheduledFuture<?>> pendingFuture = new AtomicReference<>();
    private volatile int configuredCoreCount;
    private volatile Consumer<QuorumLossIntent> listener = LocalQuorumWatcher::ignoreIntent;

    private LocalQuorumWatcher(MembershipConfig config, TimeSource timeSource, NttTimerScheduler scheduler) {
        this.config = config;
        this.timeSource = timeSource;
        this.scheduler = scheduler;
    }

    /// Production factory bound to the process-wide [`SharedScheduler`] and the system clock.
    public static LocalQuorumWatcher localQuorumWatcher(MembershipConfig config) {
        return new LocalQuorumWatcher(config, TimeSource.system(), SharedScheduler::schedule);
    }

    /// Production factory with an explicit [`TimeSource`] (e.g. the node-wide HLC physical
    /// source).
    public static LocalQuorumWatcher localQuorumWatcher(MembershipConfig config, TimeSource timeSource) {
        return new LocalQuorumWatcher(config, timeSource, SharedScheduler::schedule);
    }

    /// Test factory accepting an explicit scheduler — required for deterministic firing
    /// without wall-clock advancement.
    public static LocalQuorumWatcher localQuorumWatcher(MembershipConfig config,
                                                        TimeSource timeSource,
                                                        NttTimerScheduler scheduler) {
        return new LocalQuorumWatcher(config, timeSource, scheduler);
    }

    /// Peer-connected input. Adds `peerId` to the tracked set (idempotent) and recomputes.
    @Contract
    public void onPeerConnected(NodeId peerId) {
        connectedPeers.add(peerId);
        recompute();
    }

    /// Peer-disconnected input. Removes `peerId` from the tracked set (idempotent if absent)
    /// and recomputes.
    @Contract
    public void onPeerDisconnected(NodeId peerId) {
        connectedPeers.remove(peerId);
        recompute();
    }

    /// Configured-cluster-size input. Updates the required threshold and recomputes.
    /// Values `< 1` are treated as "unknown" — firing is suppressed.
    @Contract
    public void onConfiguredCoreCountChanged(int newCoreCount) {
        configuredCoreCount = Math.max(0, newCoreCount);
        recompute();
    }

    /// Register the consumer that will receive emitted [`QuorumLossIntent`]s. At E1 the
    /// wiring layer's consumer just logs; a later stage replaces it with the §8 drain
    /// trigger.
    @Contract
    public void setQuorumLossListener(Consumer<QuorumLossIntent> newListener) {
        listener = newListener;
    }

    /// Observability — current `localQuorumCount` (self + connected peers).
    public int currentLocalQuorumCount() {
        return connectedPeers.size() + 1;
    }

    /// Observability — current required threshold derived from `configuredCoreCount`.
    /// Returns `0` while `configuredCoreCount` is unknown (firing suppressed).
    public int currentRequiredThreshold() {
        return requiredThresholdFor(configuredCoreCount);
    }

    /// Observability — whether the watcher is currently below threshold. Returns `false`
    /// while `configuredCoreCount` is unknown (no threshold to compare against yet).
    public boolean isBelowThreshold() {
        var threshold = currentRequiredThreshold();

        return threshold > 0 && currentLocalQuorumCount() < threshold;
    }

    /// Observability — `some(timestamp)` if currently below threshold (giving the monotonic
    /// nanos at which the current below-window started); `none()` otherwise.
    public Option<Long> belowThresholdSinceNanos() {
        var since = belowThresholdSinceNanos.get();

        return since == Long.MIN_VALUE ? none() : some(since);
    }

    private void recompute() {
        var threshold = requiredThresholdFor(configuredCoreCount);
        var quorumCount = currentLocalQuorumCount();
        var nowBelow = threshold > 0 && quorumCount < threshold;
        var now = timeSource.nanoTime();

        reconcileWindow(nowBelow, now);
    }

    private synchronized void reconcileWindow(boolean nowBelow, long now) {
        var wasBelow = belowThresholdSinceNanos.get() != Long.MIN_VALUE;

        if (nowBelow && !wasBelow) {
            enterBelowWindow(now);
        } else if (!nowBelow && wasBelow) {
            exitBelowWindow();
        }
    }

    private void enterBelowWindow(long now) {
        belowThresholdSinceNanos.set(now);
        scheduleFiringCheck(now);
    }

    private void exitBelowWindow() {
        belowThresholdSinceNanos.set(Long.MIN_VALUE);
        cancelPendingFuture();
    }

    private void scheduleFiringCheck(long windowStartNanos) {
        cancelPendingFuture();
        var future = scheduler.schedule(() -> onFiringCheck(windowStartNanos), config.quorumLossDrainThreshold());

        pendingFuture.set(future);
    }

    private void cancelPendingFuture() {
        var prev = pendingFuture.getAndSet(null);

        if (prev != null) {
            prev.cancel(false);
        }
    }

    private synchronized void onFiringCheck(long windowStartNanos) {
        if (belowThresholdSinceNanos.get() != windowStartNanos) {
            return;
        }

        var threshold = requiredThresholdFor(configuredCoreCount);
        var quorumCount = currentLocalQuorumCount();

        if (threshold <= 0 || quorumCount >= threshold) {
            return;
        }

        pendingFuture.set(null);

        var intent = QuorumLossIntent.quorumLossIntent(timeSource.nanoTime(), quorumCount, threshold);

        listener.accept(intent);
    }

    /// Same shape as `ClusterTopologyManagerRecord.quorumThreshold(int)` line 1200 —
    /// `configured / 2 + 1`. Returns `0` for unknown (`coreCount < 1`).
    private static int requiredThresholdFor(int configuredCoreCount) {
        return configuredCoreCount < 1 ? 0 : configuredCoreCount / 2 + 1;
    }

    @Contract
    private static void ignoreIntent(QuorumLossIntent intent) {
        // intentionally empty — default listener prior to wiring
    }
}
