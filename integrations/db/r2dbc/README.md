# R2DBC Integration

Promise-based reactive database access with typed error handling.

## Overview

Wraps R2DBC operations to return `Promise<T>` instead of using Reactive Streams Publishers. Provides query methods (`queryOne`, `queryOptional`, `queryList`), update operations, and a bridge between Reactive Streams `Publisher` and `Promise` via `ReactiveOperations`.

All R2DBC errors are mapped to typed `R2dbcError` causes (`ConnectionFailed`, `QueryFailed`, `ConstraintViolation`, `Timeout`, `NoResult`, `MultipleResults`, `DatabaseFailure`).

## Usage

```java
import org.pragmatica.r2dbc.R2dbcOperations;

R2dbcOperations r2dbc = R2dbcOperations.r2dbcOperations(connectionFactory);

// Query with row mapper
Promise<User> user = r2dbc.queryOne(
    "SELECT * FROM users WHERE id = $1", this::mapUser, userId);

Promise<Option<User>> opt = r2dbc.queryOptional(
    "SELECT * FROM users WHERE email = $1", this::mapUser, email);

Promise<List<User>> list = r2dbc.queryList(
    "SELECT * FROM users WHERE active = $1", this::mapUser, true);

// Update
Promise<Long> count = r2dbc.update(
    "UPDATE users SET active = $1 WHERE id = $2", false, userId);

// Reactive Streams bridge
Promise<T> result = ReactiveOperations.fromPublisher(publisher);
Promise<List<T>> all = ReactiveOperations.collectFromPublisher(publisher);
```

## Dependencies

- R2DBC SPI 1.0+
- R2DBC Driver (PostgreSQL, MySQL, H2, etc.)
- Reactive Streams 1.0+
- `pragmatica-lite-core`
