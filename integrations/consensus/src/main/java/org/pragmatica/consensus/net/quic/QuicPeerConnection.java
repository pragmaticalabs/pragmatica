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

import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Option.option;

/// Wraps a QUIC connection to a single peer with named streams.
///
/// Long-lived streams (consensus, KV store) are opened once and reused.
/// Short-lived streams (HTTP forward, DHT relay) are opened per exchange.
public final class QuicPeerConnection {
    private final NodeId peerId;
    private final QuicChannel connection;
    private final QuicStreamChannel[] longLivedStreams;

    private QuicPeerConnection(NodeId peerId, QuicChannel connection) {
        this.peerId = peerId;
        this.connection = connection;
        this.longLivedStreams = new QuicStreamChannel[StreamType.values().length];
    }

    /// Create a new peer connection wrapping a QUIC channel.
    public static QuicPeerConnection quicPeerConnection(NodeId peerId, QuicChannel connection) {
        return new QuicPeerConnection(peerId, connection);
    }

    public NodeId peerId() {
        return peerId;
    }

    public QuicChannel connection() {
        return connection;
    }

    /// Get a long-lived stream, if already opened.
    public Option<QuicStreamChannel> stream(StreamType type) {
        return option(longLivedStreams[type.streamIndex()]);
    }

    /// Register a long-lived stream for the given type.
    public void registerStream(StreamType type, QuicStreamChannel channel) {
        longLivedStreams[type.streamIndex()] = channel;
    }

    /// Check if the underlying QUIC connection is active.
    public boolean isActive() {
        return connection.isActive();
    }

    /// Close all streams and the underlying connection.
    public Promise<Unit> close() {
        return Promise.lift(QuicTransportError.ConnectionCloseFailed::new, this::closeSync);
    }

    @SuppressWarnings("JBCT-UTIL-01")
    private void closeSync() throws Exception {
        closeLongLivedStreams();
        connection.close().sync();
    }

    private void closeLongLivedStreams() {
        for (int i = 0; i < longLivedStreams.length; i++) {
            var stream = longLivedStreams[i];
            if (stream != null && stream.isActive()) {
                stream.close();
            }
            longLivedStreams[i] = null;
        }
    }
}
