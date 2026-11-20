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

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;
import static org.pragmatica.net.tcp.ServerConfig.serverConfig;

/// Every test binds an ephemeral port (asked of the OS, never a fixed number), so the class is safe under the
/// module-parallel `-T 1C` build and needs no environment gate.
class ServerIT {

    @Test
    void server_starts_and_accepts_connections() throws InterruptedException {
        var receivedMessage = new AtomicReference<String>();
        var messageLatch = new CountDownLatch(1);
        var port = freeTcpPort();

        // Create server with simple handler
        Server.server("test-server", port, () -> List.of(
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
        )).await()
            .onFailure(cause -> fail(cause.message()))
            .onSuccess(server -> {
                try {
                    // Connect and send message
                    server.connectTo(nodeAddress("localhost", port).unwrap())
                        .await()
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
                    server.stop(() -> Promise.success(Unit.unit())).await();
                }
            });
    }

    @Test
    void server_with_tls_starts_successfully() {
        var port = freeTcpPort();
        var config = serverConfig("tls-server", port)
            .withTls(TlsConfig.selfSigned());

        Server.server(config, () -> List.of(
            new ChannelInboundHandlerAdapter()
        )).await()
            .onFailure(cause -> fail(cause.message()))
            .onSuccess(server -> {
                assertThat(server.name()).isEqualTo("tls-server");
                assertThat(server.port()).isEqualTo(port);
                server.stop(() -> Promise.success(Unit.unit())).await();
            });
    }

    @Test
    void server_stop_executes_intermediate_operation() {
        var intermediateExecuted = new AtomicReference<>(false);
        var port = freeTcpPort();

        Server.server("stop-test", port, () -> List.of(
            new ChannelInboundHandlerAdapter()
        )).await()
            .onFailure(cause -> fail(cause.message()))
            .onSuccess(server -> {
                server.stop(() -> {
                    intermediateExecuted.set(true);
                    return Promise.success(Unit.unit());
                }).await()
                    .onFailure(cause -> fail(cause.message()))
                    .onSuccessRun(() -> {
                        assertThat(intermediateExecuted.get()).isTrue();
                    });
            });
    }

    private static int freeTcpPort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
