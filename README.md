# Pragmatica

A unified monorepo containing the Pragmatica ecosystem for modern functional Java development.

## Components

| Component | Description | License |
|-----------|-------------|---------|
| **Pragmatica Lite Core** | Functional programming library with Result, Option, and Promise monads | Apache 2.0 |
| **Pragmatica Lite Integrations** | Integration modules for popular Java libraries (Jackson, Micrometer, JPA, jOOQ, HTTP, etc.) | Apache 2.0 |
| **Pragmatica JBCT Tools** | CLI and Maven plugin for JBCT code formatting and linting | Apache 2.0 |
| **Pragmatica Aether** | AI-driven distributed runtime for Java applications | BSL 1.1 |

## Version

Current version: **0.16.0**

### Pre-Monorepo Versions
- pragmatica-lite: 0.11.3
- jbct-cli: 0.6.1
- aetherx: 0.8.2

## Quick Start

### Build

```bash
# Build all modules (skip tests for speed)
mvn install -DskipTests

# Build with tests
mvn verify
```

### Using Pragmatica Lite Core

```xml
<dependency>
    <groupId>org.pragmatica-lite</groupId>
    <artifactId>core</artifactId>
    <version>0.16.0</version>
</dependency>
```

### Using JBCT Maven Plugin

```xml
<plugin>
    <groupId>org.pragmatica-lite</groupId>
    <artifactId>jbct-maven-plugin</artifactId>
    <version>0.16.0</version>
    <executions>
        <execution>
            <goals>
                <goal>format</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

### Using Aether Slice API

```xml
<dependency>
    <groupId>org.pragmatica-lite.aether</groupId>
    <artifactId>slice-api</artifactId>
    <version>0.16.0</version>
    <scope>provided</scope>
</dependency>
```

## Project Structure

```
pragmatica/
├── core/                    # Pragmatica Lite Core (Result, Option, Promise)
├── integrations/            # Integration modules
│   ├── json/jackson/        # Jackson JSON integration
│   ├── metrics/micrometer/  # Micrometer metrics integration
│   ├── db/                  # Database integrations (JPA, jOOQ, JDBC, R2DBC)
│   ├── net/                 # Network (HTTP client, TCP, DNS)
│   ├── serialization/       # Kryo, Fury serialization
│   ├── messaging/           # Message bus
│   ├── consensus/           # Consensus protocols
│   ├── cluster/             # Distributed cluster networking
│   └── ...
├── testing/                 # Property-based testing utilities
├── jbct/                    # JBCT Tools
│   ├── jbct-core/           # Core formatting and linting
│   ├── jbct-cli/            # CLI application
│   ├── jbct-maven-plugin/   # Maven plugin
│   └── slice-processor/     # Aether slice annotation processor
├── aether/                  # Aether Distributed Runtime
│   ├── slice-api/           # Slice interface definitions
│   ├── node/                # Runtime node
│   ├── forge/               # Simulator for testing
│   ├── cli/                 # Aether CLI
│   └── infra-slices/        # Infrastructure slices
├── examples/                # Example projects
│   ├── pragmatica-lite/     # Core library examples
│   ├── url-shortener/       # Aether slice example
│   └── ecommerce/           # Multi-slice example
├── docs/                    # Documentation
└── scripts/                 # Build and release scripts
```

## Documentation

- [Core Library](core/README.md) - Result, Option, Promise types
- [JBCT Tools](jbct/README.md) - Formatting and linting
- [Aether Runtime](aether/README.md) - Distributed runtime
- [Aether Documentation](aether/docs/README.md) - Full Aether docs

## Requirements

- Java 25+
- Maven 3.9+

## License

- Core modules (core, integrations, jbct): Apache License 2.0
- Aether modules: Business Source License 1.1 (converts to Apache 2.0 on January 1, 2030)

See [LICENSE](LICENSE) for core modules and [aether/LICENSE](aether/LICENSE) for Aether-specific terms.
