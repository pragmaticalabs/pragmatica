# Terra implementation handover

Current checkpoint (2026-10-08): compiler, migrations, HTTP/authentication, executable assembly, and expanded examples are implemented. Nine distributions compile 21 original slice implementations. The sections below retain each increment's scope and evidence; [the example matrix](example-apps/README.md) describes current application support. Streams/entities remain explicitly unsupported, with an investigation and proposed next contract.

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

## Current state and next work

The expanded [application matrix](example-apps/README.md) now builds eight distributions in addition to Catalog, compiling 21 original slice implementations including both URL-shortener versions. Original business sources remain unchanged. HTTP/process tests run ecommerce, URL shortener v1/v2, and pricing; embedding tests exercise banking, step composition, PG showcase, and two-datasource comprehensive persistence against PostgreSQL. The latter retains explicit failures for its original nullable/non-optional row fields; populated joins/aggregates and arrays pass.

The compiler now binds single-payload subscriptions on injected plain-interface steps and imports type tokens for keyed multi-parameter interceptors. The launcher exposes owned `startApplication()` for programs without HTTP routes. Three defects discovered on live examples are fixed in shared modules: JDBC Instant parameters, generic native PostgreSQL binary decoding, and cache maintenance completing after its business call.

[Streams and durable entities](STREAMS-AND-ENTITIES.md) identifies reusable WAL/state-machine code and proposes recovery, cursor, timer, ownership, and codec requirements. These remain future work and explicitly refused by Terra. Existing foundation tests passed (28 stream WAL/recovery/retention, 41 entity fold/timer/serialization); that is not a Terra integration proof. Implement local storage ownership and codecs before admitting stream/entity resources.

Continue making cohesive validated local commits; do not push or merge. Required Forge/review gates remain before publication. Windows launcher execution and cross-process database startup remain outside the verified scope.

## Build hygiene

Use the isolated Maven repository required by `CLAUDE.md`; do not write shared `~/.m2`. The developer checkout has a local absolute repository setting in `.mvn/maven.config`, protected with `skip-worktree`, and `.m2-local/` excluded via `.git/info/exclude`. Keep these machine-specific settings out of commits. Maven uses Java 25; ensure the same JDK when launching the demo.

Changelog fragments currently use provisional number `0`; rename them when a real issue/PR number exists. Workspace-level `AGENTS.md` and the detailed investigation handover live outside the repository; this file carries the portable implementation handover.

## Executable assembly increment

`terra/launcher` and `terra/maven-plugin` now produce an attached ZIP from Maven runtime JARs, with blueprint-selected factories, isolated JAR defaults, explicit override layers, host configuration, migration preflight, and shutdown hooks. See `launcher/README.md`. Clean validation passed 36 tests plus all touched JBCT gates; four opt-in database tests skipped. Extracted distribution served unchanged Catalog outside the checkout and shut down on SIGTERM. Windows script is supplied but untested. Maven dependency declarations locate candidate JARs; the assembler does not independently rebuild/resolve blueprint coordinates.

## Expanded examples verification

The clean application reactor built all 20 child modules; eight opt-in PostgreSQL tests passed, followed by a reviewed rerun of all eight. The original fixture passed nine tests with all four database tests enabled. Processor 364, runtime/launcher 27, JDBC/resource 40, generic native PostgreSQL 4, and interceptor 139 focused tests passed. Existing stream/entity foundation suites passed 69 tests. The support matrix separates process, embedding, and partial original-example behavior.

Relevant Terra/resource JBCT gates pass. Forced legacy gates retain baseline debt: processor 38 lint/10 format failures, integration JDBC five lint failures, native PostgreSQL converter 43 lint failures. Changed production files are formatted; these legacy checks are not claimed green. The converter and cache fixes have separate commits from the Terra application expansion.

The downstream `aether/node -am install -DskipTests` reactor passed after the shared fixes, with no skipped modules.

## Extension boundary review

Terra rejects null slice instances and null factory, binding, resource-acquisition, or cleanup completion values with operation-specific failures. Five fault-injection cases in `TerraApplicationTest` exercise these extension defects and verify reverse cleanup reaches every scope. Generated factories obey these contracts; the guards cover custom embedding implementations. This does not attempt to compensate arbitrary external effects performed by a defective factory.

After boundary hardening, `mvn -o -T 1 -f terra/pom.xml clean install -Dterra.test.jdbcUrl=…` rebuilt the entire Terra subtree and all nine distributions. **64 tests passed with zero failures, errors, or skips**, including HTTP/TLS, 12 live database tests, and extracted-process tests. The explicit runtime JBCT gate passed (zero lint errors or format issues).

## Nested resource validation review

A compiler regression reproduced a stream resource accepted through an injected plain step: the shared `PlainInterfaceModel.dependencies` list is currently empty. Terra now inspects the same static factory parameters used by shared generation. Parameterized tests require explicit refusal of nested `StreamPublisher`, `StreamAccess`, and durable-entity resources. This change is Terra-only and does not change Aether's factory/envelope output. The changed descriptor's explicit JBCT check passes with zero lint errors or format issues.

Final compiler validation passed **367 tests**. All 20 application child modules then clean-built successfully with that compiler; this last assembly-only run skipped tests, following the successful 64-test full Terra run above. The isolated PostgreSQL test cluster was stopped after verification. No original `examples/` sources were modified.
