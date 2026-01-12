package org.pragmatica.aether.infra.streaming;

import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.Set;

/**
 * Kafka-like streaming service with partitioned topics and consumer groups.
 * Provides at-least-once delivery semantics with manual offset commits.
 */
public interface StreamingService extends Slice {
    // ========== Topic Management ==========
    /**
     * Creates a new topic with specified number of partitions.
     *
     * @param topic      Topic name
     * @param partitions Number of partitions
     * @return Unit on success
     */
    Promise<Unit> createTopic(String topic, int partitions);

    /**
     * Creates a new topic with default number of partitions.
     *
     * @param topic Topic name
     * @return Unit on success
     */
    Promise<Unit> createTopic(String topic);

    /**
     * Deletes a topic and all its messages.
     *
     * @param topic Topic name
     * @return true if topic existed and was deleted
     */
    Promise<Boolean> deleteTopic(String topic);

    /**
     * Lists all topics.
     *
     * @return Set of topic information
     */
    Promise<Set<TopicInfo>> listTopics();

    /**
     * Gets information about a specific topic.
     *
     * @param topic Topic name
     * @return Option containing topic info if exists
     */
    Promise<Option<TopicInfo>> getTopic(String topic);

    // ========== Publishing ==========
    /**
     * Publishes a message to a topic. Partition is determined by key hash or round-robin.
     *
     * @param topic Topic name
     * @param key   Optional message key (used for partition routing)
     * @param value Message payload
     * @return The published message with assigned partition and offset
     */
    Promise<StreamMessage> publish(String topic, Option<String> key, byte[] value);

    /**
     * Publishes a message to a specific partition.
     *
     * @param topic     Topic name
     * @param partition Target partition
     * @param key       Optional message key
     * @param value     Message payload
     * @return The published message with assigned offset
     */
    Promise<StreamMessage> publishToPartition(String topic, int partition, Option<String> key, byte[] value);

    /**
     * Publishes multiple messages to a topic atomically.
     *
     * @param topic    Topic name
     * @param messages List of key-value pairs to publish
     * @return List of published messages with assigned partitions and offsets
     */
    Promise<List<StreamMessage>> publishBatch(String topic, List<KeyValue> messages);

    /**
     * Key-value pair for batch publishing.
     */
    record KeyValue(Option<String> key, byte[] value) {
        public static KeyValue keyValue(String key, byte[] value) {
            return new KeyValue(Option.option(key), value);
        }

        public static KeyValue keyValue(byte[] value) {
            return new KeyValue(Option.none(), value);
        }
    }

    // ========== Consumer Groups ==========
    /**
     * Creates or joins a consumer group.
     *
     * @param groupId Consumer group identifier
     * @return Unit on success
     */
    Promise<Unit> createConsumerGroup(String groupId);

    /**
     * Subscribes a consumer group to a topic.
     *
     * @param groupId Consumer group identifier
     * @param topic   Topic to subscribe to
     * @return Unit on success
     */
    Promise<Unit> subscribe(String groupId, String topic);

    /**
     * Unsubscribes a consumer group from a topic.
     *
     * @param groupId Consumer group identifier
     * @param topic   Topic to unsubscribe from
     * @return Unit on success
     */
    Promise<Unit> unsubscribe(String groupId, String topic);

    /**
     * Lists all consumer groups.
     *
     * @return Set of consumer group information
     */
    Promise<Set<ConsumerGroupInfo>> listConsumerGroups();

    /**
     * Gets information about a specific consumer group.
     *
     * @param groupId Consumer group identifier
     * @return Option containing group info if exists
     */
    Promise<Option<ConsumerGroupInfo>> getConsumerGroup(String groupId);

    /**
     * Deletes a consumer group.
     *
     * @param groupId Consumer group identifier
     * @return true if group existed and was deleted
     */
    Promise<Boolean> deleteConsumerGroup(String groupId);

    // ========== Consuming ==========
    /**
     * Polls for messages from subscribed topics.
     *
     * @param groupId  Consumer group identifier
     * @param maxCount Maximum number of messages to return
     * @return List of messages (may be empty if none available)
     */
    Promise<List<StreamMessage>> poll(String groupId, int maxCount);

    /**
     * Commits the offset for a message, marking it as processed.
     *
     * @param groupId Consumer group identifier
     * @param message Message to commit offset for
     * @return Unit on success
     */
    Promise<Unit> commit(String groupId, StreamMessage message);

    /**
     * Commits offsets for multiple messages.
     *
     * @param groupId  Consumer group identifier
     * @param messages Messages to commit offsets for
     * @return Unit on success
     */
    Promise<Unit> commitBatch(String groupId, List<StreamMessage> messages);

    /**
     * Seeks to a specific offset in a partition.
     *
     * @param groupId   Consumer group identifier
     * @param topic     Topic name
     * @param partition Partition number
     * @param offset    Target offset
     * @return Unit on success
     */
    Promise<Unit> seek(String groupId, String topic, int partition, long offset);

    /**
     * Seeks to the beginning of a partition.
     *
     * @param groupId   Consumer group identifier
     * @param topic     Topic name
     * @param partition Partition number
     * @return Unit on success
     */
    Promise<Unit> seekToBeginning(String groupId, String topic, int partition);

    /**
     * Seeks to the end of a partition.
     *
     * @param groupId   Consumer group identifier
     * @param topic     Topic name
     * @param partition Partition number
     * @return Unit on success
     */
    Promise<Unit> seekToEnd(String groupId, String topic, int partition);

    // ========== Offset Management ==========
    /**
     * Gets the current committed offset for a topic-partition.
     *
     * @param groupId   Consumer group identifier
     * @param topic     Topic name
     * @param partition Partition number
     * @return Option containing committed offset if exists
     */
    Promise<Option<Long>> getCommittedOffset(String groupId, String topic, int partition);

    /**
     * Gets the latest offset (end) for a topic-partition.
     *
     * @param topic     Topic name
     * @param partition Partition number
     * @return Latest offset
     */
    Promise<Long> getLatestOffset(String topic, int partition);

    /**
     * Gets the earliest offset (beginning) for a topic-partition.
     *
     * @param topic     Topic name
     * @param partition Partition number
     * @return Earliest offset
     */
    Promise<Long> getEarliestOffset(String topic, int partition);

    // ========== Factory Methods ==========
    /**
     * Creates an in-memory streaming service with default configuration.
     */
    static StreamingService streamingService() {
        return InMemoryStreamingService.inMemoryStreamingService();
    }

    /**
     * Creates an in-memory streaming service with custom configuration.
     */
    static StreamingService streamingService(StreamingConfig config) {
        return InMemoryStreamingService.inMemoryStreamingService(config);
    }

    // ========== Slice Lifecycle ==========
    @Override
    default Promise<Unit> start() {
        return Promise.success(Unit.unit());
    }

    @Override
    default Promise<Unit> stop() {
        return Promise.success(Unit.unit());
    }

    @Override
    default List<SliceMethod< ?, ?>> methods() {
        return List.of();
    }
}
