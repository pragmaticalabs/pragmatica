# Terra implementation handover

Work is on `feat/terra-runtime`, based on `release-1.0.0-rc4` at `c39009df6`. The specification and usage guide are [SPEC.md](SPEC.md) and [README.md](README.md). The first commit extracts shared migrations; the Terra commit adds the runtime, compiler backend, and examples. Publishing and merging have not been requested.

## Completed

- Rebuild unchanged slice sources with `slice.target=terra`. Shared analysis, resource injection, interception, and route generation; direct typed dependencies and generated factory SPI descriptors.
- Blueprint-selected assembly, explicit per-slice configuration, scoped resource cleanup, LOCAL cache reuse, and ephemeral in-process pub-sub with failure aggregation and shutdown draining.
- Shared database migration execution/history in `aether/db-migrations`, with Aether's existing policy/result/error adapter. Persisted formats, checksums, ownership, transactional behavior, and statement checkpoints remain intact.
- Terra startup from an exploded blueprint plus sibling `schema/`, with process-wide serialized migrations and connector cleanup before constructing slices. One process per database is the supported envelope.
- Existing Catalog, PricingService, and InventoryService compile from their original source directories. A runnable composition demo and real PostgreSQL startup tests exercise the runtime.

## Verification

The second increment passed 605 focused tests: 44 shared migration tests, 17 Terra runtime tests, 362 processor tests, 6 example tests (including 4 real PostgreSQL tests), 95 Aether schema tests, and 81 node schema-route tests. Target modules were clean-built after moving sources. The downstream node reactor passed with no skipped modules. Relevant JBCT gates passed; the processor retains its pre-existing rc4 lint debt. See the specification for exact commands and limits.

The isolated local PostgreSQL instance used for verification was stopped. Database tests require `-Dterra.test.jdbcUrl`; without it they explicitly skip. No Forge, cloud, or multi-node validation has been performed. Run the repository's required Forge gate and review before any push involving the deployment changes.

## Current next step

Implement Terra HTTP hosting using the existing generated routers and lower-level HTTP server. Preserve routing/error/version behavior and declared route security, discover only blueprint-selected slice routes, reject collisions, publish readiness only after startup, and drain requests before closing application resources. Authentication policy and supported protocols need explicit contracts. Executable distribution/blueprint artifact assembly follows after HTTP.

## Build hygiene

Use the isolated Maven repository required by `CLAUDE.md`; do not write shared `~/.m2`. The developer checkout has a local absolute repository setting in `.mvn/maven.config`, protected with `skip-worktree`, and `.m2-local/` excluded via `.git/info/exclude`. Keep these machine-specific settings out of commits. Maven uses Java 25; ensure the same JDK when launching the demo.

Changelog fragments currently use provisional number `0`; rename them when a real issue/PR number exists. Workspace-level `AGENTS.md` and the detailed investigation handover live outside the repository; this file carries the portable implementation handover.
