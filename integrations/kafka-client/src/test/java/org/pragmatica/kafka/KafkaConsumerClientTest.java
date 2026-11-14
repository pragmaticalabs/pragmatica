package org.pragmatica.kafka;

import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;

import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;


class KafkaConsumerClientTest {
    private static final KafkaConfig CONFIG = KafkaConfig.kafkaConfig("a:9092", "c").unwrap();
    private static final Partition P0 = new Partition("t", 0);

    private static MockConsumer<byte[], byte[]> mock() {
        return new MockConsumer<>("earliest");
    }

    @Test
    void probeConfinement_offTheOwnedThread_isRefused() {
        var client = KafkaConsumerClient.kafkaConsumerClient(mock(), CONFIG);
        var result = client.probeConfinement();

        assertThat(result.<Cause> fold(c -> c, _ -> fail("expected refusal"))).isSameAs(KafkaError.General.THREAD_CONFINEMENT_VIOLATED);
    }

    @Test
    void probeOnOwner_onTheOwnedThread_succeeds() {
        var client = KafkaConsumerClient.kafkaConsumerClient(mock(), CONFIG);

        assertThat(client.probeOnOwner().await().isSuccess()).isTrue();
    }

    @Test
    void assignSeekCommit_roundTripOnMock() {
        var consumer = mock();
        var client = KafkaConsumerClient.kafkaConsumerClient(consumer, CONFIG);

        consumer.updateBeginningOffsets(Map.of(new TopicPartition("t", 0), 0L));
        consumer.updateEndOffsets(Map.of(new TopicPartition("t", 0), 5L));
        assertThat(client.assign(List.of(P0)).await().isSuccess()).isTrue();
        assertThat(client.seek(P0, 3).await().isSuccess()).isTrue();
        assertThat(client.commitSync(Map.of(P0, 4L)).await().isSuccess()).isTrue();
        assertThat(client.committed(List.of(P0)).await().unwrap()).containsEntry(P0, Option.some(4L));
        assertThat(client.beginningOffsets(List.of(P0)).await().unwrap()).containsEntry(P0, 0L);
        assertThat(client.endOffsets(List.of(P0)).await().unwrap()).containsEntry(P0, 5L);
    }

    @Test
    void close_calledTwice_isIdempotentAndRefusesLaterOperations() {
        var consumer = mock();
        var client = KafkaConsumerClient.kafkaConsumerClient(consumer, CONFIG);

        assertThat(client.close().await(timeSpan(10).seconds()).isSuccess()).isTrue();
        assertThat(client.close().await(timeSpan(10).seconds()).isSuccess()).isTrue();
        assertThat(consumer.closed()).isTrue();
        assertThat(client.poll(timeSpan(1).millis()).await().<Cause> fold(c -> c, _ -> fail("expected refusal"))).isSameAs(KafkaError.General.CLOSED);
    }
}
