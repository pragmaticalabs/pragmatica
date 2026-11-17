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
package org.pragmatica.consensus.net;

import java.util.Map;
import java.util.Set;
import java.util.Collection;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.net.quic.PeerTransitionRecord;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.net.tcp.Server;

import static org.pragmatica.consensus.net.NetworkServiceMessage.*;


/// Generalized Network API for cluster communication.
///
/// **Implementation contract:** All methods must be exception-safe.
/// Network failures should be logged internally, not thrown to callers.
///
/// Delivery is best-effort and unacknowledged: every unicast `send` reports a local [WriteOutcome], and one
/// reported as [WriteOutcome.Sent] was not refused locally, which says nothing about delivery. Rabia tolerates loss, reordering and duplicates through its own timeouts and
/// retransmission; every other consumer earns end-to-end delivery itself (quorum collection,
/// anti-entropy, caller deadlines).
public interface ClusterNetwork {
    /// Additive fault-injection filter. It cannot bypass transport or application authority checks.
    default Unit setInboundFaultFilter(java.util.function.BiPredicate<NodeId, org.pragmatica.messaging.Message.Wired> filter) {
        return Unit.unit();
    }

    /// Validates a state-transfer envelope against the actual encoded transport limit.
    /// In-memory transports without a frame limit need no additional restriction.
    default org.pragmatica.lang.Result<Unit> validateOutboundMessage(ProtocolMessage message) {
        return org.pragmatica.lang.Result.success(Unit.unit());
    }

    /// Selects one deterministic initiator for each peer pair. Both endpoints must use
    /// the same immutable-role rule; health must not change the selected initiator.
    /// Install before startup. The default transport policy uses NodeId ordering.
    default Unit setConnectionInitiator(java.util.function.BiPredicate<NodeId, NodeId> policy) {
        return Unit.unit();
    }

    /// Installs an additional authenticated-peer admission filter before router delivery.
    /// The transport always enforces sender identity; this filter cannot bypass it.
    @Contract
    default Unit setInboundMessagePolicy(java.util.function.BiPredicate<NodeId, org.pragmatica.messaging.Message.Wired> policy) {
        return Unit.unit();
    }

    /// Permits proposal evidence relays from installed voters only. Other messages must
    /// name the authenticated connection peer as sender.
    @Contract
    default Unit setProposalRelayMembership(java.util.function.Predicate<NodeId> membership) {
        return Unit.unit();
    }

    /// Install the node's [BootTokens] registry — shared with SWIM — through which every peer's
    /// handshake is admitted (terminal removal). Must be set before the transport starts.
    default Unit setBootTokens(BootTokens registry) {
        return Unit.unit();
    }

    /// Broadcast a message to all nodes in the cluster.
    ///
    /// Note that actual implementation may just send messages directly,
    /// not necessarily use broadcasting in underlying protocol.
    ///
    /// Implementations must not throw exceptions. Failed sends should be
    /// logged and silently ignored - the protocol handles message loss.
    <M extends ProtocolMessage> Unit broadcast(M message);

    @Contract
    @MessageReceiver
    void connect(ConnectNode connectNode);

    @Contract
    @MessageReceiver
    void disconnect(DisconnectNode disconnectNode);

    /// Permanently remove a decommissioned peer. Unlike [#disconnect(DisconnectNode)],
    /// which soft-evicts to EVICTED state (allowing future reconnect), this removes the
    /// peer entry entirely (REMOVED state) and closes the datagram channel, preventing
    /// WARN flood from broadcast attempts to a dead node.
    @Contract
    default void departurePermanent(NodeId nodeId) {}

    /// Connect to a newly-announced peer using its full NodeInfo (address + id).
    /// Called by the SWIM JoinAnnounced listener for peers not in the static topology.
    @Contract
    default void connect(NodeInfo nodeInfo) {}

    /// Gate the missing-peer reconciler: peers for which the supplier returns false
    /// (FAULTY or UNKNOWN in SWIM) are skipped during reconnect attempts.
    @Contract
    default void setSwimHealthGate(Function<NodeId, Boolean> gate) {}

    /// Gate the INBOUND tombstone-readmit path (Fix A): a REMOVED peer presenting a completed
    /// authenticated Hello is readmitted only when this raw-SWIM proof-of-life predicate reports
    /// it recently ALIVE (HEALTHY or SUSPECTED) — independent of the FSM's terminal-DEAD verdict.
    /// Covers the lowest-id node that nobody dials (so the dial-path readmit can never run for it).
    /// Default no-op: transports without a tombstone phase keep the legacy behaviour.
    @Contract
    default void setSwimLivenessGate(Function<NodeId, Boolean> gate) {}

    /// Publish the FSM-authoritative desired core-member connection set. When present, the
    /// missing-peer reconciler reconciles live connections against THIS set (MEMBER + SUSPECT,
    /// non-worker, address-known) instead of static topology, dropping the legacy SWIM
    /// membership/health dial gates (the set already encodes them). Default no-op: transports
    /// without a reconciler keep the legacy topology-driven behaviour.
    @Contract
    default void setDesiredConnections(Supplier<Collection<NodeInfo>> supplier) {}

