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

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.handler.codec.quic.QuicSslContext;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.CoreError;
import org.pragmatica.net.tcp.QuicSslContextFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1612: `stop()` of both HTTP servers is a dependent, bounded chain that reports its first failure, typed.
///
/// What each test proves at the pre-#1612 base, stated because two of them are regression guards rather than
/// reproductions: the ticket's premise ("stop() resolves before the groups terminate") was false — the old code
/// already listened on the termination future — so the ordering and rebind tests were GREEN at base. The wedged
/// loop tests were RED at base (it hung), and the bind-failure test was RED at base (the groups were still live
/// when the create failed). Ports come from the OS (bound to 0, then released), so the tests do not collide
/// with other modules running concurrently (#939).
class HttpServerStopTest {
    /// Channel-close bound + group-termination bound (with its caller-side slack), plus slack.
    private static final long STOP_BOUND_MS = ServerShutdown.CHANNEL_CLOSE_TIMEOUT_MS + ServerShutdown.SHUTDOWN_TIMEOUT_MS
                                              + 1_000L + 3_000L;
    private static final BiConsumer<HttpRequest, ResponseWriter> NO_HANDLER = (_, _) -> {};

    @Nested
    class Http1 {
        /// GREEN at base (guard). The resolve-early mutation reddens it.
        @Test
        void stop_resolvesOnlyAfterTheOwnedGroupsTerminate() throws InterruptedException {
            var server = startH1(freeTcpPort());
            var release = new CountDownLatch(1);
            var blocking = new CountDownLatch(1);

            server.workerGroup()
                  .unwrap()
                  .next()
                  .execute(() -> hold(blocking, release));
            assertThat(blocking.await(5, TimeUnit.SECONDS)).as("the worker task is running").isTrue();

            var stopped = server.stop();

            Thread.sleep(300);
            assertThat(stopped.isResolved()).as("stop() must not resolve while a worker loop has not terminated")
                                            .isFalse();

            release.countDown();

            assertThat(stopped.await(timeSpan(STOP_BOUND_MS).millis()).isSuccess()).as("stop() resolves once the loops terminate")
                                                                                   .isTrue();
            assertThat(server.bossGroup().unwrap().isTerminated()).as("boss group terminated when stop() resolved").isTrue();
            assertThat(server.workerGroup().unwrap().isTerminated()).as("worker group terminated when stop() resolved")
                                                                    .isTrue();
        }

        /// GREEN at base (guard): the port is free the moment stop() resolved.
        @Test
        void stop_releasesThePort_soTheSamePortRebindsRightAfter() {
            var port = freeTcpPort();

            assertThat(startH1(port).stop().await(timeSpan(STOP_BOUND_MS).millis()).isSuccess()).isTrue();
            assertThat(startH1(port).stop().await(timeSpan(STOP_BOUND_MS).millis()).isSuccess())
                .as("a second server bound the same port right after stop() resolved, and stopped")
                .isTrue();
        }

        /// RED at base (it hung): a wedged boss loop never completes the server-channel close. The close is
        /// bounded, so the groups are still asked to shut down and stop() fails typed within the bound.
        @Test
        void stop_withAWedgedBossLoop_asksBothGroupsToShutDown_andFailsWithinTheBound() throws InterruptedException {
            var server = startH1(freeTcpPort());

            assertStopBoundedWhileWedged(server::stop,
                                         server.bossGroup().unwrap().next(),
                                         Set.of(server.bossGroup().unwrap(), server.workerGroup().unwrap()));
        }

        /// RED at base: a wedged worker loop does not stop the close, but it never terminates. The termination
        /// is bounded and its failure is reported, instead of stop() waiting on it indefinitely and then
        /// reporting success.
        @Test
        void stop_withAWedgedWorkerLoop_reportsTheTerminationFailure() throws InterruptedException {
            var server = startH1(freeTcpPort());

            assertStopBoundedWhileWedged(server::stop,
                                         server.workerGroup().unwrap().next(),
                                         Set.of(server.bossGroup().unwrap(), server.workerGroup().unwrap()));
        }

        /// Shared groups belong to their owner: stop() closes the channel and leaves them running.
        @Test
        void stop_withSharedGroups_leavesThemRunning() {
            var boss = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
            var worker = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());

