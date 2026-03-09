# Slice Patterns

Advanced patterns for building real-world Aether slices with the `@Slice` annotation.

This guide assumes you have completed the [Getting Started](getting-started.md) tutorial and
are familiar with the basics: `@Slice`, factory methods, `Promise<T>`, and sealed `Cause` types.
Each pattern here builds on those fundamentals.

## Single-Method Slices (Lambda Implementation)

When a slice has exactly one method, the interface is a functional interface. The factory
method returns a lambda that directly implements the method.

```java
@Slice
public interface GreetingService {

    record Request(String name) {
        private static final Fn1<Cause, String> NAME_REQUIRED =
            Causes.forOneValue("Name is required, got: '%s'");

        public static Result<Request> request(String name) {
            return Verify.ensure(name, Verify.Is::present, NAME_REQUIRED.apply(name))
                         .map(String::trim)
                         .map(Request::new);
        }
    }

    record Response(String greeting) {}

    Promise<Response> greet(Request request);

    static GreetingService greetingService() {
        return request -> Promise.success(new Response("Hello, " + request.name() + "!"));
    }
}
```

This is the simplest slice form. Use it when:
- The slice exposes a single operation
- No infrastructure dependencies are needed
- The logic fits in a single expression or a call to a named method

## Multi-Method Slices (Inline Record Implementation)

When a slice has two or more methods, lambdas cannot work because they implement exactly
one method. Use an **inline record** inside the factory method:

```java
@Slice
public interface CatalogService {

    record ProductRequest(String productId) {
        public static Result<ProductRequest> productRequest(String productId) {
            return Verify.ensure(productId, Verify.Is::present, CatalogError.missingProductId())
                         .map(ProductRequest::new);
        }
    }

    record Product(String id, String name, String price) {}
    record CategoryList(List<String> categories) {}

    sealed interface CatalogError extends Cause {
        record MissingProductId() implements CatalogError {
            @Override
            public String message() {
                return "Product ID is required";
            }
        }

        record ProductNotFound(String id) implements CatalogError {
            @Override
            public String message() {
                return "Product not found: " + id;
            }
        }

        static CatalogError missingProductId() {
            return new MissingProductId();
        }

        static CatalogError productNotFound(String id) {
            return new ProductNotFound(id);
        }
    }

    Promise<Product> getProduct(ProductRequest request);
    Promise<CategoryList> listCategories();

    static CatalogService catalogService() {
        record catalogService() implements CatalogService {
            private static final Map<String, Product> PRODUCTS = Map.of(
                "P001", new Product("P001", "Widget", "9.99"),
                "P002", new Product("P002", "Gadget", "19.99")
            );

            @Override
            public Promise<Product> getProduct(ProductRequest request) {
                return Option.option(PRODUCTS.get(request.productId()))
                             .async(CatalogError.productNotFound(request.productId()));
            }

            @Override
            public Promise<CategoryList> listCategories() {
                return Promise.success(new CategoryList(List.of("Electronics", "Home")));
            }
        }
        return new catalogService();
    }
}
```

Key points about the inline record pattern:
- The record name matches the factory method name (lowercase first letter)
- The record is private to the factory method — no one can instantiate it directly
- Static fields and helper methods live inside the record
- Each method override follows the one-pattern-per-function rule

## Resource Injection

Slices access infrastructure — databases, HTTP clients, caches — through **resource qualifiers** on factory method parameters. The annotation processor generates all provisioning code. See the [Resource Reference](resource-reference.md) for the full list of built-in qualifiers, configuration options, and API details.

## HTTP Routing via routes.toml

Slices communicate over Aether's internal protocol. To expose a slice as an HTTP API,
add a `routes.toml` file. The annotation processor reads it at compile time and generates
type-safe route bindings — no hand-written controllers.

### Basic Routing

Create `src/main/resources/routes.toml`:

```toml
prefix = "/api/v1/orders"

[routes]
placeOrder  = "POST /"
getOrder    = "GET /{orderId:Long}"
listOrders  = "GET /list?status&limit:Integer"

[errors]
default = 500
HTTP_400 = ["*Invalid*", "*Empty*"]
HTTP_404 = ["*NotFound*"]
```

### Route DSL

