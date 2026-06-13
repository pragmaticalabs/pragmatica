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

import java.util.function.Consumer;

import io.netty.channel.WriteBufferWaterMark;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.StreamType;

import static org.pragmatica.lang.Option.option;

/// Wraps a QUIC connection to a single peer with named streams.
///
/// Long-lived streams (consensus, KV store) are opened once and reused.
/// Short-lived streams (HTTP forward, DHT relay) are opened per exchange.
public final class QuicPeerConnection {
    /// Lazily (re)opens a missing long-lived lane stream on this live connection. Installed by the
    /// transport endpoint that built the connection (dialer/acceptor): it creates a fresh
    /// bidirectional stream, writes the lane preamble, installs the framing + data handler, registers
    /// the stream via [#registerStream], and calls back with the registered stream (or empty on
    /// failure). This restores the dial-path "all lanes present" invariant on the ACCEPTOR path,
    /// where [#registerPeerConnection] publishes the peer CONNECTED with ONLY the CONTROL lane and
    /// the data lanes register asynchronously as the dialer's preamble frames arrive — a write that
    /// races that window otherwise fails "No stream available" on a perfectly healthy link.
    @FunctionalInterface
    public interface LaneOpener {
        /// Open `lane` on the connection and invoke `onResult` with the registered stream (some) or
        /// empty when the open failed / the connection is dead. Asynchronous: `onResult` runs on the
        /// connection's event loop once the stream is created. No-op opener (the default) reports empty.
        @Contract
        void open(StreamType lane, Consumer<Option<QuicStreamChannel>> onResult);

        /// No-op opener: reports empty for every lane. Used for connections built by unit fixtures
        /// that never drive real streams, so lazy-open degrades to the BACKSTOP eviction path.
        static LaneOpener noop() {
            return (lane, onResult) -> onResult.accept(Option.empty());
        }
    }

    private final NodeId peerId;
    private final QuicChannel connection;
    private final QuicStreamChannel[] longLivedStreams;
    private final int consensusWatermarkLowBytes;
    private final int consensusWatermarkHighBytes;
    private volatile LaneOpener laneOpener = LaneOpener.noop();

    private QuicPeerConnection(NodeId peerId,
                               QuicChannel connection,
                               int consensusWatermarkLowBytes,
                               int consensusWatermarkHighBytes) {
        this.peerId = peerId;
        this.connection = connection;
        this.longLivedStreams = new QuicStreamChannel[StreamType.values().length];
        this.consensusWatermarkLowBytes = consensusWatermarkLowBytes;
        this.consensusWatermarkHighBytes = consensusWatermarkHighBytes;
    }

    /// Create a new peer connection wrapping a QUIC channel, using the default CONSENSUS
    /// stream write-buffer watermarks ([QuicTransportTuning] defaults).
    public static QuicPeerConnection quicPeerConnection(NodeId peerId, QuicChannel connection) {
        return new QuicPeerConnection(peerId,
                                      connection,
                                      QuicTransportTuning.DEFAULT_WATERMARK_LOW_BYTES,
                                      QuicTransportTuning.DEFAULT_WATERMARK_HIGH_BYTES);
    }

    /// Create a new peer connection wrapping a QUIC channel with explicit CONSENSUS stream
    /// write-buffer watermarks. Used when the enclosing transport carries non-default tuning.
    public static QuicPeerConnection quicPeerConnection(NodeId peerId,
                                                        QuicChannel connection,
                                                        int consensusWatermarkLowBytes,
                                                        int consensusWatermarkHighBytes) {
        return new QuicPeerConnection(peerId, connection, consensusWatermarkLowBytes, consensusWatermarkHighBytes);
    }

    public NodeId peerId() {
        return peerId;
    }

    public QuicChannel connection() {
        return connection;
    }

    /// Install the lazy lane-opener. Called by the transport endpoint that built this connection
    /// (dialer/acceptor) right after construction, so a write that finds a missing data lane can
    /// (re)open it on the live channel instead of failing. A `null` opener resets to the no-op.
    @Contract
    public void laneOpener(LaneOpener opener) {
        this.laneOpener = opener == null ? LaneOpener.noop() : opener;
    }

    /// Lazily (re)open a missing long-lived `lane` stream on this live connection, invoking
    /// `onResult` with the registered stream (some) or empty on failure. Delegates to the installed
    /// [LaneOpener]; the default no-op opener reports empty (no live channel to open against).
    @Contract
    public void openLane(StreamType lane, Consumer<Option<QuicStreamChannel>> onResult) {
        laneOpener.open(lane, onResult);
    }

    /// Get a long-lived stream, if already opened.
    public Option<QuicStreamChannel> stream(StreamType type) {
        return option(longLivedStreams[type.streamIndex()]);
    }

    /// Register a long-lived stream for the given type.
    ///
    /// For [StreamType#CONSENSUS] the stream's write-buffer high-watermark is raised
    /// (default 256KB low / 1MB high — see [QuicTransportTuning]) so that bursts of
    /// consensus commands fit in the write buffer before `isWritable()` flips false. This
    /// is the first line of defence against the consensus-send backpressure defect; the
    /// async retry wrap in `QuicClusterNetwork.writeIfWritable` is the safety net for the
    /// pathological case. `WriteBufferWaterMark` is a Netty `ChannelConfig` facility and is
    /// supported by the incubator `QuicStreamChannel`'s config.
    @Contract
    public void registerStream(StreamType type, QuicStreamChannel channel) {
        if (type == StreamType.CONSENSUS) {
            channel.config()
                   .setWriteBufferWaterMark(new WriteBufferWaterMark(consensusWatermarkLowBytes, consensusWatermarkHighBytes));
        }
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

    @Contract
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
