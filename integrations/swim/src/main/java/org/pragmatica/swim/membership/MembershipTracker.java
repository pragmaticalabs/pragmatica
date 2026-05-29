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

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.MembershipView;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.swim.HealthSnapshot;
import org.pragmatica.swim.SwimHealth;
import org.pragmatica.swim.SwimObservation;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;

/// The single SWIM-fed membership source (membership-unification-spec §2, §4).
///
/// Replaces `NodeTopologyTracker`'s leaky instantaneous-set + N per-node timers with a
/// single deterministic **sample tick** plus **per-node hysteresis on the set**:
///
/// - On each tick the tracker recomputes the *candidate live set* from the current SWIM
///   liveness snapshot, biased by QUIC connect/disconnect hints (a disconnect makes a
///   node "likely gone" for that sample, a reconnect "likely back" — hints DO NOT bypass
///   debounce, they only colour the per-sample observation).
/// - A node ENTERS the stable member set after `upHysteresis` consecutive present samples
///   and LEAVES after `downHysteresis` consecutive absent samples. Identity-preserving:
///   counters are kept per `NodeId`.
/// - When the stable set changes, exactly one [`MembershipChange`] delta is emitted per
///   stable transition (diffed against the last-emitted set).
///
/// `self` is always a member (self-seed) and can never leave.
///
/// Implements [`MembershipView`] as-is so consensus's `TopologyObserver` can read
/// membership/quorum from the tracker via a `GenerationSnapshotSource` adapter in a later
/// phase (the consensus interface needs no change). Per spec §2/D4, per-member
/// READY/DRAINING state is a deployment concern not yet modelled here, so `onDutyMemberIds`
/// mirrors the full stable member set.
///
/// Injectable for tests: clock (`nowNanos`), scheduler (the periodic tick is driven by an
/// explicit [`#sample`] call so tests step the FSM deterministically without a real
/// scheduler), K up/down + sample interval (via [`MembershipTrackerConfig`]), and the
/// quorum threshold (`quorumThreshold` supplier).
public final class MembershipTracker implements MembershipView {
    private static final Logger log = LoggerFactory.getLogger(MembershipTracker.class);

    /// Transient per-sample QUIC bias for a node.
    private enum QuicBias { NONE, PRESENT, ABSENT }

    private final NodeId self;
    private final MembershipTrackerConfig config;
    private final Supplier<HealthSnapshot> healthSupplier;
    /// Configured core size (e.g. 5). Quorum threshold is derived as the majority
    /// `coreSize/2 + 1`. A supplier (not a constant) so operator-driven `SetClusterSize`
    /// can move the bar at runtime without rebuilding the tracker.
    private final IntSupplier coreSize;
    private final LongSupplier nowNanos;
    private final MembershipListener listener;

    /// Per-node consecutive-sample counters. Positive = up-streak, negative = down-streak.
    /// A node not present here has never been sampled.
    private final Map<NodeId, Integer> streaks = new ConcurrentHashMap<>();

    /// Per-node QUIC hint applied to the NEXT sample only. Consumed (reset to NONE) on read.
    private final Map<NodeId, QuicBias> quicBias = new ConcurrentHashMap<>();

    /// Stable member set (debounced). Always contains self.
    private final Set<NodeId> stableMembers = ConcurrentHashMap.newKeySet();

    /// Last-emitted set — the delta baseline. Distinct from `stableMembers` so a delta is
    /// computed exactly once per transition even across concurrent reads.
    private final AtomicReference<Set<NodeId>> lastEmitted;

    private final AtomicBoolean everQuorate = new AtomicBoolean(false);
    private final AtomicReference<Option<ScheduledFuture<?>>> tickFuture = new AtomicReference<>(Option.none());
    private final Object sampleLock = new Object();

    private MembershipTracker(NodeId self,
                              MembershipTrackerConfig config,
                              Supplier<HealthSnapshot> healthSupplier,
                              IntSupplier coreSize,
                              LongSupplier nowNanos,
                              MembershipListener listener) {
        this.self = self;
        this.config = config;
        this.healthSupplier = healthSupplier;
        this.coreSize = coreSize;
        this.nowNanos = nowNanos;
        this.listener = listener;
        this.stableMembers.add(self);
        this.lastEmitted = new AtomicReference<>(Set.of(self));
    }

