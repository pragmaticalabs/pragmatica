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
package org.pragmatica.swim;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;


/// Informational hint emitted by Layer 0 (Transport / QUIC) toward Layer 1 (SWIM).
///
/// Per the membership-architecture spec (§4.1, §4.2, §6 Signal Catalog):
/// this signal is **advisory only**. SWIM remains the canonical health source
/// and is free to ignore or merely bias its own suspect-window timers based on
/// transport observations. Layer 0 must never translate transport observations
/// into authoritative HEALTHY/FAULTY signals — those still go through SWIM
/// gossip aggregation.
///
/// Variants:
/// - [`PeerReachable`] — local transport reports the peer's connection
///   established or recovered. It retracts the transport's own
///   [`HintOrigin#LINK_LOST`] hint for that peer and never reports the peer alive.
/// - [`PeerUnreachable`] — local transport reports death evidence for the peer;
///   SWIM may shorten its suspect window for this peer from the default toward a
///   configured floor. Its [`HintOrigin`] decides how long SWIM believes it (#1061).
public sealed interface TransportObservation {
    NodeId peer();

    /// Where a [`PeerUnreachable`] hint's evidence comes from (#1061).
    enum HintOrigin {
        /// The local transport lost its link to the peer (eviction, channel close). The
        /// evidence describes that link only, so SWIM disregards it while the link is
        /// connected and drops it when the transport reconnects.
        LINK_LOST,
        /// The link is up but the peer stopped answering a liveness exchange carried over it
        /// (a hung process). A connected link does not contradict it, so it survives
        /// reconnects; only SWIM's own HEALTHY evidence clears it.
        PEER_UNRESPONSIVE
    }

    record PeerReachable(NodeId peer) implements TransportObservation {}

    record PeerUnreachable(NodeId peer, Cause cause, HintOrigin origin) implements TransportObservation {}
}
