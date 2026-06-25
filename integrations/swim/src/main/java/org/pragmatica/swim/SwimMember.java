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

import java.net.InetSocketAddress;
import java.util.Map;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.serialization.Codec;

/// A member in the SWIM protocol membership list.
///
/// @param nodeId      unique identifier for the member
/// @param state       current membership state
/// @param incarnation monotonically increasing counter used to refute suspicions
/// @param address     network address for sending probes
/// @param labels      descriptor labels (e.g. role/source) learned from the member's
///                    ANNOUNCE `NodeInfo`. Empty when the member was learned only via
///                    transitive piggyback (back-compat default). Never on the SWIM wire —
///                    `SwimMember` is not a `SwimMessage` variant.
@Codec
public record SwimMember(NodeId nodeId, MemberState state, long incarnation, InetSocketAddress address,
                         Map<String, String> labels) {

    /// Compact constructor ensures labels are an immutable copy.
    public SwimMember {
        labels = Map.copyOf(labels);
    }

    /// Membership state of a SWIM member.
    ///
    /// `OBSERVED` is a LOCAL-ONLY birth state for a freshly seeded/announced member: known
    /// and probe-eligible, but NOT yet alive and NOT death-timer-armed, until a real probe-ack
    /// (or a gossiped `Alive`) promotes it to `ALIVE` or a sustained probe-timeout past the join
    /// deadline escalates it to `SUSPECT` (#336/#241). It is the WEAKEST membership state, so a
    /// same-incarnation gossiped `Alive` supersedes it (it is NOT sticky). It is never disseminated
    /// on the SWIM wire — BOTH `SwimProtocol.addMemberUpdate` overloads drop any OBSERVED member
    /// before the piggyback-buffer serialization chokepoint — so peers never learn it. Appended
    /// last for cleanliness.
    @Codec
    public enum MemberState {
        ALIVE,
        SUSPECT,
        FAULTY,
        OBSERVED
    }

    /// Factory creating a member with all parameters and explicit labels.
    public static SwimMember swimMember(NodeId nodeId, MemberState state, long incarnation,
                                        InetSocketAddress address, Map<String, String> labels) {
        return new SwimMember(nodeId, state, incarnation, address, labels);
    }

    /// Factory creating a member with all parameters (no labels — back-compat default).
    public static SwimMember swimMember(NodeId nodeId, MemberState state, long incarnation, InetSocketAddress address) {
        return new SwimMember(nodeId, state, incarnation, address, Map.of());
    }

    /// Factory creating an ALIVE member with incarnation 0 (no labels).
    public static SwimMember swimMember(NodeId nodeId, InetSocketAddress address) {
        return new SwimMember(nodeId, MemberState.ALIVE, 0, address, Map.of());
    }

    /// Return a copy with the given state, preserving labels.
    public SwimMember withState(MemberState newState) {
        return new SwimMember(nodeId, newState, incarnation, address, labels);
    }

    /// Return a copy with the given incarnation, preserving labels.
    public SwimMember withIncarnation(long newIncarnation) {
        return new SwimMember(nodeId, state, newIncarnation, address, labels);
    }

    /// Return a copy with the given probe address, preserving identity/state/incarnation/labels
    /// (cluster-topology-overhaul Wave 9 Fix C). Used when a re-ANNOUNCE / gossip update for an
    /// ALREADY-KNOWN member carries a NEW source-derived address — e.g. a Docker IP reshuffle on
    /// partition-heal. Without adopting it, SWIM keeps probing the stale pre-partition IP, acks
    /// go silent, and the member is falsely declared FAULTY (the post-heal stale-IP probe storm).
    public SwimMember withAddress(InetSocketAddress newAddress) {
        return new SwimMember(nodeId, state, incarnation, newAddress, labels);
    }
}
