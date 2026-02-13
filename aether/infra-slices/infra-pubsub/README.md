# Aether Infrastructure: PubSub

Topic-based publish/subscribe service for the Aether distributed runtime.

## Overview

Provides simple topic-based publish/subscribe messaging for decoupled communication between slices. Supports topic management (create, delete, list), publishing messages, subscribing with async handlers, and multiple subscribers per topic.

## Usage

```java
var pubsub = PubSub.inMemory();

// Create topic and subscribe
pubsub.createTopic("orders.created").await();
var subscription = pubsub.subscribe("orders.created", message -> {
    System.out.println("Received: " + message.payload());
    return Promise.unitPromise();
}).await();

// Publish message
var message = Message.message().payload("{\"orderId\":\"123\"}").build();
pubsub.publish("orders.created", message).await();

// Unsubscribe and list topics
pubsub.unsubscribe(subscription).await();
var topics = pubsub.listTopics().await();
```

## Dependencies

- `pragmatica-lite-core`
