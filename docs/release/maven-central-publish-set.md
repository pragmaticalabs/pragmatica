# Maven Central publish set: what is published, what is skipped, and why

Decided per module for rc4 (#1989, #1988). **Publishing is opt-in**: the root skips every module by default, and a module publishes
only when its own `pom.xml` declares `central-publishing-maven-plugin` in `<build><plugins>` with `<skipPublishing>false</skipPublishing>`.
A parent pom that publishes while some children do not marks its entry `<inherited>false</inherited>`. A new module therefore stays
unpublished until somebody chooses otherwise.

- **Only `skipPublishing` excludes a module.** `maven.deploy.skip` is ignored by the Central plugin (#1988); the root keeps it `true`
  only so a module the plugin is not bound to does not run the default deploy.
- **The set is the closure of what a user's generated project needs.** Seeds: the six template dependencies, `jbct-maven-plugin`,
  `pg-codegen` and `utility`; plus every module they reach at compile/runtime scope and every parent pom.
- **Gates.** `tools/check-publish-closure.py` fails when a published module depends (compile/runtime, not optional) on an unpublished
  one or has an unpublished parent. It runs in CI, and first in a `-DperformRelease=true` build (the `release` profile runs it at
  `validate`). The same script checks this table against the poms (`--check-table`), so a table that drifts from the poms fails CI.
- **Changing the set:** edit the module's `pom.xml`, then this table (`python3 tools/check-publish-closure.py --table` prints the
  rows), then run the gate.

## How the user-facing set was verified (2026-10-07, rc4 `2f4721a97`)

From the real CLI output, not from the review: built `jbct/jbct-cli`, ran `jbct init` (slice, `--no-slice`, `--with-persistence`) and
`jbct add-slice`, `add-event`, `add-persistence` into scratch directories, and read every `pragmatica` dependency, plugin and
annotation-processor path out of the generated `pom.xml` files.

| Command | Maven artifacts the generated project needs |
|---|---|
| `jbct init` (slice) | `core`, `slice-processor`, `http-routing-adapter`, `resource-api`, `slice-annotations`, `slice-api` (all provided), plugin `jbct-maven-plugin`, processor path `slice-processor` |
| `jbct init --no-slice` | `core`, plugin `jbct-maven-plugin` |
| `jbct init --with-persistence`, `jbct add-persistence` | the slice set plus `pg-codegen` as a processor path |
| `jbct add-slice`, `jbct add-event` | nothing new (source files and resources only) |

An http-client adder exists only in `aether/docs/specs/interactive-scaffolding-spec.md`, with no CLI command
(`JbctCommand` subcommands: format, lint, check, check-sheet, derive, score, shape-census, obligations, doc, upgrade, init, add-slice,
add-event, add-persistence, fix-slice, update, migrate, verify-slice). The `jbct`, `aether` and node/forge jars reach users through the
install script, GitHub releases and the container image, not Maven Central.

## What it weighs (measured, 2026-10-07, `4855e51bc`)

A full `mvn clean install -DperformRelease=true` (every module, with a throwaway 4096-bit key) and a `deploy` whose "Central" is a
sink on `127.0.0.1`, so the plugin built and sent its own bundle; the bundle was opened and its entries counted. Nothing left the host.

| | Files in the bundle | Bytes (uncompressed / zip) | Releases per month under 1,167 files / 78 MB |
|---|---:|---:|---|
| `checksums=ALL` (the plugin default) | 612 | 15.8 MB / 14.5 MB | 1 (files bind: 1,224 for two) |
| `checksums=required` | 408 | 15.8 MB / 14.5 MB | 2 (816 files; bytes allow 4) |

612 = 102 files built (33 poms, 23 jars, 23 sources jars, 23 javadoc jars) + 102 `.asc` + 408 checksums (the plugin writes md5, sha1,
sha256 and sha512 for each of the 102, not for the `.asc`). A jar module is 24 entries, a parent pom 6. Javadoc is built with
`--no-fonts`: a javadoc jar was about 4.1 MB for the web fonts alone and is now about 0.1 MB for a small module, 10.4 MB for all 23.
The limits are cumulative per month, so a second release in the same month needs `checksums=required` or a smaller set.
`[unverified: whether Central counts the checksums it asks the plugin for; only Sonatype support can say]`.

## Decision table


| Module | Coordinate | Packaging | Decision | Why |
|---|---|---|---|---|
| `.` | `org.pragmatica-lite:pragmatica` | pom | publish | parent pom of a published module; a consumer resolves it to read the child (parent of integrations) |
| `aether` | `org.pragmatica-lite.aether:aether` | pom | publish | parent pom of a published module; a consumer resolves it to read the child (parent of aether-pg-tools) |
| `aether/aether-config` | `org.pragmatica-lite.aether:aether-config` | jar | skip | runtime internal of the node (ships inside the node jar and image) or Aether tooling; not a dependency of a slice |
| `aether/aether-control` | `org.pragmatica-lite.aether:aether-control` | jar | skip | runtime internal of the node (ships inside the node jar and image) or Aether tooling; not a dependency of a slice |
| `aether/aether-deployment` | `org.pragmatica-lite.aether:aether-deployment` | jar | skip | runtime internal of the node (ships inside the node jar and image) or Aether tooling; not a dependency of a slice |
| `aether/aether-dht` | `org.pragmatica-lite.aether:aether-dht` | jar | skip | runtime internal of the node (ships inside the node jar and image) or Aether tooling; not a dependency of a slice |
| `aether/aether-invoke` | `org.pragmatica-lite.aether:aether-invoke` | jar | skip | runtime internal of the node (ships inside the node jar and image) or Aether tooling; not a dependency of a slice |
| `aether/aether-management-api` | `org.pragmatica-lite.aether:aether-management-api` | jar | skip | runtime internal of the node (ships inside the node jar and image) or Aether tooling; not a dependency of a slice |
| `aether/aether-metrics` | `org.pragmatica-lite.aether:aether-metrics` | jar | skip | runtime internal of the node (ships inside the node jar and image) or Aether tooling; not a dependency of a slice |
| `aether/aether-setup` | `org.pragmatica-lite.aether:aether-setup` | jar | skip | runtime internal of the node (ships inside the node jar and image) or Aether tooling; not a dependency of a slice |
| `aether/aether-storage` | `org.pragmatica-lite.aether:aether-storage` | jar | skip | runtime internal of the node (ships inside the node jar and image) or Aether tooling; not a dependency of a slice |
| `aether/aether-stream` | `org.pragmatica-lite.aether:aether-stream` | jar | skip | runtime internal of the node (ships inside the node jar and image) or Aether tooling; not a dependency of a slice |
| `aether/aether-ttm` | `org.pragmatica-lite.aether:aether-ttm` | jar | skip | runtime internal of the node (ships inside the node jar and image) or Aether tooling; not a dependency of a slice |
| `aether/aether-ttm-onnx` | `org.pragmatica-lite.aether:aether-ttm-onnx` | jar | skip | runtime internal of the node (ships inside the node jar and image) or Aether tooling; not a dependency of a slice |
| `aether/cli` | `org.pragmatica-lite.aether:cli` | jar | skip | runtime internal of the node (ships inside the node jar and image) or Aether tooling; not a dependency of a slice |
| `aether/dashboard` | `org.pragmatica-lite.aether:dashboard` | jar | skip | runtime internal of the node (ships inside the node jar and image) or Aether tooling; not a dependency of a slice |
| `aether/dead-surface-gate` | `org.pragmatica-lite.aether:dead-surface-gate` | jar | skip | build gate or build instrument; produces no consumer-facing artifact |
| `aether/e2e-tests/echo-slice` | `org.pragmatica-lite.aether.test:echo-slice` | jar | skip | end-to-end test slice |
| `aether/e2e-tests/echo-slice-v2` | `org.pragmatica-lite.aether.test:echo-slice-v2` | jar | skip | end-to-end test slice |
| `aether/e2e-tests/versioned-slice` | `org.pragmatica-lite.aether.test:versioned-slice` | jar | skip | end-to-end test slice |
| `aether/ember` | `org.pragmatica-lite.aether:ember` | jar | skip | runtime internal of the node (ships inside the node jar and image) or Aether tooling; not a dependency of a slice |
| `aether/environment` | `org.pragmatica-lite.aether:environment` | pom | skip | cloud environment driver, runtime internal of the node |
| `aether/environment-integration` | `org.pragmatica-lite.aether:environment-integration` | jar | skip | cloud environment driver, runtime internal of the node |
| `aether/environment/aws` | `org.pragmatica-lite.aether:environment-aws` | jar | skip | cloud environment driver, runtime internal of the node |
| `aether/environment/azure` | `org.pragmatica-lite.aether:environment-azure` | jar | skip | cloud environment driver, runtime internal of the node |
| `aether/environment/docker` | `org.pragmatica-lite.aether:environment-docker` | jar | skip | cloud environment driver, runtime internal of the node |
| `aether/environment/gcp` | `org.pragmatica-lite.aether:environment-gcp` | jar | skip | cloud environment driver, runtime internal of the node |
| `aether/environment/hetzner` | `org.pragmatica-lite.aether:environment-hetzner` | jar | skip | cloud environment driver, runtime internal of the node |
| `aether/forge` | `org.pragmatica-lite.aether:forge` | pom | skip | Forge dev simulator; ships as its own jar/image |
| `aether/forge/forge-api` | `org.pragmatica-lite.aether:forge-api` | jar | skip | Forge dev simulator; ships as its own jar/image |
| `aether/forge/forge-core` | `org.pragmatica-lite.aether:forge-core` | jar | skip | Forge dev simulator; ships as its own jar/image |
| `aether/forge/forge-load` | `org.pragmatica-lite.aether:forge-load` | jar | skip | Forge dev simulator; ships as its own jar/image |
| `aether/forge/forge-simulator` | `org.pragmatica-lite.aether:forge-simulator` | jar | skip | Forge dev simulator; ships as its own jar/image |
| `aether/http-handler-api` | `org.pragmatica-lite.aether:http-handler-api` | jar | publish | compile/runtime dependency of http-routing-adapter |
| `aether/http-routing-adapter` | `org.pragmatica-lite.aether:http-routing-adapter` | jar | publish | template dependency (`jbct init`, provided) |
| `aether/node` | `org.pragmatica-lite.aether:node` | jar | skip | runtime internal of the node (ships inside the node jar and image) or Aether tooling; not a dependency of a slice |
| `aether/pg-tools` | `org.pragmatica-lite.aether:aether-pg-tools` | pom | publish | parent pom of a published module; a consumer resolves it to read the child (parent of pg-codegen) |
| `aether/pg-tools/pg-codegen` | `org.pragmatica-lite.aether:pg-codegen` | jar | publish | added by `jbct add-persistence` (and `jbct init --with-persistence`): annotation-processor path and dependency |
| `aether/pg-tools/pg-maven-plugin` | `org.pragmatica-lite.aether:pg-maven-plugin` | maven-plugin | skip | optional tooling; not referenced by `jbct init`, `jbct add-persistence` or the docs' generated poms (only one example uses pg-maven-plugin) |
| `aether/pg-tools/pg-parser` | `org.pragmatica-lite.aether:pg-parser` | jar | publish | compile/runtime dependency of pg-schema |
| `aether/pg-tools/pg-schema` | `org.pragmatica-lite.aether:pg-schema` | jar | publish | compile/runtime dependency of pg-codegen |
| `aether/pg-tools/pg-test-corpus` | `org.pragmatica-lite.aether:pg-test-corpus` | jar | skip | SQL fixtures for pg-codegen and pg-schema tests; test scope since #1989, so no consumer needs it |
| `aether/pg-tools/sql-splitter` | `org.pragmatica-lite.aether:sql-splitter` | jar | skip | optional tooling; not referenced by `jbct init`, `jbct add-persistence` or the docs' generated poms (only one example uses pg-maven-plugin) |
| `aether/resource` | `org.pragmatica-lite.aether:resource` | pom | publish | parent pom of a published module; a consumer resolves it to read the child (parent of resource-api) |
| `aether/resource/api` | `org.pragmatica-lite.aether:resource-api` | jar | publish | template dependency (`jbct init`, provided): resource annotations |
| `aether/resource/db-async` | `org.pragmatica-lite.aether:resource-db-async` | jar | skip | runtime resource provider shipped inside the node; slices use `resource-api` only |
| `aether/resource/db-jdbc` | `org.pragmatica-lite.aether:resource-db-jdbc` | jar | skip | runtime resource provider shipped inside the node; slices use `resource-api` only |
| `aether/resource/db-jooq` | `org.pragmatica-lite.aether:resource-db-jooq` | jar | skip | runtime resource provider shipped inside the node; slices use `resource-api` only |
| `aether/resource/db-jooq-api` | `org.pragmatica-lite.aether:resource-db-jooq-api` | jar | skip | runtime resource provider shipped inside the node; slices use `resource-api` only |
| `aether/resource/db-jooq-async` | `org.pragmatica-lite.aether:resource-db-jooq-async` | jar | skip | runtime resource provider shipped inside the node; slices use `resource-api` only |
| `aether/resource/db-jooq-r2dbc` | `org.pragmatica-lite.aether:resource-db-jooq-r2dbc` | jar | skip | runtime resource provider shipped inside the node; slices use `resource-api` only |
| `aether/resource/db-r2dbc` | `org.pragmatica-lite.aether:resource-db-r2dbc` | jar | skip | runtime resource provider shipped inside the node; slices use `resource-api` only |
| `aether/resource/durable-entity` | `org.pragmatica-lite.aether:resource-durable-entity` | jar | skip | runtime resource provider shipped inside the node; slices use `resource-api` only |
| `aether/resource/http` | `org.pragmatica-lite.aether:resource-http` | jar | skip | runtime resource provider shipped inside the node; slices use `resource-api` only |
| `aether/resource/interceptors` | `org.pragmatica-lite.aether:resource-interceptors` | jar | skip | runtime resource provider shipped inside the node; slices use `resource-api` only |
| `aether/resource/notification` | `org.pragmatica-lite.aether:resource-notification` | jar | skip | runtime resource provider shipped inside the node; slices use `resource-api` only |
| `aether/resource/services` | `org.pragmatica-lite.aether:resource-services` | pom | skip | runtime resource provider shipped inside the node; slices use `resource-api` only |
| `aether/resource/services/artifact-repo` | `org.pragmatica-lite.aether:artifact-repo` | jar | skip | runtime resource provider shipped inside the node; slices use `resource-api` only |
| `aether/slice` | `org.pragmatica-lite.aether:slice` | jar | skip | runtime internal of the node (ships inside the node jar and image) or Aether tooling; not a dependency of a slice |
| `aether/slice-annotations` | `org.pragmatica-lite.aether:slice-annotations` | jar | publish | template dependency (`jbct init`, provided) |
| `aether/slice-api` | `org.pragmatica-lite.aether:slice-api` | jar | publish | template dependency (`jbct init`, provided) |
| `aether/slice-testkit` | `org.pragmatica-lite.aether:slice-testkit` | jar | skip | test kit for slice authors; no template or generated-project output references it (owner call if it should ship) |
| `cli-docs-gate` | `org.pragmatica-lite:cli-docs-gate` | jar | skip | build gate or build instrument; produces no consumer-facing artifact |
| `core` | `org.pragmatica-lite:core` | jar | publish | template dependency (`jbct init`, provided): `Result`/`Option`/`Promise` |
| `examples` | `org.pragmatica-lite:examples` | pom | skip | example slice or example parent; nothing published depends on it |
| `examples/banking` | `org.pragmatica-lite.aether.example.banking:banking` | pom | skip | example slice or example parent; nothing published depends on it |
| `examples/banking/account` | `org.pragmatica-lite.aether.example.banking:banking-account` | jar | skip | example slice or example parent; nothing published depends on it |
| `examples/banking/exchange` | `org.pragmatica-lite.aether.example.banking:banking-exchange` | jar | skip | example slice or example parent; nothing published depends on it |
| `examples/banking/fraud` | `org.pragmatica-lite.aether.example.banking:banking-fraud` | jar | skip | example slice or example parent; nothing published depends on it |
| `examples/banking/shared` | `org.pragmatica-lite.aether.example.banking:banking-shared` | jar | skip | example slice or example parent; nothing published depends on it |
| `examples/banking/transfer` | `org.pragmatica-lite.aether.example.banking:banking-transfer` | jar | skip | example slice or example parent; nothing published depends on it |
| `examples/catalog` | `org.pragmatica.aether.example:catalog` | jar | skip | example slice or example parent; nothing published depends on it |
| `examples/comprehensive-persistence` | `org.pragmatica.aether.example:comprehensive-persistence` | jar | skip | example slice or example parent; nothing published depends on it |
| `examples/ecommerce` | `org.pragmatica-lite.aether.example:ecommerce` | pom | skip | example slice or example parent; nothing published depends on it |
| `examples/ecommerce/fulfillment` | `org.pragmatica-lite.aether.example:fulfillment` | jar | skip | example slice or example parent; nothing published depends on it |
| `examples/ecommerce/inventory` | `org.pragmatica-lite.aether.example:inventory` | jar | skip | example slice or example parent; nothing published depends on it |
| `examples/ecommerce/payment` | `org.pragmatica-lite.aether.example:payment` | jar | skip | example slice or example parent; nothing published depends on it |
| `examples/ecommerce/place-order` | `org.pragmatica-lite.aether.example:place-order` | jar | skip | example slice or example parent; nothing published depends on it |
| `examples/ecommerce/pricing` | `org.pragmatica-lite.aether.example:pricing` | jar | skip | example slice or example parent; nothing published depends on it |
| `examples/ecommerce/shared` | `org.pragmatica-lite.aether.example:shared` | jar | skip | example slice or example parent; nothing published depends on it |
| `examples/jooq-xml-showcase` | `org.pragmatica.aether.example:jooq-xml-showcase` | pom | skip | example slice or example parent; nothing published depends on it |
| `examples/notification-hub` | `org.pragmatica.aether.example:notification-hub` | pom | skip | example slice or example parent; nothing published depends on it |
| `examples/notification-hub/notification-analytics` | `org.pragmatica.aether.example:notification-hub-notification-analytics` | jar | skip | example slice or example parent; nothing published depends on it |
| `examples/notification-hub/notification-emailer` | `org.pragmatica.aether.example:notification-hub-notification-emailer` | jar | skip | example slice or example parent; nothing published depends on it |
| `examples/notification-hub/notification-service` | `org.pragmatica.aether.example:notification-hub-notification-service` | jar | skip | example slice or example parent; nothing published depends on it |
| `examples/pg-showcase` | `org.pragmatica.aether.example:pg-showcase` | jar | skip | example slice or example parent; nothing published depends on it |
| `examples/pragmatica-lite` | `org.pragmatica-lite:examples-pragmatica-lite` | jar | skip | example slice or example parent; nothing published depends on it |
| `examples/pricing-engine` | `org.pragmatica.aether.example:pricing-engine` | jar | skip | example slice or example parent; nothing published depends on it |
| `examples/step-composition` | `org.pragmatica.aether.example:step-composition` | jar | skip | example slice or example parent; nothing published depends on it |
| `integrations` | `org.pragmatica-lite:integrations` | pom | publish | parent pom of a published module; a consumer resolves it to read the child (parent of utility) |
| `integrations/cloud` | `org.pragmatica-lite:cloud` | pom | skip | cloud SDK adapter for the runtime; internal |
| `integrations/cloud/aws` | `org.pragmatica-lite:aws` | jar | skip | cloud SDK adapter for the runtime; internal |
| `integrations/cloud/azure` | `org.pragmatica-lite:azure` | jar | skip | cloud SDK adapter for the runtime; internal |
| `integrations/cloud/gcp` | `org.pragmatica-lite:gcp` | jar | skip | cloud SDK adapter for the runtime; internal |
| `integrations/cloud/hetzner` | `org.pragmatica-lite:hetzner` | jar | skip | cloud SDK adapter for the runtime; internal |
| `integrations/cluster` | `org.pragmatica-lite:cluster` | jar | skip | library of the runtime or an optional integration (jpa, micrometer, ...) that no template references |
| `integrations/config` | `org.pragmatica-lite:config` | pom | publish | parent pom of a published module; a consumer resolves it to read the child (parent of toml) |
| `integrations/config/config-service` | `org.pragmatica-lite:config-service` | jar | publish | compile/runtime dependency of slice-api |
| `integrations/config/toml` | `org.pragmatica-lite:toml` | jar | publish | compile/runtime dependency of jbct-core |
| `integrations/consensus` | `org.pragmatica-lite:consensus` | jar | skip | library of the runtime or an optional integration (jpa, micrometer, ...) that no template references |
| `integrations/db` | `org.pragmatica-lite:db` | pom | skip | library of the runtime or an optional integration (jpa, micrometer, ...) that no template references |
| `integrations/db/jdbc` | `org.pragmatica-lite:jdbc` | jar | skip | library of the runtime or an optional integration (jpa, micrometer, ...) that no template references |
| `integrations/db/jooq` | `org.pragmatica-lite:jooq` | jar | skip | library of the runtime or an optional integration (jpa, micrometer, ...) that no template references |
| `integrations/db/jooq-r2dbc` | `org.pragmatica-lite:jooq-r2dbc` | jar | skip | library of the runtime or an optional integration (jpa, micrometer, ...) that no template references |
| `integrations/db/jpa` | `org.pragmatica-lite:jpa` | jar | skip | library of the runtime or an optional integration (jpa, micrometer, ...) that no template references |
| `integrations/db/postgres-async` | `org.pragmatica-lite:postgres-async` | jar | skip | library of the runtime or an optional integration (jpa, micrometer, ...) that no template references |
| `integrations/db/postgres-r2dbc-adapter` | `org.pragmatica-lite:postgres-r2dbc-adapter` | jar | skip | library of the runtime or an optional integration (jpa, micrometer, ...) that no template references |
| `integrations/db/r2dbc` | `org.pragmatica-lite:r2dbc` | jar | skip | library of the runtime or an optional integration (jpa, micrometer, ...) that no template references |
| `integrations/dht` | `org.pragmatica-lite:dht` | jar | skip | library of the runtime or an optional integration (jpa, micrometer, ...) that no template references |
| `integrations/email-http` | `org.pragmatica-lite:email-http` | jar | skip | library of the runtime or an optional integration (jpa, micrometer, ...) that no template references |
| `integrations/http-routing` | `org.pragmatica-lite:http-routing` | jar | publish | compile/runtime dependency of http-routing-adapter |
| `integrations/json` | `org.pragmatica-lite:json` | pom | publish | parent pom of a published module; a consumer resolves it to read the child (parent of jackson) |
| `integrations/json/jackson` | `org.pragmatica-lite:jackson` | jar | publish | compile/runtime dependency of http-routing-adapter |
| `integrations/messaging` | `org.pragmatica-lite:messaging` | jar | skip | library of the runtime or an optional integration (jpa, micrometer, ...) that no template references |
| `integrations/metrics` | `org.pragmatica-lite:metrics` | pom | skip | library of the runtime or an optional integration (jpa, micrometer, ...) that no template references |
| `integrations/metrics/micrometer` | `org.pragmatica-lite:micrometer` | jar | skip | library of the runtime or an optional integration (jpa, micrometer, ...) that no template references |
| `integrations/net` | `org.pragmatica-lite:net` | pom | publish | parent pom of a published module; a consumer resolves it to read the child (parent of http-client) |
| `integrations/net/dns` | `org.pragmatica-lite:dns` | jar | skip | library of the runtime or an optional integration (jpa, micrometer, ...) that no template references |
| `integrations/net/http-client` | `org.pragmatica-lite:http-client` | jar | publish | compile/runtime dependency of jbct-core |
| `integrations/net/http-server` | `org.pragmatica-lite:http-server` | jar | skip | library of the runtime or an optional integration (jpa, micrometer, ...) that no template references |
| `integrations/net/http-types` | `org.pragmatica-lite:http-types` | jar | publish | compile/runtime dependency of http-routing |
| `integrations/net/smtp` | `org.pragmatica-lite:smtp` | jar | skip | library of the runtime or an optional integration (jpa, micrometer, ...) that no template references |
| `integrations/net/tcp` | `org.pragmatica-lite:tcp` | jar | skip | library of the runtime or an optional integration (jpa, micrometer, ...) that no template references |
| `integrations/serialization` | `org.pragmatica-lite:serialization` | pom | publish | parent pom of a published module; a consumer resolves it to read the child (parent of serialization-api) |
| `integrations/serialization/api` | `org.pragmatica-lite:serialization-api` | jar | publish | compile/runtime dependency of slice-api |
| `integrations/serialization/codec-processor` | `org.pragmatica-lite:serialization-codec-processor` | jar | skip | library of the runtime or an optional integration (jpa, micrometer, ...) that no template references |
| `integrations/statemachine` | `org.pragmatica-lite:statemachine` | jar | skip | library of the runtime or an optional integration (jpa, micrometer, ...) that no template references |
| `integrations/storage` | `org.pragmatica-lite:storage` | jar | skip | library of the runtime or an optional integration (jpa, micrometer, ...) that no template references |
| `integrations/swim` | `org.pragmatica-lite:swim` | jar | skip | library of the runtime or an optional integration (jpa, micrometer, ...) that no template references |
| `integrations/utility` | `org.pragmatica-lite:utility` | jar | publish | used by the in-repo example slices (banking, ecommerce, step-composition) as a provided dependency; not in the template. Kept: cheap (8 files) and a documented sample dependency |
| `integrations/xml` | `org.pragmatica-lite:xml` | pom | skip | library of the runtime or an optional integration (jpa, micrometer, ...) that no template references |
| `integrations/xml/jackson-xml` | `org.pragmatica-lite:jackson-xml` | jar | skip | library of the runtime or an optional integration (jpa, micrometer, ...) that no template references |
| `jbct` | `org.pragmatica-lite:jbct-parent` | pom | publish | parent pom of a published module; a consumer resolves it to read the child (parent of jbct-maven-plugin) |
| `jbct/jbct-cli` | `org.pragmatica-lite:jbct-cli` | jar | skip | the `jbct` CLI and its libraries reach users through the install script and GitHub releases, not as a dependency; `jbct init` and `jbct add-*` reference none of them |
| `jbct/jbct-core` | `org.pragmatica-lite:jbct-core` | jar | publish | compile/runtime dependency of jbct-maven-plugin |
| `jbct/jbct-derive` | `org.pragmatica-lite:jbct-derive` | jar | skip | the `jbct` CLI and its libraries reach users through the install script and GitHub releases, not as a dependency; `jbct init` and `jbct add-*` reference none of them |
| `jbct/jbct-format` | `org.pragmatica-lite:jbct-format` | jar | publish | compile/runtime dependency of jbct-maven-plugin |
| `jbct/jbct-init` | `org.pragmatica-lite:jbct-init` | jar | skip | the `jbct` CLI and its libraries reach users through the install script and GitHub releases, not as a dependency; `jbct init` and `jbct add-*` reference none of them |
| `jbct/jbct-lint` | `org.pragmatica-lite:jbct-lint` | jar | publish | compile/runtime dependency of jbct-maven-plugin |
| `jbct/jbct-maven-plugin` | `org.pragmatica-lite:jbct-maven-plugin` | maven-plugin | publish | template build plugin: `format-check`, `lint`, `collect-slice-deps`, `package-slices`, `generate-blueprint`, `install-slices`, `verify-slice` |
| `jbct/jbct-parser` | `org.pragmatica-lite:jbct-parser` | jar | publish | compile/runtime dependency of jbct-lint |
| `jbct/slice-processor` | `org.pragmatica-lite:slice-processor` | jar | publish | template dependency (provided) and the compiler `annotationProcessorPaths` entry |
| `jbct/slice-processor-tests` | `org.pragmatica-lite:slice-processor-tests` | jar | skip | build gate or build instrument; produces no consumer-facing artifact |
| `script-gate` | `org.pragmatica-lite:script-gate` | jar | skip | build gate or build instrument; produces no consumer-facing artifact |
| `test-logging` | `org.pragmatica-lite:test-logging` | jar | skip | test support; consumers take their own test dependencies, the template adds none |
| `test-plan-witness` | `org.pragmatica-lite:test-plan-witness` | jar | skip | build gate or build instrument; produces no consumer-facing artifact |
| `testing` | `org.pragmatica-lite:testing` | jar | skip | test support; consumers take their own test dependencies, the template adds none |

33 modules publish, 114 skip (147 reactor modules; `forge-tests`, `e2e-tests` and `cloud-tests` are reactor modules only under their profiles and never publish).
