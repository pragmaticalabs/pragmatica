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
- Positions PostgreSQL does not correlate with the statement they belong to see only what lies
  outside it and keep reporting: a CTE body sees the scope enclosing its statement (so a CTE inside
  a subquery correlates with the outer query, as PostgreSQL 17 accepts, while a top-level
  statement's body never sees that statement's own FROM), a `RECURSIVE` body additionally sees the
  `WITH` names — on `SELECT`, `UPDATE` and `DELETE` alike — the `INSERT` source query sees the
  statement's `WITH` names but never its target, and a derived table without `LATERAL` sees nothing.
  [verified: `validate_cteBodyInsideSubquery_seesTheEnclosingScope`,
  `validate_topLevelCteBody_doesNotSeeTheStatementsFrom`, `validate_recursiveCteOnUpdate_isClean`,
  `validate_recursiveCteOnDelete_isClean` (both red at the first head `af54e2dfa`, where a DML
  `WITH RECURSIVE` regressed against the tip), `validate_insertSourceSelect_seesTheInsertsOwnCte`,
  `validate_insertSourceSelect_doesNotSeeTarget`, `validate_nonLateralDerivedTable_doesNotSeeOuterScope`;
  reach controls `validate_bogusTableInsideRecursiveCteBody*_errors`,
  `validate_bogusColumnInsideLateralSubquery_errors` — a skipped body reddens them]
- A scope now holds only the relations its own `FROM` list declares: the previous three overlapping
  deep `findAll` passes registered a derived table's inner relations in the enclosing scope (and
  resolved each relation up to three times, reporting an unknown table twice). With scopes chained
  that leak would have let a sibling subquery resolve another's alias.
  [verified: `validate_derivedTableRelations_doNotLeakIntoOuterScope`, `validate_siblingSubqueryAlias_isNotVisible`]
- Not changed here, filed separately: a derived table's or function table's alias is still not
  registered (`SELECT d.id FROM (SELECT id FROM users) d` reports `Table or alias not found: d`), and a
  non-recursive CTE body still cannot see an earlier CTE of the same `WITH`. [unverified: `LATERAL`
  position order — a lateral subquery may reference any relation of the enclosing FROM list, not
  only preceding ones; a correlated subquery in a statement-level `ORDER BY`/`LIMIT` is still
  refused (it is walked with the statement's enclosing scope, not the core's FROM), as before]
