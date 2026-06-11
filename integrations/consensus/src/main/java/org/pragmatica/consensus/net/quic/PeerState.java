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

package org.pragmatica.consensus.net.quic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongUnaryOperator;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.Message;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;

/// Per-peer connection lifecycle state machine for [QuicClusterNetwork].
///
/// Collapses five previously-parallel structures — `peerLinks`, `connectingInProgress`,
/// `passivePeers`, `connectionEstablishedAt`, and transient-reconnect buffering — into a
/// single authoritative phase + context per peer. All transitions are per-peer synchronized.
///
/// ## Phases
///
/// ```
///   INIT ─────► CONNECTING ─────► CONNECTED ────► EVICTED ────► REMOVED
///    ▲              │                 │               │             ▲
///    │              └───fail──────────┤               │             │
///    │                                └──auth remove──┴─────────────┘
///    └──────────────── attach (reconnect from EVICTED) ◄────────────┘
/// ```
///
/// Events and their transitions:
///
///   - `beginConnecting()` — INIT or EVICTED → CONNECTING. Idempotent (no-op if already CONNECTING/CONNECTED).
///   - `attach(conn)` — CONNECTING → CONNECTED. Returns accepted=true; caller drains offline buffer.
///     Returns false when in REMOVED (caller closes the new connection) or when a live CONNECTED
///     link already exists (duplicate — caller closes the new connection).
///   - `evict()` — CONNECTED → EVICTED. Offline buffer preserved for reconnect drain.
///   - `authoritativeRemove()` — any non-REMOVED → REMOVED. Clears buffer and connection unconditionally.
///     Terminal AND idempotent at the transition level: a repeated call on an already-REMOVED peer is a
///     pure no-op that records NO transition (Wave 5 — the duplicate `authoritative-remove`
///     REMOVED→REMOVED journal entries are structurally impossible). `attach` from REMOVED returns
///     REJECTED and `beginConnecting` from REMOVED returns false, so a same-NodeId peer never re-enters
///     the live phases. Driven by `departurePermanent` (the leader's co-confirmed-death verdict /
///     DECOMMISSIONED / SWIM `DepartedObserved`) and shutdown. EVICTED is reached on REMOVED only
///     through this path — there is no TTL-based EVICTED → REMOVED expiry.
///
/// ## Single emission source (Wave 5)
///
/// Every phase mutation produces EXACTLY ONE [PeerTransitionRecord] delivered to the
/// transition listener. The listener is the single chokepoint feeding BOTH the Wave-1
/// transition journal AND the typed transport emissions (`QuicClusterNetwork.emitPeerTransition`
/// maps connected-set transitions onto `processViewChange`). Records are queued under the
/// per-peer monitor and **flushed outside it** (mutation order preserved by the queue), so a
/// listener that routes messages — and may re-enter this or another peer's monitor — can never
/// form a cross-peer lock chain.
///
/// ## Reconnect provenance (Wave 5, C3)
///
/// `announcedUpstream` tracks whether upstream consumers have ever been told about this peer
/// (it reached CONNECTED at least once since creation or the last `readmit`). `attach` yields
/// RECONNECTED — suppressing the duplicate `PeerJoined` — for ANY re-attach of an announced peer,
/// including the previously-leaky EVICTED → `beginConnecting` → CONNECTING → attach re-dial path
/// (which used to return ACCEPTED and re-emit ADD: the #245 in-code loop). Conversely, a peer
/// that was evicted out of CONNECTING (dial failure / per-attempt timeout) without ever connecting
/// yields ACCEPTED on its eventual attach, so upstream receives the ADD it never got. `readmit`
/// resets the flag: upstream saw the REMOVE, so the next attach is a fresh ADD.
///
/// ## Offline buffer
///
/// Distinct from Netty writability backpressure (which owns a separate per-stream queue on
/// the `QuicStreamChannel`). Holds serialized broadcast/send payloads while the peer is
/// CONNECTING or EVICTED. Bounded by [OFFLINE_BUFFER_MAX]; overflow drops the oldest entry
/// (consensus messages are idempotent — the stall detector re-broadcasts stuck rounds).
///
/// Drained by [drainOfflineBuffer] right after `attach` completes. Cleared by `authoritativeRemove`.
public final class PeerState {
    public static final int OFFLINE_BUFFER_MAX = 10_000;

