# Aether Schema Management — Design Specification

## Overview

Schema migration is a cluster-level concern managed by the Aether runtime, not by individual application nodes. Applications declare their required schema versions; Aether guarantees the schema is at the expected state before routing traffic. No external migration libraries (Flyway, Liquibase) are used — Aether provides a lightweight, built-in migration engine.

## Core Principles

1. **Per-datasource lifecycle.** Each configured datasource has its own independent schema history, migration scripts, and version tracking. The migration history table (`aether_schema_history`) resides in the datasource it governs — no central catalog.
2. **Leader-driven execution.** Migration execution is coordinated via Rabia consensus, scoped to the set of nodes sharing a given datasource. Exactly one node executes migrations; others wait.
3. **Application code is schema-unaware.** Applications declare required schema versions in deployment config. The runtime handles the rest.
4. **Engine-agnostic migration engine, engine-specific scripts.** The migration engine uses plain JDBC with no dialect-specific logic. SQL scripts are inherently engine-specific and organized by datasource.

## Configuration

Datasource schema configuration is declared in TOML as part of the service deployment descriptor.

```toml
[service]
name = "order-processor"

[service.datasources.orders_db]
schema_version = 12
migrations = "classpath:/migrations/orders/"

[service.datasources.analytics_db]
schema_version = 5
migrations = "classpath:/migrations/analytics/"

[service.schema]
# Execute migrations for multiple datasources in parallel.
# Set to false to execute sequentially in declaration order.
parallel = true
```

When sequential execution is needed (rare, implies cross-database dependencies):

```toml
[service.schema]
parallel = false
order = ["orders_db", "analytics_db"]
```

## Migration Scripts

Scripts follow a simple naming convention:

```
migrations/orders/
  V001__create_orders_table.sql
  V002__add_status_column.sql
  V003__add_email_index.sql
```

- Prefix: `V` + zero-padded version number.
- Separator: `__` (double underscore).
- Suffix: descriptive name, `.sql` extension.
- Scripts are immutable once applied. Checksums are validated against history.
- Scripts should use defensive idioms (`IF NOT EXISTS`, `IF EXISTS`) for idempotency where the database engine supports them.

## Migration History Table

Created automatically (bootstrap) in each datasource on first migration run.

```sql
CREATE TABLE IF NOT EXISTS aether_schema_history (
    version        INTEGER      NOT NULL PRIMARY KEY,
    description    VARCHAR(256) NOT NULL,
    script         VARCHAR(512) NOT NULL,
    checksum       BIGINT       NOT NULL,
    applied_by     VARCHAR(128) NOT NULL,  -- Aether node ID
    applied_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    execution_ms   INTEGER      NOT NULL
);
```

## Deployment Lifecycle

### Phase 1 — Schema Transition Gate

1. New deployment is submitted to Aether with updated service descriptor.
2. Aether compares declared `schema_version` per datasource against current state (queried from `aether_schema_history`).
3. For each datasource requiring migration, Aether enters a **schema transition phase** scoped to that datasource.

### Phase 2 — Leader Election and Migration Execution

1. Via Rabia consensus (scoped to nodes sharing the datasource), a single leader is elected for migration execution.
2. The leader runs `AetherSchemaManager` for the datasource:
   - Reads `aether_schema_history`.
   - Identifies pending scripts (those above current version, up to target).
   - Validates checksums of previously applied scripts.
   - Executes pending scripts sequentially within a transaction (where supported).
   - Records each applied migration in the history table.
3. Non-leader nodes poll or are notified of completion.

### Phase 3 — Readiness Gate

1. All datasources for the service must reach their declared `schema_version`.
2. Only then does Aether mark new application instances as ready for traffic.
3. Old instances continue serving during the transition (expand-contract pattern).

### Deployment Modes

Aether distinguishes two modes based on datasource state:

- **Fresh provisioning:** Datasource has no `aether_schema_history` table. Bootstrap the table, then apply all migrations from V001.
- **Upgrade:** History table exists. Validate existing checksums, apply only pending migrations.

## Expand-Contract Support

For zero-downtime schema changes (column renames, type changes), Aether supports multi-phase deployments:

```toml
[service.datasources.orders_db]
schema_version = 14

[service.schema.phases]
# Phase 1: expand migration adds new column (V013)
# Phase 2: deploy dual-write code
# Phase 3: backfill (V014, leader-driven)
# Phase 4: deploy read-from-new code
# Phase 5: contract migration drops old column (future deployment)
```

Each phase has its own readiness criteria. Aether orchestrates the sequencing across the cluster.

## Migration Engine — `AetherSchemaManager`

A lightweight, reusable component. One instance per datasource.

**Responsibilities:**
- Read and diff migration history against available scripts.
- Execute pending scripts via plain JDBC.
- Compute and validate checksums (e.g., CRC-32 of script content).
- Record results in `aether_schema_history`.

**Non-responsibilities:**
- No dialect-specific SQL generation.
- No rollback execution (forward-only; compensating migrations are written as new scripts).
- No classpath scanning magic — script location is explicit from config.

**Characteristics:**
- Stateless — all state lives in the datasource's history table.
- Runs on the Netty event loop (blocking JDBC calls offloaded to a dedicated executor).
- Minimal footprint — estimated at a few hundred lines of code.

## Cluster-Level Observability

Aether exposes schema state as observable infrastructure:

- **Per-datasource schema version** across all nodes in the cluster.
- **Migration execution log** (timestamp, duration, node that applied).
- **Drift detection** — alerts when a node reports a schema version that doesn't match the cluster-expected version.
- **Checksum mismatch detection** — flags when a previously applied script has been modified.

## Error Handling

- **Migration failure:** The leader node reports failure to the cluster. Deployment is halted. No traffic is routed to new instances. The failed migration and error details are surfaced in cluster monitoring.
- **Checksum mismatch:** Treated as a fatal error. Deployment is blocked until resolved. Indicates script tampering or accidental modification.
- **Datasource unreachable:** Migration is retried with backoff. If the datasource remains unavailable beyond a configurable timeout, the deployment fails.
- **Partial migration (crash mid-execution):** On next attempt, the new leader reads history, determines which scripts were actually applied, and resumes from the correct point. Idempotent script design helps here.
