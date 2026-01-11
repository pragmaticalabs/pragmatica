package org.pragmatica.aether.infra.streaming;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.stream.Collectors;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;

/**
 * In-memory implementation of StreamingService.
 * Provides Kafka-like semantics with partitioned topics and consumer groups.
 */
final class InMemoryStreamingService implements StreamingService {
    private static final StreamingConfig DEFAULT_CONFIG = new StreamingConfig("default",
                                                                              4,
                                                                              TimeSpan.timeSpan(24)
                                                                                      .hours(),
                                                                              1_048_576,
                                                                              TimeSpan.timeSpan(30)
                                                                                      .seconds());

    private final StreamingConfig config;
    private final ConcurrentHashMap<String, Topic> topics = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConsumerGroup> consumerGroups = new ConcurrentHashMap<>();
    private final AtomicLong partitionCounter = new AtomicLong(0);

    private InMemoryStreamingService(StreamingConfig config) {
        this.config = config;
    }

    static InMemoryStreamingService inMemoryStreamingService() {
        return new InMemoryStreamingService(StreamingConfig.streamingConfig()
                                                           .fold(err -> DEFAULT_CONFIG, c -> c));
    }

    static InMemoryStreamingService inMemoryStreamingService(StreamingConfig config) {
        return new InMemoryStreamingService(config);
    }

    // ========== Topic Management ==========
    @Override
    public Promise<Unit> createTopic(String topic, int partitions) {
        return option(topics.putIfAbsent(topic,
                                         new Topic(topic,
                                                   partitions,
                                                   Instant.now())))
                     .fold(() -> Promise.success(unit()),
                           existing -> StreamingError.topicAlreadyExists(topic)
                                                     .promise());
    }

    @Override
    public Promise<Unit> createTopic(String topic) {
        return createTopic(topic, config.defaultPartitions());
    }

    @Override
    public Promise<Boolean> deleteTopic(String topic) {
        return Promise.success(option(topics.remove(topic))
                                     .isPresent());
    }

    @Override
    public Promise<Set<TopicInfo>> listTopics() {
        return Promise.success(topics.values()
                                     .stream()
                                     .map(Topic::toInfo)
                                     .collect(Collectors.toSet()));
    }

    @Override
    public Promise<Option<TopicInfo>> getTopic(String topic) {
        return Promise.success(option(topics.get(topic))
                                     .map(Topic::toInfo));
    }

    // ========== Publishing ==========
    @Override
    public Promise<StreamMessage> publish(String topic, Option<String> key, byte[] value) {
        return validateMessageSize(value)
                                  .flatMap(v -> getTopicOrFail(topic))
                                  .map(t -> publishToTopic(t, key, value));
    }

    private StreamMessage publishToTopic(Topic topic, Option<String> key, byte[] value) {
        int partition = selectPartition(key, topic.partitions);
        return topic.publish(partition, key, value);
    }

    @Override
    public Promise<StreamMessage> publishToPartition(String topic, int partition, Option<String> key, byte[] value) {
        return validateMessageSize(value)
                                  .flatMap(v -> getTopicOrFail(topic))
                                  .flatMap(t -> validatePartition(t, partition))
                                  .map(t -> t.publish(partition, key, value));
    }

    @Override
    public Promise<List<StreamMessage>> publishBatch(String topic, List<KeyValue> messages) {
        return getTopicOrFail(topic)
                             .flatMap(t -> validateBatchSize(messages)
                                                            .map(msgs -> publishBatchToTopic(t, msgs)));
    }

    private List<StreamMessage> publishBatchToTopic(Topic topic, List<KeyValue> messages) {
        var results = new ArrayList<StreamMessage>(messages.size());
        for (var msg : messages) {
            int partition = selectPartition(msg.key(), topic.partitions);
            results.add(topic.publish(partition, msg.key(), msg.value()));
        }
        return results;
    }

    // ========== Consumer Groups ==========
    @Override
    public Promise<Unit> createConsumerGroup(String groupId) {
        consumerGroups.putIfAbsent(groupId, new ConsumerGroup(groupId));
        return Promise.success(unit());
    }

