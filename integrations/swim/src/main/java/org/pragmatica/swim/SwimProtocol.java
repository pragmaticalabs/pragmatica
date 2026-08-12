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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.JitterUtil;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMessage.Ack;
import org.pragmatica.swim.SwimMessage.Announce;
import org.pragmatica.swim.SwimMessage.MembershipUpdate;
import org.pragmatica.swim.SwimMessage.Ping;
import org.pragmatica.swim.SwimMessage.PingReq;
import org.pragmatica.swim.SwimMessage.WhoAmI;
import org.pragmatica.swim.SwimMessage.WhoAmIReply;
import org.pragmatica.swim.SwimTransport.SwimMessageHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.consensus.topology.TransportObservation.ObservationSource.SWIM;
import static org.pragmatica.consensus.topology.TransportObservation.peerObservedFaulty;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;


/// Core SWIM protocol implementation providing failure detection and membership dissemination.
///
/// The protocol operates in periodic ticks. Each tick:
/// 1. Select a round-robin non-self ALIVE/SUSPECT member
/// 2. Send Ping with piggybacked membership updates
/// 3. Wait probeTimeout for Ack
/// 4. If no Ack, send PingReq to indirectProbes random other members
/// 5. If still no Ack, mark SUSPECT
/// 6. After suspectTimeout, SUSPECT transitions to FAULTY
public final class SwimProtocol implements SwimMessageHandler {
    private static final Logger LOG = LoggerFactory.getLogger(SwimProtocol.class);
    /// Floor (millis) for transport-hint-shortened suspect window. Spec §11
    /// open question proposes 3s as the WAN-safe minimum.
    private static final long TRANSPORT_HINT_SUSPECT_FLOOR_MS = 3_000L;
    /// TTL multiplier (vs `suspectTimeout`) bounding tombstone-map growth for
    /// permanently-dead ids. Chosen generous (10x) so the tombstone always outlives
    /// gossip convergence — survivors stop re-gossiping a removed id well within this
    /// window. Legitimate rejoin does NOT depend on the TTL: an authoritative
    /// self-ANNOUNCE (`handleAnnounce`) or a strictly-higher incarnation clears the
    /// tombstone immediately. The TTL exists purely to reclaim map entries for ids
    /// that never come back.
    private static final long TOMBSTONE_TTL_MULTIPLIER = 10L;

    private final SwimConfig config;
    private final SwimTransport transport;
    private final SwimMembershipListener listener;
    private final NodeId selfId;
    private final InetSocketAddress selfAddress;
    /// Phase gate for cold-boot FAULTY suppression (D.3, 2026-05-11):
    ///   `true`  — cluster is in `COLD_BOOT` (never had quorum); preserve the
    ///             `everSeenHealthy` per-peer cold-boot suppression — a never-healthy
    ///             peer transitions to `UnknownObserved`, not `FaultyObserved`.
    ///   `false` — cluster is in `NORMAL` or `RECOVERING`; ALWAYS emit `FaultyObserved`
    ///             on the FAULTY edge regardless of `everSeenHealthy`. The `RECOVERING`
    ///             branch is the critical fix for compose-restart: peers were Healthy
    ///             in the prior `NORMAL` period, so a post-restart kill must produce
    ///             `FaultyObserved` to drive `HealthReconciler` aggregation, the
    ///             downstream `DECOMMISSIONED` write, and the `NODE_LEFT` event.
    /// The default `() -> true` keeps the legacy cold-boot-suppression behavior for
    /// callers (notably unit tests) that don't wire a phase source.
    private final BooleanSupplier isBooting;
    /// Transport-connectivity veto for the never-HEALTHY join-grace bypass (gate RCA fix,
    /// 2026-06-11): consulted in [#emitFaultyOrUnknown] ONLY for a never-`everSeenHealthy`
    /// peer past its join grace. While the predicate reports a LIVE transport connection for
    /// the peer, the FAULTY verdict is DEFERRED (`UnknownObserved` keeps being emitted; the
    /// verdict is re-evaluated on every subsequent FAULTY edge — never latched): a peer with
    /// an open transport link is busy-but-alive (boot deploy-storm starves its first
    /// probe-ack), not a ghost. FAULTY for a never-healthy peer therefore requires
    /// co-confirmation: join-grace expired AND no live transport connection. This is one-way
    /// evidence — transport may VETO a death, it never promotes anyone toward ALIVE.
    /// Default `id -> false` (no transport view ⇒ never veto) preserves today's behavior
    /// byte-identically for tests and standalone use; the aether node wires the QUIC
    /// network's live `connectedPeers()` view.
    private final Predicate<NodeId> transportConnected;
    private final Map<NodeId, SwimMember> members = new ConcurrentHashMap<>();
    private final Map<Long, PendingProbe> pendingProbes = new ConcurrentHashMap<>();
    private final Map<Long, RelayInfo> pendingRelays = new ConcurrentHashMap<>();
    private final Map<NodeId, Long> suspectTimestamps = new ConcurrentHashMap<>();
    /// Wave-6 Lifeguard per-suspect suspicion state, created alongside the
    /// `suspectTimestamps` start-stamp in [#beginSuspicion] and removed with it in
    /// [#endSuspicion]. Holds the window bounds fixed at suspicion start (min = base
    /// `suspectTimeout`, max = base × the local LHM multiplier at that moment), the
    /// expected-confirmer count K, and the deduped accuser set driving the dogpile
    /// logarithmic shrink ([#dogpileWindowMs]). Missing entry (defensive) falls back
    /// to the base window in [#suspicionWindowMs].
    private final Map<NodeId, Suspicion> suspicions = new ConcurrentHashMap<>();
    /// Wave-6 Lifeguard Local Health Multiplier score (memberlist "awareness").
    /// Rises by 1 on local trouble where THIS node is the likely degraded party
    /// (own probe cycle timed out with no ack, [#handleProbeTimeout]; failed probe
    /// send, [#probeTarget]; a remote suspicion of self requiring refutation,
    /// [#handleSelfUpdate]) and decays by 1 on each verified probe-ack
    /// ([#acceptProbeAckIfFromTarget]); saturates in `[0, config.lhmMaxScore()]`.
    /// The suspicion window this node arms is stretched by
    /// `min(score + 1, lhmMaxScore)` — saturating at ×lhmMaxScore (×8 default), see
    /// [#lhmMultiplier]. Deliberately suspicion-window-only: probe cadence is NOT
    /// stretched, because the fixed-rate tick keeps genuine-kill detection latency
    /// predictable (rescheduling the shared fixed-rate future is not cheap and a
    /// stretched cadence would delay detection of real deaths by up to ×8).
    private final AtomicInteger lhmScore = new AtomicInteger(0);
    /// H8 FAULTY-residency stamp (Wave 6, cluster-topology-overhaul spec): wall-clock
    /// stamp (epoch ms) of each member's most recent FAULTY edge, written on EVERY
    /// FAULTY-entry path ([#transitionToFaulty], [#notifyFaulty], [#applyNewFaultyMember]).
    /// AUTHORITATIVE residency clock for [#isFaultyAndExpired]: a FAULTY member stays
    /// resident (refutable) until `suspectTimeout × 3` has elapsed FROM THIS STAMP.
    /// Introduced as a Wave-1 diagnostic that confirmed H8 (the `suspectTimestamps`
    /// re-stamp written by the FAULTY transition was removed same-tick by
    /// `expireSuspectIfOverdue`, so members were swept 2–4 ms after their FAULTY edge
    /// instead of the designed 30 000 ms residency); promoted to the authoritative
    /// stamp by the Wave-6 H8 fix. Removed on ALIVE re-admit ([#notifyAlive],
    /// [#applyAliveFromAck]), at sweep ([#logFaultyResidency]), and in [#clearDeathMemory].
    private final Map<NodeId, Long> faultyStampedAtMs = new ConcurrentHashMap<>();
    /// Per-member first-sighting timestamp (epoch millis), stamped `putIfAbsent` when a
    /// member is first introduced ([#addSeedMember] / [#applyNewMember]). Drives the
    /// NORMAL-phase JOIN-GRACE window ([#withinJoinGrace]): a never-HEALTHY member still
    /// within `config.joinGrace()` of its first sighting is treated like a cold-boot
    /// never-HEALTHY peer (FAULTY edge → `UnknownObserved`, not tombstoned), giving the
    /// leader's round-robin probe cycle time to confirm a freshly-joined CTM replacement
    /// before it is FAULTY-evicted. `putIfAbsent` so a re-add during the window keeps the
    /// original first-sighting (first add wins) — the window is not arbitrarily extended.
    /// Cleared in [#clearDeathMemory] so a genuinely-re-joining swept member gets a fresh
    /// grace window. Parallel map (does NOT touch the `SwimMember` record/codec).
    private final Map<NodeId, Long> memberFirstSeenAt = new ConcurrentHashMap<>();
    private final PiggybackBuffer piggybackBuffer;
    private final AtomicLong sequenceCounter = new AtomicLong(0);
    /// Durable, monotonic self-incarnation — the SWIM liveness epoch this node
    /// advertises for ITSELF. Seeded from the boot incarnation in [#announceJoin]
    /// and bumped strictly past any incoming suspicion in [#handleSelfUpdate].
    ///
    /// Invariant: a refutation is only authoritative if it carries an incarnation
    /// STRICTLY GREATER than the one the suspicion was raised at. Canonical SWIM
    /// orders (state, incarnation) lexically with FAULTY>SUSPECT>ALIVE at equal
    /// incarnation, so an `Alive(self, k)` can NEVER supersede a `Suspect(self, k)`
    /// at the same `k`. The previous reactive refutation re-sent the same value
    /// forever and was silently out-ordered, so a live-but-suspected node could be
    /// driven SUSPECT->FAULTY->evicted under loss/churn. Storing and strictly
    /// advancing this value makes every refutation win. `0` means "pre-announce":
    /// the proactive self-ALIVE advertisement ([#refreshSelfAlive]) is suppressed
    /// until [#announceJoin] seeds a real incarnation, so a bogus incarnation-0
    /// self is never gossiped.
    private final AtomicLong selfIncarnation = new AtomicLong(0);
    /// Fix 1 (#336 at-risk self-refutation): epoch-ms of the last evidence that peers can
    /// reach THIS node — an inbound `Ping` ([#handlePing]) or a verified `Ack` to a probe
    /// this node sent ([#acceptProbeAckIfFromTarget]). When it goes stale (≥ the at-risk
    /// window, `suspectTimeout / `[`SwimConfig#AT_RISK_WINDOW_DIVISOR`]) the node is "at risk"
    /// of a suspicion it will never hear (its inbound link is the same one a suspect-gossip
    /// would ride), and [#refreshSelfAlive] advances the self-incarnation to out-rank it.
    /// Seeded to construction time so a fresh node has a full at-risk window of grace before
    /// its first proactive bump.
    private final AtomicLong lastInboundReachabilityAt = new AtomicLong(System.currentTimeMillis());
    /// Fix 1 (#336): epoch-ms of the last at-risk self-incarnation advance, rate-limiting the
    /// proactive bump to at most once per at-risk window so a silently-isolated node does not
    /// flood incarnation churn (over-bumping would break anti-oscillation). `0` = never bumped.
    private final AtomicLong lastAtRiskBumpAt = new AtomicLong(0L);
    private final AtomicReference<Option<ScheduledFuture<?>>> tickFuture = new AtomicReference<>(none());
    /// Per-member last-probe ORDINAL (a strictly-monotonic `probeOrdinal` value), keyed by
    /// `NodeId` so probe scheduling is identity-stable under churn. Stamped in [#probeTarget]
    /// the moment a probe is sent. [#selectNextProbeTarget] always picks the least-recently-
    /// probed probable member (a member ABSENT from this map — never probed — sorts first),
    /// which restores the "probe each member once per round" invariant that the prior
    /// free-running positional index broke when `members.values()` reshuffled order/size as a
    /// peer flapped (removed+re-added via gossip), starving a genuinely-dead peer of probes so
    /// it never entered SUSPECT (#94).
    ///
    /// Ordering uses [#probeOrdinal] (a monotonic counter), NOT a wall-clock timestamp:
    /// selections can occur many times within a single `System.currentTimeMillis()` tick
    /// (millisecond granularity is far coarser than the selection cadence), which would
    /// collapse the LRP order to the `NodeId` tie-break and re-introduce positional unfairness.
    /// The ordinal makes every probe strictly distinct, so the schedule rotates fairly.
    /// Cleared in [#clearDeathMemory] so a swept/rejoining id starts fresh (sorts first →
    /// probed immediately on re-add).
    private final Map<NodeId, Long> lastProbedAt = new ConcurrentHashMap<>();
    /// Strictly-monotonic probe ordinal source. Each probe sent ([#probeTarget]) claims the
    /// next value and stamps it into [#lastProbedAt], giving a granularity-free total order
    /// for least-recently-probed selection. See [#lastProbedAt].
    private final AtomicLong probeOrdinal = new AtomicLong(0);
    /// Single false->true latch set on the first inbound SWIM Ping (proof a peer
    /// has acknowledged this node). Read by [#runAnnounceAttempt] on the scheduler
    /// thread to cancel the join-announce loop once self is acknowledged — fixes a
    /// replacement joining an already-quorate cluster never sending an ANNOUNCE
    /// because the quorum predicate is satisfied at join time. Volatile: written on
    /// the message thread, read on the scheduler thread.
    private volatile boolean inboundProbeReceived = false;
    /// Self-isolation latch (P2 isolation-era verdict expiry). Set `true` when a FAULTY
    /// edge leaves EVERY tracked peer FAULTY simultaneously — an all-peers-FAULTY signature
    /// is epistemically "I am cut off", not "the whole cluster died". While latched, the
    /// FAULTY verdicts accumulated during the isolation window sit in the dissemination
    /// backlog. On the first genuine reconnection evidence (a verified probe-ack from any
    /// peer, [#acceptAliveEvidence]) the latch clears and the isolation-era FAULTY backlog
    /// is expired ([#PiggybackBuffer.expireFaultyUpdates]) so a rejoining isolated node does
    /// NOT inject stale death verdicts into the healed cluster (the S06 partition-heal
    /// collapse root). Volatile: written/read across the probe (scheduler) and message threads.
    private volatile boolean selfIsolated = false;
    /// Incarnation-aware, TTL-bounded tombstones for ids that died and got cleaned up.
    /// Refuses third-party re-introduction of a dead id (gossip dissemination + bare
    /// channel re-seed) that would otherwise re-create it as SUSPECT and restart the
    /// SUSPECT<->FAULTY oscillation (#231).
    /// Phase-aware (02-chaos S01, 2026-06): in NORMAL/RECOVERING any FAULTY id is
    /// tombstoned, including a never-HEALTHY replacement killed in its JOINING window;
    /// in COLD_BOOT only PROVEN-HEALTHY ids are tombstoned, because a never-HEALTHY id may
    /// be a cold-boot seed that is simply slow to form and tombstoning it would break
    /// cluster formation (the reason an earlier non-gated tombstone attempt was reverted).
    /// The tombstone is cleared by an authoritative self-ANNOUNCE (partition-heal) and
    /// superseded by a strictly higher incarnation (genuine restart/refutation).
    private final Map<NodeId, Tombstone> tombstones = new ConcurrentHashMap<>();
    /// Per-peer "ever observed HEALTHY at least once" flag. Cold-boot suppression:
    /// a never-healthy peer is emitted as `UnknownObserved` instead of `FaultyObserved`
    /// (spec §4.2 "Behavior contract: Cold-boot mode").
    private final Set<NodeId> everSeenHealthy = ConcurrentHashMap.newKeySet();
    /// Per-peer last-emitted observation health, used to enforce edge-triggered
    /// emission (P5 idempotent edge transitions): same-state re-entries do NOT
    /// produce a new observation.
    private final Map<NodeId, SwimHealth> lastEmittedHealth = new ConcurrentHashMap<>();
    /// Per-peer transport-hint state. `PeerUnreachable` (a) biases the suspect
    /// window for the peer toward the [`#TRANSPORT_HINT_SUSPECT_FLOOR_MS`] floor and
    /// (b) INITIATES ALIVE->SUSPECT for a currently-ALIVE, ever-healthy peer
    /// ([#initiateTransportDrivenSuspicion]) so the high-confidence death signal is not
    /// wasted on a timer that may never start (#94); `PeerReachable` is ignored — SWIM
    /// gossip/probe state is the sole authority on liveness/recovery. Transport may
    /// accelerate DEATH suspicion, never report life: FAULTY still requires the floored
    /// window to expire WITHOUT refutation.
    private final Map<NodeId, TransportHintState> transportHints = new ConcurrentHashMap<>();
    private final List<Consumer<SwimObservation>> observationListeners = new CopyOnWriteArrayList<>();
    /// Cluster-wide `TransportObservation` emitters. SWIM-internal `SwimObservation`
    /// (see [#observationListeners]) is consumed by `SwimHealthDetector` and its
    /// listeners; the cluster-wide stream defined by
    /// `org.pragmatica.consensus.topology.TransportObservation` has different consumers
    /// (`LeaderManager`, `ClusterFsmRouter`, etc.) and is wired by the Aether layer to
    /// the cluster-wide message router. The dual emission is intentional: the two
    /// streams serve different audiences.
    private final List<TransportObservationEmitter> transportObservationEmitters = new CopyOnWriteArrayList<>();

