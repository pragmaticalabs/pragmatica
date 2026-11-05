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
package org.pragmatica.dht;

import java.util.Set;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Promise;


/// Minimal network abstraction for DHT inter-node messaging.
/// Core replicas and worker clients use the ClusterNetwork adapter to reach the core DHT ring.
@FunctionalInterface
public interface DHTNetwork {
    /// Send a protocol message to the target peer and return the synchronous local-transport outcome.
    ///
    /// Adapters that can detect immediate refusals (notably the QUIC-backed `ClusterNetwork` adapter) surface them;
    /// DHT quorum collectors use this outcome to fail-fast against unreachable replicas instead of waiting the per-op
    /// 10s timeout. A simple implementation is a single lambda that reports [WriteOutcome.Sent].
    ///
    /// See `aether/docs/specs/dht-resilience-spec.md` Layer 1 and Layer 3.
    Promise<WriteOutcome> send(NodeId target, ProtocolMessage message);

    /// Currently-reachable peer set. Used by `DistributedDHTClient` to filter the static
    /// consistent-hash target list down to peers that can actually be addressed right now
    /// — the ring continues to describe ownership, this set adds runtime reachability.
    /// When the live intersection falls below quorum, the operation fails fast with
    /// `DHTError.QuorumNotReached` instead of stalling on unreachable targets until the
    /// per-op timeout.
    ///
    /// Default impl returns an empty set, treated by `DistributedDHTClient` as
    /// "filter disabled" — adapters with real connectivity introspection override.
    ///
    /// See `aether/docs/specs/dht-resilience-spec.md` Layer 2.
    default Set<NodeId> livePeers() {
        return Set.of();
    }
}