    @Override
    public Promise<Unit> subscribe(String groupId, String topic) {
        return getConsumerGroupOrFail(groupId)
                                     .flatMap(group -> getTopicOrFail(topic)
                                                                     .map(t -> subscribeGroupToTopic(group, topic, t)));
    }

    private Unit subscribeGroupToTopic(ConsumerGroup group, String topic, Topic t) {
        group.subscribe(topic, t.partitions);
        return unit();
    }

    @Override
    public Promise<Unit> unsubscribe(String groupId, String topic) {
        return getConsumerGroupOrFail(groupId)
                                     .map(group -> unsubscribeGroupFromTopic(group, topic));
    }

    private Unit unsubscribeGroupFromTopic(ConsumerGroup group, String topic) {
        group.unsubscribe(topic);
        return unit();
    }

    @Override
    public Promise<Set<ConsumerGroupInfo>> listConsumerGroups() {
        return Promise.success(consumerGroups.values()
                                             .stream()
                                             .map(ConsumerGroup::toInfo)
                                             .collect(Collectors.toSet()));
    }

    @Override
    public Promise<Option<ConsumerGroupInfo>> getConsumerGroup(String groupId) {
        return Promise.success(option(consumerGroups.get(groupId))
                                     .map(ConsumerGroup::toInfo));
    }

    @Override
    public Promise<Boolean> deleteConsumerGroup(String groupId) {
        return Promise.success(option(consumerGroups.remove(groupId))
                                     .isPresent());
    }

    // ========== Consuming ==========
    @Override
    public Promise<List<StreamMessage>> poll(String groupId, int maxCount) {
        return getConsumerGroupOrFail(groupId)
                                     .map(group -> pollFromGroup(group, maxCount));
    }

    private List<StreamMessage> pollFromGroup(ConsumerGroup group, int maxCount) {
        var results = new ArrayList<StreamMessage>(maxCount);
        int remaining = maxCount;
        for (String topicName : group.subscribedTopics) {
            if (remaining <= 0) break;
            remaining = pollFromTopic(group, topicName, remaining, results);
        }
        return results;
    }

    private int pollFromTopic(ConsumerGroup group, String topicName, int remaining, List<StreamMessage> results) {
        return option(topics.get(topicName))
                     .fold(() -> remaining,
                           topic -> pollPartitions(group, topicName, topic, remaining, results));
    }

    private int pollPartitions(ConsumerGroup group,
                               String topicName,
                               Topic topic,
                               int remaining,
                               List<StreamMessage> results) {
        int left = remaining;
        for (int partition = 0; partition < topic.partitions && left > 0; partition++) {
            left = pollPartition(group, topicName, topic, partition, left, results);
        }
        return left;
    }

    private int pollPartition(ConsumerGroup group,
                              String topicName,
                              Topic topic,
                              int partition,
                              int remaining,
                              List<StreamMessage> results) {
        long currentOffset = group.getCurrentOffset(topicName, partition);
        var messages = topic.getMessages(partition, currentOffset, remaining);
        for (var msg : messages) {
            results.add(msg);
            group.setCurrentOffset(topicName, partition, msg.offset() + 1);
        }
        return remaining - messages.size();
    }

    @Override
    public Promise<Unit> commit(String groupId, StreamMessage message) {
        return getConsumerGroupOrFail(groupId)
                                     .map(group -> commitMessageOffset(group, message));
    }

    private Unit commitMessageOffset(ConsumerGroup group, StreamMessage message) {
        group.commitOffset(message.topic(), message.partition(), message.offset() + 1);
        return unit();
    }

    @Override
    public Promise<Unit> commitBatch(String groupId, List<StreamMessage> messages) {
        return getConsumerGroupOrFail(groupId)
                                     .map(group -> commitAllOffsets(group, messages));
    }

    private Unit commitAllOffsets(ConsumerGroup group, List<StreamMessage> messages) {
        for (var msg : messages) {
            group.commitOffset(msg.topic(), msg.partition(), msg.offset() + 1);
        }
        return unit();
    }

