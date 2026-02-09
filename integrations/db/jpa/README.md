# JPA Integration

Promise-based JPA EntityManager wrapper with typed error handling.

## Overview

Wraps JPA EntityManager operations to return `Promise<T>` instead of throwing exceptions. Provides query methods (`querySingle`, `queryOptional`, `queryList`, `findById`), entity operations (`persist`, `merge`, `remove`, `refresh`), and transactional execution via `Transactional`.

All JPA errors are mapped to typed `JpaError` causes (`EntityNotFound`, `OptimisticLock`, `PessimisticLock`, `ConstraintViolation`, `TransactionRequired`, `EntityExists`, `QueryTimeout`, `DatabaseFailure`).

## Usage

```java
import org.pragmatica.jpa.JpaOperations;

JpaOperations jpa = JpaOperations.jpaOperations(entityManager);

// Query
Promise<User> user = jpa.querySingle(JpaError::fromException, typedQuery);
Promise<Option<User>> opt = jpa.queryOptional(JpaError::fromException, typedQuery);
Promise<Option<User>> byId = jpa.findById(JpaError::fromException, User.class, userId);

// Entity operations
Promise<User> persisted = jpa.persist(JpaError::fromException, newUser);
Promise<User> merged = jpa.merge(JpaError::fromException, detachedUser);

// Transactions
Transactional.withTransaction(entityManager, JpaError::fromException, em -> {
    var jpa = JpaOperations.jpaOperations(em);
    return jpa.persist(JpaError::fromException, newOrder)
        .flatMap(_ -> jpa.executeUpdate(JpaError::fromException, updateQuery));
});
```

## Dependencies

- Jakarta Persistence API 3.1+
- JPA Provider (Hibernate, EclipseLink, etc.)
- `pragmatica-lite-core`
