# JOOQ R2DBC Integration

Promise-based JOOQ with R2DBC for type-safe reactive database access.

## Overview

Combines JOOQ's type-safe SQL DSL with R2DBC's reactive driver, exposing results as `Promise<T>`. Provides `fetchOne`, `fetchOptional`, `fetch`, and `execute` methods, plus transactional support via `JooqR2dbcTransactional`.

Error handling uses `R2dbcError` causes from the R2DBC module.

## Usage

```java
import org.pragmatica.jooq.r2dbc.JooqR2dbcOperations;

var jooq = JooqR2dbcOperations.jooqR2dbcOperations(connectionFactory, SQLDialect.POSTGRES);

// Type-safe queries
Promise<UserRecord> user = jooq.fetchOne(
    jooq.dsl().selectFrom(USERS).where(USERS.ID.eq(userId)));

Promise<Option<UserRecord>> opt = jooq.fetchOptional(
    jooq.dsl().selectFrom(USERS).where(USERS.EMAIL.eq(email)));

Promise<List<UserRecord>> list = jooq.fetch(
    jooq.dsl().selectFrom(USERS).where(USERS.ACTIVE.isTrue()));

// Execute updates
Promise<Integer> count = jooq.execute(
    jooq.dsl().update(USERS).set(USERS.ACTIVE, false).where(USERS.LAST_LOGIN.lt(cutoff)));

// Transactions
JooqR2dbcTransactional.withTransaction(connectionFactory, SQLDialect.POSTGRES, dsl -> {
    var ops = JooqR2dbcOperations.jooqR2dbcOperations(dsl);
    return ops.execute(insertQuery)
        .flatMap(_ -> ops.execute(updateQuery));
});
```

## Dependencies

- JOOQ 3.19+
- R2DBC SPI 1.0+
- R2DBC Driver
- `pragmatica-lite-r2dbc`
- `pragmatica-lite-core`
