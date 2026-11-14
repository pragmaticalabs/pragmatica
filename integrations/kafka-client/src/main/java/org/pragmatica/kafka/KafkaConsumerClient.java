package org.pragmatica.kafka;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.pragmatica.lang.Functions.ThrowingFn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

import static org.pragmatica.lang.Unit.unit;


/// Promise bridge over a manually assigned Kafka consumer: no group membership, no rebalancing. `group` names only the
/// commit target.
///
/// THREAD CONFINEMENT: a Kafka consumer is not thread-safe. This client owns one platform thread; the consumer is
/// created on the caller's thread (construction only) and from then on touched only on the owned thread. Every
/// operation is queued to it in call order and returns a promise, so callers on any thread are safe, but a
/// long `poll` delays operations queued behind it. Each operation re-checks the thread (`onOwner`) and fails with
/// [KafkaError.General#THREAD_CONFINEMENT_VIOLATED] instead of touching the consumer from elsewhere. Chained steps run
/// on the virtual-thread pool, not on the owned thread.
///
/// Guarantees: `commitSync` resolves after the broker stored the offsets (the group's committed offset is the next
/// record to read, per Kafka convention); consumption position is not committed implicitly. Blocking calls other than
/// `poll` are bounded by `requestTimeout`.
public final class KafkaConsumerClient {
    private final Consumer<byte[], byte[]> consumer;
    private final Duration closeTimeout;
    private final OwnedThread owner = OwnedThread.ownedThread("kafka-consumer-owner");
    private final Lifecycle lifecycle = Lifecycle.lifecycle("kafka-consumer");

    private KafkaConsumerClient(Consumer<byte[], byte[]> consumer, Duration closeTimeout) {
        this.consumer = consumer;
        this.closeTimeout = closeTimeout;
    }

    public static Result<KafkaConsumerClient> kafkaConsumerClient(KafkaConfig config, String group) {
        return Result.lift(KafkaError::fromOperation,
                           () -> new KafkaConsumer<byte[], byte[]>(config.consumerProperties(group)))
                     .map(consumer -> kafkaConsumerClient(consumer, config));
    }

    static KafkaConsumerClient kafkaConsumerClient(Consumer<byte[], byte[]> consumer, KafkaConfig config) {
        return new KafkaConsumerClient(consumer,
                                       config.closeTimeout().duration());
    }

    /// Replaces the assignment. Positions of newly assigned partitions start at the committed offset, else at the
    /// beginning (`auto.offset.reset` default is `latest` in Kafka; callers that care `seek` explicitly).
    public Promise<Unit> assign(List<Partition> partitions) {
        return submit(c -> assignTo(c, partitions));
    }

    public Promise<Unit> seek(Partition partition, long offset) {
        return submit(c -> seekTo(c, partition, offset));
    }

    public Promise<Unit> seekToBeginning(List<Partition> partitions) {
        return submit(c -> rewind(c, partitions));
    }

    /// Blocks the owned thread up to `timeout`; returns early with the records available, possibly none.
    public Promise<List<InboundRecord>> poll(TimeSpan timeout) {
        return submit(c -> fetched(c, timeout));
    }

    /// Commits explicit offsets (each the next record to read) for this client's group, synchronously.
    public Promise<Unit> commitSync(Map<Partition, Long> offsets) {
        return submit(c -> commit(c, offsets));
    }

    /// Committed offset of this client's group for each requested partition; absent when never committed.
    public Promise<Map<Partition, Option<Long>>> committed(List<Partition> partitions) {
        return submit(c -> committedOf(c, partitions));
    }

    public Promise<Map<Partition, Long>> beginningOffsets(List<Partition> partitions) {
        return submit(c -> offsets(c.beginningOffsets(kafkaPartitions(partitions))));
    }

    public Promise<Map<Partition, Long>> endOffsets(List<Partition> partitions) {
        return submit(c -> offsets(c.endOffsets(kafkaPartitions(partitions))));
    }

