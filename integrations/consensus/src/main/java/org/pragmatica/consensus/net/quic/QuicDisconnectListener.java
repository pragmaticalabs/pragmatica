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


/// Listener fired whenever `QuicClusterNetwork` tears down a peer connection.
///
/// Kept intentionally minimal — consensus has no awareness of cluster-generation
/// typing (that lives in `aether-slice`), so a higher layer is expected to adapt these
/// callbacks into its own domain events.
///
/// **No consumer is installed today.** [`QuicClusterNetwork#setDisconnectListener`] has no
/// callers, so the field keeps its [`#noop`] default and the leader-side
/// `disconnectListener.onDisconnect` call is a no-op for every peer teardown. Liveness
/// bookkeeping reaches the leader through `PeerConnectivityObservation` instead — see
/// `reportPeerRemoval`, which fires both.
@Contract
public interface QuicDisconnectListener {
    void onDisconnect(NodeId nodeId);

    static QuicDisconnectListener noop() {
        return nodeId -> {};
    }
}
