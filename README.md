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

## Quick Start

**Requires Java 25**

```bash
# Create new project
aether init my-app
cd my-app

# Build
mvn clean install

# Start cluster and deploy
aether start
aether deploy hello-world

# Test
curl http://localhost:8080/hello?name=World
# {"message": "Hello, World!"}

# Scale it
aether scale hello-world --instances 3
```

## Try Forge (Chaos Testing)

See Aether's resilience in action:

```bash
cd forge
mvn package
java -jar target/forge-0.6.1.jar
# Open http://localhost:8888
```

Kill nodes, inject latency, watch recovery — all from the dashboard.

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
| [Forge Guide](docs/guide/forge-guide.md) | Chaos testing |
| [Slice Developer Guide](docs/slice-developer-guide.md) | Writing slices |

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
| **Chaos Testing** | Built-in Forge for resilience testing |

## Project Structure

```
aetherx/
├── slice-api/           # Slice interface
├── slice/               # Slice management
├── node/                # Runtime (AetherNode)
├── cluster/             # Rabia consensus
├── forge/               # Chaos testing dashboard
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
