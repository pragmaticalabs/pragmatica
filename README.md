# Pragmatica Aether

**Pragmatica Aether Distributed Runtime** - AI-driven distributed runtime environment for Java applications

Aether enables predictive scaling, intelligent orchestration, and seamless multi-cloud deployment without requiring
changes to business logic.

## What Makes Aether Different

- **Predictive, Not Reactive**: AI learns traffic patterns and scales BEFORE load increases
- **Intelligent Orchestration**: Complex deployments (rolling updates, canary, blue/green, cloud migration) handled
  automatically
- **Transparent Distribution**: Write business logic without distributed systems concerns
- **Slice-Based Deployment**: Deploy use cases (lean slices) or services (service slices) with unified management
- **Typed Slice APIs**: Compile-time type safety for inter-slice communication via annotation processor

## Core Concepts

### Slices

Independently deployable units with well-defined entry points:

- **Service Slices**: Traditional microservices with multiple entry points
- **Lean Slices**: Single use case or event handler with one entry point

### AI-Driven Management

External AI observes metrics, learns patterns, and makes topology decisions:

- When to scale slice instances
- When to start/stop compute nodes
- How to perform complex deployments
- Where to deploy across clouds

### Convergence Model

Runtime continuously reconciles actual deployment with desired state stored in consensus KV-Store.

### Rabia Consensus

Leaderless CFT (crash-fault-tolerant) consensus algorithm providing:

- Cluster-wide state consistency
- Automatic leader election for coordination tasks
- No persistent event log required

## Key Features

| Feature | Description |
|---------|-------------|
| **Inter-slice Invocation** | Type-safe RPC with retry, timeout, and load balancing |
| **ClassLoader Isolation** | Slices isolated via SliceBridge with byte[] boundary |
| **Metrics Collection** | Per-node JVM metrics and per-entry-point call metrics |
| **Decision Tree Controller** | Programmatic scaling rules evaluated every second |
| **Management API** | HTTP API for cluster inspection and control |
| **Artifact Repository** | Built-in DHT-backed Maven-compatible repository |

## Quick Start

### Prerequisites

- JDK 25+
- Maven 3.9+

### Build

```bash
mvn clean install
```

### Try Aether Forge (Cluster Simulator)

The fastest way to see Aether in action:

```bash
cd forge
mvn package
java -jar target/forge-0.6.1.jar
```

This starts a 5-node cluster with a visual dashboard at `http://localhost:8888`.
Try killing nodes, adding nodes, and adjusting load to see resilience in action.

### Run the Order Demo

```bash
# Build demo slices
cd examples/order-demo
mvn clean install

# Start cluster and deploy
aether cluster start --nodes 3
aether blueprint apply demo-order.blueprint

# Test the API
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId": "CUST-123", "items": [{"productId": "PROD-ABC", "quantity": 2}]}'
```

## Documentation

| Document | Description |
|----------|-------------|
| [Vision & Goals](docs/vision-and-goals.md) | Architecture and design principles |
| [Architecture Overview](docs/architecture-overview.md) | System architecture and components |
| [Typed Slice API Design](docs/typed-slice-api-design.md) | Compile-time type-safe slice APIs |
| [Forge & Demos](docs/demos.md) | Cluster simulator and demo guide |
| [Aether Node](docs/aether-node.md) | Node configuration and API |
| [Slice Lifecycle](docs/slice-lifecycle.md) | Slice state machine |
| [Slice Developer Guide](docs/slice-developer-guide.md) | How to write slices |
| [Metrics & Control](docs/metrics-and-control.md) | Metrics collection and AI control layers |
| [Infrastructure Services](docs/infrastructure-services.md) | Built-in platform services |

## Project Structure

```
aetherx/
├── slice-annotations/  # @Slice annotation for typed APIs
├── slice-api/          # Slice interface definitions
├── slice/              # Slice management, ClassLoader isolation
├── node/               # Runtime node (AetherNode, metrics, invocation)
├── cluster/            # Rabia consensus, KVStore
├── common/             # Shared utilities
├── http-server/        # HTTP server infrastructure
├── forge/              # Cluster simulator with dashboard
├── cli/                # Command-line interface
├── infra-services/     # Infrastructure services
│   └── artifact-repo/  # DHT-backed Maven repository
├── example-slice/      # Reference slice implementation
└── examples/
    └── order-demo/     # Multi-slice order domain (5 slices)
```

## License

Pragmatica Aether Distributed Runtime is licensed under the [Business Source License 1.1](LICENSE).

This means you can:
- Use internally at any scale
- Integrate into your applications
- Modify and create derivative works
- Evaluate and test freely

The software converts to Apache License 2.0 on January 1, 2030.
