package org.pragmatica.net.redis;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// [RedisConnection] failure paths: refused frames, malformed replies, handler faults, write failure, group lifetime.
class RedisConnectionFaultTest {
    private static final TimeSpan WAIT = timeSpan(10).seconds();

    private FakeRedisServer server;
    private RedisConnection connection;
    private EventLoopGroup group;

    @AfterEach
    void tearDown() throws IOException {
        if (connection != null) {
            connection.close().await(WAIT);
        }
        if (server != null) {
            server.close();
        }
        if (group != null) {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    private static EventLoopGroup newGroup() {
        return new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    }

    private static String kind(Result<?> result) {
        return TestKinds.kind(result);
    }

    private RedisConfig config(long commandTimeoutMillis) {
        return RedisConfig.redisConfig("127.0.0.1", server.port())
                          .flatMap(c -> c.withTimeouts(timeSpan(5).seconds(), timeSpan(commandTimeoutMillis).millis()))
                          .unwrap();
    }

    private RedisConnection open(long commandTimeoutMillis, long frameLimit, FakeRedisServer.Session session) throws IOException {
        server = FakeRedisServer.start(session);
        group = newGroup();
        connection = RedisConnection.connect(config(commandTimeoutMillis), group, Option.none(), Option.some(group), frameLimit)
                                    .await(WAIT)
                                    .unwrap();
        return connection;
    }

    private RedisConnection open(long commandTimeoutMillis, FakeRedisServer.Session session) throws IOException {
        return open(commandTimeoutMillis, RespCodec.MAX_COMMAND, session);
    }

    private static void awaitInactive(RedisConnection conn) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (conn.isActive() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    @Test
    void frameTheCodecRefusesFailsTypedAndTheNextCommandKeepsItsOwnReply() throws IOException {
        var conn = open(10_000, 64, c -> {
            server.recordCommand(c);
            c.write("$1\r\nv\r\n");
            Thread.sleep(500);
        });
        var oversized = conn.execute(List.of("SET".getBytes(), "k".getBytes(), new byte[200]));
        var next = conn.get("k");

        assertThat(kind(oversized.await(WAIT))).isEqualTo("InvalidArgument");
        assertThat(new String(next.await(WAIT).unwrap().or(new byte[0]))).isEqualTo("v");
        assertThat(server.received()).containsExactly(List.of("GET", "k"));
        assertThat(conn.inFlight()).isZero();
    }

    @Test
    void replyDecodedBeforeAMalformedFrameInTheSameReadIsDelivered() throws IOException {
        var conn = open(2_000, c -> {
            c.readCommand();
            c.readCommand();
            c.write(":41\r\n?garbage\r\n");
            Thread.sleep(500);
        });
        var first = conn.incr("a");
        var second = conn.incr("b");

        assertThat(first.await(WAIT).unwrap()).isEqualTo(41L);
        assertThat(kind(second.await(WAIT))).isEqualTo("ProtocolViolation");
    }

    @Test
    void protocolViolationClosesTheConnectionAndNamesItself() throws IOException {
        var conn = open(2_000, c -> {
            c.readCommand();
            c.write("?garbage\r\n");
            Thread.sleep(3_000);
        });
        var failed = conn.incr("a").await(WAIT);

        assertThat(kind(failed)).isEqualTo("ProtocolViolation");
        assertThat(conn.isActive()).isFalse();
        assertThat(kind(conn.incr("b").await(WAIT))).isEqualTo("ProtocolViolation");
    }

    @Test
    void replyWithoutACommandInFlightEndsTheConnectionAsAProtocolViolation() throws Exception {
        var conn = open(2_000, c -> {
            c.write("+OK\r\n");
            Thread.sleep(3_000);
        });

        awaitInactive(conn);
        assertThat(conn.isActive()).isFalse();
        assertThat(kind(conn.incr("a").await(WAIT))).isEqualTo("ProtocolViolation");
    }

    @Test
    void faultInThePipelineFailsTheCommandInFlightWithItsCause() throws IOException {
        var conn = open(5_000, c -> {
            c.readCommand();
            Thread.sleep(3_000);
        });
        var pending = conn.incr("a");

        conn.channel().pipeline().fireExceptionCaught(new IllegalStateException("boom"));

        var failed = pending.await(WAIT);

        assertThat(kind(failed)).isEqualTo("ConnectionLost");
        assertThat(TestKinds.message(failed)).contains("boom");
    }

    @Test
    void failedWriteFailsTheCommandAsConnectionLostNotAsATimeout() throws IOException {
        var conn = open(2_000, c -> Thread.sleep(3_000));

        ((SocketChannel) conn.channel()).shutdownOutput().syncUninterruptibly();

        assertThat(kind(conn.incr("a").await(WAIT))).isEqualTo("ConnectionLost");
    }

    @Test
    void closedConnectionIsInactiveImmediatelyEvenWhileTheChannelIsStillClosing() throws Exception {
        var conn = open(5_000, c -> Thread.sleep(3_000));
        var blocked = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        conn.channel().eventLoop().execute(() -> {
            blocked.countDown();
            awaitQuietly(release);
        });
        blocked.await();
        conn.close();

        assertThat(conn.isActive()).isFalse();
        release.countDown();
        conn.close().await(WAIT);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void closeResolvesOnlyAfterTheOwnedEventLoopTerminated() throws IOException {
        var conn = open(5_000, c -> Thread.sleep(3_000));

        conn.close().await(WAIT).unwrap();
        assertThat(group.isTerminated()).isTrue();
    }
}
