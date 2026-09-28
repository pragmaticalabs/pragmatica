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

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.CoreError;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.ServerConfig.serverConfig;

/// #1610: `stop()` resolves only once the server has actually stopped — the event loop groups have
/// TERMINATED and the TCP and UDP ports are released — not merely once their shutdown was requested.
/// Ports come from the OS (bound to 0, then released) so the tests do not collide with other modules
/// running concurrently (#939).
class ServerStopTest {
    /// Channel-close bound + group-termination bound, plus slack.
    private static final long STOP_BOUND_MS = Server.CHANNEL_CLOSE_TIMEOUT_MS + Server.SHUTDOWN_TIMEOUT_MS + 1_000L + 3_000L;


    @Test
    void stop_resolvesOnlyAfterTheEventLoopGroupsTerminate() throws InterruptedException {
        var server = start(serverConfig("stop-order", freeTcpPort()));
        var release = new CountDownLatch(1);
        var blocking = new CountDownLatch(1);

        // A task still running on a worker loop holds that loop's termination back until released.
        server.workerGroup()
              .next()
              .execute(() -> hold(blocking, release));
        assertThat(blocking.await(5, TimeUnit.SECONDS)).as("the worker task is running").isTrue();

        var stopped = server.stop(() -> Promise.success(Unit.unit()));

        Thread.sleep(300);
        assertThat(stopped.isResolved()).as("stop() must not resolve while a worker loop has not terminated")
                                        .isFalse();

        release.countDown();

        assertThat(stopped.await(timeSpan(10).seconds()).isSuccess()).as("stop() resolves once the loops terminate")
                                                                     .isTrue();
        assertThat(server.bossGroup().isTerminated()).as("boss group terminated when stop() resolved").isTrue();
        assertThat(server.workerGroup().isTerminated()).as("worker group terminated when stop() resolved").isTrue();
    }

    @Test
    void stop_releasesTheTcpAndUdpPorts_soTheSamePortsRebindRightAfter() {
        var port = freeTcpAndUdpPort();
        var config = serverConfig("stop-rebind", port).withUdpPort(port);
        var first = start(config);

        assertThat(first.udpChannel().isPresent()).as("control: the first server bound UDP").isTrue();
        assertThat(first.stop(() -> Promise.success(Unit.unit())).await(timeSpan(10).seconds()).isSuccess()).isTrue();

        var second = start(config);

        try {
            assertThat(second.udpChannel().isPresent()).as("the UDP port is free the moment stop() resolved").isTrue();
        } finally {
            second.stop(() -> Promise.success(Unit.unit())).await(timeSpan(10).seconds());
        }
    }

    /// #1614: a wedged boss loop never completes the server-channel close. The close is bounded, so the group
    /// shutdown is still requested and `stop()` fails typed within the bound instead of hanging.
    @Test
    void stop_withAWedgedBossLoop_asksBothGroupsToShutDown_andFailsWithinTheBound() throws InterruptedException {
        var server = start(serverConfig("stop-wedged-boss", freeTcpPort()));

        assertStopBoundedWhileWedged(server, server.bossGroup().next());
    }

    /// #1614: the same for the loop the UDP channel is registered on — a hang rc4 did not have, because rc4 never
    /// waited for the UDP close.
    @Test
    void stop_withAWedgedUdpLoop_asksBothGroupsToShutDown_andFailsWithinTheBound() throws InterruptedException {
        var port = freeTcpAndUdpPort();
        var server = start(serverConfig("stop-wedged-udp", port).withUdpPort(port));

        assertThat(server.udpChannel().isPresent()).as("control: UDP bound").isTrue();
        assertStopBoundedWhileWedged(server, server.udpChannel().unwrap().eventLoop());
    }

    private static void assertStopBoundedWhileWedged(Server server, Executor loop) throws InterruptedException {
        var release = new CountDownLatch(1);
        var blocking = new CountDownLatch(1);

        loop.execute(() -> hold(blocking, release));
        assertThat(blocking.await(5, TimeUnit.SECONDS)).as("the loop is wedged").isTrue();

        try {
            var stopped = server.stop(() -> Promise.success(Unit.unit()));
            var outcome = stopped.await(timeSpan(STOP_BOUND_MS).millis());

            assertThat(stopped.isResolved()).as("stop() resolves within %d ms although a loop is wedged", STOP_BOUND_MS)
                                            .isTrue();
            assertThat(outcome.isFailure()).as("a wedged loop is reported, not hidden").isTrue();
            outcome.onFailure(cause -> assertThat(cause).isInstanceOf(CoreError.Timeout.class));
            assertThat(server.bossGroup().isShuttingDown()).as("boss group asked to shut down").isTrue();
            assertThat(server.workerGroup().isShuttingDown()).as("worker group asked to shut down").isTrue();
        } finally {
            release.countDown();
        }
    }

    private static Server start(ServerConfig config) {
        return Server.server(config, ServerStopTest::freshHandlers, ServerStopTest::freshHandlers)
                     .await(timeSpan(10).seconds())
                     .fold(cause -> {
                               throw new AssertionError("server did not start: " + cause.message());
                           },
                           server -> server);
    }

    /// A fresh handler per pipeline: `ChannelInboundHandlerAdapter` is not `@Sharable`, and one instance added to
    /// a second pipeline closes that channel.
    private static List<ChannelHandler> freshHandlers() {
        return List.of(new ChannelInboundHandlerAdapter());
    }

    private static void hold(CountDownLatch blocking, CountDownLatch release) {
        blocking.countDown();
        try {
            release.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int freeTcpPort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /// A port free for both TCP and UDP at the moment of the check.
    private static int freeTcpAndUdpPort() {
        for (int attempt = 0; attempt < 20; attempt++) {
            var candidate = freeTcpPort();

            try (var udp = new DatagramSocket(candidate)) {
                return candidate;
            } catch (IOException e) {
                // taken for UDP; try another
            }
        }
        throw new AssertionError("no port free for both TCP and UDP");
    }
}
