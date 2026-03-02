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
        <pragmatica.version>0.19.0</pragmatica.version>
        <jbct.skip>false</jbct.skip>
    </properties>

    <dependencies>
        <!-- Aether slice API (provided by runtime) -->
        <dependency>
            <groupId>org.pragmatica-lite.aether</groupId>
            <artifactId>slice-api</artifactId>
            <version>${pragmatica.version}</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.pragmatica-lite</groupId>
            <artifactId>core</artifactId>
            <version>${pragmatica.version}</version>
            <scope>provided</scope>
        </dependency>

        <!-- Annotation processor (compile-time only) -->
        <dependency>
            <groupId>org.pragmatica-lite.aether</groupId>
            <artifactId>slice-processor</artifactId>
            <version>${pragmatica.version}</version>
            <scope>provided</scope>
        </dependency>

        <!-- Resource API for @Sql, @Http qualifiers -->
        <dependency>
            <groupId>org.pragmatica-lite.aether</groupId>
            <artifactId>resource-api</artifactId>
            <version>${pragmatica.version}</version>
            <scope>provided</scope>
        </dependency>

        <!-- Your existing dependencies for database, etc. -->
    </dependencies>

    <build>
        <plugins>
            <!-- JBCT formatting and linting -->
            <plugin>
                <groupId>org.pragmatica-lite</groupId>
                <artifactId>jbct-maven-plugin</artifactId>
                <version>${pragmatica.version}</version>
                <executions>
                    <execution>
                        <goals>
                            <goal>check</goal>
                            <goal>package-slices</goal>
                            <goal>generate-blueprint</goal>
                            <goal>verify-slice</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

### Option A: Wrap Existing Code (Fastest)

If you want minimal changes, wrap the existing service using `@Slice` and `Promise.lift()`:

```java
package com.example.order;

import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;

/// Wraps the legacy OrderService as an Aether slice.
/// The legacy code runs inside Promise.lift(), which captures any
/// exceptions and converts them to Cause failures automatically.
@Slice
public interface OrderProcessor {

    record ProcessRequest(String idempotencyKey, String customerId, List<String> items) {}
    record ProcessResult(String orderId, String status) {}

    sealed interface OrderProcessorError extends Cause {
        record ProcessingFailed(Throwable cause) implements OrderProcessorError {
            @Override
            public String message() {
                return "Order processing failed: " + Causes.fromThrowable(cause);
            }
        }
    }

    Promise<ProcessResult> process(ProcessRequest request);

    static OrderProcessor orderProcessor() {
        var legacyService = createLegacyService();

        return request -> Promise.lift(
            OrderProcessorError.ProcessingFailed::new,
            () -> callLegacy(legacyService, request)
        );
    }

    private static ProcessResult callLegacy(OrderService legacyService,
                                             ProcessRequest request) {
        var legacyRequest = new OrderRequest(
            request.idempotencyKey(), request.customerId(), request.items()
        );
        var result = legacyService.processOrder(legacyRequest);
        return new ProcessResult(result.getOrderId(), result.getStatus());
    }

    private static OrderService createLegacyService() {
        // Wire up your existing service manually
        // Could use Spring ApplicationContext, manual construction, etc.
        var inventory = new InventoryRepository(/* datasource */);
        var pricing = new PricingService();
        var payments = new PaymentGateway(/* stripe key */);
        var notifications = new NotificationService(/* email config */);
        return new OrderService(inventory, pricing, payments, notifications);
    }
}
```

`Promise.lift()` wraps any exception-throwing code. The legacy service runs as-is;
exceptions become `Cause` failures automatically via the constructor reference
`ProcessingFailed::new`.

### Option B: Rewrite with JBCT (Cleaner)

For a cleaner result, rewrite using `@Slice` with the inline record pattern and
JBCT functional pipelines:

