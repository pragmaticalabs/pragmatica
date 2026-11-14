package org.pragmatica.kafka;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;

import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Partitioner;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.DisconnectException;
import org.apache.kafka.common.errors.NotEnoughReplicasException;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;


class KafkaProducerClientTest {
    private static final KafkaConfig CONFIG = KafkaConfig.kafkaConfig("a:9092", "c").unwrap();

    private static OutboundRecord record() {
        return OutboundRecord.outboundRecord("t",
                                             Option.some(0),
                                             Option.none(),
                                             new byte[]{1})
                             .unwrap();
    }

    private static final class FirstPartition implements Partitioner {
        @Override
        public int partition(String topic,
                             Object key,
                             byte[] keyBytes,
                             Object value,
                             byte[] valueBytes,
                             Cluster cluster) {
            return 0;
        }

        @Override
        public void close() {}

        @Override
        public void configure(Map<String, ?> configs) {}
    }

    private static MockProducer<byte[], byte[]> producer(boolean autoComplete) {
        return new MockProducer<>(autoComplete,
                                  new FirstPartition(),
                                  new ByteArraySerializer(),
                                  new ByteArraySerializer());
    }

    private static Cause failureOf(KafkaProducerClient client) {
        return client.send(record())
                     .await()
                     .fold(cause -> cause,
                           _ -> fail("expected a failure"));
    }

    @Test
    void send_acknowledged_returnsReceipt() {
        var client = KafkaProducerClient.kafkaProducerClient(producer(true), CONFIG);
        var receipt = client.send(record()).await().unwrap();

        assertThat(receipt.partition()).isEqualTo(new Partition("t", 0));
        assertThat(receipt.offset()).isZero();
    }

    @Test
    void send_brokerRejectsAfterInternalRetries_failsOutcomeUnknown() {
        var mock = producer(false);
        var client = KafkaProducerClient.kafkaProducerClient(mock, CONFIG);
        var pending = client.send(record());

        awaitSent(mock);
        mock.errorNext(new NotEnoughReplicasException("isr"));
        assertThat(pending.await().<Cause> fold(c -> c, _ -> fail("expected failure"))).isInstanceOf(KafkaError.OutcomeUnknown.class);
    }

    @Test
    void send_connectionLostAfterHandoff_failsOutcomeUnknown() {
        var mock = producer(false);
        var client = KafkaProducerClient.kafkaProducerClient(mock, CONFIG);
        var pending = client.send(record());

        awaitSent(mock);
        mock.errorNext(new DisconnectException("gone"));
        assertThat(pending.await().<Cause> fold(c -> c, _ -> fail("expected failure"))).isInstanceOf(KafkaError.OutcomeUnknown.class);
    }

    @Test
    void send_authenticationFailure_failsFatal() {
        var mock = producer(false);
        var client = KafkaProducerClient.kafkaProducerClient(mock, CONFIG);
        var pending = client.send(record());

        awaitSent(mock);
        mock.errorNext(new AuthenticationException("bad"));
        assertThat(pending.await().<Cause> fold(c -> c, _ -> fail("expected failure"))).isInstanceOf(KafkaError.Fatal.class);
    }

    @Test
    void send_synchronousRetriableFailureAfterDispatch_failsOutcomeUnknown() {
        var client = KafkaProducerClient.kafkaProducerClient(new ThrowingSend(), CONFIG);

        assertThat(failureOf(client)).isInstanceOf(KafkaError.OutcomeUnknown.class);
    }

    @Test
    void send_withoutPartitionOrKey_handsKafkaNeither() {
        var mock = new Capturing();
        var client = KafkaProducerClient.kafkaProducerClient(mock, CONFIG);
        var bare = OutboundRecord.outboundRecord("t", Option.none(), Option.none(), new byte[]{1}).unwrap();

        assertThat(client.send(bare).await().isSuccess()).isTrue();
        assertThat(mock.seen.getFirst().partition()).isNull();
        assertThat(mock.seen.getFirst().key()).isNull();
    }

    @Test
    void send_withPartitionAndKey_handsKafkaBoth() {
        var mock = new Capturing();
        var client = KafkaProducerClient.kafkaProducerClient(mock, CONFIG);
        var keyed = OutboundRecord.outboundRecord("t", Option.some(1), Option.some(new byte[]{7}), new byte[]{1}).unwrap();

        assertThat(client.send(keyed).await().isSuccess()).isTrue();
        assertThat(mock.seen.getFirst().partition()).isEqualTo(1);
        assertThat(mock.seen.getFirst().key()).containsExactly(7);
        assertThat(mock.seen.getFirst().value()).containsExactly(1);
    }

    @Test
    void send_twice_receiptOffsetsAdvance() {
        var client = KafkaProducerClient.kafkaProducerClient(producer(true), CONFIG);

        assertThat(client.send(record()).await().unwrap().offset()).isZero();
        assertThat(client.send(record()).await().unwrap().offset()).isEqualTo(1L);
    }

