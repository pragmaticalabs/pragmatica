package org.pragmatica.kafka;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;

import static org.pragmatica.lang.Option.option;


/// Promise bridge over the Kafka admin client: topic creation, partition metadata and committed offsets of any group.
///
/// Threads: the admin client is thread-safe and non-blocking; each call returns at once and its result is delivered by
/// a Kafka admin thread, which only resolves the promise. Chained steps run on the virtual-thread pool.
/// Blocking waits are bounded by `requestTimeout` (`default.api.timeout.ms`).
public final class KafkaAdmin {
    private final Admin admin;
    private final Duration closeTimeout;
    private final Lifecycle lifecycle = Lifecycle.lifecycle("kafka-admin");

    private KafkaAdmin(Admin admin, Duration closeTimeout) {
        this.admin = admin;
        this.closeTimeout = closeTimeout;
    }

    public static Result<KafkaAdmin> kafkaAdmin(KafkaConfig config) {
        return Result.lift(KafkaError::fromOperation,
                           () -> Admin.create(config.adminProperties()))
                     .map(admin -> kafkaAdmin(admin, config));
    }

    static KafkaAdmin kafkaAdmin(Admin admin, KafkaConfig config) {
        return new KafkaAdmin(admin,
                              config.closeTimeout().duration());
    }

    /// Creates a topic; an already existing topic fails as [KafkaError.Fatal].
    public Promise<Unit> createTopic(String topic, int partitions, short replicationFactor) {
        return guarded(() -> admin.createTopics(List.of(new NewTopic(topic, partitions, replicationFactor)))
                                  .all()
                                  .thenApply(_ -> Unit.unit()));
    }

    public Promise<TopicLayout> describeTopic(String topic) {
        return guarded(() -> admin.describeTopics(List.of(topic))
                                  .allTopicNames()
                                  .thenApply(KafkaAdmin::firstLayout));
    }

    /// Committed offset of `group` for `partition`; absent when the group never committed there.
    public Promise<Option<Long>> committedOffset(String group, Partition partition) {
        var tp = KafkaConsumerClient.kafkaPartition(partition);

        return guarded(() -> admin.listConsumerGroupOffsets(group)
                                  .partitionsToOffsetAndMetadata()
                                  .thenApply(offsets -> offsetOf(offsets, tp)));
    }

    /// Idempotent; bounded by `closeTimeout`.
    public Promise<Unit> close() {
        return lifecycle.close(this::closeAdmin)
                        .continueOn();
    }

    private <T> Promise<T> guarded(Supplier<KafkaFuture<T>> call) {
        return lifecycle.isClosing()
               ? KafkaError.General.CLOSED.promise()
               : bridge(call);
    }

    private <T> Promise<T> bridge(Supplier<KafkaFuture<T>> call) {
        var promise = Promise.<T> promise();

        return Result.lift(KafkaError::fromOperation, call::get)
                     .async()
                     .flatMap(future -> completeFrom(future, promise))
                     .continueOn();
    }

    private static <T> Promise<T> completeFrom(KafkaFuture<T> future, Promise<T> promise) {
        future.whenComplete((value, failure) -> deliver(promise, value, failure));

        return promise;
    }

    @Contract
    private static <T> void deliver(Promise<T> promise, T value, Throwable failure) {
        promise.resolve(option(failure).fold(() -> Result.success(value),
                                             f -> KafkaError.fromOperation(unwrap(f)).result()));
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof ExecutionException && failure.getCause() != null
               ? failure.getCause()
               : failure;
    }

    @Contract
    private void closeAdmin() {
        admin.close(closeTimeout);
    }

    private static Option<Long> offsetOf(Map<TopicPartition, OffsetAndMetadata> offsets, TopicPartition tp) {
        return option(offsets.get(tp)).map(OffsetAndMetadata::offset);
    }

    private static TopicLayout firstLayout(Map<String, TopicDescription> described) {
        var description = described.values().iterator().next();

        return TopicLayout.topicLayout(description.name(),
                                       description.partitions().stream().map(KafkaAdmin::layoutOf).toList());
    }

    private static TopicLayout.PartitionLayout layoutOf(TopicPartitionInfo info) {
        return TopicLayout.PartitionLayout.partitionLayout(info.partition(),
                                                           option(info.leader()).map(Node::id).or(-1),
                                                           info.replicas().stream().map(Node::id).toList());
    }
}
