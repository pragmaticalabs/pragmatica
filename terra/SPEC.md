# Terra runtime specification

Terra builds unchanged Aether slice sources into one JVM application. The application is selected by an Aether-format blueprint and assembled from factories generated for Terra. The initial deployment contract is one process per database. Existing Aether binaries are not a compatibility target.

## First increment acceptance contract

This increment establishes the compiler and executable composition path. It must compile and exercise the existing Catalog and ecommerce PricingService sources without copying or editing their business code, plus a composition/pub-sub fixture. It supplies an embeddable application runtime and typed HTTP routers. Database migration extraction is covered by the second increment below. A production HTTP listener and a standalone distribution/assembly Maven goal remain subsequent milestones, not acceptance claims for this increment.

1. `-Aslice.target=terra` selects the backend of the existing processor. The default remains Aether. Analysis, resource injection, factory return handling, method interception, and route generation are shared. Terra emits direct dependency injection and a generated factory descriptor, without Aether method handles, `Slice` adapters, or wire codecs.
2. Generated descriptors identify the slice interface, versioned artifact, and required slice interfaces. They construct the slice through its user factory and register supported topic subscriptions. An application selects exact artifact coordinates from `[[slices]]` entries. All required dependencies must be included. Duplicates, missing dependencies, cycles, and unknown artifacts fail before resource acquisition. Replica/scaling fields are deployment-only and do not create extra instances. Unsupported blueprint sections fail explicitly.
3. Startup constructs dependencies before dependents. All slices are ready and all subscriptions registered before publication is admitted. Dependency objects are the intercepted instances, so direct calls retain interceptor behavior. Typed lookups are available to application entry points and tests; no lookup occurs on each generated inter-slice call.
4. Resources retain a slice identity for configuration and release. Provisioning reuses the SPI with explicit configuration, not process-global singleton configuration. Concurrent acquisitions must be accounted for during failed startup: drain acquisitions before releasing all attempted scopes. Failed factory construction still releases its resources. Closing is idempotent and rejects new topic publications before draining accepted deliveries and releasing resources.
5. Local pub-sub is ephemeral fan-out. A publication snapshots the registered handlers, invokes every handler once, and completes only after all handlers settle. Any failure fails the publication after all results are collected. There is no persistence, retry, deduplication, replay, cross-process delivery, or total ordering across concurrent publications. No subscribers means successful delivery to an empty set. Payload objects are shared by reference and must be treated as immutable. Subscriber defects become failed promises. Recursive publication is supported while the application is running; draining rejects newly initiated publications.
6. Existing `Publisher<T>` and single-payload `Subscriber` annotations are supported. Topic names are resolved consistently for publishers/subscribers from explicit topic configuration or typed constants. Durable topics, streams, schedules, durable entities, context-carrying subscribers, transitive reactive methods, and live config notifications are rejected in this increment. They must never silently become no-ops.
7. Configuration is supplied explicitly to each slice by the embedding application. It can differ between slices; there is no merging of JAR-local `resources.toml` files. Local cache and other SPI factories may be provided. Missing configuration/factories fail startup. Distributed cache/idempotency modes are not automatically converted to local modes. No automatic schema migration is claimed until the shared migration engine milestone lands.
8. Existing generated `SliceRouterFactory` adapters remain usable with Terra-created typed instances. Tests must demonstrate Catalog routing through that adapter. Network listener security, cross-slice route composition, and graceful HTTP draining belong to the listener milestone.

## Build and verification

The runtime and source-recompiling example module participate in Maven. Example sources are added from their existing directories. Terra compilation has separate output from normal example builds. Compiler tests exercise both backends; runtime tests cover graph validation, pub-sub failure/draining, failed startup cleanup, and direct calls. Tests must actually run; compilation alone does not establish runtime compatibility.

## Shared database migration milestone

Extract the execution/history portion of `aether-deployment/schema` into a shared module, retaining migration format, checksums, persisted history tables, physical blueprint ownership, transactional DDL/history updates, and nontransactional statement checkpoints. Translate blueprint packaging and runtime status at adapters. Keep Aether's consensus leases, artifact retrieval, and deployment gating in Aether. Terra serializes startup migrations, awaits them before constructing database-dependent slices, and owns connection cleanup. A timeout is not proof that SQL stopped, so retries cannot overlap a still-running attempt. This milestone must carry existing engine tests and add a Terra startup migration proof before database examples are declared supported.

### Second increment acceptance contract

