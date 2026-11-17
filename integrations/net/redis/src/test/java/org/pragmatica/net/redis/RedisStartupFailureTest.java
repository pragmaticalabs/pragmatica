package org.pragmatica.net.redis;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// A failing event loop group construction is lifted into a typed [RedisError.ConnectionFailed], never thrown.
class RedisStartupFailureTest {
    private static final TimeSpan WAIT = timeSpan(10).seconds();
    private static final String BOOM = "no threads for the loop";

    private static RedisConfig config() {
        return RedisConfig.redisConfig("127.0.0.1", 1).unwrap();
    }

    @Test
    void clientWhoseGroupCannotBeBuiltFailsWithConnectionFailed() {
        var result = RedisClient.start(config(),
                                       Option.none(),
                                       () -> {
                                           throw new IllegalStateException(BOOM);
                                       })
                                .await(WAIT);

        assertThat(TestKinds.kind(result)).isEqualTo("ConnectionFailed");
        assertThat(TestKinds.message(result)).contains(BOOM);
    }

    @Test
    void connectionWhoseGroupCannotBeBuiltFailsWithConnectionFailed() {
        var result = RedisConnection.connectOwning(config(),
                                                   Option.none(),
                                                   () -> {
                                                       throw new IllegalStateException(BOOM);
                                                   })
                                    .await(WAIT);

        assertThat(TestKinds.kind(result)).isEqualTo("ConnectionFailed");
        assertThat(TestKinds.message(result)).contains(BOOM);
    }
}