    /// Production factory: periodic tick bound to the process-wide [`SharedScheduler`],
    /// `System::nanoTime` clock.
    public static MembershipTracker membershipTracker(NodeId self,
                                                      MembershipTrackerConfig config,
                                                      Supplier<HealthSnapshot> healthSupplier,
                                                      IntSupplier coreSize,
                                                      MembershipListener listener) {
        return new MembershipTracker(self, config, healthSupplier, coreSize, System::nanoTime, listener);
    }

    /// Test factory: explicit injected clock; the periodic tick is NOT scheduled — tests
    /// drive the FSM by calling [`#sample`] directly for deterministic, scheduler-free
    /// assertions.
    public static MembershipTracker membershipTracker(NodeId self,
                                                       MembershipTrackerConfig config,
                                                       Supplier<HealthSnapshot> healthSupplier,
                                                       IntSupplier coreSize,
                                                       LongSupplier nowNanos,
                                                       MembershipListener listener) {
        return new MembershipTracker(self, config, healthSupplier, coreSize, nowNanos, listener);
    }

    /// Start the periodic sample tick on the shared scheduler. Idempotent — a second
    /// `start()` while already running is a no-op.
    @Contract
    public void start() {
        var future = SharedScheduler.scheduleAtFixedRate(this::sample, config.sampleInterval());
        if (!tickFuture.compareAndSet(Option.none(), some(future))) {
            future.cancel(false);
        }
    }

    /// Cancel the periodic sample tick. Idempotent.
    @Contract
    public void stop() {
        tickFuture.getAndSet(Option.none())
                  .onPresent(future -> future.cancel(false));
    }

    /// QUIC reconnect hint — biases the next sample toward PRESENT for `peer`. Membership
    /// still flips only on hysteresis; the hint colours one sample, it does not bypass the
    /// debounce window.
    @Contract
    public void onQuicReconnect(NodeId peer) {
        if (!peer.equals(self)) {
            quicBias.put(peer, QuicBias.PRESENT);
        }
    }

    /// QUIC disconnect hint — biases the next sample toward ABSENT for `peer` (fast
    /// "likely gone" signal). Still gated by `downHysteresis` before membership flips.
    @Contract
    public void onQuicDisconnect(NodeId peer) {
        if (!peer.equals(self)) {
            quicBias.put(peer, QuicBias.ABSENT);
        }
    }

    /// Optional convenience entry point for SWIM push observations: a `HealthyObserved`
    /// biases the next sample PRESENT, a `FaultyObserved` / `DepartedObserved` biases it
    /// ABSENT. The authoritative liveness still comes from the snapshot supplier on the
    /// tick — this only sharpens the very next sample.
    @Contract
    public void onSwimObservation(SwimObservation observation) {
        switch (observation) {
            case SwimObservation.HealthyObserved healthy -> onQuicReconnect(healthy.peer());
            case SwimObservation.FaultyObserved faulty -> onQuicDisconnect(faulty.peer());
            case SwimObservation.DepartedObserved departed -> onQuicDisconnect(departed.peer());
            default -> { }
        }
    }

    /// One deterministic sample tick: recompute the candidate live set, advance per-node
    /// hysteresis counters, flip the stable set on threshold crossings, and emit at most
    /// one [`MembershipChange`] delta. Public so tests can step the FSM without a scheduler.
    @Contract
    public void sample() {
        synchronized (sampleLock) {
            sampleLocked();
        }
    }

    private void sampleLocked() {
        var candidates = candidateLiveSet();
        advanceStreaks(candidates);
        emitIfChanged();
    }

    /// Candidate live set for this sample = self ∪ SWIM-healthy peers, then overridden by
    /// any pending QUIC bias (PRESENT forces in, ABSENT forces out). Bias is consumed.
    private Set<NodeId> candidateLiveSet() {
        var live = new HashSet<NodeId>();
        live.add(self);
        healthSupplier.get()
                      .peerHealth()
                      .forEach((peer, health) -> addIfHealthy(live, peer, health));
        applyQuicBias(live);
        return live;
    }

    private void addIfHealthy(Set<NodeId> live, NodeId peer, SwimHealth health) {
        if (health == SwimHealth.HEALTHY) {
            live.add(peer);
        }
    }

    private void applyQuicBias(Set<NodeId> live) {
        var biases = Map.copyOf(quicBias);
        biases.forEach((peer, bias) -> applyOneBias(live, peer, bias));
        biases.keySet().forEach(quicBias::remove);
    }