            try {
                var server = NettyHttpServer.createShared(HttpServerConfig.httpServerConfig("stop-shared", freeTcpPort()),
                                                          NO_HANDLER,
                                                          boss,
                                                          worker)
                                            .await(timeSpan(10).seconds())
                                            .unwrap();

                assertThat(server.stop().await(timeSpan(STOP_BOUND_MS).millis()).isSuccess()).isTrue();
                assertThat(boss.isShuttingDown()).as("shared boss group left running").isFalse();
                assertThat(worker.isShuttingDown()).as("shared worker group left running").isFalse();
            } finally {
                boss.shutdownGracefully(0, 1, TimeUnit.SECONDS);
                worker.shutdownGracefully(0, 1, TimeUnit.SECONDS);
            }
        }

        /// RED at base: the groups a failed create owns are terminated BEFORE the create reports the failure.
        /// Termination is read inside the failure callback itself, so a create that fails first and terminates
        /// afterwards is caught deterministically; with a quiet period of 0 the threads die within
        /// microseconds either way, so a check made after the fact could not tell the two apart.
        @Test
        void create_bindFailure_terminatesItsGroupsBeforeFailing() throws IOException {
            var boss = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
            var worker = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());

            try (var taken = new ServerSocket(0)) {
                assertGroupsTerminatedWhenFailureReported(NettyHttpServer.createOwning(HttpServerConfig.httpServerConfig("bind-fail",
                                                                                                                         taken.getLocalPort()),
                                                                                       NO_HANDLER,
                                                                                       boss,
                                                                                       worker)
                                                                         .mapToUnit(),
                                                          Set.of(boss, worker));
            }
        }

        /// R4/M5: an owned-group stop does not wait out a quiet period. Netty's default (2 s) would make every
        /// stop take at least that long.
        @Test
        void stop_ownedGroups_completesWellUnderTheDefaultQuietPeriod() {
            var server = startH1(freeTcpPort());
            var started = System.nanoTime();

            assertThat(server.stop().await(timeSpan(STOP_BOUND_MS).millis()).isSuccess()).isTrue();

            var elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertThat(elapsedMs).as("stop() took %d ms; Netty's default quiet period alone is 2000 ms", elapsedMs)
                                 .isLessThan(1_000L);
        }

        /// R4/M7: a channel close that fails is the stop's typed first failure, even though the groups are
        /// still shut down and terminate.
        @Test
        void stop_channelCloseFails_reportsThatFailure_andStillTerminatesTheGroups() {
            var server = startH1(freeTcpPort());

            server.serverChannel()
                  .unwrap()
                  .pipeline()
                  .addFirst(new CloseRefusingHandler());

            var outcome = server.stop().await(timeSpan(STOP_BOUND_MS).millis());

            assertThat(outcome.isFailure()).as("the refused close is reported").isTrue();
            outcome.onFailure(cause -> assertThat(cause.message()).contains(CloseRefusingHandler.REFUSAL));
            assertThat(server.bossGroup().unwrap().isTerminated()).as("boss group terminated anyway").isTrue();
            assertThat(server.workerGroup().unwrap().isTerminated()).as("worker group terminated anyway").isTrue();
        }

        private static NettyHttpServer startH1(int port) {
            return (NettyHttpServer) NettyHttpServer.create(HttpServerConfig.httpServerConfig("stop-h1", port), NO_HANDLER)
                                                    .await(timeSpan(10).seconds())
                                                    .unwrap();
        }
    }

    @Nested
    class Http3 {
        /// GREEN at base (guard). The resolve-early mutation reddens it.
        @Test
        void stop_resolvesOnlyAfterTheOwnedGroupTerminates() throws InterruptedException {
            var server = startH3(freeUdpPort());
            var release = new CountDownLatch(1);
            var blocking = new CountDownLatch(1);

            server.workerGroup()
                  .unwrap()
                  .next()
                  .execute(() -> hold(blocking, release));
            assertThat(blocking.await(5, TimeUnit.SECONDS)).as("the task is running").isTrue();

            var stopped = server.stop();

            Thread.sleep(300);
            assertThat(stopped.isResolved()).as("stop() must not resolve while a loop has not terminated").isFalse();

            release.countDown();

            assertThat(stopped.await(timeSpan(STOP_BOUND_MS).millis()).isSuccess()).isTrue();
            assertThat(server.workerGroup().unwrap().isTerminated()).as("group terminated when stop() resolved").isTrue();
        }

        /// GREEN at base (guard).
        @Test
        void stop_releasesThePort_soTheSamePortRebindsRightAfter() {
            var port = freeUdpPort();

            assertThat(startH3(port).stop().await(timeSpan(STOP_BOUND_MS).millis()).isSuccess()).isTrue();
            assertThat(startH3(port).stop().await(timeSpan(STOP_BOUND_MS).millis()).isSuccess())
                .as("a second server bound the same UDP port right after stop() resolved, and stopped")
                .isTrue();
        }

        /// RED at base (it hung): the datagram channel's loop is wedged, so its close never completes and the
        /// group never terminates. Both are bounded, and stop() fails typed within the bound.
        @Test
        void stop_withTheChannelLoopWedged_asksTheGroupToShutDown_andFailsWithinTheBound() throws InterruptedException {
            var server = startH3(freeUdpPort());

            assertStopBoundedWhileWedged(server::stop,
                                         server.serverChannel().unwrap().eventLoop(),
                                         Set.of(server.workerGroup().unwrap()));
        }

        /// RED at base: the group a failed create owns is terminated BEFORE the create reports the failure,
        /// read inside the failure callback (see the HTTP/1.1 twin).
        @Test
        void create_bindFailure_terminatesItsGroupBeforeFailing() throws IOException {
            var group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());

            try (var taken = new DatagramSocket(0)) {
                assertGroupsTerminatedWhenFailureReported(Http3Server.bind(HttpServerConfig.httpServerConfig("bind-fail-h3",
                                                                                                             taken.getLocalPort()),
                                                                           quicSsl(),
                                                                           NO_HANDLER,
                                                                           group,
                                                                           true)
                                                                     .mapToUnit(),
                                                          Set.of(group));
            }
        }

        private static Http3Server startH3(int port) {
            return Http3Server.create(HttpServerConfig.httpServerConfig("stop-h3", port), quicSsl(), NO_HANDLER)
                              .await(timeSpan(10).seconds())
                              .unwrap();
        }

        private static QuicSslContext quicSsl() {
            return QuicSslContextFactory.createSelfSignedServer()
                                        .unwrap();
        }
    }

    private static void assertStopBoundedWhileWedged(Supplier<Promise<Unit>> stop,
                                                     Executor loop,
                                                     Set<EventLoopGroup> groups) throws InterruptedException {
        var release = new CountDownLatch(1);
        var blocking = new CountDownLatch(1);

        loop.execute(() -> hold(blocking, release));
        assertThat(blocking.await(5, TimeUnit.SECONDS)).as("the loop is wedged").isTrue();

        try {
            var stopped = stop.get();
            var outcome = stopped.await(timeSpan(STOP_BOUND_MS).millis());

            assertThat(stopped.isResolved()).as("stop() resolves within %d ms although a loop is wedged", STOP_BOUND_MS)
                                            .isTrue();
            assertThat(outcome.isFailure()).as("a wedged loop is reported, not hidden").isTrue();
            outcome.onFailure(cause -> assertThat(cause).isInstanceOf(CoreError.Timeout.class));
            assertThat(groups).as("every owned group was asked to shut down")
                              .allMatch(EventLoopGroup::isShuttingDown);
        } finally {
            release.countDown();
        }
    }

    /// Reads every owned group's `isTerminated()` INSIDE the failure callback, i.e. at the moment the failed
    /// create reports, not afterwards.
    private static void assertGroupsTerminatedWhenFailureReported(Promise<Unit> create, Set<EventLoopGroup> groups) {
        var terminatedAtFailure = new AtomicReference<Boolean>();
        // Read termination inside a DEPENDENT completion: onFailure/onResult handlers run asynchronously, so
        // await() on them can return before the callback ran (v1628 measured 3/12 nulls). fold's result is not
        // resolved until the transformer has run.
        var outcome = create.fold(result -> {
                                      result.onFailure(_ -> terminatedAtFailure.set(groups.stream()
                                                                                          .allMatch(EventLoopGroup::isTerminated)));
                                      return Promise.resolved(result);
                                  })
                            .await(timeSpan(STOP_BOUND_MS).millis());

        assertThat(outcome.isFailure()).as("control: the bind failed").isTrue();
        outcome.onFailure(cause -> assertThat(cause).isInstanceOf(HttpServerError.BindFailed.class));
        assertThat(terminatedAtFailure.get()).as("every owned group was terminated when the failure was reported")
                                            .isTrue();
    }

    /// Fails every close on the server channel's pipeline, so its close future fails.
    private static final class CloseRefusingHandler extends ChannelOutboundHandlerAdapter {
        private static final String REFUSAL = "close refused by test";

        @Override
        public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
            promise.setFailure(new IOException(REFUSAL));
        }
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

    private static int freeUdpPort() {
        try (var socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