The format is `METHOD /path/{param:Type}?queryParam:Type&anotherQuery`:

- **Path parameters** use `{name}` (defaults to `String`) or `{name:Type}`
- **Query parameters** appear after `?`, separated by `&` — always optional (`Option<T>`)
- **Body** is implicit for POST, PUT, and PATCH — the request record is deserialized from JSON

Supported types: `String`, `Integer`, `Long`, `Double`, `Float`, `Boolean`,
`BigDecimal`, `LocalDate`, `LocalDateTime`, `LocalTime`, `OffsetDateTime`, `Duration`.

### Error Mapping

The `[errors]` section maps sealed `Cause` type names to HTTP status codes using glob patterns:

```toml
[errors]
default = 500
HTTP_404 = ["*NotFound*", "*Missing*"]
HTTP_400 = ["*Invalid*", "*Empty*", "*Unsupported*"]
HTTP_409 = ["*Duplicate*", "*AlreadyExists*"]
```

Patterns support `*` wildcards: `*Suffix`, `Prefix*`, `*Contains*`, or `ExactMatch`.
For ambiguous types, use explicit overrides:

```toml
[errors.explicit]
SomeAmbiguousType = 404
```

### Inheriting Common Configuration

Multiple slices can share error mappings via `src/main/resources/routes-base.toml`:

```toml
[errors]
default = 500
HTTP_404 = ["*NotFound*"]
HTTP_400 = ["*Invalid*"]
```

Each slice's `routes.toml` inherits from the base. Child settings take precedence.

## Service Slices (Dependencies on Other Slices)

Slices can depend on other slices. Add the dependency's API JAR as a `provided` dependency,
then include the interface as a factory method parameter (without a resource qualifier annotation).

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>inventory-service-api</artifactId>
    <version>1.0.0</version>
    <scope>provided</scope>
</dependency>
```

```java
@Slice
public interface OrderService {

    record PlaceOrderRequest(String customerId, List<String> items) {
        public static Result<PlaceOrderRequest> placeOrderRequest(String customerId,
                                                                   List<String> items) {
            var validCustomer = Verify.ensure(customerId, Verify.Is::present,
                                              OrderError.missingCustomerId());
            var validItems = Verify.ensure(items, list -> !list.isEmpty(),
                                           OrderError.emptyItems());
            return Result.all(validCustomer, validItems)
                         .map(PlaceOrderRequest::new);
        }
    }

    record OrderResult(String orderId, String status) {}

    sealed interface OrderError extends Cause {
        record MissingCustomerId() implements OrderError {
            @Override
            public String message() {
                return "Customer ID is required";
            }
        }

        record EmptyItems() implements OrderError {
            @Override
            public String message() {
                return "Order must have at least one item";
            }
        }

        record InsufficientStock(String item) implements OrderError {
            @Override
            public String message() {
                return "Insufficient stock for item: " + item;
            }
        }

        static OrderError missingCustomerId() {
            return new MissingCustomerId();
        }

        static OrderError emptyItems() {
            return new EmptyItems();
        }

        static OrderError insufficientStock(String item) {
            return new InsufficientStock(item);
        }
    }

    Promise<OrderResult> placeOrder(PlaceOrderRequest request);

    static OrderService orderService(InventoryService inventory, PricingService pricing) {
        record orderService(InventoryService inventory,
                            PricingService pricing) implements OrderService {
            @Override
            public Promise<OrderResult> placeOrder(PlaceOrderRequest request) {
                return inventory.checkStock(new InventoryService.StockRequest(request.items()))
                                .flatMap(stock -> applyPricing(request, stock));
            }

            private Promise<OrderResult> applyPricing(PlaceOrderRequest request,
                                                       InventoryService.StockResult stock) {
                return pricing.calculate(new PricingService.PriceRequest(request.items()))
                              .map(price -> new OrderResult("ORD-" + System.nanoTime(), "PLACED"));
            }
        }
        return new orderService(inventory, pricing);
    }
}
```

The annotation processor detects that `InventoryService` and `PricingService` are external
slice interfaces (no `@ResourceQualifier`, different package). It generates proxy records
that delegate to `SliceInvokerFacade` for transparent remote invocation.

### Dependency Classification

The processor classifies factory parameters automatically:

| Parameter Pattern | Classification | What Happens |
|-------------------|----------------|--------------|
| `@Sql SqlConnector db` | Resource dependency | Provisioned from `aether.toml` |
| `InventoryService inventory` | Slice dependency | Proxy generated for remote calls |
| `OrderValidator validator` | Plain interface | Factory method called directly |

## Lean Slices (Pure Computation)

Slices that need no dependencies at all — pure functions, validation services, computation
engines — are the simplest to write:

```java
@Slice
public interface HashService {

