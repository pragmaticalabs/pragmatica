# Pragmatica Aether: Distributed Runtime Without the Complexity

## The Problem

Modern applications need to scale. The industry's answer has been microservices: break your monolith into dozens of independently deployable services, each with its own database, API contracts, deployment pipeline, and operational overhead.

The result? **Distributed monoliths** with all the complexity of distribution and none of the benefits. Teams spend more time on infrastructure than business logic. Simple changes require coordinating multiple teams. Debugging becomes archaeology across service boundaries.

**There has to be a better way.**

## A Different Approach

Aether takes a radically different approach: **keep your code simple, let the runtime handle distribution**.

Instead of designing your application around network boundaries, you write clean, focused business logic. Aether's runtime decides when and how to distribute it based on actual load patterns, not architectural assumptions made months ago.

```
Traditional Microservices:
┌─────────────────────────────────────────────────────────────────┐
│  You design the architecture                                    │
│  You manage service boundaries                                  │
│  You handle service discovery                                   │
│  You implement retries, circuit breakers, timeouts              │
│  You coordinate deployments                                     │
│  You debug distributed traces                                   │
└─────────────────────────────────────────────────────────────────┘

Aether:
┌─────────────────────────────────────────────────────────────────┐
│  You write business logic                                       │
│  Runtime handles everything else                                │
└─────────────────────────────────────────────────────────────────┘
```

## Two Paths to Scalability

### Path 1: New Projects with JBCT

If you're starting fresh, use [Java Backend Coding Technology (JBCT)](https://github.com/siy/pragmatica-lite) to write clean, functional business logic. Each use case becomes a **Slice** - a self-contained unit that Aether can deploy and scale independently.

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

This isn't a microservice. It's a **use case**. Aether decides whether it runs in the same process as other slices or on a separate node based on load.

### Path 2: Existing Monoliths

Already have a monolith? You don't need to rewrite it. Aether supports **gradual extraction**:

1. **Identify hot paths** - Find the code that needs to scale independently
2. **Extract to a Slice** - Wrap the functionality in Aether's Slice interface
3. **Add idempotency** - Ensure the extracted code can be safely retried
4. **Deploy** - Aether handles the rest

Your monolith keeps running. The extracted slice scales independently. No big bang migration.

```java
// Before: Method buried in a 10,000-line service class
public OrderResult processOrder(OrderRequest request) {
    // Complex logic that needs to scale
}

// After: Same logic, now independently scalable
@Slice
public interface ProcessOrder {
    Promise<OrderResult> execute(OrderRequest request);

    static ProcessOrder processOrder(LegacyOrderService legacy) {
        return request -> Promise.lift(
            cause -> new ProcessingError(cause),
            () -> legacy.processOrder(request)  // Wrap existing code
        );
    }
}
```

## The Magic: Idempotency Boundaries

The only requirement for Aether to work is **idempotency at slice boundaries**. This means:

- Calling a slice method twice with the same input produces the same result
- Side effects (database writes, external calls) are either idempotent or tracked

This isn't a new concept - it's the same requirement for any reliable distributed system. The difference is that Aether makes it explicit and provides tools to achieve it.

### Why Idempotency Matters

When Aether scales your slice to multiple nodes, requests might be retried (network issues, node failures). Idempotency ensures these retries are safe.

```java
// Non-idempotent: Dangerous in distributed systems
public void chargeCustomer(String customerId, Money amount) {
    paymentGateway.charge(customerId, amount);  // Double charge risk!
}

// Idempotent: Safe to retry
public void chargeCustomer(String orderId, String customerId, Money amount) {
    if (paymentRepository.exists(orderId)) {
        return;  // Already processed
    }
    paymentGateway.charge(customerId, amount);
    paymentRepository.markProcessed(orderId);
}
```

JBCT patterns naturally lead to idempotent code through:
- Immutable value objects
- Pure functions
- Explicit side effect boundaries

