### Fixed (#2795: pg-codegen CRUD methods reject a nullable column read into a non-`Option` component)
- **A CRUD-generated method (`findById`, `findBy...`, `save`, `insert`, no `@Query`) whose record has a non-`Option`
  component for a NULLABLE table column now fails the build** instead of failing at runtime on the first NULL row. The
  diagnostic names the method, the column (`table.column`), the component and its type, and the fix (declare
  `Option<T>`, or declare the column `NOT NULL`). `@Query` methods already carried this check (#2889). The check is
  per column, so `findByXIsNotNull` does not excuse a plain component, and an `Option<T>` component on a NOT NULL
  column stays accepted on CRUD methods. Breaking: application row records for CRUD tables must declare
  `Option<T>` for nullable columns (the `comprehensive-persistence` and `pg-showcase` examples now do).
- **The schema model reports column nullability as PostgreSQL does.** Columns of a table-level primary key or of one added
  by `ALTER TABLE ... ADD [CONSTRAINT ...] PRIMARY KEY (...)`, and `SERIAL`/`BIGSERIAL`/`SMALLSERIAL` columns, are NOT NULL
  (they were reported nullable unless the column also said `NOT NULL`, so the CRUD and `@Query` checks refused a correct
  plain component). Dropping the key leaves them NOT NULL; `DROP NOT NULL` relaxes them. A `GENERATED ALWAYS AS (...) STORED`
  column is nullable unless it says `NOT NULL` (it was reported NOT NULL). Breaking where a build relied on the old value:
  an `@Query` component for such a key or serial column must be the plain type, not `Option<T>`; an `insert`/`save` must
  supply every table-level primary-key column; a stored generated column read into a plain component is refused; the
  generated row records and the jOOQ export (`is_nullable`) follow the same values. Lint rule PG001 no longer reports
  an added stored generated column as NOT NULL without DEFAULT.
