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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// #231 leader-side φ-accrual detector + actuator. After the distributed control-plane removal the
/// metrics ping-pong is LEADER-CENTRIC: only the leader sustains a full per-peer pong stream
/// (followers' streams stall). So φ is fed ONLY while this node is the leader, and the leader is
/// the SOLE observer that drives decommission off φ-silence. There is no quorum — silence measured
/// at the leader is authoritative (the same rationale that removed the ReachabilityGate).
///
/// **Shared detector.** The detector instance is shared with `MembershipFsm`'s φ-warmth predicate
/// (`phiDetector::isWarm`), so the reducer's `(ON_DUTY, SwimFaulty)` SWIM-false-positive
/// suppression and this observer's silence-driven `ForceDecommission` read the SAME warm/cold and
/// φ values for every peer.
///
/// **Leadership-transition reset (safety-critical).** On the not-leader→leader edge the detector
/// is `clear()`-ed so the new leader re-warms from scratch. WITHOUT this, stale windows from a
/// prior leadership stint carry a huge elapsed interval → φ saturates → false mass-decommission of
/// every peer at once (the §12.8 observer-stall cascade). The actuator's debounce counters and
/// pong snapshot are cleared on the SAME edge: a re-promoted leader must not carry stale
/// suspect-streak or advancement state. Non-negotiable.
///
/// **Action (hardened, §12.8 Lifeguard).** A naïve "fire on any single warm-and-suspected tick"
/// actuator is catastrophic with a single observer: a transient leader GC/scheduler stall freezes
/// the leader's OWN pong sampling for ALL peers at once → every peer's φ jumps to the 9.0 cap in
/// the same tick → the leader mass-decommissions a healthy cluster. Four guards in
/// [`#evaluateTick`] make a single stalled/missed tick (and a sustained local stall) non-fatal:
///
/// 1. **Local-stall skip.** If there ARE tracked peers but NOT ONE peer's pong count advanced
///    since the previous tick, the observer's own sample loop stalled (or the node is fully
///    partitioned) → suppress all eviction this tick. A detector that stopped sampling cannot
///    judge anyone.
/// 2. **Consecutive-tick debounce.** A peer must be warm-and-suspected for [`#consecutiveK`]
///    consecutive ticks (default 5 ≈ 5s) before it is an eviction candidate. Any tick where the
///    peer is not suspected (a pong landed / φ dropped) or leaves the tracked set resets its
///    streak to 0. A 1-tick φ spike never reaches K; a real black-hole stays suspected every tick
///    and fires at K.
/// 3. **Quorum self-guard.** If firing ALL current candidates would leave survivors below quorum
///    (`size/2 + 1`), this is a local-stall/partition anomaly, not N independent deaths → suppress
///    the WHOLE batch (never partially evict — the batch is untrustworthy). "A detector that
///    thinks the whole world died is the broken one."
/// 4. **Self-incarnation exclusion.** Prior STALE incarnations of self (same logical/base node id,
///    different KSUID suffix) are excluded from φ evaluation so the leader never φ-decommissions
///    its own ghosts.
///
/// Guards 1+3 together are the Lifeguard invariant derived from φ data itself — no external health
/// metric is consulted. Re-issue of `ForceDecommission` is idempotent (`applyForceDecommission` is
/// a nop once the peer is STOPPED) and self-heals a lost write, so a peer is not `forget`-ten after
/// firing — it naturally drops out of the tracked-peer set once it reaches STOPPED.
///
/// Monotonic clock: `System.nanoTime() / 1_000_000L` — the detector's docstring requires a
/// monotonic millisecond source ("local monotonic only, arrivals measured at self").
public final class PhiObserver {
    private static final Logger log = LoggerFactory.getLogger(PhiObserver.class);
    private static final TimeSpan TICK_INTERVAL = TimeSpan.timeSpan(1).seconds();

    /// Default consecutive-suspect streak required before a peer becomes an eviction candidate.
    /// At the 1s tick cadence this is ≈5s of sustained, uninterrupted silence — long enough that no
    /// transient stall/jitter spike reaches it, short enough to evict a genuine black-hole quickly.
    public static final int DEFAULT_CONSECUTIVE_K = 5;

    /// KSUID suffix length emitted by `IdGenerator.generate` (Base62, no '-'). A generated NodeId is
    /// `<base>-<27charKsuid>`; the base (everything before the final '-') is the logical identity
    /// shared across incarnations. Used by [`#baseIdentity`] for self-incarnation exclusion.
    private static final int KSUID_ENCODED_LENGTH = 27;

