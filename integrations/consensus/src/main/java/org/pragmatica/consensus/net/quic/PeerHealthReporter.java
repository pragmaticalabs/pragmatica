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


/// QUIC view-change health observation sink.
///
/// Per Q6 design: SWIM `Stopped` / `Starting` no longer process peer events. Production-safe
/// observation arrival flows from `QuicClusterNetwork.processViewChange` — every NodeAdded
/// event reports HEALTHY, every NodeRemoved reports FAULTY. The consensus module has no
/// knowledge of the higher-layer `PeerObservationStore` (which lives in `aether/aether-metrics`)
/// — higher layers adapt these callbacks into `PeerHealthObservation` writes.
@Contract public interface PeerHealthReporter {
    /// QUIC observed the peer as HEALTHY (NodeAdded view-change).
    void onPeerHealthy(NodeId peerId, long observedTerm, long observedCounter);

    /// QUIC observed the peer as FAULTY (NodeRemoved / NodeDown view-change).
    void onPeerFaulty(NodeId peerId, long observedTerm, long observedCounter);

    static PeerHealthReporter noop() {
        return new PeerHealthReporter() {
            @Override public void onPeerHealthy(NodeId peerId, long observedTerm, long observedCounter) { }
            @Override public void onPeerFaulty(NodeId peerId, long observedTerm, long observedCounter) { }
        };
    }
}
