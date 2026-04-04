# Aether Store — PostgreSQL Persistence Adapter Specification

## Overview

Type-safe PostgreSQL persistence adapters for Aether slices with compile-time SQL validation. Queries are validated against the schema derived from migration files. If the code compiles, the queries work.

**Core principle:** The annotation processor parses migration SQL files at compile time, builds a schema model, then validates every query and CRUD method against that schema — checking table existence, column names, parameter types, and return type mappings.

## Architecture

```
Migration SQL files (src/main/resources/schema/)
        ↓
   [PostgresParser]  → parse and validate syntax
        ↓
   [SchemaBuilder]   → build schema model from DDL events
        ↓
   [Annotation Processor]
        ├── validate @Query SQL against schema
        ├── generate CRUD SQL from method names
        ├── rewrite queries (named params, column narrowing)
        ├── validate parameter names, types, counts
        ├── validate return type field mappings
        └── generate Factory class with implementation
```

## Persistence Interface

A persistence adapter is a plain Java interface annotated with a `PgSqlConnector`-based resource qualifier. It is injected into slice factories as a dependency.

```java
@PgSql
public interface OrderPersistence {

    @Query("SELECT id, name, email FROM users WHERE email = :email")
    Promise<Option<UserRow>> findUserByEmail(String email);

    @Query("SELECT o.id, o.total, o.status FROM orders o WHERE o.user_id = :userId")
    Promise<List<OrderRow>> findOrdersByUser(long userId);

    Promise<Option<UserRow>> findById(@Table(UserRow.class) long id);

    Promise<List<OrderRow>> findByStatusOrderByCreatedAtDesc(String status);

    Promise<OrderRow> insert(CreateOrderRequest request);

    Promise<UserRow> save(UserRow user);
}
```

### Key Points

- The interface is NOT tied to a single table — it is a cohesive data access surface for a slice
- `@PgSql` (or any custom annotation with `@ResourceQualifier(type = PgSqlConnector.class, ...)`) marks it for processing
- Methods with `@Query` have explicit SQL; methods without are auto-generated from the method name
- All methods must return `Promise<T>`, `Promise<Option<T>>`, `Promise<List<T>>`, `Promise<Unit>`, `Promise<Long>`, or `Promise<Boolean>`

## PgSqlConnector

A PostgreSQL-specific extension of `SqlConnector`:

```java
public interface PgSqlConnector extends SqlConnector {
    // Same API as SqlConnector — the type identity enables dialect detection
}
```

- Backed exclusively by the async PostgreSQL driver (no JDBC/R2DBC)
- The distinct type allows the annotation processor to detect PostgreSQL interfaces and enable validation
- If an interface uses `@Query` or `@Table` but its qualifier references generic `SqlConnector` instead of `PgSqlConnector`, the processor emits a compilation error

## Resource Qualifier

Follows the existing Aether resource qualifier pattern:

```java
@ResourceQualifier(type = PgSqlConnector.class, config = "database")
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.TYPE})
public @interface PgSql {}
```

For multiple PostgreSQL datasources:

```java
@ResourceQualifier(type = PgSqlConnector.class, config = "database.analytics")
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.TYPE})
public @interface AnalyticsPgSql {}
```

The `config` section maps to the migration subdirectory:
- `"database"` → reads from `schema/`
- `"database.analytics"` → reads from `schema/analytics/`

## Integration with Slices

The persistence interface is injected into the slice factory as a plain interface parameter:

```java
@Slice
public interface OrderProcessing {

    Promise<OrderConfirmation> placeOrder(PlaceOrderRequest request);

    static OrderProcessing orderProcessing(
            OrderPersistence persistence,
            PaymentSlice payment) {
        // ...
    }
}
```

The slice processor sees that `OrderPersistence` carries `@PgSql` (a `@ResourceQualifier`), classifies it as a resource dependency, provisions `PgSqlConnector` from the `"database"` config section, creates `OrderPersistenceFactory.orderPersistence(pgSqlConnector)`, and passes it to the slice factory.

## Named Parameters

Queries use `:paramName` syntax (Spring convention):

```java
@Query("SELECT * FROM users WHERE email = :email AND active = :active")
Promise<List<UserRow>> findActiveByEmail(String email, boolean active);
```