```java
package com.example.order;

import org.pragmatica.aether.resource.db.Sql;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

/// Order processor slice — full JBCT rewrite.
/// Each step is a named method implementing a single pattern.
@Slice
public interface OrderProcessor {

    record ProcessRequest(String idempotencyKey, String customerId, List<String> items) {
        public static Result<ProcessRequest> processRequest(String idempotencyKey,
                                                             String customerId,
                                                             List<String> items) {
            var validKey = Verify.ensure(idempotencyKey, Verify.Is::present,
                                         OrderError.missingIdempotencyKey());
            var validCustomer = Verify.ensure(customerId, Verify.Is::present,
                                              OrderError.missingCustomerId());
            var validItems = Verify.ensure(items, list -> !list.isEmpty(),
                                           OrderError.emptyItems());
            return Result.all(validKey, validCustomer, validItems)
                         .map(ProcessRequest::new);
        }
    }

    record ProcessResult(String orderId, String status) {}

    sealed interface OrderError extends Cause {
        enum General implements OrderError {
            MISSING_IDEMPOTENCY_KEY("Idempotency key is required"),
            MISSING_CUSTOMER_ID("Customer ID is required"),
            EMPTY_ITEMS("Order must have at least one item"),
            OUT_OF_STOCK("Insufficient stock for order"),
            PAYMENT_DECLINED("Payment was declined");

            private final String message;
            General(String message) { this.message = message; }
            @Override public String message() { return message; }
        }

        static OrderError missingIdempotencyKey() { return General.MISSING_IDEMPOTENCY_KEY; }
        static OrderError missingCustomerId() { return General.MISSING_CUSTOMER_ID; }
        static OrderError emptyItems() { return General.EMPTY_ITEMS; }
        static OrderError outOfStock() { return General.OUT_OF_STOCK; }
        static OrderError paymentDeclined() { return General.PAYMENT_DECLINED; }
    }

    Promise<ProcessResult> process(ProcessRequest request);

    static OrderProcessor orderProcessor(@Sql SqlConnector db) {
        record orderProcessor(SqlConnector db) implements OrderProcessor {
            @Override
            public Promise<ProcessResult> process(ProcessRequest request) {
                return checkInventory(request)
                    .flatMap(avail -> calculatePricing(request))
                    .flatMap(quote -> processPayment(request, quote))
                    .flatMap(payment -> createOrder(request, payment));
            }

            private Promise<Boolean> checkInventory(ProcessRequest request) {
                return db.query("SELECT available FROM inventory WHERE item IN (?)",
                                String.join(",", request.items()))
                         .map(OrderProcessor::toAvailability);
            }

            private Promise<String> calculatePricing(ProcessRequest request) {
                return db.query("SELECT price FROM products WHERE id IN (?)",
                                String.join(",", request.items()))
                         .map(OrderProcessor::toQuote);
            }

            private Promise<String> processPayment(ProcessRequest request, String quote) {
                return db.update("INSERT INTO payments (key, customer, amount) VALUES (?, ?, ?)",
                                 request.idempotencyKey(), request.customerId(), quote)
                         .map(OrderProcessor::toPaymentId);
            }

            private Promise<ProcessResult> createOrder(ProcessRequest request, String paymentId) {
                return db.update("INSERT INTO orders (customer, payment_id) VALUES (?, ?)",
                                 request.customerId(), paymentId)
                         .map(rowCount -> new ProcessResult(paymentId, "PLACED"));
            }
        }
        return new orderProcessor(db);
    }

    private static Boolean toAvailability(/* row */) { /* ... */ }
    private static String toQuote(/* row */) { /* ... */ }
    private static String toPaymentId(/* row */) { /* ... */ }
}
```

Each pipeline step is a named method implementing a single Leaf pattern. The `@Sql`
resource qualifier injects the database connector from `aether.toml` configuration.

### Option C: The Peeling Pattern (Incremental)

The peeling pattern bridges Options A and B. Start with a wrapped legacy call, then
incrementally refactor layer by layer. Working code at every step.

**Phase 1: Wrap everything**

