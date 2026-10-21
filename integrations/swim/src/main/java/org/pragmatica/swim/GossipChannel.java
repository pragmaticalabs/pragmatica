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
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;


/// An application-level payload channel that rides SWIM's probe cadence in datagrams of its own.
///
/// SWIM carries membership; it knows nothing of what an application wants to tell a peer. A channel is the
/// extension point: once per probe round [SwimProtocol] asks every registered channel what it has to say and
/// sends the answer to ONE randomly chosen ALIVE member, in an [SwimMessage.AppGossip] datagram. It is NOT
/// piggybacked on Ping or Ack: those carry membership updates in a datagram budget that is already tight, and
/// an application payload must never displace one. A channel is identified by an `int` the application owns.
///
/// Both directions are bounded by the protocol, not left to the channel's good manners: what a channel
/// produces is dropped when it exceeds the budget it was offered, and what arrives is dropped beyond a fixed
/// rate. Delivery is best effort -- a datagram can be lost, and a payload goes to one peer per round, never to
/// all of them.
public interface GossipChannel {
    /// What this channel sends this round, at most `budgetBytes` long, or nothing. Called from the probe tick,
    /// so it must be quick and must not block. A payload longer than the budget is dropped and counted.
    Option<byte[]> outgoing(int budgetBytes);

    /// A payload from `from`, a known member, arrived on this channel. Called on the transport's thread, so it
    /// must be quick, must not block and must not throw.
    @Contract
    void incoming(NodeId from, byte[] payload);
}
