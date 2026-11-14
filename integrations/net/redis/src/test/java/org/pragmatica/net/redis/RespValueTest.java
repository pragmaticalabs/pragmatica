package org.pragmatica.net.redis;

import org.junit.jupiter.api.Test;
import org.pragmatica.net.redis.RespValue.IntegerReply;
import org.pragmatica.net.redis.RespValue.SimpleString;

import static org.assertj.core.api.Assertions.assertThat;

/// Reply interpretation: each accessor accepts exactly the reply its command produces.
class RespValueTest {
    @Test
    void asOkAcceptsOnlyPlusOk() {
        assertThat(SimpleString.simpleString("OK").asOk().isSuccess()).isTrue();
        assertThat(TestKinds.kind(SimpleString.simpleString("QUEUED").asOk())).isEqualTo("UnexpectedReply");
        assertThat(TestKinds.kind(SimpleString.simpleString("PONG").asOk())).isEqualTo("UnexpectedReply");
    }

    @Test
    void asPongAcceptsOnlyPlusPong() {
        assertThat(SimpleString.simpleString("PONG").asPong().isSuccess()).isTrue();
        assertThat(TestKinds.kind(SimpleString.simpleString("OK").asPong())).isEqualTo("UnexpectedReply");
    }

    @Test
    void asFlagIsTrueOnlyForExactlyOne() {
        assertThat(IntegerReply.integerReply(1).asFlag().unwrap()).isTrue();
        assertThat(IntegerReply.integerReply(0).asFlag().unwrap()).isFalse();
        assertThat(IntegerReply.integerReply(2).asFlag().unwrap()).isFalse();
        assertThat(IntegerReply.integerReply(-1).asFlag().unwrap()).isFalse();
    }
}