    /// Leader-local decommission actuator. Invoked from the periodic tick for a peer that φ
    /// reports warm-and-suspected for [`#consecutiveK`] consecutive ticks (debounced); the wiring
    /// binds it to a `LifecycleCommand.ForceDecommission` emission through the FSM-routed lifecycle
    /// writer.
    @FunctionalInterface
    public interface ForceDecommissionSink {
        @Contract
        void decommission(NodeId peer);
    }

    private final NodeId self;
    private final BooleanSupplier isLeader;
    private final PhiAccrualDetector detector;
    private final Supplier<Set<NodeId>> trackedOnDutyPeers;
    private final ForceDecommissionSink sink;
    private final int consecutiveK;
    private final String selfBaseIdentity;
    private final Map<NodeId, AtomicLong> pongCounts;
    /// Pong count of each peer as observed at the END of the previous tick — used by the
    /// local-stall guard to detect "no peer advanced". Single-threaded: only mutated from the tick.
    private final Map<NodeId, Long> prevPongSnapshot;
    /// Consecutive ticks each peer has been warm-and-suspected. Reset to 0 on any non-suspected
    /// tick or when the peer leaves the tracked set. Single-threaded: only mutated from the tick.
    private final Map<NodeId, Integer> suspectStreak;
    private final AtomicBoolean wasLeader;
    private final CancellableTask task;

    private PhiObserver(NodeId self,
                        BooleanSupplier isLeader,
                        PhiAccrualDetector detector,
                        Supplier<Set<NodeId>> trackedOnDutyPeers,
                        ForceDecommissionSink sink,
                        int consecutiveK) {
        this.self = self;
        this.isLeader = isLeader;
        this.detector = detector;
        this.trackedOnDutyPeers = trackedOnDutyPeers;
        this.sink = sink;
        this.consecutiveK = consecutiveK;
        this.selfBaseIdentity = baseIdentity(self);
        this.pongCounts = new ConcurrentHashMap<>();
        this.prevPongSnapshot = new HashMap<>();
        this.suspectStreak = new HashMap<>();
        this.wasLeader = new AtomicBoolean(false);
        this.task = CancellableTask.cancellableTask();
    }

    /// Construct the leader-gated φ detector+actuator over the SHARED `detector` (the same
    /// instance the FSM's φ-warmth predicate reads), with the default consecutive-suspect debounce
    /// ([`#DEFAULT_CONSECUTIVE_K`]). `trackedOnDutyPeers` supplies the current ON_DUTY peer set
    /// (self excluded); `sink` issues the decommission command.
    public static PhiObserver phiObserver(NodeId self,
                                          BooleanSupplier isLeader,
                                          PhiAccrualDetector detector,
                                          Supplier<Set<NodeId>> trackedOnDutyPeers,
                                          ForceDecommissionSink sink) {
        return phiObserver(self, isLeader, detector, trackedOnDutyPeers, sink, DEFAULT_CONSECUTIVE_K);
    }

    /// Construct with an explicit consecutive-suspect debounce threshold `consecutiveK` (≥1). A
    /// peer must read warm-and-suspected for `consecutiveK` consecutive ticks before it is an
    /// eviction candidate. Primarily for tests; production wiring uses the default.
    public static PhiObserver phiObserver(NodeId self,
                                          BooleanSupplier isLeader,
                                          PhiAccrualDetector detector,
                                          Supplier<Set<NodeId>> trackedOnDutyPeers,
                                          ForceDecommissionSink sink,
                                          int consecutiveK) {
        return new PhiObserver(self, isLeader, detector, trackedOnDutyPeers, sink, Math.max(1, consecutiveK));
    }

    /// Per-node pong listener. Feeds the detector ONLY while leader (followers receive no full
    /// pong stream anyway — belt-and-suspenders) and bumps the peer's pong counter, which the
    /// local-stall guard reads to detect a frozen sample loop.
    @Contract
    public void onPong(NodeId peer) {
        if (!isLeader.getAsBoolean()) {
            return;
        }
        detector.heartbeat(peer, nowMs());
        pongCounts.computeIfAbsent(peer, _ -> new AtomicLong())
                  .incrementAndGet();
    }

