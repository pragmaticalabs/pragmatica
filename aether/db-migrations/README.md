# Shared database migrations

This module contains the execution/history engine extracted from Aether deployment for use by both Aether and Terra. It depends on the SQL connector API and dialect-aware SQL splitter; it has no dependency on deployment orchestration, artifact loading, cluster state, or management HTTP.

`SchemaMigrations` accepts `MigrationScript` data, a connector, an execution identity, and the versionless owning blueprint coordinate. It supports the existing migrate, undo, and baseline operations. Callers own connector lifetime and concurrency policy. Runtime adapters must validate and provide the owner identity; a different application must never use the runtime name as an owner.

`AetherSchemaManager` remains the Aether adapter. It translates blueprint migration entries, strips the blueprint version, maps shared failures (including composite failures) back to the existing `SchemaError` variants, and preserves the policy and result API consumed by Aether orchestration. Consensus leases, retry/status policy, management HTTP mapping, and deployment gating remain in Aether.

Terra’s `TerraMigrations` loads exploded blueprint scripts and serializes attempts in the JVM. It awaits SQL and connector cleanup before allowing the next attempt or constructing slices. Its supported deployment envelope is one process per database.

The extraction retains the execution algorithms and persisted `aether_schema_history`, `aether_schema_history_meta`, and `aether_schema_owner` structures. PostgreSQL transactional DDL and its history insert share a transaction; nontransactional execution keeps statement checkpoints and the existing statement-commit/checkpoint crash window. Recovery and dialect behavior are unchanged by the module move.

Pure parsing/dialect/history-evolution tests moved into this module. Existing Aether manager, resume, PostgreSQL, and schema-route tests exercise the shared code through the Aether adapter. Terra’s database tests additionally exercise real PostgreSQL startup and the existing InventoryService source. See [Terra’s specification](../../terra/SPEC.md) for the commands actually run and their evidence limits.
