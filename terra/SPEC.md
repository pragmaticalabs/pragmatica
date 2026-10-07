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


## HTTP hosting milestone

The third increment provides an owned network host around a ready Terra application. It does not yet define executable distribution assembly.

1. Discover shared generated router factories and expose only selected slices. Refuse duplicate factories, incompatible route-security contracts, duplicate identities, reserved management paths, unknown security policy types, and cross-slice ownership of the same method/base path. The shared router retains path-shape, media type, typed error, and API version behavior.
2. Support both path and header API version mounting. Handler authorization must occur after selecting the actual version and shape. Header-mode owner lookup must not prematurely apply another version's path shape. Retain default/required version behavior and deprecation/sunset response headers.
3. Extract existing API-key and JWT/JWKS verification into a shared module, retaining Aether's cluster-key adapter. Resolve undeclared route security to an explicit runtime default, authenticate off transport event loops, then enforce the actual route policy. Public routes have no credential requirement. Provide verified request/security scope to handlers and support injected authenticators. Close owned authentication resources.
4. Serve buffered HTTP/1.1 with optional TLS and existing body limits. Failed TLS construction must fail startup, never open plaintext. Port zero must report the bound port. WebSocket/streaming transport bypasses are rejected; HTTP/2 and HTTP/3 hosting are separate future capabilities.
5. Transfer ownership of the application and authenticator at startup, cleaning both after validation/bind failure. Publish readiness after successful application startup and binding. On close, reject new application requests, withdraw readiness, drain accepted handlers and response transport flushes, stop the listener, then close application resources and authentication resources. Preserve cleanup failures, continue cleanup after failure, and make close idempotent. A caller timeout does not terminate actual work or release resources early. No forced drain deadline is implied.
6. Expose credential-free liveness/readiness probes under reserved `/__terra/health/` paths and a local status snapshot with in-flight requests, accepted requests, and failed writes. Prove network behavior with unchanged Catalog, real signed JWTs/local JWKS, API keys, TLS, failed startup, and slow-request shutdown. Keep Aether's focused HTTP/security regressions passing.

Shared adapter tests additionally pin malformed primitive parameter errors as HTTP 400 and consistently mapped composite domain failures at their declared status. Mixed client/server domain failures retain HTTP 500.


## Third increment reconciliation

| Clause | Status | Evidence |
| --- | --- | --- |
| 1. Selected routes and validation | DONE | `TerraHttpRoutes` binds only selected slice types and validates factory/security/route identity contracts. Live tests pin selection and duplicate refusal. |
| 2. Version selection | DONE | Shared `SliceRouter` preserves version selection and headers; authorization decorates the actual handler. Tests cover version-specific security, required headers, fallback, and a path prefix belonging to another version. |
| 3. Shared authentication | DONE | `http-security` contains existing API-key/JWT implementations; the node adapts them. Tests exercise named API keys, role refusals, scoped principals, real RSA signatures and a live local JWKS endpoint, expiry/issuer checks, and forged signatures. |
| 4. HTTP transport | DONE | Live HTTP/HTTPS tests pass. Invalid TLS fails startup, ephemeral ports report correctly, and response writes expose completion and failure. Unsupported WebSocket/streaming transport options are rejected. |
| 5. Ownership and shutdown | DONE | Live slow-request test proves withdrawal of readiness/admission and response completion before resource release, including a timed-out shutdown caller. Failed-bind and duplicate-route tests prove owned cleanup; writes after transport close report failure. |
| 6. Probes and external proof | DONE | Live readiness/liveness endpoints and local `status()` snapshot are implemented. Original Catalog serves JSON/CSV/binary/typed errors over both version modes; Aether adapter regressions pass. |

Executed on 2026-10-07/08 with the isolated Maven repository and JDK 25:

- Clean install of `integrations/net/http-server,aether/http-security,aether/http-routing-adapter,terra/http,terra/examples`: **157 executed tests passed**, with four opt-in PostgreSQL tests explicitly skipped in this HTTP-only run.
- `aether/node clean test -Dtest='AppHttpServer*Test,*SecurityValidator*Test,*Authorization*Test,*AdminKey*Test'`: **139 tests passed**. Cleaning removes stale classes after authentication extraction.
- Downstream `aether/node -am install -DskipTests`: successful reactor, no skipped modules.
- Explicit JBCT format/check passed for HTTP adapter, shared authentication, Terra HTTP, and examples; the node gate also passed. A subsequent HTTP-only check added a null-promise defect test (11 host tests total) and passed format/lint/install.