### Rewriting

The processor rewrites `:paramName` to PostgreSQL positional `$N` parameters:

```sql
-- Source:   SELECT * FROM users WHERE email = :email AND active = :active
-- Rewritten: SELECT * FROM users WHERE email = $1 AND active = $2
```

Same parameter used multiple times maps to the same positional:

```sql
-- Source:   SELECT * FROM users WHERE id = :id OR manager_id = :id
-- Rewritten: SELECT * FROM users WHERE id = $1 OR manager_id = $1
```

### Validation

- Every `:param` in the query MUST have a matching method parameter by name → compilation error if missing
- Every method parameter MUST be used in the query → compilation error if unused
- Parameter types validated against the schema column types

### Parameter Type Mapping

| Java type | PostgreSQL wire type |
|-----------|---------------------|
| `String` | text/varchar |
| `long` | bigint |
| `int` | integer |
| `boolean` | boolean |
| `Instant` | timestamptz |
| `BigDecimal` | numeric |
| `UUID` | uuid |
| Generated enum | text (via `pgValue()`) |

## Query Records

Parameters can be grouped into records:

```java
record CreateUserRequest(String name, String email, boolean active) {}

// Individual parameters:
@Query("INSERT INTO users (name, email, active) VALUES (:name, :email, :active)")
Promise<Unit> createUser(String name, String email, boolean active);

// Equivalent with query record:
@Query("INSERT INTO users (name, email, active) VALUES (:name, :email, :active)")
Promise<Unit> createUser(CreateUserRequest request);
```

### Rules

- Record fields are flattened — field names become parameter names
- No nesting (record containing record) — flat only
- Name conflict between direct parameter and record field → compilation error
- All record fields MUST be used in the query (same exact-match rule)
- `Option<T>` field → binds NULL when empty

### Mixing Records and Direct Parameters

```java
record OrderFilter(String status, Instant createdAfter) {}

@Query("SELECT * FROM orders WHERE user_id = :userId AND status = :status AND created_at > :createdAfter")
Promise<List<OrderRow>> findOrders(long userId, OrderFilter filter);
```

## Record Expansion

Record parameters can be expanded in INSERT and UPDATE to reduce boilerplate.

### INSERT Expansion

```java
record CreateUserRequest(String name, String email, boolean active) {}

@Query("INSERT INTO users VALUES(:request)")
Promise<Unit> createUser(CreateUserRequest request);

// Processor expands to:
// INSERT INTO users (name, email, active) VALUES ($1, $2, $3)
```

Column names derived from field names via camelCase → snake_case transformation.

### UPDATE SET Expansion

```java
record UpdateUserRequest(String name, String email) {}

@Query("UPDATE users SET :request WHERE id = :id")
Promise<Unit> updateUser(long id, UpdateUserRequest request);

// Processor expands to:
// UPDATE users SET name = $2, email = $3 WHERE id = $1
```

### Expansion Validation

- Every expanded field must match a column in the target table → compilation error if not
- Type compatibility checked for each field → column pair
- Column has DEFAULT or IDENTITY → OK to omit from record
- Column is nullable → OK to omit (inserts NULL)
- Column is NOT NULL without DEFAULT and no matching field → **compilation error**

### Phase 2

Consider `:recordParam` expansion in other contexts (WHERE clause, function arguments) pending real-world usage feedback.

## Return Type Mapping

The processor maps query result columns to return record fields using snake_case → camelCase transformation.

```java
record UserSummary(long id, String name, int orderCount) {}

@Query("SELECT u.id, u.name, count(o.id) AS order_count FROM users u LEFT JOIN orders o ON u.id = o.user_id GROUP BY u.id, u.name")
Promise<List<UserSummary>> findUsersWithOrders();
// order_count → orderCount ✓
```

### Mapping Rules

1. Every field in the return record MUST have a matching column in the SELECT output → compilation error if missing
2. Column `snake_case` → field `camelCase` for matching
3. Expression aliases required for computed columns (`count(o.id) AS order_count`)
4. Extra SELECT columns not in the record are ignored (narrowing)
5. Type mismatch → compilation error

### Allowed Safe Type Coercions