    @Override
    public Promise<Unit> seek(String groupId, String topic, int partition, long offset) {
        return getConsumerGroupOrFail(groupId)
                                     .flatMap(group -> getTopicOrFail(topic)
                                                                     .map(t -> new SeekContext(group, t)))
                                     .flatMap(ctx -> validatePartition(ctx.topic, partition)
                                                                      .map(t -> seekToOffset(ctx.group,
                                                                                             topic,
                                                                                             partition,
                                                                                             offset)));
    }

    private Unit seekToOffset(ConsumerGroup group, String topic, int partition, long offset) {
        group.setCurrentOffset(topic, partition, offset);
        return unit();
    }

    private record SeekContext(ConsumerGroup group, Topic topic) {}

    @Override
    public Promise<Unit> seekToBeginning(String groupId, String topic, int partition) {
        return getTopicOrFail(topic)
                             .flatMap(t -> validatePartition(t, partition))
                             .flatMap(t -> seek(groupId,
                                                topic,
                                                partition,
                                                t.getEarliestOffset(partition)));
    }

    @Override
    public Promise<Unit> seekToEnd(String groupId, String topic, int partition) {
        return getTopicOrFail(topic)
                             .flatMap(t -> validatePartition(t, partition))
                             .flatMap(t -> seek(groupId,
                                                topic,
                                                partition,
                                                t.getLatestOffset(partition)));
    }

    // ========== Offset Management ==========
    @Override
    public Promise<Option<Long>> getCommittedOffset(String groupId, String topic, int partition) {
        return getConsumerGroupOrFail(groupId)
                                     .map(group -> group.getCommittedOffset(topic, partition));
    }

    @Override
    public Promise<Long> getLatestOffset(String topic, int partition) {
        return getTopicOrFail(topic)
                             .flatMap(t -> validatePartition(t, partition))
                             .map(t -> t.getLatestOffset(partition));
    }

    @Override
    public Promise<Long> getEarliestOffset(String topic, int partition) {
        return getTopicOrFail(topic)
                             .flatMap(t -> validatePartition(t, partition))
                             .map(t -> t.getEarliestOffset(partition));
    }

    // ========== Lifecycle ==========
    @Override
    public Promise<Unit> stop() {
        topics.clear();
        consumerGroups.clear();
        return Promise.success(unit());
    }

    // ========== Internal Helpers ==========
    private Promise<Topic> getTopicOrFail(String topic) {
        return option(topics.get(topic))
                     .fold(() -> StreamingError.topicNotFound(topic)
                                               .<Topic> promise(),
                           Promise::success);
    }

    private Promise<ConsumerGroup> getConsumerGroupOrFail(String groupId) {
        return option(consumerGroups.get(groupId))
                     .fold(() -> StreamingError.consumerGroupNotFound(groupId)
                                               .<ConsumerGroup> promise(),
                           Promise::success);
    }

    private Promise<Topic> validatePartition(Topic topic, int partition) {
        if (partition < 0 || partition >= topic.partitions) {
            return StreamingError.invalidPartition(topic.name, partition, topic.partitions)
                                 .promise();
        }
        return Promise.success(topic);
    }

    private Promise<byte[]> validateMessageSize(byte[] value) {
        if (value.length > config.maxMessageSize()) {
            return StreamingError.messageTooLarge(value.length,
                                                  config.maxMessageSize())
                                 .promise();
        }
        return Promise.success(value);
    }

    private Promise<List<KeyValue>> validateBatchSize(List<KeyValue> messages) {
        for (var msg : messages) {
            if (msg.value().length > config.maxMessageSize()) {
                return StreamingError.messageTooLarge(msg.value().length,
                                                      config.maxMessageSize())
                                     .promise();
            }
        }
        return Promise.success(messages);
    }

    private int selectPartition(Option<String> key, int numPartitions) {
        return key.fold(() -> (int)(partitionCounter.getAndIncrement() % numPartitions),
                        k -> Math.abs(k.hashCode() % numPartitions));
    }

