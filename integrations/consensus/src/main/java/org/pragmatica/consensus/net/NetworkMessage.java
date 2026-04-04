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
import org.pragmatica.consensus.net.NodeRole;
import org.pragmatica.messaging.Message;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Codec;

import java.util.Map;

@Codec
public sealed interface NetworkMessage extends Message.Wired {
    /// Hello - connection handshake, sent by both sides on channel activation.
    /// Carries the sender's role, cluster address, and metadata labels so receiving
    /// nodes can identify passive peers and add dynamically provisioned nodes to
    /// their topology with full metadata.
    record Hello(NodeId sender, NodeRole role, NodeAddress address, Map<String, String> labels) implements NetworkMessage {}

    /// Topology discovery request - asks recipient to share their known nodes
    record DiscoverNodes(NodeId self) implements NetworkMessage {}

    /// Topology discovery response - list of known nodes sent to target
    record DiscoveredNodes(NodeId target, java.util.List<NodeInfo> nodes) implements NetworkMessage {}

    /// KV-Store snapshot request — sent by passive nodes to get current state.
    record KVSyncRequest(NodeId sender) implements NetworkMessage {}

    /// KV-Store snapshot response — carries serialized KV-Store state.
    record KVSyncResponse(NodeId target, byte[] snapshot) implements NetworkMessage {}
}
