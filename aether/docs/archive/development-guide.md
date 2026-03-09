# Slice Development Guide

Complete workflow for developing Aether slices.

## Slice Design Principles

### Single Responsibility
Each slice exposes one cohesive capability. If you're adding unrelated methods, consider splitting into multiple slices.

```java
// Good: focused on order management
@Slice
public interface OrderService {
    Promise<OrderResult> placeOrder(PlaceOrderRequest request);
    Promise<OrderStatus> getOrderStatus(OrderStatusRequest request);
}

// Bad: mixing concerns
@Slice
public interface OrderService {
    Promise<OrderResult> placeOrder(PlaceOrderRequest request);
    Promise<UserProfile> getUserProfile(UserRequest request);  // Different concern
}
```

### Method Parameters
Slice methods support any number of parameters (0, 1, or more). At the transport layer, they are normalized to a single request object:

- **0 parameters**: `Unit` is used at the transport layer; the handler ignores it
- **1 parameter (record)**: passed directly as the request type
- **1 parameter (primitive)**: wrapped in a synthetic `{MethodName}Request` record
- **N parameters**: wrapped in a synthetic `{MethodName}Request` record with all parameters as fields

```java
// Zero parameters - Unit at transport layer
Promise<HealthStatus> healthCheck();

// Single record parameter - direct pass-through
Promise<OrderResult> placeOrder(PlaceOrderRequest request);

// Single primitive - synthetic GetStockRequest(String sku) generated
Promise<Stock> getStock(String sku);

// Multiple parameters - synthetic TransferRequest(String from, String to, BigDecimal amount) generated
Promise<TransferResult> transfer(String from, String to, BigDecimal amount);
```

Method overloads are rejected at compile time. Use distinct method names instead.

### Promise Return Types
All methods return `Promise<T>`. This enables:
- Non-blocking execution
- Proper error propagation
- Composition with other async operations

```java
@Override
public Promise<OrderResult> placeOrder(PlaceOrderRequest request) {
    return inventory.checkStock(new StockRequest(request.items()))
                    .flatMap(stock -> {
                        if (!stock.available()) {
                            return Promise.failure(Causes.cause("Out of stock"));
                        }
                        return processOrder(request);
                    });
}
```

## Adding Dependencies

### Resource Dependencies (Infrastructure)

To depend on infrastructure resources (databases, HTTP clients, etc.):

1. Define a qualifier annotation with `@ResourceQualifier`:
```java
@ResourceQualifier(type = DatabaseConnector.class, config = "database.primary")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface PrimaryDb {}
```

2. Annotate the factory parameter:
```java
@Slice
public interface OrderRepository {
    Promise<OrderResult> findOrder(FindOrderRequest request);

    static OrderRepository orderRepository(@PrimaryDb DatabaseConnector db) {
        return new orderRepository(db);
    }
}
```

3. Configure the resource in `aether.toml`:
```toml
[database.primary]
driver = "postgresql"
jdbc_url = "jdbc:postgresql://localhost:5432/orders"
username = "app"
password = "secret"
```

**What happens at build time:**
- Annotation processor detects `@PrimaryDb` as a `@ResourceQualifier` meta-annotation
- Extracts resource type (`DatabaseConnector.class`) and config section (`"database.primary"`)
- Generates `ctx.resources().provide(DatabaseConnector.class, "database.primary")` in the factory

**What happens at runtime:**
- Aether discovers a `ResourceFactory<DatabaseConnector, ...>` via SPI
- Loads config from `aether.toml` section `database.primary`
- Creates and caches the resource instance
- Injects it into the slice factory

### External Dependencies (Other Slices)

To depend on another slice:

1. Add the API JAR as a `provided` dependency:
```xml
<dependency>
    <groupId>org.example</groupId>
    <artifactId>inventory-service-api</artifactId>
    <version>1.0.0</version>
    <scope>provided</scope>
</dependency>
```

