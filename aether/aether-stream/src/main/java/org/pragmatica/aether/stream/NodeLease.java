// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLeaseKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLeaseValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.concurrent.CancellableTask;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// The node lease of #2981 (design §2.1, §4): one lease per node, covering every CF>=2 partition the node owns.
///
/// **Renewal.** Every renewal period the node takes `t_s = nanoTime()` and submits
/// `Put(NodeLeaseKey(self), NodeLeaseValue(seq))`. When that `seq` is DELIVERED through the store's ordered notification
/// path (never from the `Put` promise, whose completion runs on another executor after delivery, and never for a replay
/// item), `expiry = max(expiry, t_s + T)`. `expiry` is monotonic time and never decreases; revocation is a separate flag.
/// Every delivered renewal of any node also records `renewalApplied[submitter] = nanoTime()`.
///
/// **Validity** ([#valid]) reads `nanoTime()` first, then the state: `!revoked && now < expiry`, plus the A-CLK-2
/// cross-check: if wall time advanced more than `T` beyond monotonic time since the anchoring renewal, the monotonic
/// clock stopped (host suspend) and the lease is revoked at once. `revoked` is cleared only by the delivery of an own
/// renewal SUBMITTED after the revocation (NEW-3), so a renewal in flight across a freeze cannot reopen the hole.
///
/// The renewal loop never consults validity: a revoked node keeps renewing, which is how it regains the lease.
///
/// Step 2 of #2981 wires it (`StreamAssembly.nodeLease`): the renewal route, the `reset`/`replayStart` revoke, the operator
/// events, and the owner checks of `StreamPartitionManager` and `OwnerActivation` through [OwnerLease].
public interface NodeLease extends OwnerLease {
    /// Whether the lease is valid at this instant. Runs the A-CLK-2 cross-check and reports a lapse to [Events] once per
    /// lapse.
    @Override
    boolean valid();

    /// Registers a listener told, on the delivering thread, every time an own renewal turns an invalid lease valid (the renewal
    /// edge of design §2.2/§2.4: parked acks are re-checked and `visible` is re-derived). Registration appends.
    @Contract
    void onValidityRegained(Runnable listener);

    /// Revokes the lease now (`reset`, `replayStart`); re-stamps the revocation time on every call, so only a renewal
    /// submitted after the LATEST revocation clears it.
    @Contract
    void revoke();

    /// When the newest live (non-replay) renewal of `node` was delivered here, on this node's monotonic clock.
    Option<Long> renewalApplied(NodeId node);

    /// One round of the renewal loop: lapse detection, then submit the next renewal.
    @Contract
    void renew();

    /// The ordered-delivery handler for renewals; must run on the store's notification delivery thread.
    @Contract
    void onRenewal(ValuePut<NodeLeaseKey, NodeLeaseValue> put);

    /// Starts the periodic renewal loop.
    @Contract
    void start();

    /// Stops the periodic renewal loop.
    @Contract
    void stop();

    /// Why a lease stopped being valid.
    enum LapseReason {
        /// No own renewal was delivered in time: `now >= expiry`.
        EXPIRED,
        /// The A-CLK-2 cross-check found wall time ahead of monotonic time by more than `T` (a stopped monotonic clock).
        CLOCK_SUSPEND
    }

    /// The node's two clocks, a seam for tests.
    interface LeaseClock {
        long nanoTime();
        long wallMillis();

        static LeaseClock systemClock() {
            return SystemLeaseClock.INSTANCE;
        }
    }

    /// Where lapses and clock-rate divergence, and their recoveries, are raised. Production routes them to the operator events
    /// `STREAM_OWNER_LEASE_LAPSED`/`_RESTORED` and `NODE_CLOCK_RATE_DIVERGENCE`/`_NORMAL` (`StreamAssembly.leaseEvents`).
    interface Events {
        @Contract
        void lapsed(LapseReason reason, long nanos);

        /// The lease is valid again after a reported lapse (once per reported lapse).
        @Contract
        void restored();

        @Contract
        void clockRateDivergence(long monotonicNanos, long wallNanos);

        /// The clock rates agree again after a reported divergence (once per divergence episode).
        @Contract
        void clockRateNormal();

        static Events loggingEvents() {
            return LoggingEvents.INSTANCE;
        }
    }

