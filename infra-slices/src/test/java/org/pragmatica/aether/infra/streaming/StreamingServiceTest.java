package org.pragmatica.aether.infra.streaming;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;

class StreamingServiceTest {
    private StreamingService service;

    @BeforeEach
    void setUp() {
        service = StreamingService.streamingService();
    }

    // ========== Topic Management Tests ==========

    @Test
    void createTopic_succeeds_withValidName() {
        service.createTopic("test-topic", 4)
               .await()
               .onFailureRun(Assertions::fail);

        service.getTopic("test-topic")
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(optInfo -> {
                   assertThat(optInfo.isPresent()).isTrue();
                   optInfo.onPresent(info -> {
                       assertThat(info.name()).isEqualTo("test-topic");
                       assertThat(info.partitions()).isEqualTo(4);
                   });
               });
    }

    @Test
    void createTopic_fails_whenTopicExists() {
        service.createTopic("existing-topic")
               .await()
               .onFailureRun(Assertions::fail);

        service.createTopic("existing-topic")
               .await()
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isInstanceOf(StreamingError.TopicAlreadyExists.class));
    }

    @Test
    void deleteTopic_returnsTrue_whenTopicExists() {
        service.createTopic("to-delete")
               .await()
               .onFailureRun(Assertions::fail);

        service.deleteTopic("to-delete")
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(deleted -> assertThat(deleted).isTrue());
    }

    @Test
    void deleteTopic_returnsFalse_whenTopicNotExists() {
        service.deleteTopic("non-existent")
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(deleted -> assertThat(deleted).isFalse());
    }

    @Test
    void listTopics_returnsAllTopics() {
        service.createTopic("topic-1").await();
        service.createTopic("topic-2").await();
        service.createTopic("topic-3").await();

        service.listTopics()
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(topics -> assertThat(topics).hasSize(3));
    }

    // ========== Publishing Tests ==========

    @Test
    void publish_succeeds_withValidMessage() {
        service.createTopic("publish-test", 4)
               .await()
               .onFailureRun(Assertions::fail);

        var payload = "Hello, World!".getBytes(StandardCharsets.UTF_8);

        service.publish("publish-test", option("key1"), payload)
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(msg -> {
                   assertThat(msg.topic()).isEqualTo("publish-test");
                   assertThat(msg.valueAsString()).isEqualTo("Hello, World!");
                   assertThat(msg.key().isPresent()).isTrue();
                   assertThat(msg.offset()).isGreaterThanOrEqualTo(0);
               });
    }

    @Test
    void publish_fails_whenTopicNotExists() {
        var payload = "test".getBytes(StandardCharsets.UTF_8);

        service.publish("non-existent", none(), payload)
               .await()
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isInstanceOf(StreamingError.TopicNotFound.class));
    }

    @Test
    void publishToPartition_succeeds_withValidPartition() {
        service.createTopic("partition-test", 4)
               .await()
               .onFailureRun(Assertions::fail);

        var payload = "Partition message".getBytes(StandardCharsets.UTF_8);

        service.publishToPartition("partition-test", 2, none(), payload)
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(msg -> {
                   assertThat(msg.partition()).isEqualTo(2);
                   assertThat(msg.offset()).isEqualTo(0);
               });
    }

    @Test
    void publishToPartition_fails_withInvalidPartition() {
        service.createTopic("partition-test-2", 4)
               .await()
               .onFailureRun(Assertions::fail);

        var payload = "test".getBytes(StandardCharsets.UTF_8);

        service.publishToPartition("partition-test-2", 10, none(), payload)
               .await()
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isInstanceOf(StreamingError.InvalidPartition.class));
    }

    @Test
    void publishBatch_succeeds_withMultipleMessages() {
        service.createTopic("batch-test", 4)
               .await()
               .onFailureRun(Assertions::fail);

        var messages = List.of(
            StreamingService.KeyValue.keyValue("key1", "msg1".getBytes(StandardCharsets.UTF_8)),
            StreamingService.KeyValue.keyValue("key2", "msg2".getBytes(StandardCharsets.UTF_8)),
            StreamingService.KeyValue.keyValue("msg3".getBytes(StandardCharsets.UTF_8))
        );

        service.publishBatch("batch-test", messages)
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(published -> assertThat(published).hasSize(3));
    }

    // ========== Consumer Group Tests ==========

    @Test
    void createConsumerGroup_succeeds() {
        service.createConsumerGroup("test-group")
               .await()
               .onFailureRun(Assertions::fail);

        service.getConsumerGroup("test-group")
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(optGroup -> assertThat(optGroup.isPresent()).isTrue());
    }

    @Test
    void subscribe_succeeds_toExistingTopic() {
        service.createTopic("subscribe-topic")
               .await()
               .onFailureRun(Assertions::fail);

        service.createConsumerGroup("subscribe-group")
               .await()
               .onFailureRun(Assertions::fail);

        service.subscribe("subscribe-group", "subscribe-topic")
               .await()
               .onFailureRun(Assertions::fail);

        service.getConsumerGroup("subscribe-group")
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(optGroup -> {
                   assertThat(optGroup.isPresent()).isTrue();
                   optGroup.onPresent(group ->
                                          assertThat(group.subscribedTopics()).contains("subscribe-topic"));
               });
    }

    @Test
    void subscribe_fails_toNonExistentTopic() {
        service.createConsumerGroup("fail-group")
               .await()
               .onFailureRun(Assertions::fail);

        service.subscribe("fail-group", "non-existent-topic")
               .await()
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isInstanceOf(StreamingError.TopicNotFound.class));
    }

    @Test
    void deleteConsumerGroup_succeeds_whenGroupExists() {
        service.createConsumerGroup("delete-group")
               .await()
               .onFailureRun(Assertions::fail);

        service.deleteConsumerGroup("delete-group")
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(deleted -> assertThat(deleted).isTrue());
    }

    // ========== Consuming Tests ==========

    @Test
    void poll_returnsMessages_afterPublish() {
        service.createTopic("poll-topic", 1)
               .await()
               .onFailureRun(Assertions::fail);

        service.createConsumerGroup("poll-group")
               .await()
               .onFailureRun(Assertions::fail);

        service.subscribe("poll-group", "poll-topic")
               .await()
               .onFailureRun(Assertions::fail);

        // Publish messages
        service.publish("poll-topic", none(), "message1".getBytes(StandardCharsets.UTF_8)).await();
        service.publish("poll-topic", none(), "message2".getBytes(StandardCharsets.UTF_8)).await();
        service.publish("poll-topic", none(), "message3".getBytes(StandardCharsets.UTF_8)).await();

        // Poll messages
        service.poll("poll-group", 10)
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(messages -> {
                   assertThat(messages).hasSize(3);
                   assertThat(messages.get(0).valueAsString()).isEqualTo("message1");
                   assertThat(messages.get(1).valueAsString()).isEqualTo("message2");
                   assertThat(messages.get(2).valueAsString()).isEqualTo("message3");
               });
    }

    @Test
    void poll_returnsEmpty_whenNoMessages() {
        service.createTopic("empty-topic", 1)
               .await()
               .onFailureRun(Assertions::fail);

        service.createConsumerGroup("empty-group")
               .await()
               .onFailureRun(Assertions::fail);

        service.subscribe("empty-group", "empty-topic")
               .await()
               .onFailureRun(Assertions::fail);

        service.poll("empty-group", 10)
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(messages -> assertThat(messages).isEmpty());
    }

    @Test
    void commit_updatesOffset() {
        service.createTopic("commit-topic", 1)
               .await()
               .onFailureRun(Assertions::fail);

        service.createConsumerGroup("commit-group")
               .await()
               .onFailureRun(Assertions::fail);

        service.subscribe("commit-group", "commit-topic")
               .await()
               .onFailureRun(Assertions::fail);

        // Publish and poll
        service.publish("commit-topic", none(), "msg".getBytes(StandardCharsets.UTF_8)).await();

        var messages = service.poll("commit-group", 1)
                              .await()
                              .fold(err -> List.<StreamMessage>of(), m -> m);

        assertThat(messages).hasSize(1);

        // Commit
        service.commit("commit-group", messages.get(0))
               .await()
               .onFailureRun(Assertions::fail);

        // Verify committed offset
        service.getCommittedOffset("commit-group", "commit-topic", 0)
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(optOffset -> {
                   assertThat(optOffset.isPresent()).isTrue();
                   optOffset.onPresent(offset -> assertThat(offset).isEqualTo(1L));
               });
    }

    // ========== Seek Tests ==========

    @Test
    void seek_updatesConsumerPosition() {
        service.createTopic("seek-topic", 1)
               .await()
               .onFailureRun(Assertions::fail);

        service.createConsumerGroup("seek-group")
               .await()
               .onFailureRun(Assertions::fail);

        service.subscribe("seek-group", "seek-topic")
               .await()
               .onFailureRun(Assertions::fail);

        // Publish multiple messages
        for (int i = 0; i < 5; i++) {
            service.publish("seek-topic", none(), ("msg" + i).getBytes(StandardCharsets.UTF_8)).await();
        }

        // Seek to offset 3
        service.seek("seek-group", "seek-topic", 0, 3)
               .await()
               .onFailureRun(Assertions::fail);

        // Poll should start from offset 3
        service.poll("seek-group", 10)
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(messages -> {
                   assertThat(messages).hasSize(2);
                   assertThat(messages.get(0).valueAsString()).isEqualTo("msg3");
                   assertThat(messages.get(1).valueAsString()).isEqualTo("msg4");
               });
    }

    @Test
    void seekToBeginning_resetsPosition() {
        service.createTopic("seek-begin-topic", 1)
               .await()
               .onFailureRun(Assertions::fail);

        service.createConsumerGroup("seek-begin-group")
               .await()
               .onFailureRun(Assertions::fail);

        service.subscribe("seek-begin-group", "seek-begin-topic")
               .await()
               .onFailureRun(Assertions::fail);

        // Publish and consume
        service.publish("seek-begin-topic", none(), "first".getBytes(StandardCharsets.UTF_8)).await();
        service.publish("seek-begin-topic", none(), "second".getBytes(StandardCharsets.UTF_8)).await();

        service.poll("seek-begin-group", 10).await();

        // Seek to beginning
        service.seekToBeginning("seek-begin-group", "seek-begin-topic", 0)
               .await()
               .onFailureRun(Assertions::fail);

        // Should re-read messages
        service.poll("seek-begin-group", 10)
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(messages -> {
                   assertThat(messages).hasSize(2);
                   assertThat(messages.get(0).valueAsString()).isEqualTo("first");
               });
    }

    // ========== Offset Management Tests ==========

    @Test
    void getLatestOffset_returnsCurrentEnd() {
        service.createTopic("offset-topic", 1)
               .await()
               .onFailureRun(Assertions::fail);

        // Publish 3 messages
        service.publish("offset-topic", none(), "a".getBytes(StandardCharsets.UTF_8)).await();
        service.publish("offset-topic", none(), "b".getBytes(StandardCharsets.UTF_8)).await();
        service.publish("offset-topic", none(), "c".getBytes(StandardCharsets.UTF_8)).await();

        service.getLatestOffset("offset-topic", 0)
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(offset -> assertThat(offset).isEqualTo(3L));
    }

    @Test
    void getEarliestOffset_returnsBeginning() {
        service.createTopic("earliest-topic", 1)
               .await()
               .onFailureRun(Assertions::fail);

        service.publish("earliest-topic", none(), "a".getBytes(StandardCharsets.UTF_8)).await();

        service.getEarliestOffset("earliest-topic", 0)
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(offset -> assertThat(offset).isEqualTo(0L));
    }

    // ========== Partition Routing Tests ==========

    @Test
    void publish_routesByKeyHash() {
        service.createTopic("routing-topic", 4)
               .await()
               .onFailureRun(Assertions::fail);

        var payload = "test".getBytes(StandardCharsets.UTF_8);

        // Same key should always go to same partition
        var partition1 = service.publish("routing-topic", option("same-key"), payload)
                                .await()
                                .fold(err -> -1, StreamMessage::partition);

        var partition2 = service.publish("routing-topic", option("same-key"), payload)
                                .await()
                                .fold(err -> -1, StreamMessage::partition);

        assertThat(partition1).isEqualTo(partition2);
    }

    // ========== Lifecycle Tests ==========

    @Test
    void stop_clearsAllData() {
        service.createTopic("stop-topic").await();
        service.createConsumerGroup("stop-group").await();

        service.stop()
               .await()
               .onFailureRun(Assertions::fail);

        service.listTopics()
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(topics -> assertThat(topics).isEmpty());

        service.listConsumerGroups()
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(groups -> assertThat(groups).isEmpty());
    }

    // ========== Config Tests ==========

    @Test
    void streamingConfig_validatesName() {
        StreamingConfig.streamingConfig("")
                       .onSuccessRun(Assertions::fail)
                       .onFailure(cause -> assertThat(cause).isInstanceOf(StreamingError.InvalidConfiguration.class));
    }

    @Test
    void streamingConfig_validatesPartitions() {
        StreamingConfig.streamingConfig("test", 0)
                       .onSuccessRun(Assertions::fail)
                       .onFailure(cause -> assertThat(cause.message()).contains("Partitions must be at least 1"));

        StreamingConfig.streamingConfig("test", 2000)
                       .onSuccessRun(Assertions::fail)
                       .onFailure(cause -> assertThat(cause.message()).contains("Partitions cannot exceed 1024"));
    }
}
