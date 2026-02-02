# Slice Development

Build self-contained, independently deployable business capabilities with Aether slices.

## What is a Slice?

A **slice** is a microservice-like unit that:
- Exposes a single-responsibility API via a Java interface
- Communicates asynchronously using `Promise<T>`
- Declares dependencies explicitly through a factory method
- Can be deployed, scaled, and updated independently

```java
@Slice
public interface OrderService {
    Promise<OrderResult> placeOrder(PlaceOrderRequest request);

    static OrderService orderService(InventoryService inventory) {
        return new OrderServiceImpl(inventory);
    }
}
```

## Documentation

| Document | Description |
|----------|-------------|
| [Getting Started](getting-started.md) | Create your first slice in 5 minutes |
| [Development Guide](development-guide.md) | Complete development workflow |
| [Slice Patterns](slice-patterns.md) | Service vs Lean slices, common patterns |
| [Testing Slices](testing-slices.md) | Unit and integration testing |
| [Deployment](deployment.md) | Blueprints, environments, CI/CD |
| [Infrastructure Services](infra-services.md) | Using infrastructure slices |
| [Forge Guide](forge-guide.md) | Local development with Forge |
| [Troubleshooting](troubleshooting.md) | Common issues and solutions |

## Architecture Deep Dives

For contributors and those wanting to understand internals:

| Document | Description |
|----------|-------------|
| [Slice Architecture](../contributors/slice-architecture.md) | Code generation, packaging, manifests |
| [Slice Runtime](../contributors/slice-runtime.md) | How slices execute in Aether |

## Reference

| Document | Description |
|----------|-------------|
| [Slice API Reference](../reference/slice-api.md) | `@Slice` annotation, manifest format, CLI |

## Quick Start

```bash
# Create a new slice project
jbct init --slice my-service

# Build and test
cd my-service
mvn verify

# Deploy to local Forge
./deploy-forge.sh
```

## Key Concepts

1. **Single-param methods**: All slice API methods take exactly one request parameter and return `Promise<T>`
2. **Factory method**: A static method that creates the slice instance with its dependencies
3. **Internal vs External**: Dependencies in the same base package are internal; others are external
4. **Blueprint**: TOML file listing slices in dependency order for deployment

## Build Pipeline

```
@Slice interface → Annotation Processor → Generated code + manifests
                                               ↓
                              Maven Plugin → API JAR + Impl JAR
                                               ↓
                              Blueprint Generator → blueprint.toml
```

## Requirements

- Java 21+
- Maven 3.8+
- JBCT CLI 0.4.8+

## Project Structure

```
my-slice/
├── pom.xml
├── jbct.toml
├── generate-blueprint.sh
├── deploy-forge.sh
├── deploy-test.sh
├── deploy-prod.sh
└── src/
    ├── main/java/
    │   └── org/example/myslice/
    │       ├── MySlice.java         # @Slice interface
    │       ├── MySliceImpl.java     # Implementation
    │       ├── MyRequest.java       # Request record
    │       └── MyResponse.java      # Response record
    └── test/java/
        └── org/example/myslice/
            └── MySliceTest.java
```