    /// Transition-record cause constants. Stable machine-readable identifiers consumed by the
    /// Wave-1 journal AND matched by `QuicClusterNetwork.emitPeerTransition` to map each
    /// transition onto exactly one typed transport emission.
    static final String CAUSE_BEGIN_CONNECTING = "begin-connecting";
    static final String CAUSE_ATTACH_ACCEPTED = "attach-accepted";
    static final String CAUSE_ATTACH_RECONNECTED = "attach-reconnected";
    static final String CAUSE_ATTACH_SUPERSEDE = "attach-supersede";
    static final String CAUSE_ATTACH_STALE_REPLACE = "attach-stale-replace";
    static final String CAUSE_EVICT = "evict";
    static final String CAUSE_EVICT_STALE_CONNECTING = "evict-stale-connecting";
    static final String CAUSE_AUTHORITATIVE_REMOVE = "authoritative-remove";
    static final String CAUSE_READMIT = "readmit";

    /// Minimum age of a still-`isActive()` CONNECTED incumbent before a fresh inbound handshake
    /// is allowed to supersede it (adopt-newer). 3s comfortably separates a sub-millisecond
    /// dual-dial race during formation (kept as DUPLICATE) from a post-partition reconnect
    /// (adopted) where `isActive()` lies indefinitely on a partition-orphaned link.
    private static final long SUPERSEDE_MIN_AGE_NANOS = TimeUnit.SECONDS.toNanos(3);

    public enum Phase {
        INIT,
        CONNECTING,
        CONNECTED,
        EVICTED,
        REMOVED
    }

    /// Outcome of [offerOutbound]. Captured atomically inside the per-peer monitor so the
    /// connection reference in [SendNow] is the same one the phase check saw.
    public sealed interface OfferOutcome {
        /// Peer is CONNECTED — caller should serialize and write the message to `connection()`.
        record SendNow(QuicPeerConnection connection) implements OfferOutcome {}
        /// Message was queued into the offline buffer. `oldestEvicted=true` means the buffer
        /// was at capacity and the oldest entry was dropped to make room.
        record Queued(boolean oldestEvicted) implements OfferOutcome {}
        /// Peer is REMOVED — message dropped.
        record Dropped() implements OfferOutcome {}
    }

    public enum AttachResult {
        /// First-time connection accepted; the peer was never announced upstream (or was
        /// readmitted after a REMOVE), so the caller's transition emission carries a fresh
        /// `nodeAdded` view-change. Caller should drain the offline buffer.
        ACCEPTED,
        /// Reconnection accepted for a peer upstream already knows about (it was CONNECTED at
        /// least once since creation / the last readmit): EVICTED → CONNECTED, the re-dial
        /// CONNECTING → CONNECTED after an eviction, a stale (already-dead) CONNECTED link
        /// replacement, or an adopt-newer supersede of an aged incumbent. The caller MUST NOT
        /// emit a duplicate `nodeAdded` view-change — the transition emission maps this to
        /// RECONNECT. Closes the flap-loop where eviction-then-handshake fires
        /// `processViewChange(ADD)` against a peer that never left the topology. When the result
        /// arrives via [AttachOutcome], its `superseded` may carry an OLD connection the caller
        /// must close.
        RECONNECTED,
        /// Peer already has a live CONNECTED link too young to safely supersede. Caller should
        /// close the new connection.
        DUPLICATE,
        /// Peer is REMOVED. Caller should close the new connection.
        REJECTED
    }

    /// Outcome of [attach]: the [AttachResult] plus, when a still-`isActive()` incumbent
    /// connection was displaced by a fresh handshake (the adopt-newer path), the `superseded`
    /// OLD connection the caller MUST close. Empty `superseded` for every other branch.
    public record AttachOutcome(AttachResult result, Option<QuicPeerConnection> superseded) {}