    // ========== Internal Classes ==========
    private static final class Topic {
        private final String name;
        private final int partitions;
        private final Instant createdAt;
        private final List<List<StreamMessage>> partitionData;
        private final AtomicLongArray offsets;

        Topic(String name, int partitions, Instant createdAt) {
            this.name = name;
            this.partitions = partitions;
            this.createdAt = createdAt;
            this.partitionData = new ArrayList<>(partitions);
            this.offsets = new AtomicLongArray(partitions);
            for (int i = 0; i < partitions; i++) {
                partitionData.add(Collections.synchronizedList(new ArrayList<>()));
            }
        }

        StreamMessage publish(int partition, Option<String> key, byte[] value) {
            long offset = offsets.getAndIncrement(partition);
            var message = StreamMessage.streamMessage(name, partition, offset, key, value, Instant.now(), none());
            partitionData.get(partition)
                         .add(message);
            return message;
        }

        List<StreamMessage> getMessages(int partition, long fromOffset, int maxCount) {
            var partitionMessages = partitionData.get(partition);
            var results = new ArrayList<StreamMessage>(maxCount);
            synchronized (partitionMessages) {
                for (var msg : partitionMessages) {
                    if (msg.offset() >= fromOffset && results.size() < maxCount) {
                        results.add(msg);
                    }
                }
            }
            return results;
        }

        long getLatestOffset(int partition) {
            return offsets.get(partition);
        }

        long getEarliestOffset(int partition) {
            var partitionMessages = partitionData.get(partition);
            synchronized (partitionMessages) {
                return partitionMessages.isEmpty()
                       ? 0
                       : partitionMessages.get(0)
                                          .offset();
            }
        }

        TopicInfo toInfo() {
            return TopicInfo.topicInfo(name, partitions, createdAt);
        }
    }

    private static final class ConsumerGroup {
        private final String groupId;
        private final Set<String> subscribedTopics = ConcurrentHashMap.newKeySet();
        private final ConcurrentHashMap<String, ConcurrentHashMap<Integer, Long>> committedOffsets = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, ConcurrentHashMap<Integer, Long>> currentOffsets = new ConcurrentHashMap<>();

        ConsumerGroup(String groupId) {
            this.groupId = groupId;
        }

        void subscribe(String topic, int partitions) {
            subscribedTopics.add(topic);
            committedOffsets.putIfAbsent(topic, new ConcurrentHashMap<>());
            currentOffsets.putIfAbsent(topic, new ConcurrentHashMap<>());
            for (int i = 0; i < partitions; i++) {
                committedOffsets.get(topic)
                                .putIfAbsent(i, 0L);
                currentOffsets.get(topic)
                              .putIfAbsent(i, 0L);
            }
        }

        void unsubscribe(String topic) {
            subscribedTopics.remove(topic);
        }

        long getCurrentOffset(String topic, int partition) {
            return option(currentOffsets.get(topic))
                         .flatMap(m -> option(m.get(partition)))
                         .fold(() -> 0L,
                               o -> o);
        }

        void setCurrentOffset(String topic, int partition, long offset) {
            currentOffsets.computeIfAbsent(topic,
                                           k -> new ConcurrentHashMap<>())
                          .put(partition, offset);
        }

        void commitOffset(String topic, int partition, long offset) {
            committedOffsets.computeIfAbsent(topic,
                                             k -> new ConcurrentHashMap<>())
                            .put(partition, offset);
        }

        Option<Long> getCommittedOffset(String topic, int partition) {
            return option(committedOffsets.get(topic))
                         .flatMap(m -> option(m.get(partition)));
        }

        ConsumerGroupInfo toInfo() {
            var offsetsCopy = new HashMap<String, Map<Integer, Long>>();
            committedOffsets.forEach((topic, partitionOffsets) -> offsetsCopy.put(topic, Map.copyOf(partitionOffsets)));
            return ConsumerGroupInfo.consumerGroupInfo(groupId, Set.copyOf(subscribedTopics), Map.copyOf(offsetsCopy));
        }
    }
}