| Column type | Allowed Java types |
|---|---|
| `bigint` | `long`, `Long`, `BigDecimal` |
| `integer` | `int`, `long`, `Integer`, `Long` |
| `text/varchar` | `String` |
| `numeric` | `BigDecimal`, `double` (with warning) |
| `boolean` | `boolean`, `Boolean` |
| `timestamptz` | `Instant` |
| `enum` | `String`, generated Java enum |

### Query Narrowing (Mandatory)

When the SELECT output columns do not match the return record fields 1:1, the processor rewrites the query to specify only the needed columns:

```java
record UserName(long id, String name) {}

@Query("SELECT * FROM users WHERE active = true")
Promise<List<UserName>> findActiveNames();
// Rewritten: SELECT id, name FROM users WHERE active = true
```

**Narrowing rules:**
- Return type IS the table record (all columns match) → no rewrite, `*` stays
- Return type is a projection (subset of columns) → rewrite `*` to explicit column list
- Applies to `SELECT *`, `SELECT t.*` in joins

### Return Type Cardinality

| Return type | Semantics | Generated behavior |
|---|---|---|
| `Promise<T>` | Exactly one row | Error (`Cause`) if 0 rows |
| `Promise<Option<T>>` | Zero or one row | `Option.empty()` if no rows |
| `Promise<List<T>>` | Zero or more rows | Empty list if no rows |
| `Promise<Unit>` | No result (DML) | Returns successfully |
| `Promise<Long>` | Scalar count | Maps to long |
| `Promise<Boolean>` | Scalar boolean | Maps to boolean |

## Auto-Generated CRUD Methods

Methods without `@Query` are auto-generated from their name using SpringData-style conventions.

### Table Inference

Table is inferred from generated record types — no strings:

1. Return type `Promise<List<OrderRow>>` → `OrderRow` generated from `orders` → table = `orders`
2. Input type `insert(CreateUser request)` → fields matched against table
3. Ambiguous → require `@Table(RecordClass.class)` on the method

```java
@PgSql
public interface OrderPersistence {

    // Table inferred from return type
    Promise<List<OrderRow>> findByStatus(String status);

    // Table explicit when can't infer
    @Table(UserRow.class)
    Promise<Option<UserRow>> findById(long id);

    // Table inferred from input type
    Promise<OrderRow> insert(OrderRow order);
}
```

`@Table` takes a generated record class, not a string. The processor resolves the table from the record's origin.

### Method Name Patterns

| Pattern | Generated SQL | Return type |
|---|---|---|
| `findById(id)` | `SELECT ... FROM {table} WHERE id = $1` | `Promise<Option<T>>` |
| `findByXx(val)` | `SELECT ... FROM {table} WHERE xx = $1` | `Promise<List<T>>` or `Promise<Option<T>>` |
| `findByXxAndYy(x, y)` | `SELECT ... FROM {table} WHERE xx = $1 AND yy = $2` | `Promise<List<T>>` |
| `findAll()` | `SELECT ... FROM {table}` | `Promise<List<T>>` |
| `save(T)` | `INSERT ... ON CONFLICT (pk) DO UPDATE SET ... RETURNING ...` | `Promise<T>` |
| `insert(T)` | `INSERT ... VALUES (...) RETURNING ...` | `Promise<T>` |
| `deleteById(id)` | `DELETE FROM {table} WHERE id = $1` | `Promise<Unit>` |
| `countByXx(val)` | `SELECT count(*) FROM {table} WHERE xx = $1` | `Promise<Long>` |
| `existsById(id)` | `SELECT EXISTS(SELECT 1 FROM {table} WHERE id = $1)` | `Promise<Boolean>` |

### Column Name Derivation

Method name segments are camelCase → snake_case: `findByCreatedAtAfter` → column `created_at`, operator `>`.

### Operator Suffixes

| Suffix | SQL operator |
|---|---|
| *(none)* | `=` |
| `GreaterThan` / `After` | `>` |
| `LessThan` / `Before` | `<` |
| `GreaterThanOrEqual` | `>=` |
| `LessThanOrEqual` | `<=` |
| `Like` | `LIKE` |
| `In` | `IN (...)` |
| `IsNull` | `IS NULL` (no parameter) |
| `IsNotNull` | `IS NOT NULL` (no parameter) |
| `Between` | `BETWEEN $n AND $n+1` (two parameters) |
| `Not` | `!=` |

