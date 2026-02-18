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
| **Start Here** | [What is Aether?](docs/aether-overview.md), [Introduction](docs/articles/aether-introduction.md), [Getting Started](docs/slice-developers/getting-started.md), [Migration Guide](docs/slice-developers/migration-guide.md) |
| **Core Concepts** | [Scaling](docs/operators/scaling.md), [Slice Lifecycle](docs/contributors/slice-lifecycle.md), [Architecture](docs/contributors/architecture.md) |
| **Reference** | [CLI Reference](docs/reference/cli.md), [Forge Guide](docs/slice-developers/forge-guide.md), [Configuration](docs/reference/configuration.md), [Management API](docs/reference/management-api.md) |
| **Operations** | [Rolling Updates](docs/operators/rolling-updates.md), [Monitoring](docs/operators/monitoring.md), [Docker](docs/operators/docker-deployment.md) |
| **Design** | [Vision & Goals](docs/archive/vision-and-goals.md), [Metrics & Control](docs/contributors/metrics-control.md), [Slice API](docs/reference/slice-api.md) |

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
