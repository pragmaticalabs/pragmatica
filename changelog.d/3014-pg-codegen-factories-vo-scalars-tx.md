### Fixed (2026-11-11 — #3014, #3015, #3018: pg-codegen and `PgSqlConnector.transactional`)
- **`PgSqlConnector.transactional` now hands its callback a `PgSqlConnector`** (#3014), through a `PgTransactionCallback`
  overload; `@PgSql`-generated adapters run inside a transaction without a user wrapper. Commit and rollback are the
  base connector's.
- **A single-column `@Query` returning a value object with `valueMapping()`** (`Promise<V>`, `Promise<Option<V>>`,
  `Promise<List<V>>`) reads its one column and decodes it through `valueMapping().lift()` (#3015), instead of being
  mapped as a row record.
- **Generated row mappers build records through the type-named static factory** (#3018), never `::new`: `map` for a
  factory returning `T`, `flatMap` for one returning `Result<T>` (a rejected row fails the method). A row record without
  the factory is a build error naming the expected signature. Breaking: record fixtures and application row records
  need the factory.
