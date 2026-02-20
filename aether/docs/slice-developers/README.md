# Slice Development

Build self-contained, independently deployable business capabilities with Aether slices.

## Overview

A **slice** is a microservice-like unit that exposes a single-responsibility API via a Java interface, communicates asynchronously using `Promise<T>`, declares dependencies explicitly through a factory method, and can be deployed, scaled, and updated independently.

```java
@Slice
public interface OrderService {
    Promise<OrderResult> placeOrder(PlaceOrderRequest request);

    static OrderService orderService(InventoryService inventory) {
        return OrderServiceImpl.orderServiceImpl(inventory);
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
| [Resource Reference](resource-reference.md) | Resource provisioning & configuration |
| [Forge Guide](forge-guide.md) | Local development with Forge |
| [Troubleshooting](troubleshooting.md) | Common issues and solutions |
| [Migration Guide](migration-guide.md) | Moving from monolith to slices |
| [Demos](demos.md) | Example applications and walkthroughs |

## Key Concepts

1. **Single-param methods** - All slice API methods take one request parameter and return `Promise<T>`
2. **Factory method** - Static method creating the slice instance with its dependencies
3. **Internal vs External** - Dependencies in the same base package are internal; others are external
4. **Blueprint** - TOML file listing slices in dependency order for deployment

## Build Pipeline

```
@Slice interface -> Annotation Processor -> Generated code + manifests
                                                |
                              Maven Plugin -> Slice JAR
                                                |
                              Blueprint Generator -> blueprint.toml
```

## Quick Start

```bash
jbct init --slice my-service
cd my-service
mvn verify
./deploy-forge.sh
```