```java
@Slice
public interface OrderProcessor {

    record ProcessRequest(String idempotencyKey, String customerId, List<String> items) {}
    record ProcessResult(String orderId, String status) {}

    sealed interface OrderProcessorError extends Cause {
        record ProcessingFailed(Throwable cause) implements OrderProcessorError {
            @Override
            public String message() {
                return "Processing failed: " + Causes.fromThrowable(cause);
            }
        }
    }

    Promise<ProcessResult> process(ProcessRequest request);

    static OrderProcessor orderProcessor() {
        var legacyService = createLegacyService();

        return request -> Promise.lift(
            OrderProcessorError.ProcessingFailed::new,
            () -> callLegacy(legacyService, request)
        );
    }

    // ... legacy wiring helpers ...
}
```

**Phase 2: Peel outer layer into Sequencer**

Refactor the structure, but keep each step wrapped:

```java
static OrderProcessor orderProcessor() {
    var legacyService = createLegacyService();

    record orderProcessor(OrderService legacy) implements OrderProcessor {
        @Override
        public Promise<ProcessResult> process(ProcessRequest request) {
            return validateRequest(request)
                .flatMap(valid -> liftCheckInventory(valid))
                .flatMap(inv -> liftCalculatePricing(inv))
                .flatMap(quote -> liftProcessPayment(request, quote))
                .flatMap(payment -> liftCreateOrder(request, payment));
        }

        private Result<ProcessRequest> validateRequest(ProcessRequest request) {
            // New JBCT validation
            return Verify.ensure(request.customerId(), Verify.Is::present,
                                 OrderError.missingCustomerId())
                         .map(id -> request);
        }

        private Promise<Availability> liftCheckInventory(ProcessRequest request) {
            return Promise.lift(LegacyError::new,
                                () -> legacy.checkInventory(request.items()));
        }

        // ... other lifted steps ...
    }
    return new orderProcessor(legacyService);
}
```

**Phase 3: Peel one step deeper**

Take `liftCheckInventory` and expand it, replacing the legacy call with native JBCT:

```java
private Promise<Availability> checkInventory(ProcessRequest request) {
    return Promise.all(
        Promise.lift(LegacyError::new, () -> legacy.checkWarehouse(request.items())),
        Promise.lift(LegacyError::new, () -> legacy.checkSupplier(request.items()))
    ).map(OrderProcessor::combineAvailability);
}
```

**Phase 4: Continue peeling**

Repeat for each step. Eventually all `lift()` calls disappear:

```java
@Override
public Promise<ProcessResult> process(ProcessRequest request) {
    return validateRequest(request)
        .flatMap(this::checkInventory)
        .flatMap(this::calculatePricing)
        .flatMap(this::processPayment)
        .flatMap(this::createOrder);
}
```

**Benefits:**
- Working code at every phase
- Tests pass continuously
- Stop anywhere -- mixed JBCT and legacy works fine
- `lift()` calls mark remaining legacy code
- Progress is visible and measurable

## Step 4: Deploy the Slice

### Local Development with Forge

For local development, install your slice to the local Maven repository and start Forge:

```bash
# Build and install the slice
cd slices/order-processor
mvn clean install

# Start Forge (if not already running)
./script/aether-forge.sh
```

If Forge is configured with `repositories=["local"]`, it automatically discovers slices
from `~/.m2/repository`.

### Deploy to a Running Cluster

For deployment to a running Aether cluster, use the CLI or Management API:

```bash
# Push artifact from local Maven repo to the cluster's artifact repository
aether artifact push com.example:order-processor:1.0.0

# Apply a blueprint that includes the slice
aether blueprint apply order-system.toml
```

The blueprint file declares the slices and their instance counts:

```toml
# order-system.toml
id = "order-system:1.0.0"

[slices.order_processor]
artifact = "com.example:order-processor:1.0.0"
instances = 3
```

Verify the deployment:

```bash
# Check blueprint status
aether blueprint status order-system:1.0.0

# Or use the Management API directly
curl http://localhost:8080/api/blueprint/order-system:1.0.0/status
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

### Option B: Use HTTP Routing

If your slice defines HTTP routes via `routes.toml`, update your load balancer to route
matching traffic to the Aether cluster:

Create `src/main/resources/routes.toml` in your slice module:

```toml
prefix = "/api/orders"

