# Pragmatica Terra

Terra compiles the same slice sources as Aether into a single-process application. The implementation provides typed construction, blueprint selection, scoped resources, existing interceptor wrappers, ephemeral in-process pub-sub, and startup database migrations. It starts no Aether node.

The [specification](SPEC.md) records the implemented compiler, migration, and HTTP hosting contracts. Executable distribution assembly is available through `terra:assemble`; see [the launcher guide](launcher/README.md). PostgreSQL startup is exercised with the unchanged ecommerce InventoryService.

## Existing examples

`terra-examples` adds the existing Catalog, ecommerce PricingService and InventoryService, and shared value-type source directories to a separate compilation. Their business code is unchanged. Additional Shop, Counter, Events, and Sink slices demonstrate dependency injection, LOCAL caching, publisher/subscriber generation, and direct, `Option`, `Result`, and `Promise` factory returns.

The [example blueprint](examples/src/main/resources/blueprint.toml) selects six slices. The test invokes Catalog's generated HTTP router and PricingService, then exercises Shop's direct dependencies and delivery to Sink. The runnable demo publishes once and closes the application.

From the repository root, using Java 25 with preview enabled and the repository's normal Maven bootstrap completed:

```sh
mvn -pl terra/examples -am install -DskipTests
mvn -pl terra/runtime,terra/examples,jbct/slice-processor test
mvn -pl terra/examples dependency:build-classpath -Dmdep.outputFile=/tmp/terra-classpath
java --enable-preview -cp "terra/examples/target/classes:$(cat /tmp/terra-classpath)" org.pragmatica.terra.example.TerraDemo
```

Follow the repository's isolated Maven repository instructions in `CLAUDE.md`. The demo prints a successful delivery and exits; it does not bind a network port or require a database.

## Compilation and embedding

Use `slice-processor` with these javac options and include `terra-runtime` on the compilation/runtime classpath:

```text
-Aslice.target=terra
-Aslice.groupId=com.example
-Aslice.artifactId=application
-Aslice.version=1.0.0
```

Terra shares annotation analysis, resource injection, factory return handling, interception, and route generation with the default Aether target. For `OrderService`, it emits `OrderServiceFactory` accepting `TerraContext` and a `TerraFactory` service descriptor identified by `com.example:application-order-service:1.0.0`. Compile the targets into separate output directories. Generated Terra factories omit Aether dispatch proxies, wire codecs, and `Slice` adapters.

`TerraBlueprint.parse(toml)` returns the application selection. `TerraApplication.start(blueprint, configurationForArtifact)` discovers generated descriptors and provisions resources through the existing SPI. The overload accepting descriptors and a `ResourceProviderFacade` supports explicit embedding. Retrieve an entry point with `application.slice(MySlice.class)` and await `application.close()` during shutdown.

Every selected dependency must appear in the blueprint with its exact generated coordinate. Dependencies are constructed first; injected instances retain their interceptor wrappers. This increment accepts blueprint identity and slice selection, including deployment-only scaling fields, and refuses unsupported sections. One instance is created per selected slice. The assembler obtains Terra-built candidate JARs from Maven runtime dependencies and checks this selection.

Configuration is explicit and scoped per slice. Supply resource sections through the configuration provider; The executable launcher reads each owning JAR’s defaults separately; embedding callers supply their own providers. Existing LOCAL cache factories are reused. Other SPI resources require their dependencies and configuration; database startup uses the explicit migration path below, while distributed collaborators remain outside Terra’s supported runtime.

## Pub-sub contract

Existing typed topics, `Publisher<T>` resources, and single-payload subscriber annotations work without source changes. Topic configuration resolves `topic_name`, falling back to the section/typed topic name. Only ephemeral durability is accepted.

A publication invokes every handler in its subscriber snapshot and waits for all results, even if a handler fails. No subscribers is success. Handlers receive the same payload reference and must treat it as immutable. Concurrent publications have no total ordering. There is no persistence, replay, retry, deduplication, or cross-process delivery.

