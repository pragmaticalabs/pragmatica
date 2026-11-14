package org.pragmatica.net.redis;

import java.io.IOException;
import java.util.ArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// [RedisClient] pool behaviour against a scripted fake server.
class RedisClientTest {
    private static final TimeSpan WAIT = timeSpan(10).seconds();

    private FakeRedisServer server;
    private RedisClient client;

    @AfterEach
    void tearDown() throws IOException {
        if (client != null) {
            client.close().await(WAIT);
        }
        if (server != null) {
            server.close();
        }
    }

    private static String kind(Result<?> result) {
        return TestKinds.kind(result);
    }

    private void open(int poolSize, FakeRedisServer.Session session) throws IOException {
        server = FakeRedisServer.start(session);
        var cfg = RedisConfig.redisConfig("127.0.0.1", server.port())
                             .flatMap(c -> c.withPool(poolSize, 16))
                             .unwrap();

        client = RedisClient.redisClient(cfg).await(WAIT).unwrap();
    }

    @Test
    void startOpensExactlyOneConnectionAndReusesItWhileIdle() throws IOException {
        open(3, c -> {
            while (true) {
                server.recordCommand(c);
                c.write(":1\r\n");
            }
        });

        for (int i = 0; i < 5; i++) {
            assertThat(client.incr("k").await(WAIT).unwrap()).isEqualTo(1L);
        }

        assertThat(server.connections()).isEqualTo(1);
        assertThat(client.openConnections()).isEqualTo(1);
    }

    @Test
    void busyConnectionMakesThePoolOpenAnotherUpToPoolSize() throws IOException {
        open(2, c -> {
            server.recordCommand(c);
            Thread.sleep(300);
            c.write(":1\r\n");
            Thread.sleep(300);
        });

        var calls = new ArrayList<Promise<Long>>();

        for (int i = 0; i < 6; i++) {
            calls.add(client.incr("k"));
        }

        assertThat(server.connections()).isLessThanOrEqualTo(2);
        assertThat(client.openConnections()).isLessThanOrEqualTo(2);
        assertThat(calls.stream().map(call -> kind(call.await(WAIT))).toList()).doesNotContain("PoolSaturated");
    }

    @Test
    void lostConnectionIsReplacedByTheNextCommandNotRetried() throws IOException {
        var connectionNumber = new java.util.concurrent.atomic.AtomicInteger();

        open(1, c -> {
            if (connectionNumber.incrementAndGet() == 1) {
                server.recordCommand(c);
                c.drop();
            } else {
                server.recordCommand(c);
                c.write(":9\r\n");
            }
        });

        assertThat(kind(client.incr("k").await(WAIT))).isEqualTo("ConnectionLost");
        assertThat(client.incr("k").await(WAIT).unwrap()).isEqualTo(9L);
        // the failed INCR was sent once and never replayed: one command per connection
        assertThat(server.received()).hasSize(2);
        assertThat(server.connections()).isEqualTo(2);
    }

    @Test
    void closeIsIdempotentAndCommandsAfterItFailClosedWithoutBeingSent() throws IOException {
        open(2, c -> {
            while (true) {
                server.recordCommand(c);
                c.write(":1\r\n");
            }
        });
        client.incr("k").await(WAIT).unwrap();

        Promise<?> first = client.close();
        Promise<?> second = client.close();

        assertThat(first).isSameAs(second);
        assertThat(first.await(WAIT).isSuccess()).isTrue();
        assertThat(TestKinds.message(client.incr("k").await(WAIT))).isEqualTo(RedisError.General.CLOSED.message());
        assertThat(server.received()).hasSize(1);
    }

    @Test
    void unreachableServerFailsClientCreation() throws IOException {
        server = FakeRedisServer.start(c -> {});
        var port = server.port();

        server.close();

        var cfg = RedisConfig.redisConfig("127.0.0.1", port).unwrap();

        assertThat(kind(RedisClient.redisClient(cfg).await(WAIT))).isEqualTo("ConnectionFailed");
    }

    @Test
    void tlsAgainstAPlaintextServerFailsAtConnectNotOnTheFirstCommand() throws IOException {
        server = FakeRedisServer.start(c -> c.write("+OK\r\n"));
        var cfg = RedisConfig.redisConfig("127.0.0.1", server.port()).flatMap(RedisConfig::withTls).unwrap();
        var result = RedisClient.redisClient(cfg).await(WAIT);

        assertThat(kind(result)).isEqualTo("ConnectionFailed");
    }
}
