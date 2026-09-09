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
import org.pragmatica.consensus.net.quic.QuicClusterServer.MessageReceiver;
import org.pragmatica.lang.Contract;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.UnknownTypeTagException;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;


/// Handles ongoing data messages on a single lane stream after the stream-open preamble
/// (and, on the CONTROL lane, the Hello handshake) completes.
///
/// Deserializes incoming bytes and routes them via the [MessageReceiver] callback. Also
/// monitors channel writability to drain backpressure queues. All error attribution is
/// lane-scoped so a stalled stream names its lane in the logs.
///
/// Shared by both the dialer ([QuicClusterClient]) and the acceptor ([QuicClusterServer]):
/// the two formerly byte-identical inner `DataHandler` classes were de-duplicated here.
final class QuicLaneDataHandler extends SimpleChannelInboundHandler<ByteBuf> {
    private final NodeId peerId;
    private final StreamType lane;
    private final Deserializer deserializer;
    private final QuicTransportMetrics quicMetrics;
    private final MessageReceiver messageReceiver;
    private final Logger log;
    private final Runnable onWritable;

    QuicLaneDataHandler(NodeId peerId,
                        StreamType lane,
                        Deserializer deserializer,
                        QuicTransportMetrics quicMetrics,
                        MessageReceiver messageReceiver,
                        Logger log) {
        this(peerId,
             lane,
             deserializer,
             quicMetrics,
             messageReceiver,
             log,
             () -> {});
    }

    QuicLaneDataHandler(NodeId peerId,
                        StreamType lane,
                        Deserializer deserializer,
                        QuicTransportMetrics quicMetrics,
                        MessageReceiver messageReceiver,
                        Logger log,
                        Runnable onWritable) {
        this.peerId = peerId;
        this.lane = lane;
        this.deserializer = deserializer;
        this.quicMetrics = quicMetrics;
        this.messageReceiver = messageReceiver;
        this.log = log;
        this.onWritable = onWritable;
    }

    @Override
    @Contract
    @SuppressWarnings("JBCT-PAT-01")  // Adapter boundary: catch deserialization errors from external input
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf buf) {
        var bytes = new byte[buf.readableBytes()];

        buf.readBytes(bytes);
        // #726: PAYLOAD bytes at the lane boundary — after the pipeline has already stripped
        // QUIC/TLS overhead, before deserialization. Not a wire-byte or bandwidth figure.
        quicMetrics.onBytesReceived(bytes.length);
        try {
            var message = deserializer.decode(bytes);

            messageReceiver.onMessage(peerId, message);
        } catch (UnknownTypeTagException e) {
            // #964: split out of the generic arm below. Dropping a message whose TYPE this node does
            // not have is correct — an old node is not expected to handle a new message type — but the
            // drop used to be indistinguishable from a corrupt frame, logged with a stack trace under
            // the same sentence, and counted nowhere. An operator saw "failed to deserialize" and had
            // no way to reach "this cluster is running mixed codec versions". WARN and not ERROR
            // because during a rolling upgrade this is expected and self-resolving; the counter is
            // what carries the volume.
            quicMetrics.onUnknownTypeTagDrop();
            log.warn("Dropped a message from peer {} on lane {}: wire tag {} names no codec on this node."
                    + " The peer is running a codec version this node does not have — finish the rolling"
                    + " upgrade, or check that both nodes ship the same blueprint. Counter:"
                    + " quic_unknown_type_tag_drops_total.",
                     peerId,
                     lane,
                     e.tag());
        } catch (Exception e) {
            log.error("Failed to deserialize message from peer {} on lane {}", peerId, lane, e);
        }
    }

    @Override
    @Contract
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        if (ctx.channel().isWritable()) {
            onWritable.run();
        }

        super.channelWritabilityChanged(ctx);
    }

    @Override
    @Contract
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Error processing message from peer {} on lane {}", peerId, lane, cause);
        ctx.close();
    }
}