2. Use the dependency in your implementation via the inline record pattern:
```java
@Slice
public interface OrderService {
    Promise<OrderResult> placeOrder(PlaceOrderRequest request);

    static OrderService orderService(InventoryService inventory,
                                     PricingEngine pricing) {
        record orderService(InventoryService inventory,
                            PricingEngine pricing) implements OrderService {
            @Override
            public Promise<OrderResult> placeOrder(PlaceOrderRequest request) {
                return inventory.reserve(new ReserveRequest(request.items()))
                                .flatMap(reserved -> pricing.calculate(new PriceRequest(reserved)))
                                .map(priced -> new OrderResult(priced.orderId(), priced.total()));
            }
        }
        return new orderService(inventory, pricing);
    }
}
```

**What happens at build time:**
- Annotation processor detects `InventoryService` and `PricingEngine` as external (different base package)
- Generates proxy records inside `OrderServiceFactory`
- Proxies delegate to `SliceInvokerFacade` for remote calls

### Internal Dependencies (Same Module)

Dependencies within your module's package hierarchy are "internal":

```java
// org.example.order.OrderService depends on
// org.example.order.validation.OrderValidator

@Slice
public interface OrderService {
    Promise<OrderResult> placeOrder(PlaceOrderRequest request);

    static OrderService orderService(OrderValidator validator) {
        record orderService(OrderValidator validator) implements OrderService {
            @Override
            public Promise<OrderResult> placeOrder(PlaceOrderRequest request) {
                return validator.validate(request)
                                .flatMap(valid -> processOrder(valid));
            }
        }
        return new orderService(validator);
    }
}
```

Internal dependencies:
- Factory calls the dependency's factory method directly
- No proxy generation
- No network overhead

### Plain Interface Dependencies

Non-`@Slice` interfaces with static factory methods are "plain interface" dependencies. They are constructed by calling their factory method directly.

```java
// Plain interface - not a @Slice, has a factory method
public interface OrderValidator {
    Promise<Boolean> validate(String orderId);

    static OrderValidator orderValidator() {
        return new orderValidator();
    }
}

@Slice
public interface OrderService {
    Promise<OrderResult> placeOrder(PlaceOrderRequest request);

    static OrderService orderService(OrderValidator validator) {
        return new orderService(validator);
    }
}
```

**What happens at build time:**
- Processor detects `OrderValidator` has a factory method → plain interface
- Generated factory calls `OrderValidator.orderValidator()` directly
- No proxy, no network overhead

#### Plain Interfaces with Resource Parameters

If a plain interface's factory method has `@ResourceQualifier`-annotated parameters, those resources are provisioned transitively:

```java
public interface KycStep {
    Promise<Boolean> verify(String customerId);

    static KycStep kycStep(@KycProvider HttpClient httpClient) {
        return new kycStep(httpClient);
    }
}

@Slice
public interface LoanService {
    Promise<LoanResult> processLoan(LoanRequest request);

    static LoanService loanService(KycStep kycStep) {
        return new loanService(kycStep);
    }
}
```

The processor introspects `KycStep.kycStep()`, discovers `@KycProvider HttpClient`, provisions the resource, and passes it:
```java
// Generated: provisions HttpClient, then passes to KycStep factory
ctx.resources().provide(HttpClient.class, "http.kyc")
// ...
var kycStep = KycStep.kycStep(kycStep_httpClient);
```

### Dependency Classification

The processor classifies factory parameters into three categories:

| Dependency | Characteristics | Classification |
|-----------|----------------|----------------|
| `@PrimaryDb DatabaseConnector db` | Has @ResourceQualifier annotation | Resource dependency |
| `InventoryService inventory` | External interface, no factory method | Slice dependency (proxied) |
| `OrderValidator validator` | Has static factory method | Plain interface dependency |
| `KycStep kycStep` | Has factory with @ResourceQualifier params | Plain interface (transitive resources) |

## Multiple Slices in One Module

A single Maven module can contain multiple slices. Each slice **must** live in its own
package — the annotation processor enforces this at compile time. If two `@Slice` interfaces
share a package, the build fails with a clear error message suggesting separate packages.

### Adding a Slice with jbct

Use `jbct add-slice` to scaffold a new slice into an existing project:

```bash
jbct add-slice Analytics
```

This creates the slice in a sub-package (`analytics/`) with all required files:

| Generated File | Purpose |
|----------------|---------|
| `src/main/java/.../analytics/Analytics.java` | Slice interface + implementation |
| `src/test/java/.../analytics/AnalyticsTest.java` | Unit test |
| `src/main/resources/routes.toml` → `analytics/routes.toml` | HTTP route definitions |
| `src/main/resources/slices/Analytics.toml` | Runtime config (instances, timeout) |
| `src/main/resources/META-INF/dependencies/...Analytics` | Dependency manifest |

The command reads your `pom.xml` to determine the base package automatically. After adding, build and verify:

```bash
mvn clean verify
```

```
commerce/
└── src/main/java/org/example/
    ├── order/
    │   └── OrderService.java      # @Slice
    ├── payment/
    │   └── PaymentService.java    # @Slice
    └── shipping/
        └── ShippingService.java   # @Slice
```

Each `@Slice` interface generates:
- Its own API interface in `.api` subpackage
- Its own factory class
- Its own manifest in `META-INF/slice/`

The Maven plugin packages each as separate artifacts:
- `commerce-order-service-api.jar`
- `commerce-order-service.jar`
- `commerce-payment-service-api.jar`
- `commerce-payment-service.jar`
- `commerce-shipping-service-api.jar`
- `commerce-shipping-service.jar`

### Inter-Slice Dependencies

Slices in the same module can depend on each other:

```java
@Slice
public interface OrderService {
    Promise<OrderResult> placeOrder(PlaceOrderRequest request);

    static OrderService orderService(PaymentService payments,
                                     ShippingService shipping) {
        record orderService(PaymentService payments,
                            ShippingService shipping) implements OrderService {
            @Override
            public Promise<OrderResult> placeOrder(PlaceOrderRequest request) {
                return payments.charge(request)
                               .flatMap(paid -> shipping.schedule(paid));
            }
        }
        return new orderService(payments, shipping);
    }
}
```

These are classified as internal dependencies because they share the same base package structure. The blueprint generator handles topological ordering.

## Request/Response Design

### Use Records
Records are ideal for request/response types:

```java
public record PlaceOrderRequest(
    String customerId,
    List<LineItem> items,
    ShippingAddress address
) {}

public record OrderResult(
    String orderId,
    OrderStatus status,
    Instant createdAt
) {}
```

### Immutability
All request/response types must be immutable. The runtime serializes/deserializes them across the network.

### Validation

Use parse-don't-validate with the `Verify` API and `Result` types:

```java
public record PlaceOrderRequest(String customerId, List<LineItem> items) {
    public static Result<PlaceOrderRequest> placeOrderRequest(String customerId,
                                                               List<LineItem> items) {
        var validCustomer = Verify.ensure(customerId, Verify.Is::present,
                                          OrderError.missingCustomerId());
        var validItems = Verify.ensure(items, list -> !list.isEmpty(),
                                       OrderError.emptyItems());
        return Result.all(validCustomer, validItems)
                     .map(PlaceOrderRequest::new);
    }
}
```

This follows the parse-don't-validate pattern from the [Getting Started](getting-started.md) guide — invalid objects can never exist because the factory method returns `Result<T>`, not the raw type.

## Testing Slices

### Unit Testing

Test the implementation directly:

```java
class OrderServiceTest {
    @Test
    void should_place_order_successfully() {
        var inventory = mock(InventoryService.class);
        var pricing = mock(PricingEngine.class);

        when(inventory.reserve(any()))
            .thenReturn(Promise.success(new ReserveResult("RES-123")));
        when(pricing.calculate(any()))
            .thenReturn(Promise.success(new PriceResult("ORD-456", 99.99)));

        var service = OrderService.orderService(inventory, pricing);
        var request = new PlaceOrderRequest("CUST-1", List.of(item), address);

        var result = service.placeOrder(request).await();

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.unwrap().orderId()).isEqualTo("ORD-456");
    }
}
```

### Integration Testing

Test via the generated factory:

```java
class OrderServiceIntegrationTest {
    @Test
    void should_wire_dependencies() {
        var invoker = mock(SliceInvokerFacade.class);
        when(invoker.invoke(anyString(), eq("reserve"), any(), any()))
            .thenReturn(Promise.success(new ReserveResult("RES-123")));

        var result = OrderServiceFactory.create(Aspect.identity(), invoker).await();

        assertThat(result.isSuccess()).isTrue();
    }
}
```

