# Aether Store — PostgreSQL Persistence Guide

Type-safe PostgreSQL persistence adapters for Aether slices with compile-time SQL validation.

## Overview

Define a persistence interface, annotate it with `@PgSql`, and the annotation processor validates every query against your schema at compile time. If it compiles, the queries work.

```java
@PgSql
public interface OrderPersistence {

    @Query("SELECT id, total, status FROM orders WHERE user_id = :userId")
    Promise<List<OrderRow>> findOrdersByUser(long userId);

    Promise<Option<OrderRow>> findById(long id);

    Promise<OrderRow> save(OrderRow order);
}
```

## Setup

### 1. Add dependencies to your slice POM

```xml
<dependencies>
    <dependency>
        <groupId>org.pragmatica-lite.aether</groupId>
        <artifactId>resource-api</artifactId>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <configuration>
                <annotationProcessorPaths>
                    <path>
                        <groupId>org.pragmatica-lite.aether</groupId>
                        <artifactId>pg-codegen</artifactId>
                        <version>${pragmatica.version}</version>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

Or use `jbct add persistence` to add these automatically.

### 2. Create migration files

Place SQL files in `src/main/resources/schema/`:

```sql
-- schema/V001__create_tables.sql
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    email TEXT NOT NULL UNIQUE,
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Files are processed in Flyway version order (`V001__`, `V002__`, etc.).

### 3. Generate record types

```bash
mvn pg:generate
```

This reads your migration files and generates Java records:

```java
// Generated — do not edit manually
public record UserRow(long id, String name, String email, boolean active, Instant createdAt) {}
```

### 4. Define persistence interface

```java
@PgSql
public interface UserPersistence {

    Promise<Option<UserRow>> findById(long id);

    Promise<List<UserRow>> findByActive(boolean active);

    Promise<UserRow> save(UserRow user);

    Promise<Unit> deleteById(long id);
}
```

### 5. Wire into your slice

```java
@Slice
public interface UserService {
    Promise<UserRow> getUser(long id);

    static UserService userService(UserPersistence persistence) {
        // Use persistence methods here
    }
}
```

The slice processor sees `UserPersistence` carries `@PgSql` (a `@ResourceQualifier`), provisions `PgSqlConnector`, creates `UserPersistenceFactory.userPersistence(connector)`, and passes it to the slice factory.

## `@Query` Methods

### Named Parameters

```java
@Query("SELECT * FROM users WHERE email = :email AND active = :active")
Promise<List<UserRow>> findActiveByEmail(String email, boolean active);
```

Parameters use `:paramName` syntax. The processor rewrites to PostgreSQL positional `$N`:

```sql
-- Rewritten: SELECT * FROM users WHERE email = $1 AND active = $2
```

### Validation Rules

- Every `:param` must have a matching method parameter — compilation error if missing
- Every method parameter must be used in the query — compilation error if unused
- Parameter types validated against schema column types
- Return record fields validated against SELECT output columns

### Query Records

Group parameters into records:

```java
record CreateUserRequest(String name, String email, boolean active) {}

@Query("INSERT INTO users (name, email, active) VALUES (:name, :email, :active)")
Promise<Unit> createUser(CreateUserRequest request);
```

Record fields are flattened — field names become parameter names.

### Record Expansion

Reduce boilerplate for INSERT and UPDATE:

```java
// INSERT expansion
@Query("INSERT INTO users VALUES(:request)")
Promise<Unit> createUser(CreateUserRequest request);
// Expands to: INSERT INTO users (name, email, active) VALUES ($1, $2, $3)

// UPDATE SET expansion
@Query("UPDATE users SET :request WHERE id = :id")
Promise<Unit> updateUser(long id, UpdateUserRequest request);
// Expands to: UPDATE users SET name = $2, email = $3 WHERE id = $1
```

### Return Type Mapping

| Return type | Semantics |
|---|---|
| `Promise<T>` | Exactly one row (error if 0) |
| `Promise<Option<T>>` | Zero or one row |
| `Promise<List<T>>` | Zero or more rows |
| `Promise<Unit>` | No result (DML) |
| `Promise<Long>` | Scalar count |
| `Promise<Boolean>` | Scalar boolean |

### Query Narrowing

When the return type is a projection (subset of columns), `SELECT *` is rewritten:

```java
record UserName(long id, String name) {}

@Query("SELECT * FROM users WHERE active = true")
Promise<List<UserName>> findActiveNames();
// Rewritten: SELECT id, name FROM users WHERE active = true
```

## Auto-Generated CRUD Methods

Methods without `@Query` are generated from their name using Spring Data conventions.

### Patterns

| Pattern | Generated SQL |
|---|---|
| `findById(id)` | `SELECT ... WHERE id = $1` |
| `findByXx(val)` | `SELECT ... WHERE xx = $1` |
| `findByXxAndYy(x, y)` | `SELECT ... WHERE xx = $1 AND yy = $2` |
| `findAll()` | `SELECT ...` |
| `save(T)` | `INSERT ... ON CONFLICT (pk) DO UPDATE SET ...` |
| `insert(T)` | `INSERT ... VALUES (...)` |
| `deleteById(id)` | `DELETE ... WHERE id = $1` |
| `countByXx(val)` | `SELECT count(*) ... WHERE xx = $1` |
| `existsById(id)` | `SELECT EXISTS(...)` |

### Operators

| Suffix | SQL |
|---|---|
| *(none)* | `=` |
| `GreaterThan` / `After` | `>` |
| `LessThan` / `Before` | `<` |
| `Like` | `LIKE` |
| `In` | `IN (...)` |
| `IsNull` | `IS NULL` |
| `Between` | `BETWEEN $n AND $n+1` |
| `Not` | `!=` |

### OrderBy

```java
Promise<List<OrderRow>> findByActiveOrderByCreatedAtDesc(boolean active);
// SELECT * FROM orders WHERE active = $1 ORDER BY created_at DESC
```

### Table Inference

Table is inferred from the return/input record type. Use `@Table` when ambiguous:

```java
@Table(UserRow.class)
Promise<Option<UserRow>> findById(long id);
```

## Multiple Datasources

Create custom qualifier annotations:

```java
@ResourceQualifier(type = PgSqlConnector.class, config = "database.analytics")
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.TYPE})
public @interface AnalyticsPgSql {}
```

Migration files go in `schema/analytics/V001__*.sql`.

## Compile-Time Error Messages

```
error: [PG-VALIDATE] Column 'nonexistent' not found in table 'users'
  → OrderPersistence.java:15

error: [PG-VALIDATE] Parameter ':status' has no matching method parameter
  → OrderPersistence.java:18

error: [PG-VALIDATE] Type mismatch: 'id' is String but column 'users.id' is bigint
  → OrderPersistence.java:22
```

## Migration Linting

The 41 built-in lint rules check your migration SQL for:
- **Lock hazards** — `NOT NULL` without `DEFAULT`, `INDEX` without `CONCURRENTLY`
- **Type design** — `char(n)`, `timestamp without tz`, `money`, `serial`
- **Schema design** — missing PK, FK without index, unnamed constraints
- **Migration practice** — `DROP` without `IF EXISTS`, volatile defaults

Run manually: `mvn pg:lint`

## See Also

- [Resource Reference](resource-reference.md) — all Aether resource types
- [Slice Patterns](slice-patterns.md) — common slice architecture patterns
- [pg-persistence-spec](../specs/pg-persistence-spec.md) — full specification