    private void applyOneBias(Set<NodeId> live, NodeId peer, QuicBias bias) {
        switch (bias) {
            case PRESENT -> live.add(peer);
            case ABSENT -> removeUnlessSelf(live, peer);
            case NONE -> { }
        }
    }

    private void removeUnlessSelf(Set<NodeId> live, NodeId peer) {
        if (!peer.equals(self)) {
            live.remove(peer);
        }
    }

    /// Advance each candidate's up-streak and each known-but-absent node's down-streak,
    /// then apply threshold crossings to `stableMembers`. Self never leaves.
    private void advanceStreaks(Set<NodeId> candidates) {
        var known = new HashSet<NodeId>();
        known.addAll(streaks.keySet());
        known.addAll(stableMembers);
        known.addAll(candidates);
        known.remove(self);
        known.forEach(node -> advanceOne(node, candidates.contains(node)));
    }

    private void advanceOne(NodeId node, boolean present) {
        var next = nextStreak(streaks.getOrDefault(node, 0), present);
        streaks.put(node, next);
        if (present && next >= config.upHysteresis()) {
            stableMembers.add(node);
        } else if (!present && (-next) >= config.downHysteresis()) {
            stableMembers.remove(node);
        }
    }

    /// A present sample drives the streak non-negative then increments; an absent sample
    /// drives it non-positive then decrements. Direction switches reset to the first step
    /// of the new direction (so a flap does not accumulate across directions).
    private static int nextStreak(int current, boolean present) {
        if (present) {
            return current < 0 ? 1 : current + 1;
        }
        return current > 0 ? -1 : current - 1;
    }

    private void emitIfChanged() {
        var current = Set.copyOf(stableMembers);
        var previous = lastEmitted.getAndSet(current);
        if (current.equals(previous)) {
            return;
        }
        var joined = difference(current, previous);
        var left = difference(previous, current);
        markQuorumHistory(current);
        log.debug("Membership transition @{}ns: joined={} left={} members={}", nowNanos.getAsLong(), joined, left, current);
        listener.onMembershipChange(MembershipChange.membershipChange(joined, left, current));
    }

    private void markQuorumHistory(Set<NodeId> members) {
        if (isQuorate(members.size())) {
            everQuorate.set(true);
        }
    }

    private static Set<NodeId> difference(Set<NodeId> from, Set<NodeId> remove) {
        var result = new HashSet<>(from);
        result.removeAll(remove);
        return result;
    }

    private boolean isQuorate(int count) {
        return count >= quorumThreshold();
    }

    /// Majority of the configured core size: `coreSize/2 + 1`.
    private int quorumThreshold() {
        return coreSize.getAsInt() / 2 + 1;
    }

    // ---- Public read surface (spec §4 "Exposes") ----

    /// Current stable member set (includes self). Immutable snapshot.
    public Set<NodeId> members() {
        return Set.copyOf(stableMembers);
    }

    /// Stable member count (includes self).
    public int memberCount() {
        return stableMembers.size();
    }

    /// Quorum bit: `members ∩ configured-core ≥ majority`. Phase-1 treats every stable
    /// member as a core member (no dynamic-worker distinction yet), so this is
    /// `memberCount ≥ coreSize/2 + 1`.
    public boolean hasQuorum() {
        return isQuorate(stableMembers.size());
    }

    /// Coarse phase derived from the stable set + quorum history (spec §4).
    public MembershipPhase phase() {
        if (hasQuorum()) {
            return MembershipPhase.NORMAL;
        }
        return everQuorate.get() ? MembershipPhase.RECOVERING : MembershipPhase.COLD_BOOT;
    }

    /// Read-only QUIC bias state for a peer (diagnostics / tests). Empty when no bias is
    /// pending for the next sample.
    public Option<String> pendingQuicBias(NodeId peer) {
        return option(quicBias.get(peer)).map(Enum::name);
    }

    // ---- MembershipView (consensus) implementation ----

    @Override
    public Set<NodeId> coreMemberIds() {
        return members();
    }

    /// Per spec §2/D4 per-member READY/DRAINING state is a deployment concern not modelled
    /// in Phase 1; the on-duty set mirrors the full stable member set.
    @Override
    public Set<NodeId> onDutyMemberIds() {
        return members();
    }

    @Override
    public int healthyOnDutyCount() {
        return memberCount();
    }

    @Override
    public int desiredCoreSize() {
        return coreSize.getAsInt();
    }
}
