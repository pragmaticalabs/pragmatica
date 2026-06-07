// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;

import org.pragmatica.aether.deployment.membership.MembershipConfig;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.swim.HealthSnapshot;
import org.pragmatica.swim.SwimHealth;
import org.pragmatica.swim.SwimObservation;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;

/// Presence Sampler (formerly `NodeTopologyTracker`) — a **SWIM-sampling debounce clock**,
/// NOT a membership authority. Since the membership-FSM cutover (Waves D1/E) the authoritative
/// "who is in the cluster" answer is owned by [`MembershipFsm`]; this class no longer answers
/// membership-authority queries. Its sole remaining job is to debounce raw SWIM liveness into
/// stable presence edges and emit two signals:
///
/// - the **DOWN-hysteresis crossing edge** ([`#onDownHysteresisCrossing`]) → routed to the FSM
///   as the sustained-absence departure signal, and
/// - the **reconcile trigger** ([`#onReconcileNeeded`]) + **peak presence count**
///   ([`#peakMembershipCount`]) → consumed by `LeaderReconciler` (the peak feeds its
///   reached-full-membership cold-start latch).
///
/// **Mechanism = periodic sample + delta + asymmetric hysteresis.** Replaces the prior
/// per-peer one-shot `ScheduledFuture` departure-timer map (and its timer-race class)
/// with a single deterministic **sample tick**:
///
/// - On each tick the sampler recomputes the *candidate live set* from the injected
///   SWIM health snapshot supplier (a peer is a candidate iff currently `HEALTHY`),
///   biased by transient QUIC connect/disconnect hints. A disconnect makes a node
///   "likely gone" for that one sample, a reconnect "likely back" — hints DO NOT
///   bypass hysteresis, they only colour the per-sample observation.
/// - A node ENTERS the stable presence set after `upHysteresis` consecutive present
///   samples (fast — see asymmetry below) and LEAVES after `downHysteresis` consecutive
///   absent samples. Counters are kept per `NodeId` (identity-preserving). A direction
///   flip resets the streak, so a transient blip is absorbed.
/// - When the stable set changes, the constructor-injected `Runnable onReconcileNeeded`
///   is invoked exactly once per stable transition (diffed against the last-emitted set).
///
/// **Asymmetric hysteresis (UP fast, DOWN slow).** UP defaults to `K_UP_DEFAULT` (≈2)
/// consecutive healthy samples — a node already SWIM-healthy is low-risk to admit, and a
/// slow UP edge delays formation/quorum recovery and lets auto-heal replacements appear
/// after the reconciler's in-flight window (provisioning storm). DOWN is derived from
/// `MembershipConfig.nttDepartureTimeout` as `ceil(nttDepartureTimeout / sampleInterval)`,
/// preserving the legacy per-peer departure-timeout debounce: dropping a node is
/// destructive (drains, leadership churn, provisioning), so it must only react to a
/// genuinely sustained absence.
///
/// `self` is always present (self-seed) and can never leave.
///
/// **Internal presence set is NOT a membership authority.** The `stableMembers` set is this
/// sampler's OWN debounced presence state — it drives the reconcile trigger and the peak. It
/// is no longer exposed as a count anyone treats as cluster membership. The one remaining
/// presence-view read ([`#currentMembers`]) still backs the **generation-snapshot path**
/// (`PresenceGenerationSnapshotSource` BOOTING→NORMAL latch + `GenerationSnapshotPublisher`
/// membership source); migrating that path off this presence view onto the FSM is a
/// deliberately-deferred separate step (behavior-affecting on the #68-critical generation path).
///
/// **Inputs.**
/// - [`#onSwimObservation`] — biases the NEXT sample only: `HealthyObserved` PRESENT,
///   `FaultyObserved` / `DepartedObserved` ABSENT. Authoritative liveness still comes from
///   the snapshot supplier on the tick; this only sharpens the very next sample.
/// - [`#onQuicReconnect`] — up-bias for the next sample (the resurrection signal per I7).
/// - [`#onQuicDisconnect`] — down-bias for the next sample (fast "likely gone" signal).
///
/// **Concurrency.** Each [`#sample`] tick runs under `sampleLock`; presence-set and streak
/// state use concurrent collections so QUIC/SWIM bias writes are lock-free. The periodic
/// tick is bound to the process-wide [`SharedScheduler`] via [`#start`]; tests call
/// [`#sample`] directly to step the sampler deterministically without a real scheduler.
public final class PresenceSampler {
    private static final Logger log = LoggerFactory.getLogger(PresenceSampler.class);

