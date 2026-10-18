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
import org.pragmatica.lang.Option;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// HTTP/1.1 side of [BodyRateLimit] (F6). Sits between the codec and the aggregator, where a request still arrives as
/// its head followed by body chunks: a guard starts at the head, counts each chunk's bytes, and stops at the last
/// chunk. A body arriving below the minimum rate closes the connection; the request never reaches the handler,
/// because the aggregator never completes it. Per channel, not sharable.
final class BodyRateGuardHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(BodyRateGuardHandler.class);

    private final BodyRateLimit limit;
    private Option<BodyRateGuard> guard = none();

    BodyRateGuardHandler(BodyRateLimit limit) {
        this.limit = limit;
    }

    @Contract
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpRequest) {
            stopGuard();
            guard = some(BodyRateGuard.start(ctx.executor(), limit, () -> closeTooSlow(ctx)));
        }

        if (msg instanceof HttpContent content) {
            guard.onPresent(active -> active.onBytes(content.content().readableBytes()));
        }

        if (msg instanceof LastHttpContent) {
            stopGuard();
        }

        ctx.fireChannelRead(msg);
    }

    @Contract
    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        stopGuard();
    }

    private void stopGuard() {
        guard.onPresent(BodyRateGuard::stop);
        guard = none();
    }

    private void closeTooSlow(ChannelHandlerContext ctx) {
        log.debug("Closing HTTP connection {}: request body below {} B/s",
                  ctx.channel().remoteAddress(),
                  limit.bytesPerSecond());
        guard = none();
        ctx.close();
    }
}
