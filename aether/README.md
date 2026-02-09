# Pragmatica Aether

Distributed runtime for Java -- scale horizontally without microservices complexity.

## Overview

Aether lets you deploy Java business logic as **slices** and handles distribution, scaling, and resilience automatically. Write simple use cases, and Aether manages deployment across nodes, automatic scaling based on CPU/latency/request rate, failure recovery, and load balancing.

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

## Usage

### Quick Start

```bash
# Check cluster status
aether status

# Deploy a slice
aether deploy org.example:my-slice:1.0.0

# Scale it
aether scale org.example:my-slice:1.0.0 -n 3
```

### Local Development with Forge

```bash
aether-forge
# Open http://localhost:8888
```

Forge provides a visual dashboard, cluster operations, chaos testing, and per-node management API access.

### Installation

Requires **Java 25**.

```bash
curl -fsSL https://raw.githubusercontent.com/siy/aether/main/install.sh | sh
```

### Build from Source

```bash
git clone https://github.com/siy/aether.git
cd aether && mvn package -DskipTests
```

## Documentation

| Category | Documents |
|----------|-----------|
| **Start Here** | [What is Aether?](docs/aether-overview.md), [Introduction](docs/guide/introduction.md), [Getting Started](docs/guide/getting-started.md), [Migration Guide](docs/guide/migration-guide.md) |
| **Core Concepts** | [Scaling](docs/guide/scaling.md), [Slice Lifecycle](docs/slice-lifecycle.md), [Architecture](docs/architecture-overview.md) |
| **Reference** | [CLI Reference](docs/guide/cli-reference.md), [Forge Guide](docs/guide/forge-guide.md), [Configuration](docs/guide/configuration-reference.md), [Management API](docs/api/management-api.md) |
| **Operations** | [Rolling Updates](docs/guide/rolling-updates.md), [Alerts](docs/guide/alerts-and-thresholds.md), [Docker](docs/guide/docker-deployment.md) |
| **Design** | [Vision & Goals](docs/vision-and-goals.md), [Metrics & Control](docs/metrics-and-control.md), [Typed Slice APIs](docs/typed-slice-api-design.md) |

## Project Structure

```
aether/
├── slice-api/           # Slice interface
├── slice/               # Slice management
├── node/                # Runtime (AetherNode)
├── cluster/             # Rabia consensus
├── forge/               # Local cluster simulator & dashboard
├── cli/                 # Command-line tools
└── examples/
    └── ecommerce/       # E-commerce demo
```

## Dependencies

- Java 25
- Maven 3.9+
- `pragmatica-lite-core`
- `pragmatica-lite-consensus`