    /// Default sample tick period: recompute the candidate live set once per second.
    public static final TimeSpan SAMPLE_INTERVAL_DEFAULT = TimeSpan.timeSpan(1).seconds();

    /// Default fast UP hysteresis: 2 consecutive healthy samples to admit a node.
    public static final int K_UP_DEFAULT = 2;

    private static final Runnable NOOP_RECONCILE_TRIGGER = () -> {};

    private static final Consumer<NodeId> NOOP_DOWN_CROSSING = id -> {};

    /// Transient per-sample QUIC/SWIM bias for a node.
    private enum SampleBias { PRESENT, ABSENT }

    private final NodeId self;
    private final Supplier<HealthSnapshot> healthSupplier;
    private final TimeSpan sampleInterval;
    private final int upHysteresis;
    private final int downHysteresis;
    private final LongSupplier nowNanos;
    private final Runnable onReconcileNeeded;

    /// Injected callback fired exactly at the DOWN-hysteresis crossing edge for a node
    /// (mirrors `onReconcileNeeded`). Routes the crossing to the membership FSM; defaults
    /// to a no-op so callers/tests that don't supply it are unaffected. Additive to — not a
    /// replacement for — the presence-sensor `stableMembers.remove(node)` at that edge.
    ///
    /// `volatile` (not `final`): the [`MembershipFsm`] is constructed AFTER this sampler (it
    /// needs the sampler), so production cannot pass `membershipFsm::onDownHysteresisMet` into
    /// the constructor — it installs the listener post-construction via
    /// [`#onDownHysteresisCrossing(Consumer)`]. The constructor param + no-op default remain for
    /// tests that have the callback at construction time.
    private volatile Consumer<NodeId> onDownHysteresisCrossing;

    /// Per-node consecutive-sample counters. Positive = up-streak, negative = down-streak.
    /// A node not present here has never been sampled.
    private final Map<NodeId, Integer> streaks = new ConcurrentHashMap<>();

    /// Per-node bias applied to the NEXT sample only. Consumed (removed) on read.
    private final Map<NodeId, SampleBias> biases = new ConcurrentHashMap<>();

    /// Stable member set (debounced). Always contains self.
    private final Set<NodeId> stableMembers = ConcurrentHashMap.newKeySet();

    /// Last-emitted set — the delta baseline. Distinct from `stableMembers` so the
    /// reconcile trigger fires exactly once per transition.
    private final AtomicReference<Set<NodeId>> lastEmitted;

    private final AtomicReference<Option<ScheduledFuture<?>>> tickFuture = new AtomicReference<>(Option.none());
    private final Object sampleLock = new Object();

    /// One-way high-water mark of the stable member-set size ever observed (init 1 = self).
    /// Updated to `max(peak, current.size())` on every emitted membership change — including the
    /// initial 1→full-size formation growth — so it records that the cluster REACHED full
    /// configured size independently of any reconcile-pass timing. The leader reconciler latches
    /// its reached-full-membership cold-start guard off this peak, NOT off the per-pass member
    /// count: the reconciler is departure-triggered, so its first pass runs at the post-departure
    /// count (e.g. 4/5) and never during the full-membership window — a per-pass `>= configured`
    /// check could therefore never latch. Mutated only under `sampleLock`; read via the
    /// synchronized accessor.
    private int peakMembershipCount = 1;