    record HashRequest(String algorithm, String input) {
        private static final Set<String> SUPPORTED = Set.of("md5", "sha256", "sha512");
        private static final Fn1<Cause, String> UNSUPPORTED_ALGORITHM =
            Causes.forOneValue("Unsupported algorithm: '%s'");

        public static Result<HashRequest> hashRequest(String algorithm, String input) {
            var validAlgorithm = Verify.ensure(algorithm, Verify.Is::present,
                                               HashError.missingAlgorithm())
                                       .map(String::toLowerCase)
                                       .flatMap(alg -> Verify.ensure(alg, SUPPORTED::contains,
                                                                     UNSUPPORTED_ALGORITHM.apply(alg)));
            var validInput = Verify.ensure(input, Verify.Is::present, HashError.missingInput());
            return Result.all(validAlgorithm, validInput)
                         .map(HashRequest::new);
        }
    }

    record HashResponse(String hash, String algorithm) {}

    sealed interface HashError extends Cause {
        record MissingAlgorithm() implements HashError {
            @Override
            public String message() {
                return "Algorithm is required";
            }
        }

        record MissingInput() implements HashError {
            @Override
            public String message() {
                return "Input is required";
            }
        }

        static HashError missingAlgorithm() {
            return new MissingAlgorithm();
        }

        static HashError missingInput() {
            return new MissingInput();
        }
    }

    Promise<HashResponse> computeHash(HashRequest request);

    static HashService hashService() {
        return request -> Result.lift1(Causes::fromThrowable, HashService::digest,
                                       request)
                                .map(hash -> new HashResponse(hash, request.algorithm()))
                                .async();
    }

    @SuppressWarnings("JBCT-EX-01")
    private static String digest(HashRequest request) throws Exception {
        var md = java.security.MessageDigest.getInstance(request.algorithm().toUpperCase());
        var bytes = md.digest(request.input().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(bytes);
    }
}
```

Lean slices have no factory parameters. They are pure computation units that scale
horizontally without any infrastructure setup.

## Error Modeling

All failures in slice code are modeled as sealed `Cause` types — never exceptions.

### Enum Variants for Fixed Messages

When error messages are constants, use an enum:

```java
sealed interface RegistrationError extends Cause {
    enum General implements RegistrationError {
        EMAIL_ALREADY_REGISTERED("Email already registered"),
        TOKEN_GENERATION_FAILED("Token generation failed");

        private final String message;
        General(String message) { this.message = message; }
        @Override public String message() { return message; }
    }
}
```

### Record Variants for Parameterized Messages

When errors carry context, use records:

```java
sealed interface OrderError extends Cause {
    record ItemNotFound(String itemId) implements OrderError {
        @Override
        public String message() {
            return "Item not found: " + itemId;
        }
    }

    record InsufficientFunds(long required, long available) implements OrderError {
        @Override
        public String message() {
            return "Insufficient funds: required " + required + ", available " + available;
        }
    }
}
```

### Using Causes.forOneValue

For reusable error factories that accept a single parameter, use `Causes.forOneValue`
with `%s` format placeholders:

```java
private static final Fn1<Cause, String> USER_NOT_FOUND =
    Causes.forOneValue("User not found: %s");

// Usage
return USER_NOT_FOUND.apply(userId).result();   // Result<T>
return USER_NOT_FOUND.apply(userId).promise();  // Promise<T>
```

### cause.result() and cause.promise()

Always prefer `cause.result()` over `Result.failure(cause)`, and `cause.promise()` over
`Promise.failure(cause)`:

```java
// Preferred
return OrderError.itemNotFound(itemId).result();
return OrderError.itemNotFound(itemId).promise();

// Avoid
return Result.failure(OrderError.itemNotFound(itemId));
return Promise.failure(OrderError.itemNotFound(itemId));
```

## Multi-Parameter Methods

Slice methods can have any number of parameters. At the transport layer, the annotation
processor handles the mapping:

- **0 parameters**: `Unit` at transport layer
- **1 parameter (record)**: passed directly
- **1 parameter (primitive/simple type)**: wrapped in synthetic `{MethodName}Request`
- **N parameters**: wrapped in synthetic `{MethodName}Request` with all fields

```java
@Slice
public interface TransferService {

