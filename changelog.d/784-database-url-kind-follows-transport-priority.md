### Fixed (2026-10-04 — #784: database URL-derived values did not follow the connecting transport)
- **`DatabaseConnectorConfig` derived the effective host, port, database and credentials from the URL kinds in the
  fixed order jdbc, r2dbc, async**, the reverse of the transport-selection priority (async, r2dbc, jdbc), so a config
  setting `jdbc_url` to host A and `async_url` to host B selected the async transport and handed it host A.
- **Each connector now derives host, port, database, credentials and database type from ITS OWN URL**
  (`effectiveHost(Transport)` and the other `effective*(Transport)` accessors; every db connector factory and
  connector passes its transport; `DatabaseConnector#databaseType()` gives schema migration the dialect of the
  database the connector actually uses). A fixed global priority was not enough, because the transport that connects
  is the best one on the slice's classpath: a MySQL `jdbc_url` plus a PostgreSQL `async_url` with JDBC selected must
  give the JDBC pool the jdbc credentials and MySQL. A connector whose own URL is absent or cannot supply a value
  falls through to the other URLs in the documented priority, then the discrete fields, so single-URL configurations
  (every shipped one) behave as before. The no-argument `effective*()` accessors remain as the datasource-level view
  for the provisioning log, taken from `preferredTransport()` (async, then r2dbc, then jdbc). `DatabaseType.fromAnyUrl`
  follows that priority. The `resource-reference.md` caveat is replaced by the rule.
