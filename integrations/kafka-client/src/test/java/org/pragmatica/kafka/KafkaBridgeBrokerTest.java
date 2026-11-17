package org.pragmatica.kafka;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.testing.RequiresDocker;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.KafkaContainer;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;


/// Real broker (Testcontainers, Apache Kafka image): the bridge against the thing it wraps.
@RequiresDocker
class KafkaBridgeBrokerTest {
    private static final String IMAGE = "apache/kafka:4.1.0";
    private static KafkaContainer broker;
    private static KafkaConfig config;

    @BeforeAll
    static void startBroker() {
        // #3257: CI start failures (exit 126, missed RUNNING line) are rare and load-dependent; retry the start and give it CI-sized room.
        broker = new KafkaContainer(IMAGE).withStartupAttempts(3)
                                          .withStartupTimeout(Duration.ofMinutes(3));
        broker.start();
        config = KafkaConfig.kafkaConfig(broker.getBootstrapServers().replace("PLAINTEXT://", ""),
                                         "bridge-test")
                            .unwrap();
    }

    @AfterAll
    static void stopBroker() {
        broker.stop();
    }

    private static String newTopic(int partitions) {
        var topic = "t-" + UUID.randomUUID();
        var admin = KafkaAdmin.kafkaAdmin(config).unwrap();

        admin.createTopic(topic, partitions, (short) 1).await().onFailure(c -> fail(c.message()));
        admin.close().await();

        return topic;
    }

    private static OutboundRecord record(String topic, int partition, String value) {
        return OutboundRecord.outboundRecord(topic,
                                             Option.some(partition),
                                             Option.none(),
                                             value.getBytes())
                             .unwrap();
    }

    private static List<InboundRecord> pollUntil(KafkaConsumerClient consumer, int expected) {
        var all = new ArrayList<InboundRecord>();
        var deadline = System.nanoTime() + 30_000_000_000L;

        while (all.size() < expected && System.nanoTime() < deadline) {
            all.addAll(consumer.poll(timeSpan(500).millis()).await().unwrap());
        }

        return all;
    }

    @Test
    void produceThenFetch_preservesPerPartitionOrder() {
        var topic = newTopic(3);
        var producer = KafkaProducerClient.kafkaProducerClient(config).unwrap();
        var sends = new ArrayList<Promise<SendReceipt>>();

        for (var i = 0; i < 30; i++) {
            sends.add(producer.send(record(topic, i % 3, "p" + (i % 3) + "-" + i)));
        }

        sends.forEach(p -> assertThat(p.await().isSuccess()).isTrue());
        var partitions = List.of(Partition.partition(topic, 0).unwrap(),
                                 Partition.partition(topic, 1).unwrap(),
                                 Partition.partition(topic, 2).unwrap());
        var consumer = KafkaConsumerClient.kafkaConsumerClient(config, "g-" + topic).unwrap();

        consumer.assign(partitions).await();
        consumer.seekToBeginning(partitions).await();
        var fetched = pollUntil(consumer, 30);

        assertThat(fetched).hasSize(30);
        for (var p = 0; p < 3; p++) {
            var partition = p;
            var values = fetched.stream()
                                .filter(r -> r.partition()
                                              .partition() == partition)
                                .map(r -> new String(r.value()))
                                .toList();
            var expected = new ArrayList<String>();

            for (var i = partition; i < 30; i += 3) {
                expected.add("p" + partition + "-" + i);
            }

            assertThat(values).containsExactlyElementsOf(expected);
        }

        consumer.close().await();
        producer.close().await();
    }

    @Test
    void commit_thenCommittedOffset_roundTripsThroughConsumerAndAdmin() {
        var topic = newTopic(1);
        var group = "g-" + topic;
        var partition = Partition.partition(topic, 0).unwrap();
        var consumer = KafkaConsumerClient.kafkaConsumerClient(config, group).unwrap();
        var admin = KafkaAdmin.kafkaAdmin(config).unwrap();

        consumer.assign(List.of(partition)).await();
        assertThat(consumer.committed(List.of(partition)).await().unwrap()).containsEntry(partition, Option.none());
        assertThat(consumer.commitSync(Map.of(partition, 7L)).await().isSuccess()).isTrue();
        assertThat(consumer.committed(List.of(partition)).await().unwrap()).containsEntry(partition, Option.some(7L));
        assertThat(admin.committedOffset(group, partition).await().unwrap()).isEqualTo(Option.some(7L));
        assertThat(admin.committedOffset("other-" + group, partition).await().unwrap()).isEqualTo(Option.none());
        consumer.close().await();
        admin.close().await();
    }