This is **297 executed focused tests** for the HTTP increment. No cloud, Forge, multi-node, HTTP/2, or Terra HTTP/3 host proof is claimed. The transport regression suite includes its existing HTTP/3 shutdown tests, independently of Terra hosting. Executable process packaging remains the next milestone.

## Executable distribution milestone

The fourth increment assembles Terra-built slice JARs from a Maven project's resolved runtime dependencies. Maven dependency declarations locate candidate code; the existing blueprint remains the sole selection of slices to instantiate. It does not rebuild published Aether binaries or independently resolve blueprint coordinates.

1. `terra:assemble` runs after packaging. Include the project JAR and resolved compile/runtime JARs intact, retaining service descriptors and resource ownership. Refuse duplicate class definitions and conflicting bundle filenames. Use Maven's resolved version mediation; detecting every evicted transitive version request is not a separate guarantee.
2. Bundle `application/` (blueprint, optional host/resource/per-slice files, schema), `lib/`, launch scripts, and SHA-256 library inventory. Attach a ZIP with classifier `terra`. Validate a staged bundle using the bundled launcher in a separate Java process before replacing an owned output directory. Refuse replacement of unmarked directories and application symlinks. Validation failure preserves the previous distribution. This is not a transactional publication guarantee for filesystem failures during final replacement.
3. The launcher loads selected factory defaults only from the factory's own JAR/classes directory. Slice precedence is system properties (`terra.`), environment (`TERRA_`), per-slice file, deployment resources, intrinsic resources. Double underscores in environment names become dots; single underscores remain unchanged. No merging of unrelated JAR-local defaults. Reject malformed files and unresolved secret placeholders; use direct environment/property values for secrets.
4. `--check` validates graph selection, configuration syntax/host options, and migration filenames/identity without constructing slices, acquiring resources, connecting to databases/JWKS, or opening a listener. Resource-specific configuration and actual TLS/route construction are checked at startup. Host settings support API keys, JWT, explicit public defaults, TLS, path/header versions, port/body size; unknown host options fail.
5. Normal launch awaits migrations, constructs slices, binds HTTP, and reports readiness. Process termination invokes the host's graceful drain and resource cleanup. No forced shutdown deadline is implied. Java 25 with preview enabled is required; scripts use `JAVA_HOME` or `java` from PATH. The Unix script is also runnable as `sh bin/terra` after ZIP extraction.
6. Prove that the extracted archive serves unchanged Catalog from a working directory outside the checkout and shuts down on termination. Prove check mode, resource ownership/precedence, invalid selection/configuration/migrations, duplicate-class refusal, and preservation of previous output after validation failure.

### Fourth increment reconciliation

| Clause | Status | Implementation and evidence |
| --- | --- | --- |
| 1 | DONE | `AssembleMojo` resolves the Maven runtime set; `Distribution.classpath` rejects duplicate class/file identities. Distribution tests exercise class collisions and legitimate service/module metadata. |
| 2 | DONE | Staged copy/check/ZIP pipeline and marker protection. Tests prove failed validation preserves prior output and refuses an unowned directory. |
| 3 | DONE | `TerraConfiguration` reads factory code-source resources; tests use two separate JARs with colliding resource names and verify layer precedence/environment names. |
| 4 | DONE | `TerraLaunchPlan`, `LaunchHttp`, and `TerraMain`; a factory that always fails construction still passes check mode. Invalid migration/configuration/selection tests fail before provisioning. |
| 5 | DONE | Launcher composes existing migration/application/HTTP lifecycle; the extracted-process test terminates the process and observes clean shutdown. |
| 6 | DONE | `TerraDistributionIT` extracts the actual attached ZIP, launches outside the repository, requests Catalog over HTTP, and tests `--check`. |

Executed on 2026-10-08: clean installs of `terra/runtime,terra/launcher,terra/maven-plugin` and `terra/examples` passed **36 tests** (17 runtime, 10 launcher, 4 assembler, 3 example unit/network tests, 2 extracted-process tests). The four opt-in database tests skipped in this packaging-only run. Explicit JBCT format/check passed across all four touched modules. The ZIP was launched from an unrelated temporary working directory. Windows launcher execution has not been tested.


## Existing example expansion contract

Recompile original example source directories in separate Terra Maven modules, preserving their original logical slice coordinates and JAR resource ownership. Package the existing migration scripts without editing business code. Add full ecommerce, both URL-shortener versions, pricing engine, step composition, banking, PG showcase, and comprehensive persistence candidates. Distinguish compilation/assembly evidence from live application evidence for each.