    /// Idempotent and bounded. `wakeup` (the one thread-safe consumer call) cuts a running `poll` short; the close
    /// then runs on the owned thread behind whatever is queued, for at most `closeTimeout`.
    public Promise<Unit> close() {
        return lifecycle.closeAsync(this::closeConsumer)
                        .continueOn();
    }

    Result<Unit> probeConfinement() {
        return onOwner(_ -> unit());
    }

    Promise<Unit> probeOnOwner() {
        return submit(_ -> unit());
    }

    private <T> Promise<T> submit(ThrowingFn1<T, Consumer<byte[], byte[]>> operation) {
        return lifecycle.isClosing()
               ? KafkaError.General.CLOSED.promise()
               : owner.submit(() -> onOwner(operation))
                      .continueOn();
    }

    private <T> Result<T> onOwner(ThrowingFn1<T, Consumer<byte[], byte[]>> operation) {
        return owner.isOwner()
               ? Result.lift(KafkaError::fromOperation, () -> operation.apply(consumer))
               : KafkaError.General.THREAD_CONFINEMENT_VIOLATED.result();
    }

    private Promise<Unit> closeConsumer() {
        return Result.lift(KafkaError::fromOperation, consumer::wakeup)
                     .async()
                     .flatMap(_ -> owner.submit(this::closeOnOwner))
                     .onResultRun(owner::shutdown);
    }

    private Result<Unit> closeOnOwner() {
        return onOwner(c -> closeWith(c));
    }

    private Unit closeWith(Consumer<byte[], byte[]> c) {
        c.close(closeTimeout);

        return unit();
    }

    private static Unit assignTo(Consumer<byte[], byte[]> c, List<Partition> partitions) {
        c.assign(kafkaPartitions(partitions));

        return unit();
    }

    private static Unit seekTo(Consumer<byte[], byte[]> c, Partition partition, long offset) {
        c.seek(kafkaPartition(partition), offset);

        return unit();
    }

    private static Unit rewind(Consumer<byte[], byte[]> c, List<Partition> partitions) {
        c.seekToBeginning(kafkaPartitions(partitions));

        return unit();
    }

    private static Unit commit(Consumer<byte[], byte[]> c, Map<Partition, Long> offsets) {
        c.commitSync(offsets.entrySet()
                            .stream()
                            .collect(Collectors.toMap(e -> kafkaPartition(e.getKey()),
                                                      e -> new OffsetAndMetadata(e.getValue()))));

        return unit();
    }

    private static List<InboundRecord> fetched(Consumer<byte[], byte[]> c, TimeSpan timeout) {
        return StreamSupport.stream(c.poll(timeout.duration()).spliterator(),
                                    false)
                            .map(KafkaConsumerClient::inbound)
                            .toList();
    }

    private static Map<Partition, Option<Long>> committedOf(Consumer<byte[], byte[]> c, List<Partition> partitions) {
        return c.committed(kafkaPartitions(partitions))
                .entrySet()
                .stream()
                .collect(Collectors.toMap(e -> partitionOf(e.getKey()),
                                          e -> Option.option(e.getValue()).map(OffsetAndMetadata::offset)));
    }

    private static Map<Partition, Long> offsets(Map<TopicPartition, Long> raw) {
        return raw.entrySet()
                  .stream()
                  .collect(Collectors.toMap(e -> partitionOf(e.getKey()),
                                            Map.Entry::getValue));
    }

    private static InboundRecord inbound(ConsumerRecord<byte[], byte[]> record) {
        return InboundRecord.inboundRecord(partitionOf(new TopicPartition(record.topic(), record.partition())),
                                           record.offset(),
                                           record.timestamp(),
                                           Option.option(record.key()),
                                           record.value());
    }

    static Partition partitionOf(TopicPartition tp) {
        return Partition.partition(tp.topic(),
                                   tp.partition())
                        .unwrap();
    }

    static TopicPartition kafkaPartition(Partition partition) {
        return new TopicPartition(partition.topic(), partition.partition());
    }

    static List<TopicPartition> kafkaPartitions(List<Partition> partitions) {
        return partitions.stream()
                         .map(KafkaConsumerClient::kafkaPartition)
                         .toList();
    }
}