    @Test
    void offsetsAndLayout_reflectProducedRecords() {
        var topic = newTopic(2);
        var producer = KafkaProducerClient.kafkaProducerClient(config).unwrap();

        for (var i = 0; i < 4; i++) {
            producer.send(record(topic, 1, "v" + i)).await();
        }

        var p0 = Partition.partition(topic, 0).unwrap();
        var p1 = Partition.partition(topic, 1).unwrap();
        var consumer = KafkaConsumerClient.kafkaConsumerClient(config, "g-" + topic).unwrap();
        var admin = KafkaAdmin.kafkaAdmin(config).unwrap();

        assertThat(consumer.beginningOffsets(List.of(p0, p1)).await().unwrap()).containsEntry(p0, 0L)
                  .containsEntry(p1, 0L);
        assertThat(consumer.endOffsets(List.of(p0, p1)).await().unwrap()).containsEntry(p0, 0L).containsEntry(p1, 4L);
        var layout = admin.describeTopic(topic).await().unwrap();

        assertThat(layout.partitions()).hasSize(2);
        assertThat(layout.partitions().getFirst().leader()).isGreaterThanOrEqualTo(0);
        consumer.close().await();
        admin.close().await();
        producer.close().await();
    }

    @Test
    void seek_positionsTheNextPoll() {
        var topic = newTopic(1);
        var producer = KafkaProducerClient.kafkaProducerClient(config).unwrap();

        for (var i = 0; i < 5; i++) {
            producer.send(record(topic, 0, "v" + i)).await();
        }

        var partition = Partition.partition(topic, 0).unwrap();
        var consumer = KafkaConsumerClient.kafkaConsumerClient(config, "g-" + topic).unwrap();

        consumer.assign(List.of(partition)).await();
        consumer.seek(partition, 3).await();
        var fetched = pollUntil(consumer, 2);

        assertThat(fetched).extracting(InboundRecord::offset).containsExactly(3L, 4L);
        consumer.close().await();
        producer.close().await();
    }

    @Test
    void close_whilePolling_endsThePollAndFinishesWithinBound() {
        var topic = newTopic(1);
        var partition = Partition.partition(topic, 0).unwrap();
        var consumer = KafkaConsumerClient.kafkaConsumerClient(config, "g-" + topic).unwrap();

        consumer.assign(List.of(partition)).await();
        var polling = consumer.poll(timeSpan(60).seconds());
        var started = System.nanoTime();
        var closed = consumer.close().await(timeSpan(30).seconds());

        assertThat(closed.isSuccess()).isTrue();
        assertThat(polling.await(timeSpan(30).seconds()).isFailure()).isTrue();
        assertThat((System.nanoTime() - started) / 1_000_000_000L).isLessThan(30);
        assertThat(consumer.close().await().isSuccess()).isTrue();
    }

    /// Probes the shared broker until it answers a metadata-changing call, so a test that paused it leaves it serving.
    private static void awaitBrokerServing() {
        var deadline = System.nanoTime() + 60_000_000_000L;

        while (System.nanoTime() < deadline) {
            var admin = KafkaAdmin.kafkaAdmin(config).unwrap();
            var probe = admin.createTopic("ready-" + UUID.randomUUID(), 1, (short) 1).await(timeSpan(5).seconds());

            admin.close().await();
            if (probe.isSuccess()) {
                return;
            }
        }
        fail("shared broker not serving 60 s after unpause");
    }

    /// Pauses the SHARED broker (no second container to start, #3257); unpause plus a readiness wait run in `finally`,
    /// so the broker is never left paused or half-awake for the tests that follow.
    @Test
    void send_brokerPausedMidSend_neverReportsSuccess() {
        var cfg = KafkaConfig.kafkaConfig(config.bootstrapServers(),
                                          KafkaSecurity.Plaintext.plaintext(),
                                          Acks.ALL,
                                          "pause-test",
                                          timeSpan(2).seconds(),
                                          timeSpan(4).seconds(),
                                          timeSpan(3).seconds(),
                                          timeSpan(3).seconds())
                             .unwrap();
        var topic = newTopic(1);
        var producer = KafkaProducerClient.kafkaProducerClient(cfg).unwrap();

        try {
            assertThat(producer.send(record(topic, 0, "warm")).await().isSuccess()).isTrue();
            broker.getDockerClient().pauseContainerCmd(broker.getContainerId()).exec();
            var outcome = producer.send(record(topic, 0, "lost")).await(timeSpan(30).seconds());

            broker.getDockerClient().unpauseContainerCmd(broker.getContainerId()).exec();
            assertThat(outcome.isFailure()).isTrue();
            assertThat(outcome.<Cause> fold(c -> c, _ -> fail("must not succeed"))).isInstanceOfAny(KafkaError.OutcomeUnknown.class,
                                                                                                    KafkaError.Retriable.class);
        } finally {
            unpauseIfPaused();
            producer.close().await();
            awaitBrokerServing();
        }
    }

    private static void unpauseIfPaused() {
        var paused = broker.getDockerClient().inspectContainerCmd(broker.getContainerId()).exec().getState().getPaused();

        if (Boolean.TRUE.equals(paused)) {
            broker.getDockerClient().unpauseContainerCmd(broker.getContainerId()).exec();
        }
    }
}