    /// Adapter callback for forwarding SWIM-detected FAULTY observations to the
    /// cluster-wide `TransportObservation` stream owned by the consensus layer.
    /// Wired by the Aether node assembly (see `CoreSwimHealthDetector`) to the
    /// cluster `MessageRouter`. The FQCN is used here once, deliberately, to avoid
    /// the local-vs-cluster `TransportObservation` name collision in this module.
    @FunctionalInterface
    public interface TransportObservationEmitter extends Consumer<org.pragmatica.consensus.topology.TransportObservation> {}

    /// Serializes `start()` / `stop()` against each other so a `start()` racing
    /// concurrent `start()` cannot double-schedule, and a `start()` racing a
    /// `stop()` cannot leave the protocol running after `stop()` returns.
    /// The internal `getAndSet` / `compareAndSet` pattern remains inside the lock.
    private final Object lifecycleLock = new Object();

    /// Tracks a relayed PingReq: maps the relay's own sequence to the original requester info.
    /// `targetId` is the relayed-probe target — the only `from` an Ack answering this relay
    /// may legitimately carry (Wave-6 Ack.from() check, [#forwardAckIfFromTarget]).
    private record RelayInfo(long originalSequence,
                             InetSocketAddress requesterAddress,
                             NodeId targetId,
                             long createdAt) {}

    /// Wave-6 Lifeguard per-suspect suspicion state (see [#suspicions]). Window bounds are
    /// FIXED at suspicion start: `minWindowMs` = base `suspectTimeout`, `maxWindowMs` =
    /// base × the local LHM multiplier at that moment. `confirmers` is the deduped set of
    /// accuser NodeIds, seeded with the originating accuser (which does NOT count as a
    /// confirmation — C = `confirmers.size() - 1`, memberlist-style). `expectedConfirmers`
    /// is K, fixed at creation as `max(1, min(config.dogpileExpectedConfirmers(),
    /// trackedMembers - 1))`.
    private record Suspicion(long minWindowMs, long maxWindowMs, int expectedConfirmers, Set<NodeId> confirmers) {}

    /// Death-record for a PROVEN-HEALTHY id that died and was cleaned up. `incarnation`
    /// is the incarnation at which the id was tombstoned; a strictly-higher incoming
    /// incarnation supersedes it. `createdAtMs` bounds map growth via the TTL sweep.
    private record Tombstone(long incarnation, long createdAtMs) {}

    private SwimProtocol(SwimConfig config,
                         SwimTransport transport,
                         SwimMembershipListener listener,
                         NodeId selfId,
                         InetSocketAddress selfAddress,
                         BooleanSupplier isBooting,
                         Predicate<NodeId> transportConnected) {
        this.config = config;
        this.transport = transport;
        this.listener = listener;
        this.selfId = selfId;
        this.selfAddress = selfAddress;
        this.piggybackBuffer = PiggybackBuffer.piggybackBuffer(config.maxPiggyback());
        this.isBooting = isBooting;
        this.transportConnected = transportConnected;
    }

    /// Factory creating a SWIM protocol instance.
    /// Result wrapper retained for flatMap(SwimProtocol::start) composition.
    /// Backwards-compatible overload — preserves legacy per-peer `everSeenHealthy`
    /// cold-boot suppression for callers (notably tests) that do not wire a phase
    /// supplier.
    public static Result<SwimProtocol> swimProtocol(SwimConfig config,
                                                    SwimTransport transport,
                                                    SwimMembershipListener listener,
                                                    NodeId selfId,
                                                    InetSocketAddress selfAddress) {
        return swimProtocol(config, transport, listener, selfId, selfAddress, () -> true);
    }

    /// Phase-aware factory. `isBooting` is consulted in [#emitFaultyOrUnknown]: when
    /// the supplier returns `false` (cluster has reached NORMAL phase), the per-peer
    /// `everSeenHealthy` cold-boot gate is bypassed and `FaultyObserved` is emitted
    /// regardless. See audit Step 6 (2026-05-07). No transport-connectivity veto
    /// (`id -> false` — never veto), byte-identical to the pre-veto behavior; use the
    /// [#swimProtocol(SwimConfig,SwimTransport,SwimMembershipListener,NodeId,InetSocketAddress,BooleanSupplier,Predicate)]
    /// overload to wire one.
    public static Result<SwimProtocol> swimProtocol(SwimConfig config,
                                                    SwimTransport transport,
                                                    SwimMembershipListener listener,
                                                    NodeId selfId,
                                                    InetSocketAddress selfAddress,
                                                    BooleanSupplier isBooting) {
        return swimProtocol(config, transport, listener, selfId, selfAddress, isBooting, id -> false);
    }

    /// Full factory (gate RCA fix, 2026-06-11): phase supplier AND the
    /// transport-connectivity veto for the never-HEALTHY join-grace bypass (see the
    /// [#transportConnected] field doc). Production wiring supplies the QUIC network's
    /// live `connectedPeers()` view so a busy-but-alive joiner (open transport link,
    /// first probe-ack starved by the boot deploy-storm) is deferred instead of
    /// false-killed at grace expiry.
    public static Result<SwimProtocol> swimProtocol(SwimConfig config,
                                                    SwimTransport transport,
                                                    SwimMembershipListener listener,
                                                    NodeId selfId,
                                                    InetSocketAddress selfAddress,
                                                    BooleanSupplier isBooting,
                                                    Predicate<NodeId> transportConnected) {
        return Result.success(new SwimProtocol(config,
                                               transport,
                                               listener,
                                               selfId,
                                               selfAddress,
                                               isBooting,
                                               transportConnected));
    }

    /// Start the protocol: begin periodic probing via SharedScheduler.
    /// First tick delayed by startupDelay to allow all TCP connections to establish after quorum.
    public Result<SwimProtocol> start() {
        synchronized (lifecycleLock) {
            if (tickFuture.get().isPresent()) {
                return SwimError.General.PROTOCOL_ALREADY_RUNNING.result();
            }
            // Light jitter (±20%) on the startup offset only — period is intentionally fixed to keep
            // failure-detection latency predictable. The jitter de-syncs simultaneous starts after a
            // shared quorum-formation event so probe traffic is not thundering-herd.
            var jitteredStartupMs = JitterUtil.applyJitter(config.startupDelay().millis(),
                                                           JitterUtil.LIGHT_MIN_FACTOR,
                                                           JitterUtil.LIGHT_MAX_FACTOR);
            var startup = TimeSpan.timeSpan(jitteredStartupMs).millis();

            tickFuture.set(option(SharedScheduler.scheduleAtFixedRate(this::tick, startup, config.period())));
            LOG.info("SWIM protocol started for node {} (first probe in {}ms; jittered from base {}ms)",
                     selfId.id(),
                     jitteredStartupMs,
                     config.startupDelay().millis());

            return Result.success(this);
        }
    }

    /// Stop the protocol.
    public Result<SwimProtocol> stop() {
        synchronized (lifecycleLock) {
            if (!tickFuture.get().isPresent()) {
                return SwimError.General.PROTOCOL_NOT_RUNNING.result();
            }

            tickFuture.getAndSet(none()).onPresent(f -> f.cancel(false));
            LOG.info("SWIM protocol stopped for node {}", selfId.id());

            return Result.success(this);
        }
    }

    /// Add a seed member to the membership list.
    ///
    /// Resurrection guard (#231): a channel re-seed is NOT proof of reachability, so the
    /// member is introduced in the LOCAL-ONLY `OBSERVED` birth state (#336/#241) — known and
    /// probe-eligible, but NOT alive and NOT death-timer-armed. No suspicion is armed at birth
    /// (the prior born-SUSPECT-with-armed-timer evicted live joiners before the leader's probe
    /// could confirm them), and OBSERVED is never disseminated (no `addMemberUpdate`). The next
    /// tick probes it ([#isProbable] is true for OBSERVED); a real probe-ack (or a gossiped Alive)
    /// promotes it to ALIVE/HEALTHY, while a sustained probe-timeout past the join deadline
    /// escalates it to SUSPECT ([#markSuspect]). OBSERVED does NOT set `everSeenHealthy`
    /// and does NOT emit `HealthyObserved`, so a previously-dead id re-seeded onto the channel is
    /// never re-admitted as HEALTHY off a bare add.
    ///
    /// Cold-start is preserved: `notifyMemberJoined` (and its `MemberDiscovered` observation that
    /// feeds the QUIC dial set) still fires, and the first-sighting stamp lets the join-deadline
    /// escalation run if the seed never acks.
    @Contract
    public void addSeedMember(NodeId nodeId, InetSocketAddress address) {
        if (selfId.equals(nodeId)) {
            return;
        }
        // Mirror the ANNOUNCE guard (`if (!members.containsKey(...))`): only introduce
        // an UNKNOWN id. A re-seed of a member already tracked (notably a SUSPECT victim
        // whose suspect-window is counting down to FAULTY) must be a no-op — otherwise
        // each channel reconnect re-stamps `suspectTimestamps`, perpetually deferring the
        // FAULTY transition so `NODE_FAILED` never fires (#231 lingering-victim).
        if (members.containsKey(nodeId)) {
            return;
        }
        // Tombstone refusal (#231 oscillation): a bare channel re-seed is NOT proof the
        // node is alive — it carries no incarnation, so treat it as incarnation 0. A
        // dead-and-cleaned id that is still tombstoned must not be re-introduced as
        // OBSERVED (which would restart the SUSPECT<->FAULTY oscillation once it escalates).
        // A genuine rejoin arrives via self-ANNOUNCE (`handleAnnounce`), which clears the
        // tombstone.
        if (isTombstoned(nodeId, 0L)) {
            return;
        }

        var member = SwimMember.swimMember(nodeId, MemberState.OBSERVED, 0, address);

        members.put(nodeId, member);
        memberFirstSeenAt.putIfAbsent(nodeId, System.currentTimeMillis());
        notifyMemberJoined(member);
    }

    /// Return an unmodifiable snapshot of the current membership.
    public Map<NodeId, SwimMember> members() {
        return Collections.unmodifiableMap(members);
    }

    /// Register a push-channel listener for [`SwimObservation`] edge transitions.
    /// Listeners are invoked once per actual edge — same-state re-emission is
    /// suppressed (P5 idempotent edge transitions, spec §4.2).
    @Contract
    public void addObservationListener(Consumer<SwimObservation> listener) {
        observationListeners.add(listener);
    }

    /// Register an emitter for the cluster-wide
    /// `org.pragmatica.consensus.topology.TransportObservation` stream. Invoked from
    /// [#emitFaultyOrUnknown] when SWIM transitions a peer to FAULTY (paired with the
    /// SWIM-internal `SwimObservation.FaultyObserved` delivered to `observationListeners`).
    /// Wired by the Aether node assembly to the cluster `MessageRouter`.
    @Contract
    public void addTransportObservationEmitter(TransportObservationEmitter emitter) {
        transportObservationEmitters.add(emitter);
    }

    /// Pull-channel current per-peer health. Snapshot semantics — modifications
    /// to SWIM state after this call are not reflected in the returned view.
    public HealthSnapshot currentHealth() {
        var view = new HashMap<NodeId, SwimHealth>();

        members.forEach((id, member) -> view.put(id, classify(id, member)));

        return HealthSnapshot.healthSnapshot(view);
    }

    /// Record a transport-level hint from Layer 0 (QUIC). Advisory only —
    /// SWIM remains authoritative and is the sole authority on liveness/recovery.
    /// `PeerUnreachable` (a) biases this peer's suspect-window timer toward the
    /// [`#TRANSPORT_HINT_SUSPECT_FLOOR_MS`] floor and (b) initiates ALIVE->SUSPECT for a
    /// currently-ALIVE, ever-healthy peer (see [#applyUnreachableHint]); `PeerReachable`
    /// is ignored — SWIM gossip/probe-ack state is the sole authority on liveness and
    /// recovery. Transport may accelerate DEATH suspicion, never report life: a peer
    /// driven SUSPECT this way still returns to ALIVE if it refutes within the window.
    @Contract
    public void recordTransportHint(NodeId peer, TransportObservation hint) {
        if (selfId.equals(peer)) {
            return;
        }

        switch (hint) {
            case TransportObservation.PeerReachable _ -> { /* no-op: SWIM probe-ack is the sole recovery authority; transport may report death, never life */ }
            case TransportObservation.PeerUnreachable _ -> applyUnreachableHint(peer);
        }
    }

    /// Apply a transport `PeerUnreachable` hint. Two effects, both death-only (transport
    /// may report death, never life — the [#transportHints] field invariant is preserved):
    ///
    /// 1. Record the per-peer unreachable bias so the suspect-window evaluation
    ///    ([#effectiveSuspectTimeoutMs]) is floored to [`#TRANSPORT_HINT_SUSPECT_FLOOR_MS`].
    ///    This shortens an ALREADY-RUNNING suspect window (the legacy behavior).
    /// 2. INITIATE suspicion NOW for a peer we have positive prior-liveness evidence on —
    ///    `everSeenHealthy` AND currently `ALIVE` ([#initiateTransportDrivenSuspicion]). The
    ///    high-confidence transport death signal (a previously-CONNECTED QUIC peer gone
    ///    silent) no longer merely biases a timer that may never start; it drives the same
    ///    ALIVE->SUSPECT transition SWIM's own probe-timeout uses ([#markSuspect]), so the
    ///    bias set in (1) takes effect immediately and the peer expires to FAULTY within the
    ///    ~3s floor unless it refutes (probe-ack / higher-incarnation Announce). Without this,
    ///    detection waited for SWIM's CPU-load-delayed probe cycle to independently enter
    ///    SUSPECT (>60s under churn, #94).
    ///
    /// The order matters: the bias (1) is recorded BEFORE suspicion is initiated (2) so
    /// [#beginSuspicion]'s window honors the floor from the first tick.
    private void applyUnreachableHint(NodeId peer) {
        transportHints.put(peer, new TransportHintState(true, System.currentTimeMillis()));
        LOG.debug("SWIM transport hint: peer {} reported unreachable; suspect window biased to {}ms floor",
                  peer.id(),
                  TRANSPORT_HINT_SUSPECT_FLOOR_MS);
        initiateTransportDrivenSuspicion(peer);
    }

