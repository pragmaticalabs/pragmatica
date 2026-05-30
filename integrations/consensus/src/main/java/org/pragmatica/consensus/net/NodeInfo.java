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
import java.util.Objects;

/// Node information: ID, address, role, and metadata labels.
///
/// Labels are key-value metadata describing the node's environment (hostname, zone,
/// instance type, pool). They propagate through the Hello handshake so all cluster
/// members can see them.
///
/// `resolvedAddress` is the dial-preferred address: the SWIM-observed source IP of the
/// peer's ANNOUNCE datagram combined with the peer's advertised QUIC port. It lets the
/// transport dial a concrete IP instead of synchronously re-resolving a gossiped
/// hostname on the connect path (membership v2 §5). It defaults to `address` when no
/// SWIM resolution has occurred, so non-SWIM-discovered peers behave exactly as before.
///
/// `resolvedAddress` is deliberately EXCLUDED from `equals`/`hashCode`: node identity is
/// (id, address, role, labels). The resolved address is a transient transport dial-hint
/// that must not split a peer into two distinct identities when only its observed IP
/// differs from its advertised host.
@Codec
public record NodeInfo(NodeId id, NodeAddress address, NodeRole role, Map<String, String> labels,
                       NodeAddress resolvedAddress) {
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
        return new NodeInfo(id, address, NodeRole.ACTIVE, Map.of(), address);
    }

    /// Factory method for creating NodeInfo with explicit role (no labels).
    public static NodeInfo nodeInfo(NodeId id, NodeAddress address, NodeRole role) {
        return new NodeInfo(id, address, role, Map.of(), address);
    }

    /// Factory method for creating NodeInfo with explicit role and labels.
    public static NodeInfo nodeInfo(NodeId id, NodeAddress address, NodeRole role, Map<String, String> labels) {
        return new NodeInfo(id, address, role, labels, address);
    }

    /// Factory method for creating NodeInfo with an explicit resolved (dial-preferred) address.
    public static NodeInfo nodeInfo(NodeId id, NodeAddress address, NodeRole role,
                                    Map<String, String> labels, NodeAddress resolvedAddress) {
        return new NodeInfo(id, address, role, labels, resolvedAddress);
    }

    /// Return a copy with the dial-preferred address replaced, preserving identity
    /// (id, address, role, labels). Used by the SWIM ANNOUNCE path to attach the
    /// observed source IP : advertised QUIC port to a discovered peer.
    public NodeInfo withResolvedAddress(NodeAddress resolved) {
        return new NodeInfo(id, address, role, labels, resolved);
    }

    /// Identity is (id, address, role, labels). `resolvedAddress` is a transient dial-hint
    /// and is intentionally excluded so SWIM resolution never forks a peer into two identities.
    @Override
    public boolean equals(Object o) {
        return o instanceof NodeInfo(var oid, var oaddress, var orole, var olabels, var ignored)
               && id.equals(oid)
               && address.equals(oaddress)
               && role.equals(orole)
               && labels.equals(olabels);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, address, role, labels);
    }
}
