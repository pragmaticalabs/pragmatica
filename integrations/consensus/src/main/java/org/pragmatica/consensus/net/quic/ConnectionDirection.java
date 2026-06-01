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

import org.pragmatica.consensus.NodeId;

/// Deterministic duplicate-resolution tiebreak for QUIC peer connections.
///
/// This is NO LONGER a dial precondition: any node dials any peer it is missing
/// (natural establishment). When both ends dial concurrently and two live
/// connections exist for one pair, this comparator decides — symmetrically on
/// both ends — which connection's *initiator* wins, so the cluster converges on
/// a single survivor regardless of NodeId order (multi-cloud safe). The loser is
/// closed in isolation (no view-change, no REMOVE).
///
/// The winning initiator is the lower NodeId (`min` over the two initiator ids).
/// A connection's initiator is the local node when WE dialed it (client path) or
/// the peer when WE accepted it (server path).
public sealed interface ConnectionDirection {

    /// Returns true when `candidate` is the preferred surviving initiator over
    /// `incumbent` for a duplicate connection pair (lower NodeId wins). Total and
    /// symmetric: both ends, fed the same pair of initiator ids, agree on the winner.
    static boolean prefersInitiator(NodeId candidate, NodeId incumbent) {
        return candidate.compareTo(incumbent) < 0;
    }

    record Unused() implements ConnectionDirection {}
}
