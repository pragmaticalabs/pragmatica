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

import java.util.List;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.Message;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;


public sealed interface NetworkServiceMessage extends Message.Local {
    record ConnectNode(NodeId node) implements NetworkServiceMessage {}

    record DisconnectNode(NodeId nodeId) implements NetworkServiceMessage {}

    record ListConnectedNodes() implements NetworkServiceMessage {}

    record ConnectedNodesList(List<NodeId> connected) implements NetworkServiceMessage {}

    /// Notification that a connection attempt to a node has failed.
    record ConnectionFailed(NodeId nodeId, Cause cause) implements NetworkServiceMessage {}

    /// Notification that a connection to a node has been established.
    ///
    /// `nodeInfo` carries the peer's full identity (id + address + role + labels) as
    /// learned from the QUIC/Netty Hello handshake when available. Downstream consumers
    /// (notably SWIM health detection in `AetherNode`) MUST prefer this transport-supplied
    /// `NodeInfo` over a static-topology lookup so that CTM-replaced or topology-forgotten
    /// peers can be probed at their true SWIM address (fixes P1: QUIC eviction storm after
    /// topology-forgot-peer reconnect).
    record ConnectionEstablished(NodeId nodeId, Option<NodeInfo> nodeInfo) implements NetworkServiceMessage {
        /// Factory for transport sites that have learned the peer's `NodeInfo` from a
        /// Hello handshake.
        public static ConnectionEstablished connectionEstablished(NodeId nodeId, NodeInfo nodeInfo) {
            return new ConnectionEstablished(nodeId, option(nodeInfo));
        }

        /// Factory for transport sites that do not (yet) have the peer's `NodeInfo`.
        public static ConnectionEstablished connectionEstablished(NodeId nodeId) {
            return new ConnectionEstablished(nodeId, none());
        }
    }

    /// Send a wired message to a specific target node
    record Send(NodeId target, Message.Wired payload) implements NetworkServiceMessage {}

    /// Broadcast a wired message to all connected nodes
    record Broadcast(Message.Wired payload) implements NetworkServiceMessage {}
}
