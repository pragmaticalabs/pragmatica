### Fixed (2026-09-14 — #651: pg-schema correlated subqueries could not see the outer statement's scope)
- **`QueryValidator` validated every `SelectCore` in the tree standalone**, so a correlated
  subquery — `UPDATE reservations SET state = 'released' WHERE EXISTS (SELECT 1 FROM bookings b WHERE
  b.reservation_claim_id = reservations.claim_id)` — reported the outer name as `Table or alias not
  found: reservations`. Every correlated `EXISTS`/`IN`/scalar subquery, under `SELECT` as much as under
  DML, was a hard `[PG-VALIDATE]` error on legal SQL.
  [verified: `aether/pg-tools/pg-schema/src/test/java/org/pragmatica/aether/pg/schema/validator/QueryValidatorTest.java`
  — `CorrelatedSubqueries`, 20 of its 24 tests red against the `2005ea7d2` validator]
- Statements are now validated by structure, each in the scope that encloses it: a subquery's scope
  chains to the enclosing statement's, names resolve inner-first and fall back outward, and a
  subquery's own `WITH` chains the same way. `UPDATE`/`DELETE` `WHERE`, `SET` values, `USING` and
  `RETURNING`, `INSERT ... RETURNING`, and `ON CONFLICT DO UPDATE` (with `EXCLUDED`) all pass their
  scope down. [mechanism: `QueryValidator.validateStatementsIn` replaces the `findAll("SelectCore")`
  sweep and the span-keyed CTE scope index]
- Positions PostgreSQL does not correlate get no enclosing scope and keep reporting: a CTE body (a
  `RECURSIVE` one sees the `WITH` names, as before), an `INSERT` source query, and a derived table
  without `LATERAL`. [verified: `validate_insertSourceSelect_doesNotSeeTarget`,
  `validate_nonLateralDerivedTable_doesNotSeeOuterScope`, `validate_recursiveCteBody_seesItsOwnName`]
- A scope now holds only the relations its own `FROM` list declares: the previous three overlapping
  deep `findAll` passes registered a derived table's inner relations in the enclosing scope (and
  resolved each relation up to three times, reporting an unknown table twice). With scopes chained
  that leak would have let a sibling subquery resolve another's alias.
  [verified: `validate_derivedTableRelations_doNotLeakIntoOuterScope`, `validate_siblingSubqueryAlias_isNotVisible`]
- Not changed here, filed separately: a derived table's or function table's alias is still not
  registered (`SELECT d.id FROM (SELECT id FROM users) d` reports `Table or alias not found: d`), and a
  non-recursive CTE body still cannot see an earlier CTE. [unverified: `LATERAL` position order — a
  lateral subquery may reference any relation of the enclosing FROM list, not only preceding ones]
