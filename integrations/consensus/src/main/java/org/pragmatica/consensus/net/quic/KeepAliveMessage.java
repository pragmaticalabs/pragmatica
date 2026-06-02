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

package org.pragmatica.consensus.net.quic;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.serialization.Codec;

/// Application-layer QUIC keep-alive ping-pong for the consensus transport.
///
/// Carried on a dedicated long-lived [StreamType#KEEPALIVE] stream so it is isolated from
/// CONSENSUS backpressure (own flow control). The periodic loop in `QuicClusterNetwork` sends
/// a [Ping] per connected peer every tick; the peer replies with a matching [Pong]. Because
/// QUIC's `MAX_IDLE_TIMEOUT` is disabled (cluster links are persistent), a half-open link —
/// one side's read path dead while the transport still reads "active" — is otherwise invisible.
/// Unacked pings accumulate a per-link miss count; crossing the threshold evicts+reconnects on
/// BOTH ends, closing the leader-side blind spot for a fully-ready replacement whose metrics
/// pong never arrives.
///
/// The payload is exactly one signed long: the per-link monotonic sequence number. Sender
/// identity is taken from the connection, never from the wire.
@Codec
public sealed interface KeepAliveMessage extends ProtocolMessage {
    /// Sentinel sender. The keep-alive payload is exactly one signed long (`seq`) — the sender
    /// is taken from the QUIC connection on receipt, never from the wire. `ProtocolMessage`
    /// mandates a `sender()` accessor, so this returns a fixed non-routable sentinel that is
    /// never serialized (codecs encode only record components) and never consulted: the
    /// keep-alive ping/pong handlers in `QuicClusterNetwork` route purely by the connection's
    /// peer id.
    NodeId KEEPALIVE_SENDER = new NodeId("keepalive");

    @Override
    default NodeId sender() {
        return KEEPALIVE_SENDER;
    }

    /// Sequence of the very first Ping the client sends on a freshly-opened KEEPALIVE stream.
    /// Its sole purpose is to let the server identify the stream by first-frame type — it is
    /// sent directly on the stream and intentionally does NOT flow through the per-link sequence
    /// source of truth (`PeerState.nextKeepAliveSeq`), so it is not sequence-tracked.
    long IDENTIFICATION_SEQ = 1L;

    record Ping(long seq) implements KeepAliveMessage {}

    record Pong(long seq) implements KeepAliveMessage {}
}
