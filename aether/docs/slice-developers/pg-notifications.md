# PostgreSQL LISTEN/NOTIFY Integration

## Overview

PostgreSQL LISTEN/NOTIFY is a built-in pub/sub mechanism that allows database connections to receive asynchronous notifications. Aether provides first-class support for LISTEN/NOTIFY as a provisioned resource, using the same annotation-driven pattern as topic subscriptions and stream consumers.

## Prerequisites

- PostgreSQL database (9.0+)
- Aether async PostgreSQL driver (`postgres-async` module)
- Database connection configured in `resources.toml`

## How LISTEN/NOTIFY Works

PostgreSQL implements a simple but effective notification system at the connection level:

- **LISTEN** command subscribes a connection to a named channel
- **NOTIFY** (or `pg_notify()`) sends a message to all listeners on a channel
- Notifications are asynchronous, delivered between queries on idle connections
- Payload limit: 8000 bytes per notification
- No persistence: if nobody is listening, the notification is lost
- NOTIFY inside a rolled-back transaction is NOT sent
- Multiple NOTIFYs with the same channel+payload in one transaction are coalesced to a single delivery
- Order is guaranteed within a single notifying transaction

## Configuration

### resources.toml

Define notification subscriptions in `[pg-notifications.xxx]` sections:

```toml
[pg-notifications.order-events]
datasource = "database.primary"
channels = ["orders_changed", "orders_deleted"]

[pg-notifications.user-events]
datasource = "database.primary"
channels = ["users_created", "users_updated"]
```

| Field        | Type         | Default      | Description                                    |
|-------------|-------------|-------------|------------------------------------------------|
| `datasource` | string      | `"database"` | Config section of the database to connect to    |
| `channels`   | string list | `[]`         | PostgreSQL channels to LISTEN on                |

### Annotation

Create a custom method annotation using `@ResourceQualifier`:

```java
@ResourceQualifier(type = PgNotificationSubscriber.class, config = "pg-notifications.order-events")
@Retention(RUNTIME)
@Target(METHOD)
public @interface OnOrderChange {}
```

The `config` value must match the section name in `resources.toml`.

## Receiving Notifications

### Simple Handler

Annotated methods receive `PgNotification` objects and must return `Promise<Unit>`:

```java
@OnOrderChange
Promise<Unit> onNotification(PgNotification notification) {
    log.info("Channel={}, Payload={}, PID={}",
             notification.channel(), notification.payload(), notification.pid());
    return Promise.unitPromise();
}
```

### Channel-Specific Handling

When listening on multiple channels, use the `channel()` field to route:

```java
@OnOrderChange
Promise<Unit> onNotification(PgNotification notification) {
    return switch (notification.channel()) {
        case "orders_changed" -> handleOrderChange(notification.payload());
        case "orders_deleted" -> handleOrderDeletion(notification.payload());
        default -> Promise.unitPromise();
    };
}
```

### PgNotification Fields

| Field     | Type   | Description                                    |
|-----------|--------|------------------------------------------------|
| `channel` | String | The channel name that was notified             |
| `payload` | String | The notification payload (up to 8000 bytes)    |
| `pid`     | int    | Process ID of the sending PostgreSQL backend   |

## Sending Notifications

Use the regular `SqlConnector` -- no special resource needed.

### From Application Code

```java
db.query("SELECT pg_notify('orders_changed', ?)", orderId.toString());
```

### From Database Triggers (recommended)

```sql
CREATE OR REPLACE FUNCTION notify_order_change()
RETURNS trigger AS $$
BEGIN
    PERFORM pg_notify('orders_changed', json_build_object(
        'operation', TG_OP,
        'id', NEW.id,
        'status', NEW.status
    )::text);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER order_change_trigger
    AFTER INSERT OR UPDATE ON orders
    FOR EACH ROW EXECUTE FUNCTION notify_order_change();
```

## Transaction Semantics

### NOTIFY Inside Transaction

- NOTIFY is queued until COMMIT
- If the transaction ROLLS BACK, the notification is NOT sent
- Multiple NOTIFYs in the same transaction with the same channel+payload are coalesced (sent once)

### LISTEN and Transactions

- LISTEN takes effect immediately (not transactional)
- Notifications are received between queries during idle periods
- The async driver delivers notifications via its event loop

## Connection Lifecycle

- Aether creates a **dedicated connection** for notifications (separate from the query pool)
- The connection stays open for the lifetime of the slice
- On slice unload: UNLISTEN all channels, close the dedicated connection
- One dedicated connection per `[pg-notifications.xxx]` config section

## Use Cases

1. **Cache Invalidation** -- NOTIFY on data change, handler evicts cache entry
2. **Real-Time Updates** -- trigger on INSERT, NOTIFY, handler pushes via WebSocket
3. **Cross-Service Events** -- service A writes to shared DB, trigger NOTIFYs, service B reacts
4. **Change Data Capture (CDC)** -- capture changes via triggers + NOTIFY, feed to streaming pipeline

## Limitations

- **Payload maximum:** 8000 bytes (use JSON, reference larger data by ID)
- **No persistence:** missed notifications are gone (use Aether Streams for durable events)
- **No acknowledgment:** fire-and-forget (use Aether Streams for at-least-once delivery)
- **Single database:** notifications do not cross database boundaries
- **Connection required:** if the dedicated connection drops, notifications are lost until reconnect

## Comparison with Aether Streams

| Feature               | LISTEN/NOTIFY           | Aether Streams           |
|-----------------------|------------------------|--------------------------|
| Persistence           | None                   | Configurable retention   |
| Delivery guarantee    | At-most-once           | At-least-once            |
| Payload size          | 8000 bytes             | Configurable (default 1MB) |
| Cross-node            | Same database only     | Cluster-wide             |
| Latency               | Sub-millisecond        | Low milliseconds         |
| Setup complexity      | Minimal (SQL only)     | Requires stream config   |

Use LISTEN/NOTIFY for lightweight, low-latency notifications where loss is acceptable.
Use Aether Streams for durable, cross-node event delivery.
