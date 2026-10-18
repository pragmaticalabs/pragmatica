# Pragmatica

A monorepo for building reliable Java backends with functional programming patterns and a unified application runtime.

**Current release branch: `release-1.0.0-rc5`.** Development integrates here and pull requests target this branch, not `main`; once the release ships, find the current one with `git ls-remote origin 'release-*'`. See [CONTRIBUTING.md](CONTRIBUTING.md).

**Pre-GA release candidate — not production-ready.** Read the [Aether known limitations](aether/docs/reference/known-limitations.md) before evaluating.

## What's Inside

**[Pragmatica Core](core/README.md)** — Functional primitives for Java: `Result<T>`, `Option<T>`, `Promise<T>`. No exceptions, no nulls, composable error handling.

**[Pragmatica Integrations](integrations/)** — Ready-made integrations: PostgreSQL (async + JDBC + jOOQ), HTTP server/client, serialization (Kryo, Fury), consensus protocols, distributed storage, messaging, metrics.

**[JBCT Tools](jbct/README.md)** — CLI and Maven plugin for Java Backend Coding Technology: code formatting, linting, slice annotation processing, project scaffolding.

**[Aether Runtime](aether/README.md)** — Unified Application Runtime for Java. Deploy services as slices, scale transparently, no microservices boilerplate. Consensus-based state, automatic topology management, built-in observability.

## Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| Java | 25+ | Runtime and build |
| Maven | 3.9+ | Build system |
| Docker | Latest | Container runtime (for examples with PostgreSQL) |
| Git, curl, jq | any | Quick Start: clone the examples, install Aether, read the JSON reply |

## Quick Start

Forge, the local simulator, comes from the GitHub release through the installer. The only thing built on your
machine is the example itself, from artifacts on Maven Central.

In a first terminal:

<!-- quickstart:terminal-1 -- tools/quickstart-check.sh extracts and runs this block as written; keep it runnable. -->
```bash
# The release this Quick Start uses; the installer and the clone below both take it
AETHER_VERSION=1.0.0-rc4

# Install Aether (CLI, node and Forge) from the GitHub release into ~/.aether
curl -fsSL https://raw.githubusercontent.com/pragmaticalabs/pragmatica/main/aether/install.sh | sh -s -- --version "$AETHER_VERSION"

# Get the examples at the same release
git clone --depth 1 --branch "v$AETHER_VERSION" https://github.com/pragmaticalabs/pragmatica.git
cd pragmatica/examples/pricing-engine

# Start PostgreSQL, then build the example and run it on the installed Forge (stays in the foreground)
./start-postgres.sh
./run-forge.sh
```

In a second terminal, once Forge has deployed the blueprint:

<!-- quickstart:terminal-2 -->
```bash
curl -fsS -X POST http://localhost:8070/api/v1/pricing/calculate \
  -H 'Content-Type: application/json' \
  -d '{"productId":"WIDGET-D","quantity":3,"regionCode":"US-CA","couponCode":"SAVE20"}' | jq
```

`run-forge.sh` finds Forge on your `PATH` or in `~/.aether` (set `AETHER_FORGE` to use another one) and stops with the
install command if there is none. Contributors running Forge built from this checkout use `./run-forge.sh --forge-from-source`.

See [examples/](examples/) for more: URL shortener, ecommerce (multi-slice), banking, notification hub, PostgreSQL persistence showcase.

## Build

The monorepo requires specific build ordering — annotation processors and Maven plugins must be bootstrapped before the modules that depend on them. Use the provided script:

```bash
./build.sh                 # Full build: bootstrap, lint, install, e2e compile, test blueprints
mvn test                   # Run tests after build
```

## Using in Your Project

```xml
<!-- Core library -->
<dependency>
    <groupId>org.pragmatica-lite</groupId>
    <artifactId>core</artifactId>
    <version>1.0.0-rc5</version>
</dependency>

<!-- Aether Slice API (for writing slices) -->
<dependency>
    <groupId>org.pragmatica-lite.aether</groupId>
    <artifactId>slice-api</artifactId>
    <version>1.0.0-rc5</version>
    <scope>provided</scope>
</dependency>

<!-- JBCT Maven Plugin (formatting + linting) -->
<plugin>
    <groupId>org.pragmatica-lite</groupId>
    <artifactId>jbct-maven-plugin</artifactId>
    <version>1.0.0-rc5</version>
</plugin>
```

## Documentation

- [Core Library](core/README.md) — Result, Option, Promise types
- [JBCT Tools](jbct/README.md) — Formatting, linting, project scaffolding
- [Aether Runtime](aether/README.md) — Architecture and deployment
- [Aether Docs](aether/docs/README.md) — Full reference documentation

## License

- Apache License 2.0 everywhere by default: core, integrations, JBCT, and the Aether slice API, resources, CLI and tooling
- Business Source License 1.1 (converts to Apache 2.0 on January 1, 2030) for the Aether runtime modules listed in [LICENSE](LICENSE): `node`, `aether-deployment`, `aether-control`, `aether-ttm`, `aether-ttm-onnx`, `aether-stream`, `aether-invoke`, `ember`, `forge-api`, `forge-core` (and the `forge-tests` harness)

Each BSL module carries its own `LICENSE` file. No Apache-2.0 module depends on a BSL module; `tools/check-licence-closure.py` enforces that in CI.