    /// Begin the periodic detect→decommission + per-peer φ logging tick.
    @Contract
    public void start() {
        task.set(SharedScheduler.scheduleAtFixedRate(this::tick, TICK_INTERVAL));
    }

    /// Cancel the periodic task (node shutdown).
    @Contract
    public void stop() {
        task.cancel();
    }

    /// Scheduler plumbing only: leadership-edge reset + role logging, then delegate the per-tick
    /// decision to the unit-testable [`#evaluateTick`] while leader.
    private void tick() {
        var leader = isLeader.getAsBoolean();
        resetOnLeadershipGain(leader);
        logRole(leader);

        if (!leader) {
            return;
        }
        evaluateTick(nowMs());
    }

    /// Leadership-transition reset: on the not-leader→leader edge, drop ALL accumulated φ state AND
    /// all actuator state (pong counts, prev snapshot, suspect streaks) so the new leader re-warms
    /// and re-debounces from scratch (see class docstring §12.8 cascade rationale). On loss of
    /// leadership, arm the edge so the next gain triggers a fresh reset.
    private void resetOnLeadershipGain(boolean leader) {
        if (leader && !wasLeader.getAndSet(true)) {
            detector.clear();
            pongCounts.clear();
            prevPongSnapshot.clear();
            suspectStreak.clear();
            log.info("[PHI] self={} became leader — detector + debounce/snapshot cleared, re-warming from scratch", self);
        } else if (!leader) {
            wasLeader.set(false);
        }
    }

    /// Per-tick decision logic (package-private for unit testing with a controlled detector + sink +
    /// tracked-peer set). Applies, in order: self-incarnation exclusion, per-peer φ logging, the
    /// local-stall skip (guard 1), per-peer debounce (guard 2), the quorum self-guard (guard 3),
    /// and finally the debounced eviction. The pong snapshot is refreshed at the end so the next
    /// tick can detect advancement.
    void evaluateTick(long nowMs) {
        var peers = trackedPeersExcludingSelfIncarnations();

        peers.forEach(peer -> logPeer(peer, nowMs));
        pruneDepartedPeers(peers);

        if (isLocalStall(peers)) {
            log.warn("[PHI] local-stall suspected (no pong advanced for any of {} peers) → suppressing eviction this tick",
                     peers.size());
            updatePongSnapshot(peers);
            return;
        }

        var candidates = updateStreaksAndCollectCandidates(peers, nowMs);
        actuate(peers, candidates);
        updatePongSnapshot(peers);
    }

    /// Current tracked ON_DUTY peers minus any prior STALE incarnation of self (same logical base
    /// id, different KSUID). The leader must never φ-decommission its own ghost (issue #231).
    private List<NodeId> trackedPeersExcludingSelfIncarnations() {
        var result = new ArrayList<NodeId>();
        trackedOnDutyPeers.get()
                          .stream()
                          .filter(peer -> !isSelfIncarnation(peer))
                          .forEach(result::add);
        return result;
    }

    /// True iff `peer` is a different incarnation of the SAME logical node as self: their base
    /// identities match but the full ids differ. For generated ids the base is the prefix before
    /// the trailing KSUID; for non-generated ids the base is the whole id, so this reduces to "is
    /// literally self" (already excluded by the supplier) and never over-matches unrelated peers.
    private boolean isSelfIncarnation(NodeId peer) {
        return !peer.equals(self) && baseIdentity(peer).equals(selfBaseIdentity);
    }

    /// Local-stall guard: peers exist but NOT ONE advanced its pong count since the previous tick →
    /// the leader's own sample loop is frozen (GC/scheduler stall) or the node is fully
    /// partitioned. A first tick (empty snapshot) is never a stall.
    private boolean isLocalStall(List<NodeId> peers) {
        if (peers.isEmpty() || prevPongSnapshot.isEmpty()) {
            return false;
        }
        return peers.stream().noneMatch(this::pongAdvanced);
    }

    private boolean pongAdvanced(NodeId peer) {
        var prev = prevPongSnapshot.getOrDefault(peer, -1L);
        return currentPongCount(peer) > prev;
    }

    /// Per-peer debounce (guard 2): a peer warm-and-suspected this tick increments its streak; any
    /// other state (recovered, cold) resets it to 0. A peer is an eviction candidate once its
    /// streak reaches [`#consecutiveK`].
    private List<NodeId> updateStreaksAndCollectCandidates(List<NodeId> peers, long nowMs) {
        var candidates = new ArrayList<NodeId>();
        peers.forEach(peer -> updateStreak(peer, nowMs, candidates));
        return candidates;
    }

