# Aether Infrastructure: PubSub

Topic-based publish/subscribe service for [Aether](https://github.com/siy/aether) distributed runtime.

## Installation

```xml
<dependency>
    <groupId>org.pragmatica-lite.aether</groupId>
    <artifactId>infra-pubsub</artifactId>
    <version>${aether.version}</version>
</dependency>
```

## Overview

Provides simple topic-based publish/subscribe messaging for decoupled communication between slices.

### Key Features

- Topic management (create, delete, list)
- Publish messages to topics
- Subscribe with async message handlers
- Multiple subscribers per topic

### Quick Start

```java
var pubsub = PubSub.inMemory();

// Create topic
pubsub.createTopic("orders.created").await();

// Subscribe to topic
var subscription = pubsub.subscribe("orders.created", message -> {
    System.out.println("Received: " + message.payload());
    return Promise.unitPromise();
}).await();

// Publish message
var message = Message.message()
    .payload("{\"orderId\":\"123\"}")
    .build();
pubsub.publish("orders.created", message).await();

// Unsubscribe when done
pubsub.unsubscribe(subscription).await();

// List topics
var topics = pubsub.listTopics().await(); // Set<String>
```

### API Summary

| Category | Methods |
|----------|---------|
| Topics | `createTopic`, `deleteTopic`, `listTopics` |
| Messaging | `publish`, `subscribe`, `unsubscribe` |

### Message

```java
record Message(String id, String payload, Instant timestamp,
               Map<String, String> headers) {
    static MessageBuilder message();
}
```

### Subscription

```java
public interface Subscription {
    String id();
    String topic();
    Instant subscribedAt();
}
```

## License

Apache License 2.0