    /// Initiate ALIVE->SUSPECT for `peer` on a transport `PeerUnreachable` hint, gated
    /// strictly on positive prior-liveness evidence: the peer must be currently tracked,
    /// have been observed HEALTHY at least once (`everSeenHealthy`), and be currently
    /// `ALIVE`. Reuses [#markSuspect] — the EXACT probe-timeout SUSPECT path — which
    /// itself re-filters on `state() == ALIVE`, so the transition is idempotent: an
    /// already-SUSPECT or FAULTY peer's suspicion clock is NEVER restarted, and there is
    /// no double-suspicion. A never-seen-healthy or UNKNOWN peer is NOT suspected (a
    /// never-connected node must not be false-killed); a tombstoned/removed id is not
    /// tracked as ALIVE here, so it is never resurrected to SUSPECT.
    private void initiateTransportDrivenSuspicion(NodeId peer) {
        if (!everSeenHealthy.contains(peer)) {
            return;
        }

        markSuspect(peer);
    }

    private SwimHealth classify(NodeId peer, SwimMember member) {
        if (!everSeenHealthy.contains(peer) && member.state() != MemberState.ALIVE) {
            return SwimHealth.UNKNOWN;
        }

        return switch (member.state()) {
            case ALIVE -> SwimHealth.HEALTHY;
            case SUSPECT -> SwimHealth.SUSPECTED;
            case FAULTY -> SwimHealth.FAULTY;
            // OBSERVED is a never-confirmed local birth state — it has not been observed
            // HEALTHY, so the early return above already classifies it UNKNOWN; this arm
            // keeps the switch exhaustive.
            case OBSERVED -> SwimHealth.UNKNOWN;
        };
    }

    // -- SwimMessageHandler --
    @Override
    @Contract
    public void onMessage(InetSocketAddress sender, SwimMessage message) {
        LOG.trace("SWIM recv from {}: {}",
                  sender,
                  message.getClass().getSimpleName());
        switch (message) {
            case Ping ping -> handlePing(sender, ping);
            case Ack ack -> handleAck(ack);
            case PingReq pingReq -> handlePingReq(sender, pingReq);
            case Announce announce -> handleAnnounce(sender, announce);
            case WhoAmI w -> handleWhoAmI(sender, w);
            case WhoAmIReply r -> handleWhoAmIReply(r);
        }
    }

    // -- Internal tick --
    private void tick() {
        refreshSelfAlive();
        expireSuspectMembers();
        cleanupFaultyMembers();
        selectNextProbeTarget().onPresent(this::probeTarget);
    }

    /// Proactively disseminate this node's own latest `Alive(selfId, selfIncarnation)`
    /// once per probe round (canonical SWIM self-dissemination). This propagates
    /// liveness BEFORE a remote suspect window can expire, rather than only reacting
    /// to an inbound suspicion in [#handleSelfUpdate] — closing the window in which a
    /// live-but-temporarily-silent node is driven SUSPECT->FAULTY under loss/churn.
    ///
    /// Injected at the probe tick (NOT per outgoing message): `PiggybackBuffer` does
    /// not dedup by nodeId, so a per-message add would accumulate. One add per round
    /// rides the next probe/ack/relay via the shared buffer and is bounded by the
    /// buffer's dissemination-count eviction; each round's entry supersedes the prior
    /// by carrying the same-or-higher incarnation.
    ///
    /// Suppressed while `selfIncarnation == 0` (pre-announce) so a bogus incarnation-0
    /// self is never advertised.
    @Contract
    private void refreshSelfAlive() {
        var incarnation = selfIncarnation.get();

        if (incarnation == 0) {
            return;
        }

        addMemberUpdate(MembershipUpdate.membershipUpdate(selfId,
                                                          MemberState.ALIVE,
                                                          refreshedSelfIncarnation(incarnation),
                                                          selfAddress));
    }

    /// Fix 1 (#336 PRIMARY): the incarnation this round's proactive self-ALIVE advertises.
    /// When the node is "at risk" — no inbound-reachability evidence for at least the at-risk
    /// window AND the previous at-risk bump is at least that old (rate-limit) — it advances
    /// the durable self-incarnation (atomic +1) so the broadcast `Alive(self, k+1)` STRICTLY
    /// out-ranks any in-flight `Suspect(self, k)` raised by a prober this node never heard
    /// from (the canonical-precedence dead-end the failing link created — proactive
    /// rebroadcast at the UNCHANGED incarnation could never clear it). Otherwise it returns
    /// the unchanged incarnation (today's rebroadcast behavior).
    private long refreshedSelfIncarnation(long currentIncarnation) {
        var now = System.currentTimeMillis();

        if (!atRiskOfUnheardSuspicion(now) || !atRiskBumpDue(now)) {
            return currentIncarnation;
        }

        lastAtRiskBumpAt.set(now);
        var bumped = selfIncarnation.incrementAndGet();

        LOG.info("SWIM at-risk self-refutation (#336): no inbound reachability for >={}ms — advancing self-incarnation "
                + "{} -> {} and broadcasting Alive(self, {}) to out-rank any unheard Suspect(self, {})",
                 atRiskWindowMs(),
                 currentIncarnation,
                 bumped,
                 bumped,
                 currentIncarnation);

        return bumped;
    }

    /// Fix 1: true when no evidence that peers can reach this node has arrived within the
    /// at-risk window (`suspectTimeout / `[`SwimConfig#AT_RISK_WINDOW_DIVISOR`]).
    private boolean atRiskOfUnheardSuspicion(long now) {
        return now - lastInboundReachabilityAt.get() >= atRiskWindowMs();
    }

    /// Fix 1 rate-limit: at most one at-risk incarnation advance per at-risk window.
    private boolean atRiskBumpDue(long now) {
        return now - lastAtRiskBumpAt.get() >= atRiskWindowMs();
    }

    private long atRiskWindowMs() {
        return config.suspectTimeout()
                     .millis() / SwimConfig.AT_RISK_WINDOW_DIVISOR;
    }

    /// Fix 1: record evidence that peers can currently reach this node (an inbound Ping or a
    /// verified Ack to a probe we sent), resetting the at-risk clock.
    private void recordInboundReachability() {
        lastInboundReachabilityAt.set(System.currentTimeMillis());
    }

    private void probeTarget(SwimMember target) {
        var seq = sequenceCounter.incrementAndGet();
        var piggyback = piggybackBuffer.peekUpdates(config.maxPiggyback());
        var ping = Ping.ping(selfId, seq, piggyback);

        lastProbedAt.put(target.nodeId(), probeOrdinal.incrementAndGet());
        pendingProbes.put(seq,
                          PendingProbe.pendingProbe(target.nodeId(), System.currentTimeMillis(), false));
        // A failed probe SEND is local trouble (Wave-6 Lifeguard): this node could not even
        // get the datagram out, so it is the likely degraded party.
        transport.send(target.address(),
                       ping)
                 .onFailure(cause -> lhmIncrement("probe send to " + target.nodeId()
                                                                           .id()
                                                 + " failed: " + cause.message()));
        scheduleProbeTimeout(seq);
    }

    private void expireSuspectMembers() {
        var now = System.currentTimeMillis();
        var baseSuspectTimeoutMillis = config.suspectTimeout().millis();

        suspectTimestamps.forEach((nodeId, timestamp) -> expireSuspectIfOverdue(nodeId,
                                                                                timestamp,
                                                                                now,
                                                                                baseSuspectTimeoutMillis));
        // Clean up stale relays by age, not by pendingProbes presence
        var relayTimeoutMillis = config.probeTimeout().millis() * 3;

        pendingRelays.entrySet().removeIf(entry -> now - entry.getValue()
                                                              .createdAt() > relayTimeoutMillis);
    }

    /// Remove FAULTY members after the H8 residency window to prevent unbounded growth.
    /// MAP-HYGIENE ONLY: member removal, tombstone, death-memory clear and the residency
    /// journal line — NO observation is emitted here. The death broadcast
    /// (`FaultyObserved` + `DepartedObserved`) fires at the FAULTY edge
    /// ([#emitFaultyAndDeparted]); the residency window between edge and sweep exists
    /// purely for SWIM-map refutability/tombstone correctness.
    private void cleanupFaultyMembers() {
        var now = System.currentTimeMillis();
        var cleanupThreshold = config.suspectTimeout().millis() * 3;
        var iterator = members.entrySet().iterator();

        while (iterator.hasNext()) {
            var entry = iterator.next();

            if (isFaultyAndExpired(entry, now, cleanupThreshold)) {
                logFaultyResidency(entry.getKey(), now, cleanupThreshold);
                iterator.remove();
                tombstoneIfWasHealthy(entry.getKey(),
                                      entry.getValue().incarnation(),
                                      now);
                clearDeathMemory(entry.getKey());
            }
        }

        sweepExpiredTombstones(now);
    }

    /// Wave-6 H8 journal line (residency decision logging, per the Wave-1 diagnostic
    /// pattern): on each FAULTY-member sweep, log the measured FAULTY-edge → sweep
    /// residency interval alongside the designed window (`suspectTimeout × 3`). The
    /// stamp read here ([#faultyStampedAtMs]) is the AUTHORITATIVE residency clock the
    /// sweep decision itself used ([#isFaultyAndExpired]), so the logged interval is
    /// the enforced one. MUST run BEFORE [#clearDeathMemory] (which also erases the
    /// stamp); the `remove` here makes the log emission once-per-sweep.
    private void logFaultyResidency(NodeId peer, long now, long designedResidencyMs) {
        option(faultyStampedAtMs.remove(peer)).onPresent(stampedAt -> LOG.info("SWIM FAULTY-residency (Wave-6 H8): member {} swept {} ms after its FAULTY edge "
                                                                              + "(designed residency window {} ms)",
                                                                               peer.id(),
                                                                               now - stampedAt,
                                                                               designedResidencyMs));
    }

    /// Sweep-time tombstone backstop. The PRIMARY tombstone is set at the FAULTY edge
    /// ([#tombstoneOnFaultyEdge]) so a re-admit during the FAULTY->sweep window is
    /// refused. This sweep-time set is retained as a backstop for the rare path where a
    /// member reaches the sweep still FAULTY without having traversed an instrumented
    /// FAULTY edge (idempotent: re-stamping at the same incarnation is harmless).
    ///
    /// Same phase-aware gate as the FAULTY edge: tombstone when
    /// `(!isBooting AND !withinJoinGrace) OR everSeenHealthy.contains(peer)`. In
    /// NORMAL/RECOVERING a swept never-HEALTHY FAULTY id is tombstoned ONCE its join-grace
    /// has expired (02-chaos S01 backstop); within the grace window (a freshly-joined
    /// replacement not yet confirmed HEALTHY) it is NOT tombstoned so the leader's probe
    /// cycle can still confirm it. In COLD_BOOT only proven-healthy ids are tombstoned so a
    /// slow cold-boot seed is not refused (formation safety).
    /// MUST be called BEFORE `clearDeathMemory` (which erases `everSeenHealthy` and the
    /// first-sighting entry the grace check reads).
    private void tombstoneIfWasHealthy(NodeId peer, long incarnation, long now) {
        if ((isBooting.getAsBoolean() || withinJoinGrace(peer)) && !everSeenHealthy.contains(peer)) {
            return;
        }

        tombstones.put(peer, new Tombstone(incarnation, now));
    }

    /// PRIMARY tombstone set: stamp the death-record at the FAULTY EDGE (the moment a
    /// member transitions to FAULTY), not at sweep. This closes the FAULTY->sweep
    /// window during which the member is still resident in `members` and an incoming
    /// ALIVE/SUSPECT gossip (or a stray relayed Ack) would otherwise re-admit it via
    /// `applyExistingMember` / `markAliveIfNeeded`, re-firing `HealthyObserved` and
    /// restarting the SUSPECT<->FAULTY oscillation (#231).
    ///
    /// Phase-aware gate (02-chaos S01 never-HEALTHY oscillation, 2026-06; NORMAL-phase
    /// join-grace, 2026-06): a FAULTY id is tombstoned when
    /// `(!isBooting AND !withinJoinGrace) OR everSeenHealthy.contains(peer)`. Concretely:
    /// - In `NORMAL`/`RECOVERING` (`isBooting=false`) once the per-member join-grace has
    ///   expired: ALWAYS tombstone the FAULTY id, even one NEVER observed HEALTHY. This is
    ///   the fix for a replacement node killed inside its JOINING/SYNCING window: its stale
    ///   Hello re-completes, the bare re-seed re-adds it SUSPECT, it expires to FAULTY, gets
    ///   swept, re-adds again — a ~1 Hz SUSPECT<->FAULTY oscillation that republishes a
    ///   generation snapshot on every FAULTY edge so cluster `quiescence` never clears.
    ///   Tombstoning the FAULTY id in NORMAL phase makes every re-add gate refuse it.
    /// - WITHIN the NORMAL-phase join-grace window (a freshly-joined CTM replacement not yet
    ///   confirmed HEALTHY by the leader's round-robin probe): NOT tombstoned, so a premature
    ///   FAULTY before the leader can confirm it does not evict a live replacement. The
    ///   tombstone (and FAULTY emission) is merely DEFERRED to grace expiry.
    /// - In `COLD_BOOT` (`isBooting=true`): preserve the legacy `everSeenHealthy` gate —
    ///   a never-HEALTHY id is NOT tombstoned. A slow cold-boot seed is never-HEALTHY by
    ///   definition; tombstoning it would refuse its legitimate re-add and break cluster
    ///   formation (the invariant a prior non-gated tombstone attempt violated).
    ///
    /// Set ONLY on the FAULTY edge. A SUSPECT flap that later recovers to ALIVE (S04/S13
    /// transient) is never tombstoned because this is never invoked on the SUSPECT edge.
    /// Incarnation/TTL semantics are unchanged; the tombstone-CLEAR paths (self-ANNOUNCE,
    /// higher-incarnation supersede) are untouched, so legitimate rejoin still works.
    private void tombstoneOnFaultyEdge(NodeId peer, long incarnation) {
        if ((isBooting.getAsBoolean() || withinJoinGrace(peer)) && !everSeenHealthy.contains(peer)) {
            return;
        }

        tombstones.put(peer, new Tombstone(incarnation, System.currentTimeMillis()));
        LOG.debug("SWIM tombstone set at FAULTY edge for id {} (incarnation {}, everSeenHealthy={})",
                  peer.id(),
                  incarnation,
                  everSeenHealthy.contains(peer));
    }

    /// Bound tombstone-map growth: drop tombstones older than the TTL. Legitimate
    /// rejoin is handled by self-ANNOUNCE clear / higher-incarnation supersede, so the
    /// TTL only reclaims entries for permanently-dead ids.
    private void sweepExpiredTombstones(long now) {
        var ttlMs = config.suspectTimeout().millis() * TOMBSTONE_TTL_MULTIPLIER;

        tombstones.entrySet().removeIf(entry -> now - entry.getValue()
                                                           .createdAtMs() > ttlMs);
    }

    /// Whether `id` is currently tombstoned against an incoming re-add at
    /// `incomingIncarnation`. Absent -> false. Present but `incomingIncarnation`
    /// strictly exceeds the tombstoned incarnation -> remove and return false
    /// (a genuine restart/refutation at a higher incarnation always wins). Otherwise
    /// -> true (refuse the re-add).
    private boolean isTombstoned(NodeId id, long incomingIncarnation) {
        return option(tombstones.get(id)).map(tombstone -> supersedeOrRefuse(id, tombstone, incomingIncarnation))
                     .or(false);
    }

    private boolean supersedeOrRefuse(NodeId id, Tombstone tombstone, long incomingIncarnation) {
        if (incomingIncarnation > tombstone.incarnation()) {
            tombstones.remove(id);

            return false;
        }

        return true;
    }

    /// Whether an ALIVE-promotion (or re-admit) of `id` at `incomingIncarnation` must
    /// be refused because the id is tombstoned. Thin alias over [#isTombstoned] so the
    /// higher-incarnation supersede semantics are shared by EVERY ALIVE-promotion path
    /// (the `applyUpdate` isEmpty add-path, the `applyExistingMember` regression-toward-
    /// ALIVE path, and the `markAliveIfNeeded` Ack path). A strictly-higher incarnation
    /// still supersedes and re-admits (genuine restart / partition-heal).
    private boolean blockedByTombstone(NodeId id, long incomingIncarnation) {
        return isTombstoned(id, incomingIncarnation);
    }