    /// Creates a lease for `self`. `duration` is `T`, `renewalInterval` is `R`, `rho` the tolerated clock-rate divergence
    /// (validated by the streaming configuration, step 4). `applier` submits the renewal; `replaying` tells whether the
    /// notification being delivered is a replay item.
    static NodeLease nodeLease(NodeId self,
                               TimeSpan duration,
                               TimeSpan renewalInterval,
                               double rho,
                               Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                               BooleanSupplier replaying,
                               LeaseClock clock,
                               Events events) {
        return new LiveNodeLease(self,
                                 duration.nanos(),
                                 renewalInterval,
                                 rho,
                                 applier,
                                 replaying,
                                 clock,
                                 events,
                                 new AtomicReference<>(LeaseState.initial(clock.nanoTime())),
                                 new ConcurrentHashMap<>(),
                                 new ConcurrentHashMap<>(),
                                 new AtomicLong(TimeUnit.MILLISECONDS.toMicros(clock.wallMillis())),
                                 CancellableTask.cancellableTask(),
                                 new AtomicBoolean(),
                                 new AtomicBoolean(),
                                 new CopyOnWriteArrayList<>());
    }

    /// The two clock readings taken when a renewal is submitted.
    record Anchor(long monotonicNanos, long wallMillis) {}

    /// The lease state, replaced atomically as a whole. `anchor*` is the submission point of the renewal that last moved
    /// the anchor (the A-CLK-2 reference); `lapseReported` keeps a lapse from being reported twice.
    record LeaseState(long expiry,
                      boolean revoked,
                      long revokedAt,
                      boolean anchored,
                      long anchorNanos,
                      long anchorWallMillis,
                      boolean lapseReported) {
        /// A new lease is revoked as of its creation: only a renewal submitted after it grants anything.
        static LeaseState initial(long now) {
            return new LeaseState(now, true, now, false, now, 0, true);
        }

        boolean validAt(long now) {
            return ! revoked && now - expiry < 0;
        }

        /// How far wall time ran ahead of monotonic time since the anchor (A-CLK-2).
        long suspendGap(long now, long wallMillis) {
            return TimeUnit.MILLISECONDS.toNanos(wallMillis - anchorWallMillis) - (now - anchorNanos);
        }

        /// Re-stamps the revocation; `revokedAt` never moves back, so concurrent revokes commit in any order (C4(a)).
        LeaseState revokedAt(long now) {
            var stamp = revoked && now - revokedAt < 0
                        ? revokedAt
                        : now;

            return new LeaseState(expiry, true, stamp, anchored, anchorNanos, anchorWallMillis, lapseReported);
        }

        LeaseState lapseNoted() {
            return new LeaseState(expiry, revoked, revokedAt, anchored, anchorNanos, anchorWallMillis, true);
        }

        /// The delivery of an own renewal submitted at `anchor`. While revoked, only a renewal submitted after the
        /// revocation counts (NEW-3); `expiry` never decreases; the anchor only moves forward.
        LeaseState extendedBy(Anchor anchor, long duration) {
            if (revoked && anchor.monotonicNanos() - revokedAt <= 0) {
                return this;
            }

            var candidate = anchor.monotonicNanos() + duration;
            var newExpiry = candidate - expiry > 0
                            ? candidate
                            : expiry;
            var moveAnchor = !anchored || anchor.monotonicNanos() - anchorNanos > 0;

            return new LeaseState(newExpiry,
                                  false,
                                  revokedAt,
                                  true,
                                  moveAnchor
                                  ? anchor.monotonicNanos()
                                  : anchorNanos,
                                  moveAnchor
                                  ? anchor.wallMillis()
                                  : anchorWallMillis,
                                  lapseReported && newExpiry == expiry && !revoked);
        }

        boolean anchorMovedFrom(LeaseState before) {
            return anchored
                   && before.anchored
                   && anchorNanos - before.anchorNanos > 0;
        }
    }
}

enum SystemLeaseClock implements NodeLease.LeaseClock {
    INSTANCE;
    @Override
    public long nanoTime() {
        return System.nanoTime();
    }
    @Override
    public long wallMillis() {
        return System.currentTimeMillis();
    }
}

enum LoggingEvents implements NodeLease.Events {
    INSTANCE;
    private static final Logger log = LoggerFactory.getLogger(NodeLease.class);
    @Contract
    @Override
    public void lapsed(NodeLease.LapseReason reason, long nanos) {
        log.warn("Node lease lapsed ({}), {} ms", reason, TimeUnit.NANOSECONDS.toMillis(nanos));
    }
    @Contract
    @Override
    public void restored() {
        log.info("Node lease restored");
    }
    @Contract
    @Override
    public void clockRateDivergence(long monotonicNanos, long wallNanos) {
        log.warn("Node clock rate divergence: monotonic advanced {} ns while wall advanced {} ns",
                 monotonicNanos,
                 wallNanos);
    }
    @Contract
    @Override
    public void clockRateNormal() {
        log.info("Node clock rates agree again");
    }
}

