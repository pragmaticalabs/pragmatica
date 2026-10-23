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
package org.pragmatica.http.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.handler.codec.http3.Http3ErrorCode;
import io.netty.handler.codec.http3.Http3Exception;
import io.netty.handler.codec.quic.QuicStreamChannel;

/// #2386: RFC 9114 §7.1 — a stream that ends inside a frame is a connection error of type `H3_FRAME_ERROR`. Netty's
/// frame codec does not check it: it passes on the bytes of a DATA frame cut short and drops the rest of a cut frame
/// without a word, so a request whose last frame was truncated reached the handler as if complete.
///
/// Installed ahead of the codec on each request stream, this guard follows the frame boundaries in the bytes it passes
/// on unchanged (the type and length varints of RFC 9000 §16; the payload is skipped). When the stream's input ends
/// inside a frame, it raises an `H3_FRAME_ERROR` [Http3Exception] down the stream's pipeline, before the input-shutdown
/// event, so the request handler marks the stream rejected before it could dispatch, and closes the connection with
/// that code.
final class Http3FrameBoundaryGuard extends ChannelInboundHandlerAdapter {
    private long payloadRemaining;
    private int varintRemaining;
    private long varint;
    private boolean readingLength;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof ByteBuf bytes) {
            track(bytes);
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt == ChannelInputShutdownEvent.INSTANCE && !atFrameBoundary()) {
            ctx.fireExceptionCaught(new Http3Exception(Http3ErrorCode.H3_FRAME_ERROR, "Request stream ended inside a frame"));
            if (ctx.channel() instanceof QuicStreamChannel stream) {
                stream.parent().close(true, Http3ErrorCode.H3_FRAME_ERROR.code(), Unpooled.EMPTY_BUFFER);
            }
        }
        ctx.fireUserEventTriggered(evt);
    }

    /// Package-private so a test can drive the boundary tracking without a QUIC stream.
    boolean atFrameBoundary() {
        return payloadRemaining == 0 && varintRemaining == 0 && !readingLength;
    }

    private void track(ByteBuf bytes) {
        var index = bytes.readerIndex();
        var end = bytes.writerIndex();

        while (index < end) {
            if (payloadRemaining > 0) {
                var skipped = (int) Math.min(payloadRemaining, end - index);

                index += skipped;
                payloadRemaining -= skipped;
            } else {
                varintByte(bytes.getUnsignedByte(index++));
            }
        }
    }

    /// The first byte of a varint gives its length in its top two bits (1, 2, 4 or 8 bytes); each frame header is two
    /// varints, the type and then the payload length.
    private void varintByte(short value) {
        if (varintRemaining == 0) {
            varintRemaining = (1 << (value >> 6)) - 1;
            varint = value & 0x3F;
        } else {
            varint = (varint << 8) | value;
            varintRemaining--;
        }
        if (varintRemaining == 0) {
            if (readingLength) {
                payloadRemaining = varint;
            }
            readingLength = !readingLength;
        }
    }
}