### OrderBy

```java
Promise<List<OrderRow>> findByActiveOrderByCreatedAtDesc(boolean active);
// SELECT * FROM orders WHERE active = $1 ORDER BY created_at DESC

Promise<List<UserRow>> findByActiveOrderByNameAscCreatedAtDesc(boolean active);
// SELECT * FROM users WHERE active = $1 ORDER BY name ASC, created_at DESC
```

- `OrderBy` separates WHERE part from ORDER part
- Each column optionally followed by `Asc` (default) or `Desc`
- Multiple ORDER columns concatenated
- All columns validated against table schema

### `insert()` — Plain INSERT

```java
Promise<UserRow> insert(UserRow user);
// INSERT INTO users (id, name, email, active, created_at) VALUES ($1, $2, $3, $4, $5)
// RETURNING *
```

- Fails on PK conflict → returns `Cause`
- `RETURNING *` appended when return type is not `Promise<Unit>`
- Columns with IDENTITY/DEFAULT: always included if present in the input record
- NOT NULL columns without DEFAULT must be in the input record → compilation error

### `save()` — Upsert

```java
Promise<UserRow> save(UserRow user);
// INSERT INTO users (id, name, email, active, created_at) VALUES ($1, $2, $3, $4, $5)
// ON CONFLICT (id) DO UPDATE SET name = $2, email = $3, active = $4, created_at = $5
// RETURNING *
```

- PK columns from schema used for `ON CONFLICT`
- PK columns excluded from `DO UPDATE SET`
- Table must have a PK → compilation error if not
- `RETURNING` controlled by return type (same as `insert()`)

### Query Narrowing for CRUD

Same narrowing rules apply — if the return type is a projection (not the full table record), `SELECT *` is rewritten to the needed columns.

## Compile-Time Validation Pipeline

### Stage 1: Interface Validation

- Interface must have `@ResourceQualifier`-annotated annotation referencing `PgSqlConnector`
- All methods must return allowed `Promise<...>` types
- If qualifier references generic `SqlConnector` with `@Query`/`@Table` → error with guidance

### Stage 2: Schema Loading

- Read migration files from `schema/` on classpath (Aether convention)
- `schema/*.sql` → default datasource
- `schema/{name}/*.sql` → named datasource `database.{name}`
- Subdirectory matched to qualifier's config section
- Files processed in Flyway order (`V001__`, `V002__`, etc.)
- Schema cached across all interfaces in the compilation unit

### Stage 3: Per-Method Validation

For `@Query` methods:
1. Parse SQL syntax
2. Resolve named parameters → match to method parameters by name (exact match)
3. Validate parameter types against schema column types
4. Validate return type field mapping against SELECT output
5. Determine query rewriting (column narrowing, parameter positional)
6. Run lint rules (configurable, warning by default)

For auto-generated CRUD methods:
1. Parse method name → extract operation, columns, operators, ordering
2. Infer table from return/input type or `@Table`
3. Validate table exists, columns exist, types match
4. For `save()`/`insert()`: validate NOT NULL column coverage

### Stage 4: Code Generation

- Generate `{Interface}Factory` class
- Factory method returning local record implementation
- SQL constants, row mappers, connector delegation

### Error Messages

```
error: [PG-VALIDATE] Column 'nonexistent' not found in table 'users'
  → OrderPersistence.java:15: findByNonexistent(String value)

error: [PG-VALIDATE] Parameter ':status' has no matching method parameter
  → OrderPersistence.java:18: @Query("SELECT * FROM orders WHERE status = :status")

error: [PG-VALIDATE] Type mismatch: parameter 'id' is String but column 'users.id' is bigint
  → OrderPersistence.java:22: findById(String id)

error: [PG-VALIDATE] NOT NULL column 'email' has no DEFAULT and is not in CreateUserRequest
  → OrderPersistence.java:25: insert(CreateUserRequest request)

error: [PG-VALIDATE] Interface 'LegacyPersistence' uses @Query but its qualifier
  references SqlConnector, not PgSqlConnector. Use a PgSqlConnector-based qualifier.
  → LegacyPersistence.java:3
```

## Generated Code Structure