    /// Wave-6 H8 fix: residency is measured from the FAULTY-edge stamp
    /// ([#faultyStampedAtMs], written on every FAULTY-entry path), NOT from
    /// `suspectTimestamps` — whose entry the suspicion-expiry tick removed in the very
    /// tick that fired the FAULTY edge, sending this method into its "no timestamp →
    /// remove" branch and sweeping the member 2–4 ms after its FAULTY edge (Wave-1
    /// confirmed) instead of honouring the designed `suspectTimeout × 3` window. A
    /// FAULTY member now stays resident (visible and refutable by a higher-incarnation
    /// ALIVE or self-ANNOUNCE) for the full designed window. A FAULTY member with no
    /// stamp (artificial state, e.g. test-injected) is swept immediately — the
    /// pre-fix backstop semantics.
    private boolean isFaultyAndExpired(Map.Entry<NodeId, SwimMember> entry, long now, long threshold) {
        var member = entry.getValue();

        if (member.state() != MemberState.FAULTY) {
            return false;
        }

        return option(faultyStampedAtMs.get(member.nodeId())).map(faultyAt -> now - faultyAt > threshold)
                     .or(true);
    }

    /// Erase all per-peer death-memory when a FAULTY member is removed from the
    /// membership. Without this, a subsequent re-add of the same id (channel
    /// re-seed via `addSeedMember`, or gossip `applyNewMember`) re-creates the
    /// member at incarnation-0 while `everSeenHealthy` still holds it "proven",
    /// so `classify` returns HEALTHY instantly and NTT re-admits the dead node —
    /// the #231 resurrection oscillation. Clearing the gate makes a re-added
    /// ALIVE member classify as UNKNOWN (cold-boot suppression) until it is
    /// re-proven by a real probe-ack.
    private void clearDeathMemory(NodeId peer) {
        everSeenHealthy.remove(peer);
        suspectTimestamps.remove(peer);
        suspicions.remove(peer);
        memberFirstSeenAt.remove(peer);
        lastProbedAt.remove(peer);
        faultyStampedAtMs.remove(peer);
        // Emission-free hygiene (formerly done by the sweep-time DepartedObserved
        // emission, which is gone — the pair fires at the FAULTY edge): drop the
        // last-emitted marker so a genuinely re-joining id re-fires fresh edges.
        lastEmittedHealth.remove(peer);
    }

    /// Whether `peer` is still within its per-member NORMAL-phase JOIN-GRACE window:
    /// it has a recorded first-sighting AND less than `config.joinGrace()` has elapsed
    /// since. A member with no first-sighting entry (should not happen) is NOT within
    /// grace — fail-safe to the pre-grace behavior. Used (together with
    /// `!everSeenHealthy.contains(peer)`) to extend the existing cold-boot FAULTY/tombstone
    /// suppression to a freshly-joined never-HEALTHY member in `NORMAL`/`RECOVERING` phase.
    /// Also exposed (via `CoreSwimHealthDetector.withinJoinGrace`) to the cluster-sync
    /// eviction guard so a within-grace peer — transiently SUSPECT during its first
    /// probe-ack window — is not app-level ping-timeout-evicted before SWIM can probe it.
    public boolean withinJoinGrace(NodeId peer) {
        var graceMs = config.joinGrace().millis();

        return option(memberFirstSeenAt.get(peer)).map(firstSeen -> System.currentTimeMillis() - firstSeen < graceMs)
                     .or(false);
    }

    /// Whether `peer`'s OBSERVED join deadline has passed — the complement of
    /// [#withinJoinGrace]. Gate for the OBSERVED->SUSPECT escalation in [#markSuspect]: an
    /// OBSERVED member is only armed for death once at least `config.joinGrace()` has elapsed
    /// since its first sighting. A member with no first-sighting entry is treated as PAST the
    /// deadline (fail-safe: an OBSERVED member must never sit in immortal limbo — both birth
    /// paths stamp the first-sighting, so this is purely defensive).
    private boolean pastJoinDeadline(NodeId peer) {
        return ! withinJoinGrace(peer);
    }

    // -- Wave-6 Lifeguard: suspicion lifecycle, LHM, dogpile --
    /// Arm (or re-arm) the suspicion of `suspect`: stamp the suspicion start and create
    /// the per-suspect dogpile state. The window bounds are FIXED here: min = base
    /// `suspectTimeout`, max = base × the local LHM multiplier at this moment — a node
    /// that suspects ITSELF slow arms longer windows (more conservative about declaring
    /// peers FAULTY); a healthy node (score 0) arms exactly the base window, so the
    /// genuine-kill detection budget is unchanged. Every decision journaled (Wave-1
    /// pattern).
    private void beginSuspicion(NodeId suspect, NodeId accuser) {
        suspectTimestamps.put(suspect, System.currentTimeMillis());
        var suspicion = newSuspicion(accuser);

        suspicions.put(suspect, suspicion);
        LOG.info("SWIM suspicion start (Wave-6 Lifeguard): suspect {} accused by {}; window {}ms "
                + "(min {}ms, max {}ms, LHM score {}, multiplier x{}, K={})",
                 suspect.id(),
                 accuser.id(),
                 dogpileWindowMs(suspicion),
                 suspicion.minWindowMs(),
                 suspicion.maxWindowMs(),
                 lhmScore.get(),
                 lhmMultiplier(),
                 suspicion.expectedConfirmers());
    }

    /// Disarm the suspicion of `suspect` (refuted, promoted ALIVE, expired to FAULTY,
    /// or swept): remove both the start-stamp and the dogpile state.
    private void endSuspicion(NodeId suspect) {
        suspectTimestamps.remove(suspect);
        suspicions.remove(suspect);
    }

    private Suspicion newSuspicion(NodeId accuser) {
        var baseMs = config.suspectTimeout().millis();
        var expectedConfirmers = Math.max(1,
                                          Math.min(config.dogpileExpectedConfirmers(), members.size() - 1));
        var confirmers = ConcurrentHashMap.<NodeId> newKeySet();

        confirmers.add(accuser);

        return new Suspicion(baseMs, baseMs * lhmMultiplier(), expectedConfirmers, confirmers);
    }

    /// Record an independent dogpile confirmation of an ACTIVE suspicion of `suspect`
    /// by `accuser`. No active suspicion → no-op (the creating update arms it instead).
    private void confirmSuspicion(NodeId suspect, NodeId accuser) {
        option(suspicions.get(suspect)).onPresent(suspicion -> recordConfirmer(suspect, suspicion, accuser));
    }

    private void recordConfirmer(NodeId suspect, Suspicion suspicion, NodeId accuser) {
        var windowBefore = dogpileWindowMs(suspicion);

        if (!suspicion.confirmers().add(accuser)) {
            return;
        }

        LOG.info("SWIM dogpile confirmation (Wave-6 Lifeguard): suspect {} confirmed by {} (C={}, K={}); "
                + "suspicion window {}ms -> {}ms",
                 suspect.id(),
                 accuser.id(),
                 confirmationCount(suspicion),
                 suspicion.expectedConfirmers(),
                 windowBefore,
                 dogpileWindowMs(suspicion));
    }

    /// Current effective suspicion window for `nodeId`: the dogpile-shrunk window of its
    /// active suspicion (or the base timeout when no suspicion state exists, defensive),
    /// scaled by the Fix 3 cluster-size term ([#clusterSizeWindowMultiplier]).
    private long suspicionWindowMs(NodeId nodeId, long baseMs) {
        var dogpileWindow = option(suspicions.get(nodeId)).map(SwimProtocol::dogpileWindowMs).or(baseMs);

        return (long)(dogpileWindow * clusterSizeWindowMultiplier());
    }

    /// Fix 3 (#336 cluster-size suspect-window scaling): the canonical Lifeguard size term
    /// `max(1, ln(N + 1))`, N = live member count (ALIVE peers + self), clamped to
    /// [`SwimConfig#MAX_CLUSTER_SIZE_WINDOW_MULTIPLIER`]. As N grows the round-robin re-probe
    /// interval lengthens toward the fixed base suspect window; widening the window by this
    /// term keeps it several re-probe intervals wide, so a transient probe-ack miss on an
    /// established core no longer ripens to FAULTY as the cluster scales out. Pure function of
    /// N (and, via the dogpile window it multiplies, the LHM factor).
    private double clusterSizeWindowMultiplier() {
        var liveCount = 1 + members.values().stream().filter(member -> member.state() == MemberState.ALIVE).count();

        return Math.min(Math.max(1.0, Math.log(liveCount + 1.0)),
                        SwimConfig.MAX_CLUSTER_SIZE_WINDOW_MULTIPLIER);
    }

    /// Memberlist-style dogpile shrink: `max - (max-min) · log(C+1)/log(K+1)`, clamped
    /// at `min`. C = independent confirmations beyond the originating accuser. A lone
    /// accuser (C=0) keeps the full max window; K independent confirmers converge the
    /// window to min (= base). When max == min (local LHM score 0) the window is flat
    /// at base regardless of confirmations — by design, the stretch (and therefore the
    /// shrink) exists only when the local node suspects itself degraded.
    private static long dogpileWindowMs(Suspicion suspicion) {
        var confirmations = confirmationCount(suspicion);
        var range = suspicion.maxWindowMs() - suspicion.minWindowMs();

        if (range == 0 || confirmations == 0) {
            return suspicion.maxWindowMs();
        }

        var shrink = (long)(range * Math.log(confirmations + 1.0) / Math.log(suspicion.expectedConfirmers() + 1.0));

        return Math.max(suspicion.minWindowMs(), suspicion.maxWindowMs() - shrink);
    }

    /// Confirmations beyond the originating accuser (the originator does not count).
    private static int confirmationCount(Suspicion suspicion) {
        return Math.max(0,
                        suspicion.confirmers().size() - 1);
    }

    /// LHM window multiplier: `min(score + 1, lhmMaxScore)` — both the score AND the
    /// multiplier saturate at `lhmMaxScore` (default 8 → max stretch exactly ×8; score
    /// 7 and score 8 both yield ×8). Documented choice: the spec offered "score cap 8,
    /// multiplier = score+1" vs "score cap 7 for an exact ×8"; this saturating variant
    /// keeps the configured cap as BOTH bounds, tested in SwimProtocolWave6Test.
    private long lhmMultiplier() {
        return Math.min(lhmScore.get() + 1,
                        Math.max(1, config.lhmMaxScore()));
    }

    private void lhmIncrement(String cause) {
        var cap = Math.max(0, config.lhmMaxScore());
        var previous = lhmScore.getAndUpdate(score -> Math.min(score + 1, cap));

        if (previous < cap) {
            LOG.info("SWIM LHM (Wave-6 Lifeguard): local health score {} -> {} (cause: {})",
                     previous,
                     previous + 1,
                     cause);
        }
    }

    private void lhmDecrement(String cause) {
        var previous = lhmScore.getAndUpdate(score -> Math.max(score - 1, 0));

        if (previous > 0) {
            LOG.info("SWIM LHM (Wave-6 Lifeguard): local health score {} -> {} (cause: {})",
                     previous,
                     previous - 1,
                     cause);
        }
    }

    private void expireSuspectIfOverdue(NodeId nodeId, long timestamp, long now, long baseSuspectTimeoutMillis) {
        var windowMs = suspicionWindowMs(nodeId, baseSuspectTimeoutMillis);
        var effectiveTimeoutMs = effectiveSuspectTimeoutMs(nodeId, windowMs);

        if (now - timestamp < effectiveTimeoutMs) {
            return;
        }
        // No join-grace DEFER here anymore (#336/#241): a freshly seeded/announced never-HEALTHY
        // member is held in the OBSERVED birth state (no suspect-window, not death-timer-armed)
        // until its join deadline, so it never reaches this SUSPECT-expiry path within grace. By
        // the time a member IS SUSPECT, either it was ALIVE (genuine probe-timeout), or its join
        // deadline already passed (OBSERVED->SUSPECT escalation, [#markSuspect]), or it was gossiped
        // SUSPECT — all of which correctly proceed to FAULTY here.
        option(members.get(nodeId)).filter(member -> member.state() == MemberState.SUSPECT)
              .onPresent(this::transitionToFaulty);
        endSuspicion(nodeId);
    }

    /// Apply the transport-hint bias to the per-peer suspect window. When
    /// QUIC has reported the peer unreachable, shorten the timeout to the
    /// floor (or the configured default if it is shorter than the floor).
    /// Otherwise keep the configured default. Spec §4.1, §11.
    private long effectiveSuspectTimeoutMs(NodeId nodeId, long defaultMs) {
        return option(transportHints.get(nodeId)).filter(TransportHintState::unreachable)
                     .map(_ -> Math.min(defaultMs, TRANSPORT_HINT_SUSPECT_FLOOR_MS))
                     .or(defaultMs);
    }

    private void transitionToFaulty(SwimMember member) {
        // Fix 2 (#336): this node's own suspect-window expiry is it INDEPENDENTLY concluding
        // the peer is dead — register self as an accuser so a peer suspected ONLY by this one
        // node (no co-confirming gossip) is recognised as under-confirmed by the kill-gate in
        // emitFaultyOrUnknown. A self-originated suspicion already holds self (dedup no-op); a
        // gossip-originated one now reaches two distinct accusers (gossip sender + self).
        confirmSuspicion(member.nodeId(), selfId);
        var faulty = member.withState(MemberState.FAULTY);

        members.put(member.nodeId(), faulty);
        // Wave-6 H8 fix: the FAULTY residency clock is THIS stamp (authoritative, read by
        // isFaultyAndExpired). The pre-fix `suspectTimestamps` re-stamp written here was
        // removed by expireSuspectIfOverdue in the same tick, defeating the designed
        // suspectTimeout×3 residency (Wave-1 measured 2–4 ms) — so it is gone.
        faultyStampedAtMs.put(member.nodeId(), System.currentTimeMillis());
        tombstoneOnFaultyEdge(member.nodeId(), faulty.incarnation());
        // FIRST-HAND: this node's OWN probe cycle expired (handleProbeTimeout → markSuspect →
        // suspect-window expiry → here). Direct local evidence — the death path proceeds unconditionally.
        listener.onMemberFaulty(faulty, true);
        addMemberUpdate(faulty);
        emitFaultyOrUnknown(member.nodeId(), faulty.incarnation(), true);
        detectSelfIsolation();
        LOG.warn("Member {} marked FAULTY",
                 member.nodeId().id());
    }

    /// P2 isolation signature: latch self-isolation when EVERY tracked peer is FAULTY at the
    /// same time (requires ≥1 peer). All-peers-FAULTY is "I am cut off, not them" — the
    /// verdicts accumulated here are isolation-era and must not be gossiped into the cluster
    /// on rejoin (expired in [#acceptAliveEvidence] on the first reconnection evidence).
    private void detectSelfIsolation() {
        if (selfIsolated || members.isEmpty()) {
            return;
        }

        if (members.values().stream().allMatch(m -> m.state() == MemberState.FAULTY)) {
            selfIsolated = true;
            LOG.warn("SWIM self-isolation detected: all {} tracked peers FAULTY simultaneously — "
                    + "treating as local isolation; isolation-era FAULTY verdicts will be expired on rejoin",
                     members.size());
        }
    }

    /// Least-recently-probed (LRP) selection: pick the probable member with the
    /// SMALLEST `lastProbedAt` value so every probable member is probed once per round
    /// regardless of `members.values()` iteration order/size shifting under churn (#94).
    /// A member ABSENT from `lastProbedAt` (never probed) is treated as `Long.MIN_VALUE`
    /// so it sorts first and is probed immediately. Ties broken deterministically by
    /// `NodeId` for stability (so a stable set rotates fairly rather than re-probing one
    /// member). Identity-keyed, so a flapping peer can no longer perturb the schedule of
    /// the others.
    private Option<SwimMember> selectNextProbeTarget() {
        return members.values()
                      .stream()
                      .filter(this::isProbable)
                      .min(Comparator.comparingLong(this::lastProbedAtOf).thenComparing(member -> member.nodeId()
                                                                                                        .id()))
                      .map(Option::option)
                      .orElseGet(Option::none);
    }

