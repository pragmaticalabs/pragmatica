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

### Single-Parameter Methods
Every slice method takes exactly one request parameter. This enables:
- Uniform serialization/deserialization
- Consistent logging and metrics
- Versioning via request evolution

```java
// Correct
Promise<OrderResult> placeOrder(PlaceOrderRequest request);

// Wrong - multiple parameters
Promise<OrderResult> placeOrder(String customerId, List<LineItem> items);
```

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
                            return Promise.failed(new OutOfStockException());
                        }
                        return processOrder(request);
                    });
}
```

## Adding Dependencies

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

2. Add the interface to your factory method:
```java
@Slice
public interface OrderService {
    Promise<OrderResult> placeOrder(PlaceOrderRequest request);

    static OrderService orderService(InventoryService inventory,
                                     PricingEngine pricing) {
        return new OrderServiceImpl(inventory, pricing);
    }
}
```

3. Use the dependency in your implementation:
```java
public class OrderServiceImpl implements OrderService {
    private final InventoryService inventory;
    private final PricingEngine pricing;

    public OrderServiceImpl(InventoryService inventory, PricingEngine pricing) {
        this.inventory = inventory;
        this.pricing = pricing;
    }

    @Override
    public Promise<OrderResult> placeOrder(PlaceOrderRequest request) {
        return inventory.reserve(new ReserveRequest(request.items()))
                        .flatMap(reserved -> pricing.calculate(new PriceRequest(reserved)))
                        .map(priced -> new OrderResult(priced.orderId(), priced.total()));
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
        return new OrderServiceImpl(validator);
    }
}
```

Internal dependencies:
- Factory calls the dependency's factory method directly
- No proxy generation
- No network overhead

### Dependency Classification

The processor uses package names to classify:

| Dependency Package | Slice Package | Classification |
|-------------------|---------------|----------------|
| `org.example.order.validation` | `org.example.order` | Internal (starts with slice's package) |
| `org.example.inventory` | `org.example.order` | External (different base) |
| `com.other.service` | `org.example.order` | External |

## Multiple Slices in One Module

A single Maven module can contain multiple slices:

```
commerce/
└── src/main/java/org/example/
    ├── order/
    │   ├── OrderService.java      # @Slice
    │   └── OrderServiceImpl.java
    ├── payment/
    │   ├── PaymentService.java    # @Slice
    │   └── PaymentServiceImpl.java
    └── shipping/
        ├── ShippingService.java   # @Slice
        └── ShippingServiceImpl.java
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
        return new OrderServiceImpl(payments, shipping);
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
Validate in your implementation, not in records:

```java
@Override
public Promise<OrderResult> placeOrder(PlaceOrderRequest request) {
    if (request.items().isEmpty()) {
        return Promise.failed(new ValidationException("Order must have items"));
    }
    // ... process order
}
```

Or use parse-don't-validate with Result types:

```java
public static Result<ValidatedOrder> validate(PlaceOrderRequest request) {
    return Result.all(
        validateItems(request.items()),
        validateAddress(request.address())
    ).map(ValidatedOrder::new);
}
```

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
            .thenReturn(Promise.successful(new ReserveResult("RES-123")));
        when(pricing.calculate(any()))
            .thenReturn(Promise.successful(new PriceResult("ORD-456", 99.99)));

        var service = new OrderServiceImpl(inventory, pricing);
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
            .thenReturn(Promise.successful(new ReserveResult("RES-123")));

        var result = OrderServiceFactory.create(Aspect.identity(), invoker).await();

        assertThat(result.isSuccess()).isTrue();
    }
}
```

## Error Handling

### Use Promise.failed()

For business errors, return failed promises:

```java
@Override
public Promise<OrderResult> placeOrder(PlaceOrderRequest request) {
    if (request.items().isEmpty()) {
        return Promise.failed(new EmptyOrderException());
    }

    return inventory.checkStock(stockRequest)
                    .flatMap(stock -> {
                        if (!stock.sufficient()) {
                            return Promise.failed(new InsufficientStockException(stock));
                        }
                        return completeOrder(request);
                    });
}
```

### Exception Types

Define clear exception hierarchies:

```java
public sealed class OrderException extends RuntimeException {
    public static final class EmptyOrderException extends OrderException {}
    public static final class InsufficientStockException extends OrderException {
        private final StockStatus stock;
        // ...
    }
    public static final class PaymentDeclinedException extends OrderException {}
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
- All methods have exactly one parameter

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