Support single-payload ephemeral subscriptions on injected plain-interface steps by retaining the constructed step and binding that exact instance before publication is admitted. Keep schedules, context-carrying subscriptions, live config callbacks, and durable/stream resources refused, including nested step resources. Preserve the Aether factory output contract. Keyed multi-parameter interceptor generation must import its shared type-token API for Terra too.

The live ecommerce proof exposed unsupported `Instant` arguments in shared JDBC binding. `JdbcParameters` now normalizes these to `Timestamp` for ordinary and transactional queries/updates/batches; driver/column timezone behavior is retained. Validation: 37 JDBC tests, 3 JDBC-resource tests, and live extracted ecommerce/URL processes passed. The JDBC resource JBCT gate passes; forcing the normally skipped integration JDBC gate reports the same five existing exception-boundary lint errors as an exported HEAD baseline, with no format errors after formatting the new helper.

### Fifth increment reconciliation

| Clause | Status | Implementation and executed evidence |
| --- | --- | --- |
| Unchanged examples and assembly | DONE | `example-apps` has 20 child modules and eight staged, preflight-checked distributions. The original `examples/` tree is unchanged. The support matrix names each executed path and the comprehensive example's existing nullable-field limitation. |
| Plain-interface subscriptions | DONE | `TerraContext.retainStep` retains the injected instance; generated descriptor binding retrieves it. A real database test delivers an order event to the original listener. Nested unsupported resource/reactive declarations remain compiler errors. |
| Keyed interceptors | DONE | Terra generation registers `TypeToken`; the original banking account methods compile and sequential credit/read/transfer operations pass with LOCAL caching. |
| Application-only embedding | DONE | `TerraLaunchPlan.startApplication()` uses the same migration/construction path; banking, step, and persistence tests await owned close. |
| Shared defects discovered by examples | DONE | JDBC Instant normalization; native PostgreSQL generic conversion preserving binary/text format; cache-aside population and write-around invalidation awaited before business completion. Shared tests and live examples pass. |
| Streams/entities investigation | DONE | `STREAMS-AND-ENTITIES.md` traces local WAL, consumer cursors, entity state machine, timers, codec and ownership requirements. It labels the next contract as proposed, with no implemented Terra durability claim. |

Executed on 2026-10-08 using JDK 25, the isolated Maven repository, and local PostgreSQL 14.20:

- Clean application reactor install: all 20 child modules built and all **8 live database tests** passed. After review tightened nullable-column assertions and float compatibility, all application bundles were rebuilt and the 8 tests passed again. Process tests extract archives outside the checkout; embedding tests exercise application APIs against real databases.
- Original `terra/examples` clean install with the live database property: **9 tests passed**, including four migration/database tests, live Catalog HTTP, and two extracted-process tests; no skips.
- Processor: **364 tests passed**; runtime/launcher clean install: **27 tests passed**. JDBC and JDBC-resource: **40 tests passed**. Native PostgreSQL targeted generic conversion: **4 tests passed**. Interceptors: **139 tests passed**.
- Existing stream/entity feasibility suites: **69 tests passed**, independently of Terra. No Terra stream/entity or replicated-availability proof is implied.
- Touched runtime, launcher, JDBC resource, and interceptor JBCT gates passed. Normally skipped legacy gates were also examined: processor retains the baseline 38 lint/10 formatting failures, JDBC integration retains five lint failures, and the touched native PostgreSQL converter retains 43 lint failures. Exported baseline comparisons found no added lint failures; changed/new production files are formatted. These legacy checks are not reported as green.

The cache change orders sequential maintenance; it does not provide concurrent read/write coherence or a forced maintenance timeout. Original example sources remain authoritative: comprehensive persistence still rejects nullable columns mapped to required fields, and banking's transfer history remains in memory. Windows execution, cloud/Forge, multi-node startup, cross-process migration exclusion, and stream/entity durability are outside this validation.

The downstream `aether/node -am install -DskipTests` reactor passed after the shared fixes, with no skipped modules.

## Extension boundary review

Terra rejects null slice instances and null factory, binding, resource-acquisition, or cleanup completion values with operation-specific failures. Five fault-injection cases in `TerraApplicationTest` exercise these extension defects and verify reverse cleanup reaches every scope. Generated factories obey these contracts; the guards cover custom embedding implementations. This does not attempt to compensate arbitrary external effects performed by a defective factory.

After boundary hardening, `mvn -o -T 1 -f terra/pom.xml clean install -Dterra.test.jdbcUrl=…` rebuilt the entire Terra subtree and all nine distributions. **64 tests passed with zero failures, errors, or skips**, including HTTP/TLS, 12 live database tests, and extracted-process tests. The explicit runtime JBCT gate passed (zero lint errors or format issues).
