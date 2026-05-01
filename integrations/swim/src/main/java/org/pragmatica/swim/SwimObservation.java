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

/// Edge-triggered per-peer health observation emitted by SWIM (Layer 1).
///
/// Per the membership-architecture spec (§4.2, §6 Signal Catalog):
/// SWIM is the canonical health-observation source. Each variant fires
/// **once per state edge** for a given peer (P5: idempotent edge transitions).
/// Re-emission of the same state for the same peer does NOT produce a new
/// observation.
///
/// Cold-boot suppression: a peer that has never reached HEALTHY does NOT
/// receive a `FaultyObserved` emission. Instead, an `UnknownObserved` is
/// emitted to signal "not yet here, not failed." Once a peer reaches
/// HEALTHY at least once, normal FAULTY semantics apply forever after for
/// that peer.
public sealed interface SwimObservation {
    NodeId peer();
    long incarnation();

    /// Peer is reachable / has joined / has recovered from SUSPECT.
    record HealthyObserved(NodeId peer, long incarnation) implements SwimObservation {}

    /// Failure detector has not received an ack within the probe-timeout window.
    record SuspectObserved(NodeId peer, long incarnation) implements SwimObservation {}

    /// Failure detector has confirmed the peer faulty after suspect-window expiry
    /// (or k-of-n peers reporting suspect). Emitted only for peers that have
    /// previously reached HEALTHY at least once.
    record FaultyObserved(NodeId peer, long incarnation) implements SwimObservation {}

    /// Peer has been removed from the membership list (gossip departed).
    record DepartedObserved(NodeId peer, long incarnation) implements SwimObservation {}

    /// Cold-boot suppression placeholder: emitted in lieu of `FaultyObserved`
    /// for peers that have never been observed HEALTHY. Signals "not yet here,
    /// not failed."
    record UnknownObserved(NodeId peer, long incarnation) implements SwimObservation {}
}
