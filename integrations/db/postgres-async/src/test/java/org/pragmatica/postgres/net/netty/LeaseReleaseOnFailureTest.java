/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.pragmatica.postgres.net.netty;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.postgres.message.frontend.StartupMessage;
import org.pragmatica.postgres.net.Connectible;
import org.pragmatica.postgres.net.PoolMode;
import org.pragmatica.postgres.net.SslConfig;
import org.pragmatica.testing.PollUntil;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;


/// #3635, ReleaseChain invariant: every resource that holds a lease is released on EVERY outcome of every step before it.
/// One test per provision or close step whose failure used to skip the release after it (rows R4-R8 of the PR table).
class LeaseReleaseOnFailureTest {
    private static final TimeSpan GUARD = TimeSpan.fromDuration(Duration.ofSeconds(10));
    private static final Duration BOUND = Duration.ofSeconds(10);

    private final List<ServerSocket> servers = new ArrayList<>();
    private final List<Socket> accepted = new ArrayList<>();
    private final List<EventLoopGroup> groups = new ArrayList<>();
    private final CountDownLatch startupReceived = new CountDownLatch(1);

    @AfterEach
    void cleanUp() throws IOException {
        for (var socket : accepted) {
            socket.close();
        }

        for (var server : servers) {
            server.close();
        }

        groups.forEach(group -> group.shutdownGracefully(0, 1, TimeUnit.SECONDS));
    }

    enum Kind {
        PLAIN,
        SESSION_POOL,
        TRANSACTION_POOL
    }

    /// R4-R6: the server refuses the startup and keeps the socket open. The stream obtained for the connect is closed by
    /// the failed connect itself (PgDatabase, PgConnectionPool, PgTransactionPool), so its lease goes back at once and
    /// closing the Connectible terminates the group. The validation step sits inside the same release wrap.
    @ParameterizedTest
    @EnumSource(Kind.class)
    void refusedStartup_closesTheStream_andTheGroupTerminatesOnClose(Kind kind) throws IOException {
        var builder = builderTo(refusingServerPort());
        var connectible = build(builder, kind);

        assertThat(attemptStartup(connectible).await(GUARD).isFailure()).as("startup refused").isTrue();
        assertThat(awaitLeases(builder, 1)).as("leases once the refused connect settled: only the Connectible's").isEqualTo(1);
        var group = builder.ownedGroup();

        assertThat(connectible.close().await(GUARD).isSuccess()).isTrue();
        assertThat(group.terminationFuture().awaitUninterruptibly(10, TimeUnit.SECONDS)).as("group terminated").isTrue();
    }

    /// R7: the Terminate write fails while the channel is still open (output shut down); the channel is closed anyway
    /// and the write failure is the result.
    @Test
    void close_closesTheChannel_whenTheTerminateWriteFails() throws Exception {
        var released = new AtomicInteger();
        var stream = streamTo(silentServerPort(), released);

        var connecting = stream.connect(new StartupMessage("u", "d"));

        assertThat(startupReceived.await(10, TimeUnit.SECONDS)).as("channel active, startup sent").isTrue();
        ((SocketChannel) stream.channel()).shutdownOutput().sync();

        var outcome = stream.close().await(GUARD);

        assertThat(outcome.isFailure()).as("the failed Terminate write is the result: %s", outcome).isTrue();
        assertThat(awaitReleased(released, 1)).as("channel lease released").isEqualTo(1);
        assertThat(stream.channel().isOpen()).isFalse();
        assertThat(connecting.await(GUARD).isFailure()).isTrue();
    }

    /// R8: a channel that cannot be created never completes its closeFuture (Netty 4.2.18 AbstractBootstrap.initAndRegister);
    /// the failed connect releases the stream's lease instead.
    @Test
    void failedChannelCreation_releasesTheStreamLease() throws IOException {
        var released = new AtomicInteger();
        var stream = new NettyPgProtocolStream(new InetSocketAddress("127.0.0.1", closedPort()),
                                               "127.0.0.1",
                                               SslConfig.disabled(),
                                               StandardCharsets.UTF_8,
                                               group(),
                                               released::incrementAndGet,
                                               () -> {
                                                   throw new IllegalStateException("too many open files (injected by the test)");
                                               });

        assertThat(stream.connect(new StartupMessage("u", "d")).await(GUARD).isFailure()).isTrue();
        assertThat(awaitReleased(released, 1)).as("stream lease released after a failed channel creation").isEqualTo(1);
    }

