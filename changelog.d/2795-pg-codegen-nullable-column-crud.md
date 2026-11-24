### Fixed (#2795: pg-codegen CRUD methods reject a nullable column read into a non-`Option` component)
- **A CRUD-generated method (`findById`, `findBy...`, `save`, `insert`, no `@Query`) whose record has a non-`Option`
  component for a NULLABLE table column now fails the build** instead of failing at runtime on the first NULL row. The
  diagnostic names the method, the column (`table.column`), the component and its type, and the fix (declare
  `Option<T>`, or declare the column `NOT NULL`). `@Query` methods already carried this check (#2889). The check is
  per column, so `findByXIsNotNull` does not excuse a plain component, and an `Option<T>` component on a NOT NULL
  column stays accepted on CRUD methods. Breaking: application row records for CRUD tables must declare
  `Option<T>` for nullable columns (the `comprehensive-persistence` and `pg-showcase` examples now do).
