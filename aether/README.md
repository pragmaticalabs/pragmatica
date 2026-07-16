# Aether — Unified Application Runtime

Unified Application Runtime for Java -- scale horizontally without microservices complexity.

> **Release status: `1.0.0-rc3` (release candidate).** This RC validates the distributed
> foundation — consensus, leader election, failure detection, membership, and topology
> management — under sustained cloud load. Scope for this RC:
> - **Single trust domain.** Aether assumes all cluster nodes and management clients are
>   operated by one trusted party. It is **not** hardened for multi-tenant or hostile-network
>   deployment.
> - **Security is built in but OFF by default in this RC.** The management API supports
>   API-key auth with role-based access (viewer / operator / admin), and inter-node transport
>   runs over TLS (self-signed by default, or operator-supplied certificates) — but the default
>   `SecurityMode` is `NONE`, so auth must be **explicitly enabled**. Making security default-on
>   is a hard gate for RC2. Separately, `AETHER_INSECURE_DEV_MODE` is an explicit opt-in that
>   enables test-injection endpoints; it is **refused at boot** when operator TLS certificates
>   are configured, and logs a loud startup warning whenever it is active.
> - **Not yet production-hardened.** Some background reconcilers are tuned for settled clusters
>   and can be transiently slower to converge under heavy churn; these are tracked for the next
>   milestones. Use RC1 for evaluation and non-critical workloads.

## Overview

Aether lets you deploy Java services as **slices** and handles distribution, scaling, and resilience automatically. A slice is a typed Java interface with dependency injection via factory method parameters -- the same service model as Spring `@Service`, without the infrastructure overhead. Write services, and Aether manages deployment across nodes, automatic scaling based on CPU/latency/request rate, failure recovery, and load balancing. No actors, no message passing, no new paradigm to learn.

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
| **Start Here** | [What is Aether?](docs/aether-overview.md), [Introduction](docs/articles/aether-introduction.md), [Getting Started](docs/slice-developers/getting-started.md), [Migration Guide](docs/slice-developers/migration-guide.md), [FAQ](docs/slice-developers/faq.md) |
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
