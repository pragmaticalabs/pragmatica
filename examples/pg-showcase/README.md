# pg-showcase — @PgSql Persistence Patterns

Minimal, self-contained example showcasing the @PgSql compile-time persistence adapter. Every query in this module is validated against the schema at compile time; if it compiles, the SQL is structurally correct and type-safe against the schema.

## Layout

```
pg-showcase/
├── src/main/resources/schema/
│   ├── V001__create_tables.sql          # Base tables (users, orders)
│   └── V002__add_columns.sql            # Advanced columns + constraints
├── src/main/java/.../
│   ├── UserPersistence.java             # Auto-generated CRUD with operators
│   ├── OrderPersistence.java            # @Query with JOIN / subquery + CRUD
│   └── OrderProcessing.java             # @Slice wiring both persistences
└── src/main/resources/resources.toml    # aether.toml for the database resource
```

## Schema coverage

V001 creates the two base tables (users, orders) with surrogate PKs and FKs. V002 demonstrates every advanced feature supported by the parser and validator:

- `CREATE TYPE ... AS ENUM` — named enum type `order_status`.
- `ALTER TABLE ... ADD COLUMN` — adds a nullable `deleted_at` to users and four new columns to orders (metadata, correlation_id, tags, total_with_tax).
- `JSONB` — `metadata` column with `DEFAULT '{}'` and a GIN index.
- `UUID` — `correlation_id` with `DEFAULT gen_random_uuid()`.
- `ARRAY` — `tags TEXT[]` with `DEFAULT '{}'` and a GIN index.
- `GENERATED ALWAYS AS ... STORED` — `total_with_tax` as `total * 1.08`.
- `CHECK` constraint — `total > 0` via named `ALTER TABLE ADD CONSTRAINT`.
- Partial index — `WHERE status <> 'cancelled'`.

## Persistence patterns

### Narrowing projections

`OrderSummary`, `OrderWithUserName` are projections the validator uses to narrow `SELECT *` down to only the requested columns.

### Explicit @Query methods

| Method | Pattern |
|---|---|
| `findOrdersWithUserName` | JOIN across two tables, alias resolution |
| `findOrdersByUserEmailDomain` | Subquery in WHERE (`IN (SELECT …)`) |
| `createOrder` | Explicit INSERT with named `:param` placeholders |
| `updateStatus` | Explicit UPDATE with named `:param` placeholders |
| `countActiveUsers` | Scalar count with `AND deleted_at IS NULL` |

### Auto-generated CRUD

| Method | Generated SQL |
|---|---|
| `findById` | `SELECT … WHERE id = $1` |
| `save(OrderRow)` | `INSERT … ON CONFLICT (id) DO UPDATE SET …` |
| `deleteById` | `DELETE … WHERE id = $1` |
| `countByStatus` | `SELECT count(*) … WHERE status = $1` |
| `existsById` | `SELECT EXISTS(SELECT 1 …)` |
| `findByStatusOrderByCreatedAtDesc` | ORDER BY from method name |
| `findByStatusNot` | Operator suffix `Not` → `status != $1` |
| `findByNameLike` | Operator suffix `Like` → `name LIKE $1` |
| `findByDeletedAtIsNull` / `…IsNotNull` | Zero-parameter operators |

### Advanced column types in return records

`OrderRow` pulls JSONB (`metadata`), UUID (`correlation_id`), and the GENERATED column (`total_with_tax`) back into Java as `String`, `UUID`, and `BigDecimal`.

## Running locally

Start a Postgres matching `resources.toml`:

```bash
docker run -d --name pg-forge -p 5432:5432 \
    -e POSTGRES_USER=forge -e POSTGRES_PASSWORD=forge -e POSTGRES_DB=forge \
    postgres:16
```

Apply the migrations (any Flyway-compatible tool), then build and install:

```bash
mvn -f examples/pg-showcase/pom.xml clean install
```

See [persistence-guide.md](../../aether/docs/slice-developers/persistence-guide.md) for the full architectural context.

## Validator notes

The compile-time validator currently enforces:

- Every `:param` has a matching method parameter.
- Every method parameter is used in the SQL.
- Column names and aliases resolve against the schema.
- Parameter Java types are coercion-compatible with column Postgres types.
- NOT NULL INSERT coverage.

Patterns deliberately avoided in this example to keep compile warnings at zero:

- CTE (`WITH clause`) — alias resolution of CTE aliases as tables is not implemented yet.
- @Query with `:record` expansion in INSERT/UPDATE — the validator flags the expanded fields as "unused"; see the comprehensive-persistence example for a usage pattern that documents this gap.
- @Query parameters of types that would require imports in the generated factory (`BigDecimal`, `Instant`, `UUID`) — wrapped in record parameters or converted at the call site.
