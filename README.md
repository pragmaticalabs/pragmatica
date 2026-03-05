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

Current version: **0.19.2**

### Pre-Monorepo Versions
- pragmatica-lite: 0.11.3
- jbct-cli: 0.6.1
- aetherx: 0.8.2

## Install

```bash
curl -fsSL https://raw.githubusercontent.com/pragmaticalabs/pragmatica/main/aether/install.sh | sh
```

## Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| Java | 25+ | Runtime and build |
| Maven | 3.9+ | Build system |
| Podman | Latest | Container runtime for PostgreSQL |
| k6 | Latest | Load testing (optional) |

## Quick Start

### Pricing Engine (with PostgreSQL)

```bash
cd examples/pricing-engine
./start-postgres.sh
./run-forge.sh
```

```bash
curl -s -X POST http://localhost:8070/api/v1/pricing/calculate \
  -H 'Content-Type: application/json' \
  -d '{"productId":"WIDGET-D","quantity":3,"regionCode":"US-CA","couponCode":"SAVE20"}' | jq
```

### URL Shortener (with PostgreSQL)

```bash
cd examples/url-shortener
./start-postgres.sh
./run-forge.sh
```

```bash
curl -s -X POST http://localhost:8070/api/v1/urls/ \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/hello-world"}' | jq
```

### Ecommerce (with PostgreSQL)

A multi-slice application with pricing, inventory, payment, fulfillment, and order placement.

```bash
curl -s -X POST http://localhost:8070/api/v1/orders/ \
  -H 'Content-Type: application/json' \
  -d '{
    "customerId": "customer-42",
    "items": [
      {"productId": "LAPTOP-PRO", "quantity": 1},
      {"productId": "MOUSE-WIRELESS", "quantity": 2}
    ],
    "shippingAddress": {
      "street": "123 Main St",
      "city": "San Francisco",
      "state": "CA",
      "postalCode": "94105",
      "country": "US"
    },
    "paymentMethod": {
      "cardNumber": "4111111111111111",
      "expiryMonth": "12",
      "expiryYear": "2027",
      "cvv": "123",
      "cardholderName": "Jane Smith"
    },
    "shippingOption": "STANDARD",
    "discountCode": "SAVE10"
  }' | jq
```

## Load Testing

Each example includes k6 load testing scripts:

| Example | Script | Description |
|---------|--------|-------------|
| pricing-engine | `k6/load-test.js` | Steady-state load test |
| pricing-engine | `k6/ramp-up.js` | Find saturation point |
| pricing-engine | `k6/per-node.js` | Per-node comparison |
| pricing-engine | `k6/spike.js` | Spike recovery test |
| url-shortener | `k6/load-test.js` | Steady-state load test |
| url-shortener | `k6/ramp-up.js` | Find saturation point |
| url-shortener | `k6/per-node.js` | Per-node comparison |
| url-shortener | `k6/spike.js` | Spike recovery test |

Helper shell scripts are provided for common scenarios:

```bash
cd examples/pricing-engine
k6/run-steady.sh      # Steady-state load
k6/run-ramp.sh        # Ramp-up to find limits
k6/run-per-node.sh    # Per-node comparison
k6/run-spike.sh       # Spike recovery
```

## Build

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
    <version>0.19.2</version>
</dependency>
```

### Using JBCT Maven Plugin

```xml
<plugin>
    <groupId>org.pragmatica-lite</groupId>
    <artifactId>jbct-maven-plugin</artifactId>
    <version>0.19.2</version>
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
    <version>0.19.2</version>
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
│   ├── ember/               # Embeddable runtime
│   ├── lb/                  # Load balancer
│   ├── dashboard/           # Web dashboard
│   ├── environment/         # Environment management
│   ├── resource/            # Resource handling
│   └── slice/               # Slice loading and management
├── examples/                # Example projects
│   ├── pragmatica-lite/     # Core library examples
│   ├── url-shortener/       # URL shortener with PostgreSQL
│   ├── ecommerce/           # Multi-slice ecommerce app
│   ├── pricing-engine/      # Dynamic pricing with PostgreSQL
│   └── banking/             # Banking domain example
├── docs/                    # Documentation
└── scripts/                 # Build and release scripts
```

## Documentation

- [Core Library](core/README.md) - Result, Option, Promise types
- [JBCT Tools](jbct/README.md) - Formatting and linting
- [Aether Runtime](aether/README.md) - Distributed runtime
- [Aether Documentation](aether/docs/README.md) - Full Aether docs

## License

- Core modules (core, integrations, jbct): Apache License 2.0
- Aether modules: Business Source License 1.1 (converts to Apache 2.0 on January 1, 2030)

See [LICENSE](LICENSE) for core modules and [aether/LICENSE](aether/LICENSE) for Aether-specific terms.
