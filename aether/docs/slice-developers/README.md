# Slice Development

Build self-contained, independently deployable business capabilities with Aether slices.

## Overview

A **slice** is a service -- a typed Java interface with a factory method for dependencies, deployed and managed by the Aether runtime. If you have written a Spring `@Service`, you already know the programming model. Slices use request/response via `Promise<T>`, declare dependencies through factory method parameters (the equivalent of constructor injection), and can be deployed, scaled, and updated independently.

## Slices Are Services, Not Actors

A common first reaction from architects is "so you implemented actors." This is incorrect and the mental model is limiting. Slices are services -- the same programming model you already use.

| Dimension | Actors (Akka/Erlang) | Slices |
|-----------|---------------------|--------|
| Communication | Untyped message passing, fire-and-forget | Typed method calls, request/response (`Promise<T>`) |
| Interface | Receive handler with pattern matching | Standard Java interface with methods |
| Dependencies | Actor references, message routing | Interface parameters in factory method (dependency injection) |
| State model | Mutable private state, behavior switching | Stateless by design (state in DB/KV-Store) |
| HTTP | Requires adapter layer | Native HTTP route declaration via `routes.toml` |
| Paradigm shift | Fundamental (message-driven thinking) | None (service-oriented thinking) |
| Learning curve | Steep (supervision, mailboxes, dead letters) | Minimal (it's a Java interface) |

**What slices map to:**

| You know this... | Slice equivalent |
|-----------------|-----------------|
| `@Service` class | `@Slice` interface |
| `@Autowired` / constructor injection | Factory method parameters |
| Service method call | `Promise<T>` method call (local or remote, transparent) |
| `@RestController` | `routes.toml` (declarative HTTP routing) |
| Kubernetes Deployment YAML | Blueprint TOML |
| HPA (Horizontal Pod Autoscaler) | Built-in reactive + predictive scaling |

There is no message passing, no mailbox, no behavior switching, no supervision tree. Your existing service-based designs, API contracts, domain decomposition, and interaction patterns transfer directly.

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
| [FAQ](faq.md) | Common questions (actors vs slices, Spring comparison, migration) |
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
