# Pragmatica Aether

**Distributed Runtime for Java — Without the Microservices Complexity**

Aether lets you scale Java applications horizontally without rewriting them as microservices. Write simple business logic, deploy it as **slices**, and let Aether handle distribution, scaling, and resilience automatically.

## The Problem with Microservices

Microservices promise scalability but deliver complexity:
- Dozens of services to deploy and monitor
- Network calls everywhere, each a potential failure point
- Distributed transactions, eventual consistency headaches
- Months of infrastructure work before writing business logic

**There's a simpler way.**

## Aether's Approach

```
┌────────────────────────────────────────────────────────────────┐
│  You write:            │  Aether handles:                     │
│  ─────────             │  ──────────────                       │
│  Business logic        │  Distribution across nodes           │
│  Use cases             │  Automatic scaling                    │
│  Domain services       │  Failure recovery                     │
│                        │  Load balancing                       │
│                        │  Metrics & monitoring                 │
└────────────────────────────────────────────────────────────────┘
```

Your code stays simple. A single use case:

```java
@Slice
public interface PlaceOrder {
    Promise<OrderResult> execute(PlaceOrderRequest request);

    static PlaceOrder placeOrder(InventoryService inventory, PricingService pricing) {
        return request ->
            inventory.checkStock(request.items())
                .flatMap(stock -> pricing.calculateTotal(request.items()))
                .flatMap(total -> createOrder(request, stock, total));
    }
}
```

Aether deploys it, scales it when needed, and handles failures — all transparent to your code.

## Two Paths to Scalability

### New Projects

Start with [JBCT](https://github.com/siy/pragmatica-lite) patterns. Each use case becomes a slice. Scale from day one.

→ [Getting Started Guide](docs/guide/getting-started.md)

### Existing Monoliths

Extract hot paths into slices gradually. No big bang rewrite.

→ [Migration Guide](docs/guide/migration-guide.md)

## Installation

**Requires Java 25**

### Quick Install (Linux/macOS)

```bash
curl -fsSL https://raw.githubusercontent.com/siy/aether/main/install.sh | sh
```

The installer:
- Downloads `aether`, `aether-node`, and `aether-forge`
- Installs to `~/.aether/`
- Adds `~/.aether/bin` to PATH

Custom install location: `AETHER_HOME=/custom/path sh install.sh`

### Manual Installation

Download JARs from [releases](https://github.com/siy/aether/releases):

```bash
java -jar aether.jar --help
java -jar aether-node.jar --help
java -jar aether-forge.jar
```

### Build from Source

```bash
git clone https://github.com/siy/aether.git
cd aether
mvn package -DskipTests

# JARs:
#   cli/target/aether.jar
#   node/target/aether-node.jar
#   forge/target/aether-forge.jar
```

## Quick Start

```bash
# Check cluster status
aether status

# Deploy a slice
aether deploy org.example:my-slice:1.0.0

# Scale it
aether scale org.example:my-slice:1.0.0 -n 3
```

## Try Forge (Local Development Environment)

Run a complete Aether cluster locally with visual monitoring:

```bash
aether-forge
# Open http://localhost:8888
```

- **Visual Dashboard** — Real-time cluster topology, per-node metrics (CPU, heap, leader status)
- **Cluster Operations** — Add/remove nodes, rolling restarts, scale up/down
- **Chaos Testing** — Kill nodes, inject failures, observe recovery
- **Management Access** — Each node exposes management API (ports 5150+)

Perfect for development, testing, and learning how Aether works.

→ [Forge Guide](docs/guide/forge-guide.md)

## Documentation

### Start Here
| Document | Description |
|----------|-------------|
| **[Introduction](docs/guide/introduction.md)** | What Aether is and why it exists |
| **[Getting Started](docs/guide/getting-started.md)** | Create your first project |
| **[Migration Guide](docs/guide/migration-guide.md)** | Extract slices from existing code |

### Core Concepts
| Document | Description |
|----------|-------------|
| [Scaling](docs/guide/scaling.md) | How and when scaling happens |
| [Slice Lifecycle](docs/slice-lifecycle.md) | Slice states and transitions |
| [Architecture](docs/architecture-overview.md) | System internals |

### Reference
| Document | Description |
|----------|-------------|
| [CLI Reference](docs/guide/cli-reference.md) | All commands |
| [Forge Guide](docs/guide/forge-guide.md) | Local cluster development & testing |
| [Slice Developer Guide](docs/slice-developer-guide.md) | Writing slices |
| [Configuration Reference](docs/guide/configuration-reference.md) | All settings |
| [Management API](docs/api/management-api.md) | HTTP API reference |

### Operations
| Document | Description |
|----------|-------------|
| [Rolling Updates](docs/guide/rolling-updates.md) | Zero-downtime deployments |
| [Alerts & Thresholds](docs/guide/alerts-and-thresholds.md) | Monitoring and alerts |
| [Docker Deployment](docs/guide/docker-deployment.md) | Container deployment |
| [E2E Testing](docs/guide/e2e-testing.md) | Integration testing |

### Design Documents
| Document | Description |
|----------|-------------|
| [Vision & Goals](docs/vision-and-goals.md) | Long-term direction |
| [Metrics & Control](docs/metrics-and-control.md) | AI control layers |
| [Typed Slice APIs](docs/typed-slice-api-design.md) | Compile-time type safety |

## Key Features

| Feature | Benefit |
|---------|---------|
| **Automatic Scaling** | Scales based on CPU, latency, request rate |
| **Predictive Scaling** | Learns patterns, scales before load spikes |
| **No Cold Starts** | Instances stay warm, no container spin-up |
| **Failure Recovery** | Automatic retry, failover, rebalancing |
| **Type-Safe Invocation** | Compile-time checks for slice calls |
| **Local Dev Environment** | Forge for cluster development, testing & debugging |

## Project Structure

```
aetherx/
├── slice-api/           # Slice interface
├── slice/               # Slice management
├── node/                # Runtime (AetherNode)
├── cluster/             # Rabia consensus
├── forge/               # Local cluster simulator & dashboard
├── cli/                 # Command-line tools
└── examples/
    └── order-demo/      # Complete demo (5 slices)
```

## Requirements

- **Java 25** (required)
- Maven 3.9+

## License

[Business Source License 1.1](LICENSE)

Free for internal use at any scale. Converts to Apache 2.0 on January 1, 2030.

## Links

- [JBCT Patterns](https://github.com/siy/pragmatica-lite) - Recommended coding style
- [Changelog](CHANGELOG.md) - Release history