## How Scaling Actually Works

### Automatic Scaling

Aether monitors every slice's performance:
- Request rate per entry point
- Latency distribution
- Error rates
- Resource utilization

When metrics cross thresholds, Aether's control loop takes action:

```
┌─────────────────────────────────────────────────────────────────┐
│  Metrics Collection (every second)                              │
│  ↓                                                              │
│  Pattern Detection (2-hour sliding window)                      │
│  ↓                                                              │
│  Decision Tree Controller                                       │
│  ↓                                                              │
│  Blueprint Update (desired state)                               │
│  ↓                                                              │
│  Cluster Convergence (actual state → desired state)             │
└─────────────────────────────────────────────────────────────────┘
```

### What Gets Scaled

Aether scales **slices**, not services. A slice might be:
- A single use case (`PlaceOrder`)
- A domain service (`InventoryService`)
- A data adapter (`OrderRepository`)

You decide the granularity when you define slices. Aether decides how many instances and where they run.

### No Cold Starts

Unlike serverless platforms, Aether keeps slice instances warm. When load increases, new instances are started on existing cluster nodes - no container spin-up delay.

## Architecture Without Architects

Traditional distributed systems require careful upfront architecture:
- Which services exist?
- How do they communicate?
- Where are the database boundaries?
- What's the deployment topology?

Get it wrong, and you're stuck with costly refactoring.

Aether inverts this. **Start with a single node**. As load grows, Aether automatically distributes slices across nodes. Your architecture emerges from actual usage patterns, not theoretical diagrams.

```
Day 1: Everything on one node
┌──────────────────────────────┐
│  Node 1                      │
│  ┌──────┐ ┌──────┐ ┌──────┐ │
│  │Place │ │Inven-│ │Pric- │ │
│  │Order │ │tory  │ │ing   │ │
│  └──────┘ └──────┘ └──────┘ │
└──────────────────────────────┘

Month 6: Load increased, Aether adapted
┌──────────────────────────────┐  ┌──────────────────────────────┐
│  Node 1                      │  │  Node 2                      │
│  ┌──────┐ ┌──────┐          │  │  ┌──────┐ ┌──────┐ ┌──────┐ │
│  │Place │ │Place │          │  │  │Inven-│ │Inven-│ │Pric- │ │
│  │Order │ │Order │          │  │  │tory  │ │tory  │ │ing   │ │
│  └──────┘ └──────┘          │  │  └──────┘ └──────┘ └──────┘ │
└──────────────────────────────┘  └──────────────────────────────┘
```

You didn't redesign anything. You didn't coordinate with other teams. Aether observed that `PlaceOrder` was hot and `Inventory` needed more capacity, then acted.

## What Aether Is Not

- **Not a service mesh** - No sidecars, no proxies, no YAML manifests
- **Not Kubernetes** - No containers required (though it works with them)
- **Not serverless** - No cold starts, no vendor lock-in, no execution limits
- **Not a framework** - Your code doesn't depend on Aether internals

Aether is a **runtime** - it runs your slices and handles distribution transparently.

## Key Concepts

| Concept | Description |
|---------|-------------|
| **Slice** | A deployable unit of business logic (use case or service) |
| **Node** | A JVM running Aether runtime and hosting slices |
| **Cluster** | Multiple nodes sharing state via consensus |
| **Blueprint** | Desired state: which slices, how many instances |
| **SliceInvoker** | How slices call each other (local or remote, transparent) |

## Next Steps

- [Getting Started](getting-started.md) - Set up your first Aether project
- [Architecture](../architecture-overview.md) - Deep dive into how Aether works
- [Migration Guide](migration-guide.md) - Extract slices from existing code
- [Scaling Guide](scaling.md) - Understand scaling behavior
- [CLI Reference](cli-reference.md) - Command-line tools
- [Forge Testing](forge-guide.md) - Continuous testing with chaos engineering