```java
public final class OrderPersistenceFactory {

    public static OrderPersistence orderPersistence(PgSqlConnector db) {

        record orderPersistence(PgSqlConnector db) implements OrderPersistence {

            private static final String FIND_USER_BY_EMAIL =
                "SELECT id, name, email FROM users WHERE email = $1";

            private static final String FIND_ORDERS_BY_USER =
                "SELECT id, total, status FROM orders WHERE user_id = $1";

            @Override
            public Promise<Option<UserRow>> findUserByEmail(String email) {
                return db.queryOptional(FIND_USER_BY_EMAIL,
                    OrderPersistenceFactory::mapUserRow, email);
            }

            @Override
            public Promise<List<OrderRow>> findOrdersByUser(long userId) {
                return db.queryList(FIND_ORDERS_BY_USER,
                    OrderPersistenceFactory::mapOrderRow, userId);
            }
        }

        return new orderPersistence(db);
    }

    private static Result<UserRow> mapUserRow(RowAccessor row) {
        return Result.all(
            row.getLong("id"),
            row.getString("name"),
            row.getString("email")
        ).map(UserRow::new);
    }

    private static Result<OrderRow> mapOrderRow(RowAccessor row) {
        return Result.all(
            row.getLong("id"),
            row.getObject("total", BigDecimal.class),
            row.getString("status")
        ).map(OrderRow::new);
    }
}
```

### Connector Method Selection

| Return type | Connector method |
|---|---|
| `Promise<T>` | `db.queryOne(sql, mapper, params...)` |
| `Promise<Option<T>>` | `db.queryOptional(sql, mapper, params...)` |
| `Promise<List<T>>` | `db.queryList(sql, mapper, params...)` |
| `Promise<Unit>` | `db.update(sql, params...)` |
| `Promise<Long>` | `db.queryOne(sql, longMapper, params...)` |
| `Promise<Boolean>` | `db.queryOne(sql, boolMapper, params...)` |

## Phase 2 — Extensions

- `:recordParam` expansion in contexts beyond VALUES and SET (WHERE, function arguments)
- Parameter name override annotation (working name `@As`, needs design — should be distinct from other annotations, avoid confusion with `AS` SQL keyword)
- Batch operations: `Promise<int[]> insertBatch(List<T>)`
- Pagination support
- `RETURNING` clause for UPDATE/DELETE
- `SELECT *` rewriting in JOINs with table alias qualification
- Schema version checksum drift detection at runtime
- Query plan hints (`timeout`, `readOnly`)

## Documentation Requirements

The implementor MUST:

1. Write user-facing documentation with examples covering:
   - Persistence interface definition and annotation usage
   - `@Query` methods with named parameters
   - Query records and record expansion
   - Auto-generated CRUD methods with all supported patterns
   - Return type mapping and narrowing behavior
   - Table inference and `@Table` disambiguation
   - Compile-time error messages and how to fix them
   - Multi-datasource configuration

2. Update existing slice developer documentation (`aether/docs/slice-developers/`) to provide references to the persistence adapter documentation:
   - `resource-reference.md` — add PgSqlConnector section with link
   - `slice-patterns.md` — add persistence adapter pattern with link

## Files to Create/Modify

### New in Aether (parent project)

| File | Description |
|---|---|
| `resource/api/.../PgSqlConnector.java` | Interface extending SqlConnector |
| `resource/api/.../PgSql.java` | Resource qualifier annotation (add TYPE target) |
| `resource/db-async/...` | Factory for PgSqlConnector provisioning |

### New in aether-pg-tools

| File | Description |
|---|---|
| `pg-codegen/.../QueryAnnotationProcessor.java` | Main annotation processor |
| `pg-codegen/.../MethodNameParser.java` | CRUD method name → SQL |
| `pg-codegen/.../QueryRewriter.java` | Named params, column narrowing, record expansion |
| `pg-codegen/.../FactoryGenerator.java` | Generates Factory class code |
| `pg-codegen/.../RowMapperGenerator.java` | Extend existing for query return types |

### Modified in aether-pg-tools

| File | Description |
|---|---|
| `pg-codegen/pom.xml` | Add annotation processor dependencies |
| `pg-schema/.../QueryValidator.java` | Extend for parameter type validation |