    /// Last-probe ordinal for `member`, or `Long.MIN_VALUE` if never probed (sorts
    /// first in [#selectNextProbeTarget] so a fresh/swept-and-readmitted member wins).
    private long lastProbedAtOf(SwimMember member) {
        return option(lastProbedAt.get(member.nodeId())).or(Long.MIN_VALUE);
    }

    private boolean isProbable(SwimMember member) {
        return member.state() == MemberState.ALIVE || member.state() == MemberState.SUSPECT || member.state() == MemberState.OBSERVED;
    }

    private void scheduleProbeTimeout(long seq) {
        SharedScheduler.schedule(() -> onProbeTimeout(seq), config.probeTimeout());
    }

    private void onProbeTimeout(long seq) {
        option(pendingProbes.get(seq)).onPresent(probe -> handleProbeTimeout(seq, probe));
    }

    private void handleProbeTimeout(long seq, PendingProbe probe) {
        if (probe.indirectSent()) {
            // Full probe cycle (direct + indirect) produced no ack: local trouble —
            // this node may itself be the degraded party (Wave-6 Lifeguard LHM).
            lhmIncrement("probe cycle for " + probe.targetId().id() + " timed out with no ack");
            markSuspect(probe.targetId());
            pendingProbes.remove(seq);

            return;
        }

        sendIndirectProbes(seq, probe);
    }

    private void sendIndirectProbes(long seq, PendingProbe probe) {
        pendingProbes.put(seq,
                          PendingProbe.pendingProbe(probe.targetId(), probe.startTime(), true));
        var others = selectRandomOtherMembers(probe.targetId(), config.indirectProbes());
        var pingReq = PingReq.pingReq(selfId, probe.targetId(), seq);

        others.forEach(other -> transport.send(other.address(), pingReq));
        scheduleProbeTimeout(seq);
    }

    private List<SwimMember> selectRandomOtherMembers(NodeId exclude, int count) {
        var candidates = new ArrayList<>(members.values()
                                                .stream()
                                                .filter(m -> !m.nodeId()
                                                               .equals(exclude) && isProbable(m))
                                                .toList());

        Collections.shuffle(candidates);

        return candidates.subList(0,
                                  Math.min(count, candidates.size()));
    }

    private void markSuspect(NodeId nodeId) {
        option(members.get(nodeId)).filter(this::isSuspectEscalation).onPresent(member -> applySuspect(nodeId, member));
    }

    /// Whether a probe-cycle timeout should escalate `member` to SUSPECT. An ALIVE member
    /// always escalates (canonical SWIM probe-timeout). An OBSERVED member (freshly seeded/
    /// announced, never yet confirmed) escalates ONLY once its join deadline has passed
    /// ([#pastJoinDeadline]); before the deadline a probe-timeout leaves it OBSERVED so the
    /// next tick keeps probing — a slow-to-ack joiner is not prematurely armed for death
    /// (#336/#241). SUSPECT/FAULTY members are not re-escalated (idempotent: no clock restart).
    private boolean isSuspectEscalation(SwimMember member) {
        return member.state() == MemberState.ALIVE || (member.state() == MemberState.OBSERVED && pastJoinDeadline(member.nodeId()));
    }

    private void applySuspect(NodeId nodeId, SwimMember member) {
        var suspect = member.withState(MemberState.SUSPECT);

        members.put(nodeId, suspect);
        beginSuspicion(nodeId, selfId);
        listener.onMemberSuspect(suspect);
        addMemberUpdate(suspect);
        emitSuspect(nodeId, suspect.incarnation());
        LOG.warn("Member {} marked SUSPECT", nodeId.id());
    }

    // -- Message handlers --
    private void handlePing(InetSocketAddress sender, Ping ping) {
        inboundProbeReceived = true;
        recordInboundReachability();
        processPiggyback(ping.piggyback(), ping.from());
        var piggyback = piggybackBuffer.peekUpdates(config.maxPiggyback());
        var ack = Ack.ack(selfId, ping.sequence(), piggyback);

        transport.send(sender, ack);
    }

    /// Reflects the kernel-resolved source address back to a joining node. The seed echoes
    /// exactly the address it observed `sender` arrive from, so a node behind a container/VM
    /// hostname can learn the routable address peers actually see.
    private void handleWhoAmI(InetSocketAddress sender, WhoAmI w) {
        LOG.trace("SWIM WhoAmI from {}: reflecting observed source address {}",
                  w.from().id(),
                  sender);
        var reply = WhoAmIReply.whoAmIReply(sender);

        transport.send(sender, reply);
    }

    /// Defensive no-op: this SwimProtocol never initiates WhoAmI requests, so a reply has no
    /// pending request to satisfy. Replies are consumed by a transient client transport (later
    /// task); the case exists only to keep [#onMessage] exhaustive over the sealed message set.
    private void handleWhoAmIReply(WhoAmIReply r) {
        LOG.trace("SWIM unexpected WhoAmIReply (no pending request): {}", r.observedAddress());
    }

    private void handleAck(Ack ack) {
        processPiggyback(ack.piggyback(), ack.from());
        processAckProbe(ack);
        forwardRelay(ack);
    }

    /// Wave-6 Ack.from() check: an Ack only counts as a probe-ack when its sequence
    /// matches a pending probe AND its `from` matches that probe's target. An
    /// unsolicited Ack (no pending probe) is no longer alive-evidence for anyone —
    /// previously it unconditionally promoted `ack.from()` to ALIVE. The piggyback
    /// has already been processed by [#handleAck] (gossip flows regardless).
    private void processAckProbe(Ack ack) {
        option(pendingProbes.get(ack.sequence())).onPresent(probe -> acceptProbeAckIfFromTarget(ack, probe));
    }

    /// A mismatched ack (claimed `from` differs from the probed target) is NOT
    /// alive-evidence for the target and does NOT consume the pending probe — the
    /// timeout path still escalates (indirect probes / SUSPECT) normally. A verified
    /// ack is a healthy local round: the Lifeguard LHM score decays.
    private void acceptProbeAckIfFromTarget(Ack ack, PendingProbe probe) {
        if (!probe.targetId().equals(ack.from())) {
            LOG.warn("SWIM Ack.from mismatch (Wave-6): ack seq {} claims from {} but the probe targeted {} — "
                    + "ignored as alive-evidence",
                     ack.sequence(),
                     ack.from().id(),
                     probe.targetId().id());

            return;
        }

        pendingProbes.remove(ack.sequence());
        // Fix 1 (#336): a verified Ack round-trip proves peers can reach this node right now.
        recordInboundReachability();
        lhmDecrement("verified probe ack from " + ack.from().id());
        acceptAliveEvidence(ack.from());
    }

    /// Direct alive-evidence for `peer` from a VERIFIED probe-ack: promote to ALIVE if
    /// needed; even if already ALIVE, record ever-seen-healthy and emit HealthyObserved
    /// on the first such edge.
    ///
    /// P2 reconnection: a verified probe-ack is genuine proof this node is no longer isolated;
    /// the isolation latch clear + isolation-era FAULTY backlog expiry happen in
    /// [#recordHealthyAndEmit] (the single HEALTHY-edge chokepoint this routes through).
    private void acceptAliveEvidence(NodeId peer) {
        markAliveIfNeeded(peer);
        option(members.get(peer)).filter(m -> m.state() == MemberState.ALIVE)
              .onPresent(m -> recordHealthyAndEmit(m.nodeId(),
                                                   m.incarnation()));
    }

    /// Clear the self-isolation latch on the first reconnection evidence and expire the
    /// isolation-era FAULTY backlog (P2). A no-op when not isolated.
    private void clearIsolationOnReconnect(NodeId peer) {
        if (!selfIsolated) {
            return;
        }

        selfIsolated = false;
        var dropped = piggybackBuffer.expireFaultyUpdates();

        LOG.warn("SWIM rejoin after self-isolation (reconnection evidence from {}): expired {} isolation-era "
                + "FAULTY dissemination entries — not gossiping stale death verdicts into the healed cluster",
                 peer.id(),
                 dropped);
    }

    private void forwardRelay(Ack ack) {
        option(pendingRelays.get(ack.sequence())).onPresent(relay -> forwardAckIfFromTarget(ack, relay));
    }

    /// Wave-6 Ack.from() check on the relay path: the ack answering a relayed probe
    /// must come from the relayed-probe target; a mismatch is neither forwarded nor
    /// counted as alive-evidence (and does not consume the pending relay). On a match
    /// the relayer itself pinged the target directly, so the ack is direct
    /// alive-evidence for the relayer too (pre-fix behavior, now verified).
    private void forwardAckIfFromTarget(Ack ack, RelayInfo relay) {
        if (!relay.targetId().equals(ack.from())) {
            LOG.warn("SWIM Ack.from mismatch (Wave-6, relay): ack seq {} claims from {} but the relayed probe "
                    + "targeted {} — not forwarded, ignored as alive-evidence",
                     ack.sequence(),
                     ack.from().id(),
                     relay.targetId().id());

            return;
        }

        pendingRelays.remove(ack.sequence());
        acceptAliveEvidence(ack.from());
        var forwardAck = Ack.ack(ack.from(), relay.originalSequence(), ack.piggyback());

        transport.send(relay.requesterAddress(), forwardAck);
    }

    private void handlePingReq(InetSocketAddress requesterAddress, PingReq pingReq) {
        option(members.get(pingReq.target())).onPresent(target -> relayPingReq(requesterAddress, pingReq, target));
    }

    private void handleAnnounce(InetSocketAddress sender, Announce announce) {
        var expectedName = config.clusterName();

        // Cross-cluster ANNOUNCE gate. Both sides must CLAIM a name for the comparison to mean
        // anything: an empty expectation is "this node was not told its cluster", and an empty
        // announced name is "the sender did not tell us its cluster" — neither is evidence of a
        // mismatch, and treating them as one would reject honest peers.
        //
        // That asymmetry is what makes arming this upgrade-safe. During a mixed-version window a
        // named node accepts an unnamed peer's ANNOUNCE, and an unnamed node short-circuits on its
        // own empty expectation, so membership survives in both directions; the gate becomes
        // effective only once every node carries a name. Protection is absent during the window,
        // which is exactly the status quo — never a regression.
        //
        // Note this is the ONLY cross-cluster ANNOUNCE isolation: the transport's
        // `isAnnounceAllowed` is a per-source RATE LIMITER, not an allowlist. Accepting a foreign
        // ANNOUNCE clears tombstones and introduces the sender as an observed member, so the
        // realistic failure this catches is a stale or copy-pasted seed list pointing at another
        // cluster's addresses — the wire-level counterpart to `Main.verifyClusterLabelConsistency`.
        if (!expectedName.isEmpty() && !announce.clusterName().isEmpty()
            && !expectedName.equals(announce.clusterName())) {
            LOG.warn("ANNOUNCE from {} rejected: cluster name mismatch (got '{}', expected '{}')",
                     announce.nodeInfo().id().id(),
                     announce.clusterName(),
                     expectedName);

            return;
        }
        // Authoritative liveness: a node announcing ITSELF is proof it is alive.
        // Clear any tombstone UNCONDITIONALLY (whether or not the id is still resident):
        // with the FAULTY-edge tombstone, a killed-then-returning node is frequently
        // still present as FAULTY when its self-ANNOUNCE arrives, so the clear must not
        // be gated on absence. A dead node never self-announces, so this cannot reopen
        // the oscillation; this is what preserves partition-heal (suite 12 S06).
        tombstones.remove(announce.nodeInfo().id());
        if (!members.containsKey(announce.nodeInfo().id())) {
            // Direct liveness evidence: a self-ANNOUNCE datagram is a node speaking for ITSELF
            // (canonical SWIM positive liveness), NOT third-party gossip. Introduce it in the
            // LOCAL-ONLY `OBSERVED` birth state (#336/#241) — no suspect-timer armed at birth, so
            // it never races a later probe-ack toward FAULTY, yet not counted ALIVE/HEALTHY until a
            // real probe-ack confirms it (or a gossiped Alive arrives). Third-party gossip
            // (`applyUpdate`/`applyNewMember`) introduces an unknown ALIVE member ALIVE and an
            // unknown FAULTY member via its co-confirmation path, but an unknown SUSPECT is hearsay
            // and is ALSO born OBSERVED ([#applyNewSuspectMember]) — only our own probing escalates it.
            //
            // #231 invariant preserved: the OBSERVED introduction is routed THROUGH the
            // tombstone gate (`introduceAnnouncedObserved` -> `blockedByTombstone`), never
            // around it. A proven-dead id is therefore NOT revived by a bare ANNOUNCE at
            // an incarnation that does not strictly exceed the tombstone. (The
            // unconditional tombstone-CLEAR above is the partition-heal path and is what a
            // GENUINE returning node relies on; the gate here is the residual safety net.)
            //
            // FIX B — the SWIM probe address is taken from the ANNOUNCE SOURCE IP
            // (kernel-resolved, asymmetry-proof) rather than the gossiped hostname routed
            // through async DNS, falling back to the advertised address when no source IP
            // is available (cold-boot static seeds / NAT). `JoinAnnounced` (below) still
            // fires, so the QUIC reachability probe proceeds — formation is unaffected.
            introduceAnnouncedObserved(announce, swimProbeAddressFor(sender, announce.nodeInfo()));
        } else {
            // Wave 9 Fix C — KNOWN member re-ANNOUNCE: adopt the fresh source-derived probe
            // address if it changed (Docker IP reshuffle on partition-heal). The new-member
            // branch above already adopts it on introduction; this is the symmetric refresh for
            // an already-resident member so SWIM stops probing the stale pre-partition IP (the
            // root of the post-heal false-FAULTY storm).
            refreshProbeAddressIfChanged(announce.nodeInfo().id(),
                                         swimProbeAddressFor(sender, announce.nodeInfo()));
        }
        // Attach the dial-preferred QUIC address: the IP the ANNOUNCE datagram physically
        // arrived from (already-resolved by the OS) combined with the peer's advertised QUIC
        // port — NOT the SWIM source port. This lets the QUIC transport dial a concrete IP
        // instead of synchronously re-resolving the gossiped hostname (membership v2 §5).
        deliverObservation(new SwimObservation.JoinAnnounced(announce.nodeInfo()
                                                                     .withResolvedAddress(resolvedQuicAddress(sender,
                                                                                                              announce.nodeInfo())),
                                                             announce.clusterName(),
                                                             announce.incarnation()));
    }

    /// Derive the dial-preferred QUIC address for an announcing peer: the ANNOUNCE source IP
    /// (resolved by the kernel) combined with the peer's advertised QUIC port. Falls back to
    /// the advertised address when the source IP is unavailable (defensive — never NPEs).
    private NodeAddress resolvedQuicAddress(InetSocketAddress sender, NodeInfo nodeInfo) {
        return Option.option(sender.getAddress())
                     .map(resolvedIp -> new NodeAddress(resolvedIp.getHostAddress(),
                                                        nodeInfo.address().port()))
                     .or(nodeInfo.address());
    }

    /// Derive the authoritative SWIM listen address for a peer from its `NodeInfo`.
    /// `NodeInfo.address()` carries the cluster's primary transport port (e.g. QUIC);
    /// SWIM listens on `port + swimPortOffset`. Applied uniformly at every site that
    /// learns a peer's address from a SWIM control message (ANNOUNCE/Ping/Ack).
    /// Defaults preserve legacy behavior (offset == 0).
    private InetSocketAddress swimAddressFor(NodeInfo nodeInfo) {
        return new InetSocketAddress(nodeInfo.address().host(),
                                     nodeInfo.address().port() + config.swimPortOffset());
    }

