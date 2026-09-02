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
///   established or recovered.
/// - [`PeerUnreachable`] — local transport reports the peer's connection
///   lost / failed; SWIM may shorten its suspect window for this peer
///   from the default toward a configured floor.
public sealed interface TransportObservation {
    NodeId peer();

    record PeerReachable(NodeId peer) implements TransportObservation {}

    record PeerUnreachable(NodeId peer, Cause cause) implements TransportObservation {}
}