    private final NodeId peerId;
    /// Wave-1/Wave-5 transition chokepoint: invoked with one [PeerTransitionRecord] per phase
    /// mutation (and per CONNECTED→CONNECTED connection-replacement in [#attachOverConnected]).
    /// Invoked OUTSIDE the per-peer monitor (records queue under the monitor, flush after it is
    /// released, in mutation order), so implementations may journal AND route typed transport
    /// emissions without risking a cross-peer lock chain. Mutable (volatile) so
    /// `QuicClusterNetwork.seedPeerForTests` can rewire test-built states onto the live chokepoint.
    private volatile Consumer<PeerTransitionRecord> transitionListener;
    /// Records produced under the monitor, awaiting the out-of-monitor flush. Guarded by `this`.
    private final Deque<PeerTransitionRecord> pendingTransitions = new ArrayDeque<>();
    private Phase phase = Phase.INIT;
    private QuicPeerConnection connection;
    private long phaseChangedAtNanos;
    private boolean passive;
    /// Wave-5 reconnect provenance: true once the peer has reached CONNECTED (upstream has been
    /// told about it via ADD/RECONNECT); reset by [#readmit] (upstream saw the REMOVE). Guarded by `this`.
    private boolean announcedUpstream;
    private final Deque<Message.Wired> offlineBuffer = new ArrayDeque<>();

    /// Wall-clock-free (`System.nanoTime`) instant of the most recent inbound frame observed on
    /// this peer's link, across ALL lanes (transport keepalives, ClusterSync pongs, consensus).
    /// RECEIPT EVIDENCE ONLY (Wave 5): refreshed EXCLUSIVELY by [#markInbound] — the single
    /// inbound funnel (`QuicClusterNetwork.onMessageReceived`, post-blackhole-guard). A dial
    /// success / attach (including a LATE attach after the per-attempt dial timeout) never
    /// refreshes it: our OWN outbound handshake completing is not evidence the peer is sending
    /// to us. The CONNECTED-zombie liveness TTL sweep (`QuicClusterNetwork.sweepPeer`) pairs this
    /// receipt clock with the phase age: a link is silent-zombie only when BOTH exceed the TTL,
    /// so a fresh attach gets exactly one TTL window of grace to produce its first inbound frame.
    /// Blackholed inbound is intentionally NOT counted (the chaos substrate keeps the link
    /// CONNECTED yet silent), so the sweep still trips.
    private long lastInboundAtNanos;

    /// Wall-clock instant (ms) at which the missing-peer reconciler is next allowed to
    /// attempt a re-dial of this peer. Zero means no reconciler attempt has been made yet
    /// (any reconciler tick may dispatch immediately). Used by `QuicClusterNetwork`'s
    /// periodic reconciler to back off unreachable peers without piling up redundant
    /// dial attempts. Distinct from `ReconnectBackoff` (eviction-driven) which paces
    /// rapid heartbeat-loop reconnects on a freshly evicted CONNECTED link.
    private long reconcileNextAttemptMs;

    /// Current reconcile-backoff window in ms; doubled on every reconciler dispatch
    /// for this peer up to a configured cap, reset by `resetReconcileBackoff` on a
    /// successful attach.
    private long reconcileCurrentDelayMs;

    private PeerState(NodeId peerId, long nowNanos, Consumer<PeerTransitionRecord> transitionListener) {
        this.peerId = peerId;
        this.phaseChangedAtNanos = nowNanos;
        this.transitionListener = transitionListener;
    }

    public static PeerState peerState(NodeId peerId, long nowNanos) {
        return new PeerState(peerId, nowNanos, ignored -> {});
    }

    /// Factory with the transition-chokepoint listener (Wave 1 journal + Wave 5 emission).
    /// The listener receives one [PeerTransitionRecord] per phase mutation, invoked OUTSIDE
    /// the per-peer monitor in mutation order.
    public static PeerState peerState(NodeId peerId, long nowNanos, Consumer<PeerTransitionRecord> transitionListener) {
        return new PeerState(peerId, nowNanos, transitionListener);
    }