    @Test
    void flush_completesRecordsStillInFlight() {
        var mock = producer(false);
        var client = KafkaProducerClient.kafkaProducerClient(mock, CONFIG);
        var pending = client.send(record());

        awaitSent(mock);
        assertThat(client.flush().await().isSuccess()).isTrue();
        assertThat(mock.flushed()).isTrue();
        assertThat(pending.await(timeSpan(5).seconds()).isSuccess()).isTrue();
    }

    @Test
    void send_afterClose_isRefusedWithoutSending() {
        var mock = producer(true);
        var client = KafkaProducerClient.kafkaProducerClient(mock, CONFIG);

        client.close().await();
        assertThat(failureOf(client)).isSameAs(KafkaError.General.CLOSED);
        assertThat(mock.history()).isEmpty();
    }

    @Test
    void close_calledTwice_returnsTheSameOutcome() {
        var mock = producer(true);
        var client = KafkaProducerClient.kafkaProducerClient(mock, CONFIG);

        assertThat(client.close().await().isSuccess()).isTrue();
        assertThat(client.close().await().isSuccess()).isTrue();
        assertThat(mock.closed()).isTrue();
    }

    /// Producer whose `flush` holds the submitter thread until released.
    private static final class HeldFlush extends MockProducer<byte[], byte[]> {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch holding = new CountDownLatch(1);

        HeldFlush() {
            super(true, new FirstPartition(), new ByteArraySerializer(), new ByteArraySerializer());
        }

        @Override
        public void flush() {
            holding.countDown();
            awaitQuietly(release);
        }
    }

    /// Producer whose `send` blocks like a metadata wait, then fails the way Kafka does when closed meanwhile.
    private static final class ClosedMidSend extends MockProducer<byte[], byte[]> {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch inSend = new CountDownLatch(1);

        ClosedMidSend() {
            super(true, new FirstPartition(), new ByteArraySerializer(), new ByteArraySerializer());
        }

        @Override
        public synchronized Future<RecordMetadata> send(ProducerRecord<byte[], byte[]> record, Callback callback) {
            inSend.countDown();
            awaitQuietly(release);
            throw new KafkaException("Producer closed while send in progress");
        }
    }

    /// Producer that keeps the records exactly as the client handed them over.
    private static final class Capturing extends MockProducer<byte[], byte[]> {
        final List<ProducerRecord<byte[], byte[]>> seen = new CopyOnWriteArrayList<>();

        Capturing() {
            super(true, new FirstPartition(), new ByteArraySerializer(), new ByteArraySerializer());
        }

        @Override
        public synchronized Future<RecordMetadata> send(ProducerRecord<byte[], byte[]> record, Callback callback) {
            seen.add(record);
            return super.send(record, callback);
        }
    }

    /// Producer whose `send` fails synchronously with a retriable broker-side error.
    private static final class ThrowingSend extends MockProducer<byte[], byte[]> {
        ThrowingSend() {
            super(true, new FirstPartition(), new ByteArraySerializer(), new ByteArraySerializer());
        }

        @Override
        public synchronized Future<RecordMetadata> send(ProducerRecord<byte[], byte[]> record, Callback callback) {
            throw new DisconnectException("sync");
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
    void send_queuedBehindWorkWhenClientCloses_failsClosedAndIsNeverSent() throws InterruptedException {
        var mock = new HeldFlush();
        var client = KafkaProducerClient.kafkaProducerClient(mock, CONFIG);
        var flushing = client.flush();

        assertThat(mock.holding.await(5, TimeUnit.SECONDS)).isTrue();
        var queued = client.send(record());
        var closing = client.close();

        mock.release.countDown();
        assertThat(queued.await().<Cause> fold(c -> c, _ -> fail("expected failure"))).isSameAs(KafkaError.General.CLOSED);
        assertThat(mock.history()).isEmpty();
        assertThat(flushing.await().isSuccess()).isTrue();
        assertThat(closing.await().isSuccess()).isTrue();
    }

    @Test
    void send_blockedInMetadataWaitWhenClientCloses_failsClosed() throws InterruptedException {
        var mock = new ClosedMidSend();
        var client = KafkaProducerClient.kafkaProducerClient(mock, CONFIG);
        var blocked = client.send(record());

        assertThat(mock.inSend.await(5, TimeUnit.SECONDS)).isTrue();
        var closing = client.close();

        mock.release.countDown();
        assertThat(blocked.await().<Cause> fold(c -> c, _ -> fail("expected failure"))).isSameAs(KafkaError.General.CLOSED);
        assertThat(closing.await().isSuccess()).isTrue();
    }

    private static void awaitSent(MockProducer<byte[], byte[]> mock) {
        var deadline = System.nanoTime() + 5_000_000_000L;

        while (mock.history().isEmpty() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        assertThat(mock.history()).hasSize(1);
    }
}
