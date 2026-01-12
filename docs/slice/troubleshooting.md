# Slice Troubleshooting

Common issues and their solutions.

## Build Errors

### "No factory method found"

**Error:**
```
error: @Slice interface OrderService has no factory method
```

**Cause:** The `@Slice` interface is missing a static factory method.

**Fix:** Add a factory method that returns the interface type:
```java
@Slice
public interface OrderService {
    Promise<OrderResult> placeOrder(PlaceOrderRequest request);

    // Add this
    static OrderService orderService() {
        return new OrderServiceImpl();
    }
}
```

### "Factory method returns wrong type"

**Error:**
```
error: Factory method 'create' returns OtherService, expected OrderService
```

**Cause:** The factory method doesn't return the interface type.

**Fix:** Ensure factory returns the interface:
```java
// Wrong
static OtherService create() { ... }

// Correct
static OrderService orderService() { ... }
```

### "Method must return Promise"

**Error:**
```
error: Method 'placeOrder' must return Promise<T>, found: OrderResult
```

**Cause:** Slice methods must return `Promise<T>`.

**Fix:**
```java
// Wrong
OrderResult placeOrder(PlaceOrderRequest request);

// Correct
Promise<OrderResult> placeOrder(PlaceOrderRequest request);
```

### "Method must have exactly one parameter"

**Error:**
```
error: Method 'placeOrder' must have exactly one parameter, found: 2
```

**Cause:** Slice methods must have exactly one parameter.

**Fix:** Wrap parameters in a request record:
```java
// Wrong
Promise<OrderResult> placeOrder(String customerId, List<LineItem> items);

// Correct
public record PlaceOrderRequest(String customerId, List<LineItem> items) {}
Promise<OrderResult> placeOrder(PlaceOrderRequest request);
```

### "Generated sources not found"

**Error:**
```
error: cannot find symbol
  symbol: class OrderServiceFactory
```

**Cause:** IDE not recognizing generated sources.

**Fix:**
1. Run `mvn compile`
2. In IntelliJ: Right-click `target/generated-sources/annotations` → Mark Directory as → Generated Sources Root
3. Rebuild project in IDE

### "Annotation processor not running"

**Error:** No `META-INF/slice/` directory in `target/classes`

**Cause:** Processor not configured or dependency missing.

**Fix:** Ensure pom.xml has:
```xml
<dependency>
    <groupId>org.pragmatica.jbct</groupId>
    <artifactId>slice-processor</artifactId>
    <version>${jbct.version}</version>
    <scope>provided</scope>
</dependency>
```

And compiler plugin doesn't disable annotation processing:
```xml
<plugin>
    <artifactId>maven-compiler-plugin</artifactId>
    <!-- Don't set <proc>none</proc> -->
</plugin>
```

## Packaging Errors

### "No slice manifests found"

**Error:**
```
[INFO] No slice manifests found - skipping blueprint generation
```

**Cause:** No `META-INF/slice/*.manifest` files exist.

**Fix:**
1. Verify `@Slice` annotation is on interface
2. Check annotation processor ran: `ls target/classes/META-INF/slice/`
3. Rebuild: `mvn clean compile`

### "Failed to load manifest"

**Error:**
```
error: Failed to load: target/classes/META-INF/slice/OrderService.manifest
```

**Cause:** Corrupted or incomplete manifest file.

**Fix:**
```bash
rm -rf target/classes/META-INF/slice/
mvn clean compile
```

### "Circular dependency detected"

**Error:**
```
error: Circular dependency detected: org.example:order-service:1.0.0
```

**Cause:** Slices depend on each other in a cycle.

**Fix:** Refactor to break the cycle:
```
A → B → C → A  # Circular

# Fix: Extract common functionality
A → D
B → D
C → D
```

## Deployment Errors

### "Connection refused to Forge"

**Error:**
```
Failed to deploy: Connection refused (localhost:8080)
```

**Cause:** Forge not running.

**Fix:**
```bash
aether-forge start
# Wait for startup, then deploy
./deploy-forge.sh
```

### "Slice not found"

**Error:**
```
SliceNotFoundException: org.example:order-service:1.0.0
```

**Cause:** Slice JAR not deployed or wrong artifact coordinates.

**Fix:**
1. Check deployed slices: `curl http://localhost:8080/slices`
2. Verify artifact coordinates in blueprint match JAR
3. Redeploy: `./deploy-forge.sh`

### "Dependency version mismatch"

**Error:**
```
error: Unresolved dependency version: org.example:inventory-service:api
```

**Cause:** External dependency version not in `slice-deps.properties`.

**Fix:**
1. Add dependency to pom.xml with `provided` scope:
```xml
<dependency>
    <groupId>org.example</groupId>
    <artifactId>inventory-service-api</artifactId>
    <version>1.0.0</version>
    <scope>provided</scope>
</dependency>
```

2. Run: `mvn jbct:collect-slice-deps compile`

### "Blueprint not found"

**Error:**
```
FileNotFoundException: target/blueprint.toml
```

**Cause:** Blueprint not generated.

**Fix:**
```bash
./generate-blueprint.sh
# or
mvn package jbct:generate-blueprint -DskipTests
```

## Runtime Errors

### "Serialization failed"

**Error:**
```
SerializationException: Cannot serialize OrderRequest
```

**Cause:** Request/response type not serializable.

**Common causes:**
- Non-serializable field (e.g., `Connection`, `InputStream`)
- Missing no-arg constructor (for non-record types)
- Circular references

**Fix:** Use records with serializable fields:
```java
// Wrong
public record BadRequest(Connection connection) {}

// Correct
public record GoodRequest(String connectionId) {}
```

