package org.pragmatica.kafka;

import org.pragmatica.lang.Cause;

import org.apache.kafka.clients.admin.MockAdminClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;


class KafkaAdminTest {
    private static final KafkaConfig CONFIG = KafkaConfig.kafkaConfig("a:9092", "c").unwrap();

    @Test
    void describeTopic_unknownTopic_failsRetriableNotFatal() {
        var admin = KafkaAdmin.kafkaAdmin(new MockAdminClient.Builder().numBrokers(1).build(), CONFIG);
        var cause = admin.describeTopic("missing").await().<Cause> fold(c -> c, _ -> fail("expected a failure"));

        assertThat(cause).isInstanceOf(KafkaError.Retriable.class);
    }
}
