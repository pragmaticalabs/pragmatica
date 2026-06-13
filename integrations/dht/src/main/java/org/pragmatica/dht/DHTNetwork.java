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

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;

import java.util.Set;

/// Minimal network abstraction for DHT inter-node messaging.
/// Implemented by both core cluster network (via ClusterNetwork adapter)
/// and worker network (via WorkerDHTNetwork).
@FunctionalInterface
public interface DHTNetwork {
    /// Fire-and-forget send of a protocol message to the target peer. The synchronous
    /// local-transport outcome is surfaced via [#sendOutcome] for adapters that can detect it;
    /// this SAM is intentionally void so the simplest implementations remain a single lambda.
    @Contract
    void send(NodeId target, ProtocolMessage message);

    /// Send a message and return the synchronous local-transport outcome.
    ///
    /// Default impl falls back to `send` and reports [WriteOutcome.Sent] — adapters that
    /// can detect immediate refusals (notably the QUIC-backed `ClusterNetwork` adapter)
    /// override to surface them. DHT quorum collectors use this outcome to fail-fast
    /// against unreachable replicas instead of waiting the per-op 10s timeout.
    ///
    /// See `aether/docs/specs/dht-resilience-spec.md` Layer 3.
    default Promise<WriteOutcome> sendOutcome(NodeId target, ProtocolMessage message) {
        send(target, message);
        return Promise.success(new WriteOutcome.Sent(target));
    }

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
