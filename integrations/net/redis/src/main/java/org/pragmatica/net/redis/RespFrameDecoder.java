/*
 *  Copyright (c) 2022-2025 Sergiy Yevtushenko.
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
package org.pragmatica.net.redis;

import java.util.List;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;


/// Netty adapter over [RespCodec#decode]: emits one [RespValue] per complete reply, however the bytes
/// were split across reads. A protocol violation is not thrown: the cause is fired as a user event
/// (the handler that owns the correlation queue fails the pipeline with it), the unread bytes are
/// dropped because the stream position is lost, and the channel is closed AFTER the values decoded before
/// the violation were passed on (the close is queued on the event loop behind the current read), so a reply
/// that arrived complete in the same read as a malformed frame is still delivered.
final class RespFrameDecoder extends ByteToMessageDecoder {
    @Override
    @Contract
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        var more = true;

        while (more && in.isReadable()) {
            var decoded = RespCodec.decode(in);

            decoded.onFailure(cause -> abandon(ctx, in, cause)).onSuccess(value -> value.onPresent(out::add));
            more = decoded.fold(_ -> false, value -> value.isPresent());
        }
    }

    @Contract
    private static void abandon(ChannelHandlerContext ctx, ByteBuf in, Cause cause) {
        in.skipBytes(in.readableBytes());
        ctx.fireUserEventTriggered(cause);
        ctx.executor().execute(ctx::close);
    }
}
