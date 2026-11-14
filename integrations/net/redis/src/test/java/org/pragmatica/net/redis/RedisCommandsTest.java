package org.pragmatica.net.redis;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.redis.SetOptions.Expiry;
import static org.pragmatica.net.redis.SetOptions.setOptions;

/// Wire form of every command: what the server receives for each API call.
class RedisCommandsTest {
    private static final TimeSpan WAIT = timeSpan(10).seconds();

    private FakeRedisServer server;
    private RedisConnection connection;

    @BeforeEach
    void setUp() throws IOException {
        server = FakeRedisServer.start(c -> {
            while (true) {
                var command = server.recordCommand(c);

                c.write(replyFor(command.getFirst()));
            }
        });
        connection = RedisConnection.redisConnection(RedisConnectionTest.config(server.port()))
                                    .await(WAIT)
                                    .unwrap();
    }

    @AfterEach
    void tearDown() throws IOException {
        connection.close().await(WAIT);
        server.close();
    }

    private static String replyFor(String verb) {
        return switch (verb) {
            case "PING", "SET" -> "+" + (verb.equals("PING") ? "PONG" : "OK") + "\r\n";
            case "GET" -> "$-1\r\n";
            case "MGET" -> "*2\r\n$1\r\nx\r\n$-1\r\n";
            case "EVAL" -> "*2\r\n:1\r\n$2\r\nok\r\n";
            default -> ":1\r\n";
        };
    }

    private List<String> lastCommand() {
        return server.received().getLast();
    }

    @Test
    void ping() {
        connection.ping().await(WAIT).unwrap();
        assertThat(lastCommand()).containsExactly("PING");
    }

    @Test
    void getMissingKeyIsNone() {
        assertThat(connection.get("k").await(WAIT).unwrap().isEmpty()).isTrue();
        assertThat(lastCommand()).containsExactly("GET", "k");
    }

    @Test
    void setPlain() {
        assertThat(connection.set("k", "v".getBytes()).await(WAIT).unwrap()).isTrue();
        assertThat(lastCommand()).containsExactly("SET", "k", "v");
    }

    @Test
    void setWithSecondsMillisAndNx() {
        connection.set("k", "v".getBytes(), setOptions().withExpiry(Expiry.seconds(30).unwrap())).await(WAIT).unwrap();
        assertThat(lastCommand()).containsExactly("SET", "k", "v", "EX", "30");
        connection.set("k", "v".getBytes(), setOptions().withExpiry(Expiry.millis(1500).unwrap()).ifAbsent())
                  .await(WAIT)
                  .unwrap();
        assertThat(lastCommand()).containsExactly("SET", "k", "v", "PX", "1500", "NX");
    }

    @Test
    void setNxRefusedByServerIsFalseNotAnError() throws IOException {
        server.close();
        server = FakeRedisServer.start(c -> {
            server.recordCommand(c);
            c.write("$-1\r\n");
        });
        connection.close().await(WAIT);
        connection = RedisConnection.redisConnection(RedisConnectionTest.config(server.port())).await(WAIT).unwrap();

        assertThat(connection.set("k", "v".getBytes(), setOptions().ifAbsent()).await(WAIT).unwrap()).isFalse();
    }

    @Test
    void delExpirePexpireIncr() {
        assertThat(connection.del("a", "b").await(WAIT).unwrap()).isEqualTo(1L);
        assertThat(lastCommand()).containsExactly("DEL", "a", "b");
        assertThat(connection.expire("a", 10).await(WAIT).unwrap()).isTrue();
        assertThat(lastCommand()).containsExactly("EXPIRE", "a", "10");
        assertThat(connection.pexpire("a", 250).await(WAIT).unwrap()).isTrue();
        assertThat(lastCommand()).containsExactly("PEXPIRE", "a", "250");
        assertThat(connection.incr("n").await(WAIT).unwrap()).isEqualTo(1L);
        assertThat(lastCommand()).containsExactly("INCR", "n");
    }

    @Test
    void mgetKeepsKeyOrderAndMarksMissingKeys() {
        var values = connection.mget("a", "b").await(WAIT).unwrap();

        assertThat(lastCommand()).containsExactly("MGET", "a", "b");
        assertThat(new String(values.get(0).or(new byte[0]))).isEqualTo("x");
        assertThat(values.get(1).isEmpty()).isTrue();
    }

    @Test
    void evalCarriesKeyCountKeysThenArguments() {
        var reply = connection.eval("return 1", List.of("k1", "k2"), List.of("a1".getBytes())).await(WAIT).unwrap();

        assertThat(lastCommand()).containsExactly("EVAL", "return 1", "2", "k1", "k2", "a1");
        assertThat(RespCodecTest.show(reply)).isEqualTo("[:1,$ok]");
    }

    @Test
    void blankEvalScriptIsRefusedBeforeAnythingIsSent() {
        var before = server.received().size();
        var result = connection.eval(" ", List.of(), List.of()).await(WAIT);

        assertThat(TestKinds.kind(result)).isEqualTo("InvalidArgument");
        assertThat(server.received()).hasSize(before);
    }

    @Test
    void unexpectedReplyTypeIsTyped() throws IOException {
        server.close();
        server = FakeRedisServer.start(c -> {
            server.recordCommand(c);
            c.write("+NOTANINT\r\n");
        });
        connection.close().await(WAIT);
        connection = RedisConnection.redisConnection(RedisConnectionTest.config(server.port())).await(WAIT).unwrap();

        assertThat(TestKinds.kind(connection.incr("n").await(WAIT)))
            .isEqualTo("UnexpectedReply");
    }
}
