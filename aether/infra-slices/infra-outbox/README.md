# Aether Infrastructure: Outbox

Transactional outbox pattern implementation for [Aether](https://github.com/siy/aether) distributed runtime.

## Installation

```xml
<dependency>
    <groupId>org.pragmatica-lite.aether</groupId>
    <artifactId>infra-outbox</artifactId>
    <version>${aether.version}</version>
</dependency>
```

## Overview

Implements the transactional outbox pattern for reliable message delivery. Messages are first persisted to the outbox, then processed asynchronously with retry support.

### Key Features

- Reliable message delivery (at-least-once)
- Status tracking (PENDING, PROCESSING, DELIVERED, FAILED, CANCELLED)
- Retry scheduling
- Topic-based organization
- Cleanup of old messages

### Quick Start

```java
var outbox = OutboxService.outboxService();

// Add message to outbox
var message = OutboxMessage.outboxMessage()
    .topic("orders")
    .payload("{\"orderId\":\"123\",\"action\":\"created\"}")
    .build();
outbox.add(message).await();

// Process ready messages
var ready = outbox.getReady(10).await();
for (var msg : ready) {
    outbox.markProcessing(msg.messageId()).await();
    try {
        // Process message...
        outbox.markDelivered(msg.messageId()).await();
    } catch (Exception e) {
        outbox.markFailed(msg.messageId(), e.getMessage()).await();
        // Reschedule for retry
        outbox.reschedule(msg.messageId(), Instant.now().plusSeconds(60)).await();
    }
}

// Query by topic or status
var orderMessages = outbox.getByTopic("orders").await();
var failed = outbox.getByStatus(OutboxMessageStatus.FAILED).await();

// Cleanup old delivered messages
outbox.cleanup(Instant.now().minusDays(7)).await();
```

### API Summary

| Category | Methods |
|----------|---------|
| CRUD | `add`, `get`, `delete` |
| Status | `markProcessing`, `markDelivered`, `markFailed`, `cancel` |
| Query | `getReady`, `getByTopic`, `getByStatus` |
| Scheduling | `reschedule` |
| Admin | `cleanup`, `count`, `countByStatus` |

### Message Status Flow

```
PENDING → PROCESSING → DELIVERED
              ↓
           FAILED → (reschedule) → PENDING
              ↓
          CANCELLED
```

## License

Apache License 2.0
