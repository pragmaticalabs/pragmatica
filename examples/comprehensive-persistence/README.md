# comprehensive-persistence

End-to-end `@PgSql` persistence example exercising the full surface area of the compile-time validator, the CRUD name parser, the row-mapper generator, and the multi-datasource resolver — across two schemas (`database` and `database.analytics`) composed into a single `@Slice`.

## Layout

```
comprehensive-persistence/
├── pom.xml
├── src/main/resources/
│   ├── resources.toml                       # Primary + analytics datasource configuration
│   └── schema/
│       ├── V001__base.sql                   # Tables: customers, products, orders, order_items
│       ├── V002__add_constraints.sql        # FKs, UNIQUE constraints, indexes
│       ├── V003__add_columns.sql            # ENUM, JSONB, UUID, GENERATED column, CHECK, partial index
│       ├── V004__seed.sql                   # Seed data for local smoke runs
│       ├── V005__add_columns.sql            # TEXT[] array column (tags)
│       └── analytics/
│           └── V001__base.sql               # Analytics schema: order_metrics, daily_snapshot
├── src/main/java/.../comprehensive/
│   ├── AnalyticsPgSql.java                  # Custom @ResourceQualifier bound to database.analytics
│   ├── AnalyticsPersistence.java            # @AnalyticsPgSql-scoped queries
│   ├── BasePersistence.java                 # @Query: JOINs, aggregates, subqueries, CTE, arrays
│   ├── CrudPersistence.java                 # CRUD with every operator suffix
│   └── OrderSlice.java                      # @Slice composing all three persistences
└── src/test/java/.../comprehensive/
    └── ComprehensivePersistenceTest.java
```

## Features demonstrated

| Feature | Where |
|---|---|
| Custom `@ResourceQualifier` qualifier | `AnalyticsPgSql` → triggers processor on `AnalyticsPersistence` with `config = "database.analytics"` |
| Isolated schema per datasource | `schema/analytics/` loaded for `AnalyticsPersistence`, primary `schema/` for everything else |
| Transitive resource wiring | `OrderSliceFactory` provisions two `PgSqlConnector`s from the slice method signature |
| `BIGINT GENERATED ALWAYS AS IDENTITY` | `orders.id`, `customers.id`, `order_metrics.id` — omitted from `NewOrderMetric` input record, exempted by NOT-NULL coverage check |
| `GENERATED ALWAYS AS (expr) STORED` | `orders.total_with_tax` — exempted from INSERT coverage |
| `TEXT[]` array column | `products.tags` → mapped to `String[]` via `row.getObject("tags", String[].class)` |
| Non-primitive `@Query` parameters | `UUID` in `findByCorrelationId`, `Instant`/`BigDecimal` in `recentOrderTotalForCustomer` — imports auto-collected in generated factory |
| Non-primitive scalar return types | `Promise<BigDecimal>` from `recentOrderTotalForCustomer` and `recentRevenueForCustomer` — row accessor inferred via `TypeMapper` |
| Common Table Expression (`WITH`) | `recentOrderTotalForCustomer` and `recentRevenueForCustomer` — CTE alias registered in validator scope |
| JOINs with alias resolution | `findCustomerOrdersByStatus`, `productSalesReport` |
| Aggregates + `GROUP BY` | `customerRevenueReport`, `productSalesReport` |
| Subquery in WHERE | `ordersForDomain` |
| Complex WHERE (AND/OR) | `findHighValueOrders` |
| Every CRUD operator suffix | `findByTier`, `findByTierNot`, `findByNameLike`, `findByDeletedAtIsNull`, `findByDeletedAtIsNotNull`, `findByTierAndActive`, `findByActiveOrderByNameAsc`, `findByActiveOrderByTierAscNameDesc` |
| `save(record)` with `ON CONFLICT` upsert | `CrudPersistence.save`, `AnalyticsPersistence.save` |
| `insert(record)` omitting identity column | `AnalyticsPersistence.insert(NewOrderMetric)` |
| JSONB, UUID, ENUM column types | `customers.preferences`, `orders.metadata`, `orders.correlation_id`, `order_status` enum |

## Running the tests

```bash
mvn -pl examples/comprehensive-persistence test
```

The tests use inline stubs (no real database) to verify that:
- All generated factories compile (proving the compile-time validator accepts every query in `BasePersistence`, `CrudPersistence`, and `AnalyticsPersistence`).
- The `OrderSlice` factory composes the three persistences end-to-end.
- Record shapes match the schema columns (including array columns and UUID-typed `correlation_id`).
