# Aether Persistence (AEP) — PostgreSQL Persistence Guide

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

## Nullable Columns

An `Option<T>` record component reads a nullable column: SQL `NULL` is `Option.none()`, never a default value. An
`Option<T>` parameter (or component of an expanded record) binds empty as `NULL`. `T` is a column type, a value object
with a `ValueMapping`, or a `@Jsonb` document.

```java
// Assumes a nullable column: ALTER TABLE users ADD COLUMN nickname TEXT;
record UserRow(long id, String name, Option<String> nickname) {}

@Query("UPDATE users SET nickname = :nickname WHERE id = :id")
Promise<Unit> rename(long id, Option<String> nickname);
```

A plain `String` component of a nullable column still reads `NULL` as `""`.

## JSON Documents (`json` / `jsonb`)

Mark a value stored as a JSON document with `@Jsonb` (from `org.pragmatica.aether.pg.codegen.annotation`). The
generated factory encodes and decodes it with a generated JSON codec: the same compile-time codec generator the slice
router uses for HTTP bodies. There is no reflection and no Jackson.

```sql
-- The table the example below reads and writes
CREATE TABLE user_docs (
    id BIGINT PRIMARY KEY,
    profile JSONB NOT NULL,
    previous JSONB
);
```

```java
record Address(String city, String zip) {}
record Profile(String name, Option<String> nickname, List<Address> addresses) {}
record ProfileProbe(String name) {}

// A row record: each marked component is one json/jsonb column.
record UserDocRow(long id, @Jsonb Profile profile, @Jsonb Option<Profile> previous) {}

@PgSql
public interface UserDocs {
    @Table(UserDocRow.class)
    Promise<Unit> insert(UserDocRow row);                     // CRUD: marked components are encoded
    Promise<Option<UserDocRow>> findById(long id);           // CRUD: marked components are decoded

    // A method marked @Jsonb reads its single result column as the document.
    @Query("SELECT profile FROM user_docs WHERE id = :id")
    @Jsonb
    Promise<Option<Profile>> profile(long id);

    // A marked parameter is bound as the document, e.g. for containment.
    @Query("SELECT id, profile, previous FROM user_docs WHERE profile @> :probe")
    Promise<List<UserDocRow>> containing(@Jsonb ProfileProbe probe);
}
```

| Where | Meaning |
|---|---|
| Record component | That column of a row record, read and written. `Option<T>`: `NULL` reads as empty and empty writes `NULL` |
| Method | The single result column (`Promise<T>`, `Promise<Option<T>>`, `Promise<List<T>>`); needs `@Query`. `Option` means zero or one row: a missing row is empty, but a row whose column is `NULL` fails the read. Read a nullable document through a row record with an `Option<T>` component instead |
| Parameter | The argument is bound as the document instead of being expanded into its components |

- **Types.** A marked type must be a record or enum the codec generator supports. Anything else is a compile error that
  carries the generator's reason. The escape hatch is a hand-written value-object mapping to the document text:
  `static ValueMapping<T, JsonText> valueMapping()` on `T`, used without `@Jsonb`.
- **Schema check.** Where the column's type is known (CRUD methods, `WHERE column = :param` pairs), a `@Jsonb` target
  must map to a `json` or `jsonb` column. Otherwise the build fails.
- **Binding.** A document is bound as `JsonText`, text with no declared type, so one value binds to `json` and `jsonb`
  columns and works as a `jsonb` operator operand (`@>`). A `String` parameter is declared `varchar`. A `jsonb`
  column or operator refuses it when the statement is prepared fresh, but the driver caches prepared statements by SQL
  text, so a `String` bind that reuses a statement prepared earlier on the connection with an untyped parameter is
  accepted. Neither outcome can be relied on: bind such a value as `JsonText.jsonText(text)`, or cast it in SQL
  (`CAST(:doc AS jsonb)`).
- **Unmarked columns.** An unmarked `String` component still reads a `json`/`jsonb` column as its raw text.
- **Fixed codec settings.** Stored documents use the codec's fixed defaults (component names as written, empty `Option`
  written as `null`). Deploy-time JSON settings for HTTP routes never apply to them, because a stored document is a
  durable contract.
- **Evolving stored documents.** Add new components as `Option<T>` only. A document written before the component
  existed decodes with it empty. A new required component makes every older row unreadable. Renaming a component drops
  the value stored under the old name (or makes older rows unreadable if the new name is required). Removing a
  component is safe: the old field becomes an unknown field, and unknown fields are skipped, which is also why older
  code reads documents written by newer code.
- **Decode failures.** A document the codec refuses fails the row read. The failure names the column.
- jOOQ (`db-jooq-async`) has no `@Jsonb` mapping yet.

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

error: [PG-VALIDATE] @Jsonb target 'title' maps to column 'docs.title' of type text; a JSON document needs a json or jsonb column
  → DocPersistence.java:12
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
