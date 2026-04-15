# Pragmatica

A monorepo for building reliable Java backends with functional programming patterns and a distributed runtime.

## What's Inside

**[Pragmatica Lite Core](core/README.md)** — Functional primitives for Java: `Result<T>`, `Option<T>`, `Promise<T>`. No exceptions, no nulls, composable error handling.

**[Pragmatica Lite Integrations](integrations/)** — Ready-made integrations: PostgreSQL (async + JDBC + jOOQ), HTTP server/client, serialization (Kryo, Fury), consensus protocols, distributed storage, messaging, metrics.

**[JBCT Tools](jbct/README.md)** — CLI and Maven plugin for Java Backend Coding Technology: code formatting, linting, slice annotation processing, project scaffolding.

**[Aether Runtime](aether/README.md)** — Distributed runtime for Java. Deploy services as slices, scale transparently, no microservices boilerplate. Consensus-based state, automatic topology management, built-in observability.

## Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| Java | 25+ | Runtime and build |
| Maven | 3.9+ | Build system |
| Docker | Latest | Container runtime (for examples with PostgreSQL) |

## Quick Start

```bash
# Install Aether CLI
curl -fsSL https://raw.githubusercontent.com/pragmaticalabs/pragmatica/main/aether/install.sh | sh

# Run an example
cd examples/pricing-engine
./start-postgres.sh
./run-forge.sh

# Try it
curl -s -X POST http://localhost:8070/api/v1/pricing/calculate \
  -H 'Content-Type: application/json' \
  -d '{"productId":"WIDGET-D","quantity":3,"regionCode":"US-CA","couponCode":"SAVE20"}' | jq
```

See [examples/](examples/) for more: URL shortener, ecommerce (multi-slice), banking, notification hub, PostgreSQL persistence showcase.

## Build

```bash
mvn install -DskipTests    # Build all modules
mvn verify                 # Build with tests
```

## Using in Your Project

```xml
<!-- Core library -->
<dependency>
    <groupId>org.pragmatica-lite</groupId>
    <artifactId>core</artifactId>
    <version>1.0.0-rc1</version>
</dependency>

<!-- Aether Slice API (for writing slices) -->
<dependency>
    <groupId>org.pragmatica-lite.aether</groupId>
    <artifactId>slice-api</artifactId>
    <version>1.0.0-rc1</version>
    <scope>provided</scope>
</dependency>

<!-- JBCT Maven Plugin (formatting + linting) -->
<plugin>
    <groupId>org.pragmatica-lite</groupId>
    <artifactId>jbct-maven-plugin</artifactId>
    <version>1.0.0-rc1</version>
</plugin>
```

## Documentation

- [Core Library](core/README.md) — Result, Option, Promise types
- [JBCT Tools](jbct/README.md) — Formatting, linting, project scaffolding
- [Aether Runtime](aether/README.md) — Architecture and deployment
- [Aether Docs](aether/docs/README.md) — Full reference documentation

## License

- Core, integrations, JBCT: Apache License 2.0
- Aether: Business Source License 1.1 (converts to Apache 2.0 on January 1, 2030)

See [LICENSE](LICENSE) and [aether/LICENSE](aether/LICENSE).