    record Account(String id) {}
    record TransferResult(String transactionId, String status) {}

    // Zero parameters
    Promise<List<Account>> listAccounts();

    // Single record parameter
    Promise<Account> getAccount(Account request);

    // Multiple parameters — processor generates TransferRequest(String from, String to, long amount)
    Promise<TransferResult> transfer(String from, String to, long amount);

    static TransferService transferService(@Sql SqlConnector db) {
        record transferService(SqlConnector db) implements TransferService {
            @Override
            public Promise<List<Account>> listAccounts() {
                return db.query("SELECT id FROM accounts")
                         .map(TransferService::toAccountList);
            }

            @Override
            public Promise<Account> getAccount(Account request) {
                return db.query("SELECT id FROM accounts WHERE id = ?", request.id())
                         .map(TransferService::toAccount);
            }

            @Override
            public Promise<TransferResult> transfer(String from, String to, long amount) {
                return db.update("INSERT INTO transfers (from_id, to_id, amount) VALUES (?, ?, ?)",
                                 from, to, amount)
                         .map(rowCount -> new TransferResult("TXN-" + System.nanoTime(), "COMPLETED"));
            }
        }
        return new transferService(db);
    }

    private static List<Account> toAccountList(/* rows */) { /* ... */ }
    private static Account toAccount(/* row */) { /* ... */ }
}
```

Method overloads are rejected at compile time. Use distinct method names instead.

## Inline Record Pattern for Dependency Capture

The inline record pattern is the standard way to capture dependencies when the slice
has multiple methods or needs shared state:

```java
static NotificationService notificationService(@Sql SqlConnector db,
                                                @Http HttpClient http,
                                                EmailGateway emailGateway) {
    record notificationService(SqlConnector db,
                                HttpClient http,
                                EmailGateway emailGateway) implements NotificationService {
        @Override
        public Promise<SendResult> sendEmail(EmailRequest request) {
            return emailGateway.send(request.to(), request.subject(), request.body())
                               .flatMap(sent -> logNotification(request, sent));
        }

        @Override
        public Promise<SendResult> sendSms(SmsRequest request) {
            return http.post("/sms", request)
                       .flatMap(sent -> logNotification(request, sent));
        }

        private Promise<SendResult> logNotification(Object request, Object result) {
            return db.update("INSERT INTO notifications (type, payload) VALUES (?, ?)",
                             request.getClass().getSimpleName(), result.toString())
                     .map(rowCount -> new SendResult("OK"));
        }
    }
    return new notificationService(db, http, emailGateway);
}
```

The record captures all dependencies as fields. Each method has access to `db`, `http`, and
`emailGateway` through the record's accessor methods. The record is private to the factory —
callers only see the `NotificationService` interface.

## Pattern Summary

| Pattern | When to Use | Implementation |
|---------|-------------|----------------|
| Lambda | Single method, no deps | `return request -> ...;` |
| Lambda with deps | Single method, has deps | `return request -> dep.call(...);` |
| Inline record, no deps | Multiple methods | `record impl() implements MySlice { ... }` |
| Inline record with deps | Multiple methods + deps | `record impl(Dep dep) implements MySlice { ... }` |
| Resource qualifier | Infrastructure access | `@Sql`, `@Http`, or custom `@ResourceQualifier` |
| Slice dependency | Cross-slice calls | Plain interface parameter (proxy generated) |

## See Also

- [Getting Started](getting-started.md) — build your first slice from scratch
- [Resource Reference](resource-reference.md) — databases, HTTP clients, caching, pub-sub, scheduling
- [Testing Slices](testing-slices.md) — unit and integration testing
- [Migration Guide](migration-guide.md) — migrating existing applications to Aether
