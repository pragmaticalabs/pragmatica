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
package org.pragmatica.consensus.net;

import java.util.Map;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Codec;


@Codec
public sealed interface NetworkMessage extends Message.Wired {
    /// Discovery/handshake traffic travels on the CONTROL lane by default; KV-sync
    /// records override to the KV lane.
    @Override
    default StreamType streamType() {
        return StreamType.CONTROL;
    }

    /// Hello - connection handshake, sent by both sides on channel activation.
    /// Carries the sender's cluster address and metadata labels (including the CORE/WORKER/SPOT
    /// `role` label) so receiving nodes can add dynamically provisioned nodes to their topology
    /// with full metadata, and the sender's per-process random `bootToken` (`0` = none), which the
    /// receiver admits through [BootTokens] before attaching the connection (terminal removal).
    /// `intendedPeer` is the identity the DIALER meant to reach (empty on the acceptor's response and
    /// where the dialer named nobody): an acceptor that is not that identity refuses the Hello before
    /// attaching, so a dial aimed at a peer whose address was recycled to another node never attaches
    /// to, or supersedes a healthy link of, whichever node now answers there.
    record Hello(NodeId sender,
                 NodeAddress address,
                 Map<String, String> labels,
                 long bootToken,
                 Option<NodeId> intendedPeer) implements NetworkMessage {}

    /// Explicit refusal of a Hello by the boot-token gate (terminal removal): the acceptor tells the
    /// dialing process that its NodeId belongs to a retired or different process, so the refused
    /// process can exit instead of retrying forever. Sent in place of the Hello response.
    record HelloRefused(NodeId sender, NodeId refused, String reason) implements NetworkMessage {}

    /// Topology discovery request - asks recipient to share their known nodes
    record DiscoverNodes(NodeId self) implements NetworkMessage {}

    /// Transport-internal liveness beacon (cluster-topology-overhaul spec, Wave 5). Sent by
    /// the QUIC transport on the CONTROL lane to every CONNECTED peer at the ClusterSync ping
    /// cadence, so an otherwise-idle healthy link carries periodic inbound traffic — the
    /// receipt evidence the CONNECTED-zombie liveness-TTL sweep measures. SWIM probes ride
    /// their own UDP socket (`port + swimPortOffset`), NOT the QUIC lanes, and ClusterSync
    /// ping/pong covers only leader<->follower links, so without this beacon an idle healthy
    /// follower<->follower link would be falsely evicted every TTL (the journaled ~10s
    /// CONNECTED→EVICTED→re-dial background cycle). Swallowed by the receiving transport
    /// right after it refreshes the per-peer inbound clock — never routed to consumers.
    record KeepAlive(NodeId sender) implements NetworkMessage {}

    /// Topology discovery response - list of known nodes sent to target
    record DiscoveredNodes(NodeId target, java.util.List<NodeInfo> nodes) implements NetworkMessage {}

    /// KV-Store snapshot request — sent by passive nodes to get current state.
    record KVSyncRequest(NodeId sender) implements NetworkMessage {
        @Override
        public StreamType streamType() {
            return StreamType.KV;
        }
    }

    /// KV-Store snapshot response — carries serialized KV-Store state.
    record KVSyncResponse(NodeId target, byte[] snapshot) implements NetworkMessage {
        @Override
        public StreamType streamType() {
            return StreamType.KV;
        }
    }

    /// Leader pre-vote question (#1748): "do you still see `leader` healthy?". Sent by a follower that
    /// lost its own view of the committed leader, BEFORE it elects, so one follower's broken link cannot
    /// depose a leader the rest of the electorate still reaches. `round` pairs the answer with the
    /// asking round.
    record LeaderPreVoteRequest(NodeId sender, NodeId leader, long round) implements NetworkMessage {}

    /// Answer to a [LeaderPreVoteRequest]. `leaderHealthy` is the responder's own view: it follows `leader`
    /// and still sees it reachable and fresh. A responder with no view of the leadership at all (booting,
    /// quorum lost, passive) does not answer.
    record LeaderPreVoteResponse(NodeId sender, NodeId leader, long round, boolean leaderHealthy) implements NetworkMessage {}
}
