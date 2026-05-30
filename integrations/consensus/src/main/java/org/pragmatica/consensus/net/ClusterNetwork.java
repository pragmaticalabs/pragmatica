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

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.net.tcp.Server;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.pragmatica.consensus.net.NetworkServiceMessage.*;

/// Generalized Network API for cluster communication.
///
/// **Implementation contract:** All methods must be exception-safe.
/// Network failures should be logged internally, not thrown to callers.
/// The consensus protocol handles message loss through timeouts and retries.
public interface ClusterNetwork {
    /// Broadcast a message to all nodes in the cluster.
    ///
    /// Note that actual implementation may just send messages directly,
    /// not necessarily use broadcasting in underlying protocol.
    ///
    /// Implementations must not throw exceptions. Failed sends should be
    /// logged and silently ignored - the protocol handles message loss.
    <M extends ProtocolMessage> Unit broadcast(M message);

    @MessageReceiver
    void connect(ConnectNode connectNode);

    @MessageReceiver
    void disconnect(DisconnectNode disconnectNode);

    /// Permanently remove a decommissioned peer. Unlike [#disconnect(DisconnectNode)],
    /// which soft-evicts to EVICTED state (allowing future reconnect), this removes the
    /// peer entry entirely (REMOVED state) and closes the datagram channel, preventing
    /// WARN flood from broadcast attempts to a dead node.
    default void departurePermanent(NodeId nodeId) {}

    /// Connect to a newly-announced peer using its full NodeInfo (address + id).
    /// Called by the SWIM JoinAnnounced listener for peers not in the static topology.
    default void connect(NodeInfo nodeInfo) {}

    /// Gate the missing-peer reconciler: peers for which the supplier returns false
    /// (FAULTY or UNKNOWN in SWIM) are skipped during reconnect attempts.
    default void setSwimHealthGate(Function<NodeId, Boolean> gate) {}

    /// Register a callback fired once the transport is bound and ready to dial/accept
    /// connections — i.e. transport-ready, NOT consensus-quorum-ready. If the transport is
    /// already ready when this is called, the hook runs immediately. Default best-effort for
    /// impls without an explicit ready signal: run immediately.
    default void whenReady(Runnable hook) { hook.run(); }

    @MessageReceiver
    void listNodes(ListConnectedNodes listConnectedNodes);

    @MessageReceiver
    void handleSend(Send send);

    @MessageReceiver
    void handleBroadcast(Broadcast broadcast);

    /// Send a message to a specific node.
    ///
    /// Implementations must not throw exceptions. Failed sends should be
    /// logged and silently ignored - the protocol handles message loss.
    <M extends ProtocolMessage> Unit send(NodeId nodeId, M message);

    /// Send a message to a specific node and return the synchronous local-transport
    /// outcome.
    ///
    /// Where [#send(NodeId, ProtocolMessage)] is fire-and-forget (failures handled
    /// upstream via protocol retransmits / timeouts), this overload reports back
    /// immediately whether the message was enqueued ([WriteOutcome.Sent]) or refused
    /// (backpressure / dead connection / unknown peer). Callers that need to react
    /// faster than per-operation timeouts — currently only the DHT quorum path — use
    /// this to short-circuit waiting on a clearly-unreachable replica.
    ///
    /// Default implementation falls back to `send` and returns [WriteOutcome.Sent] —
    /// transports without per-write outcome tracking remain backward-compatible.
    ///
    /// See `aether/docs/specs/dht-resilience-spec.md` Layer 1.
    default <M extends ProtocolMessage> Promise<WriteOutcome> sendOutcome(NodeId nodeId, M message) {
        send(nodeId, message);
        return Promise.success(new WriteOutcome.Sent(nodeId));
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

    /// Peers locally reachable for quorum purposes — includes CONNECTED and EVICTED
    /// (transient stale-link awaiting reconcile). Mirrors `activeConnectedCount`
    /// semantics so externally-visible counts do not flicker on momentary evictions.
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
    default void blackhole(boolean enabled) {}

    /// Get transport-level metrics as a name-to-value map.
    /// Returns an empty map by default; QUIC transport overrides with connection/handshake/message stats.
    default Map<String, Number> transportMetrics() {
        return Map.of();
    }
}