    /// Rewire the transition listener. Used by `QuicClusterNetwork.seedPeerForTests` so states
    /// built directly by tests (no-op listener) join the live journal+emission chokepoint once
    /// seeded into the connection table. Subsequent transitions flow to the new listener;
    /// transitions performed before the swap are not replayed.
    void replaceTransitionListener(Consumer<PeerTransitionRecord> listener) {
        this.transitionListener = listener;
    }

    public NodeId peerId() {
        return peerId;
    }

    public synchronized Phase phase() {
        return phase;
    }

    public synchronized boolean isPassive() {
        return passive;
    }

    public synchronized Unit markPassive() {
        this.passive = true;
        return unit();
    }

    /// Returns the live connection if the peer is CONNECTED. Empty otherwise.
    public synchronized Option<QuicPeerConnection> activeConnection() {
        return phase == Phase.CONNECTED ? option(connection) : Option.empty();
    }

    /// Returns nanoseconds since the most recent phase transition.
    /// Used by the protection window check in `handleDisconnect` and as the attach-grace
    /// floor of the liveness TTL sweep (a fresh CONNECTED link gets one TTL window to
    /// produce its first inbound frame before the receipt clock alone can evict it).
    public synchronized long phaseAgeNanos(long nowNanos) {
        return nowNanos - phaseChangedAtNanos;
    }

    /// Record that an inbound frame was just observed on this peer's link (any lane). The ONLY
    /// refresher of the receipt-evidence liveness clock (Wave 5). Called from the single inbound
    /// funnel (`QuicClusterNetwork.onMessageReceived`) AFTER the blackhole early-return, so a
    /// blackholed (silently-dropping) link is never refreshed and the sweep can trip.
    public synchronized void markInbound(long nowNanos) {
        this.lastInboundAtNanos = nowNanos;
    }

    /// Nanoseconds since the most recent inbound frame (= now − lastInboundAtNanos). Used by the
    /// liveness TTL sweep to detect a CONNECTED zombie whose `isActive()` lies true but whose link
    /// has gone silent past the keepalive-cadence TTL. Before the first [#markInbound] this is
    /// "age since the nanoTime epoch" — effectively infinite — which is why the sweep pairs it
    /// with [#phaseAgeNanos] as the attach-grace floor.
    public synchronized long inboundAgeNanos(long nowNanos) {
        return nowNanos - lastInboundAtNanos;
    }

    /// Transitions INIT or EVICTED → CONNECTING. Idempotent from CONNECTING/CONNECTED.
    /// Returns true when the caller should initiate the actual dial.
    public boolean beginConnecting(long nowNanos) {
        var initiate = beginConnectingInternal(nowNanos);
        flushTransitions();
        return initiate;
    }

    private synchronized boolean beginConnectingInternal(long nowNanos) {
        return switch (phase) {
            case INIT, EVICTED -> {
                changePhase(Phase.CONNECTING, nowNanos, CAUSE_BEGIN_CONNECTING);
                yield true;
            }
            case CONNECTING, CONNECTED, REMOVED -> false;
        };
    }

    /// Transitions CONNECTING (or INIT/EVICTED — accepted inbound before explicit connect) → CONNECTED.
    /// Returns ACCEPTED on a first-time success (peer never announced upstream), RECONNECTED when the
    /// peer was previously CONNECTED (any re-attach path: EVICTED → CONNECTED, the re-dial
    /// CONNECTING → CONNECTED after an eviction, replacing a stale CONNECTED link, or superseding an
    /// aged-but-active incumbent — the adopt-newer path, whose [AttachOutcome.superseded] carries the
    /// displaced OLD connection), DUPLICATE if a live, too-young link already exists, REJECTED if
    /// REMOVED. Never refreshes the receipt-evidence liveness clock (see [#markInbound]).
    public AttachOutcome attach(QuicPeerConnection newConnection, long nowNanos) {
        var outcome = attachInternal(newConnection, nowNanos);
        flushTransitions();
        return outcome;
    }

    private synchronized AttachOutcome attachInternal(QuicPeerConnection newConnection, long nowNanos) {
        return switch (phase) {
            case REMOVED -> new AttachOutcome(AttachResult.REJECTED, Option.empty());
            case CONNECTED -> attachOverConnected(newConnection, nowNanos);
            case INIT, CONNECTING, EVICTED -> attachFresh(newConnection, nowNanos);
        };
    }

