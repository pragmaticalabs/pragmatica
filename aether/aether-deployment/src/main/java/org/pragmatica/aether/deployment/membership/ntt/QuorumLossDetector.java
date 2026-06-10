// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;

import org.pragmatica.aether.deployment.membership.MembershipConfig;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.lang.utils.TimeSource;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// Per-node observer of cluster quorum visibility (membership v2 spec §4, §8.1 third bullet,
/// §12.5, I10). Unlike its predecessor `LocalQuorumWatcher` — which independently counted
/// QUIC-connected peers — this detector consumes the member count from the single SWIM-fed
/// membership source (`PresenceSampler.currentMemberCount()`, which already includes self).
/// It tracks
/// that count against the simple-majority threshold `coreCount / 2 + 1` (same formula as
/// `TopologyManager.quorumSize() = clusterSize() / 2 + 1`) and emits a [`QuorumLossIntent`]
/// when the count stays below threshold continuously for at least `quorumLossDrainThreshold`.
///
/// **Mechanism only.** Runs on every node, observation-only at E1. The feature flag's gating
/// is upstream; QuorumLossDetector itself does not check the flag. At E1 the listener just
/// logs — Stage 6 wires it to the actual §8 drain procedure.
///
/// **Inputs.**
/// - [`#onMemberCountChanged`] — stores the latest member count (includes self) and recomputes.
/// - `coreCountSupplier` — the configured core size, read on each recompute to derive the
///   threshold.
///
/// **Output.** Single registered [`Consumer<QuorumLossIntent>`] receives at most one intent
/// per below-threshold window. The window's start is the first below-edge after a contiguous
/// above-period; recovery (count returns to ≥ threshold) closes the window and resets the
/// "first below" timestamp for the next window.
///
/// **Quorum count semantics.** The member count is supplied externally and already includes
/// self — the detector never adds an implicit `+1`. A repeated `onMemberCountChanged` with the
/// same value simply re-evaluates the window (no edge transition, so a no-op when the
/// below/above state is unchanged).
///
/// **Initial-state firing suppression.** `coreCountSupplier` returning `0` means "unknown — do
/// not fire". Until the wiring layer supplies a configured core size `≥ 1`, no intent will ever
/// be emitted regardless of the member count. This avoids a spurious fire during bootstrap
/// before cluster config is known.
///
/// **Concurrency.** All public methods are thread-safe. Mutation paths take a single intrinsic
/// lock around the transition-decision step (the compound update of the below-since timestamp
/// and the pending scheduled future is inherently coupled and far simpler to express as a
/// short critical section than as a multi-field CAS dance). The member count is a `volatile
/// int`, the timestamp is an [`AtomicLong`], and the pending future is an [`AtomicReference`];
/// these expose lock-free reads to the observability accessors ([`#currentMemberCount`],
/// [`#isBelowThreshold`], [`#belowThresholdSinceNanos`]) without taking the lock.
///
/// **Firing-check expiry guard.** When the scheduled task fires, it re-reads state under the
/// same lock and emits the intent only if (a) the detector is armed AND (b) still below
/// threshold AND (c) the `belowThresholdSinceNanos` timestamp is still the one captured when
/// the task was scheduled. A state change in the window between scheduling and firing makes
/// the task a no-op.
///
/// **Arm-after-first-quorum latch (safety-critical).** `armed` starts `false` and is set
/// `true` — permanently, never reset — the first time the member count reaches quorum
/// (`threshold > 0 && memberCount >= threshold`). The firing check is suppressed while
/// unarmed. Rationale: a node booting into a forming/minority cluster (never yet quorate)
/// must never self-drain — that is normal cold-start, not quorum loss. Only a node that WAS
/// quorate and then dropped below quorum should drain; `armed` captures exactly "has the
/// cluster ever been quorate from this node's view".
public final class QuorumLossDetector {
    private final MembershipConfig config;
    private final IntSupplier coreCountSupplier;
    private final TimeSource timeSource;
    private final NttTimerScheduler scheduler;
    private final AtomicLong belowThresholdSinceNanos = new AtomicLong(Long.MIN_VALUE);
    private final AtomicReference<ScheduledFuture<?>> pendingFuture = new AtomicReference<>();
    private volatile int memberCount;
    private volatile boolean armed;
    private volatile Consumer<QuorumLossIntent> listener = QuorumLossDetector::ignoreIntent;

