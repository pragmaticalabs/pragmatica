package org.pragmatica.kafka;

import java.time.Duration;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;


/// Promise bridge over a Kafka producer.
///
/// Threads: `send` returns at once. `producer.send` can block up to `maxBlock` on a metadata wait, so it runs on one
/// owned submitter thread, which also fixes the order in which sends reach the producer: records sent to the same
/// partition are appended in call order. The broker callback runs on a Kafka I/O thread and only resolves the promise;
/// every step chained on a returned promise runs on the built-in virtual-thread pool, never on a Kafka thread.
///
/// Guarantee per `send`, at the strength of `acks` (`ALL` also enables the idempotent producer, which removes
/// producer-retry duplicates within one producer session):
/// success = the broker acknowledged the append (`NONE`: not acknowledged, the receipt offset is -1);
/// [KafkaError.Retriable] = rejected or never sent, repeating is safe;
/// [KafkaError.OutcomeUnknown] = the record may be in the log; [KafkaError.Fatal] = repeating cannot help.
public final class KafkaProducerClient {
    private final Producer<byte[], byte[]> producer;
    private final Duration closeTimeout;
    private final OwnedThread submitter = OwnedThread.ownedThread("kafka-producer-submit");
    private final Lifecycle lifecycle = Lifecycle.lifecycle("kafka-producer");

    private KafkaProducerClient(Producer<byte[], byte[]> producer, Duration closeTimeout) {
        this.producer = producer;
        this.closeTimeout = closeTimeout;
    }

    public static Result<KafkaProducerClient> kafkaProducerClient(KafkaConfig config) {
        return Result.lift(KafkaError::fromOperation,
                           () -> new KafkaProducer<byte[], byte[]>(config.producerProperties()))
                     .map(producer -> kafkaProducerClient(producer, config));
    }

    static KafkaProducerClient kafkaProducerClient(Producer<byte[], byte[]> producer, KafkaConfig config) {
        return new KafkaProducerClient(producer,
                                       config.closeTimeout().duration());
    }

    public Promise<SendReceipt> send(OutboundRecord record) {
        return lifecycle.isClosing()
               ? KafkaError.General.CLOSED.promise()
               : sendOpen(record);
    }

    /// Barrier: resolves when every record sent before it was acknowledged or failed. Blocks the submitter thread, so
    /// sends issued meanwhile queue behind it.
    public Promise<Unit> flush() {
        return submitter.submit(() -> Result.lift(KafkaError::fromOperation, producer::flush))
                        .continueOn();
    }

    /// Idempotent. Pending records are flushed for at most `closeTimeout`; those still unsent then fail as
    /// [KafkaError.OutcomeUnknown]. Resolves when the producer is closed.
    public Promise<Unit> close() {
        return lifecycle.close(this::closeProducer)
                        .continueOn();
    }

    private Promise<SendReceipt> sendOpen(OutboundRecord record) {
        var receipt = Promise.<SendReceipt> promise();

        return submitter.submit(() -> dispatch(record, receipt))
                        .flatMap(_ -> receipt)
                        .continueOn();
    }

    private Result<Unit> dispatch(OutboundRecord record, Promise<SendReceipt> receipt) {
        return Result.lift(KafkaError::fromSend,
                           () -> producer.send(toKafka(record),
                                               (metadata, failure) -> complete(receipt, metadata, failure)))
                     .mapToUnit();
    }

    @Contract
    private void closeProducer() {
        producer.close(closeTimeout);
        submitter.shutdown();
    }

    private static ProducerRecord<byte[], byte[]> toKafka(OutboundRecord record) {
        return new ProducerRecord<>(record.topic(),
                                    record.partition().or((Integer) null),
                                    record.key().or((byte[]) null),
                                    record.value());
    }

    /// Kafka producer callback: exactly one of `metadata` and `failure` is set.
    @Contract
    private static void complete(Promise<SendReceipt> receipt, RecordMetadata metadata, Exception failure) {
        receipt.resolve(Option.option(failure).fold(() -> receiptOf(metadata),
                                                    failed -> KafkaError.fromSend(failed).result()));
    }

    private static Result<SendReceipt> receiptOf(RecordMetadata metadata) {
        return Partition.partition(metadata.topic(),
                                   metadata.partition())
                        .map(partition -> SendReceipt.sendReceipt(partition,
                                                                  metadata.offset(),
                                                                  metadata.timestamp()));
    }
}