    /// FIX B — SWIM probe target for an announcing peer, derived from the ANNOUNCE
    /// SOURCE IP (resolved by the kernel) combined with the SWIM port. The SWIM port
    /// keeps the existing `swimPortOffset` derivation (`advertised QUIC port + offset`),
    /// identical to [#swimAddressFor], so the dial/probe port math stays consistent with
    /// [#dialInfoFor]'s inverse. The source IP is asymmetry-proof: it is the address the
    /// datagram physically arrived from, so the very next probe targets a proven-reachable
    /// IP rather than re-resolving the gossiped hostname through async DNS. Falls back to
    /// the advertised host when no source IP is available (cold-boot static seeds and
    /// NAT/non-Docker envs where source-IP != advertised host) — resolution is never
    /// mandatory.
    private InetSocketAddress swimProbeAddressFor(InetSocketAddress sender, NodeInfo nodeInfo) {
        var swimPort = nodeInfo.address().port() + config.swimPortOffset();

        return Option.option(sender.getAddress())
                     .map(resolvedIp -> new InetSocketAddress(resolvedIp.getHostAddress(),
                                                              swimPort))
                     .or(swimAddressFor(nodeInfo));
    }

    /// Introduce a self-announced member in the LOCAL-ONLY `OBSERVED` birth state (#336/#241),
    /// routed THROUGH the tombstone gate (#231): a proven-dead id whose tombstone the preceding
    /// unconditional clear did not remove — or one at an incarnation that does not strictly exceed
    /// the tombstone — is refused here too, never resurrected off a bare ANNOUNCE. On admission the
    /// member is recorded OBSERVED (no suspect-timer armed at birth), the first-sighting is stamped
    /// (so the join-deadline escalation runs if it never acks), and `notifyMemberJoined` fires (QUIC
    /// dial set + listener). Deliberately NOT counted ALIVE/HEALTHY yet: it does NOT set
    /// `everSeenHealthy`, does NOT emit `HealthyObserved`, and is NOT disseminated (`addMemberUpdate`
    /// drops OBSERVED) — a real probe-ack ([#markAliveIfNeeded]) or a gossiped Alive promotes it to
    /// ALIVE. Like the gossip-SUSPECT-of-unknown path ([#applyNewSuspectMember]), an unconfirmed
    /// member is born OBSERVED; an unknown ALIVE/FAULTY gossip is still honoured in its gossiped state.
    private void introduceAnnouncedObserved(Announce announce, InetSocketAddress probeAddress) {
        if (blockedByTombstone(announce.nodeInfo().id(),
                               announce.incarnation())) {
            return;
        }

        var member = SwimMember.swimMember(announce.nodeInfo().id(),
                                           MemberState.OBSERVED,
                                           announce.incarnation(),
                                           probeAddress,
                                           announce.nodeInfo().labels());

        members.put(member.nodeId(), member);
        memberFirstSeenAt.putIfAbsent(member.nodeId(), System.currentTimeMillis());
        notifyMemberJoined(member);
    }

    /// Wave 9 Fix C — refresh a resident member's SWIM probe address when a re-ANNOUNCE carries a
    /// new source-derived address (the member's IP changed, e.g. Docker reshuffle on
    /// partition-heal). Idempotent: a no-op when the address is unchanged or the member is gone.
    /// Stops the stale-IP probe storm at the root — the next probe targets the proven-reachable
    /// source IP the ANNOUNCE physically arrived from instead of the dead pre-partition IP.
    private void refreshProbeAddressIfChanged(NodeId nodeId, InetSocketAddress freshProbeAddress) {
        Option.option(members.get(nodeId))
              .filter(member -> !member.address()
                                       .equals(freshProbeAddress))
              .onPresent(member -> adoptFreshProbeAddress(member, freshProbeAddress));
    }

    private void adoptFreshProbeAddress(SwimMember member, InetSocketAddress freshProbeAddress) {
        LOG.info("SWIM probe address for {} updated {} -> {} (re-ANNOUNCE source IP changed; partition-heal/IP-reshuffle)",
                 member.nodeId().id(),
                 member.address(),
                 freshProbeAddress);
        members.put(member.nodeId(), member.withAddress(freshProbeAddress));
    }

    /// Notify membership-join to BOTH the membership listener and the observation channel.
    /// The `MemberDiscovered` observation feeds the QUIC dial set so every SWIM-known peer
    /// (gossip-learned included), not only directly-announced ones, gets dialed into the mesh.
    @Contract
    private void notifyMemberJoined(SwimMember member) {
        listener.onMemberJoined(member);
        deliverObservation(new SwimObservation.MemberDiscovered(dialInfoFor(member), member.incarnation()));
    }

    /// Derive the peer's QUIC `NodeInfo` from its SWIM probe address: the QUIC port is the
    /// SWIM port minus `swimPortOffset` (inverse of [swimAddressFor]); prefer the kernel-resolved
    /// IP when present (DNS-free dial), else fall back to the address host string. The member's
    /// stored labels (role/source learned via ANNOUNCE) are hydrated into the reconstructed
    /// `NodeInfo`; an empty label map yields an address-only `NodeInfo` exactly as before.
    private NodeInfo dialInfoFor(SwimMember member) {
        var swimAddr = member.address();
        var quicPort = swimAddr.getPort() - config.swimPortOffset();
        var host = Option.option(swimAddr.getAddress()).map(InetAddress::getHostAddress).or(swimAddr.getHostString());

        return NodeInfo.nodeInfo(member.nodeId(), new NodeAddress(host, quicPort), member.labels());
    }

    /// Send ANNOUNCE to all seeds every 500ms until this node is acknowledged by a peer or 60 attempts are exhausted.
    ///
    /// Runs on the shared scheduler. Stops once this node is acknowledged by a peer (inbound probe) or after 60 attempts (30s).
    @Contract
    public void announceJoin(NodeInfo self, String clusterName, long incarnation, List<InetSocketAddress> seeds) {
        // Seed the durable self-incarnation from the boot incarnation BEFORE the
        // announce loop runs. Monotonic max so a re-announce (or a refutation that
        // already advanced the value) never regresses it.
        selfIncarnation.updateAndGet(cur -> Math.max(cur, incarnation));
        var attempts = new AtomicInteger(0);
        var future = new AtomicReference<ScheduledFuture<?>>();
        var task = SharedScheduler.scheduleAtFixedRate(() -> runAnnounceAttempt(self,
                                                                                clusterName,
                                                                                incarnation,
                                                                                seeds,
                                                                                attempts,
                                                                                future),
                                                       TimeSpan.timeSpan(500).millis());

        future.set(task);
    }

    /// Per-peer health view used by transport-side gates (e.g. `swimHealthGate`
    /// in `QuicClusterNetwork`). Reads the last edge-emitted health; if none has
    /// been emitted yet (startup window between `addSeedMember` and the first
    /// probe-ack edge), fall back to the live `members` map so an ALIVE seed is
    /// reported HEALTHY rather than UNKNOWN. Resolves the 1–2s startup window
    /// where the gate would otherwise reject all peers (P3).
    public SwimHealth healthOf(NodeId nodeId) {
        var emitted = lastEmittedHealth.get(nodeId);

        if (emitted != null) {
            return emitted;
        }

        return option(members.get(nodeId)).map(m -> m.state() == MemberState.ALIVE
                                                    ? SwimHealth.HEALTHY
                                                    : SwimHealth.UNKNOWN)
                     .or(SwimHealth.UNKNOWN);
    }

    private void runAnnounceAttempt(NodeInfo self,
                                    String clusterName,
                                    long incarnation,
                                    List<InetSocketAddress> seeds,
                                    AtomicInteger attempts,
                                    AtomicReference<ScheduledFuture<?>> future) {
        if (inboundProbeReceived) {
            cancelAnnounce(future, self, "self acknowledged by peer");

            return;
        }

        var attempt = attempts.incrementAndGet();

        LOG.info("SWIM ANNOUNCE join attempt {}/60 for node {} to {} seeds",
                 attempt,
                 self.id().id(),
                 seeds.size());
        seeds.forEach(seed -> transport.send(seed, Announce.announce(self, clusterName, incarnation)));
        if (attempt >= 60) {
            cancelAnnounce(future, self, "max attempts reached");
        }
    }

    private void cancelAnnounce(AtomicReference<ScheduledFuture<?>> future, NodeInfo self, String reason) {
        option(future.getAndSet(null)).onPresent(f -> f.cancel(false));
        LOG.info("SWIM ANNOUNCE join stopped for node {} ({})",
                 self.id().id(),
                 reason);
    }

    private void relayPingReq(InetSocketAddress requesterAddress, PingReq pingReq, SwimMember target) {
        var relaySeq = sequenceCounter.incrementAndGet();

        pendingRelays.put(relaySeq,
                          new RelayInfo(pingReq.sequence(),
                                        requesterAddress,
                                        target.nodeId(),
                                        System.currentTimeMillis()));
        var piggyback = piggybackBuffer.peekUpdates(config.maxPiggyback());
        var ping = Ping.ping(selfId, relaySeq, piggyback);

        transport.send(target.address(), ping);
    }

    /// Promote to ALIVE at the member's OBSERVED incarnation (Wave-6 C4 fix).
    ///
    /// The pre-fix `withIncarnation(member.incarnation() + 1)` FABRICATED a third-party
    /// incarnation advance and gossiped `Alive(X, k+1)` for a REMOTE member — only X
    /// itself may advance X's incarnation. A probe/relay ack proves X is alive at the
    /// incarnation X advertises, nothing more. Consequence (canonical SWIM, accepted):
    /// a stale `Suspect(X, k)` piggyback CAN re-suspect X at the same incarnation; the
    /// refutation is X's OWN job — [#handleSelfUpdate] (`max(cur, incoming) + 1`) and
    /// the proactive [#refreshSelfAlive] round. Refutation ordering is preserved:
    /// `Suspect(X, k)` is refuted by X's own `Alive(X, k+1)`, never by a third party.
    ///
    /// Tombstone gate (#231): an Ack relayed for a FAULTY-resident tombstoned id is NOT
    /// proof of genuine liveness — it carries no incarnation for the target, so treat it
    /// as incarnation 0. A tombstoned id is therefore not flipped ALIVE off a stray Ack;
    /// a real return arrives via self-ANNOUNCE (clears the tombstone) or a higher
    /// incarnation (supersedes). A non-tombstoned member is promoted normally.
    private void markAliveIfNeeded(NodeId nodeId) {
        if (blockedByTombstone(nodeId, 0L)) {
            return;
        }

        option(members.get(nodeId)).filter(member -> member.state() != MemberState.ALIVE)
              .onPresent(member -> applyAliveFromAck(nodeId, member));
    }

    private void applyAliveFromAck(NodeId nodeId, SwimMember member) {
        var alive = member.withState(MemberState.ALIVE);

        members.put(nodeId, alive);
        endSuspicion(nodeId);
        faultyStampedAtMs.remove(nodeId);
        addMemberUpdate(alive);
        recordHealthyAndEmit(nodeId, alive.incarnation());
    }

    private void processPiggyback(List<MembershipUpdate> updates, NodeId gossipSender) {
        updates.forEach(update -> applyUpdate(update, gossipSender));
    }

    private void applyUpdate(MembershipUpdate update, NodeId gossipSender) {
        if (selfId.equals(update.nodeId())) {
            handleSelfUpdate(update);

            return;
        }

        var existing = option(members.get(update.nodeId()));

        if (existing.isPresent()) {
            existing.onPresent(member -> applyExistingMember(member, update, gossipSender));
        } else {
            // Tombstone refusal (#231 oscillation): third-party GOSSIP about an unknown
            // id is NOT proof it is alive — survivors still holding a dead id re-gossip
            // it. Refuse re-creation while tombstoned at this incarnation; a strictly
            // higher incarnation supersedes the tombstone (genuine restart). Gated at the
            // call site (not inside `applyNewMember`) so the authoritative self-ANNOUNCE
            // re-add path in `handleAnnounce` stays open.
            if (isTombstoned(update.nodeId(), update.incarnation())) {
                return;
            }

            applyNewMember(update);
        }
    }

    /// Refute a remote suspicion of SELF with a durable, monotonically-advancing
    /// incarnation. The bump is `max(stored, incoming) + 1`, so the refutation always
    /// carries an incarnation STRICTLY GREATER than both the stored self-incarnation
    /// and the incarnation the suspicion was raised at. This is the crux of the fix:
    /// an equal-incarnation `Alive(self, k)` can never supersede a `Suspect(self, k)`
    /// (FAULTY>SUSPECT>ALIVE at equal incarnation), so the prior reactive refutation
    /// re-broadcast the same value forever and was out-ordered — letting a live node
    /// be driven SUSPECT->FAULTY under loss. Storing the bumped value also makes the
    /// proactive self-ALIVE advertisement ([#refreshSelfAlive]) carry the advanced
    /// incarnation on subsequent rounds.
    ///
    /// Stale-suspicion gate (Wave-6 live-gate defect): refute — and count the LHM
    /// "missed refutation traffic" increment — ONLY when the incoming incarnation is
    /// `>=` the current self-incarnation, i.e. the suspicion actually challenges our
    /// CURRENT liveness claim. A suspicion at a LOWER incarnation is a stale
    /// re-broadcast already superseded by an earlier refutation; canonical SWIM
    /// precedence (`Alive(self, k+1)` outranks `Suspect(self, k)`) suppresses it
    /// cluster-wide without our help, so it is ignored entirely: no incarnation
    /// bump (which fed more gossip churn) and no LHM increment (which pinned a
    /// healthy idle node's score at the cap, one increment per replayed receipt).
    /// One genuine suspicion event => exactly one refutation + one LHM increment.
    private void handleSelfUpdate(MembershipUpdate update) {
        if (update.state() != MemberState.SUSPECT && update.state() != MemberState.FAULTY) {
            return;
        }

        if (update.incarnation() < selfIncarnation.get()) {
            LOG.debug("Ignoring stale self-suspicion at incarnation {} (already refuted; current self-incarnation {})",
                      update.incarnation(),
                      selfIncarnation.get());

            return;
        }

        long bumped = selfIncarnation.updateAndGet(cur -> Math.max(cur, update.incarnation()) + 1);

        LOG.warn("Self suspected/faulted by remote node, refuting with incarnation {}", bumped);
        // Wave-6 Lifeguard: a GENUINE (non-stale, gate above) remote suspicion of SELF
        // means this node's liveness traffic was too slow to pre-empt it — local
        // trouble, LHM rises once per suspicion event.
        lhmIncrement("self suspected/faulted by a remote node (missed refutation traffic)");
        addMemberUpdate(MembershipUpdate.membershipUpdate(selfId, MemberState.ALIVE, bumped, selfAddress));
    }

    private void applyNewMember(MembershipUpdate update) {
        var member = SwimMember.swimMember(update.nodeId(), update.state(), update.incarnation(), update.address());

        members.put(update.nodeId(), member);
        memberFirstSeenAt.putIfAbsent(update.nodeId(), System.currentTimeMillis());
        switch (update.state()) {
            case ALIVE -> applyNewAliveMember(member);
            case SUSPECT -> applyNewSuspectMember(member);
            case FAULTY -> applyNewFaultyMember(member);
            // Defensive: OBSERVED is a LOCAL-ONLY birth state and is never serialized
            // ([#addMemberUpdate] drops it), so a gossiped OBSERVED update is impossible.
            // Drop it rather than treat it as a real membership event.
            case OBSERVED -> LOG.warn("SWIM dropping gossiped OBSERVED update for {} — OBSERVED is a " + "local-only birth state and must never arrive on the wire",
                                      update.nodeId().id());
        }
        // Re-broadcast based on the LOCAL stored state, NOT the raw wire update (#336/#241 wire-leak,
        // Finding B): a gossiped SUSPECT-of-unknown is birthed OBSERVED ([#applyNewSuspectMember]) and
        // the guarded [#addMemberUpdate(SwimMember)] overload drops OBSERVED, so an unconfirmed member
        // is never disseminated. FAULTY is not re-broadcast here (unchanged) — the FAULTY edge owns its
        // own co-confirmation/dissemination path.
        if (update.state() != MemberState.FAULTY) {
            rebroadcastStoredMember(update.nodeId());
        }
    }