record LiveNodeLease(NodeId self,
                     long durationNanos,
                     TimeSpan renewalInterval,
                     double rho,
                     Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                     BooleanSupplier replaying,
                     NodeLease.LeaseClock clock,
                     NodeLease.Events events,
                     AtomicReference<NodeLease.LeaseState> state,
                     Map<Long, NodeLease.Anchor> pending,
                     Map<NodeId, Long> applied,
                     AtomicLong seq,
                     CancellableTask task,
                     AtomicBoolean lapseRaised,
                     AtomicBoolean divergenceRaised,
                     List<Runnable> regainedListeners) implements NodeLease {
    private static final Logger log = LoggerFactory.getLogger(LiveNodeLease.class);

    @Override
    public boolean valid() {
        // Read order: monotonic, then wall. A pause between the two reads inflates the gap, a spurious revoke that fails
        // closed and clears with the next renewal.
        var now = clock.nanoTime();
        var wallMillis = clock.wallMillis();
        var current = state.get();

        if (current.revoked()) {
            return false;
        }

        var gap = current.suspendGap(now, wallMillis);

        if (gap > durationNanos) {
            return revokeOnSuspend(current, now, gap);
        }

        return current.validAt(now) || noteLapse(current, now);
    }

    @Contract
    @Override
    public void revoke() {
        var now = clock.nanoTime();

        state.updateAndGet(current -> current.revokedAt(now));
    }

    @Contract
    @Override
    public void onValidityRegained(Runnable listener) {
        regainedListeners.add(listener);
    }

    @Override
    public Option<Long> renewalApplied(NodeId node) {
        return Option.option(applied.get(node));
    }

    /// The renewal failure is absorbed here, FER: the next round submits a fresh `seq`, and a renewal that keeps failing
    /// surfaces as the lapse this round's detection reports.
    @Contract
    @Override
    public void renew() {
        valid();
        var anchor = new NodeLease.Anchor(clock.nanoTime(), clock.wallMillis());
        var next = seq.incrementAndGet();

        pending.values().removeIf(old -> anchor.monotonicNanos() - (old.monotonicNanos() + durationNanos) >= 0);
        pending.put(next, anchor);
        applier.apply(List.of(new KVCommand.Put<AetherKey, AetherValue>(NodeLeaseKey.nodeLeaseKey(self),
                                                                        NodeLeaseValue.nodeLeaseValue(next))))
               .onFailure(cause -> log.debug("Node lease renewal {} not applied: {}",
                                             next,
                                             cause.message()));
    }

    @Contract
    @Override
    public void onRenewal(ValuePut<NodeLeaseKey, NodeLeaseValue> put) {
        if (replaying.getAsBoolean()) {
            return;
        }

        var submitter = put.cause().key().nodeId();

        applied.put(submitter, clock.nanoTime());
        if (submitter.equals(self)) {
            applyOwn(put.cause().value().seq());
        }
    }

    @Contract
    @Override
    public void start() {
        task.set(SharedScheduler.scheduleAtFixedRate(this::renew, renewalInterval));
    }

    @Contract
    @Override
    public void stop() {
        task.cancel();
    }

    @Contract
    private void applyOwn(long delivered) {
        Option.option(pending.remove(delivered)).onPresent(this::extendFrom);
    }

    @Contract
    private void extendFrom(NodeLease.Anchor anchor) {
        var before = state.getAndUpdate(current -> current.extendedBy(anchor, durationNanos));
        var after = before.extendedBy(anchor, durationNanos);
        var now = clock.nanoTime();

        if (after.anchorMovedFrom(before)) {
            checkRate(after.anchorNanos() - before.anchorNanos(),
                      TimeUnit.MILLISECONDS.toNanos(after.anchorWallMillis() - before.anchorWallMillis()));
        }
        if (!before.validAt(now) && after.validAt(now)) {
            regained();
        }
    }

    /// The renewal edge: the recovery event once per reported lapse, then every regained listener.
    @Contract
    private void regained() {
        if (lapseRaised.compareAndSet(true, false)) {
            events.restored();
        }
        regainedListeners.forEach(Runnable::run);
    }

    @Contract
    private void checkRate(long monotonicNanos, long wallNanos) {
        if (Math.abs(wallNanos - monotonicNanos) > rho * monotonicNanos) {
            divergenceRaised.set(true);
            events.clockRateDivergence(monotonicNanos, wallNanos);
        } else if (divergenceRaised.compareAndSet(true, false)) {
            events.clockRateNormal();
        }
    }

    private boolean revokeOnSuspend(NodeLease.LeaseState current, long now, long gap) {
        if (state.compareAndSet(current, current.revokedAt(now))) {
            lapseRaised.set(true);
            events.lapsed(NodeLease.LapseReason.CLOCK_SUSPEND, gap);
        }

        return false;
    }

    private boolean noteLapse(NodeLease.LeaseState current, long now) {
        if (!current.lapseReported() && state.compareAndSet(current, current.lapseNoted())) {
            lapseRaised.set(true);
            events.lapsed(NodeLease.LapseReason.EXPIRED, now - current.expiry());
        }

        return false;
    }
}
