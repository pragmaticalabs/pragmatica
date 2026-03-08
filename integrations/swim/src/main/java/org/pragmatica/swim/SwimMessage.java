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
import java.util.List;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.serialization.Codec;
import org.pragmatica.swim.SwimMember.MemberState;

/// Messages exchanged by the SWIM protocol.
@Codec
public sealed interface SwimMessage {

    /// Direct ping probe carrying piggybacked membership updates.
    record Ping(NodeId from, long sequence, List<MembershipUpdate> piggyback) implements SwimMessage {
        public static Ping ping(NodeId from, long sequence, List<MembershipUpdate> piggyback) {
            return new Ping(from, sequence, piggyback);
        }
    }

    /// Acknowledgement of a Ping, also carrying piggybacked membership updates.
    record Ack(NodeId from, long sequence, List<MembershipUpdate> piggyback) implements SwimMessage {
        public static Ack ack(NodeId from, long sequence, List<MembershipUpdate> piggyback) {
            return new Ack(from, sequence, piggyback);
        }
    }

    /// Indirect probe request: asks another member to ping the target on our behalf.
    record PingReq(NodeId from, NodeId target, long sequence) implements SwimMessage {
        public static PingReq pingReq(NodeId from, NodeId target, long sequence) {
            return new PingReq(from, target, sequence);
        }
    }

    /// A single membership update disseminated via piggyback.
    @Codec
    record MembershipUpdate(NodeId nodeId, MemberState state, long incarnation, InetSocketAddress address) {
        public static MembershipUpdate membershipUpdate(NodeId nodeId, MemberState state, long incarnation, InetSocketAddress address) {
            return new MembershipUpdate(nodeId, state, incarnation, address);
        }
    }
}
