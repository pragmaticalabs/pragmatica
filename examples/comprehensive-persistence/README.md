# comprehensive-persistence

End-to-end @PgSql persistence example exercising the full surface area of the compile-time validator: JOINs, aggregations, subqueries, complex WHERE with `AND`/`OR`, every operator suffix the CRUD name parser understands, and an illustrative multi-datasource qualifier.

## Layout

```
comprehensive-persistence/
├── pom.xml
├── src/main/resources/
│   ├── resources.toml                # Primary + analytics datasource configuration
│   └── schema/
│       ├── V001__base.sql            # Tables: customers, products, orders, order_items
│       ├── V002__add_constraints.sql # FKs, UNIQUE constraints, indexes
│       ├── V003__add_columns.sql     # Advanced columns: ENUM, JSONB, UUID, GENERATED, CHECK, partial index
│       └── V004__seed.sql            # Analytics tables: order_metrics, daily_snapshot
├── src/main/java/.../comprehensive/
│   ├── AnalyticsPgSql.java           # Custom @ResourceQualifier for a second datasource
│   ├── BasePersistence.java          # @Query: JOINs, aggregates, subqueries, complex WHERE
│   ├── CrudPersistence.java          # CRUD with every operator suffix
│   ├── AnalyticsPersistence.java     # Analytics-scoped queries
│   └── OrderSlice.java               # @Slice composing all three persistences
└── src/test/java/.../comprehensive/
    └── ComprehensivePersistenceTest.java
```

## What is exercised

### Schema (V001 + V002 + V003 + V004)

- `GENERATED ALWAYS AS IDENTITY` surrogate keys.
- NOT NULL columns with and without DEFAULTs.
- Foreign keys added via `ALTER TABLE ADD CONSTRAINT`, with `ON DELETE CASCADE`.
- Unique constraints.
- Regular and GIN indexes (the latter on JSONB columns).
- Partial index (`WHERE status <> 'cancelled'`).
- Named `CHECK` constraint.
- `CREATE TYPE ... AS ENUM` (`order_status`).
- Advanced column types: `JSONB`, `UUID`, `TEXT[]`, `GENERATED ALWAYS AS ... STORED`.

### BasePersistence @Query shapes

| Method | SQL pattern |
|---|---|
| `findCustomerOrdersByStatus` | JOIN across `customers` + `orders`, alias resolution |
| `customerRevenueReport` | LEFT JOIN + aggregates (`count`, `sum`, `min`, `max`) + `GROUP BY` |
| `productSalesReport` | Three-table JOIN + aggregates + `GROUP BY` |
| `findHighValueOrders` | Complex WHERE combining `AND` and `OR` |
| `ordersForDomain` | Subquery in WHERE (`IN (SELECT ...)`) |
| `distinctCustomersByStatus` | Scalar count of a distinct expression |

### CrudPersistence operator suffixes

| Method | Generated SQL |
|---|---|
| `findByTier` | `WHERE tier = $1` |
| `findByTierNot` | `WHERE tier != $1` |
| `findByNameLike` | `WHERE name LIKE $1` |
| `findByDeletedAtIsNull` | `WHERE deleted_at IS NULL` |
| `findByDeletedAtIsNotNull` | `WHERE deleted_at IS NOT NULL` |
| `findByTierAndActive` | Conjunction of two `=` predicates |
| `findByActiveOrderByNameAsc` | ORDER BY with explicit direction |
| `findByActiveOrderByTierAscNameDesc` | Multi-column ORDER BY |
| `save(CustomerRow)` | `INSERT ... ON CONFLICT (id) DO UPDATE SET ...` |
| `findById` / `deleteById` / `countByActive` / `existsById` | Standard CRUD verbs |

### AnalyticsPersistence

Scoped queries that operate on `order_metrics` / `daily_snapshot`. Illustrates an analytics-only API surface separate from the operational `BasePersistence` / `CrudPersistence`.

### OrderSlice

Composes all three persistences in a single `@Slice` factory. Demonstrates transitive resource provisioning — the slice processor sees the three persistence parameters, walks their `@PgSql` meta-annotation to find `PgSqlConnector`, and generates resource wiring.

## Running the tests

```bash
mvn -pl examples/comprehensive-persistence test
```

The tests use inline stubs (not a real database) to verify that the generated factories and the slice composition type-check, the slice factory returns working wiring, and record shapes are consistent with the schema.

## Validator gaps documented in the example

- **Multi-datasource qualifier** (`@AnalyticsPgSql`) — the [persistence spec](../../aether/docs/slice-developers/persistence-guide.md#multiple-datasources) describes applying a custom qualifier at the interface level to bind to a different config section (and schema folder). The current processor only triggers on `@PgSql` itself (`@SupportedAnnotationTypes`), so `AnalyticsPersistence` uses `@PgSql` and the extra `[database.analytics]` / `schema/analytics/` are included as documentation of the intended pattern. `AnalyticsPgSql.java` is included to show the qualifier definition.
- **CTE (`WITH`) queries** — the query validator does not yet recognise CTE aliases as tables, so a `SELECT ... FROM shipped` where `shipped` is a CTE reports `Table 'shipped' not found in schema`. Once this lands, the `findShippedViaCte` pattern can be reintroduced.
- **Record expansion in `@Query`** — `INSERT INTO t VALUES(:record)` and `UPDATE t SET :record` run expansion correctly (the emitted SQL is right) but flag the record's fields as "unused method parameters". CRUD `insert()` / `save()` with record input types are the warning-free equivalents and are used throughout.
- **Array return columns** — `TEXT[]` columns emit constructor references whose element types do not match the mapper's `getString()` accessor (`String[]` vs `String`). For now we keep arrays OUT of return records and exercise them in schema only (JSONB and UUID are on the happy path).
- **`@Query` parameter types** — the factory generator strips package prefixes from parameter type names but does not emit corresponding imports. Keeping @Query parameters to `String`, `Long`, `boolean`, `double` (and record wrappers for INSERT-shaped calls) avoids the issue.
