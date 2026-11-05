### Fixed
- Parameterised statements through `postgres-r2dbc-adapter` (and so `db-r2dbc` on PostgreSQL) no longer fail with SQLSTATE 42601. The adapter used to rewrite `$n` placeholders to `?`, which the server rejects; the SQL is now sent unchanged (#2432).
