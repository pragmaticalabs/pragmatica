package org.pragmatica.net.redis;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// [RedisClient] lifetime and pool edge cases: close ordering, late connections, start failure, lost-pool refill.
class RedisClientCloseTest {
    private static final TimeSpan WAIT = timeSpan(10).seconds();

    private FakeRedisServer server;
    private RedisClient client;
    private EventLoopGroup group;

    @AfterEach
    void tearDown() throws IOException {
        if (client != null) {
            client.close().await(WAIT);
        }
        if (server != null) {
            server.close();
        }
        if (group != null) {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    private RedisConfig config(int poolSize) {
        return RedisConfig.redisConfig("127.0.0.1", server.port())
                          .flatMap(c -> c.withPool(poolSize, 16))
                          .unwrap();
    }

    private void newClient(RedisConfig cfg) {
        group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        client = new RedisClient(cfg, Option.none(), group);
    }

    @Test
    void closeResolvesOnlyAfterTheEventLoopGroupTerminated() throws IOException {
        server = FakeRedisServer.start(c -> {
            while (true) {
                server.recordCommand(c);
                c.write(":1\r\n");
            }
        });
        newClient(config(1));
        client.open().await(WAIT).unwrap();

        client.close().await(WAIT).unwrap();
        assertThat(group.isTerminated()).isTrue();
    }

    @Test
    void failedStartShutsDownTheEventLoopGroup() throws IOException {
        server = FakeRedisServer.start(c -> {});
        var cfg = config(1);

        server.close();
        newClient(cfg);

        assertThat(TestKinds.kind(client.open().await(WAIT))).isEqualTo("ConnectionFailed");
        assertThat(group.terminationFuture().awaitUninterruptibly(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void connectionThatFinishesOpeningAfterCloseIsClosedAndNotPooled() throws Exception {
        var authSeen = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var peerSawClose = new CountDownLatch(1);
        var accepted = new AtomicInteger();

        server = FakeRedisServer.start(c -> {
            accepted.incrementAndGet();
            c.readCommand();
            authSeen.countDown();
            release.await();
            c.write("+OK\r\n");
            if (c.in().read() < 0) {
                peerSawClose.countDown();
            }
        });
        var cfg = RedisConfig.redisConfig("127.0.0.1", server.port()).flatMap(c -> c.withPassword("pw")).unwrap();

        newClient(cfg);

        var opened = client.open();

        authSeen.await();

        var closing = client.close();

        Thread.sleep(500);
        assertThat(closing.isResolved()).isFalse();
        release.countDown();
        closing.await(WAIT).unwrap();
        opened.await(WAIT);

        assertThat(client.openConnections()).isZero();
        assertThat(peerSawClose.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(group.isTerminated()).isTrue();
    }

    @Test
    void acquiringAfterCloseIsRefusedAsClosedWithoutOpeningAConnection() throws IOException {
        server = FakeRedisServer.start(c -> {});
        newClient(config(1));
        client.close().await(WAIT).unwrap();

        var refused = client.open().await(WAIT);

        assertThat(TestKinds.message(refused)).isEqualTo(RedisError.General.CLOSED.message());
        assertThat(client.openConnections()).isZero();
    }

    @Test
    void commandsIssuedWhileEveryConnectionIsBeingReopenedWaitInsteadOfSaturating() throws Exception {
        var connectionNumber = new AtomicInteger();
        var firstDropped = new CountDownLatch(1);
        var clientOpened = new CountDownLatch(1);

        server = FakeRedisServer.start(c -> {
            if (connectionNumber.incrementAndGet() == 1) {
                // Dropping at accept races the client's connect (RST before finishConnect fails open itself):
                // hold the connection until the client has finished opening it.
                clientOpened.await();
                c.drop();
                firstDropped.countDown();
                return;
            }
            while (true) {
                server.recordCommand(c);
                c.write(":1\r\n");
            }
        });
        newClient(config(2));
        client.open().await(WAIT).unwrap();
        clientOpened.countDown();
        assertThat(firstDropped.await(10, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(300);

        var calls = new ArrayList<Promise<Long>>();

        for (int i = 0; i < 6; i++) {
            calls.add(client.incr("k"));
        }

        assertThat(calls.stream().map(call -> TestKinds.kind(call.await(WAIT))).toList()).containsOnly("success");
    }
}