    /// Non-CONNECTED attach: bind the connection and decide ACCEPTED vs RECONNECTED by upstream
    /// provenance, not by the phase the attach arrived in. An announced peer re-attaching from
    /// EVICTED *or* from a re-dial CONNECTING yields RECONNECTED (no duplicate ADD); a peer that
    /// was never announced (fresh, or dial-failed before ever connecting, or readmitted) yields
    /// ACCEPTED so upstream receives its (first) ADD.
    private AttachOutcome attachFresh(QuicPeerConnection newConnection, long nowNanos) {
        var reconnect = announcedUpstream;
        this.connection = newConnection;
        this.announcedUpstream = true;
        changePhase(Phase.CONNECTED, nowNanos, reconnect ? CAUSE_ATTACH_RECONNECTED : CAUSE_ATTACH_ACCEPTED);
        return new AttachOutcome(reconnect ? AttachResult.RECONNECTED : AttachResult.ACCEPTED, Option.empty());
    }

    /// CONNECTED-branch of [attach]. An incumbent that is null or reports `!isActive()` is a
    /// stale link and is transparently replaced (RECONNECTED, no superseded). An incumbent that
    /// still reports `isActive()` is normally a duplicate — UNLESS it is older than
    /// [SUPERSEDE_MIN_AGE_NANOS], in which case the fresh handshake adopts-newer and the OLD
    /// connection is handed back for the caller to close. Rationale: a completed Hello handshake
    /// proves the peer is dialable NOW, whereas `isActive()` can lie indefinitely on a
    /// partition-orphaned link (QUIC idle timeout disabled) — but it is still OUR outbound
    /// evidence, so the receipt clock is NOT refreshed; the restarted phase age supplies the
    /// fresh link's TTL grace. `ConnectionDirection.shouldInitiate` guarantees exactly one
    /// dialer per pair, so a fresh inbound handshake means the designated dialer detected death
    /// and re-dialed — defer to it. The age guard preserves the existing protection against a
    /// sub-millisecond dual-dial race during formation.
    private AttachOutcome attachOverConnected(QuicPeerConnection newConnection, long nowNanos) {
        if (connection != null && connection.isActive()) {
            if (phaseAgeNanos(nowNanos) <= SUPERSEDE_MIN_AGE_NANOS) {
                return new AttachOutcome(AttachResult.DUPLICATE, Option.empty());
            }
            var superseded = connection;
            this.connection = newConnection;
            this.phaseChangedAtNanos = nowNanos;
            notifyTransition(Phase.CONNECTED, Phase.CONNECTED, CAUSE_ATTACH_SUPERSEDE);
            return new AttachOutcome(AttachResult.RECONNECTED, option(superseded));
        }
        // Stale CONNECTED link replaced — peer is already known upstream.
        this.connection = newConnection;
        this.phaseChangedAtNanos = nowNanos;
        notifyTransition(Phase.CONNECTED, Phase.CONNECTED, CAUSE_ATTACH_STALE_REPLACE);
        return new AttachOutcome(AttachResult.RECONNECTED, Option.empty());
    }

    /// Transitions CONNECTED → EVICTED. Preserves offline buffer for reconnect drain.
    /// Returns the evicted connection for the caller to close. Empty if no-op.
    public Option<QuicPeerConnection> evict(long nowNanos) {
        var evicted = evictInternal(nowNanos);
        flushTransitions();
        return evicted;
    }

    private synchronized Option<QuicPeerConnection> evictInternal(long nowNanos) {
        if (phase != Phase.CONNECTED) {
            return Option.empty();
        }
        var evicted = connection;
        this.connection = null;
        changePhase(Phase.EVICTED, nowNanos, CAUSE_EVICT);
        return option(evicted);
    }

