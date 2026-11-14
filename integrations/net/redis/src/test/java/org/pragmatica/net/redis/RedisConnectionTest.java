package org.pragmatica.net.redis;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// [RedisConnection] against a scripted fake server: FIFO correlation, pipelining, failure typing.
class RedisConnectionTest {
    private static final TimeSpan WAIT = timeSpan(10).seconds();

    private FakeRedisServer server;
    private RedisConnection connection;

    @AfterEach
    void tearDown() throws IOException {
        if (connection != null) {
            connection.close().await(WAIT);
        }
        if (server != null) {
            server.close();
        }
    }

    static RedisConfig config(int port) {
        return RedisConfig.redisConfig("127.0.0.1", port)
                          .unwrap();
    }

    private RedisConnection open(FakeRedisServer.Session session) throws IOException {
        server = FakeRedisServer.start(session);
        connection = RedisConnection.redisConnection(config(server.port()))
                                    .await(WAIT)
                                    .unwrap();
        return connection;
    }

    private static String kind(Result<?> result) {
        return TestKinds.kind(result);
    }

    @Test
    void pipelinedCommandsAreAnsweredInIssueOrder() throws IOException {
        // The server answers only after it read all three commands: a client that did not pipeline would
        // deadlock here and fail on the command timeout.
        var conn = open(c -> {
            var commands = List.of(c.readCommand(), c.readCommand(), c.readCommand());

            c.write("$" + commands.get(0).get(1).length() + "\r\n" + commands.get(0).get(1) + "\r\n"
                    + ":42\r\n"
                    + "$" + commands.get(2).get(1).length() + "\r\n" + commands.get(2).get(1) + "\r\n");
        });
        var first = conn.get("alpha");
        var second = conn.incr("counter");
        var third = conn.get("gamma");

        assertThat(new String(first.await(WAIT).unwrap().or(new byte[0]))).isEqualTo("alpha");
        assertThat(second.await(WAIT).unwrap()).isEqualTo(42L);
        assertThat(new String(third.await(WAIT).unwrap().or(new byte[0]))).isEqualTo("gamma");
    }

    @Test
    void replySplitIntoSingleByteFragmentsIsReassembled() throws IOException {
        var conn = open(c -> {
            c.readCommand();
            c.writeFragmented("*2\r\n$5\r\nhello\r\n$-1\r\n");
        });
        var reply = conn.mget("a", "b").await(WAIT).unwrap();

        assertThat(reply).hasSize(2);
        assertThat(new String(reply.get(0).or(new byte[0]))).isEqualTo("hello");
        assertThat(reply.get(1).isEmpty()).isTrue();
    }

    @Test
    void connectionDroppedMidPipelineFailsEveryUnansweredCommandAndNoneSucceeds() throws IOException {
        var conn = open(c -> {
            c.readCommand();
            c.readCommand();
            c.readCommand();
            c.drop();
        });
        var results = List.of(conn.incr("a"), conn.incr("b"), conn.incr("c"))
                          .stream()
                          .map(promise -> promise.await(WAIT))
                          .toList();

        assertThat(results).allSatisfy(result -> assertThat(kind(result)).isEqualTo("ConnectionLost"));
        assertThat(conn.inFlight()).isZero();
    }

    @Test
    void repliesBeforeTheDropStillSucceedAndOnlyTheRestFail() throws IOException {
        var conn = open(c -> {
            c.readCommand();
            c.readCommand();
            c.write(":1\r\n");
            c.drop();
        });
        var first = conn.incr("a");
        var second = conn.incr("b");

        assertThat(first.await(WAIT).unwrap()).isEqualTo(1L);
        assertThat(kind(second.await(WAIT))).isEqualTo("ConnectionLost");
    }

    @Test
    void commandAfterTheConnectionWasLostFailsWithoutBeingSent() throws IOException {
        var conn = open(c -> c.drop());

        assertThat(kind(conn.incr("a").await(WAIT))).isEqualTo("ConnectionLost");
        assertThat(kind(conn.incr("b").await(WAIT))).isNotEqualTo("success");
        assertThat(conn.isActive()).isFalse();
    }

