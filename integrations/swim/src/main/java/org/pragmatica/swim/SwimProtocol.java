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
    private final Map<NodeId, SwimMember> members = new ConcurrentHashMap<>();
    private final Map<Long, PendingProbe> pendingProbes = new ConcurrentHashMap<>();
    private final Map<Long, RelayInfo> pendingRelays = new ConcurrentHashMap<>();
    private final Map<NodeId, Long> suspectTimestamps = new ConcurrentHashMap<>();
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
    private final AtomicReference<Option<ScheduledFuture<?>>> tickFuture = new AtomicReference<>(none());
    private final AtomicInteger probeIndex = new AtomicInteger(0);
    /// Single false->true latch set on the first inbound SWIM Ping (proof a peer
    /// has acknowledged this node). Read by [#runAnnounceAttempt] on the scheduler
    /// thread to cancel the join-announce loop once self is acknowledged — fixes a
    /// replacement joining an already-quorate cluster never sending an ANNOUNCE
    /// because the quorum predicate is satisfied at join time. Volatile: written on
    /// the message thread, read on the scheduler thread.
    private volatile boolean inboundProbeReceived = false;
    /// Incarnation-aware, TTL-bounded tombstones for ids that were PROVEN HEALTHY
    /// and then died and got cleaned up. Refuses third-party re-introduction of a
    /// dead id (gossip dissemination + bare channel re-seed) that would otherwise
    /// re-create it as SUSPECT and restart the SUSPECT<->FAULTY oscillation (#231).
    /// Crucially, a never-HEALTHY id is NEVER tombstoned: it may be a cold-boot seed
    /// that is simply slow to form, and tombstoning it would break cluster formation
    /// (the reason an earlier non-gated tombstone attempt was reverted). The
    /// tombstone is cleared by an authoritative self-ANNOUNCE (partition-heal) and
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
    /// Per-peer transport-hint state. PeerUnreachable shortens the suspect
    /// window for the peer toward [`#TRANSPORT_HINT_SUSPECT_FLOOR_MS`];
    /// PeerReachable removes the bias.
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
    public interface TransportObservationEmitter
        extends Consumer<org.pragmatica.consensus.topology.TransportObservation> {}
    /// Serializes `start()` / `stop()` against each other so a `start()` racing
    /// concurrent `start()` cannot double-schedule, and a `start()` racing a
    /// `stop()` cannot leave the protocol running after `stop()` returns.
    /// The internal `getAndSet` / `compareAndSet` pattern remains inside the lock.
    private final Object lifecycleLock = new Object();

    /// Tracks a relayed PingReq: maps the relay's own sequence to the original requester info.
    private record RelayInfo(long originalSequence, InetSocketAddress requesterAddress, long createdAt) {}

    /// Death-record for a PROVEN-HEALTHY id that died and was cleaned up. `incarnation`
    /// is the incarnation at which the id was tombstoned; a strictly-higher incoming
    /// incarnation supersedes it. `createdAtMs` bounds map growth via the TTL sweep.
    private record Tombstone(long incarnation, long createdAtMs) {}

    private SwimProtocol(SwimConfig config,
                         SwimTransport transport,
                         SwimMembershipListener listener,
                         NodeId selfId,
                         InetSocketAddress selfAddress,
                         BooleanSupplier isBooting) {
        this.config = config;
        this.transport = transport;
        this.listener = listener;
        this.selfId = selfId;
        this.selfAddress = selfAddress;
        this.piggybackBuffer = PiggybackBuffer.piggybackBuffer(config.maxPiggyback());
        this.isBooting = isBooting;
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
    /// regardless. See audit Step 6 (2026-05-07).
    public static Result<SwimProtocol> swimProtocol(SwimConfig config,
                                                    SwimTransport transport,
                                                    SwimMembershipListener listener,
                                                    NodeId selfId,
                                                    InetSocketAddress selfAddress,
                                                    BooleanSupplier isBooting) {
        return Result.success(new SwimProtocol(config, transport, listener, selfId, selfAddress, isBooting));
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
                     selfId.id(), jitteredStartupMs, config.startupDelay().millis());
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
    /// Resurrection guard (#231): a channel re-seed is NOT proof of reachability,
    /// so the member is introduced as SUSPECT (probe-on-arrival) rather than ALIVE,
    /// mirroring the ANNOUNCE path (`handleAnnounce`). SUSPECT keeps the member
    /// `isProbable` so the next tick probes it; a real probe-ack or QUIC
    /// `PeerConnected` promotes it to ALIVE/HEALTHY. SUSPECT does NOT set
    /// `everSeenHealthy` and does NOT emit `HealthyObserved`, so a previously-dead
    /// id re-seeded onto the channel is never re-admitted as HEALTHY off a bare add.
    ///
    /// Cold-start is preserved: `notifyMemberJoined` (and its `MemberDiscovered`
    /// observation that feeds the QUIC dial set) still fires, and the registered
    /// suspect timestamp lets the next probe-ack promote a genuine seed to ALIVE
    /// within one probe period.
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
        // SUSPECT (which would restart the SUSPECT<->FAULTY oscillation). A genuine
        // rejoin arrives via self-ANNOUNCE (`handleAnnounce`), which clears the tombstone.
        if (isTombstoned(nodeId, 0L)) {
            return;
        }

        var member = SwimMember.swimMember(nodeId, MemberState.SUSPECT, 0, address);
        members.put(nodeId, member);
        suspectTimestamps.put(nodeId, System.currentTimeMillis());
        notifyMemberJoined(member);
        addMemberUpdate(member);
    }

    /// Return an unmodifiable snapshot of the current membership.
    public Map<NodeId, SwimMember> members() {
        return Collections.unmodifiableMap(members);
    }

    /// Register a push-channel listener for [`SwimObservation`] edge transitions.
    /// Listeners are invoked once per actual edge — same-state re-emission is
    /// suppressed (P5 idempotent edge transitions, spec §4.2).
    @Contract public void addObservationListener(Consumer<SwimObservation> listener) {
        observationListeners.add(listener);
    }

    /// Register an emitter for the cluster-wide
    /// `org.pragmatica.consensus.topology.TransportObservation` stream. Invoked from
    /// [#emitFaultyOrUnknown] when SWIM transitions a peer to FAULTY (paired with the
    /// SWIM-internal `SwimObservation.FaultyObserved` delivered to `observationListeners`).
    /// Wired by the Aether node assembly to the cluster `MessageRouter`.
    @Contract public void addTransportObservationEmitter(TransportObservationEmitter emitter) {
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
    /// SWIM remains authoritative. `PeerUnreachable` biases this peer's
    /// suspect-window timer toward the [`#TRANSPORT_HINT_SUSPECT_FLOOR_MS`]
    /// floor; `PeerReachable` removes the bias.
    /// SWIM never blindly adopts QUIC's verdict — its own gossip-aggregated
    /// state remains the source of truth.
    @Contract public void recordTransportHint(NodeId peer, TransportObservation hint) {
        if (selfId.equals(peer)) {
            return;
        }

        switch (hint) {
            case TransportObservation.PeerReachable _ -> applyReachableHint(peer);
            case TransportObservation.PeerUnreachable _ -> applyUnreachableHint(peer);
        }
    }

    private void applyUnreachableHint(NodeId peer) {
        transportHints.put(peer, new TransportHintState(true, System.currentTimeMillis()));
        LOG.debug("SWIM transport hint: peer {} reported unreachable; suspect window biased to {}ms floor",
                  peer.id(), TRANSPORT_HINT_SUSPECT_FLOOR_MS);
    }

    private void applyReachableHint(NodeId peer) {
        transportHints.put(peer, new TransportHintState(false, System.currentTimeMillis()));
        // Accelerate exit-from-SUSPECT if the peer is currently SUSPECT and the
        // bias would expire its suspect window earlier than the default.
        // SWIM remains authoritative: this only nudges timers, never overrides
        // gossip state directly.
        option(members.get(peer))
            .filter(m -> m.state() == MemberState.SUSPECT)
            .onPresent(_ -> shortenSuspectExpiry(peer));
        LOG.debug("SWIM transport hint: peer {} reported reachable; bias removed", peer.id());
    }

    private void shortenSuspectExpiry(NodeId peer) {
        // Backdate the suspect timestamp so the next tick re-evaluates within
        // the floor window. Authoritative state is unchanged — only the timer
        // shifts forward (i.e., timestamp moves earlier in time).
        option(suspectTimestamps.get(peer)).onPresent(ts -> shortenSuspectExpiryWith(peer, ts));
    }

    private void shortenSuspectExpiryWith(NodeId peer, long ts) {
        var defaultMs = config.suspectTimeout().millis();
        if (defaultMs <= TRANSPORT_HINT_SUSPECT_FLOOR_MS) {
            return;
        }
        // Apparent age becomes (default - floor); only `floor` ms remain before
        // the next expiry check. Only backdate (never push timestamp forward).
        var biasedTs = System.currentTimeMillis() - (defaultMs - TRANSPORT_HINT_SUSPECT_FLOOR_MS);
        if (biasedTs >= ts) {
            return;
        }
        suspectTimestamps.put(peer, biasedTs);
    }

    private SwimHealth classify(NodeId peer, SwimMember member) {
        if (!everSeenHealthy.contains(peer) && member.state() != MemberState.ALIVE) {
            return SwimHealth.UNKNOWN;
        }
        return switch (member.state()) {
            case ALIVE -> SwimHealth.HEALTHY;
            case SUSPECT -> SwimHealth.SUSPECTED;
            case FAULTY -> SwimHealth.FAULTY;
        };
    }

    // -- SwimMessageHandler --

    @Override
    public void onMessage(InetSocketAddress sender, SwimMessage message) {
        LOG.trace("SWIM recv from {}: {}", sender, message.getClass().getSimpleName());
        switch (message) {
            case Ping ping -> handlePing(sender, ping);
            case Ack ack -> handleAck(ack);
            case PingReq pingReq -> handlePingReq(sender, pingReq);
            case Announce announce -> handleAnnounce(sender, announce);
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
        addMemberUpdate(MembershipUpdate.membershipUpdate(selfId, MemberState.ALIVE, incarnation, selfAddress));
    }

    private void probeTarget(SwimMember target) {
        var seq = sequenceCounter.incrementAndGet();
        var piggyback = piggybackBuffer.peekUpdates(config.maxPiggyback());
        var ping = Ping.ping(selfId, seq, piggyback);

        pendingProbes.put(seq, PendingProbe.pendingProbe(target.nodeId(), System.currentTimeMillis(), false));
        transport.send(target.address(), ping);

        scheduleProbeTimeout(seq);
    }

    private void expireSuspectMembers() {
        var now = System.currentTimeMillis();
        var suspectTimeoutMillis = config.suspectTimeout().millis();

        suspectTimestamps.forEach((nodeId, timestamp) -> expireSuspectIfOverdue(nodeId, timestamp, now, suspectTimeoutMillis));
        // Clean up stale relays by age, not by pendingProbes presence
        var relayTimeoutMillis = config.probeTimeout().millis() * 3;
        pendingRelays.entrySet().removeIf(entry -> now - entry.getValue().createdAt() > relayTimeoutMillis);
    }

    /// Remove FAULTY members after suspect timeout to prevent unbounded growth.
    /// Emits `DepartedObserved` once per removed peer.
    private void cleanupFaultyMembers() {
        var now = System.currentTimeMillis();
        var cleanupThreshold = config.suspectTimeout().millis() * 3;

        var iterator = members.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (isFaultyAndExpired(entry, now, cleanupThreshold)) {
                iterator.remove();
                tombstoneIfWasHealthy(entry.getKey(), entry.getValue().incarnation(), now);
                clearDeathMemory(entry.getKey());
                emitDeparted(entry.getKey(), entry.getValue().incarnation());
            }
        }

        sweepExpiredTombstones(now);
    }

    /// Tombstone a removed FAULTY id ONLY if it was PROVEN HEALTHY at some point.
    /// MUST be called BEFORE `clearDeathMemory` (which erases `everSeenHealthy`). A
    /// never-HEALTHY id is a cold-boot seed that may simply be slow to form, so it is
    /// NOT tombstoned — tombstoning it would break formation (the prior tombstone
    /// attempt was reverted for exactly this). Only a node that actually lived and
    /// then died is tombstoned, blocking its third-party resurrection (#231).
    ///
    /// Sweep-time backstop: the PRIMARY tombstone is now set at the FAULTY edge
    /// ([#tombstoneIfProvenHealthy]) so a re-admit during the FAULTY->sweep window is
    /// refused. This sweep-time set is retained as a backstop for the rare path where
    /// a member reaches the sweep still FAULTY without having traversed an instrumented
    /// FAULTY edge (idempotent: re-stamping at the same incarnation is harmless).
    private void tombstoneIfWasHealthy(NodeId peer, long incarnation, long now) {
        if (everSeenHealthy.contains(peer)) {
            tombstones.put(peer, new Tombstone(incarnation, now));
        }
    }

    /// PRIMARY tombstone set: stamp the death-record at the FAULTY EDGE (the moment a
    /// member transitions to FAULTY), not at sweep. This closes the FAULTY->sweep
    /// window during which the member is still resident in `members` and an incoming
    /// ALIVE/SUSPECT gossip (or a stray relayed Ack) would otherwise re-admit it via
    /// `applyExistingMember` / `markAliveIfNeeded`, re-firing `HealthyObserved` and
    /// restarting the SUSPECT<->FAULTY oscillation (#231).
    ///
    /// Set ONLY on the FAULTY edge and ONLY for a PROVEN-HEALTHY id (`everSeenHealthy`).
    /// A SUSPECT flap that later recovers to ALIVE (S04/S13 transient) is never
    /// tombstoned because this is never invoked on the SUSPECT edge. A never-HEALTHY
    /// cold-boot seed is never tombstoned because of the `everSeenHealthy` gate.
    private void tombstoneIfProvenHealthy(NodeId peer, long incarnation) {
        if (everSeenHealthy.contains(peer)) {
            tombstones.put(peer, new Tombstone(incarnation, System.currentTimeMillis()));
            LOG.debug("SWIM tombstone set at FAULTY edge for proven-healthy id {} (incarnation {})",
                      peer.id(), incarnation);
        }
    }

    /// Bound tombstone-map growth: drop tombstones older than the TTL. Legitimate
    /// rejoin is handled by self-ANNOUNCE clear / higher-incarnation supersede, so the
    /// TTL only reclaims entries for permanently-dead ids.
    private void sweepExpiredTombstones(long now) {
        var ttlMs = config.suspectTimeout().millis() * TOMBSTONE_TTL_MULTIPLIER;
        tombstones.entrySet().removeIf(entry -> now - entry.getValue().createdAtMs() > ttlMs);
    }

    /// Whether `id` is currently tombstoned against an incoming re-add at
    /// `incomingIncarnation`. Absent -> false. Present but `incomingIncarnation`
    /// strictly exceeds the tombstoned incarnation -> remove and return false
    /// (a genuine restart/refutation at a higher incarnation always wins). Otherwise
    /// -> true (refuse the re-add).
    private boolean isTombstoned(NodeId id, long incomingIncarnation) {
        return option(tombstones.get(id))
            .map(tombstone -> supersedeOrRefuse(id, tombstone, incomingIncarnation))
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

    private boolean isFaultyAndExpired(Map.Entry<NodeId, SwimMember> entry, long now, long threshold) {
        var member = entry.getValue();

        if (member.state() != MemberState.FAULTY) {
            return false;
        }

        // Remove if no suspectTimestamp exists (already cleaned) or if it's old enough
        return option(suspectTimestamps.get(member.nodeId()))
            .map(suspectTime -> now - suspectTime > threshold)
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
    }

    private void expireSuspectIfOverdue(NodeId nodeId, long timestamp, long now, long suspectTimeoutMillis) {
        var effectiveTimeoutMs = effectiveSuspectTimeoutMs(nodeId, suspectTimeoutMillis);
        if (now - timestamp < effectiveTimeoutMs) {
            return;
        }

        option(members.get(nodeId))
            .filter(member -> member.state() == MemberState.SUSPECT)
            .onPresent(this::transitionToFaulty);

        suspectTimestamps.remove(nodeId);
    }

    /// Apply the transport-hint bias to the per-peer suspect window. When
    /// QUIC has reported the peer unreachable, shorten the timeout to the
    /// floor (or the configured default if it is shorter than the floor).
    /// Otherwise keep the configured default. Spec §4.1, §11.
    private long effectiveSuspectTimeoutMs(NodeId nodeId, long defaultMs) {
        return option(transportHints.get(nodeId))
            .filter(TransportHintState::unreachable)
            .map(_ -> Math.min(defaultMs, TRANSPORT_HINT_SUSPECT_FLOOR_MS))
            .or(defaultMs);
    }

    private void transitionToFaulty(SwimMember member) {
        var faulty = member.withState(MemberState.FAULTY);
        members.put(member.nodeId(), faulty);
        suspectTimestamps.put(member.nodeId(), System.currentTimeMillis());
        tombstoneIfProvenHealthy(member.nodeId(), faulty.incarnation());
        listener.onMemberFaulty(faulty);
        addMemberUpdate(faulty);
        emitFaultyOrUnknown(member.nodeId(), faulty.incarnation());
        LOG.warn("Member {} marked FAULTY", member.nodeId().id());
    }

    /// Round-robin selection: each member probed exactly once per round.
    private Option<SwimMember> selectNextProbeTarget() {
        var candidates = members.values().stream()
                                .filter(this::isProbable)
                                .toList();

        if (candidates.isEmpty()) {
            return none();
        }

        // Capture size locally so a concurrent membership change between modulo and get()
        // cannot drive the index out of bounds. `Math.floorMod` keeps the index non-negative
        // across the eventual `getAndIncrement` overflow at Integer.MIN_VALUE.
        var size = candidates.size();
        var index = Math.floorMod(probeIndex.getAndIncrement(), size);
        return option(candidates.get(index));
    }

    private boolean isProbable(SwimMember member) {
        return member.state() == MemberState.ALIVE || member.state() == MemberState.SUSPECT;
    }

    private void scheduleProbeTimeout(long seq) {
        SharedScheduler.schedule(() -> onProbeTimeout(seq), config.probeTimeout());
    }

    private void onProbeTimeout(long seq) {
        option(pendingProbes.get(seq))
            .onPresent(probe -> handleProbeTimeout(seq, probe));
    }

    private void handleProbeTimeout(long seq, PendingProbe probe) {
        if (probe.indirectSent()) {
            markSuspect(probe.targetId());
            pendingProbes.remove(seq);
            return;
        }

        sendIndirectProbes(seq, probe);
    }

    private void sendIndirectProbes(long seq, PendingProbe probe) {
        pendingProbes.put(seq, PendingProbe.pendingProbe(probe.targetId(), probe.startTime(), true));

        var others = selectRandomOtherMembers(probe.targetId(), config.indirectProbes());
        var pingReq = PingReq.pingReq(selfId, probe.targetId(), seq);

        others.forEach(other -> transport.send(other.address(), pingReq));

        scheduleProbeTimeout(seq);
    }

    private List<SwimMember> selectRandomOtherMembers(NodeId exclude, int count) {
        var candidates = new ArrayList<>(members.values().stream()
                                                 .filter(m -> !m.nodeId().equals(exclude) && isProbable(m))
                                                 .toList());

        Collections.shuffle(candidates);
        return candidates.subList(0, Math.min(count, candidates.size()));
    }

    private void markSuspect(NodeId nodeId) {
        option(members.get(nodeId))
            .filter(member -> member.state() == MemberState.ALIVE)
            .onPresent(member -> applySuspect(nodeId, member));
    }

    private void applySuspect(NodeId nodeId, SwimMember member) {
        var suspect = member.withState(MemberState.SUSPECT);
        members.put(nodeId, suspect);
        suspectTimestamps.put(nodeId, System.currentTimeMillis());
        listener.onMemberSuspect(suspect);
        addMemberUpdate(suspect);
        emitSuspect(nodeId, suspect.incarnation());
        LOG.warn("Member {} marked SUSPECT", nodeId.id());
    }

    // -- Message handlers --

    private void handlePing(InetSocketAddress sender, Ping ping) {
        inboundProbeReceived = true;
        processPiggyback(ping.piggyback());
        var piggyback = piggybackBuffer.peekUpdates(config.maxPiggyback());
        var ack = Ack.ack(selfId, ping.sequence(), piggyback);
        transport.send(sender, ack);
    }

    private void handleAck(Ack ack) {
        processPiggyback(ack.piggyback());
        processAckProbe(ack);
        forwardRelay(ack);
    }

    private void processAckProbe(Ack ack) {
        pendingProbes.remove(ack.sequence());
        markAliveIfNeeded(ack.from());
        // Even if member was already ALIVE, ack is positive evidence — record
        // ever-seen-healthy and emit HealthyObserved on the first such edge.
        option(members.get(ack.from()))
            .filter(m -> m.state() == MemberState.ALIVE)
            .onPresent(m -> recordHealthyAndEmit(m.nodeId(), m.incarnation()));
    }

    private void forwardRelay(Ack ack) {
        option(pendingRelays.remove(ack.sequence()))
            .onPresent(relay -> forwardAckToRequester(ack, relay));
    }

    private void forwardAckToRequester(Ack ack, RelayInfo relay) {
        var forwardAck = Ack.ack(ack.from(), relay.originalSequence(), ack.piggyback());
        transport.send(relay.requesterAddress(), forwardAck);
    }

    private void handlePingReq(InetSocketAddress requesterAddress, PingReq pingReq) {
        option(members.get(pingReq.target()))
            .onPresent(target -> relayPingReq(requesterAddress, pingReq, target));
    }

    private void handleAnnounce(InetSocketAddress sender, Announce announce) {
        var expectedName = config.clusterName();
        if (!expectedName.isEmpty() && !expectedName.equals(announce.clusterName())) {
            LOG.warn("ANNOUNCE from {} rejected: cluster name mismatch (got '{}', expected '{}')",
                     announce.nodeInfo().id().id(), announce.clusterName(), expectedName);
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
            // Resurrection guard: a bare ANNOUNCE is gossip, NOT proof of reachability.
            // Introduce the unknown member as SUSPECT (probe-on-arrival) rather than
            // ALIVE. SUSPECT keeps the member `isProbable` so the next tick probes it;
            // a real probe-ack (`processAckProbe`) or a QUIC `PeerConnected` promotes it
            // to ALIVE/HEALTHY. Crucially SUSPECT does NOT set `everSeenHealthy` and does
            // NOT emit `HealthyObserved`, so a dead node re-announced via stale gossip is
            // never re-admitted as HEALTHY off a bare join. If the node is genuinely
            // unreachable the suspect-window expiry drives it to FAULTY/UNKNOWN.
            // `JoinAnnounced` (below) still fires, so the legitimate reachability probe
            // (`clusterNetwork.connect`) proceeds — formation is unaffected.
            var update = MembershipUpdate.membershipUpdate(
                announce.nodeInfo().id(), MemberState.SUSPECT, announce.incarnation(),
                swimAddressFor(announce.nodeInfo()));
            applyNewMember(update);
        }

        // Attach the dial-preferred QUIC address: the IP the ANNOUNCE datagram physically
        // arrived from (already-resolved by the OS) combined with the peer's advertised QUIC
        // port — NOT the SWIM source port. This lets the QUIC transport dial a concrete IP
        // instead of synchronously re-resolving the gossiped hostname (membership v2 §5).
        deliverObservation(new SwimObservation.JoinAnnounced(
            announce.nodeInfo().withResolvedAddress(resolvedQuicAddress(sender, announce.nodeInfo())),
            announce.clusterName(), announce.incarnation()));
    }

    /// Derive the dial-preferred QUIC address for an announcing peer: the ANNOUNCE source IP
    /// (resolved by the kernel) combined with the peer's advertised QUIC port. Falls back to
    /// the advertised address when the source IP is unavailable (defensive — never NPEs).
    private NodeAddress resolvedQuicAddress(InetSocketAddress sender, NodeInfo nodeInfo) {
        return Option.option(sender.getAddress())
                     .map(resolvedIp -> new NodeAddress(resolvedIp.getHostAddress(), nodeInfo.address().port()))
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
    /// IP when present (DNS-free dial), else fall back to the address host string.
    private NodeInfo dialInfoFor(SwimMember member) {
        var swimAddr = member.address();
        var quicPort = swimAddr.getPort() - config.swimPortOffset();
        var host = Option.option(swimAddr.getAddress())
                         .map(InetAddress::getHostAddress)
                         .or(swimAddr.getHostString());
        return NodeInfo.nodeInfo(member.nodeId(), new NodeAddress(host, quicPort));
    }

    /// Send ANNOUNCE to all seeds every 500ms until this node is acknowledged by a peer or 60 attempts are exhausted.
    ///
    /// Runs on the shared scheduler. Stops once this node is acknowledged by a peer (inbound probe) or after 60 attempts (30s).
    @Contract public void announceJoin(NodeInfo self, String clusterName, long incarnation,
                                       List<InetSocketAddress> seeds) {
        // Seed the durable self-incarnation from the boot incarnation BEFORE the
        // announce loop runs. Monotonic max so a re-announce (or a refutation that
        // already advanced the value) never regresses it.
        selfIncarnation.updateAndGet(cur -> Math.max(cur, incarnation));
        var attempts = new AtomicInteger(0);
        var future = new AtomicReference<ScheduledFuture<?>>();
        var task = SharedScheduler.scheduleAtFixedRate(
            () -> runAnnounceAttempt(self, clusterName, incarnation, seeds, attempts, future),
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
        return option(members.get(nodeId))
            .map(m -> m.state() == MemberState.ALIVE ? SwimHealth.HEALTHY : SwimHealth.UNKNOWN)
            .or(SwimHealth.UNKNOWN);
    }

    private void runAnnounceAttempt(NodeInfo self, String clusterName, long incarnation,
                                    List<InetSocketAddress> seeds,
                                    AtomicInteger attempts, AtomicReference<ScheduledFuture<?>> future) {
        if (inboundProbeReceived) {
            cancelAnnounce(future, self, "self acknowledged by peer");
            return;
        }

        var attempt = attempts.incrementAndGet();
        LOG.info("SWIM ANNOUNCE join attempt {}/60 for node {} to {} seeds",
                 attempt, self.id().id(), seeds.size());
        seeds.forEach(seed -> transport.send(seed, Announce.announce(self, clusterName, incarnation)));

        if (attempt >= 60) {
            cancelAnnounce(future, self, "max attempts reached");
        }
    }

    private void cancelAnnounce(AtomicReference<ScheduledFuture<?>> future, NodeInfo self, String reason) {
        option(future.getAndSet(null)).onPresent(f -> f.cancel(false));
        LOG.info("SWIM ANNOUNCE join stopped for node {} ({})", self.id().id(), reason);
    }

    private void relayPingReq(InetSocketAddress requesterAddress, PingReq pingReq, SwimMember target) {
        var relaySeq = sequenceCounter.incrementAndGet();
        pendingRelays.put(relaySeq, new RelayInfo(pingReq.sequence(), requesterAddress, System.currentTimeMillis()));

        var piggyback = piggybackBuffer.peekUpdates(config.maxPiggyback());
        var ping = Ping.ping(selfId, relaySeq, piggyback);
        transport.send(target.address(), ping);
    }

    /// Transport-plane liveness promotion for a KNOWN member, driven by a completed QUIC
    /// connection (`PeerConnected`).
    ///
    /// (a) Cold-start formation race: a follower completes its QUIC Hello (consensus-ACTIVE)
    /// in ~1s, but the first SWIM probe only fires after `startupDelay` (≈ the suspect
    /// timeout). The seeded member's SUSPECT window can therefore expire SUSPECT→FAULTY
    /// before any probe-ack arrives, evicting a node that is provably reachable.
    ///
    /// (b) A completed QUIC channel to a known member IS transport-plane reachability proof.
    /// Promoting it ALIVE resets the suspect window so it survives until the first probe-ack.
    ///
    /// (c) Tombstone-gated (#231): `markAliveIfNeeded` refuses promotion of a
    /// proven-healthy-then-silently-dead id (tombstoned), so a black-holed peer is NOT
    /// resurrected off a stale/reopened channel. Only never-tombstoned members
    /// (cold-start seeds / live-flapping) are promoted — the tombstone is the discriminator.
    ///
    /// (d) This is the dual of two-plane death confirmation: the transport plane confirms
    /// life here just as it confirms death elsewhere.
    @Contract public void markAliveFromTransport(NodeId nodeId) {
        markAliveIfNeeded(nodeId);
    }

    /// Bump incarnation when marking alive via Ack — prevents stale SUSPECT piggyback from overriding.
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

        option(members.get(nodeId))
            .filter(member -> member.state() != MemberState.ALIVE)
            .onPresent(member -> applyAliveFromAck(nodeId, member));
    }

    private void applyAliveFromAck(NodeId nodeId, SwimMember member) {
        var alive = member.withState(MemberState.ALIVE)
                          .withIncarnation(member.incarnation() + 1);
        members.put(nodeId, alive);
        suspectTimestamps.remove(nodeId);
        addMemberUpdate(alive);
        recordHealthyAndEmit(nodeId, alive.incarnation());
    }

    private void processPiggyback(List<MembershipUpdate> updates) {
        updates.forEach(this::applyUpdate);
    }

    private void applyUpdate(MembershipUpdate update) {
        if (selfId.equals(update.nodeId())) {
            handleSelfUpdate(update);
            return;
        }

        var existing = option(members.get(update.nodeId()));

        if (existing.isPresent()) {
            existing.onPresent(member -> applyExistingMember(member, update));
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
    private void handleSelfUpdate(MembershipUpdate update) {
        if (update.state() == MemberState.SUSPECT || update.state() == MemberState.FAULTY) {
            long bumped = selfIncarnation.updateAndGet(cur -> Math.max(cur, update.incarnation()) + 1);
            LOG.warn("Self suspected/faulted by remote node, refuting with incarnation {}", bumped);
            addMemberUpdate(MembershipUpdate.membershipUpdate(selfId, MemberState.ALIVE, bumped, selfAddress));
        }
    }

    private void applyNewMember(MembershipUpdate update) {
        var member = SwimMember.swimMember(update.nodeId(), update.state(), update.incarnation(), update.address());
        members.put(update.nodeId(), member);
        if (update.state() != MemberState.FAULTY) {
            addMemberUpdate(update);
        }

        switch (update.state()) {
            case ALIVE -> applyNewAliveMember(member);
            case SUSPECT -> applyNewSuspectMember(member);
            case FAULTY -> emitFaultyOrUnknown(member.nodeId(), member.incarnation());
        }
    }

    private void applyNewAliveMember(SwimMember member) {
        notifyMemberJoined(member);
        recordHealthyAndEmit(member.nodeId(), member.incarnation());
    }

    private void applyNewSuspectMember(SwimMember member) {
        // First-sight SUSPECT must register a timestamp so the suspect-window
        // expiry tick eventually transitions the member to FAULTY (or
        // UnknownObserved under cold-boot suppression).
        suspectTimestamps.put(member.nodeId(), System.currentTimeMillis());
        listener.onMemberSuspect(member);
        emitSuspect(member.nodeId(), member.incarnation());
    }

    /// Enforce SWIM state priority at same incarnation: FAULTY > SUSPECT > ALIVE.
    /// At equal incarnation, only allow state progression (ALIVE->SUSPECT->FAULTY), not regression.
    private void applyExistingMember(SwimMember existing, MembershipUpdate update) {
        if (update.incarnation() < existing.incarnation()) {
            return;
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
        if (update.incarnation() == existing.incarnation()
            && statePriority(update.state()) < statePriority(existing.state())) {
            return;
        }

        var updated = SwimMember.swimMember(update.nodeId(), update.state(), update.incarnation(), update.address());
        members.put(update.nodeId(), updated);

        notifyStateChange(existing.state(), updated);
    }

    /// A re-admit toward ALIVE: the resident member is currently FAULTY or SUSPECT and
    /// the update would promote it back to ALIVE. This is the only direction the
    /// tombstone blocks in `applyExistingMember`; FAULTY-progression is never blocked.
    private static boolean isReAdmitTowardAlive(SwimMember existing, MembershipUpdate update) {
        return update.state() == MemberState.ALIVE && existing.state() != MemberState.ALIVE;
    }

    /// State priority for SWIM: FAULTY > SUSPECT > ALIVE.
    private static int statePriority(MemberState state) {
        return switch (state) {
            case ALIVE -> 0;
            case SUSPECT -> 1;
            case FAULTY -> 2;
        };
    }

    private void notifyStateChange(MemberState oldState, SwimMember updated) {
        if (oldState == updated.state()) {
            return;
        }

        switch (updated.state()) {
            case ALIVE -> notifyAlive(updated);
            case SUSPECT -> notifySuspect(updated);
            case FAULTY -> notifyFaulty(updated);
        }
    }

    private void notifyAlive(SwimMember updated) {
        notifyMemberJoined(updated);
        recordHealthyAndEmit(updated.nodeId(), updated.incarnation());
    }

    private void notifySuspect(SwimMember updated) {
        suspectTimestamps.put(updated.nodeId(), System.currentTimeMillis());
        listener.onMemberSuspect(updated);
        emitSuspect(updated.nodeId(), updated.incarnation());
    }

    private void notifyFaulty(SwimMember updated) {
        suspectTimestamps.remove(updated.nodeId());
        tombstoneIfProvenHealthy(updated.nodeId(), updated.incarnation());
        listener.onMemberFaulty(updated);
        emitFaultyOrUnknown(updated.nodeId(), updated.incarnation());
    }

    private void addMemberUpdate(SwimMember member) {
        piggybackBuffer.addUpdate(MembershipUpdate.membershipUpdate(member.nodeId(), member.state(), member.incarnation(), member.address()));
    }

    private void addMemberUpdate(MembershipUpdate update) {
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
    private void recordHealthyAndEmit(NodeId peer, long incarnation) {
        everSeenHealthy.add(peer);
        emitObservationOnEdge(peer, SwimHealth.HEALTHY, () -> new SwimObservation.HealthyObserved(peer, incarnation));
    }

    /// Emit `SuspectObserved` on edge (idempotent against repeats).
    private void emitSuspect(NodeId peer, long incarnation) {
        emitObservationOnEdge(peer, SwimHealth.SUSPECTED, () -> new SwimObservation.SuspectObserved(peer, incarnation));
    }

    /// Emit FAULTY on edge.
    ///
    /// Phase-aware cold-boot suppression (D.3 three-phase model, 2026-05-11):
    /// - In `COLD_BOOT` phase (`isBooting=true`), preserve the per-peer
    ///   `everSeenHealthy` gate: a peer that has never been observed HEALTHY emits
    ///   `UnknownObserved` so noisy bootstrap-time SWIM transitions do not flood
    ///   `HealthReconciler` with unactionable FAULTY edges.
    /// - In `NORMAL` and `RECOVERING` phases (`isBooting=false`), ALWAYS emit
    ///   `FaultyObserved` regardless of `everSeenHealthy`. The `RECOVERING` branch
    ///   is the critical compose-restart fix: peers were visible-and-Healthy in the
    ///   prior `NORMAL` period (their `everSeenHealthy` flag is preserved across
    ///   protocol life), so a post-restart kill must produce a cluster-visible
    ///   `FaultyObserved`. This drives `HealthReconciler` aggregation, the
    ///   `DECOMMISSIONED` write, and the downstream `NODE_LEFT` / `NODE_FAILED`
    ///   event that integration tests depend on.
    private void emitFaultyOrUnknown(NodeId peer, long incarnation) {
        var booting = isBooting.getAsBoolean();
        if (booting && !everSeenHealthy.contains(peer)) {
            LOG.info("SWIM cold-boot suppression (COLD_BOOT phase): peer {} never observed HEALTHY — emitting UNKNOWN instead of FAULTY",
                     peer.id());
            emitObservationOnEdge(peer, SwimHealth.UNKNOWN, () -> new SwimObservation.UnknownObserved(peer, incarnation));
            return;
        }
        if (!booting && !everSeenHealthy.contains(peer)) {
            LOG.warn("SWIM phase=NORMAL_OR_RECOVERING: emitting FaultyObserved for never-HEALTHY peer {} (cold-boot suppression bypassed)",
                     peer.id());
        }
        emitObservationOnEdge(peer, SwimHealth.FAULTY, () -> new SwimObservation.FaultyObserved(peer, incarnation));
        emitClusterFaulty(peer);
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
        Result.lift(Causes::fromThrowable, () -> deliverTransportObservation(emitter, observation))
              .onFailure(cause -> LOG.warn("Cluster TransportObservation emitter threw: {}", cause.message()));
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
        return Stream.concat(
                Stream.of(selfId),
                members.entrySet()
                       .stream()
                       .filter(entry -> entry.getValue().state() == MemberState.ALIVE)
                       .map(Map.Entry::getKey))
            .sorted(Comparator.comparing(NodeId::id))
            .toList();
    }

    /// Emit `DepartedObserved` on edge.
    private void emitDeparted(NodeId peer, long incarnation) {
        // Departed is always a terminal edge; we still gate on the previous
        // last-emitted state to avoid duplicate emissions if the peer has
        // already been emitted as departed.
        emitObservationOnEdge(peer, null, () -> new SwimObservation.DepartedObserved(peer, incarnation));
    }

    /// Edge-triggered emission: deliver the observation only if `target` differs
    /// from the previously-emitted health for `peer`. `target == null` is used
    /// for one-shot terminal events (Departed) and is always emitted at most
    /// once per terminal occurrence.
    private void emitObservationOnEdge(NodeId peer, SwimHealth target, Supplier<SwimObservation> factory) {
        if (target == null) {
            // Departed: emit only if not already in DEPARTED-pseudo state.
            if (lastEmittedHealth.remove(peer) == null) {
                // Was never emitted — still emit Departed once (downstream may need
                // to release per-peer resources). But guard against double-departed:
                // record a sentinel via lastEmittedHealth.put(peer, null) is not
                // possible with ConcurrentHashMap, so we accept the at-most-once
                // semantic by removing the entry above and letting the listeners run.
            }
            deliverObservation(factory.get());
            return;
        }

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
        Result.lift(Causes::fromThrowable, () -> deliverOne(consumer, observation))
              .onFailure(cause -> LOG.warn("SWIM observation listener threw: {}", cause.message()));
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

    /// Test-only accessor for the durable self-incarnation. Used by the
    /// self-refutation regression test to assert the refutation incarnation
    /// strictly advances and is durably stored.
    long selfIncarnationForTest() {
        return selfIncarnation.get();
    }
}
