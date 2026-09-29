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

import org.pragmatica.consensus.net.quic.QuicClusterServer.MessageReceiver;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.serialization.Deserializer;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;

import static org.pragmatica.lang.Unit.unit;


/// #1578 — the one routing of a data-lane stream opened by the OTHER end of a connection, shared by
/// the acceptor ([QuicClusterServer]) and the dialer ([QuicClusterClient]).
///
/// Before #1578 only the acceptor had it. The acceptor lazily opens a lane that is missing on its
/// side (a write racing the dialer's preamble frames), but the dialer installed no handler for
/// streams the acceptor opens, so every message written on such a stand-in lane was read by nobody
/// while the writer saw success. Both ends now attribute a peer-opened stream by its 1-byte preamble
/// and read it with the same [QuicLaneDataHandler]; which stream the lane keeps is decided by
/// [QuicPeerConnection#registerStream], identically at both ends.
record PeerOpenedLaneRouter(Deserializer deserializer,
                            QuicTransportMetrics quicMetrics,
                            MessageReceiver messageReceiver,
                            Logger log) {
    /// Parent-QuicChannel attribute carrying the per-peer connection. Stamped once the Hello
    /// handshake has verified the peer (acceptor: on the Hello; dialer: on the Hello response) and
    /// read by each peer-opened data-lane stream to find the connection it belongs to.
    static final AttributeKey<QuicPeerConnection> PEER_CONNECTION = AttributeKey.valueOf("aether.quic.peerConnection");

    /// Decode the 1-byte lane preamble that opens every stream. Empty for a missing or unknown index.
    static Option<StreamType> preambleLane(ByteBuf buf) {
        return buf.readableBytes() < 1
               ? Option.none()
               : StreamType.fromIndex(buf.readByte());
    }

    /// Attach a peer-opened stream to the verified connection stamped on its parent channel, or close
    /// it when the handshake has not stamped one — the handshake-first ordering the transport relies
    /// on to never deliver a message under an unverified identity.
    Unit attach(ChannelHandlerContext ctx, ChannelHandler preambleHandler, StreamType lane) {
        var parent = (QuicChannel) ctx.channel().parent();

        return Option.option(parent.attr(PEER_CONNECTION).get()).fold(() -> refuseUnverified(ctx, lane),
                                                                      peerConnection -> attachTo(ctx,
                                                                                                 preambleHandler,
                                                                                                 lane,
                                                                                                 peerConnection));
    }

    private Unit attachTo(ChannelHandlerContext ctx,
                          ChannelHandler preambleHandler,
                          StreamType lane,
                          QuicPeerConnection peerConnection) {
        var _ = peerConnection.registerStream(lane, (QuicStreamChannel) ctx.channel());

        ctx.pipeline()
           .replace(preambleHandler,
                    "data-handler",
                    new QuicLaneDataHandler(peerConnection.peerId(),
                                            lane,
                                            deserializer,
                                            quicMetrics,
                                            messageReceiver,
                                            log));
        log.debug("Attached peer-opened {} lane stream from peer {}", lane, peerConnection.peerId());

        return unit();
    }

    private Unit refuseUnverified(ChannelHandlerContext ctx, StreamType lane) {
        log.warn("No verified peer connection on parent channel for {} lane from {} — closing (handshake-first ordering violated)",
                 lane,
                 ctx.channel().remoteAddress());
        ctx.close();

        return unit();
    }
}