    @Test
    void serverErrorFailsOnlyThatCommandAndTheConnectionStaysUsable() throws IOException {
        var conn = open(c -> {
            c.readCommand();
            c.write("-WRONGTYPE Operation against a key holding the wrong kind of value\r\n");
            c.readCommand();
            c.write(":7\r\n");
        });
        var failed = conn.incr("a").await(WAIT);
        var next = conn.incr("b").await(WAIT);

        assertThat(kind(failed)).isEqualTo("ServerError");
        assertThat(TestKinds.message(failed)).contains("WRONGTYPE");
        assertThat(next.unwrap()).isEqualTo(7L);
        assertThat(conn.inFlight()).isZero();
    }

    @Test
    void commandTimeoutFailsWithTimeoutAndEndsTheConnection() throws IOException {
        server = FakeRedisServer.start(c -> {
            c.readCommand();
            Thread.sleep(2000);
        });
        var cfg = config(server.port()).withTimeouts(timeSpan(5).seconds(), timeSpan(200).millis()).unwrap();

        connection = RedisConnection.redisConnection(cfg).await(WAIT).unwrap();

        var timedOut = connection.incr("a").await(WAIT);

        assertThat(kind(timedOut)).isEqualTo("Timeout");
        assertThat(TestKinds.message(timedOut)).isEqualTo("Redis command timed out after 200 ms");
        assertThat(kind(connection.incr("b").await(WAIT))).isEqualTo("ConnectionLost");
    }

    @Test
    void maxInFlightRefusesTheNextCommandWithoutSendingIt() throws IOException {
        server = FakeRedisServer.start(c -> {
            server.recordCommand(c);
            server.recordCommand(c);
            Thread.sleep(500);
            c.write(":1\r\n:2\r\n");
            Thread.sleep(500);
        });
        var cfg = config(server.port()).withPool(1, 2).unwrap();

        connection = RedisConnection.redisConnection(cfg).await(WAIT).unwrap();

        var a = connection.incr("a");
        var b = connection.incr("b");
        var c = connection.incr("c");

        assertThat(kind(c.await(WAIT))).isEqualTo("PoolSaturated");
        assertThat(a.await(WAIT).unwrap()).isEqualTo(1L);
        assertThat(b.await(WAIT).unwrap()).isEqualTo(2L);
        assertThat(server.received()).hasSize(2);
        assertThat(connection.inFlight()).isZero();
    }

    @Test
    void closeIsIdempotentSharesOnePromiseAndFailsInFlightCommands() throws IOException {
        var conn = open(c -> {
            c.readCommand();
            Thread.sleep(1000);
        });
        var pending = conn.incr("a");
        Promise<?> first = conn.close();
        Promise<?> second = conn.close();

        assertThat(first).isSameAs(second);
        assertThat(first.await(WAIT).isSuccess()).isTrue();
        assertThat(kind(pending.await(WAIT))).isNotEqualTo("success");
        assertThat(kind(conn.incr("b").await(WAIT))).isNotEqualTo("success");
        assertThat(conn.close().await(WAIT).isSuccess()).isTrue();
    }

    @Test
    void authAndSelectAreSentBeforeTheConnectionIsHandedOut() throws IOException {
        server = FakeRedisServer.start(c -> {
            server.recordCommand(c);
            c.write("+OK\r\n");
            server.recordCommand(c);
            c.write("+OK\r\n");
            server.recordCommand(c);
            c.write("+PONG\r\n");
        });
        var cfg = config(server.port()).withPassword("s3cret").flatMap(c -> c.withDatabase(3)).unwrap();

        connection = RedisConnection.redisConnection(cfg).await(WAIT).unwrap();
        connection.ping().await(WAIT).unwrap();

        assertThat(server.received()).containsExactly(List.of("AUTH", "s3cret"), List.of("SELECT", "3"), List.of("PING"));
    }

    @Test
    void refusedAuthIsTypedAndNeverHandsOutAConnection() throws IOException {
        server = FakeRedisServer.start(c -> {
            c.readCommand();
            c.write("-WRONGPASS invalid username-password pair\r\n");
        });
        var cfg = config(server.port()).withPassword("bad").unwrap();

        assertThat(kind(RedisConnection.redisConnection(cfg).await(WAIT))).isEqualTo("AuthFailed");
    }

    @Test
    void unreachableServerIsConnectionFailed() throws IOException {
        server = FakeRedisServer.start(c -> {});
        var port = server.port();

        server.close();
        assertThat(kind(RedisConnection.redisConnection(config(port)).await(WAIT))).isEqualTo("ConnectionFailed");
    }
}