    private void updateStreak(NodeId peer, long nowMs, List<NodeId> candidates) {
        if (isWarmSuspected(peer, nowMs)) {
            var streak = suspectStreak.merge(peer, 1, Integer::sum);
            collectIfReady(peer, streak, candidates);
        } else {
            suspectStreak.put(peer, 0);
        }
    }

    private void collectIfReady(NodeId peer, int streak, List<NodeId> candidates) {
        if (streak >= consecutiveK) {
            candidates.add(peer);
        }
    }

    private boolean isWarmSuspected(NodeId peer, long nowMs) {
        return detector.isWarm(peer) && detector.suspected(peer, nowMs);
    }

    /// Quorum self-guard (guard 3) + actuation. If firing every candidate would drop survivors
    /// below quorum the whole batch is untrustworthy (local-stall/partition signature) → suppress;
    /// otherwise fire each candidate's idempotent ForceDecommission.
    private void actuate(List<NodeId> peers, List<NodeId> candidates) {
        if (candidates.isEmpty()) {
            return;
        }
        var clusterSize = peers.size() + 1;
        var quorum = (clusterSize / 2) + 1;
        var survivors = clusterSize - candidates.size();

        if (survivors < quorum) {
            log.warn("[PHI] quorum-guard: {} candidates would breach quorum (size={} quorum={}) → suppressing eviction this tick",
                     candidates.size(),
                     clusterSize,
                     quorum);
            return;
        }
        candidates.forEach(this::fire);
    }

    private void fire(NodeId peer) {
        log.warn("[PHI] self={} peer={} warm+suspected for {} consecutive ticks → ForceDecommission",
                 self,
                 peer,
                 consecutiveK);
        sink.decommission(peer);
    }

    /// Drop streak + snapshot entries for peers no longer tracked so a re-joined node with the same
    /// id starts a fresh streak rather than inheriting a stale one.
    private void pruneDepartedPeers(List<NodeId> peers) {
        suspectStreak.keySet().retainAll(peers);
        prevPongSnapshot.keySet().retainAll(peers);
    }

    private void updatePongSnapshot(List<NodeId> peers) {
        peers.forEach(peer -> prevPongSnapshot.put(peer, currentPongCount(peer)));
    }

    private long currentPongCount(NodeId peer) {
        return pongCounts.computeIfAbsent(peer, _ -> new AtomicLong()).get();
    }

    private void logRole(boolean leader) {
        log.info("[PHI] self={} role={} trackedPeers={}",
                 self,
                 leader ? "LEADER" : "follower",
                 pongCounts.size());
    }

    private void logPeer(NodeId peer, long now) {
        log.info("[PHI] self={} peer={} pongs={} phi={} warm={} suspected={} streak={}",
                 self,
                 peer,
                 currentPongCount(peer),
                 String.format("%.2f", detector.phi(peer, now)),
                 detector.isWarm(peer),
                 detector.suspected(peer, now),
                 suspectStreak.getOrDefault(peer, 0));
    }

    /// Logical/base identity of a NodeId. `IdGenerator.generate(prefix)` produces `prefix-<ksuid>`
    /// where the KSUID is a fixed-length Base62 string (no '-'), so the base is everything before
    /// the final '-' WHEN the trailing segment is a well-formed KSUID. Ids not matching that shape
    /// (e.g. constructed via `NodeId.nodeId` from config/PEERS) have no incarnation suffix → the
    /// whole id is its own base. This parses the documented IdGenerator format rather than blindly
    /// prefix-matching, so it never groups two genuinely distinct logical nodes together.
    private static String baseIdentity(NodeId nodeId) {
        var id = nodeId.id();
        var lastDash = id.lastIndexOf('-');

        if (lastDash <= 0 || !isKsuidSuffix(id, lastDash)) {
            return id;
        }
        return id.substring(0, lastDash);
    }

    private static boolean isKsuidSuffix(String id, int lastDash) {
        var suffix = id.substring(lastDash + 1);

        return suffix.length() == KSUID_ENCODED_LENGTH && suffix.chars().allMatch(PhiObserver::isBase62);
    }

    private static boolean isBase62(int ch) {
        return (ch >= '0' && ch <= '9') || (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z');
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }
}
