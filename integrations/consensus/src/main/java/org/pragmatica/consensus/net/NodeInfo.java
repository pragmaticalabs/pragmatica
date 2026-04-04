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
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Codec;

import java.util.Map;

/// Node information: ID, address, role, and metadata labels.
///
/// Labels are key-value metadata describing the node's environment (hostname, zone,
/// instance type, pool). They propagate through the Hello handshake so all cluster
/// members can see them.
@Codec
public record NodeInfo(NodeId id, NodeAddress address, NodeRole role, Map<String, String> labels) {
    /// Standard label key for the node's hostname.
    public static final String LABEL_HOSTNAME = "hostname";

    /// Standard label key for the availability zone.
    public static final String LABEL_ZONE = "zone";

    /// Standard label key for the compute instance type.
    public static final String LABEL_INSTANCE_TYPE = "instance-type";

    /// Standard label key for the node pool name.
    public static final String LABEL_POOL = "pool";

    /// Compact constructor ensures labels are an immutable copy.
    public NodeInfo {
        labels = Map.copyOf(labels);
    }

    /// Factory method for creating NodeInfo (backward-compatible, defaults to ACTIVE with no labels).
    public static NodeInfo nodeInfo(NodeId id, NodeAddress address) {
        return new NodeInfo(id, address, NodeRole.ACTIVE, Map.of());
    }

    /// Factory method for creating NodeInfo with explicit role (no labels).
    public static NodeInfo nodeInfo(NodeId id, NodeAddress address, NodeRole role) {
        return new NodeInfo(id, address, role, Map.of());
    }

    /// Factory method for creating NodeInfo with explicit role and labels.
    public static NodeInfo nodeInfo(NodeId id, NodeAddress address, NodeRole role, Map<String, String> labels) {
        return new NodeInfo(id, address, role, labels);
    }
}