    /// Re-broadcast a freshly-introduced member by its LOCAL stored state via the guarded
    /// [#addMemberUpdate(SwimMember)] overload (which drops OBSERVED), so a SUSPECT-wire-update
    /// birthed OBSERVED is NOT propagated. A no-op if the member is already gone.
    private void rebroadcastStoredMember(NodeId nodeId) {
        option(members.get(nodeId)).onPresent(this::addMemberUpdate);
    }

    private void applyNewAliveMember(SwimMember member) {
        notifyMemberJoined(member);
        recordHealthyAndEmit(member.nodeId(), member.incarnation());
    }

    /// A gossiped SUSPECT for a member we have NEVER seen is hearsay, not confirmation (#336/#241):
    /// introduce it in the LOCAL-ONLY OBSERVED birth state — identical lifecycle to a seeded/announced
    /// OBSERVED member (no `beginSuspicion`, no armed death-timer; the first-sighting was stamped by
    /// [#applyNewMember], so the join-deadline escalation still runs; probe-eligible). Our OWN
    /// probe-timeout past the join deadline ([#markSuspect]) decides, so a live-but-slow joiner is
    /// never killed on third-party hearsay within grace. The member is stored OBSERVED, so the
    /// re-broadcast in [#applyNewMember] drops it (the wire SUSPECT is NOT propagated). `notifyMemberJoined`
    /// fires (QUIC dial set + listener), exactly as for an ALIVE first-sight join.
    private void applyNewSuspectMember(SwimMember member) {
        var observed = member.withState(MemberState.OBSERVED);

        members.put(observed.nodeId(), observed);
        notifyMemberJoined(observed);
    }

    /// Wave-6 H8: a member first learned as FAULTY still gets the residency stamp so it
    /// stays resident (refutable) for the designed `suspectTimeout × 3` window instead
    /// of being swept on the next tick (it has no suspect timestamp by construction).
    ///
    /// P1 death-path co-confirmation (refined): a FAULTY verdict learned via gossip is SECOND-HAND.
    /// It is downgraded to SUSPECT ONLY when this node's OWN live transport link to the peer
    /// CONTRADICTS it (S06 poison case); ABSENT/DOWN local evidence accepts the verdict (an
    /// unknown/fresh peer or a genuinely-departed victim must NOT be kept artificially counted).
    private void applyNewFaultyMember(SwimMember member) {
        if (isContradictedByLiveTransport(member.nodeId())) {
            downgradeContradictedFaulty(member);

            return;
        }

        faultyStampedAtMs.put(member.nodeId(), System.currentTimeMillis());
        listener.onMemberFaulty(member, false);
        emitFaultyOrUnknown(member.nodeId(), member.incarnation(), false);
    }

    /// Enforce SWIM state priority at same incarnation: FAULTY > SUSPECT > ALIVE > OBSERVED.
    /// At equal incarnation, only allow state progression, not regression. A resident OBSERVED
    /// member is special-cased (see inline): a gossiped `Alive` promotes it, any other gossiped
    /// state is ignored (our own probe-timeout decides), #336/#241.
    private void applyExistingMember(SwimMember existing, MembershipUpdate update, NodeId accuser) {
        if (update.incarnation() < existing.incarnation()) {
            return;
        }
        // OBSERVED is a not-yet-confirmed local placeholder: a gossiped Alive (propagated probe-ack
        // evidence) promotes it (falls through to the normal merge below); a gossiped SUSPECT/FAULTY is
        // NOT adopted — our own probe-timeout past the join deadline decides (A1 / #126 co-confirmation),
        // so a fresh joiner is never killed on third-party hearsay.
        if (existing.state() == MemberState.OBSERVED && update.state() != MemberState.ALIVE) {
            return;
        }
        // Wave-6 dogpile: any non-stale SUSPECT gossip about a peer is an independent
        // confirmation by its sender — recorded BEFORE the same-state dedup below,
        // because rebroadcasts at the same (state, incarnation) are exactly how
        // independent accusers reach this node. Deduped by NodeId inside the
        // suspicion; a no-op when no suspicion is active (the creating update arms
        // it in notifySuspect instead).
        if (update.state() == MemberState.SUSPECT) {
            confirmSuspicion(update.nodeId(), accuser);
        }
        // Tombstone gate (#231 oscillation): refuse a regression TOWARD ALIVE for a
        // tombstoned id while it is still resident (the FAULTY->sweep window). Without
        // this, an ALIVE/SUSPECT gossip arriving before the sweep re-admits a dead
        // proven-healthy id via `notifyStateChange`->`notifyAlive`->`recordHealthyAndEmit`,
        // re-firing `HealthyObserved` and restarting the oscillation. Only re-admits are
        // blocked: FAULTY-progression updates are NOT (they must still advance the member
        // to FAULTY); a strictly-higher incarnation supersedes the tombstone and is
        // allowed (genuine restart). Self-ANNOUNCE clears the tombstone via a separate
        // path (`handleAnnounce`) and is unaffected.
        if (isReAdmitTowardAlive(existing, update) && blockedByTombstone(update.nodeId(), update.incarnation())) {
            return;
        }
        // Same-state same-incarnation: gossip rebroadcast, not a state event.
        // Canonical SWIM: ignore. Otherwise repeated gossip would re-fire listener
        // notifications and reset suspect timers, preventing FAULTY transition.
        if (update.incarnation() == existing.incarnation() && update.state() == existing.state()) {
            return;
        }
        // Same incarnation: only accept if update state has higher or equal priority
        if (update.incarnation() == existing.incarnation() && statePriority(update.state()) < statePriority(existing.state())) {
            return;
        }

        var updated = SwimMember.swimMember(update.nodeId(), update.state(), update.incarnation(), update.address());

        members.put(update.nodeId(), updated);
        notifyStateChange(existing.state(), updated, accuser);
    }

    /// A re-admit toward ALIVE: the resident member is currently FAULTY or SUSPECT and
    /// the update would promote it back to ALIVE. This is the only direction the
    /// tombstone blocks in `applyExistingMember`; FAULTY-progression is never blocked.
    private static boolean isReAdmitTowardAlive(SwimMember existing, MembershipUpdate update) {
        return update.state() == MemberState.ALIVE && existing.state() != MemberState.ALIVE;
    }

    /// State priority for SWIM at EQUAL incarnation: FAULTY(2) > SUSPECT(1) > ALIVE(0) > OBSERVED(-1);
    /// a same-incarnation update is rejected in [#applyExistingMember] only when it has a strictly
    /// LOWER priority than the resident state (no regression).
    ///
    /// OBSERVED is the WEAKEST/most-easily-superseded state (-1) — a LOCAL not-yet-confirmed
    /// placeholder, NOT sticky. At equal incarnation a gossiped `Alive`(0) outranks OBSERVED(-1) and
    /// is NOT rejected, so propagated probe-ack evidence promotes the member out of OBSERVED.
    /// Conversely a (malformed) gossiped OBSERVED(-1) is rejected against every real resident state,
    /// so it can never overwrite a healthy ALIVE/SUSPECT/FAULTY. OBSERVED is promoted to ALIVE by a
    /// real probe-ack ([#markAliveIfNeeded]) OR a gossiped `Alive`, and escalated out of OBSERVED only
    /// by the local join-deadline path ([#markSuspect]); a gossiped SUSPECT/FAULTY is explicitly
    /// ignored for an OBSERVED member in [#applyExistingMember] (#336/#241).
    private static int statePriority(MemberState state) {
        return switch (state) {
            case ALIVE -> 0;
            case SUSPECT -> 1;
            case FAULTY -> 2;
            case OBSERVED -> - 1;
        };
    }

    private void notifyStateChange(MemberState oldState, SwimMember updated, NodeId accuser) {
        if (oldState == updated.state()) {
            return;
        }

        switch (updated.state()) {
            case ALIVE -> notifyAlive(updated);
            case SUSPECT -> notifySuspect(updated, accuser);
            case FAULTY -> notifyFaulty(updated);
            // Defensive: OBSERVED never arrives on the wire, so a gossip-driven state change
            // INTO OBSERVED is impossible; no listener/observation fires for it.
            case OBSERVED -> { /* no-op: OBSERVED is a local-only birth state, never gossiped */ }
        }
    }

    private void notifyAlive(SwimMember updated) {
        // Wave-6 hygiene: the gossip-driven ALIVE re-admit refutes any active suspicion
        // and ends any FAULTY residency — disarm both so no stale timer/stamp lingers.
        endSuspicion(updated.nodeId());
        faultyStampedAtMs.remove(updated.nodeId());
        notifyMemberJoined(updated);
        recordHealthyAndEmit(updated.nodeId(), updated.incarnation());
    }

    private void notifySuspect(SwimMember updated, NodeId accuser) {
        beginSuspicion(updated.nodeId(), accuser);
        listener.onMemberSuspect(updated);
        emitSuspect(updated.nodeId(), updated.incarnation());
    }

    private void notifyFaulty(SwimMember updated) {
        // SECOND-HAND (gossip-disseminated) FAULTY edge. P1 death-path co-confirmation (refined):
        // downgrade to SUSPECT ONLY when this node's OWN live transport link to the peer
        // CONTRADICTS the verdict ("gossip says dead, my QUIC link is up" — the S06 poison case).
        // ABSENT/DOWN local evidence is NOT a contradiction: the gossiped FAULTY is accepted so a
        // real death is not kept artificially counted (auto-heal deficit-stall regression).
        if (isContradictedByLiveTransport(updated.nodeId())) {
            downgradeContradictedFaulty(updated);

            return;
        }

        endSuspicion(updated.nodeId());
        // Wave-6 H8: the gossip-driven FAULTY edge stamps the residency clock too, so the member
        // stays resident (refutable) for the designed window instead of being swept on the next tick.
        faultyStampedAtMs.put(updated.nodeId(), System.currentTimeMillis());
        tombstoneOnFaultyEdge(updated.nodeId(), updated.incarnation());
        listener.onMemberFaulty(updated, false);
        emitFaultyOrUnknown(updated.nodeId(), updated.incarnation(), false);
    }

    /// Live-transport CONTRADICTION of a SECOND-HAND FAULTY verdict (P1, refined). A gossiped
    /// FAULTY is downgraded to SUSPECT ONLY when this node has a LIVE transport connection to the
    /// peer RIGHT NOW — "gossip says dead, but my own QUIC link to it is up" — the exact S06 poison
    /// case (node-1 held live links to accused node-2/3 when a rejoining isolated minority gossiped
    /// their isolation-era FAULTY verdicts). [#transportConnected] is the QUIC network's live
    /// `connectedPeers()` view (default `id -> false` for standalone/tests ⇒ never contradicts).
    ///
    /// ABSENT or DOWN local evidence is NOT a contradiction: when this node has no live link to the
    /// peer (an unknown peer, a fresh joiner whose link hasn't formed, or a genuinely-departed
    /// docker-killed victim), the gossiped FAULTY is ACCEPTED — canonical SWIM dissemination is
    /// preserved, refutation handles any error, and a real death is NOT kept artificially counted
    /// (the auto-heal deficit-stall regression the prior accept-on-absent gate caused). This is why
    /// the predicate keys off POSITIVE live-transport reachability, not the (death-only,
    /// never-`unreachable==false`) `transportHints` map, which cannot express "currently connected".
    private boolean isContradictedByLiveTransport(NodeId peer) {
        return transportConnected.test(peer);
    }

    /// Downgrade a live-transport-CONTRADICTED SECOND-HAND FAULTY to SUSPECT (P1). The member is
    /// kept SUSPECT — counted toward quorum, refutable by the next ALIVE gossip/probe-ack, and
    /// re-probed immediately so this node gathers its OWN evidence rather than terminalizing on
    /// hearsay that its live link contradicts. Emits `SuspectObserved` (not FaultyObserved/
    /// DepartedObserved), so the death-driving observation never fires and `MembershipFsm` stays at
    /// SUSPECT.
    private void downgradeContradictedFaulty(SwimMember member) {
        var suspect = member.withState(MemberState.SUSPECT);

        members.put(member.nodeId(), suspect);
        beginSuspicion(member.nodeId(), selfId);
        LOG.warn("SWIM second-hand FAULTY for {} CONTRADICTED by a live local transport connection — "
                + "downgrading to SUSPECT and re-probing (no death-path emission)",
                 member.nodeId().id());
        emitSuspect(member.nodeId(), suspect.incarnation());
        reProbe(member.nodeId());
    }

    /// Trigger an immediate local probe of `peer` (P1 re-probe). Resolves the current
    /// member descriptor and probes it now, off the periodic tick, so an uncorroborated
    /// second-hand FAULTY downgraded to SUSPECT gathers first-hand evidence promptly. A
    /// no-op if the member is no longer tracked.
    private void reProbe(NodeId peer) {
        option(members.get(peer)).onPresent(this::probeTarget);
    }

    private void addMemberUpdate(SwimMember member) {
        // Wire-leak guard (#336/#241): OBSERVED is a LOCAL-ONLY birth state — a node must never
        // disseminate an unconfirmed member. BOTH addMemberUpdate overloads guard OBSERVED, so it
        // can never reach the piggyback-buffer serialization chokepoint regardless of caller.
        if (member.state() == MemberState.OBSERVED) {
            return;
        }

        piggybackBuffer.addUpdate(MembershipUpdate.membershipUpdate(member.nodeId(),
                                                                    member.state(),
                                                                    member.incarnation(),
                                                                    member.address()));
    }

    private void addMemberUpdate(MembershipUpdate update) {
        // Wire-leak guard (#336/#241): the sibling overload of the OBSERVED drop. Today's callers
        // ([#refreshSelfAlive] / [#handleSelfUpdate]) only ever pass self-ALIVE updates, but guarding
        // here too makes the pair the single OBSERVED-proof serialization chokepoint (Finding B/C).
        if (update.state() == MemberState.OBSERVED) {
            return;
        }

        piggybackBuffer.addUpdate(update);
    }

    /// Pending probe tracking: which node is being probed and whether indirect probes were sent.
    record PendingProbe(NodeId targetId, long startTime, boolean indirectSent) {
        static PendingProbe pendingProbe(NodeId targetId, long startTime, boolean indirectSent) {
            return new PendingProbe(targetId, startTime, indirectSent);
        }
    }

    /// Per-peer transport-hint state. `unreachable=true` shortens this peer's
    /// suspect-window evaluation toward the [`#TRANSPORT_HINT_SUSPECT_FLOOR_MS`] floor.
    record TransportHintState(boolean unreachable, long appliedAtMs) {}

    // -- Observation emission (edge-triggered, P5 idempotent) --
    /// Mark a peer HEALTHY-observed and emit `HealthyObserved` (idempotent
    /// against same-state re-emission). Sets `everSeenHealthy` for the peer
    /// so future FAULTY transitions are no longer cold-boot suppressed.
    ///
    /// P2 reconnection seam: a peer observed HEALTHY again is genuine proof this node is no
    /// longer isolated (a returning peer's re-announcement / probe-ack / gossip-ALIVE refutation).
    /// If self-isolation was latched, clear it and expire the isolation-era FAULTY backlog.
    private void recordHealthyAndEmit(NodeId peer, long incarnation) {
        everSeenHealthy.add(peer);
        // SWIM's own healthy evidence supersedes any stale transport unreachable-bias.
        transportHints.remove(peer);
        clearIsolationOnReconnect(peer);
        emitObservationOnEdge(peer, SwimHealth.HEALTHY, () -> new SwimObservation.HealthyObserved(peer, incarnation));
    }

    /// Emit `SuspectObserved` on edge (idempotent against repeats).
    private void emitSuspect(NodeId peer, long incarnation) {
        emitObservationOnEdge(peer, SwimHealth.SUSPECTED, () -> new SwimObservation.SuspectObserved(peer, incarnation));
    }

