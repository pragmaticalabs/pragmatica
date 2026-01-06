# Migrating Existing Applications to Aether

This guide shows how to gradually extract functionality from a monolithic application into Aether slices, enabling independent scaling without a complete rewrite.

## The Strangler Fig Pattern

Aether supports incremental migration using the strangler fig pattern:

1. **Identify** a hot path or bottleneck in your monolith
2. **Extract** it into a slice
3. **Route** traffic to the slice
4. **Repeat** until the monolith is hollowed out

Your existing application keeps running throughout. No big bang migration.

```
Before:                          After (Phase 1):
┌─────────────────────┐          ┌─────────────────────┐
│     Monolith        │          │     Monolith        │
│  ┌───────────────┐  │          │  ┌───────────────┐  │
│  │ Order Logic   │  │          │  │ Order Logic   │──┼──→ OrderSlice
│  ├───────────────┤  │          │  ├───────────────┤  │    (Aether)
│  │ Inventory     │  │          │  │ Inventory     │  │
│  ├───────────────┤  │          │  ├───────────────┤  │
│  │ Payments      │  │          │  │ Payments      │  │
│  └───────────────┘  │          │  └───────────────┘  │
└─────────────────────┘          └─────────────────────┘
```

## Prerequisites

- **Java 25** (your monolith can stay on older Java; only slices need 25)
- Aether cluster running (even single node is fine to start)
- Identified extraction candidate

## Step 1: Identify Extraction Candidates

Good candidates for extraction:

| Characteristic | Why It's Good |
|----------------|---------------|
| CPU-intensive | Benefits most from horizontal scaling |
| Stateless or idempotent | Easiest to distribute |
| Clear boundaries | Minimal dependencies on other code |
| High request volume | Scaling has visible impact |
| Independent data | Doesn't require distributed transactions |

### Example: Order Processing

```java
// Existing monolith code - typical enterprise service
@Service
public class OrderService {
    @Autowired private InventoryRepository inventory;
    @Autowired private PricingService pricing;
    @Autowired private PaymentGateway payments;
    @Autowired private NotificationService notifications;

    @Transactional
    public OrderResult processOrder(OrderRequest request) {
        // 1. Check inventory
        var availability = inventory.checkAvailability(request.getItems());
        if (!availability.isAvailable()) {
            return OrderResult.outOfStock(availability.getMissingItems());
        }

        // 2. Calculate pricing
        var quote = pricing.calculateQuote(request.getItems(), request.getCustomerId());

        // 3. Process payment
        var paymentResult = payments.charge(request.getCustomerId(), quote.getTotal());
        if (!paymentResult.isSuccessful()) {
            return OrderResult.paymentFailed(paymentResult.getError());
        }

        // 4. Reserve inventory
        inventory.reserve(request.getItems(), paymentResult.getTransactionId());

        // 5. Create order record
        var order = orderRepository.save(new Order(request, quote, paymentResult));

        // 6. Send confirmation
        notifications.sendOrderConfirmation(order);

        return OrderResult.success(order);
    }
}
```

This is a good candidate because:
- It's a hot path (every order goes through it)
- Steps 1-2 can be made idempotent
- Steps 3-5 need idempotency handling (payment deduplication)

## Step 2: Make It Idempotent

Before extracting, ensure the code handles retries safely:

```java
// Add idempotency key to request
public record OrderRequest(
    String idempotencyKey,  // ADD THIS - client provides unique key
    String customerId,
    List<OrderItem> items
) {}

// Check for duplicate processing
@Transactional
public OrderResult processOrder(OrderRequest request) {
    // Check if already processed
    var existing = orderRepository.findByIdempotencyKey(request.idempotencyKey());
    if (existing.isPresent()) {
        return OrderResult.success(existing.get());  // Return cached result
    }

    // ... rest of processing ...

    // Save with idempotency key
    var order = orderRepository.save(new Order(request, quote, paymentResult));

    return OrderResult.success(order);
}
```

For payment processing, use the payment gateway's idempotency:

```java
// Most payment gateways support idempotency keys
var paymentResult = payments.charge(
    request.customerId(),
    quote.getTotal(),
    request.idempotencyKey()  // Same key = same result
);
```

## Step 3: Create the Slice

Create a new Maven module for the slice:

```xml
<!-- slices/order-processor/pom.xml -->
<project>
    <groupId>com.example</groupId>
    <artifactId>order-processor</artifactId>
    <version>1.0.0</version>

    <properties>
        <maven.compiler.source>25</maven.compiler.source>
        <maven.compiler.target>25</maven.compiler.target>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.pragmatica-lite.aether</groupId>
            <artifactId>slice-api</artifactId>
            <version>0.7.2</version>
        </dependency>
        <dependency>
            <groupId>org.pragmatica-lite</groupId>
            <artifactId>core</artifactId>
            <version>0.9.0</version>
        </dependency>
        <!-- Your existing dependencies for database, etc. -->
    </dependencies>
</project>
```

### Option A: Wrap Existing Code (Fastest)

If you want minimal changes, wrap the existing service:

```java
package com.example.order;

import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;

public class OrderProcessorSlice implements Slice {

    private final OrderService legacyService;  // Your existing code

    public OrderProcessorSlice() {
        // Initialize your existing service
        // This could use Spring context, manual wiring, etc.
        this.legacyService = createLegacyService();
    }

    @Override
    public List<SliceMethod<?, ?>> methods() {
        return List.of(
            SliceMethod.sliceMethod(
                "processOrder",
                OrderRequest.class,
                OrderResult.class,
                this::processOrder
            )
        );
    }

    private Promise<OrderResult> processOrder(OrderRequest request) {
        return Promise.lift(
            cause -> OrderResult.error("Processing failed: " + cause.getMessage()),
            () -> legacyService.processOrder(request)
        );
    }

    @Override
    public Promise<Unit> start() {
        // Initialize connections, warm caches, etc.
        return Promise.success(Unit.unit());
    }

    @Override
    public Promise<Unit> stop() {
        // Cleanup
        return Promise.success(Unit.unit());
    }

    private OrderService createLegacyService() {
        // Wire up your existing service
        // Could be Spring ApplicationContext, manual construction, etc.
        var inventory = new InventoryRepositoryImpl(dataSource);
        var pricing = new PricingServiceImpl();
        var payments = new PaymentGatewayImpl(stripeApiKey);
        var notifications = new NotificationServiceImpl(emailConfig);
        var orderRepo = new OrderRepositoryImpl(dataSource);

        return new OrderService(inventory, pricing, payments, notifications, orderRepo);
    }
}
```

### Option B: Rewrite with JBCT (Cleaner)

For a cleaner result, rewrite using JBCT patterns:

```java
package com.example.order;

import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;

public class OrderProcessorSlice implements Slice {

    private final CheckInventory checkInventory;
    private final CalculatePricing calculatePricing;
    private final ProcessPayment processPayment;
    private final CreateOrder createOrder;
    private final SendConfirmation sendConfirmation;

    public OrderProcessorSlice(
            CheckInventory checkInventory,
            CalculatePricing calculatePricing,
            ProcessPayment processPayment,
            CreateOrder createOrder,
            SendConfirmation sendConfirmation) {
        this.checkInventory = checkInventory;
        this.calculatePricing = calculatePricing;
        this.processPayment = processPayment;
        this.createOrder = createOrder;
        this.sendConfirmation = sendConfirmation;
    }

    @Override
    public List<SliceMethod<?, ?>> methods() {
        return List.of(
            SliceMethod.sliceMethod("process", OrderRequest.class, OrderResult.class, this::process)
        );
    }

    // Clean functional pipeline
    private Promise<OrderResult> process(OrderRequest request) {
        return checkInventory.apply(request)
            .flatMap(availability ->
                availability.isAvailable()
                    ? calculatePricing.apply(request)
                    : Promise.success(OrderResult.outOfStock(availability.missing())))
            .flatMap(quote -> processPayment.apply(request.idempotencyKey(), quote))
            .flatMap(payment -> createOrder.apply(request, payment))
            .flatMap(order -> sendConfirmation.apply(order).map(_ -> OrderResult.success(order)));
    }

    @Override
    public Promise<Unit> start() {
        return Promise.success(Unit.unit());
    }

    @Override
    public Promise<Unit> stop() {
        return Promise.success(Unit.unit());
    }
}

// Each step is a simple functional interface
@FunctionalInterface
interface CheckInventory {
    Promise<Availability> apply(OrderRequest request);
}

@FunctionalInterface
interface CalculatePricing {
    Promise<Quote> apply(OrderRequest request);
}

// etc.
```

## Step 4: Deploy the Slice

```bash
# Build the slice
cd slices/order-processor
mvn clean install

# Deploy to Aether cluster
aether deploy com.example:order-processor:1.0.0

# Verify it's running
aether status
# Slices: order-processor (1 instance on node-1)
```

## Step 5: Route Traffic

### Option A: Update Monolith to Call Slice

Create a client in your monolith that calls the Aether slice:

```java
// In your monolith, replace the direct call
@Service
public class OrderService {

    private final AetherClient aether;  // Aether client library

    public OrderResult processOrder(OrderRequest request) {
        // Route to Aether slice instead of local processing
        return aether.invoke(
            "com.example:order-processor:1.0.0",
            "processOrder",
            request,
            OrderResult.class
        ).await(Duration.ofSeconds(30));
    }
}
```

### Option B: Use HTTP Router

If your slice defines HTTP routes, update your load balancer:

```java
// In the slice
@Override
public List<SliceRoute> routes() {
    return List.of(
        SliceRoute.post("/api/orders", "process")
    );
}
```

```nginx
# nginx.conf - route order traffic to Aether
location /api/orders {
    proxy_pass http://aether-cluster:8080;
}

# Everything else goes to monolith
location / {
    proxy_pass http://monolith:8080;
}
```

## Step 6: Scale Independently

Now you can scale the extracted slice:

```bash
# Scale based on load
aether scale order-processor --instances 5

# Add more nodes for capacity
aether node add --address node-4.example.com:4040
aether node add --address node-5.example.com:4040

# Aether distributes slice instances across nodes
aether status
# Nodes: 5
# Slices:
#   order-processor: 5 instances (distributed across 5 nodes)
```

## Common Migration Patterns

### Pattern 1: Database Sharing

Initially, the slice can share the monolith's database:

```java
// Slice uses same database as monolith
public class OrderProcessorSlice implements Slice {
    private final DataSource sharedDataSource;

    public OrderProcessorSlice() {
        // Connect to monolith's database
        this.sharedDataSource = createDataSource(
            System.getenv("DATABASE_URL")
        );
    }
}
```

Later, you can migrate to a separate database if needed.

### Pattern 2: Event Bridge

Use events to decouple from the monolith:

```java
// Slice publishes events
private Promise<OrderResult> process(OrderRequest request) {
    return createOrder.apply(request)
        .onSuccess(order -> eventBus.publish(new OrderCreatedEvent(order)));
}

// Monolith subscribes to events
@EventListener
public void onOrderCreated(OrderCreatedEvent event) {
    // Update local read model, send notifications, etc.
}
```

### Pattern 3: Feature Flag Rollout

Use feature flags for gradual rollout:

```java
// In monolith
public OrderResult processOrder(OrderRequest request) {
    if (featureFlags.isEnabled("use-aether-order-processor", request.customerId())) {
        return aether.invoke("order-processor", "process", request, OrderResult.class).await();
    } else {
        return legacyProcessOrder(request);
    }
}
```

## Handling Transactions

Distributed transactions are complex. Aether recommends:

### Saga Pattern

```java
// Each step is compensatable
private Promise<OrderResult> process(OrderRequest request) {
    return reserveInventory.apply(request)
        .flatMap(reservation ->
            processPayment.apply(request)
                .recoverWith(error -> {
                    // Compensate: release inventory
                    return releaseInventory.apply(reservation)
                        .flatMap(_ -> Promise.failure(error));
                })
        )
        .flatMap(payment -> createOrder.apply(request, payment));
}
```

### Outbox Pattern

```java
// Write to outbox table in same transaction as business data
@Transactional
public Order createOrder(OrderRequest request, Payment payment) {
    var order = orderRepository.save(new Order(request, payment));

    // Outbox entry - processed asynchronously
    outboxRepository.save(new OutboxEvent(
        "OrderCreated",
        objectMapper.writeValueAsString(order)
    ));

    return order;
}
```

## Testing the Migration

### Parallel Run

Run both paths and compare:

```java
public OrderResult processOrder(OrderRequest request) {
    var legacyResult = legacyProcessOrder(request);

    // Shadow call to Aether (async, don't wait)
    aether.invoke("order-processor", "process", request, OrderResult.class)
        .onSuccess(aetherResult -> {
            if (!aetherResult.equals(legacyResult)) {
                log.warn("Result mismatch: legacy={}, aether={}", legacyResult, aetherResult);
            }
        });

    return legacyResult;  // Still use legacy result
}
```

### Forge Chaos Testing

```bash
# Start Forge with your slice
aether forge start

# Generate load
# Kill nodes
# Watch recovery
# Verify no data loss
```

## Rollback Strategy

If something goes wrong:

```bash
# Scale down the slice
aether scale order-processor --instances 0

# Traffic automatically falls back to monolith
# (if you kept the feature flag or fallback logic)
```

## Next Steps

- [Scaling Guide](scaling.md) - Configure auto-scaling
- [Forge Guide](forge-guide.md) - Chaos testing
- [Architecture](../architecture-overview.md) - Deep dive into Aether internals
