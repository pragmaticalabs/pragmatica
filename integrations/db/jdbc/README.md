# JDBC Integration

Promise-based JDBC operations with typed error handling.

## Overview

Wraps standard JDBC operations to return `Promise<T>` instead of throwing exceptions. Provides query methods (`queryOne`, `queryOptional`, `queryList`), update methods, batch operations, and transactional execution via `JdbcTransactional`.

All JDBC errors are mapped to typed `JdbcError` causes (`ConnectionFailed`, `QueryFailed`, `ConstraintViolation`, `Timeout`, `TransactionRollback`, `TransactionRequired`, `DatabaseFailure`).

## Usage

```java
import org.pragmatica.jdbc.JdbcOperations;

JdbcOperations jdbc = JdbcOperations.jdbcOperations(dataSource);

// Query single result
Promise<User> user = jdbc.queryOne(sql, this::mapUser, userId);

// Query optional result
Promise<Option<User>> user = jdbc.queryOptional(sql, this::mapUser, email);

// Query list
Promise<List<User>> users = jdbc.queryList(sql, this::mapUser, true);

// Update
Promise<Integer> rows = jdbc.update(sql, newName, userId);

// Transaction
JdbcTransactional.withTransaction(dataSource, JdbcError::fromException, conn -> {
    var jdbc = JdbcOperations.jdbcOperations(dataSource);
    return jdbc.update(insertSql, userId, total)
        .flatMap(_ -> jdbc.update(updateSql, userId));
});
```

## Dependencies

- JDBC Driver for your database
- `pragmatica-lite-core`
- Optional: HikariCP for connection pooling