    private PresenceSampler(NodeId self,
                                Supplier<HealthSnapshot> healthSupplier,
                                TimeSpan sampleInterval,
                                int upHysteresis,
                                int downHysteresis,
                                LongSupplier nowNanos,
                                Runnable onReconcileNeeded,
                                Consumer<NodeId> onDownHysteresisCrossing) {
        this.self = self;
        this.healthSupplier = healthSupplier;
        this.sampleInterval = sampleInterval;
        this.upHysteresis = Math.max(1, upHysteresis);
        this.downHysteresis = Math.max(1, downHysteresis);
        this.nowNanos = nowNanos;
        this.onReconcileNeeded = onReconcileNeeded;
        this.onDownHysteresisCrossing = onDownHysteresisCrossing;
        this.stableMembers.add(self);
        this.lastEmitted = new AtomicReference<>(Set.of(self));
    }

    /// Production factory: 1s sample tick on the [`SharedScheduler`], `K_UP_DEFAULT` up
    /// hysteresis, down hysteresis derived from `config.nttDepartureTimeout`,
    /// `System::nanoTime` clock.
    public static PresenceSampler presenceSampler(MembershipConfig config,
                                                          NodeId self,
                                                          Supplier<HealthSnapshot> healthSupplier,
                                                          Runnable onReconcileNeeded) {
        return presenceSampler(config, self, healthSupplier, onReconcileNeeded, NOOP_DOWN_CROSSING);
    }

    /// Production factory with an explicit DOWN-hysteresis crossing callback (routes the
    /// crossing edge to the membership FSM). Otherwise identical to the no-callback overload.
    public static PresenceSampler presenceSampler(MembershipConfig config,
                                                          NodeId self,
                                                          Supplier<HealthSnapshot> healthSupplier,
                                                          Runnable onReconcileNeeded,
                                                          Consumer<NodeId> onDownHysteresisCrossing) {
        return new PresenceSampler(self,
                                       healthSupplier,
                                       SAMPLE_INTERVAL_DEFAULT,
                                       K_UP_DEFAULT,
                                       downHysteresisFor(config.nttDepartureTimeout(), SAMPLE_INTERVAL_DEFAULT),
                                       System::nanoTime,
                                       onReconcileNeeded,
                                       onDownHysteresisCrossing);
    }

    /// Test factory: explicit sample interval, hysteresis, clock and a no-op reconcile
    /// trigger. The periodic tick is NOT scheduled — tests drive the FSM via [`#sample`].
    public static PresenceSampler presenceSampler(NodeId self,
                                                          Supplier<HealthSnapshot> healthSupplier,
                                                          TimeSpan sampleInterval,
                                                          int upHysteresis,
                                                          int downHysteresis,
                                                          LongSupplier nowNanos) {
        return new PresenceSampler(self,
                                       healthSupplier,
                                       sampleInterval,
                                       upHysteresis,
                                       downHysteresis,
                                       nowNanos,
                                       NOOP_RECONCILE_TRIGGER,
                                       NOOP_DOWN_CROSSING);
    }

    /// Test factory: explicit sample interval, hysteresis, clock and a reconcile trigger.
    /// Required for deterministic emit-once assertions. The periodic tick is NOT scheduled.
    public static PresenceSampler presenceSampler(NodeId self,
                                                          Supplier<HealthSnapshot> healthSupplier,
                                                          TimeSpan sampleInterval,
                                                          int upHysteresis,
                                                          int downHysteresis,
                                                          LongSupplier nowNanos,
                                                          Runnable onReconcileNeeded) {
        return presenceSampler(self,
                                   healthSupplier,
                                   sampleInterval,
                                   upHysteresis,
                                   downHysteresis,
                                   nowNanos,
                                   onReconcileNeeded,
                                   NOOP_DOWN_CROSSING);
    }

    /// Test factory: explicit sample interval, hysteresis, clock, a reconcile trigger and a
    /// DOWN-hysteresis crossing callback. Required for deterministic crossing-edge
    /// assertions. The periodic tick is NOT scheduled.
    public static PresenceSampler presenceSampler(NodeId self,
                                                          Supplier<HealthSnapshot> healthSupplier,
                                                          TimeSpan sampleInterval,
                                                          int upHysteresis,
                                                          int downHysteresis,
                                                          LongSupplier nowNanos,
                                                          Runnable onReconcileNeeded,
                                                          Consumer<NodeId> onDownHysteresisCrossing) {
        return new PresenceSampler(self,
                                       healthSupplier,
                                       sampleInterval,
                                       upHysteresis,
                                       downHysteresis,
                                       nowNanos,
                                       onReconcileNeeded,
                                       onDownHysteresisCrossing);
    }

