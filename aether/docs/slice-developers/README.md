# Slice Development

Build self-contained, independently deployable business capabilities with Aether slices.

## Overview

A **slice** is a microservice-like unit that exposes a single-responsibility API via a Java interface, communicates asynchronously using `Promise<T>`, declares dependencies explicitly through a factory method, and can be deployed, scaled, and updated independently.

```java
@Slice
public interface OrderService {
    Promise<OrderResult> placeOrder(PlaceOrderRequest request);

    static OrderService orderService(InventoryService inventory) {
        record orderService(InventoryService inventory) implements OrderService {
            @Override
            public Promise<OrderResult> placeOrder(PlaceOrderRequest request) {
                return inventory.reserve(new ReserveRequest(request.items()))
                                .map(reserved -> new OrderResult(reserved.orderId()));
            }
        }
        return new orderService(inventory);
    }
}
```

## Documentation

| Document | Description |
|----------|-------------|
| [Getting Started](getting-started.md) | Create your first slice in 5 minutes |
| [Development Guide](development-guide.md) | Complete development workflow |
| [Slice Patterns](slice-patterns.md) | Service vs Lean slices, common patterns |
| [Testing Slices](testing-slices.md) | End-to-end testing with Testcontainers |
| [Deployment](deployment.md) | Blueprints, environments, CI/CD |
| [Infrastructure Services](infra-services.md) | Using infrastructure slices |
| [Resource Reference](resource-reference.md) | Resource provisioning & configuration |
| [Forge Guide](forge-guide.md) | Local development with Forge |
| [Troubleshooting](troubleshooting.md) | Common issues and solutions |
| [Migration Guide](migration-guide.md) | Moving from monolith to slices |
| [Demos](demos.md) | Example applications and walkthroughs |

## Key Concepts

1. **Method parameters** - Slice methods support 0, 1, or multiple parameters, all returning `Promise<T>`. Multi-parameter methods use synthetic request records at the transport layer.
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
jbct init my-service --slice
cd my-service
mvn verify
./run-forge.sh
./deploy-forge.sh
```
