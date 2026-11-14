package org.pragmatica.kafka;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;

import org.apache.kafka.clients.consumer.ConsumerRecords;
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

    /// Consumer whose `poll` holds the owned thread until released, and counts how many polls reached it.
    private static final class HeldPoll extends MockConsumer<byte[], byte[]> {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch holding = new CountDownLatch(1);
        final AtomicInteger polls = new AtomicInteger();

        HeldPoll() {
            super("earliest");
        }

        @Override
        public ConsumerRecords<byte[], byte[]> poll(Duration timeout) {
            polls.incrementAndGet();
            holding.countDown();
            awaitQuietly(release);

            return ConsumerRecords.empty();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void close_pollsQueuedBehindARunningPoll_failClosedWithoutRunning() throws InterruptedException {
        var consumer = new HeldPoll();
        var client = KafkaConsumerClient.kafkaConsumerClient(consumer, CONFIG);
        var running = client.poll(timeSpan(30).seconds());

        assertThat(consumer.holding.await(5, TimeUnit.SECONDS)).isTrue();
        var queued = client.poll(timeSpan(30).seconds());
        var closing = client.close();

        consumer.release.countDown();
        assertThat(queued.await(timeSpan(10).seconds()).<Cause> fold(c -> c, _ -> fail("expected CLOSED"))).isSameAs(KafkaError.General.CLOSED);
        assertThat(consumer.polls.get()).isEqualTo(1);
        assertThat(running.await(timeSpan(10).seconds()).isSuccess()).isTrue();
        assertThat(closing.await(timeSpan(10).seconds()).isSuccess()).isTrue();
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