    /// Down hysteresis derived from the legacy departure timeout:
    /// `ceil(departureTimeout / sampleInterval)`, at least 1. Preserves NTT's per-peer
    /// departure-timeout semantics as a debounce window on the sample stream.
    public static int downHysteresisFor(TimeSpan departureTimeout, TimeSpan sampleInterval) {
        var interval = Math.max(1L, sampleInterval.nanos());
        return Math.max(1, (int) ((departureTimeout.nanos() + interval - 1) / interval));
    }

    /// Start the periodic sample tick on the shared scheduler. Idempotent — a second
    /// `start()` while already running is a no-op.
    @Contract
    public void start() {
        var future = SharedScheduler.scheduleAtFixedRate(this::sample, sampleInterval);
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

    /// SWIM observation entry point — biases the NEXT sample only. `HealthyObserved`
    /// PRESENT, `FaultyObserved` / `DepartedObserved` ABSENT. All other observation kinds
    /// are ignored. Authoritative liveness still comes from the snapshot supplier on the
    /// tick; this only sharpens the very next sample.
    @Contract
    public void onSwimObservation(SwimObservation observation) {
        switch (observation) {
            case SwimObservation.HealthyObserved healthy -> biasPresent(healthy.peer());
            case SwimObservation.FaultyObserved faulty -> biasAbsent(faulty.peer());
            case SwimObservation.DepartedObserved departed -> biasAbsent(departed.peer());
            default -> {}
        }
    }

    /// QUIC reconnect hint — biases the next sample PRESENT for `peer` (the resurrection
    /// signal per I7). Membership still flips only on hysteresis.
    @Contract
    public void onQuicReconnect(NodeId peer) {
        biasPresent(peer);
    }

    /// QUIC disconnect hint — biases the next sample ABSENT for `peer` (fast "likely gone"
    /// signal). Still gated by `downHysteresis` before membership flips.
    @Contract
    public void onQuicDisconnect(NodeId peer) {
        biasAbsent(peer);
    }

    /// Post-construction installer for the DOWN-hysteresis crossing listener (routes the crossing
    /// edge to the membership FSM). Required because the [`MembershipFsm`] is built AFTER this
    /// sampler (it needs the sampler), so production cannot supply `membershipFsm::onDownHysteresisMet`
    /// at construction time. `null` resets to the no-op default. No behavior change to the firing
    /// logic in [`#crossDownHysteresis`] — only which listener it dispatches to.
    @Contract
    public void onDownHysteresisCrossing(Consumer<NodeId> listener) {
        onDownHysteresisCrossing = listener == null ? NOOP_DOWN_CROSSING : listener;
    }

    @Contract
    private void biasPresent(NodeId peer) {
        if (!peer.equals(self)) {
            biases.put(peer, SampleBias.PRESENT);
        }
    }

    @Contract
    private void biasAbsent(NodeId peer) {
        if (!peer.equals(self)) {
            biases.put(peer, SampleBias.ABSENT);
        }
    }

    /// One deterministic sample tick: recompute the candidate live set, advance per-node
    /// hysteresis counters, flip the stable set on threshold crossings, and invoke the
    /// reconcile trigger at most once. Public so tests can step the FSM without a scheduler.
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
    /// any pending bias (PRESENT forces in, ABSENT forces out). Bias is consumed.
    private Set<NodeId> candidateLiveSet() {
        var live = new HashSet<NodeId>();
        live.add(self);
        healthSupplier.get()
                      .peerHealth()
                      .forEach((peer, health) -> addIfHealthy(live, peer, health));
        applyBias(live);
        return live;
    }

    private void addIfHealthy(Set<NodeId> live, NodeId peer, SwimHealth health) {
        if (health == SwimHealth.HEALTHY) {
            live.add(peer);
        }
    }

    private void applyBias(Set<NodeId> live) {
        var pending = Map.copyOf(biases);
        pending.forEach((peer, bias) -> applyOneBias(live, peer, bias));
        pending.keySet().forEach(biases::remove);
    }

    private void applyOneBias(Set<NodeId> live, NodeId peer, SampleBias bias) {
        switch (bias) {
            case PRESENT -> live.add(peer);
            case ABSENT -> removeUnlessSelf(live, peer);
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
        if (present && next >= upHysteresis) {
            stableMembers.add(node);
        } else if (!present && (-next) >= downHysteresis) {
            crossDownHysteresis(node);
        }
    }

    /// DOWN-hysteresis crossing edge: remove the node from the stable set (presence-sensor
    /// removal, unchanged) and route the crossing to the FSM callback. The callback fires
    /// ONLY on the genuine member→removed transition (gated on `stableMembers.remove`
    /// returning true, mirroring `evictLocked`'s `wasStableMember`), so it does not re-fire
    /// on every subsequent absent sample once the streak stays past the threshold.
    private void crossDownHysteresis(NodeId node) {
        if (stableMembers.remove(node)) {
            onDownHysteresisCrossing.accept(node);
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
        peakMembershipCount = Math.max(peakMembershipCount, current.size());
        var previous = lastEmitted.getAndSet(current);
        if (current.equals(previous)) {
            return;
        }
        log.info("NTT membership changed: {} -> {} (added={}, removed={})",
                 previous.size(),
                 current.size(),
                 difference(current, previous),
                 difference(previous, current));
        onReconcileNeeded.run();
    }

    private static Set<NodeId> difference(Set<NodeId> from, Set<NodeId> remove) {
        var result = new HashSet<>(from);
        result.removeAll(remove);
        return result;
    }

    /// Hard-evict a confirmed-dead node from the stable member set immediately, bypassing
    /// the slow `downHysteresis` debounce. Removes `node` from `stableMembers`, clears its
    /// per-node `streaks` and `biases` entries (so a re-joined node starts from a clean
    /// streak and is not instantly re-evicted by a stale bias), and fires the reconcile
    /// trigger exactly once — the SAME callback hysteresis removal fires via [`#emitIfChanged`].
    ///
    /// Caller contract: only invoke once a node is CO-CONFIRMED dead (SWIM-FAULTY ∧
    /// liveness-gone) — this is the additional fast path layered over the soft
    /// QUIC-disconnect/hysteresis path, not a replacement for it. `self` is ignored
    /// (self can never leave). Idempotent: a no-op (no trigger fired) if `node` is already
    /// absent from the stable set. Runs under `sampleLock` for consistency with the periodic
    /// member-set mutations.
    @Contract
    public void evict(NodeId node) {
        if (node.equals(self)) {
            return;
        }
        synchronized (sampleLock) {
            evictLocked(node);
        }
    }

    private void evictLocked(NodeId node) {
        streaks.remove(node);
        biases.remove(node);
        var wasStableMember = stableMembers.remove(node);
        log.info("NTT evict({}): wasStableMember={}", node, wasStableMember);
        if (!wasStableMember) {
            return;
        }
        lastEmitted.set(Set.copyOf(stableMembers));
        log.debug("Hard evict @{}ns: node={} members={}", nowNanos.getAsLong(), node, stableMembers);
        onReconcileNeeded.run();
    }

    /// Count of currently-tracked cluster members (includes self).
    public int currentMemberCount() {
        return stableMembers.size();
    }

    /// One-way high-water mark of the largest stable member-set size ever observed (>= 1, includes
    /// self). Records that the cluster REACHED a given size independently of reconcile-pass timing —
    /// the leader reconciler uses this to decide cold-start-over (the cluster was once at full
    /// configured membership) rather than the post-departure per-pass count. Never decreases.
    public int peakMembershipCount() {
        synchronized (sampleLock) {
            return peakMembershipCount;
        }
    }

    /// Read-only snapshot of the currently-tracked cluster member set (includes self).
    /// Used by the leader reconciler for the seed-PEERS list when provisioning
    /// replacements and for drain-victim selection.
    public Set<NodeId> currentMembers() {
        return Set.copyOf(stableMembers);
    }
}
