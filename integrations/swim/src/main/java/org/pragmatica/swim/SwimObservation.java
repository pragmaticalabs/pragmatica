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
import org.pragmatica.consensus.net.NodeInfo;


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
///
/// ### Versioning
/// `incarnation` = SWIM-protocol generation (per-peer monotonic counter; advances
/// on peer restart / suspect refutation).
/// `version`     = wire-format schema version (global, monotonic per code release;
/// see [CURRENT_VERSION]). RC1 is the version-floor — pre-RC1 in-process state
/// is not migrated. The field is placed LAST on every variant so that auto-generated
/// `@Codec` decoders remain compatible with persisted state that never carried it.
/// Receiver policy on mismatch: WARN-log and drop the observation (gossip is
/// self-healing; dropping unknown-version frames is preferred over crashing).
public sealed interface SwimObservation {
    /// Current schema version stamped onto every newly-constructed observation.
    /// Bump on any structural change to the variant payload.
    byte CURRENT_VERSION = 1;
    NodeId peer();
    long incarnation();
    byte version();

    /// Peer is reachable / has joined / has recovered from SUSPECT.
    record HealthyObserved(NodeId peer, long incarnation, byte version) implements SwimObservation {
        public HealthyObserved(NodeId peer, long incarnation) {
            this(peer, incarnation, CURRENT_VERSION);
        }
    }

    /// Failure detector has not received an ack within the probe-timeout window.
    record SuspectObserved(NodeId peer, long incarnation, byte version) implements SwimObservation {
        public SuspectObserved(NodeId peer, long incarnation) {
            this(peer, incarnation, CURRENT_VERSION);
        }
    }

    /// Failure detector has confirmed the peer faulty after suspect-window expiry
    /// (or k-of-n peers reporting suspect). Emitted only for peers that have
    /// previously reached HEALTHY at least once.
    record FaultyObserved(NodeId peer, long incarnation, byte version) implements SwimObservation {
        public FaultyObserved(NodeId peer, long incarnation) {
            this(peer, incarnation, CURRENT_VERSION);
        }
    }

    /// Peer has been removed from the membership list (gossip departed).
    record DepartedObserved(NodeId peer, long incarnation, byte version) implements SwimObservation {
        public DepartedObserved(NodeId peer, long incarnation) {
            this(peer, incarnation, CURRENT_VERSION);
        }
    }

    /// Cold-boot suppression placeholder: emitted in lieu of `FaultyObserved`
    /// for peers that have never been observed HEALTHY. Signals "not yet here,
    /// not failed."
    record UnknownObserved(NodeId peer, long incarnation, byte version) implements SwimObservation {
        public UnknownObserved(NodeId peer, long incarnation) {
            this(peer, incarnation, CURRENT_VERSION);
        }
    }

    /// A new peer has announced itself via the ANNOUNCE datagram and the gossip layer
    /// has propagated the event. Carries the full `NodeInfo` (id, address, role, labels)
    /// so receivers can immediately register the peer without a separate lookup.
    record JoinAnnounced(NodeInfo nodeInfo, String clusterName, long incarnation, byte version) implements SwimObservation {
        public JoinAnnounced(NodeInfo nodeInfo, String clusterName, long incarnation) {
            this(nodeInfo, clusterName, incarnation, CURRENT_VERSION);
        }

        @Override
        public NodeId peer() {
            return nodeInfo.id();
        }
    }

    /// Emitted whenever a node is admitted to SWIM membership (via direct ANNOUNCE OR via
    /// gossip/probe), carrying the peer's `NodeInfo` derived for the QUIC dial set. Unlike
    /// `JoinAnnounced` (which fires only on a directly-received ANNOUNCE), this fires for
    /// gossip-learned members too, so the QUIC dial set sees every SWIM-known peer.
    record MemberDiscovered(NodeInfo nodeInfo, long incarnation, byte version) implements SwimObservation {
        public MemberDiscovered(NodeInfo nodeInfo, long incarnation) {
            this(nodeInfo, incarnation, CURRENT_VERSION);
        }

        @Override
        public NodeId peer() {
            return nodeInfo.id();
        }
    }
}