    /// Transitions CONNECTING → EVICTED for a dial that neither completed nor failed (a hung
    /// `client.connect(...)` that never invoked `completePeerConnection` nor `onConnectFailed`).
    /// Without this, such a peer is pinned in CONNECTING forever and the reconciler's in-flight
    /// dedup (`considerPeerForReconcile`'s `CONNECTING → return`) silently skips it on every tick,
    /// so it is never re-dialed and never counted as CONNECTED. Returns `true` only on a real
    /// CONNECTING → EVICTED transition so the caller emits diagnostics / re-dials exactly once.
    /// No-op (returns `false`) from any other phase — distinct from [#evict], which only handles
    /// the CONNECTED → EVICTED stale-link path. EVICTED is dial-eligible via [#beginConnecting].
    /// A LATE dial completion after this eviction still attaches normally ([#attach] from
    /// EVICTED) — provenance decides ACCEPTED vs RECONNECTED.
    public boolean evictStaleConnecting(long nowNanos) {
        var evicted = evictStaleConnectingInternal(nowNanos);
        flushTransitions();
        return evicted;
    }

    private synchronized boolean evictStaleConnectingInternal(long nowNanos) {
        if (phase != Phase.CONNECTING) {
            return false;
        }
        this.connection = null;
        changePhase(Phase.EVICTED, nowNanos, CAUSE_EVICT_STALE_CONNECTING);
        return true;
    }

    /// Authoritative removal — any non-REMOVED → REMOVED. Caller owns contract that this peer is
    /// truly gone (`departurePermanent`: co-confirmed-death verdict / DECOMMISSIONED / SWIM
    /// DepartedObserved, shutdown). Clears offline buffer and drops any held connection. Returns
    /// the dropped connection for the caller to close. Idempotent: a repeated call on an
    /// already-REMOVED peer returns empty and records NO transition (no REMOVED→REMOVED
    /// duplicate in the journal, no duplicate REMOVE emission).
    public Option<QuicPeerConnection> authoritativeRemove(long nowNanos) {
        var dropped = authoritativeRemoveInternal(nowNanos);
        flushTransitions();
        return dropped;
    }

    private synchronized Option<QuicPeerConnection> authoritativeRemoveInternal(long nowNanos) {
        if (phase == Phase.REMOVED) {
            return Option.empty();
        }
        var dropped = connection;
        this.connection = null;
        offlineBuffer.clear();
        changePhase(Phase.REMOVED, nowNanos, CAUSE_AUTHORITATIVE_REMOVE);
        return option(dropped);
    }

    /// Incarnation-gated resurrection: REMOVED -> INIT. Called ONLY when the SWIM-authoritative
    /// membership has re-admitted this NodeId (back in `coreNodes()` — possible only after a
    /// strictly-higher incarnation superseded the tombstone, per `SwimProtocol.supersedeOrRefuse`).
    /// Restores the peer to a dial-eligible / attach-eligible state so a transient-partition
    /// survivor reconnects; the SWIM probe-ack remains the sole ALIVE authority, so re-admission
    /// here is NOT resurrection-to-ALIVE (preserves the anti-resurrection guarantee). No-op (returns
    /// false) when the peer is not REMOVED. Offline buffer stays cleared (authoritativeRemove cleared
    /// it). Resets the upstream-announce provenance: the REMOVE was emitted, so the next attach is a
    /// fresh ACCEPTED/ADD, not a RECONNECT.
    public boolean readmit(long nowNanos) {
        var readmitted = readmitInternal(nowNanos);
        flushTransitions();
        return readmitted;
    }

    private synchronized boolean readmitInternal(long nowNanos) {
        if (phase != Phase.REMOVED) {
            return false;
        }
        this.announcedUpstream = false;
        changePhase(Phase.INIT, nowNanos, CAUSE_READMIT);
        return true;
    }

    /// Offer an outbound message. Returns `SendNow(conn)` when the caller must serialize and
    /// write it to the captured live connection; `Queued` when the message was buffered (held
    /// as-is, serialized lazily at the single write/drain site, retaining its lane); `Dropped`
    /// when the peer is REMOVED.
    public synchronized OfferOutcome offerOutbound(Message.Wired message) {
        return switch (phase) {
            case CONNECTED -> new OfferOutcome.SendNow(connection);
            case REMOVED -> new OfferOutcome.Dropped();
            case INIT, CONNECTING, EVICTED -> {
                var wasFull = offlineBuffer.size() >= OFFLINE_BUFFER_MAX;
                if (wasFull) {
                    offlineBuffer.pollFirst();
                }
                offlineBuffer.offerLast(message);
                yield new OfferOutcome.Queued(wasFull);
            }
        };
    }

