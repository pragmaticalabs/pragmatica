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

package org.pragmatica.net.smtp;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.lang.Promise;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioSocketChannel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.net.smtp.SmtpConfig.smtpConfig;
import static org.pragmatica.net.smtp.SmtpMessage.smtpMessage;


/// The server greeting can reach the response handler BEFORE the connect future's listener has
/// run, so nothing that assigns the channel from that listener is in time.
///
/// Netty defers a listener added after its future already completed to a QUEUED event-loop task,
/// while an inbound read is delivered from `processSelectedKeys()` — which runs before
/// `runAllTasks()` in the same loop iteration. Measured on the rc4 tip `e4c697c12` with a probe
/// built to production's own bootstrap shape: the connect listener had not yet run at the first
/// read in 3 of 3000 connections, and 3000 sequential sends through the real `SmtpClient`
/// produced 5 failures whose stack is `sendCommand` <- `handleGreeting` <- `handleResponse`.
///
/// The order that makes the session safe is the pipeline's own: the channel is assigned while the
/// pipeline is being built, on the event loop, before the response handler that delivers the
/// greeting is in it. Every other test in this package calls `setChannel` by hand first, which is
/// why none of them can see this.
class SmtpChannelInitializerOrderTest {
    @Test
    void greetingIsAnsweredEvenWhenItArrivesBeforeTheConnectListenerRuns() {
        var group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());

        try {
            var config = smtpConfig("localhost", 25).withTlsMode(SmtpTlsMode.NONE);
            var message = smtpMessage("a@b.com", List.of("c@d.com"), "Sub", "Body");
            var session = new SmtpSession(config, message, Promise.promise(), none());
            var channel = new NioSocketChannel();
            var written = new CopyOnWriteArrayList<String>();

            group.register(channel).syncUninterruptibly();
            channel.pipeline().addFirst("capture", captureOutbound(written));
            // Exactly what the bootstrap invokes, and the only thing that has run at this point:
            // the connect listener has NOT, which is the state the race leaves the session in.
            SmtpChannelInitializer.forSession(session, config, none()).initChannel(channel);

            channel.eventLoop()
                   .submit(() -> session.handleResponse(220, "probe ESMTP"))
                   .syncUninterruptibly();

            assertThat(written).as("the greeting must be answered on the wire, not dropped")
                               .hasSize(1);
            assertThat(written.getFirst()).startsWith("EHLO ");
        } finally {
            group.shutdownGracefully();
        }
    }

    private static ChannelOutboundHandlerAdapter captureOutbound(List<String> written) {
        return new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                if (msg instanceof ByteBuf buf) {
                    written.add(buf.toString(StandardCharsets.US_ASCII));
                }

                promise.setSuccess();
            }
        };
    }
}
