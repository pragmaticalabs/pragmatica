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
package org.pragmatica.consensus.topology;

import java.time.Instant;

import org.pragmatica.consensus.net.NodeInfo;


/// A node this observer has DISCOVERED, and when it was first seen.
///
/// ## This type deliberately carries no health (#558)
///
/// It used to hold `NodeHealth`, `failedAttempts` and `nextAttemptAfter`, with a `suspected(...)`
/// constructor and a `canAttemptConnection(now)` backoff gate. **None of it was ever driven.**
/// `suspected(...)` had zero callers repo-wide, and `TopologyObserver.nodeStatesById` has exactly two
/// mutation sites — `putIfAbsent` of a fresh entry, and `remove`. Every entry was born HEALTHY and
/// stayed HEALTHY until removed, so `health == HEALTHY` was a constant-true predicate and
/// `canAttemptConnection` a constant-true gate.
///
/// That vocabulary was not harmless. A reader auditing a count sees `filter(health == HEALTHY)` and
/// reasonably concludes it is reachability-aware; it was not. That misreading reached production:
/// `ClusterTopologyManagerRecord.buildProvisionContext` filtered a replacement node's PEERS list
/// through the same predicate and a neighbouring docstring asserted it kept dead hosts out (#678).
///
/// ## Why deleted rather than wired
///
/// There is exactly ONE re-dial authority, and it is not here. The transport layer owns re-dial
/// policy — QUIC peer-phase dedup, the in-flight CONNECTING guard, and the per-attempt dial timeout —
/// while SWIM owns suspicion. Reviving this backoff would install a SECOND authority that nothing
/// currently exercises, and two mechanisms disagreeing about when to re-dial is worse than one. A
/// vestigial gate that nothing drives is a standing invitation to wire it someday and create exactly
/// that disagreement.
///
/// Reachability now lives where it is genuinely observed: `TopologyObserver.observedConnections`
/// (post-handshake CONNECTED peers) for boot quorum, and `MembershipFsm.coreObservedMembers` (QUIC
/// handshake or SWIM ALIVE) for the steady-state quorum numerator.
///
/// @param info      The node information
/// @param firstSeen When this node was first discovered
public record NodeState(NodeInfo info, Instant firstSeen) {
    /// Creates a state for a newly discovered node. Discovery is all this records — it is NOT a
    /// claim that the node is reachable.
    public static NodeState discovered(NodeInfo info, Instant now) {
        return new NodeState(info, now);
    }
}