    /// Drain the offline buffer. Intended to be called right after `attach` returns ACCEPTED.
    /// The returned list is a snapshot of the buffered messages (each re-sent on its own lane
    /// at drain time); internal deque is left empty.
    public synchronized List<Message.Wired> drainOfflineBuffer() {
        if (offlineBuffer.isEmpty()) {
            return List.of();
        }
        var drained = new ArrayList<Message.Wired>(offlineBuffer.size());
        drained.addAll(offlineBuffer);
        offlineBuffer.clear();
        return drained;
    }

    /// Size of the offline buffer. Used for diagnostics and metrics.
    public synchronized int offlineBufferSize() {
        return offlineBuffer.size();
    }

    /// Returns true when the periodic missing-peer reconciler is allowed to dispatch a
    /// re-dial for this peer at the supplied wall-clock instant. On allow, advances the
    /// internal backoff state: doubles the current delay (capped at `capMs`), seeds the
    /// next-attempt timestamp at `nowMs + jitterFn(nextDelay)`, and stores it. On disallow
    /// the state is unchanged. Per-peer monitor protects against concurrent reconciler
    /// ticks racing the same peer.
    ///
    /// `initialMs` and `capMs` are passed in by the caller to keep this class free of
    /// configuration coupling. `jitterFn` is invoked exactly once on the *new* delay
    /// (doubled, capped) so the deferred-attempt timestamp reflects the chosen jitter.
    public synchronized boolean reconcileBackoffAllows(long nowMs,
                                                       long initialMs,
                                                       long capMs,
                                                       LongUnaryOperator jitterFn) {
        if (nowMs < reconcileNextAttemptMs) {
            return false;
        }
        var nextDelay = reconcileCurrentDelayMs == 0L
                       ? initialMs
                       : Math.min(reconcileCurrentDelayMs * 2L, capMs);
        reconcileCurrentDelayMs = nextDelay;
        reconcileNextAttemptMs = nowMs + Math.max(1L, jitterFn.applyAsLong(nextDelay));
        return true;
    }

    /// Reset the reconcile backoff. Called when a peer attaches successfully so a peer
    /// that was stuck does not pay the doubled delay forever after recovery.
    public synchronized void resetReconcileBackoff() {
        reconcileCurrentDelayMs = 0L;
        reconcileNextAttemptMs = 0L;
    }

    /// Current reconcile-backoff delay in ms; intended for tests/diagnostics.
    public synchronized long reconcileCurrentDelayMs() {
        return reconcileCurrentDelayMs;
    }

    private void changePhase(Phase next, long nowNanos, String cause) {
        var from = this.phase;
        this.phase = next;
        this.phaseChangedAtNanos = nowNanos;
        notifyTransition(from, next, cause);
    }

    /// Queue one [PeerTransitionRecord] for this mutation (single record per mutation). Called
    /// while holding the per-peer monitor; delivery happens in [#flushTransitions] after the
    /// monitor is released.
    private void notifyTransition(Phase from, Phase to, String cause) {
        pendingTransitions.offerLast(new PeerTransitionRecord(peerId, from, to, cause, System.currentTimeMillis()));
    }

    /// Deliver queued transition records to the listener OUTSIDE the per-peer monitor.
    /// The queue preserves mutation order even when a racing thread flushes records another
    /// thread enqueued; each record is delivered exactly once.
    private void flushTransitions() {
        List<PeerTransitionRecord> toEmit;
        synchronized (this) {
            if (pendingTransitions.isEmpty()) {
                return;
            }
            toEmit = List.copyOf(pendingTransitions);
            pendingTransitions.clear();
        }
        toEmit.forEach(transitionListener::accept);
    }
}