    private QuorumLossDetector(MembershipConfig config,
                               IntSupplier coreCountSupplier,
                               TimeSource timeSource,
                               NttTimerScheduler scheduler) {
        this.config = config;
        this.coreCountSupplier = coreCountSupplier;
        this.timeSource = timeSource;
        this.scheduler = scheduler;
    }

    /// Production factory bound to the process-wide [`SharedScheduler`] and the system clock.
    public static QuorumLossDetector quorumLossDetector(MembershipConfig config, IntSupplier coreCountSupplier) {
        return new QuorumLossDetector(config, coreCountSupplier, TimeSource.system(), SharedScheduler::schedule);
    }

    /// Production factory with an explicit [`TimeSource`] (e.g. the node-wide HLC physical
    /// source).
    public static QuorumLossDetector quorumLossDetector(MembershipConfig config,
                                                        IntSupplier coreCountSupplier,
                                                        TimeSource timeSource) {
        return new QuorumLossDetector(config, coreCountSupplier, timeSource, SharedScheduler::schedule);
    }

    /// Test factory accepting an explicit scheduler — required for deterministic firing
    /// without wall-clock advancement.
    public static QuorumLossDetector quorumLossDetector(MembershipConfig config,
                                                        IntSupplier coreCountSupplier,
                                                        TimeSource timeSource,
                                                        NttTimerScheduler scheduler) {
        return new QuorumLossDetector(config, coreCountSupplier, timeSource, scheduler);
    }

    /// Member-count input. Stores the latest count (sourced from the SWIM-fed membership
    /// tracker, which already includes self) and recomputes.
    @Contract
    public void onMemberCountChanged(int newMemberCount) {
        memberCount = newMemberCount;
        recompute();
    }

    /// Register the consumer that will receive emitted [`QuorumLossIntent`]s. At E1 the
    /// wiring layer's consumer just logs; a later stage replaces it with the §8 drain
    /// trigger.
    @Contract
    public void setQuorumLossListener(Consumer<QuorumLossIntent> newListener) {
        listener = newListener;
    }

    /// Observability — current member count (as last supplied; includes self).
    public int currentMemberCount() {
        return memberCount;
    }

    /// Observability — whether the detector has ever observed a quorate member count. Once
    /// armed it never resets; the firing check is suppressed while unarmed (cold-start guard).
    public boolean isArmed() {
        return armed;
    }

    /// Observability — current required threshold derived from the configured core count.
    /// Returns `0` while the core count is unknown (firing suppressed).
    public int currentRequiredThreshold() {
        return requiredThresholdFor(coreCountSupplier.getAsInt());
    }

    /// Observability — whether the detector is currently below threshold. Returns `false`
    /// while the core count is unknown (no threshold to compare against yet).
    public boolean isBelowThreshold() {
        var threshold = currentRequiredThreshold();

        return threshold > 0 && currentMemberCount() < threshold;
    }

    /// Observability — `some(timestamp)` if currently below threshold (giving the monotonic
    /// nanos at which the current below-window started); `none()` otherwise.
    public Option<Long> belowThresholdSinceNanos() {
        var since = belowThresholdSinceNanos.get();

        return since == Long.MIN_VALUE
               ? none()
               : some(since);
    }

    private void recompute() {
        var threshold = requiredThresholdFor(coreCountSupplier.getAsInt());
        var quorumCount = currentMemberCount();
        var nowBelow = threshold > 0 && quorumCount < threshold;
        var now = timeSource.nanoTime();

        if (threshold > 0 && quorumCount >= threshold) {
            armed = true;
        }

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

        var threshold = requiredThresholdFor(coreCountSupplier.getAsInt());
        var quorumCount = currentMemberCount();

        if (!armed || threshold <= 0 || quorumCount >= threshold) {
            return;
        }

        pendingFuture.set(null);
        var intent = QuorumLossIntent.quorumLossIntent(timeSource.nanoTime(), quorumCount, threshold);

        listener.accept(intent);
    }

    /// Same shape as `ClusterTopologyManagerRecord.quorumThreshold(int)` — `configured / 2 + 1`.
    /// Returns `0` for unknown (`coreCount < 1`).
    private static int requiredThresholdFor(int coreCount) {
        return coreCount < 1
               ? 0
               : coreCount / 2 + 1;
    }

    @Contract
    private static void ignoreIntent(QuorumLossIntent intent) {
    // intentionally empty — default listener prior to wiring
    }
}
