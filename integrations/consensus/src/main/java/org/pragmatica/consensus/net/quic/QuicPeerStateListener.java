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


/// Listener fired on every QUIC peer-state transition emitted by `QuicClusterNetwork`,
/// including transient eviction-driven reconnects whose upstream `TransportObservation.PeerJoined`
/// is suppressed (per the flap-loop fix). Higher layers (e.g. `ClusterTopologyManager`) consume
/// these to keep stability windows aligned with the transport's view, even when membership
/// notifications are debounced.
///
/// Distinct from [`QuicDisconnectListener`] — that listener feeds the leader's
/// `HealthReconciler` with a `HealthSignal.QuicDisconnect` and does NOT fire on
/// join/reconnect. This listener fires on every QUIC peer-state change so CTM-style
/// stability bookkeeping can remain consistent with the transport.
@Contract
public interface QuicPeerStateListener {
    /// First-time peer attach (INIT/CONNECTING -> CONNECTED). Upstream consumers
    /// receive a fresh `TransportObservation.PeerJoined`.
    void onPeerJoined(NodeId nodeId);
    /// Reconnect after a transient eviction (EVICTED -> CONNECTED, or a stale
    /// CONNECTED link replaced). Upstream `TransportObservation.PeerJoined` is suppressed;
    /// consumers that track peer-state churn (e.g., CTM stability gate) observe via this hook
    /// or via `TransportObservation.PeerReconnected`.
    void onPeerReconnected(NodeId nodeId);
    /// Peer authoritatively left (REMOVED) or QUIC view-change emitted REMOVE.
    void onPeerLeft(NodeId nodeId);

    static QuicPeerStateListener noop() {
        return new QuicPeerStateListener() {
            @Override
            public void onPeerJoined(NodeId nodeId) {}

            @Override
            public void onPeerReconnected(NodeId nodeId) {}

            @Override
            public void onPeerLeft(NodeId nodeId) {}
        };
    }
}