    /// Wave-1 transition journal feed (cluster-topology-overhaul spec, Enrichment A): install a
    /// listener invoked with one [org.pragmatica.consensus.net.quic.PeerTransitionRecord] per
    /// peer-lifecycle phase mutation (and per dialer expected-vs-actual Hello diagnostic).
    /// Diagnostic-only — emitting has no control-flow effect. Default no-op: transports without
    /// per-peer phase machines (or without journal wiring) are unaffected. A `null` argument
    /// resets to the no-op.
    @Contract
    default void setPeerTransitionListener(Consumer<PeerTransitionRecord> listener) {}

    /// Register a callback fired once the transport is bound and ready to dial/accept
    /// connections — i.e. transport-ready, NOT consensus-quorum-ready. If the transport is
    /// already ready when this is called, the hook runs immediately. Default best-effort for
    /// impls without an explicit ready signal: run immediately.
    @Contract
    default void whenReady(Runnable hook) {
        hook.run();
    }

    /// The labels `nodeId` presented in its Hello, once its handshake has completed on this node; empty before
    /// that. An empty `Map` inside the `Option` means handshaken with no labels (#2981 step 6 reads it as wire
    /// generation 1). Default: transports that record no Hello labels report every peer as not handshaken.
    default Option<Map<String, String>> peerHelloLabels(NodeId nodeId) {
        return Option.empty();
    }

    @Contract
    @MessageReceiver
    void listNodes(ListConnectedNodes listConnectedNodes);

    @Contract
    @MessageReceiver
    void handleSend(Send send);

    @Contract
    @MessageReceiver
    void handleBroadcast(Broadcast broadcast);

    /// Send a message to a specific node and report the synchronous local-transport outcome.
    ///
    /// The outcome is local only (see [WriteOutcome]): [WriteOutcome.Sent] means the transport did not refuse the frame
    /// (it was written, queued, or silently discarded, as a test blackhole does), not that the peer received it. Only a refusal ([WriteOutcome.BackpressureRefused], [WriteOutcome.NoPeerState],
    /// [WriteOutcome.EncodeFailed]) proves the frame never left; [WriteOutcome.ConnectionDead] proves it only for a
    /// [NoOfflineBuffering] frame, because an ordinary one may sit in the offline buffer and be delivered later. A
    /// caller that ignores the returned outcome relies on its own end-to-end mechanism (retransmission, a deadline,
    /// re-derivation) to cover a frame that never arrives.
    ///
    /// Implementations must not throw exceptions, and the returned promise always resolves successfully: a transport
    /// failure is an outcome, never a failed promise.
    <M extends ProtocolMessage> Promise<WriteOutcome> send(NodeId nodeId, M message);
    /// Send a REQUEST frame whose caller stops waiting after `offlineTtl` (#1996), reporting the outcome as
    /// [#send(NodeId, ProtocolMessage)] does.
    ///
    /// Identical to [#send(NodeId, ProtocolMessage)] while the peer is connected. While it is not, the frame may sit
    /// in the offline buffer, and the buffer drops it at the flush once `offlineTtl` (capped by
    /// [#setOfflineBufferCap]) has passed instead of delivering work whose caller has given up. Use it for every
    /// request that has a caller deadline. Plain `send` and `broadcast` carry no caller deadline and are bounded by the
    /// cap alone: nothing the transport buffers is held for ever. A transport with no offline buffer implements it as
    /// plain `send`.
    <M extends ProtocolMessage> Promise<WriteOutcome> send(NodeId nodeId, M message, TimeSpan offlineTtl);

    /// Longest any offline-buffered frame may be held. Default no-op for transports with no offline buffer.
    @Contract
    default Unit setOfflineBufferCap(TimeSpan cap) {
        return Unit.unit();
    }

    /// Start the network.
    Promise<Unit> start();
    /// Stop the network.
    Promise<Unit> stop();
    /// Get the number of currently connected peer nodes.
    /// This count does NOT include self - only remote peers with active connections.
    int connectedNodeCount();
    /// Get the IDs of currently connected peer nodes.
    /// This set does NOT include self - only remote peers with active connections.
    Set<NodeId> connectedPeers();

    /// Peers this node counts as active — CONNECTED plus EVICTED (a stale link awaiting
    /// re-dial, which is not reachable until it re-attaches). Mirrors `activeConnectedCount`
    /// semantics so externally-visible counts do not flicker on momentary evictions. The
    /// transport only logs that count: quorum is decided by the membership layer and the
    /// Rabia engine, not here.
    /// Default falls back to `connectedPeers` for transports without an EVICTED state.
    default Set<NodeId> activePeers() {
        return connectedPeers();
    }

    /// Get the underlying server instance for metrics collection.
    /// Returns empty if the network has not been started yet.
    Option<Server> server();

    /// Test-only fault injection: when enabled, the transport silently drops ALL application
    /// traffic (outbound sends/broadcasts become no-ops; inbound frames are dropped before
    /// routing) WITHOUT closing any connection — peers continue to see the link as connected.
    /// Reproduces a hard process kill with idle-timeout disabled (silent death). Default no-op
    /// for transports that do not support fault injection.
    @Contract
    default void blackhole(boolean enabled) {}

    /// Get transport-level metrics as a name-to-value map.
    /// Returns an empty map by default; QUIC transport overrides with connection/handshake/message stats.
    default Map<String, Number> transportMetrics() {
        return Map.of();
    }
}