    /// R8: a refused connect completes both the connect future (failed) and the closeFuture; the lease goes back once.
    @Test
    void refusedConnect_releasesTheStreamLeaseExactlyOnce() throws Exception {
        var released = new AtomicInteger();
        var stream = streamTo(closedPort(), released);
        var bothSignalled = new CountDownLatch(1);

        assertThat(stream.connect(new StartupMessage("u", "d")).await(GUARD).isFailure()).isTrue();
        stream.channel().closeFuture().addListener(_ -> bothSignalled.countDown());
        assertThat(bothSignalled.await(10, TimeUnit.SECONDS)).as("closeFuture completed").isTrue();
        assertThat(released.get()).as("releases after the failed connect and the channel close").isEqualTo(1);
    }

    /// The transaction pool hands out a logical connection and connects on the first statement, so the statement is part of
    /// the attempt; the other kinds connect in `getConnection()`.
    private static Promise<?> attemptStartup(Connectible connectible) {
        return connectible.getConnection()
                          .flatMap(connection -> connection.completeScript("select 1"));
    }

    private static int awaitLeases(NettyConnectibleBuilder builder, int expected) {
        return PollUntil.pollUntil(builder::leaseCount, count -> count == expected, BOUND).finalValue();
    }

    private static int awaitReleased(AtomicInteger released, int expected) {
        return PollUntil.pollUntil(released::get, count -> count == expected, BOUND).finalValue();
    }

    private static Connectible build(NettyConnectibleBuilder builder, Kind kind) {
        return switch (kind) {
            case PLAIN -> builder.plain();
            case SESSION_POOL -> builder.pool();
            case TRANSACTION_POOL -> {
                builder.poolMode(PoolMode.TRANSACTION);
                yield builder.pool();
            }
        };
    }

    private static NettyConnectibleBuilder builderTo(int port) {
        var builder = new NettyConnectibleBuilder();

        builder.hostname("127.0.0.1").port(port).username("u").password("p").database("d");

        return builder;
    }

    private NettyPgProtocolStream streamTo(int port, AtomicInteger released) {
        return new NettyPgProtocolStream(new InetSocketAddress("127.0.0.1", port),
                                         "127.0.0.1",
                                         SslConfig.disabled(),
                                         StandardCharsets.UTF_8,
                                         group(),
                                         released::incrementAndGet);
    }

    private EventLoopGroup group() {
        var group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());

        groups.add(group);

        return group;
    }

    private static int closedPort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /// Reads the startup message and never answers: the connect stays in flight with an active channel.
    private int silentServerPort() throws IOException {
        return serve(_ -> {});
    }

    /// Reads the startup message, answers with a FATAL ErrorResponse and keeps the socket open (a real server closes it).
    private int refusingServerPort() throws IOException {
        return serve(LeaseReleaseOnFailureTest::refuse);
    }

    private int serve(StartupAnswer answer) throws IOException {
        var server = new ServerSocket(0);

        servers.add(server);
        Thread.ofVirtual().start(() -> acceptEach(server, answer));

        return server.getLocalPort();
    }

    private void acceptEach(ServerSocket server, StartupAnswer answer) {
        try {
            while (!server.isClosed()) {
                var socket = server.accept();

                accepted.add(socket);
                readStartup(socket);
                startupReceived.countDown();
                answer.answer(socket);
            }
        } catch (IOException _) {
        // closed by cleanUp
        }
    }

    private static void readStartup(Socket socket) throws IOException {
        var input = new DataInputStream(socket.getInputStream());
        var length = input.readInt();

        input.readFully(new byte[length - 4]);
    }

    private static void refuse(Socket socket) throws IOException {
        var fields = new ByteArrayOutputStream();

        fields.writeBytes("SFATAL\0C28P01\0Mrefused by the test\0\0".getBytes(StandardCharsets.US_ASCII));
        var body = fields.toByteArray();
        var message = new ByteArrayOutputStream();

        message.write('E');
        message.write(intBytes(body.length + 4));
        message.write(body);
        socket.getOutputStream().write(message.toByteArray());
        socket.getOutputStream().flush();
    }

    private static byte[] intBytes(int value) {
        return new byte[]{(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
    }

    @FunctionalInterface
    private interface StartupAnswer {
        void answer(Socket socket) throws IOException;
    }
}
