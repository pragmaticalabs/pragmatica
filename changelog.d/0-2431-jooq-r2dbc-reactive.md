### Fixed
- The jOOQ-over-R2DBC connectors (`db-jooq-r2dbc`, `db-jooq-async`) can run queries: `fetchOne`, `fetchOptional`, `fetch`, `execute` and `transactional` no longer fail with jOOQ's `DetachedException`. Queries now run through jOOQ's reactive API on the R2DBC connection, and a constraint violation keeps its SQLSTATE, a lost connection stays transient and is retried (#2431).
