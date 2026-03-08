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

import org.pragmatica.consensus.NodeId;
import org.pragmatica.serialization.Codec;

/// A member in the SWIM protocol membership list.
///
/// @param nodeId      unique identifier for the member
/// @param state       current membership state
/// @param incarnation monotonically increasing counter used to refute suspicions
/// @param address     network address for sending probes
@Codec
public record SwimMember(NodeId nodeId, MemberState state, long incarnation, InetSocketAddress address) {

    /// Membership state of a SWIM member.
    @Codec
    public enum MemberState {
        ALIVE,
        SUSPECT,
        FAULTY
    }

    /// Factory creating a member with all parameters.
    public static SwimMember swimMember(NodeId nodeId, MemberState state, long incarnation, InetSocketAddress address) {
        return new SwimMember(nodeId, state, incarnation, address);
    }

    /// Factory creating an ALIVE member with incarnation 0.
    public static SwimMember swimMember(NodeId nodeId, InetSocketAddress address) {
        return new SwimMember(nodeId, MemberState.ALIVE, 0, address);
    }

    /// Return a copy with the given state.
    public SwimMember withState(MemberState newState) {
        return new SwimMember(nodeId, newState, incarnation, address);
    }

    /// Return a copy with the given incarnation.
    public SwimMember withIncarnation(long newIncarnation) {
        return new SwimMember(nodeId, state, newIncarnation, address);
    }
}
