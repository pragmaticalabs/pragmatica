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
/// owned submitter thread, which also fixes the order in which sends reach the producer. Records sent to the same
/// partition are appended in call order within one producer session: `ALL` uses the idempotent producer (ordered across
/// retries), `NONE` and `LEADER` run with one request in flight per connection (`max.in.flight.requests.per.connection`
/// = 1). The submitter queue is bounded ([OwnedThread#DEFAULT_CAPACITY]); a send past the bound fails as
/// [KafkaError.Retriable] without blocking the caller. The broker callback runs on a Kafka I/O thread and only resolves the promise;
/// every step chained on a returned promise runs on the built-in virtual-thread pool, never on a Kafka thread.
///
/// Guarantee per `send`, at the strength of `acks` (`ALL` also enables the idempotent producer, which removes
/// producer-retry duplicates within one producer session):
/// success = the broker acknowledged the append (`LEADER`, `ALL`); with `NONE` success means only that the record was
/// handed to the producer, not acknowledged, and the receipt offset is -1;
/// [KafkaError.Retriable] = never sent (metadata wait timed out, submitter queue full), repeating is safe;
/// [KafkaError.OutcomeUnknown] = the record may be in the log, including every error the broker returns for a send,
/// because the producer retries internally and an earlier attempt may have been appended;
/// [KafkaError.General#CLOSED] = the client was closing and the record was not sent;
/// [KafkaError.Fatal] = repeating cannot help.
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

    /// Idempotent. Records already handed to the producer are flushed for at most `closeTimeout`; those still unsent then
    /// fail as [KafkaError.OutcomeUnknown]. Sends accepted but not yet handed over (queued on the submitter, or blocked
    /// in the metadata wait) fail as [KafkaError.General#CLOSED]. Resolves when the producer is closed.
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

    /// A queued send that reaches the front after the close began is refused here, before the producer is touched: the
    /// producer may not be closed yet (the closer thread races the submitter), and sending then would hand the record
    /// over after `close()` was called.
    private Result<Unit> dispatch(OutboundRecord record, Promise<SendReceipt> receipt) {
        return lifecycle.isClosing()
               ? KafkaError.General.CLOSED.result()
               : handOver(record, receipt);
    }

    private Result<Unit> handOver(OutboundRecord record, Promise<SendReceipt> receipt) {
        return Result.lift(this::classifySend,
                           () -> producer.send(toKafka(record),
                                               (metadata, failure) -> complete(receipt, metadata, failure)))
                     .mapToUnit();
    }

    /// A send that runs after the producer closed (queued on the submitter) or is aborted by the close (blocked in the
    /// metadata wait) is refused by the producer itself; while closing, that refusal is `CLOSED`.
    private KafkaError classifySend(Throwable failure) {
        return lifecycle.isClosing()
               ? KafkaError.fromSendWhileClosing(failure)
               : KafkaError.fromSend(failure);
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