### "Method not found"

**Error:**
```
MethodNotFoundException: checkStock on org.example:inventory-service:1.0.0
```

**Cause:** Method name mismatch or API version incompatible.

**Fix:**
1. Verify method exists in target slice
2. Check API version matches: deployed vs. dependency in pom.xml
3. Redeploy target slice with correct version

### "Timeout waiting for response"

**Error:**
```
TimeoutException: Timeout after 30000ms waiting for org.example:inventory-service:1.0.0
```

**Cause:** Target slice not responding in time.

**Possible causes:**
- Target slice overloaded
- Network issues
- Deadlock in slice code

**Fix:**
1. Check target slice health: `curl http://localhost:8080/slices/org.example:inventory-service:1.0.0/health`
2. Increase timeout if needed:
```toml
[[slices]]
artifact = "org.example:order-service:1.0.0"
timeout_ms = 60000
```
3. Check for blocking operations in slice code

### "Circuit breaker open"

**Error:**
```
CircuitBreakerOpenException: org.example:inventory-service:1.0.0
```

**Cause:** Too many failures to target slice.

**Fix:**
1. Check target slice health
2. Wait for circuit breaker to half-open (default 10s)
3. Fix underlying issue in target slice

### "Promise already completed"

**Error:**
```
IllegalStateException: Promise already completed
```

**Cause:** Trying to complete a Promise twice.

**Common causes:**
- Callback called multiple times
- Both success and failure paths executed

**Fix:** Ensure single completion path:
```java
// Wrong
return inventory.reserve(request)
    .map(result -> new OrderResult(result))
    .recover(error -> new OrderResult("failed"));  // Both complete if success

// Correct
return inventory.reserve(request)
    .map(result -> new OrderResult(result.id()))
    .mapFailure(error -> new OrderException(error.getMessage()));
```

## IDE Issues

### IntelliJ: "Cannot resolve symbol"

For generated classes (`*Factory`, `*.api.*`):

1. Build project: `mvn compile`
2. File → Invalidate Caches → Restart
3. Right-click project → Maven → Reimport
4. Mark generated sources:
   - Right-click `target/generated-sources/annotations`
   - Mark Directory as → Generated Sources Root

### IntelliJ: Annotation processor not running

1. Settings → Build → Compiler → Annotation Processors
2. Enable "Enable annotation processing"
3. Set "Store generated sources relative to:" → Module content root
4. Set "Production sources directory:" → `target/generated-sources/annotations`

### Eclipse: Generated sources missing

1. Right-click project → Properties
2. Java Compiler → Annotation Processing → Enable
3. Factory Path → Add JARs → Add `slice-processor` JAR
4. Rebuild project

## Testing Issues

### "Mock not returning expected value"

**Error:**
```
NullPointerException in OrderServiceImpl.placeOrder
```

**Cause:** Mock not configured for the call.

**Fix:** Configure mock to return Promise:
```java
// Wrong - returns null
when(inventory.reserve(any())).thenReturn(null);

// Correct
when(inventory.reserve(any()))
    .thenReturn(Promise.successful(new ReserveResult("RES-123")));
```

### "Factory requires SliceInvokerFacade"

**Error:**
```
Cannot invoke factory - missing invoker parameter
```

**Fix:** Provide mock invoker in integration tests:
```java
var invoker = mock(SliceInvokerFacade.class);
when(invoker.invoke(anyString(), anyString(), any(), any()))
    .thenReturn(Promise.successful(expectedResponse));

var slice = OrderServiceFactory.create(Aspect.identity(), invoker)
                               .await()
                               .unwrap();
```

## Common Mistakes

### Returning completed Promise in implementation

```java
// Wrong - blocks the thread
@Override
public Promise<OrderResult> placeOrder(PlaceOrderRequest request) {
    var result = processOrder(request);  // Blocking call
    return Promise.successful(result);
}

// Correct - non-blocking
@Override
public Promise<OrderResult> placeOrder(PlaceOrderRequest request) {
    return Promise.promise(resolver -> {
        processOrderAsync(request, result -> resolver.succeed(result));
    });
}
```

### Not propagating failures

```java
// Wrong - swallows failure
return inventory.reserve(request)
    .map(result -> processOrder(result))
    .recover(error -> null);  // Hides the error

// Correct - propagate or handle explicitly
return inventory.reserve(request)
    .map(result -> processOrder(result));
// Failures propagate automatically

// Or handle explicitly
return inventory.reserve(request)
    .map(result -> processOrder(result))
    .recover(error -> {
        log.error("Reservation failed", error);
        return new OrderResult(OrderStatus.FAILED, error.getMessage());
    });
```

### Mutable state in slice

```java
// Wrong - race condition with multiple instances
public class OrderServiceImpl implements OrderService {
    private int orderCount = 0;  // Mutable shared state!

    @Override
    public Promise<OrderResult> placeOrder(PlaceOrderRequest request) {
        orderCount++;  // Race condition
        return Promise.successful(new OrderResult("ORD-" + orderCount));
    }
}

// Correct - use external state store
public class OrderServiceImpl implements OrderService {
    private final OrderRepository repository;

    @Override
    public Promise<OrderResult> placeOrder(PlaceOrderRequest request) {
        return repository.nextOrderId()
                         .map(id -> new OrderResult(id));
    }
}
```

## Getting Help

1. Check logs: `tail -f ~/.aether/logs/forge.log`
2. Enable debug: `mvn compile -X`
3. Verify configuration: `mvn jbct:verify-slice`
4. Report issues: https://github.com/pragmatica/jbct-cli/issues