    /// Emit FAULTY on edge (paired with `DepartedObserved` — see [#emitFaultyAndDeparted]).
    ///
    /// Phase-aware cold-boot suppression (D.3 three-phase model, 2026-05-11):
    /// - In `COLD_BOOT` phase (`isBooting=true`), preserve the per-peer
    ///   `everSeenHealthy` gate: a peer that has never been observed HEALTHY emits
    ///   `UnknownObserved` so noisy bootstrap-time SWIM transitions do not flood
    ///   `HealthReconciler` with unactionable FAULTY edges.
    /// - In `NORMAL` and `RECOVERING` phases (`isBooting=false`), emit
    ///   `FaultyObserved` regardless of `everSeenHealthy` — EXCEPT when the never-HEALTHY
    ///   peer still has a LIVE transport connection (gate RCA fix, 2026-06-11): then the
    ///   verdict is DEFERRED (`UnknownObserved`, re-evaluated on every subsequent FAULTY
    ///   edge — never latched). FAULTY for a never-healthy peer requires co-confirmation:
    ///   no live transport connection — a busy-but-alive joiner whose first probe-ack is
    ///   starved by the boot deploy-storm must not be false-killed while its transport link
    ///   is provably up. The `RECOVERING` branch is the critical compose-restart fix: peers
    ///   were visible-and-Healthy in the prior `NORMAL` period (their `everSeenHealthy` flag
    ///   is preserved across protocol life), so a post-restart kill must produce a
    ///   cluster-visible `FaultyObserved`. This drives `HealthReconciler` aggregation, the
    ///   `DECOMMISSIONED` write, and the downstream `NODE_LEFT` / `NODE_FAILED` event that
    ///   integration tests depend on.
    ///
    /// The NORMAL-phase JOIN-GRACE suppression that previously lived here is GONE (#336/#241):
    /// a freshly seeded/announced never-HEALTHY member is now held in the OBSERVED birth state
    /// (no suspect-window, not death-timer-armed) until its join deadline, so by the time it
    /// reaches a FAULTY edge in NORMAL phase the grace is structurally already past. The
    /// transport-veto co-confirmation (kept below) remains the sole NORMAL-phase deferral for a
    /// never-healthy peer.
    ///
    /// Fix 2 (#336 reachability-evidence): a NORMAL-phase first-hand FAULTY for an EVER-HEALTHY
    /// peer is additionally held (`coConfirmedFaulty`) until corroborated by ≥2 distinct accusers
    /// or a transport-unreachable hint — so a single prober's transient SUSPECT cannot terminally
    /// depart an established peer.
    private void emitFaultyOrUnknown(NodeId peer, long incarnation, boolean firstHand) {
        var booting = isBooting.getAsBoolean();

        if (booting && !everSeenHealthy.contains(peer)) {
            logColdBootSuppression(peer);
            emitObservationOnEdge(peer, SwimHealth.UNKNOWN, () -> new SwimObservation.UnknownObserved(peer, incarnation));

            return;
        }

        if (!everSeenHealthy.contains(peer) && transportConnected.test(peer)) {
            LOG.info("SWIM transport veto: never-HEALTHY peer {} has a LIVE transport connection — deferring FAULTY (emitting UNKNOWN, re-checked on the next FAULTY edge)",
                     peer.id());
            emitObservationOnEdge(peer, SwimHealth.UNKNOWN, () -> new SwimObservation.UnknownObserved(peer, incarnation));

            return;
        }
        // Fix 2 (#336 co-confirmation kill-gate): an ever-HEALTHY peer must not be terminally
        // departed on this node's lone say-so — hold the death broadcast (emit UNKNOWN) until
        // the verdict is independently corroborated, letting evidence accumulate / recovery happen.
        if (everSeenHealthy.contains(peer) && !coConfirmedFaulty(peer, firstHand)) {
            logUnderConfirmedFaulty(peer);
            emitObservationOnEdge(peer, SwimHealth.UNKNOWN, () -> new SwimObservation.UnknownObserved(peer, incarnation));

            return;
        }

        if (!booting && !everSeenHealthy.contains(peer)) {
            LOG.warn("SWIM phase=NORMAL_OR_RECOVERING: emitting FaultyObserved for never-HEALTHY peer {} (no live transport connection, cold-boot suppression bypassed)",
                     peer.id());
        }

        emitFaultyAndDeparted(peer, incarnation);
        emitClusterFaulty(peer);
    }

    /// Fix 2 (#336 co-confirmation kill-gate): whether an EVER-HEALTHY peer's FAULTY verdict
    /// carries enough independent corroboration to emit the terminal `DepartedObserved`.
    /// - A SECOND-HAND (gossiped) FAULTY is authoritative: the gossip sender already ran a full
    ///   suspicion cycle, and the live-transport contradiction gate ([#notifyFaulty]/
    ///   [#applyNewFaultyMember]) has already cleared it.
    /// - A FIRST-HAND FAULTY (this node's own suspect-window expiry) requires either a
    ///   transport-veto co-confirmation OR at least [`SwimConfig#MIN_FAULTY_CONFIRMERS`] distinct
    ///   accusers in the active suspicion (self counts as one — registered in [#transitionToFaulty]).
    /// Otherwise the verdict is this node's lone say-so about a long-established peer (the #336
    /// false-positive: a transient probe-ack miss at one prober ripening to FAULTY while every
    /// other peer can still reach the core) and the terminal observation is held.
    private boolean coConfirmedFaulty(NodeId peer, boolean firstHand) {
        return ! firstHand || transportVetoConfirms(peer) || distinctAccusers(peer) >= SwimConfig.MIN_FAULTY_CONFIRMERS;
    }

    /// Fix 2: a transport `PeerUnreachable` hint independently corroborates the death (the
    /// high-confidence transport death signal), so a FAULTY edge is not held even if only this
    /// node accused via SWIM.
    private boolean transportVetoConfirms(NodeId peer) {
        return option(transportHints.get(peer)).map(TransportHintState::unreachable)
                     .or(false);
    }

    /// Fix 2: distinct independent accusers recorded for `peer`'s active suspicion (the
    /// originating accuser plus every dogpile confirmer, deduped by NodeId). Zero when no
    /// suspicion is active (defensive).
    private int distinctAccusers(NodeId peer) {
        return option(suspicions.get(peer)).map(suspicion -> suspicion.confirmers()
                                                                      .size())
                     .or(0);
    }

    private void logUnderConfirmedFaulty(NodeId peer) {
        LOG.warn("SWIM co-confirmation kill-gate (#336): holding terminal DepartedObserved for ever-HEALTHY peer {} — "
                + "first-hand FAULTY under-confirmed ({} distinct accuser(s) < {}, no transport veto); emitting UNKNOWN "
                + "and letting evidence accumulate / allowing recovery",
                 peer.id(),
                 distinctAccusers(peer),
                 SwimConfig.MIN_FAULTY_CONFIRMERS);
    }

    /// Emit the FAULTY-edge pair: `FaultyObserved` immediately followed by
    /// `DepartedObserved`, once per actual edge (the shared per-key `compute` keeps
    /// the existing edge-dedup — a FAULTY re-entry emits neither).
    ///
    /// FAULTY IS confirmed death (canonical SWIM): the suspicion window — now
    /// LHM/dogpile-scaled — is the refutation time. The death broadcast therefore
    /// fires AT the FAULTY edge; the H8 residency window that follows exists for
    /// SWIM-map hygiene only (refutability + tombstone correctness), and the sweep
    /// ([#cleanupFaultyMembers]) no longer emits anything. Pre-fix, `DepartedObserved`
    /// was sweep-only, structurally delaying SWIM-driven cluster death (FSM
    /// Suspect→Departing via `SwimDeparted`) by `suspectTimeout × 3` (~30s) after
    /// FAULTY confirmation — measured live as NODE_FAILED at ~55–65s vs the 60s SLO.
    ///
    /// Intended semantic consequence: a member refuted AFTER its FAULTY edge (within
    /// residency) is re-admitted SWIM-side (`HealthyObserved` fires), but the cluster
    /// FSM death already broadcast here is TERMINAL by design — the rejoin path
    /// handles the returning node with a new identity.
    private void emitFaultyAndDeparted(NodeId peer, long incarnation) {
        lastEmittedHealth.compute(peer, (_, prev) -> emitFaultyEdgePair(peer, prev, incarnation));
    }

    private SwimHealth emitFaultyEdgePair(NodeId peer, SwimHealth prev, long incarnation) {
        if (prev == SwimHealth.FAULTY) {
            return prev;
        }

        deliverObservation(new SwimObservation.FaultyObserved(peer, incarnation));
        deliverObservation(new SwimObservation.DepartedObserved(peer, incarnation));

        return SwimHealth.FAULTY;
    }

    /// Cold-boot FAULTY-suppression journal line: in COLD_BOOT phase a never-HEALTHY peer
    /// emits `UnknownObserved` instead of `FaultyObserved` so formation-time churn does not
    /// flood `HealthReconciler`. (The former NORMAL-phase join-grace suppression branch is gone
    /// — that case is now handled before the FAULTY edge by the OBSERVED birth state, #336/#241.)
    private void logColdBootSuppression(NodeId peer) {
        LOG.info("SWIM cold-boot suppression (COLD_BOOT phase): peer {} never observed HEALTHY — emitting UNKNOWN instead of FAULTY",
                 peer.id());
    }

    /// Forward SWIM's FAULTY edge to the cluster-wide `TransportObservation` stream
    /// (`PeerObservedFaulty` with `ObservationSource.SWIM`). Independent of the
    /// SWIM-internal `SwimObservation` delivery — the two streams have different
    /// consumers and the dual emission is intentional. No-op if no emitter is wired.
    private void emitClusterFaulty(NodeId peer) {
        if (transportObservationEmitters.isEmpty()) {
            return;
        }

        var topology = aliveTopologySnapshot();
        var observation = peerObservedFaulty(peer, topology, SWIM);

        transportObservationEmitters.forEach(emitter -> safeEmitTransportObservation(emitter, observation));
    }

    private void safeEmitTransportObservation(TransportObservationEmitter emitter,
                                              org.pragmatica.consensus.topology.TransportObservation observation) {
        Result.lift(Causes::fromThrowable, () -> deliverTransportObservation(emitter, observation)).onFailure(cause -> LOG.warn("Cluster TransportObservation emitter threw: {}",
                                                                                                                                cause.message()));
    }

    private static Unit deliverTransportObservation(TransportObservationEmitter emitter,
                                                    org.pragmatica.consensus.topology.TransportObservation observation) {
        emitter.accept(observation);

        return Unit.unit();
    }

    /// Snapshot of currently-ALIVE peer ids plus self, sorted. Local view; matches
    /// the partial-view semantics of `TransportObservation` (each node emits its own
    /// observations independently).
    private List<NodeId> aliveTopologySnapshot() {
        return Stream.concat(Stream.of(selfId),
                             members.entrySet()
                                    .stream()
                                    .filter(entry -> entry.getValue()
                                                          .state() == MemberState.ALIVE)
                                    .map(Map.Entry::getKey))
                     .sorted(Comparator.comparing(NodeId::id))
                     .toList();
    }

    /// Edge-triggered emission: deliver the observation only if `target` differs
    /// from the previously-emitted health for `peer`. (`DepartedObserved` is emitted
    /// as part of the FAULTY-edge pair in [#emitFaultyAndDeparted], not through this
    /// method — the former sweep-time terminal emission is gone.)
    private void emitObservationOnEdge(NodeId peer, SwimHealth target, Supplier<SwimObservation> factory) {
        // `compute` serializes per-key against concurrent mutations, so two threads
        // racing the same (peer, target) edge cannot both observe `prev != target`
        // and both deliver. Delivering inside the lambda keeps the edge transition
        // and the listener fan-out atomic.
        lastEmittedHealth.compute(peer, (_, prev) -> emitIfEdge(prev, target, factory));
    }

    private SwimHealth emitIfEdge(SwimHealth prev, SwimHealth target, Supplier<SwimObservation> factory) {
        if (prev == target) {
            return prev;
        }

        deliverObservation(factory.get());

        return target;
    }

    private void deliverObservation(SwimObservation observation) {
        observationListeners.forEach(l -> safeDeliver(l, observation));
    }

    private void safeDeliver(Consumer<SwimObservation> consumer, SwimObservation observation) {
        Result.lift(Causes::fromThrowable, () -> deliverOne(consumer, observation)).onFailure(cause -> LOG.warn("SWIM observation listener threw: {}",
                                                                                                                cause.message()));
    }

    private static Unit deliverOne(Consumer<SwimObservation> consumer, SwimObservation observation) {
        consumer.accept(observation);

        return Unit.unit();
    }

    /// Test-only accessor for the per-peer ever-seen-healthy flag. Use to
    /// validate cold-boot suppression invariants from unit tests.
    boolean everSeenHealthyForTest(NodeId peer) {
        return everSeenHealthy.contains(peer);
    }

    /// Test-only: number of FAULTY updates currently buffered for dissemination. Used by the P2
    /// isolation-era expiry tests to assert the backlog is purged on rejoin.
    int piggybackFaultyCountForTest() {
        return piggybackBuffer.faultyCount();
    }

    /// Test-only: whether the self-isolation latch (P2) is currently set.
    boolean selfIsolatedForTest() {
        return selfIsolated;
    }

    /// Test-only accessor for the per-peer suspect-timestamp map. Used by
    /// transport-hint tests to validate the timer-bias mechanism deterministically.
    Option<Long> suspectTimestampForTest(NodeId peer) {
        return option(suspectTimestamps.get(peer));
    }

    /// Test-only accessor for the per-peer tombstone state. Used by the
    /// anti-oscillation regression tests to assert tombstone set/clear/supersede.
    boolean tombstonedForTest(NodeId peer) {
        return tombstones.containsKey(peer);
    }

    /// Test-only: current Wave-6 Lifeguard local-health-multiplier score.
    int lhmScoreForTest() {
        return lhmScore.get();
    }

    /// Test-only: current effective (dogpile-shrunk) suspicion window for `peer`,
    /// empty when no suspicion is active. Used by the Wave-6 Lifeguard tests to
    /// assert LHM stretch / dogpile shrink deterministically without timing.
    Option<Long> suspicionWindowForTest(NodeId peer) {
        return option(suspicions.get(peer)).map(SwimProtocol::dogpileWindowMs);
    }

    /// This node's current durable self-incarnation (boot-seeded monotonic generation;
    /// advanced on self-refutation). Single authority for `(NodeId, incarnation)` identity —
    /// consumed by SWIM membership AND the metrics readiness epoch. Returns 0 before
    /// `announceJoin` seeds it (pre-announce sentinel).
    public long selfIncarnation() {
        return selfIncarnation.get();
    }

    /// Test-only: run one least-recently-probed selection-and-probe cycle (the
    /// `selectNextProbeTarget().onPresent(probeTarget)` half of [#tick], without the
    /// refresh/expire/cleanup housekeeping). Used by the probe-fairness regression tests
    /// to drive selection deterministically without scheduler timing. Returns the selected
    /// target (if any) so a test can assert which member was chosen.
    Option<SwimMember> probeOnceForTest() {
        var selected = selectNextProbeTarget();

        selected.onPresent(this::probeTarget);

        return selected;
    }

    /// Test-only: introduce a member directly into the membership map in the given state,
    /// bypassing the seed/gossip/announce admission gates. Used by the probe-fairness
    /// regression tests to set up and churn a controlled membership set.
    @Contract
    void putMemberForTest(NodeId nodeId, InetSocketAddress address, MemberState state) {
        members.put(nodeId, SwimMember.swimMember(nodeId, state, 0, address));
    }

    /// Test-only: remove a member directly from the membership map (and its probe
    /// schedule entry), simulating a gossip-driven removal. Used by the probe-fairness
    /// regression tests to churn a flapping member between selections.
    @Contract
    void removeMemberForTest(NodeId nodeId) {
        members.remove(nodeId);
        lastProbedAt.remove(nodeId);
    }
}
