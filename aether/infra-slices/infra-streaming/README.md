# Aether Infrastructure: Streaming

Kafka-like streaming service for [Aether](https://github.com/siy/aether) distributed runtime.

## Installation

```xml
<dependency>
    <groupId>org.pragmatica-lite.aether</groupId>
    <artifactId>infra-streaming</artifactId>
    <version>${aether.version}</version>
</dependency>
```

## Overview

Provides Kafka-like streaming with partitioned topics, consumer groups, and at-least-once delivery semantics.

### Key Features

- Partitioned topics with configurable partition count
- Consumer groups with offset tracking
- Manual offset commits (at-least-once delivery)
- Key-based partition routing
- Seek operations (beginning, end, specific offset)

### Quick Start

```java
var streaming = StreamingService.streamingService();

// Create partitioned topic
streaming.createTopic("orders", 4).await(); // 4 partitions

// Publish messages
streaming.publish("orders", Option.some("order-123"), payload).await();

// Batch publish
streaming.publishBatch("orders", List.of(
    KeyValue.keyValue("order-1", payload1),
    KeyValue.keyValue("order-2", payload2)
)).await();

// Consumer group
streaming.createConsumerGroup("order-processor").await();
streaming.subscribe("order-processor", "orders").await();

// Poll and process
var messages = streaming.poll("order-processor", 100).await();
for (var msg : messages) {
    process(msg);
    streaming.commit("order-processor", msg).await();
}

// Seek to specific offset
streaming.seek("order-processor", "orders", 0, 1000L).await();
```

### API Summary

| Category | Methods |
|----------|---------|
| Topics | `createTopic`, `deleteTopic`, `listTopics`, `getTopic` |
| Publish | `publish`, `publishToPartition`, `publishBatch` |
| Consumer Groups | `createConsumerGroup`, `subscribe`, `unsubscribe`, `deleteConsumerGroup` |
| Consume | `poll`, `commit`, `commitBatch` |
| Seek | `seek`, `seekToBeginning`, `seekToEnd` |
| Offsets | `getCommittedOffset`, `getLatestOffset`, `getEarliestOffset` |

### Data Types

```java
record StreamMessage(String topic, int partition, long offset,
                     Option<String> key, byte[] value, Instant timestamp) {}

record TopicInfo(String name, int partitions, long messageCount) {}

record ConsumerGroupInfo(String groupId, Set<String> subscriptions,
                         Map<TopicPartition, Long> offsets) {}
```

## License

Apache License 2.0