## Error Handling

### Use Promise.failure()

For business errors, return failed promises using sealed `Cause` types:

```java
@Override
public Promise<OrderResult> placeOrder(PlaceOrderRequest request) {
    if (request.items().isEmpty()) {
        return Promise.failure(OrderCause.EMPTY_ORDER);
    }

    return inventory.checkStock(stockRequest)
                    .flatMap(stock -> {
                        if (!stock.sufficient()) {
                            return Promise.failure(OrderCause.insufficientStock(stock));
                        }
                        return completeOrder(request);
                    });
}
```

### Cause Types

Define sealed `Cause` hierarchies instead of exceptions:

```java
public sealed interface OrderCause extends Cause {
    OrderCause EMPTY_ORDER = new EmptyOrder();
    static OrderCause insufficientStock(StockStatus stock) { return new InsufficientStock(stock); }

    record EmptyOrder() implements OrderCause {
        public String message() { return "Order must have items"; }
    }
    record InsufficientStock(StockStatus stock) implements OrderCause {
        public String message() { return "Insufficient stock: " + stock; }
    }
    record PaymentDeclined() implements OrderCause {
        public String message() { return "Payment was declined"; }
    }
}
```

## Slice Configuration

Each slice can have a configuration file that controls runtime properties like instance count and timeout.

### Config File Location

`src/main/resources/slices/{SliceName}.toml`

### Example Configuration

```toml
# src/main/resources/slices/OrderService.toml

[blueprint]
instances = 3
timeout_ms = 30000
memory_mb = 512
load_balancing = "round_robin"
affinity_key = "customerId"
```

### Available Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `instances` | int | `1` | Number of slice instances |
| `timeout_ms` | int | - | Request timeout in milliseconds |
| `memory_mb` | int | - | Memory per instance |
| `load_balancing` | string | - | `round_robin` or `least_connections` |
| `affinity_key` | string | - | Request field for sticky routing |

### When Config is Missing

If no config file exists, default values are used (logged as info). This is intentional - you don't need a config file for simple slices.

### Pub-Sub Messaging

Slices can subscribe to topics for event-driven communication. A subscription handler receives messages published to a topic.

**1. Define a subscription annotation:**
```java
@ResourceQualifier(type = Subscriber.class, config = "messaging.orders")
@Retention(RUNTIME) @Target(METHOD)
public @interface OrderEvents {}
```

**2. Annotate a handler method:**
```java
@Slice
public interface OrderProcessor {
    @OrderEvents
    Promise<Unit> handleOrderEvent(OrderEvent event);
}
```

**3. Configure the topic in TOML:**
```toml
# aether.toml
[messaging.orders]
topic = "order-events"
```

Subscription methods must return `Promise<Unit>`. The runtime registers the handler with the cluster pub-sub system and routes messages to any node with the slice loaded.

### Adding Events with jbct

Use `jbct add-event` to generate pub-sub event scaffolding:

```bash
jbct add-event order-placed
```

This generates:
- `@OrderPlacedPublisher` annotation for the publishing slice
- `@OrderPlacedSubscription` annotation for subscribing slices
- Auto-appends a `[messaging.order-placed]` section to `aether.toml`

**Wire the publisher** into the publishing slice:

```java
@Slice
public interface OrderService {
    Promise<OrderResult> placeOrder(PlaceOrderRequest request);

    static OrderService orderService(@OrderPlacedPublisher Publisher<OrderEvent> publisher) {
        record orderService(Publisher<OrderEvent> publisher) implements OrderService {
            @Override
            public Promise<OrderResult> placeOrder(PlaceOrderRequest request) {
                return processOrder(request)
                    .onSuccess(result -> publisher.publish(new OrderEvent(result.orderId())));
            }
        }
        return new orderService(publisher);
    }
}
```

**Wire the subscriber** into the subscribing slice:

```java
@Slice
public interface AnalyticsService {
    @OrderPlacedSubscription
    Promise<Unit> handleOrderPlaced(OrderEvent event);

    static AnalyticsService analyticsService(@Sql SqlConnector db) {
        record analyticsService(SqlConnector db) implements AnalyticsService {
            @Override
            public Promise<Unit> handleOrderPlaced(OrderEvent event) {
                return db.update("INSERT INTO analytics_events ...", event.orderId())
                         .map(_ -> Unit.unit());
            }
        }
        return new analyticsService(db);
    }
}
```

### Scheduled Invocation

Slices can declare methods for periodic execution using interval or cron scheduling.

**1. Define a schedule annotation:**
```java
@ResourceQualifier(type = Scheduled.class, config = "scheduling.cleanup")
@Retention(RUNTIME) @Target(METHOD)
public @interface CleanupSchedule {}
```

**2. Annotate a zero-parameter method:**
```java
@Slice
public interface OrderService {
    Promise<OrderResult> placeOrder(PlaceOrderRequest request);

    @CleanupSchedule
    Promise<Unit> cleanupExpiredOrders();
}
```

**3. Configure the schedule in TOML:**
```toml
# aether.toml
[scheduling.cleanup]
interval = "5m"       # fixed-rate: "30s", "5m", "1h", "1d"
leaderOnly = true     # only the leader node triggers (default: true)
```

Or use cron expressions:
```toml
# aether.toml
[scheduling.report]
cron = "0 0 * * *"    # standard 5-field: min hour dom month dow
leaderOnly = true
```

**Method constraints** (validated at compile time):
- Zero parameters
- Returns `Promise<Unit>`

**Execution modes:**
- `leaderOnly = true` (default): only the leader node starts the timer; invocation routes to any node with the slice
- `leaderOnly = false`: every node with the slice runs its own timer

Schedules are stored in the cluster KV-Store and can be updated at runtime via the Management API (`PUT /api/scheduled-tasks/{configSection}`).

## Build Workflow

### Standard Build

```bash
mvn verify
```

Runs:
1. `compile` - Compiles sources, triggers annotation processor
2. `test` - Runs unit tests
3. `package` - Creates JAR (and slice-specific JARs via `jbct:package-slices`)
4. `verify` - Runs `jbct:check` for formatting/linting

### Generate Blueprint

```bash
./generate-blueprint.sh
# or
mvn package jbct:generate-blueprint -DskipTests
```

### Local Development Cycle

```bash
# Make changes, then deploy to Forge:
./deploy-forge.sh
```

Forge automatically detects changes in your local Maven repository and reloads.

### Verify Slice Configuration

```bash
mvn jbct:verify-slice
```

Checks:
- `@Slice` interface has factory method
- Factory method returns the interface type
- All methods return `Promise<T>`
- No overloaded method names

## IDE Setup

### IntelliJ IDEA

Enable annotation processing:
1. Settings → Build, Execution, Deployment → Compiler → Annotation Processors
2. Check "Enable annotation processing"
3. Set "Production sources directory" to `target/generated-sources/annotations`

### Generated Sources

The processor generates files to `target/generated-sources/annotations/`. If your IDE doesn't recognize them:

```bash
mvn compile
# Then refresh project in IDE
```

## Best Practices

### Naming Conventions

| Element | Convention | Example |
|---------|------------|---------|
| Slice interface | `{Noun}Service` | `OrderService` |
| Factory method | `camelCase(interface)` | `orderService(...)` |
| Request type | `{Action}Request` | `PlaceOrderRequest` |
| Response type | `{Noun}Result` or `{Noun}Response` | `OrderResult` |
| Package | `org.{company}.{domain}` | `org.example.order` |

### Slice Granularity

**Too coarse:**
- Single slice with 20+ methods
- Mixed concerns (order + user + payment)
- Hard to scale independently

**Too fine:**
- One slice per method
- Excessive network overhead
- Complex dependency graphs

**Right-sized:**
- 3-7 related methods
- Single bounded context
- Clear responsibility

### Version Management

Use semantic versioning for slice APIs:
- **Major**: Breaking changes to request/response types
- **Minor**: New methods, backward-compatible changes
- **Patch**: Bug fixes, internal changes

External consumers depend on your API JAR. Breaking changes require major version bump.