Publication is admitted only after all slices and subscriptions are ready. Closing rejects new publications, drains accepted deliveries, then releases resources in reverse construction order. Callers must stop other entry points before closing; arbitrary direct calls are not automatically drained. Handlers that never complete prevent a graceful drain.

Streams, schedules, durable entities/topics, transitive reactive methods, context-carrying subscribers, and live configuration callbacks are refused. The HTTP host below supplies listener lifecycle, authentication, route composition, and draining for the generated typed routers.

## Database startup

Terra reuses [the shared migration engine](../aether/db-migrations/README.md), also called by Aether. It preserves the existing `aether_schema_history`, history metadata, and owner tables. Supply a versioned blueprint identity, for example `id = "com.example:inventory-app:1.0.0"`; the persisted owner is `com.example:inventory-app`, so upgrading the application version retains ownership.

For an exploded application, place SQL under `schema/` next to `blueprint.toml`. `schema/V001__tables.sql` uses the `database` configuration section; `schema/orders/V001__tables.sql` uses `database.orders`. Filenames and UTF-8 CRC32 checksums follow Aether’s blueprint format. Call:

```java
TerraApplication.start(blueprintPath, configurationForSlice, migrationConfiguration)
```

This loads and validates the migration inputs, validates slice selection, migrates each datasource, closes the migration connections, then constructs slices. The embedding overload accepting a `TerraMigrations` plan supports application-specific packaging. The original `start(TerraBlueprint, ...)` overload without a migration plan remains for applications with no migrations; it cannot discover files without a path.

Include the desired SQL resource factory, connection pool, and JDBC driver. The example module explicitly includes `resource-db-jdbc` and HikariCP; its PostgreSQL test adds the driver. Supply `database.jdbc_url` through the configuration provider. Migration and slice configuration should point at the same database when they share a schema.

`application.migrations()` reports the applied count, current version, and duration for each datasource. All startup migrations are serialized within the JVM, including datasource aliases. The initial contract remains one application process per database; there is no cross-process lock. There is no automatic retry or timeout that advances the migration queue while SQL or connection cleanup remains outstanding.

On failure, startup returns the cause and creates no slices. Correct the configuration, ownership, or unapplied script and restart after the previous attempt settles. Never edit an already-applied versioned script; publish another migration. A successfully migrated schema remains committed if later slice construction fails. Nontransactional migrations retain the engine’s statement checkpoints and its existing crash-window limitation: a committed statement whose checkpoint was not saved may run again on restart.

Run the PostgreSQL proof against a local test database (the test creates and drops only uniquely named test schemas):

```sh
mvn -pl terra/examples test -Dterra.test.jdbcUrl='jdbc:postgresql://localhost:5432/test_database?user=test_user'
```

Without that property, the PostgreSQL test is explicitly skipped. The proof covers InventoryService SQL calls, a schema query during factory construction, restart idempotence, checksum and ownership refusal, and transactional DDL/history rollback. Unit tests separately pin startup failure and queue retention across caller timeouts and cleanup failure.


## HTTP hosting

The `terra-http` module hosts the generated routers over HTTP/1.1 or TLS and owns graceful application shutdown. It supports path/header API version selection, API-key and JWT authentication, route policy enforcement, and health probes. See [the host guide](http/README.md) for configuration, ownership, and drain semantics. `TerraCatalogHttpTest` drives the unchanged Catalog source through real listeners in both versioning modes, including JSON, CSV, binary payloads, and typed failures.

## Run the standalone distribution

```sh
mvn -pl terra/examples -am install -DskipTests
export JAVA_HOME=/path/to/jdk-25
sh terra/examples/target/terra-examples-terra/bin/terra --check
sh terra/examples/target/terra-examples-terra/bin/terra
curl http://localhost:8080/api/catalog/v2/items
```

The ZIP at `terra/examples/target/terra-examples-1.0.0-rc4-terra.zip` includes its dependencies and can run outside the repository. The example explicitly uses public default routes; configure API keys or JWT for protected deployments.