[routes]
process = "POST /"

[errors]
default = 500
HTTP_400 = ["*Missing*", "*Empty*"]
```

Then configure your reverse proxy:

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

Now you can scale the extracted slice using the CLI or Management API:

```bash
# Scale via CLI (slice must be part of an active blueprint)
aether scale com.example:order-processor:1.0.0 -n 5

# Or scale via Management API
curl -X POST http://localhost:8080/api/scale \
  -H "Content-Type: application/json" \
  -d '{"artifact": "com.example:order-processor:1.0.0", "instances": 5}'
```

Check cluster status:

```bash
# Via CLI
aether status

# Via Management API
curl http://localhost:8080/api/status
curl http://localhost:8080/api/slices/status
```

Adding more nodes for capacity:

```bash
# Start additional nodes and point them to existing peers
./script/aether-node.sh \
  --node-id=node-4 \
  --port=8094 \
  --peers=localhost:8091,localhost:8092,localhost:8093,localhost:8094

# Aether distributes slice instances across all nodes automatically
```

## Common Migration Patterns

### Pattern 1: Database Sharing

Initially, the slice can share the monolith's database using a resource qualifier:

```java
@ResourceQualifier(type = SqlConnector.class, config = "database.shared")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@interface SharedDb {}

@Slice
public interface OrderProcessor {

    // ... request/response records ...

    Promise<ProcessResult> process(ProcessRequest request);

    static OrderProcessor orderProcessor(@SharedDb SqlConnector db) {
        return request -> db.query("SELECT * FROM orders WHERE id = ?", request.orderId())
                            .map(OrderProcessor::toProcessResult);
    }

    private static ProcessResult toProcessResult(/* row */) { /* ... */ }
}
```

Configure the shared database in `aether.toml`:

```toml
[database.shared]
jdbc_url = "jdbc:postgresql://localhost:5432/monolith_db"
username = "app"
password = "secret"
```

Later, you can migrate to a separate database by changing the config section.

### Pattern 2: Event Bridge

Use events to decouple from the monolith:

```java
// Slice publishes events
@Override
public Promise<ProcessResult> process(ProcessRequest request) {
    return createOrder(request)
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
@Override
public Promise<ProcessResult> process(ProcessRequest request) {
    return reserveInventory(request)
        .flatMap(reservation -> processPayment(request, reservation))
        .flatMap(payment -> createOrder(request, payment));
}

private Promise<Reservation> reserveInventory(ProcessRequest request) {
    return db.update("INSERT INTO reservations ...")
             .map(OrderProcessor::toReservation);
}

private Promise<Payment> processPayment(ProcessRequest request, Reservation reservation) {
    return db.update("INSERT INTO payments ...")
             .map(OrderProcessor::toPayment)
             .onFailure(error -> releaseInventory(reservation));
}

private void releaseInventory(Reservation reservation) {
    db.update("DELETE FROM reservations WHERE id = ?", reservation.id());
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

### Forge Testing

Start Forge for local testing with simulated cluster conditions:

```bash
# Start Forge
./script/aether-forge.sh

# Generate load, kill nodes, watch recovery, verify no data loss
# Forge dashboard is available at http://localhost:8888
```

## Rollback Strategy

If something goes wrong, scale the slice down via CLI or Management API:

```bash
# Scale to zero instances via CLI
aether scale com.example:order-processor:1.0.0 -n 0

# Or via Management API
curl -X POST http://localhost:8080/api/scale \
  -H "Content-Type: application/json" \
  -d '{"artifact": "com.example:order-processor:1.0.0", "instances": 0}'

# Or remove the blueprint entirely
aether blueprint delete order-system:1.0.0

# Traffic automatically falls back to monolith
# (if you kept the feature flag or fallback logic)
```

## Next Steps

- [Slice Patterns](slice-patterns.md) - Advanced slice patterns
- [Forge Guide](forge-guide.md) - Chaos testing with Forge
- [Architecture](../contributors/architecture.md) - Deep dive into Aether internals
