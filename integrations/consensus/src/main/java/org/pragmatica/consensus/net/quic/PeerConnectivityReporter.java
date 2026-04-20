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
import org.pragmatica.lang.Contract;


/// Follower-side QUIC connectivity observation sink.
///
/// Consensus has no knowledge of cluster-sync wire types (those live in the
/// `integrations/cluster` module). Higher layers adapt this callback into
/// `PeerConnectivityObservation` records carried on the next outbound
/// `ClusterSyncPong`.
///
/// See `aether/docs/specs/clustersync-refactor-spec.md` commit 2.
@Contract public interface PeerConnectivityReporter {
    /// Follower observed the peer as DISCONNECTED at the given epoch.
    void onPeerDisconnected(NodeId peerId, long observedTerm, long observedCounter);

    static PeerConnectivityReporter noop() {
        return (_, _, _) -> { };
    }
}
