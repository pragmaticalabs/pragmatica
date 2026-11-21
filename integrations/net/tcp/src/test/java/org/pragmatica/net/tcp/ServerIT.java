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

package org.pragmatica.net.tcp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;
import static org.pragmatica.net.tcp.ServerConfig.serverConfig;

/// Every test binds port 0 and reads the port the OS assigned back from [Server#port], so no number is chosen before
/// the bind: the class is safe under the module-parallel `-T 1C` build and needs no environment gate. Every wait is
/// bounded by [#TIMEOUT].
class ServerIT {
    private static final TimeSpan TIMEOUT = timeSpan(10).seconds();

    @Test
    void server_starts_and_accepts_connections() throws InterruptedException {
        var receivedMessage = new AtomicReference<String>();
        var messageLatch = new CountDownLatch(1);

        // Create server with simple handler
        Server.server("test-server", 0, () -> List.of(
            new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    if (msg instanceof ByteBuf buf) {
                        receivedMessage.set(buf.toString(StandardCharsets.UTF_8));
                        buf.release();
                        messageLatch.countDown();
                        ctx.close();
                    }
                }
            }
        )).await(TIMEOUT)
            .onFailure(cause -> fail(cause.message()))
            .onSuccess(server -> {
                try {
                    // Connect to the port the server reports and send a message
                    server.connectTo(nodeAddress("localhost", server.port()).unwrap())
                        .await(TIMEOUT)
                        .onFailure(cause -> fail(cause.message()))
                        .onSuccess(channel -> {
                            channel.writeAndFlush(Unpooled.copiedBuffer("Hello", StandardCharsets.UTF_8));
                        });

                    // Wait for message
                    try {
                        assertThat(messageLatch.await(5, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        fail("Interrupted while waiting for message");
                    }
                    assertThat(receivedMessage.get()).isEqualTo("Hello");
                } finally {
                    server.stop(() -> Promise.success(Unit.unit())).await(TIMEOUT);
                }
            });
    }

    @Test
    void server_with_tls_starts_successfully() {
        var config = serverConfig("tls-server", 0)
            .withTls(TlsConfig.selfSigned());

        Server.server(config, () -> List.of(
            new ChannelInboundHandlerAdapter()
        )).await(TIMEOUT)
            .onFailure(cause -> fail(cause.message()))
            .onSuccess(server -> {
                try {
                    assertThat(server.name()).isEqualTo("tls-server");
                    // Port 0 was asked for: the server must report the port it is actually bound to.
                    assertThat(server.port()).isPositive();
                    server.connectTo(nodeAddress("localhost", server.port()).unwrap())
                        .await(TIMEOUT)
                        .onFailure(cause -> fail("No connection to the reported port " + server.port() + ": " + cause.message()))
                        .onSuccess(channel -> channel.close().syncUninterruptibly());
                } finally {
                    server.stop(() -> Promise.success(Unit.unit())).await(TIMEOUT);
                }
            });
    }

    @Test
    void server_stop_executes_intermediate_operation() {
        var intermediateExecuted = new AtomicReference<>(false);

        Server.server("stop-test", 0, () -> List.of(
            new ChannelInboundHandlerAdapter()
        )).await(TIMEOUT)
            .onFailure(cause -> fail(cause.message()))
            .onSuccess(server -> {
                server.stop(() -> {
                    intermediateExecuted.set(true);
                    return Promise.success(Unit.unit());
                }).await(TIMEOUT)
                    .onFailure(cause -> fail(cause.message()))
                    .onSuccessRun(() -> {
                        assertThat(intermediateExecuted.get()).isTrue();
                    });
            });
    }
}
