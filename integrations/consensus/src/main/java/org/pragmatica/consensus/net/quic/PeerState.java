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
import java.util.function.LongUnaryOperator;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;

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
///    │                                └──auth remove──┴─────────────┤
///    └────────────────── expire-evicted ────────────────────────────┘
/// ```
///
/// Events and their transitions:
///
///   - `beginConnecting()` — INIT or EVICTED → CONNECTING. Idempotent (no-op if already CONNECTING/CONNECTED).
///   - `attach(conn)` — CONNECTING → CONNECTED. Returns accepted=true; caller drains offline buffer.
///     Returns false when in REMOVED (caller closes the new connection) or when a live CONNECTED
///     link already exists (duplicate — caller closes the new connection).
///   - `evict()` — CONNECTED → EVICTED. Offline buffer preserved for reconnect drain.
///   - `expireEvicted()` — EVICTED → REMOVED when the peer stays evicted past a grace TTL.
///   - `authoritativeRemove()` — any → REMOVED. Clears buffer and connection unconditionally.
///     Driven by `DisconnectNode` (SWIM-confirmed departure) and `unregisterPeer` (topology pruning).
///
/// ## Offline buffer
///
/// Distinct from Netty writability backpressure (which owns a separate per-stream queue on
/// the `QuicStreamChannel`). Holds serialized broadcast/send payloads while the peer is
/// CONNECTING or EVICTED. Bounded by [OFFLINE_BUFFER_MAX]; overflow drops the oldest entry
/// (consensus messages are idempotent — the stall detector re-broadcasts stuck rounds).
///
/// Drained by [drainOfflineBuffer] right after `attach` completes. Cleared by
/// `authoritativeRemove` and `expireEvicted`.
public final class PeerState {
    public static final int OFFLINE_BUFFER_MAX = 10_000;

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
        /// Peer is CONNECTED — caller should write the bytes to `connection()`.
        record SendNow(QuicPeerConnection connection) implements OfferOutcome {}
        /// Bytes were queued into the offline buffer. `oldestEvicted=true` means the buffer
        /// was at capacity and the oldest entry was dropped to make room.
        record Queued(boolean oldestEvicted) implements OfferOutcome {}
        /// Peer is REMOVED — bytes dropped.
        record Dropped() implements OfferOutcome {}
    }

    public enum AttachResult {
        /// First-time connection accepted; peer transitioned INIT/CONNECTING → CONNECTED.
        /// Caller should drain the offline buffer and emit a fresh `nodeAdded` view-change.
        ACCEPTED,
        /// Reconnection accepted; peer transitioned EVICTED → CONNECTED, OR replaced a stale
        /// (already-dead) CONNECTED link with a fresh one. The peer is already known to upstream
        /// consumers — caller should drain the offline buffer but MUST NOT emit a duplicate
        /// `nodeAdded` view-change. Closes the flap-loop where eviction-then-handshake fires
        /// `processViewChange(ADD)` against a peer that never left the topology.
        RECONNECTED,
        /// Peer already has a live CONNECTED link. Caller should close the new connection.
        DUPLICATE,
        /// Peer is REMOVED. Caller should close the new connection.
        REJECTED
    }

    private final NodeId peerId;
    private Phase phase = Phase.INIT;
    private QuicPeerConnection connection;
    private long phaseChangedAtNanos;
    private boolean passive;
    private final Deque<byte[]> offlineBuffer = new ArrayDeque<>();

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
    /// Used by the protection window check in `handleDisconnect` and by `expireEvicted`.
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
    /// Returns ACCEPTED on first-time success, RECONNECTED when transitioning from EVICTED or
    /// replacing a stale CONNECTED link (peer was already known to upstream consumers),
    /// DUPLICATE if a live link already exists, REJECTED if REMOVED.
    public synchronized AttachResult attach(QuicPeerConnection newConnection, long nowNanos) {
        return switch (phase) {
            case REMOVED -> AttachResult.REJECTED;
            case CONNECTED -> {
                if (connection != null && connection.isActive()) {
                    yield AttachResult.DUPLICATE;
                }
                // Stale CONNECTED link replaced — peer is already known upstream.
                this.connection = newConnection;
                this.phaseChangedAtNanos = nowNanos;
                yield AttachResult.RECONNECTED;
            }
            case EVICTED -> {
                // Reconnect after eviction — peer never left topology, suppress duplicate ADD.
                this.connection = newConnection;
                changePhase(Phase.CONNECTED, nowNanos);
                yield AttachResult.RECONNECTED;
            }
            case INIT, CONNECTING -> {
                // First-time accept (no prior CONNECTED link).
                this.connection = newConnection;
                changePhase(Phase.CONNECTED, nowNanos);
                yield AttachResult.ACCEPTED;
            }
        };
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

    /// Transitions EVICTED → REMOVED when the peer stays evicted past `ttlNanos`.
    /// Returns true when the transition fired. Clears offline buffer.
    public synchronized boolean expireEvicted(long nowNanos, long ttlNanos) {
        if (phase != Phase.EVICTED || (nowNanos - phaseChangedAtNanos) < ttlNanos) {
            return false;
        }
        changePhase(Phase.REMOVED, nowNanos);
        offlineBuffer.clear();
        return true;
    }

    /// Authoritative removal — any → REMOVED. Caller owns contract that this peer is truly gone
    /// (SWIM-confirmed DisconnectNode, explicit unregisterPeer, shutdown). Clears offline buffer
    /// and drops any held connection. Returns the dropped connection for the caller to close.
    public synchronized Option<QuicPeerConnection> authoritativeRemove(long nowNanos) {
        var dropped = connection;
        this.connection = null;
        offlineBuffer.clear();
        changePhase(Phase.REMOVED, nowNanos);
        return option(dropped);
    }

    /// Offer a serialized outbound message. Returns `SendNow(conn)` when the caller must write
    /// it to the captured live connection; `Queued` when the message was buffered; `Dropped`
    /// when the peer is REMOVED.
    public synchronized OfferOutcome offerOutbound(byte[] bytes) {
        return switch (phase) {
            case CONNECTED -> new OfferOutcome.SendNow(connection);
            case REMOVED -> new OfferOutcome.Dropped();
            case INIT, CONNECTING, EVICTED -> {
                var wasFull = offlineBuffer.size() >= OFFLINE_BUFFER_MAX;
                if (wasFull) {
                    offlineBuffer.pollFirst();
                }
                offlineBuffer.offerLast(bytes);
                yield new OfferOutcome.Queued(wasFull);
            }
        };
    }

    /// Drain the offline buffer. Intended to be called right after `attach` returns ACCEPTED.
    /// The returned list is a snapshot of what was buffered; internal deque is left empty.
    public synchronized List<byte[]> drainOfflineBuffer() {
        if (offlineBuffer.isEmpty()) {
            return List.of();
        }
        var drained = new ArrayList<byte[]>(offlineBuffer.size());
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
