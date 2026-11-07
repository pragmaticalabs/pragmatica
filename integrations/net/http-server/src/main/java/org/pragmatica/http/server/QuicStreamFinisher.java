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

import org.pragmatica.lang.Contract;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.handler.codec.quic.DefaultQuicStreamFrame;


/// #2746: ends a QUIC stream with its last bytes, never after them.
///
/// quiche (netty-codec-native-quic 4.2.18, unchanged in 4.2.20) does not retransmit a FIN-only STREAM frame (length 0)
/// when its loss is detected in the same batch as an earlier data loss on the same stream. The receiver then holds every
/// byte but never sees the end of the stream, and the exchange stalls until the idle timeout. `shutdownOutput()` after
/// the last write sends exactly such a frame whenever the writes are made outside the QUIC channel's read handling
/// (Netty defers sending only there and while it resumes writable streams): each write is then handed to quiche and
/// packetised at once, so the `FIN` that follows travels alone. A STREAM frame that carries data and `FIN` together is
/// retransmitted with its `FIN`.
///
/// This handler, installed ahead of the HTTP/3 frame codec, turns the bytes the codec produces for the write made
/// through [#writeAndFinish] into one [DefaultQuicStreamFrame] with `FIN` set, so the stream ends with them and no
/// FIN-only frame exists. That write is recognised by its promise, which the codec and the frame validators hand on
/// unchanged with the frame's bytes (also when the codec queues a write until the QPACK encoder stream is up).
///
/// A twin lives in http-client (`NettyHttpOperations` request writer): the two modules share no QUIC module, and
/// http-client does not depend on the tcp module.
///
/// Upstream quiche defect, worked around here; not reported upstream yet.
@Sharable
final class QuicStreamFinisher extends ChannelOutboundHandlerAdapter {
    static final QuicStreamFinisher INSTANCE = new QuicStreamFinisher();

    private QuicStreamFinisher() {}

    /// Writes and flushes `frame` from `ctx`; the stream ends with the bytes it becomes. The stream's pipeline must
    /// carry [#INSTANCE] between `ctx` and the channel.
    static ChannelFuture writeAndFinish(ChannelHandlerContext ctx, Object frame) {
        return ctx.writeAndFlush(frame, new FinishingPromise(ctx));
    }

    @Contract
    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (promise instanceof FinishingPromise && msg instanceof ByteBuf bytes) {
            ctx.write(new DefaultQuicStreamFrame(bytes, true), promise);
        } else {
            ctx.write(msg, promise);
        }
    }

    /// Marks the write whose bytes end the stream.
    private static final class FinishingPromise extends DefaultChannelPromise {
        FinishingPromise(ChannelHandlerContext ctx) {
            super(ctx.channel(), ctx.executor());
        }
    }
}
