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
import java.util.Map;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
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

    /// UDP datagram broadcast by a joining node so existing members can register it
    /// without waiting for the next Ping/Ack cycle. Carries the full `NodeInfo`
    /// (id, address, role, labels), cluster name for membership gating, and the sender's
    /// per-process random `bootToken` (`0` = none; compared by equality only).
    record Announce(NodeInfo nodeInfo, String clusterName, long incarnation, long bootToken) implements SwimMessage {
        /// An ANNOUNCE carrying no process token (`0`): never token-gated.
        public static Announce announce(NodeInfo nodeInfo, String clusterName, long incarnation) {
            return new Announce(nodeInfo, clusterName, incarnation, 0L);
        }

        public static Announce announce(NodeInfo nodeInfo, String clusterName, long incarnation, long bootToken) {
            return new Announce(nodeInfo, clusterName, incarnation, bootToken);
        }
    }

    /// Address-reflection request: a joining node asks a seed "what source address did you
    /// observe my datagram coming from?". The seed replies with [WhoAmIReply] carrying the
    /// kernel-resolved source address. `from` is the asking node's id (for logging/trace).
    record WhoAmI(NodeId from) implements SwimMessage {
        public static WhoAmI whoAmI(NodeId from) {
            return new WhoAmI(from);
        }
    }

    /// Reply to a [WhoAmI] request: `observedAddress` is the source address the seed observed
    /// on the requester's datagram, echoed back so the requester can advertise a routable address.
    record WhoAmIReply(InetSocketAddress observedAddress) implements SwimMessage {
        public static WhoAmIReply whoAmIReply(InetSocketAddress observedAddress) {
            return new WhoAmIReply(observedAddress);
        }
    }

    /// Explicit refusal of an ANNOUNCE by the boot-token gate (terminal removal): tells the announcing
    /// process that its NodeId belongs to a retired or different process, so it can exit instead of
    /// announcing forever. `refused` is the NodeId the refusal is about.
    record IdentityRefused(NodeId from, NodeId refused, String reason) implements SwimMessage {
        public static IdentityRefused identityRefused(NodeId from, NodeId refused, String reason) {
            return new IdentityRefused(from, refused, reason);
        }
    }

    /// A single membership update disseminated via piggyback. `bootToken` is the subject's
    /// per-process random boot token as known to the sender (`0` = unknown; equality only).
    /// `labels` are the subject's descriptor labels (role/source) as known to the sender — empty
    /// when the sender learned the subject without them. Carrying them is what lets a peer that
    /// learns the subject ONLY by gossip still classify its role (a core counts only with an
    /// explicit `role=core`).
    @Codec
    record MembershipUpdate(NodeId nodeId,
                            MemberState state,
                            long incarnation,
                            InetSocketAddress address,
                            long bootToken,
                            Map<String, String> labels) {
        /// Compact constructor ensures labels are an immutable copy.
        public MembershipUpdate {
            labels = Map.copyOf(labels);
        }

        /// An update carrying no process token (`0`) and no labels: never token-gated.
        public static MembershipUpdate membershipUpdate(NodeId nodeId,
                                                        MemberState state,
                                                        long incarnation,
                                                        InetSocketAddress address) {
            return new MembershipUpdate(nodeId, state, incarnation, address, 0L, Map.of());
        }

        /// An update carrying a process token and no labels.
        public static MembershipUpdate membershipUpdate(NodeId nodeId,
                                                        MemberState state,
                                                        long incarnation,
                                                        InetSocketAddress address,
                                                        long bootToken) {
            return new MembershipUpdate(nodeId, state, incarnation, address, bootToken, Map.of());
        }

        public static MembershipUpdate membershipUpdate(NodeId nodeId,
                                                        MemberState state,
                                                        long incarnation,
                                                        InetSocketAddress address,
                                                        long bootToken,
                                                        Map<String, String> labels) {
            return new MembershipUpdate(nodeId, state, incarnation, address, bootToken, labels);
        }
    }
}
