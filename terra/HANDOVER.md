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

## HTTP increment

The new `terra/http` module hosts selected generated routers over HTTP/1.1 and TLS, in both path and header versioning modes. It enforces the actual selected handler's security policy using shared API-key/JWT verification from `aether/http-security`; the Aether node retains its cluster-key adapter. Startup failure releases owned application/authentication resources; close withdraws readiness, drains accepted handlers and transport flushes, stops the listener, then releases resources. `/__terra/health/live` and `/__terra/health/ready` provide probes; `status()` exposes request/write counters.

Transport prerequisites fix invalid-TLS plaintext fallback, port-zero reporting, and add write-flush completion. A Catalog network regression also exposed shared primitive/composite error mapping; the adapter now preserves client statuses and retains 500 for mixed client/server domain failures.

Clean focused validation passed 158 tests: 36 transport, 25 routing adapter, 83 shared authentication, 11 Terra HTTP, and 3 examples. Four opt-in PostgreSQL tests were skipped in this HTTP-only run (their prior live proof remains recorded above). Another 139 focused Aether HTTP/security tests passed after a clean node build: **297 executed tests**. The downstream node reactor compiled with no skipped modules. HTTP/security/adapter/example and node JBCT gates passed.

## Current next step

The user authorized autonomous work for the next few hours toward a working Terra and as many unchanged examples as practical, including investigation of streams and durable entities. Executable assembly is now implemented and verified. Next broaden examples (ecommerce, URL shortener, and step composition are candidates). Assess local stream/entity storage and recovery before claiming support; current processor refusal remains in place. Continue making cohesive validated local commits; do not push or merge.

## Build hygiene

Use the isolated Maven repository required by `CLAUDE.md`; do not write shared `~/.m2`. The developer checkout has a local absolute repository setting in `.mvn/maven.config`, protected with `skip-worktree`, and `.m2-local/` excluded via `.git/info/exclude`. Keep these machine-specific settings out of commits. Maven uses Java 25; ensure the same JDK when launching the demo.

Changelog fragments currently use provisional number `0`; rename them when a real issue/PR number exists. Workspace-level `AGENTS.md` and the detailed investigation handover live outside the repository; this file carries the portable implementation handover.

## Executable assembly increment

`terra/launcher` and `terra/maven-plugin` now produce an attached ZIP from Maven runtime JARs, with blueprint-selected factories, isolated JAR defaults, explicit override layers, host configuration, migration preflight, and shutdown hooks. See `launcher/README.md`. Clean validation passed 36 tests plus all touched JBCT gates; four opt-in database tests skipped. Extracted distribution served unchanged Catalog outside the checkout and shut down on SIGTERM. Windows script is supplied but untested. Maven dependency declarations locate candidate JARs; the assembler does not independently rebuild/resolve blueprint coordinates.
