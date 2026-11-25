# Pragmatica Terra

Pragmatica Terra compiles the slice sources you would run on Aether into one ordinary Java application: one JVM process, one executable distribution, no cluster. Licensed under Apache 2.0 (see the [license map](../LICENSE)).

The same sources run on both runtimes. The blueprint syntax is shared, so a slice written for Aether needs no edits to start on Terra, and moving it to Aether later needs none either. Twenty-one original example slices compile for Terra unchanged, and eight example applications are proven end to end ([the matrix](example-apps/README.md)); the examples that need streams are refused explicitly ([why](../docs/rfc/RFC-0020-terra-streams-and-entities.md)).

## What you get

- Startup database migrations, shared with Aether: versioned SQL under `schema/`, applied before any slice is constructed.
- HTTP hosting: HTTP/1.1 with optional TLS, path or header API versioning, API-key or JWT authentication, per-route policies, liveness and readiness probes, and graceful drain on shutdown ([host guide](http/README.md)).
- An executable distribution built by `terra:assemble`, started with `bin/terra`; `bin/terra --check` validates the slice graph, configuration and migration files without starting anything ([launcher guide](launcher/README.md)).
- Reflection-free JSON codecs, generated at compile time ([JSON module](../integrations/serialization/json/README.md)).
- In-process, ephemeral pub-sub between slices.
- Actuator-compatible management endpoints under Spring Boot's `management.*` keys: a default launch serves `/actuator/health`, and a slice route at or under the base path (default `/actuator`) is refused at startup naming the route ([settings](launcher/README.md#management-endpoints-actuator)).

For AI-assisted development the structure is fixed: slices, resources and routes have one shape, and a mistake in that shape surfaces at compile time (the annotation processor) or in `--check`, not in production.

## Which runtime

| You need | Terra | Aether |
| --- | --- | --- |
| One process you start with `bin/terra` | yes | no, a cluster |
| Slices spread across nodes by a cluster runtime | no | yes |
| Streams, durable entities, schedules | no, refused when the slice is compiled | yes |
| Durable topics | no, refused at startup (`durability` other than `ephemeral`) | yes |
| Pub-sub across processes | no, not refused: a publish with no local subscriber succeeds and reaches no other process | yes |
| The same slice sources and blueprint syntax | yes | yes |

Terra does not start an Aether node, and distributed collaborators are outside its supported runtime.

## Deployment shape

Direction (2026-11-06, not a shipped guarantee): Terra will ship resource clients that work from both runtimes, so the runtime and each resource's backend are chosen independently, and the standard deployment becomes two Terra instances with state in external standard infrastructure. Today the contract is narrower: two instances may start against one database, because the migration engine's database fence (PostgreSQL `pg_try_advisory_lock`, per database) lets one session run a datasource's migrations at a time, and a second instance's startup waits for the holder and then re-runs, finding the migrations applied (guarantee in the reference); pub-sub is in-process, so two instances do not share events. See [the reference](SPEC.md#database-startup).

## Run the demo distribution

From the repository root, with Java 25 and the repository's normal Maven bootstrap done (follow the isolated Maven repository instructions in `CLAUDE.md`):

```sh
mvn -pl terra/examples -am install -DskipTests
export JAVA_HOME=/path/to/jdk-25
sh terra/examples/target/terra-examples-terra/bin/terra --check
sh terra/examples/target/terra-examples-terra/bin/terra
curl http://localhost:8080/api/catalog/v2/items
```

### JVM options (`JAVA_OPTS`)

Both launchers (`bin/terra`, `bin/terra.cmd`) read `JAVA_OPTS` and place it after `--enable-preview` and before `-cp`; unset or empty adds nothing. Use it for heap, GC and system properties without bypassing the launcher; run each instance of a multi-instance deployment with its own value:

```sh
JAVA_OPTS="-Xms512m -Xmx512m -XX:+UseZGC" sh bin/terra
```

- `bin/terra` splits the value on whitespace and does no quote parsing or `*` expansion, so one option cannot contain whitespace. For such an option (for example `-Dname="a b"`) use the JVM's own `JAVA_TOOL_OPTIONS`, which the JVM parses itself.
- `bin/terra.cmd` expands `%JAVA_OPTS%` in place, so cmd quoting applies.
- There is no `TERRA_OPTS`: the `TERRA_` environment prefix is reserved for slice configuration, where `TERRA_OPTS` would read as a slice property.
- The check run during assembly (`--check`) does not use `JAVA_OPTS`; it runs the JVM the plugin was given.

[verified: terra/maven-plugin/src/test/java/org/pragmatica/terra/maven/LauncherTest.java] for `bin/terra` (stub `java` argv and a real JVM's `-XX:+PrintFlagsFinal`); `bin/terra.cmd` is pinned by text only, [design intent — unverified] at runtime.

The ZIP `terra/examples/target/terra-examples-<version>-terra.zip` carries its dependencies and runs outside the repository. The demo uses public default routes (`security_mode = "none"`); set API keys or JWT for anything protected, as described in the launcher guide.

## Build your own

1. Compile your slices with `slice-processor` and `-Aslice.target=terra`, in a compilation separate from the Aether one.
2. Put `blueprint.toml` (and optionally `terra.toml`, `resources.toml`, `schema/`) under `src/main/terra/`.
3. Bind `terra:assemble` to `package`, add `terra-launcher` and your slice JARs as runtime dependencies.

Details: [launcher guide](launcher/README.md), [example POM](examples/pom.xml). For embedding in your own process (`TerraApplication`, descriptors, the resource SPI, migration entry points), see the [reference in SPEC.md](SPEC.md#embedding-spi-and-descriptor-reference).

## More

- [HTTP host](http/README.md): versioning, authentication, probes, drain.
- [Example applications](example-apps/README.md): eight applications with live PostgreSQL proofs.
- [Specification](SPEC.md): contracts, increments, evidence, test counts.
- [Streams and durable entities](../docs/rfc/RFC-0020-terra-streams-and-entities.md): not supported; investigation and proposed contract.
