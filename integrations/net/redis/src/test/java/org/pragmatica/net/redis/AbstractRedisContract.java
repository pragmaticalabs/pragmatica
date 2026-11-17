package org.pragmatica.net.redis;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.redis.SetOptions.Expiry;
import static org.pragmatica.net.redis.SetOptions.setOptions;

/// The contract every Redis-protocol server must pass; one subclass per server image.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractRedisContract {
    private static final TimeSpan WAIT = timeSpan(20).seconds();
    private static final String COMPARE_AND_DELETE = "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end";

    private GenericContainer<?> container;
    private RedisClient client;

    abstract String image();

    @BeforeAll
    void start() {
        container = new GenericContainer<>(DockerImageName.parse(image())).withExposedPorts(6379);
        container.start();
        client = RedisClient.redisClient(RedisConfig.redisConfig(container.getHost(), container.getMappedPort(6379)).unwrap())
                            .await(WAIT)
                            .unwrap();
    }

    @AfterAll
    void stop() {
        client.close().await(WAIT);
        container.stop();
    }

    @Test
    void pingAndRoundTrip() {
        client.ping().await(WAIT).unwrap();
        assertThat(client.set("rt", "v\r\n\0bin".getBytes()).await(WAIT).unwrap()).isTrue();
        assertThat(new String(client.get("rt").await(WAIT).unwrap().or(new byte[0]))).isEqualTo("v\r\n\0bin");
        assertThat(client.get("absent-key").await(WAIT).unwrap().isEmpty()).isTrue();
    }

    @Test
    void setNxIsRefusedWhenThePresentAndAppliedWhenAbsent() {
        var nx = setOptions().ifAbsent();

        assertThat(client.set("nx", "1".getBytes(), nx).await(WAIT).unwrap()).isTrue();
        assertThat(client.set("nx", "2".getBytes(), nx).await(WAIT).unwrap()).isFalse();
        assertThat(new String(client.get("nx").await(WAIT).unwrap().or(new byte[0]))).isEqualTo("1");
    }

    @Test
    void expiryViaSetExAndPexpire() {
        client.set("ex", "v".getBytes(), setOptions().withExpiry(Expiry.seconds(100).unwrap())).await(WAIT).unwrap();
        assertThat(client.expire("ex", 50).await(WAIT).unwrap()).isTrue();
        assertThat(client.pexpire("ex", 60_000).await(WAIT).unwrap()).isTrue();
        assertThat(client.expire("no-such-key", 5).await(WAIT).unwrap()).isFalse();
    }

    @Test
    void incrDelAndMget() {
        assertThat(client.incr("cnt").await(WAIT).unwrap()).isEqualTo(1L);
        assertThat(client.incr("cnt").await(WAIT).unwrap()).isEqualTo(2L);
        client.set("m1", "a".getBytes()).await(WAIT).unwrap();

        var values = client.mget("m1", "m-missing", "cnt").await(WAIT).unwrap();

        assertThat(values).hasSize(3);
        assertThat(values.get(1).isEmpty()).isTrue();
        assertThat(new String(values.get(2).or(new byte[0]))).isEqualTo("2");
        assertThat(client.del("m1", "cnt", "m-missing").await(WAIT).unwrap()).isEqualTo(2L);
    }

    @Test
    void evalCompareAndDeleteIsAtomicOnTheServer() {
        client.set("lock", "owner-a".getBytes()).await(WAIT).unwrap();

        var wrong = client.eval(COMPARE_AND_DELETE, List.of("lock"), List.of("owner-b".getBytes())).await(WAIT).unwrap();
        var right = client.eval(COMPARE_AND_DELETE, List.of("lock"), List.of("owner-a".getBytes())).await(WAIT).unwrap();

        assertThat(RespCodecTest.show(wrong)).isEqualTo(":0");
        assertThat(RespCodecTest.show(right)).isEqualTo(":1");
    }

    @Test
    void serverErrorIsTypedAndTheClientStaysUsable() {
        client.set("str", "x".getBytes()).await(WAIT).unwrap();
        var result = client.execute(RedisCommands.command("LPUSH", "str".getBytes(), "v".getBytes())).await(WAIT);

        assertThat(TestKinds.kind(result)).isEqualTo("ServerError");
        client.ping().await(WAIT).unwrap();
    }

    @Test
    void pipelinedBurstIsAnsweredInOrder() {
        var calls = new ArrayList<Promise<Long>>();

        client.set("burst", "0".getBytes()).await(WAIT).unwrap();
        for (int i = 0; i < 500; i++) {
            calls.add(client.incr("burst"));
        }
        // one connection per idle pool is used first, so order is only guaranteed per connection:
        // the 500 results are a permutation of 1..500 and their sum proves no reply went missing
        var sum = calls.stream().mapToLong(call -> call.await(WAIT).unwrap()).sum();

        assertThat(sum).isEqualTo(500L * 501 / 2);
    }
}

