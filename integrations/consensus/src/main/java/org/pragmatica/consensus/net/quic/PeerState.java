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
///   - `authoritativeRemove()` — any → REMOVED. Clears buffer and connection unconditionally. Terminal:
///     `attach` from REMOVED returns REJECTED and `beginConnecting` from REMOVED returns false, so a
///     same-NodeId peer never re-enters the live phases. Driven by `departurePermanent` (the leader's
///     co-confirmed-death verdict / DECOMMISSIONED / SWIM `DepartedObserved`) and shutdown. EVICTED is
///     reached on REMOVED only through this path — there is no TTL-based EVICTED → REMOVED expiry.
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
        /// First-time connection accepted; peer transitioned INIT/CONNECTING → CONNECTED.
        /// Caller should drain the offline buffer and emit a fresh `nodeAdded` view-change.
        ACCEPTED,
        /// Reconnection accepted; peer transitioned EVICTED → CONNECTED, OR replaced a stale
        /// (already-dead) CONNECTED link with a fresh one, OR superseded a still-`isActive()` but
        /// aged incumbent with a fresh handshake. The peer is already known to upstream
        /// consumers — caller should drain the offline buffer but MUST NOT emit a duplicate
        /// `nodeAdded` view-change. Closes the flap-loop where eviction-then-handshake fires
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
    private Phase phase = Phase.INIT;
    private QuicPeerConnection connection;
    private long phaseChangedAtNanos;
    private boolean passive;
    private final Deque<Message.Wired> offlineBuffer = new ArrayDeque<>();

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

    private PeerState(NodeId peerId, long nowNanos) {
        this.peerId = peerId;
        this.phaseChangedAtNanos = nowNanos;
    }

    public static PeerState peerState(NodeId peerId, long nowNanos) {
        return new PeerState(peerId, nowNanos);
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
    /// Used by the protection window check in `handleDisconnect`.
    public synchronized long phaseAgeNanos(long nowNanos) {
        return nowNanos - phaseChangedAtNanos;
    }

    /// Transitions INIT or EVICTED → CONNECTING. Idempotent from CONNECTING/CONNECTED.
    /// Returns true when the caller should initiate the actual dial.
    public synchronized boolean beginConnecting(long nowNanos) {
        return switch (phase) {
            case INIT, EVICTED -> {
                changePhase(Phase.CONNECTING, nowNanos);
                yield true;
            }
            case CONNECTING, CONNECTED, REMOVED -> false;
        };
    }

    /// Transitions CONNECTING (or INIT/EVICTED — accepted inbound before explicit connect) → CONNECTED.
    /// Returns ACCEPTED on first-time success, RECONNECTED when transitioning from EVICTED,
    /// replacing a stale CONNECTED link, or superseding an aged-but-active incumbent (the
    /// adopt-newer path, whose [AttachOutcome.superseded] carries the displaced OLD connection),
    /// DUPLICATE if a live, too-young link already exists, REJECTED if REMOVED.
    public synchronized AttachOutcome attach(QuicPeerConnection newConnection, long nowNanos) {
        return switch (phase) {
            case REMOVED -> new AttachOutcome(AttachResult.REJECTED, Option.empty());
            case CONNECTED -> attachOverConnected(newConnection, nowNanos);
            case EVICTED -> {
                // Reconnect after eviction — peer never left topology, suppress duplicate ADD.
                this.connection = newConnection;
                changePhase(Phase.CONNECTED, nowNanos);
                yield new AttachOutcome(AttachResult.RECONNECTED, Option.empty());
            }
            case INIT, CONNECTING -> {
                // First-time accept (no prior CONNECTED link).
                this.connection = newConnection;
                changePhase(Phase.CONNECTED, nowNanos);
                yield new AttachOutcome(AttachResult.ACCEPTED, Option.empty());
            }
        };
    }

    /// CONNECTED-branch of [attach]. An incumbent that is null or reports `!isActive()` is a
    /// stale link and is transparently replaced (RECONNECTED, no superseded). An incumbent that
    /// still reports `isActive()` is normally a duplicate — UNLESS it is older than
    /// [SUPERSEDE_MIN_AGE_NANOS], in which case the fresh handshake adopts-newer and the OLD
    /// connection is handed back for the caller to close. Rationale: a completed Hello handshake
    /// is a current liveness proof, whereas `isActive()` can lie indefinitely on a
    /// partition-orphaned link (QUIC idle timeout disabled). `ConnectionDirection.shouldInitiate`
    /// guarantees exactly one dialer per pair, so a fresh inbound handshake means the designated
    /// dialer detected death and re-dialed — defer to it. The age guard preserves the existing
    /// protection against a sub-millisecond dual-dial race during formation.
    private AttachOutcome attachOverConnected(QuicPeerConnection newConnection, long nowNanos) {
        if (connection != null && connection.isActive()) {
            if (phaseAgeNanos(nowNanos) <= SUPERSEDE_MIN_AGE_NANOS) {
                return new AttachOutcome(AttachResult.DUPLICATE, Option.empty());
            }
            var superseded = connection;
            this.connection = newConnection;
            this.phaseChangedAtNanos = nowNanos;
            return new AttachOutcome(AttachResult.RECONNECTED, option(superseded));
        }
        // Stale CONNECTED link replaced — peer is already known upstream.
        this.connection = newConnection;
        this.phaseChangedAtNanos = nowNanos;
        return new AttachOutcome(AttachResult.RECONNECTED, Option.empty());
    }

    /// Transitions CONNECTED → EVICTED. Preserves offline buffer for reconnect drain.
    /// Returns the evicted connection for the caller to close. Empty if no-op.
    public synchronized Option<QuicPeerConnection> evict(long nowNanos) {
        if (phase != Phase.CONNECTED) {
            return Option.empty();
        }
        var evicted = connection;
        this.connection = null;
        changePhase(Phase.EVICTED, nowNanos);
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
    public synchronized boolean evictStaleConnecting(long nowNanos) {
        if (phase != Phase.CONNECTING) {
            return false;
        }
        this.connection = null;
        changePhase(Phase.EVICTED, nowNanos);
        return true;
    }

    /// Authoritative removal — any → REMOVED. Caller owns contract that this peer is truly gone
    /// (`departurePermanent`: co-confirmed-death verdict / DECOMMISSIONED / SWIM DepartedObserved,
    /// shutdown). Clears offline buffer and drops any held connection. Returns the dropped
    /// connection for the caller to close.
    public synchronized Option<QuicPeerConnection> authoritativeRemove(long nowNanos) {
        var dropped = connection;
        this.connection = null;
        offlineBuffer.clear();
        changePhase(Phase.REMOVED, nowNanos);
        return option(dropped);
    }

    /// Incarnation-gated resurrection: REMOVED -> INIT. Called ONLY when the SWIM-authoritative
    /// membership has re-admitted this NodeId (back in `coreNodes()` — possible only after a
    /// strictly-higher incarnation superseded the tombstone, per `SwimProtocol.supersedeOrRefuse`).
    /// Restores the peer to a dial-eligible / attach-eligible state so a transient-partition
    /// survivor reconnects; the SWIM probe-ack remains the sole ALIVE authority, so re-admission
    /// here is NOT resurrection-to-ALIVE (preserves the anti-resurrection guarantee). No-op (returns
    /// false) when the peer is not REMOVED. Offline buffer stays cleared (authoritativeRemove cleared it).
    public synchronized boolean readmit(long nowNanos) {
        if (phase != Phase.REMOVED) {
            return false;
        }
        changePhase(Phase.INIT, nowNanos);
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

    private void changePhase(Phase next, long nowNanos) {
        this.phase = next;
        this.phaseChangedAtNanos = nowNanos;
    }
}