1. `aether/db-migrations` owns execution, parsing, dialect selection, and history evolution. Its API uses neutral script data and a versionless owner coordinate, with no dependency on Aether deployment, blueprint loading, consensus, or management HTTP. Aether retains a compatibility adapter for its policy, result, and error types; no persisted schema format or existing execution algorithm is changed by the extraction.
2. Terra can start an exploded application from a blueprint file and its sibling `schema/` directory. Layout and UTF-8 CRC32 checksums match Aether blueprint packaging: `schema/*.sql` belongs to `database`, and `schema/<name>/*.sql` belongs to `database.<name>`. Migration-bearing applications require a versioned blueprint `id`; its versionless coordinate is the persisted owner. Invalid selection or migration filenames fail before database provisioning.
3. Terra provisions migration connectors through the existing datasource SPI with explicit configuration. It serializes all startup migration attempts in the process, including aliases and separate application objects. It releases each connector before proceeding. An attempt retains its place in the queue until SQL and cleanup actually settle, even if a caller stops waiting or times out. No automatic retry or cross-process coordination is added.
4. All migrations and their cleanup finish before any slice constructor runs. Failure prevents readiness and construction. Successful application objects expose per-datasource migration counts, current versions, and elapsed times. Schema changes remain committed if subsequent slice construction fails; restarting rechecks existing history.
5. Recompile the unchanged ecommerce InventoryService source and exercise its SQL calls after startup migration against a real PostgreSQL instance. Tests must also establish repeated-start idempotence, checksum/owner rejection, transactional failure behavior, migration-before-construction ordering, and failed-attempt cleanup/queue behavior. Existing Aether manager and schema route tests must exercise the shared engine through the adapter.

This increment does not add a schema administration CLI, a JAR resolver, production HTTP hosting, or application-wide automatic retry. Recovery is to correct the reported configuration/script/ownership problem and start again once the previous attempt has actually settled; applied versioned scripts must not be edited.

## Subsequent assembly and HTTP milestones

Add a Maven blueprint assembler that resolves/rebuilds Terra artifacts, checks classpath/version collisions, retains configuration resource ownership, and creates an executable distribution. Add an HTTP host around shared routing primitives with explicit authentication, readiness, and drain behavior. Keep the source annotations common across targets. Add further resources only with explicit contracts and executable examples.

## Implementation reconciliation

The first increment is implemented on `feat/terra-runtime`, based on `release-1.0.0-rc4` at `c39009df6`. Subsequent milestones remain design scope and are not part of its completion claim.

| Clause | Implementation and evidence |
| --- | --- |
| 1. Compiler backend | `SliceProcessor`, shared `FactoryClassGenerator`, and `TerraDescriptorGenerator`; `TerraProcessorTest` compiles Terra factories and checks absence of Aether adapters/codecs. The complete processor regression suite passes with Aether still the default. |
| 2. Blueprint/graph | `TerraBlueprint` and `TerraApplication.ordered`; runtime tests cover missing dependencies/artifacts, duplicate artifacts, cycles, and unsupported configuration. The example blueprint lists dependents before dependencies. |
| 3. Construction/injection | `TerraApplication.construct` and generated typed factories; `TerraExamplesTest` calls Shop through its injected Catalog, Counter, and Events dependencies, proving cached direct calls and subscriber delivery. Fixture factories cover direct, `Option`, `Result`, and `Promise` returns. |
| 4. Resource lifecycle | `TerraContext.Scope` and application cleanup; tests prove late acquisition drains before failed-startup release, reverse release order, repeated close, continued cleanup after a provider defect, and retention of both startup/cleanup failures. |
| 5. Pub-sub | `TerraTopicsTest` proves all-handler completion after failure, admission closure/drain, thrown-handler isolation, recursion, and empty fan-out; example tests exercise generated binding end to end in JVM. |
| 6. Capability refusal | Descriptor validation rejects unsupported reactive/resource shapes; compiler tests pin schedule refusal and runtime tests pin durable-topic refusal. No distributed/durable delivery implementation is claimed. |
| 7. Configuration/interception | Explicit per-artifact configuration reaches the existing resource SPI and `ConfigFacade`. Example tests use the real LOCAL cache factory and intercepted slice. Configuration packaging, migration orchestration, and distributed collaborators are outside this increment. |
| 8. Typed HTTP | `TerraExamplesTest` invokes generated `CatalogRoutes` against a Terra instance and asserts HTTP 200 plus the catalog body. This is in-JVM router evidence, not a network listener test. |

Executed on 2026-10-07 with Maven 3.9.12, JDK 25.0.2, preview enabled, and an isolated Maven repository:

- `mvn -o -T 1 -pl terra/examples -am install -DskipTests`: dependency reactor compilation passed.
- `mvn -o -T 1 -pl terra/runtime,terra/examples,jbct/slice-processor clean install`: clean target-module compilation and all **376 tests** passed (12 runtime, 2 examples, 362 processor); no failures or skips.
- `mvn -o -T 1 -pl aether/slice-api test`: **364 tests** passed; no failures or skips.
- Java 25 `TerraDemo` executed as a separate process, printed `Terra: Catalog + PricingService compiled; Shop publication delivered to 1 subscriber.`, and exited successfully.
- Explicit JBCT format/check passed for `terra/runtime`, `terra/examples`, and `aether/slice-api`. Forcing the normally skipped processor check finds the same **38 lint errors and 10 formatting failures** as exported rc4. Normalized diagnostics show no added lint errors; the new descriptor and changed factory generator are formatted. Existing processor debt remains.
- `git diff --check` passed. The changelog fragment uses provisional number `0` until an issue/PR is assigned; the commit-range changelog gate must also pass before publication.

The first-increment run performed no cluster, cloud, database migration, or production HTTP server validation; database validation from the second increment is recorded below. Sharing existing resource modules still brings some Aether/distributed libraries onto the classpath; Terra does not initialize their node services. Dependency slimming can follow resource modularization.


## Second increment reconciliation

| Clause | Status | Implementation and executed evidence |
| --- | --- | --- |
| 1. Shared engine and Aether adapter | DONE | `aether/db-migrations` contains the extracted execution/history code and neutral script/error API. `AetherSchemaManager` translates inputs, outputs, and nested composite failures; the selected Aether schema and schema-route suites pass. Pure parser/dialect/evolution tests moved into the shared module. |
| 2. Exploded blueprint input | DONE | `TerraApplication.start(Path, ...)` reads the blueprint and sibling schema files. `TerraBlueprint` retains the identity; `TerraMigrations` uses Aether-compatible datasource paths and CRC32 checksums, validates filenames, and requires the versioned blueprint identity when migrations exist. PostgreSQL startup and the missing-identity unit test exercise this path. |
| 3. Provisioning/serialization/cleanup | DONE | Explicit configuration feeds `DatasourceConnectionProvider`; each attempt releases its migration connector before advancing. `MigrationQueue` protects actual operation completion independently of its exposed Promise. `TerraMigrationLifecycleTest` proves that caller timeout, delayed acquisition, and cleanup failure do not admit the next queued attempt prematurely. |
| 4. Readiness and reporting | DONE | Graph validation precedes migrations; migration and release precede construction. Tests cover pending/failed migrations and invalid graphs. `application.migrations()` exposes reports. A real PostgreSQL `SchemaProbe` queries migrated tables in its factory. |
| 5. Database example and regressions | DONE | InventoryService compiles from its original source directory. `TerraDatabaseTest` uses its original schema plus test seed data on real PostgreSQL, exercising SQL calls, restart across blueprint versions, checksum/owner rejection, and transactional failure/restart. Existing Aether schema and route tests pass through the adapter. |

Executed on 2026-10-07:

- Clean install of `aether/db-migrations`, `terra/runtime`, `jbct/slice-processor`, and `terra/examples` with `-Dterra.test.jdbcUrl` against an isolated PostgreSQL **14.20** instance: **429 tests passed**, no failures or skips (44 shared-engine, 17 Terra runtime, 362 processor, 6 example tests including all 4 PostgreSQL tests).
- `mvn -o -T 1 -pl aether/aether-deployment test -Dtest='*Schema*Test'`: **95 tests passed**.
- `mvn -o -T 1 -pl aether/node test -Dtest='Schema*Test'`: **81 tests passed**, including real-manager undo/baseline adapter paths. These use the existing route/orchestrator test fixtures; they are not a live cluster proof.
- `aether/db-migrations` and `aether/aether-deployment` were clean-built after moving source files. The downstream `aether/node -am install -DskipTests` reactor passed with no skipped modules.
- Explicit format/lint gates passed for `aether/db-migrations`, `aether/aether-deployment`, `terra/runtime`, and `terra/examples`; the node module check also passed. The processor’s pre-existing rc4 lint debt described above remains unchanged.
- `git diff --check` passed. The isolated PostgreSQL instance was stopped after validation.

This is **605 focused tests** for the second increment. Container-dependent Aether database tests and Forge/multi-node validation were not part of this run. No cross-process exclusion, other-dialect live proof, production HTTP listener, or executable-distribution claim is made. No migration execution/history algorithm was intentionally changed during extraction; existing engine limitations (including nontransactional checkpoint crash windows) remain.
